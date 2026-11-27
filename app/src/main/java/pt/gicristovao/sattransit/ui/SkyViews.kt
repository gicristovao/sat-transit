package pt.gicristovao.sattransit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import pt.gicristovao.sattransit.R
import pt.gicristovao.sattransit.data.weather.SkyForecast
import pt.gicristovao.sattransit.data.weather.SkyResult
import pt.gicristovao.sattransit.data.weather.SkySource
import pt.gicristovao.sattransit.data.weather.SkyStep
import pt.gicristovao.sattransit.data.weather.SkyVerdict
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val SkyGood = Color(0xFF8FD3A6)
private val SkyUncertain = Color(0xFFE9C46A)
private val SkyBad = Color(0xFFE88A7A)
private val CloudColor = Color(0xFFC9D3E6)

fun skyVerdictColor(v: SkyVerdict): Color = when (v) {
    SkyVerdict.GOOD -> SkyGood
    SkyVerdict.UNCERTAIN -> SkyUncertain
    SkyVerdict.BAD -> SkyBad
    SkyVerdict.INDICATIVE -> CloudColor
}

/** Símbolo + palavra, para não depender só da cor. */
@Composable
fun skyVerdictLabel(v: SkyVerdict): String = when (v) {
    SkyVerdict.GOOD -> "● " + stringResource(R.string.sky_good)
    SkyVerdict.UNCERTAIN -> "◐ " + stringResource(R.string.sky_uncertain)
    SkyVerdict.BAD -> "✕ " + stringResource(R.string.sky_bad)
    SkyVerdict.INDICATIVE -> "◌ " + stringResource(R.string.sky_indicative)
}

enum class WxKind { CLEAR, FEW, PARTLY, CLOUD, HIGH, RAIN, STORM, FOG, SNOW }

fun ipmaKind(type: Int): WxKind = when (type) {
    1, 22 -> WxKind.CLEAR
    2 -> WxKind.FEW
    3, 24, 25 -> WxKind.PARTLY
    4, 27 -> WxKind.CLOUD
    5 -> WxKind.HIGH
    16, 17, 26 -> WxKind.FOG
    18, 28, 29, 30 -> WxKind.SNOW
    19, 20, 23 -> WxKind.STORM
    else -> WxKind.RAIN
}

private fun omKind(total: Int, rain: Int?): WxKind = when {
    (rain ?: 0) >= 50 -> WxKind.RAIN
    total < 15 -> WxKind.CLEAR
    total < 40 -> WxKind.FEW
    total < 70 -> WxKind.PARTLY
    else -> WxKind.CLOUD
}

private fun DrawScope.cloud(l: Float, t: Float, r: Float, b: Float, stroke: Float, color: Color) {
    val w = r - l
    val h = b - t
    val p = Path().apply { addRoundRect(RoundRect(l, t + h * 0.45f, r, b, CornerRadius(h * 0.28f))) }
    val c1 = Path().apply { addOval(Rect(Offset(l + w * 0.38f, t + h * 0.45f), w * 0.24f)) }
    val c2 = Path().apply { addOval(Rect(Offset(l + w * 0.66f, t + h * 0.55f), w * 0.17f)) }
    val u1 = Path().apply { op(p, c1, PathOperation.Union) }
    val u = Path().apply { op(u1, c2, PathOperation.Union) }
    drawPath(u, Color(0xFF161B26))
    drawPath(u, color, style = Stroke(width = stroke))
}

private fun DrawScope.sunOrMoon(cx: Float, cy: Float, r: Float, stroke: Float, night: Boolean, rays: Boolean) {
    if (night) {
        val full = Path().apply { addOval(Rect(Offset(cx, cy), r * 1.1f)) }
        val cut = Path().apply { addOval(Rect(Offset(cx + r * 0.55f, cy - r * 0.35f), r * 0.95f)) }
        val crescent = Path().apply { op(full, cut, PathOperation.Difference) }
        drawPath(crescent, CloudColor)
        return
    }
    drawCircle(C.Amber, r, Offset(cx, cy), style = Stroke(width = stroke))
    if (!rays) return
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val r1 = r * 1.5f; val r2 = r * 2.05f
        drawLine(
            C.Amber,
            Offset(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat()),
            Offset(cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat()),
            strokeWidth = stroke, cap = StrokeCap.Round
        )
    }
}

/** Ícone do tipo de tempo, em traço, no estilo da app. [night]: a Lua em vez do Sol. */
@Composable
fun WeatherIcon(kind: WxKind, night: Boolean, sizeDp: Int = 24, tint: Color = CloudColor) {
    Canvas(Modifier.size(sizeDp.dp)) {
        val w = size.minDimension
        val s = (w / 13f).coerceAtLeast(1.2f)
        when (kind) {
            WxKind.CLEAR -> sunOrMoon(w / 2, w / 2, w * 0.2f, s, night, rays = true)
            WxKind.FEW -> {
                sunOrMoon(w * 0.4f, w * 0.38f, w * 0.17f, s, night, rays = true)
                cloud(w * 0.5f, w * 0.58f, w * 0.96f, w * 0.88f, s, tint)
            }
            WxKind.PARTLY -> {
                sunOrMoon(w * 0.36f, w * 0.34f, w * 0.15f, s, night, rays = false)
                cloud(w * 0.22f, w * 0.42f, w * 0.96f, w * 0.86f, s, tint)
            }
            WxKind.CLOUD -> cloud(w * 0.06f, w * 0.22f, w * 0.94f, w * 0.78f, s, tint)
            WxKind.HIGH -> {
                drawLine(tint, Offset(w * 0.12f, w * 0.34f), Offset(w * 0.62f, w * 0.34f), s, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.28f, w * 0.52f), Offset(w * 0.86f, w * 0.52f), s, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.1f, w * 0.7f), Offset(w * 0.56f, w * 0.7f), s, StrokeCap.Round)
            }
            WxKind.FOG -> for (i in 0 until 4) {
                val y = w * (0.26f + i * 0.16f)
                drawLine(tint, Offset(w * 0.12f, y), Offset(w * 0.88f, y), s, StrokeCap.Round)
            }
            WxKind.RAIN, WxKind.STORM, WxKind.SNOW -> {
                cloud(w * 0.08f, w * 0.1f, w * 0.92f, w * 0.6f, s, tint)
                when (kind) {
                    WxKind.RAIN -> for (i in 0 until 3) {
                        val x = w * (0.3f + i * 0.2f)
                        drawLine(tint, Offset(x, w * 0.7f), Offset(x - w * 0.06f, w * 0.9f), s, StrokeCap.Round)
                    }
                    WxKind.STORM -> {
                        val p = Path().apply {
                            moveTo(w * 0.55f, w * 0.64f); lineTo(w * 0.42f, w * 0.8f); lineTo(w * 0.56f, w * 0.8f); lineTo(w * 0.45f, w * 0.96f)
                        }
                        drawPath(p, SkyUncertain, style = Stroke(width = s, cap = StrokeCap.Round))
                    }
                    else -> for (i in 0 until 3) drawCircle(tint, s * 0.9f, Offset(w * (0.3f + i * 0.2f), w * (0.76f + (i % 2) * 0.1f)))
                }
            }
        }
    }
}

private fun windText(deg: Double, names: Array<String>): String = names[((deg / 22.5) + 0.5).toInt() % 16]

@Composable
private fun agoText(t: Instant?): String {
    val h = (System.currentTimeMillis() - (t?.toEpochMilli() ?: System.currentTimeMillis())) / 3_600_000.0
    return when {
        h < 1 -> stringResource(R.string.less_than_hour)
        h < 48 -> stringResource(R.string.n_hours, h.toInt())
        else -> stringResource(R.string.n_days, (h / 24).toInt())
    }
}

private fun dayText(t: Instant): String =
    SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(t.toEpochMilli())).replace(".", "")

/** Caixa "Céu às hh:00" no detalhe do trânsito. [sky] null = a carregar. */
@Composable
fun SkyBox(sky: SkyResult?, night: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface)
            .border(1.dp, C.CardLine, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (sky) {
            null -> SkySimple(stringResource(R.string.sky_loading))
            is SkyResult.Unavailable -> SkySimple(stringResource(R.string.sky_none))
            is SkyResult.Later -> SkySimple(stringResource(R.string.sky_later, dayText(sky.from)))
            is SkyResult.Ready -> SkyReady(sky.f, night)
        }
    }
}

@Composable
private fun SkySimple(text: String) {
    Text(stringResource(R.string.sky_title).uppercase(), color = C.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
    Text(text, color = C.Muted, fontSize = 14.sp, lineHeight = 19.sp)
}

@Composable
private fun SkyReady(f: SkyForecast, night: Boolean) {
    val zone = ZoneId.systemDefault()
    val compass = stringArrayResource(R.array.compass)
    val types = stringArrayResource(R.array.ipma_types)
    val daily = f.step == SkyStep.DAILY
    val accent = if (night) CloudColor else C.Amber

    // cabeçalho
    Row(verticalAlignment = Alignment.CenterVertically) {
        val title = if (daily) stringResource(R.string.sky_day, dayText(f.time))
            else stringResource(R.string.sky_at, String.format(Locale.getDefault(), "%02d:00", f.time.atZone(zone).hour))
        Text(title.uppercase(), color = C.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp, modifier = Modifier.weight(1f))
        val vc = skyVerdictColor(f.verdict)
        val label = skyVerdictLabel(f.verdict)
        if (f.verdict == SkyVerdict.INDICATIVE) {
            Text(label, color = vc, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF3A4256), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
        } else {
            Text(label, color = C.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(vc).padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }

    // rain/vento
    val extras = buildList {
        f.rainPct?.let { add(stringResource(if (daily) R.string.sky_rain_day else R.string.sky_rain, it)) }
        if (f.windKmh != null && f.windFromDeg != null) add(stringResource(R.string.sky_wind, f.windKmh, windText(f.windFromDeg, compass)))
    }.joinToString(" · ")

    // linha principal
    if (f.ipmaType != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            WeatherIcon(ipmaKind(f.ipmaType), night, sizeDp = 48)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(types.getOrElse(f.ipmaType) { "—" }, color = C.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp)
                if (extras.isNotEmpty()) Text(extras, color = C.Muted, fontSize = 13.sp)
            }
        }
    } else if (f.layers != null) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${f.layers.total}%", color = C.Text, fontSize = 34.sp, fontFamily = FontFamily.Monospace, letterSpacing = (-0.5).sp)
                Text(stringResource(R.string.sky_covered), color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
            if (extras.isNotEmpty()) Text(extras, color = C.Muted, fontSize = 13.sp)
        }
    }

    // nuvens por camada (Open-Meteo)
    f.layers?.let { l ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (f.source == SkySource.IPMA) Text(stringResource(R.string.sky_layers_title), color = C.Muted, fontSize = 12.sp)
            LayerBar(stringResource(R.string.sky_high), l.high)
            LayerBar(stringResource(R.string.sky_mid), l.mid)
            LayerBar(stringResource(R.string.sky_low), l.low)
        }
    }

    // faixa de horas
    if (!daily && f.slots.size >= 2) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (slot in f.slots) {
                val mod = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).let {
                    if (slot.isMain) it.background(C.Ink).border(1.dp, accent, RoundedCornerShape(10.dp)) else it
                }
                Column(mod.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.sky_hour, slot.time.atZone(zone).hour),
                        color = if (slot.isMain) accent else C.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = if (slot.isMain) FontWeight.Medium else FontWeight.Normal
                    )
                    if (slot.ipmaType != null) {
                        WeatherIcon(ipmaKind(slot.ipmaType), night, sizeDp = 24)
                    } else if (slot.cloudTotal != null) {
                        Text("${slot.cloudTotal}%", color = C.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }

    // só a diária: quando chega a previsão para a hora
    if (daily) {
        val now = System.currentTimeMillis()
        val t3 = f.threeHourlyFrom?.takeIf { it.toEpochMilli() > now }
        val t1 = f.hourlyFrom?.takeIf { it.toEpochMilli() > now }
        val txt = when {
            t3 != null && t1 != null -> stringResource(R.string.sky_daily_both, dayText(t3), dayText(t1))
            t3 != null -> stringResource(R.string.sky_daily_3h, dayText(t3))
            else -> stringResource(R.string.sky_daily_none)
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Ink).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Canvas(Modifier.padding(top = 1.dp).size(18.dp)) {
                val w = size.minDimension
                val sw = 2.dp.toPx()
                drawCircle(C.Moon, w * 0.42f, Offset(w / 2, w / 2), style = Stroke(sw))
                drawLine(C.Moon, Offset(w / 2, w * 0.28f), Offset(w / 2, w / 2), sw, StrokeCap.Round)
                drawLine(C.Moon, Offset(w / 2, w / 2), Offset(w * 0.66f, w * 0.62f), sw, StrokeCap.Round)
            }
            Text(txt, color = CloudColor, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }

    // fonte
    val step = stringResource(when (f.step) {
        SkyStep.HOURLY -> R.string.sky_step_hourly
        SkyStep.THREE_HOURLY -> R.string.sky_step_3h
        SkyStep.DAILY -> R.string.sky_step_daily
    })
    val src = if (f.source == SkySource.IPMA) {
        stringResource(R.string.sky_src_ipma, f.ipmaPlace ?: "", (f.ipmaKm ?: 0.0).roundToInt(), step, agoText(f.updated)) +
            (if (f.layers != null) "\n" + stringResource(R.string.sky_src_ipma_layers) else "")
    } else {
        stringResource(R.string.sky_src_om, agoText(f.updated))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.CardLine))
    Text(src, color = C.Muted, fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
private fun LayerBar(label: String, pct: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = C.Muted, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.Ink)) {
            val frac = (pct.coerceIn(0, 100) / 100f)
            if (frac > 0f) Box(Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(4.dp)).background(CloudColor))
        }
        Text("$pct%", color = C.Text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
    }
}

/** Linha do céu no cartão da lista. Nada enquanto carrega ou sem dados. */
@Composable
fun SkyLine(sky: SkyResult?, night: Boolean) {
    when (sky) {
        null, is SkyResult.Unavailable -> Unit
        is SkyResult.Later -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WeatherIcon(WxKind.CLOUD, night, sizeDp = 18, tint = C.Faint)
            Text(stringResource(R.string.sky_later_card, dayText(sky.from)), color = C.Faint, fontSize = 13.sp)
        }
        is SkyResult.Ready -> {
            val f = sky.f
            val col = skyVerdictColor(f.verdict)
            val shortNames = stringArrayResource(R.array.ipma_types_short)
            val kind: WxKind
            var text: String
            if (f.ipmaType != null) {
                kind = ipmaKind(f.ipmaType)
                text = shortNames.getOrElse(f.ipmaType) { "—" }
            } else {
                val total = f.layers?.total ?: return
                kind = omKind(total, f.rainPct)
                text = stringResource(R.string.sky_card_om, total)
            }
            if (f.step == SkyStep.DAILY) text = stringResource(R.string.sky_card_day, text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WeatherIcon(kind, night, sizeDp = 18, tint = col)
                Text(text, color = col, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
