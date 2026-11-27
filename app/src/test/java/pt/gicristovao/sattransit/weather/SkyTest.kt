package pt.gicristovao.sattransit.weather

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.gicristovao.sattransit.data.weather.CloudLayers
import pt.gicristovao.sattransit.data.weather.Ipma
import pt.gicristovao.sattransit.data.weather.LocalPerto
import pt.gicristovao.sattransit.data.weather.OpenMeteo
import pt.gicristovao.sattransit.data.weather.PlaceWeather
import pt.gicristovao.sattransit.data.weather.Sky
import pt.gicristovao.sattransit.data.weather.SkyResult
import pt.gicristovao.sattransit.data.weather.SkySource
import pt.gicristovao.sattransit.data.weather.SkyStep
import pt.gicristovao.sattransit.data.weather.SkyVerdict
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class SkyTest {

    private val zone = ZoneId.of("Europe/Lisbon")
    private val now = Instant.parse("2026-11-27T10:00:00Z")
    private val update = "2026-11-27T07:55:48"

    private fun dp(t: Instant) = t.toString().removeSuffix("Z")

    /** Um aggregate do IPMA no formato real: horária até 29/11 00h, 3 em 3 h até 2/12 00h, diária até 6/12. */
    private fun aggregate(): String {
        val a = JSONArray()
        var t = Instant.parse("2026-11-27T00:00:00Z")
        val endH = Instant.parse("2026-11-29T00:00:00Z")
        val special = mapOf("2026-11-28T10:00:00Z" to 3, "2026-11-28T13:00:00Z" to 1, "2026-11-28T14:00:00Z" to 5)
        while (!t.isAfter(endH)) {
            val h = t.atZone(ZoneId.of("UTC")).hour
            a.put(JSONObject().apply {
                put("tMed", "13.6"); put("hR", "92.3"); put("dataUpdate", update); put("utci", "12.4")
                put("ffVento", "9.4"); put("ddVento", "NE"); put("globalIdLocal", 1131721)
                put("idTipoTempo", special[t.toString()] ?: 2)
                put("probabilidadePrecipita", if (h % 3 == 0) "5.0" else "-99.0")
                put("idPeriodo", 1); put("dataPrev", dp(t))
            })
            t = t.plus(Duration.ofHours(1))
        }
        t = Instant.parse("2026-11-29T03:00:00Z")
        while (!t.isAfter(Instant.parse("2026-12-02T00:00:00Z"))) {
            a.put(JSONObject().apply {
                put("tMed", "12.0"); put("dataUpdate", update); put("ffVento", "15.0"); put("ddVento", "O")
                put("idTipoTempo", 5); put("probabilidadePrecipita", "20.0"); put("idPeriodo", 3); put("dataPrev", dp(t))
            })
            t = t.plus(Duration.ofHours(3))
        }
        for (d in 27..36) {
            val day = if (d <= 30) "2026-11-%02d".format(d) else "2026-12-%02d".format(d - 30)
            a.put(JSONObject().apply {
                put("tMin", "10.0"); put("tMax", "16.0"); put("dataUpdate", update); put("idTipoTempo", 3)
                put("probabilidadePrecipita", "20.0"); put("idPeriodo", 24); put("dataPrev", day + "T00:00:00")
            })
        }
        return a.toString()
    }

    private fun openMeteo(low: Int, mid: Int, high: Int, total: Int): String {
        val time = JSONArray(); val tc = JSONArray(); val l = JSONArray(); val m = JSONArray(); val hi = JSONArray()
        val rain = JSONArray(); val ws = JSONArray(); val wd = JSONArray()
        var t = Instant.parse("2026-11-27T00:00:00Z")
        repeat(16 * 24) {
            time.put(t.epochSecond); tc.put(total); l.put(low); m.put(mid); hi.put(high); rain.put(0); ws.put(6.2); wd.put(270)
            t = t.plus(Duration.ofHours(1))
        }
        return JSONObject().put("hourly", JSONObject().apply {
            put("time", time); put("cloud_cover", tc); put("cloud_cover_low", l); put("cloud_cover_mid", m)
            put("cloud_cover_high", hi); put("precipitation_probability", rain); put("wind_speed_10m", ws); put("wind_direction_10m", wd)
        }).toString()
    }

    private val place = LocalPerto(1131721, "Porto, Praia de Salgueiros", 2.3)
    private fun ipmaOnly() = PlaceWeather(place, Ipma.lerAgregado(aggregate()), null, now)

    private fun ready(r: SkyResult) = (r as? SkyResult.Ready)?.f ?: throw AssertionError("esperava Ready, veio $r")

    @Test
    fun lerAgregado_trataMenos99ComoSemDado() {
        val e = Ipma.lerAgregado(aggregate())
        val h1 = e.first { it.periodo == 1 && it.instante == Instant.parse("2026-11-27T01:00:00Z") }
        assertNull(h1.chuva)
        assertEquals(9.4, h1.vento!!, 1e-9)
        assertEquals(Instant.parse("2026-11-27T07:55:48Z"), h1.atualizado)
        assertEquals(10, e.count { it.periodo == 24 })
    }

    @Test
    fun horaria_horaDoTransito() {
        val f = ready(ipmaOnly().at(Instant.parse("2026-11-28T12:00:00.400Z"), now, zone))
        assertEquals(SkySource.IPMA, f.source)
        assertEquals(SkyStep.HOURLY, f.step)
        assertEquals(Instant.parse("2026-11-28T12:00:00Z"), f.time)
        assertEquals(2, f.ipmaType)
        assertEquals(5, f.rainPct)                     // entrada das 15h cobre 12–15h
        assertEquals(SkyVerdict.GOOD, f.verdict)
        assertEquals(listOf(3, 2, 2, 1, 5), f.slots.map { it.ipmaType })
        assertTrue(f.slots[2].isMain)
        assertEquals(45.0, f.windFromDeg!!, 1e-9)
        assertEquals("Porto, Praia de Salgueiros", f.ipmaPlace)
        assertNull(f.layers)
    }

    @Test
    fun arredondaParaAHoraMaisProxima() {
        val f = ready(ipmaOnly().at(Instant.parse("2026-11-28T13:40:00Z"), now, zone))
        assertEquals(Instant.parse("2026-11-28T14:00:00Z"), f.time)
        assertEquals(5, f.ipmaType)
        assertEquals(SkyVerdict.UNCERTAIN, f.verdict)  // nuvens altas
    }

    @Test
    fun deTresEmTresHoras() {
        val f = ready(ipmaOnly().at(Instant.parse("2026-11-30T21:10:00Z"), now, zone))
        assertEquals(SkyStep.THREE_HOURLY, f.step)
        assertEquals(Instant.parse("2026-11-30T21:00:00Z"), f.time)
        assertEquals(5, f.slots.size)
        assertEquals(Instant.parse("2026-11-30T15:00:00Z"), f.slots.first().time)
        assertEquals(270.0, f.windFromDeg!!, 1e-9)    // "O" em português
    }

    @Test
    fun soDiaria_indicativaEComDatas() {
        val at = Instant.parse("2026-12-05T14:28:51Z")
        val f = ready(ipmaOnly().at(at, now, zone))
        assertEquals(SkyStep.DAILY, f.step)
        assertEquals(SkyVerdict.INDICATIVE, f.verdict)
        assertEquals(3, f.ipmaType)
        assertEquals(20, f.rainPct)
        assertTrue(f.slots.isEmpty())
        // horária vai até 29/11 00h (38 h depois de agora); 3 em 3 h até 2/12 00h
        assertEquals(at.minus(Duration.between(now, Instant.parse("2026-11-29T00:00:00Z"))), f.hourlyFrom)
        assertEquals(at.minus(Duration.between(now, Instant.parse("2026-12-02T00:00:00Z"))), f.threeHourlyFrom)
    }

    @Test
    fun longeDeMais_dizQuandoHavera() {
        val at = Instant.parse("2026-12-10T12:57:08Z")
        val r = ipmaOnly().at(at, now, zone)
        assertEquals(SkyResult.Later(at.minus(Duration.ofDays(10))), r)
    }

    @Test
    fun foraDePortugal_openMeteo() {
        val w = PlaceWeather(null, null, OpenMeteo.ler(openMeteo(5, 40, 10, 45)), now)
        val f = ready(w.at(Instant.parse("2026-11-30T21:14:08Z"), now, zone))
        assertEquals(SkySource.OPEN_METEO, f.source)
        assertEquals(CloudLayers(45, 5, 40, 10), f.layers)
        assertEquals(SkyVerdict.UNCERTAIN, f.verdict)
        assertEquals(listOf(19, 20, 21, 22, 23), f.slots.map { it.time.atZone(ZoneId.of("UTC")).hour })
        assertEquals(now, f.updated)
    }

    @Test
    fun openMeteoAlemDe6Dias_indicativo() {
        val w = PlaceWeather(null, null, OpenMeteo.ler(openMeteo(0, 0, 0, 0)), now)
        assertEquals(SkyVerdict.INDICATIVE, ready(w.at(Instant.parse("2026-12-04T12:00:00Z"), now, zone)).verdict)
        assertEquals(SkyVerdict.GOOD, ready(w.at(Instant.parse("2026-12-02T12:00:00Z"), now, zone)).verdict)
    }

    @Test
    fun emPortugal_ipmaComCamadasDoOpenMeteo() {
        val w = PlaceWeather(place, Ipma.lerAgregado(aggregate()), OpenMeteo.ler(openMeteo(0, 10, 20, 25)), now)
        val f = ready(w.at(Instant.parse("2026-11-28T12:00:00Z"), now, zone))
        assertEquals(SkySource.IPMA, f.source)
        assertEquals(SkyVerdict.GOOD, f.verdict)       // a avaliação continua a ser a do IPMA
        assertNotNull(f.layers)
        assertEquals(20, f.layers!!.high)
    }

    @Test
    fun ipmaFalhou_usaOpenMeteo() {
        val w = PlaceWeather(null, null, OpenMeteo.ler(openMeteo(80, 0, 0, 80)), now)
        assertEquals(SkyVerdict.BAD, ready(w.at(Instant.parse("2026-11-28T12:00:00Z"), now, zone)).verdict)
    }

    @Test
    fun semDados() {
        assertEquals(SkyResult.Unavailable, PlaceWeather(null, null, null, now).at(Instant.parse("2026-11-28T12:00:00Z"), now, zone))
        assertEquals(SkyResult.Unavailable, ipmaOnly().at(Instant.parse("2026-11-26T12:00:00Z"), now, zone))
    }

    @Test
    fun avaliacoes() {
        assertEquals(SkyVerdict.GOOD, Sky.verdictIpma(1, 0))
        assertEquals(SkyVerdict.UNCERTAIN, Sky.verdictIpma(2, 10))
        assertEquals(SkyVerdict.BAD, Sky.verdictIpma(5, 60))
        assertEquals(SkyVerdict.BAD, Sky.verdictIpma(4, 0))
        assertEquals(SkyVerdict.UNCERTAIN, Sky.verdictIpma(22, 0))
        assertEquals(SkyVerdict.GOOD, Sky.verdictOm(CloudLayers(20, 10, 20, 30), 0))
        assertEquals(SkyVerdict.UNCERTAIN, Sky.verdictOm(CloudLayers(50, 0, 0, 50), 0))
        assertEquals(SkyVerdict.BAD, Sky.verdictOm(CloudLayers(70, 70, 0, 0), 0))
        assertNull(Sky.windDegrees("??"))
    }
}
