package pt.gicristovao.sattransit.data.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * IPMA — previsão por localidade. Sem chave.
 * Adaptado da classe Ipma.kt da webapp da Gi Cristóvão Photography (mesmo método, em produção desde 9 nov 2026):
 * as 423 localidades de previsão do ipma.pt, a mais próxima por Haversine, e o ficheiro «aggregate» de cada uma
 * (horária ~3 dias, de 3 em 3 h até ~6 dias, diária até 10 dias).
 * Os ficheiros «public-data» não têm documentação oficial: são os que o próprio ipma.pt usa e podem mudar sem aviso.
 */

data class LocalIpma(val id: Int, val nome: String, val lat: Double, val lon: Double)
data class LocalPerto(val id: Int, val nome: String, val km: Double)

/** Uma entrada do ficheiro aggregate. periodo: 1 = horária, 3 = de 3 em 3 h, 24 = diária. null = sem dado (-99). */
data class Entrada(
    val instante: Instant,          // dataPrev convertido de UTC
    val dia: LocalDate,             // os 10 primeiros caracteres de dataPrev (útil nas diárias)
    val periodo: Int,
    val tipo: Int?,                 // idTipoTempo
    val chuva: Double?,             // probabilidadePrecipita (%), refere-se às 3 h anteriores
    val vento: Double?,             // ffVento (km/h)
    val direcaoVento: String?,      // ddVento (N, NE, …)
    val atualizado: Instant?,       // dataUpdate (UTC)
)

class IpmaErro(msg: String, causa: Throwable? = null) : Exception(msg, causa)

class Ipma(private val cacheDir: File) {

    companion object {
        const val BASE = "https://api.ipma.pt/public-data/forecast/"

        private const val LOCAIS_MS = 7L * 24 * 3600 * 1000      // a lista quase não muda
        private const val PREVISAO_MS = 30L * 60 * 1000           // o IPMA actualiza poucas vezes por dia

        val DISTRITOS = mapOf(
            1 to "Aveiro", 2 to "Beja", 3 to "Braga", 4 to "Bragança", 5 to "Castelo Branco", 6 to "Coimbra",
            7 to "Évora", 8 to "Faro", 9 to "Guarda", 10 to "Leiria", 11 to "Lisboa", 12 to "Portalegre",
            13 to "Porto", 14 to "Santarém", 15 to "Setúbal", 16 to "Viana do Castelo", 17 to "Vila Real", 18 to "Viseu"
        )

        /** Distância em linha recta (Haversine), em km. */
        fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = Math.PI / 180
            val dLat = (lat2 - lat1) * r
            val dLon = (lon2 - lon1) * r
            val x = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * r) * cos(lat2 * r) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * 6371 * asin(sqrt(x))
        }

        /** "18.6" → 18.6; "-99.0", vazio ou ausente → null. */
        fun num(o: JSONObject, campo: String): Double? {
            if (!o.has(campo) || o.isNull(campo)) return null
            val n = o.optString(campo).toDoubleOrNull() ?: return null
            return if (n > -90) n else null
        }

        /** dataPrev/dataUpdate vêm em UTC sem «Z» ("2026-11-09T02:00:00"). */
        fun instante(data: String): Instant =
            Instant.parse(if (Regex("(Z|[+-]\\d{2}:?\\d{2})$").containsMatchIn(data)) data else data + "Z")

        /** Converte o texto de locations.json em localidades «Distrito, Localidade». */
        fun lerLocais(texto: String): List<LocalIpma> {
            val j = JSONArray(texto)
            val out = ArrayList<LocalIpma>(j.length())
            for (i in 0 until j.length()) {
                val o = j.getJSONObject(i)
                val id = o.optInt("globalIdLocal", -1)
                val lat = o.optString("latitude").toDoubleOrNull()
                val lon = o.optString("longitude").toDoubleOrNull()
                if (id < 0 || lat == null || lon == null) continue
                val dist = DISTRITOS[o.optInt("idDistrito")] ?: ""
                val local = o.optString("local").trim()
                val nome = if (dist.isNotEmpty() && !local.startsWith(dist)) "$dist, $local" else local
                out.add(LocalIpma(id, nome, lat, lon))
            }
            return out
        }

        /** Converte o texto do aggregate em entradas (sem rede; usado nos testes). */
        fun lerAgregado(texto: String): List<Entrada> {
            val j = JSONArray(texto)
            val out = ArrayList<Entrada>(j.length())
            for (i in 0 until j.length()) {
                val o = j.getJSONObject(i)
                val dp = o.optString("dataPrev")
                if (dp.length < 10) continue
                val t = try { instante(dp) } catch (e: Exception) { continue }
                val up = o.optString("dataUpdate").takeIf { it.length >= 19 }?.let { runCatching { instante(it) }.getOrNull() }
                out.add(
                    Entrada(
                        instante = t, dia = LocalDate.parse(dp.substring(0, 10)), periodo = o.optInt("idPeriodo"),
                        tipo = num(o, "idTipoTempo")?.toInt()?.takeIf { it in 1..30 },
                        chuva = num(o, "probabilidadePrecipita"),
                        vento = num(o, "ffVento"), direcaoVento = o.optString("ddVento").ifEmpty { null },
                        atualizado = up,
                    )
                )
            }
            return out.sortedWith(compareBy<Entrada>({ it.instante }, { it.periodo }))
        }

        /** A localidade mais próxima de uma lista (tudo em memória). */
        fun maisProximo(locais: List<LocalIpma>, lat: Double, lon: Double): LocalPerto? =
            locais.minByOrNull { km(lat, lon, it.lat, it.lon) }?.let { LocalPerto(it.id, it.nome, km(lat, lon, it.lat, it.lon)) }
    }

    private var locais: List<LocalIpma>? = null
    private val previsoes = HashMap<Int, Pair<Long, List<Entrada>>>()

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val c = try { URI(url).toURL().openConnection() as HttpURLConnection } catch (e: Exception) { throw IpmaErro("sem ligação ao IPMA", e) }
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "SatTransit (Android)")
            val code = try { c.responseCode } catch (e: Exception) { throw IpmaErro("sem ligação ao IPMA", e) }
            if (code != 200) throw IpmaErro("IPMA devolveu $code")
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** As localidades de previsão. Guardadas em disco por 7 dias; sem rede, serve a cópia antiga. */
    suspend fun locais(): List<LocalIpma> {
        locais?.let { return it }
        val f = File(cacheDir, "ipma_locations.json")
        val idade = System.currentTimeMillis() - f.lastModified()
        if (f.exists() && idade < LOCAIS_MS) {
            runCatching { lerLocais(f.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { locais = it; return it }
        }
        val txt = try { get(BASE + "locations.json") } catch (e: IpmaErro) {
            if (f.exists()) runCatching { lerLocais(f.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { locais = it; return it }
            throw e
        }
        val lista = try { lerLocais(txt) } catch (e: Exception) { throw IpmaErro("lista de locais do IPMA inesperada", e) }
        if (lista.isEmpty()) throw IpmaErro("lista de locais do IPMA vazia")
        withContext(Dispatchers.IO) { runCatching { f.writeText(txt) } }
        locais = lista
        return lista
    }

    suspend fun maisProximo(lat: Double, lon: Double): LocalPerto? = maisProximo(locais(), lat, lon)

    /** Ficheiro aggregate de uma localidade (cache de 30 min em memória). */
    suspend fun previsao(id: Int): List<Entrada> {
        val agora = System.currentTimeMillis()
        previsoes[id]?.let { if (agora - it.first < PREVISAO_MS) return it.second }
        val txt = get(BASE + "aggregate/$id.json")
        val lista = try { lerAgregado(txt) } catch (e: Exception) { throw IpmaErro("previsão do IPMA inesperada", e) }
        previsoes[id] = agora to lista
        return lista
    }
}
