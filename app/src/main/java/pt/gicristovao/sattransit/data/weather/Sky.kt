package pt.gicristovao.sattransit.data.weather

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class SkySource { IPMA, OPEN_METEO }
enum class SkyStep { HOURLY, THREE_HOURLY, DAILY }
enum class SkyVerdict { GOOD, UNCERTAIN, BAD, INDICATIVE }

data class CloudLayers(val total: Int, val low: Int, val mid: Int, val high: Int)

/** Uma coluna da faixa de horas à volta do trânsito. */
data class SkySlot(val time: Instant, val ipmaType: Int?, val cloudTotal: Int?, val isMain: Boolean)

/** O céu previsto para um trânsito, pronto a mostrar. */
data class SkyForecast(
    val source: SkySource,
    val step: SkyStep,
    val verdict: SkyVerdict,
    /** Hora a que a previsão se refere (a hora cheia mais próxima; na diária, o próprio trânsito). */
    val time: Instant,
    /** Tipo de tempo do IPMA (1–30), só em Portugal. */
    val ipmaType: Int?,
    /** % de nuvens por camada (Open-Meteo; também em Portugal, como complemento). */
    val layers: CloudLayers?,
    val rainPct: Int?,
    val windKmh: Int?,
    /** Graus, de onde sopra o vento. */
    val windFromDeg: Double?,
    val slots: List<SkySlot>,
    val ipmaPlace: String?,
    val ipmaKm: Double?,
    val updated: Instant?,
    /** Só na diária: a partir de quando há previsão de 3 em 3 h e de hora a hora para a hora do trânsito. */
    val threeHourlyFrom: Instant?,
    val hourlyFrom: Instant?,
)

sealed interface SkyResult {
    data class Ready(val f: SkyForecast) : SkyResult
    /** Trânsito ainda longe de mais: há previsão a partir de [from]. */
    data class Later(val from: Instant) : SkyResult
    /** Sem dados (sem rede, ou fora do alcance das duas fontes). */
    data object Unavailable : SkyResult
}

/** Os dados de previsão de um local: IPMA (só em Portugal) e Open-Meteo. */
class PlaceWeather(
    val ipmaPlace: LocalPerto?,
    val ipma: List<Entrada>?,
    val om: List<OmHora>?,
    val fetchedAt: Instant,
) {
    fun at(at: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): SkyResult =
        Sky.build(this, at, now, zone)
}

object Sky {
    /** Só se mostra previsão até 10 dias antes (o alcance da diária do IPMA). */
    val MAX_AHEAD: Duration = Duration.ofDays(10)
    /** Open-Meteo para lá de 6 dias: «Indicativo», como a diária do IPMA. */
    val OM_INDICATIVE_AFTER: Duration = Duration.ofDays(6)
    /** Até esta distância da localidade IPMA mais próxima, usa-se o IPMA. */
    const val IPMA_MAX_KM = 20.0

    private val HOUR = Duration.ofHours(1)
    private val THREE_HOURS = Duration.ofHours(3)

    fun build(w: PlaceWeather, at: Instant, now: Instant, zone: ZoneId): SkyResult {
        if (at.isBefore(now.minus(HOUR))) return SkyResult.Unavailable
        if (Duration.between(now, at) > MAX_AHEAD) return SkyResult.Later(at.minus(MAX_AHEAD))
        val om = w.om?.let { pickOm(it, at) }
        val place = w.ipmaPlace
        val ipma = w.ipma
        if (place != null && ipma != null) {
            pickIpma(ipma, at, now, zone)?.let { p ->
                return SkyResult.Ready(p.copy(layers = om?.layers, ipmaPlace = place.nome, ipmaKm = place.km))
            }
        }
        if (om != null) {
            val v = if (Duration.between(now, at) > OM_INDICATIVE_AFTER) SkyVerdict.INDICATIVE else om.verdict
            return SkyResult.Ready(om.copy(verdict = v, updated = w.fetchedAt))
        }
        return SkyResult.Unavailable
    }

    private fun roundHour(t: Instant): Instant = t.plus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.HOURS)

    private fun absDist(a: Instant, b: Instant): Duration = Duration.between(a, b).abs()

    /** IPMA: horária exacta, senão a de 3 h mais próxima (até ±1,5 h), senão a diária do dia. */
    internal fun pickIpma(entries: List<Entrada>, at: Instant, now: Instant, zone: ZoneId): SkyForecast? {
        val short = entries.filter { (it.periodo == 1 || it.periodo == 3) && it.tipo != null }
        val hour = roundHour(at)
        val main = short.firstOrNull { it.periodo == 1 && it.instante == hour }
            ?: short.filter { it.periodo == 3 && absDist(it.instante, at) <= Duration.ofMinutes(90) }
                .minByOrNull { absDist(it.instante, at) }

        if (main != null) {
            val step = if (main.periodo == 1) HOUR else THREE_HOURS
            val slots = (-2..2).mapNotNull { k ->
                val t = main.instante.plus(step.multipliedBy(k.toLong()))
                short.filter { it.instante == t }.minByOrNull { it.periodo }?.let { SkySlot(t, it.tipo, null, k == 0) }
            }
            // a probabilidade de chuva de uma entrada refere-se às 3 horas anteriores: (t − 3 h, t]
            val rain = entries.filter {
                (it.periodo == 1 || it.periodo == 3) && it.chuva != null &&
                    !it.instante.isBefore(at) && it.instante.minus(THREE_HOURS).isBefore(at)
            }.minByOrNull { it.instante }?.chuva?.let { Math.round(it).toInt() }
            val type = main.tipo!!
            return SkyForecast(
                SkySource.IPMA, if (main.periodo == 1) SkyStep.HOURLY else SkyStep.THREE_HOURLY,
                verdictIpma(type, rain), main.instante, type, null, rain,
                main.vento?.let { Math.round(it).toInt() }, windDegrees(main.direcaoVento), slots,
                null, null, main.atualizado, null, null,
            )
        }

        val day = at.atZone(zone).toLocalDate()
        val d = entries.firstOrNull { it.periodo == 24 && it.dia == day && it.tipo != null } ?: return null
        fun from(periodo: Int): Instant? {
            val last = entries.filter { it.periodo == periodo }.maxOfOrNull { it.instante } ?: return null
            if (!last.isAfter(now)) return null
            return at.minus(Duration.between(now, last))
        }
        return SkyForecast(
            SkySource.IPMA, SkyStep.DAILY, SkyVerdict.INDICATIVE, at, d.tipo, null,
            d.chuva?.let { Math.round(it).toInt() }, null, null, emptyList(),
            null, null, d.atualizado, from(3), from(1),
        )
    }

    /** Open-Meteo: a hora mais próxima (até ±31 min) e duas para cada lado. */
    internal fun pickOm(hours: List<OmHora>, at: Instant): SkyForecast? {
        val valid = hours.filter { it.total != null }
        val main = valid.minByOrNull { absDist(it.instante, at) }
            ?.takeIf { absDist(it.instante, at) <= Duration.ofMinutes(31) } ?: return null
        val layers = CloudLayers(main.total!!, main.baixas ?: 0, main.medias ?: 0, main.altas ?: 0)
        val slots = (-2..2).mapNotNull { k ->
            val t = main.instante.plus(HOUR.multipliedBy(k.toLong()))
            valid.firstOrNull { it.instante == t }?.let { SkySlot(t, null, it.total, k == 0) }
        }
        return SkyForecast(
            SkySource.OPEN_METEO, SkyStep.HOURLY, verdictOm(layers, main.chuva), main.instante, null, layers,
            main.chuva, main.vento?.let { Math.round(it).toInt() }, main.direcaoVento, slots,
            null, null, null, null, null,
        )
    }

    /**
     * Avaliação pelo tipo de tempo do IPMA. Bom = limpo ou pouco nublado e chuva < 10% (critério da webapp).
     * Nuvens altas, parcialmente nublado e geada (céu normalmente limpo) = Incerto. O resto = Mau.
     */
    fun verdictIpma(type: Int, rainPct: Int?): SkyVerdict {
        val rain = rainPct ?: 0
        val base = when (type) {
            1, 2 -> SkyVerdict.GOOD
            3, 5, 22, 25 -> SkyVerdict.UNCERTAIN
            else -> SkyVerdict.BAD
        }
        return when {
            rain >= 50 -> SkyVerdict.BAD
            base == SkyVerdict.GOOD && rain >= 10 -> SkyVerdict.UNCERTAIN
            else -> base
        }
    }

    /**
     * Avaliação pelas % do Open-Meteo. As nuvens baixas e médias tapam o disco; as altas costumam ser finas.
     * Bom: baixas e médias ≤ 20%, altas ≤ 30%, chuva < 20%. Mau: baixas ou médias ≥ 70%, total ≥ 90% ou chuva ≥ 50%.
     */
    fun verdictOm(l: CloudLayers, rainPct: Int?): SkyVerdict {
        val rain = rainPct ?: 0
        val lowMid = maxOf(l.low, l.mid)
        return when {
            lowMid >= 70 || l.total >= 90 || rain >= 50 -> SkyVerdict.BAD
            lowMid <= 20 && l.high <= 30 && rain < 20 -> SkyVerdict.GOOD
            else -> SkyVerdict.UNCERTAIN
        }
    }

    /** "NE" → 45°. Aceita as letras em inglês e em português (O/SO/NO). */
    fun windDegrees(dir: String?): Double? = when (dir?.trim()?.uppercase()) {
        "N" -> 0.0
        "NE" -> 45.0
        "E" -> 90.0
        "SE" -> 135.0
        "S" -> 180.0
        "SW", "SO" -> 225.0
        "W", "O" -> 270.0
        "NW", "NO" -> 315.0
        else -> null
    }
}
