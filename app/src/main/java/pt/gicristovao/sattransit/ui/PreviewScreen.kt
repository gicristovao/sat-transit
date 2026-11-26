package pt.gicristovao.sattransit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.gicristovao.sattransit.R
import pt.gicristovao.sattransit.data.PreviewSettings
import pt.gicristovao.sattransit.data.SensorType
import pt.gicristovao.sattransit.engine.Ephemeris
import pt.gicristovao.sattransit.engine.Transit
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val ARCSEC_PER_RAD = 206264.806

/**
 * Pré-visualização do trânsito no enquadramento do sensor.
 * t deve ter sido avaliado no ponto de vista (a tua posição ou o pino), com skyTrack.
 */
@Composable
fun PreviewScreen(t: Transit, fromPin: Boolean, isGps: Boolean = true, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var settings by remember { mutableStateOf(PreviewSettings.load(context)) }
    fun update(s: PreviewSettings) { settings = s; PreviewSettings.save(context, s) }
    var focalText by remember { mutableStateOf(settings.focalMm.toString()) }
    var fpsText by remember { mutableStateOf(settings.fps.toString()) }
    var sensorDialog by remember { mutableStateOf(false) }

    val sun = t.body == Ephemeris.Body.SUN
    val visible = t.visibleFromObserver()
    val ms = Transit.jdToMillis(t.jdObserver)
    val dec = DecimalFormatSymbols.getInstance().decimalSeparator
    val hms = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms)) + dec + ((ms % 1000) / 100)
    val day = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(ms)).replace(".", "")
    val pct = (t.minSeparationArcsec / t.bodyRadiusArcsec * 100).toInt()

    // silhuetas ao ritmo da rajada
    val shots = remember(t, settings.fps) { silhouettes(t, settings.fps) }
    val onDisc = shots.count { hypot(it.x, it.y) < t.bodyRadiusArcsec }

    val f = settings.focalMm.toDouble()
    val discMm = 2 * t.bodyRadiusArcsec * f / ARCSEC_PER_RAD
    val issMm = t.satSizeArcsec * f / ARCSEC_PER_RAD
    val shortSide = min(settings.widthMm, settings.heightMm)
    val tooBig = discMm > shortSide * 0.95

    Column(
        Modifier.fillMaxSize().background(C.Ink).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, C.Line, RoundedCornerShape(12.dp)).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(20.dp)) {
                    val w = size.minDimension
                    drawLine(C.Text, Offset(w * 0.62f, w * 0.2f), Offset(w * 0.3f, w * 0.5f), strokeWidth = 2.dp.toPx())
                    drawLine(C.Text, Offset(w * 0.3f, w * 0.5f), Offset(w * 0.62f, w * 0.8f), strokeWidth = 2.dp.toPx())
                }
            }
            Column {
                Text(stringResource(R.string.preview), color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("${stringResource(if (sun) R.string.sun else R.string.moon)} · $day · $hms", color = C.Muted, fontSize = 13.sp)
            }
        }

        // de onde se vê
        val origin = buildString {
            append(stringResource(when { fromPin -> R.string.preview_from_pin; isGps -> R.string.preview_from_you; else -> R.string.preview_from_place }))
            append(" · ")
            if (visible) {
                append(stringResource(R.string.inside_band)); append(" · "); append(stringResource(R.string.pct_from_center, pct))
            } else append(stringResource(R.string.outside_band))
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(if (visible) Color(0xFF1E1708) else C.Surface)
                .border(1.dp, if (visible) Color(0xFF5C4313) else C.Line, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(origin, color = if (visible) Color(0xFFF1D9A8) else C.Muted, fontSize = 13.sp)
        }

        // enquadramento
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(290.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF05070B))
                .border(1.dp, C.CardLine, RoundedCornerShape(16.dp))
        ) {
            val density = LocalDensity.current
            Canvas(Modifier.fillMaxSize()) {
                val pad = 12.dp.toPx()
                val long = max(settings.widthMm, settings.heightMm)
                val short = min(settings.widthMm, settings.heightMm)
                val a = (long / short).toFloat()
                val fw: Float
                val fh: Float
                if (!settings.portrait) {
                    fw = min(size.width - 2 * pad, (size.height - 2 * pad) * a); fh = fw / a
                } else {
                    fh = min(size.height - 2 * pad, (size.width - 2 * pad) * a); fw = fh / a
                }
                val left = (size.width - fw) / 2
                val top = (size.height - fh) / 2
                val c = Offset(size.width / 2, size.height / 2)
                val pxPerMm = max(fw, fh) / long.toFloat()
                val k = (pxPerMm * f / ARCSEC_PER_RAD).toFloat()

                drawRect(Color(0xFF0E121A), Offset(left, top), Size(fw, fh))
                for (i in 1..2) {
                    drawLine(Color(0xFF1B2130), Offset(left + fw * i / 3, top), Offset(left + fw * i / 3, top + fh), 1f)
                    drawLine(Color(0xFF1B2130), Offset(left, top + fh * i / 3), Offset(left + fw, top + fh * i / 3), 1f)
                }
                clipRect(left, top, left + fw, top + fh) {
                    val r = max(1.5f, (t.bodyRadiusArcsec * k).toFloat())
                    if (t.body == Ephemeris.Body.SUN) drawCircle(C.Amber, r, c)
                    else drawMoon(c, r, t.moonIllumination.toFloat(), Math.toDegrees(t.moonBrightLimbRad).toFloat())

                    val trk = t.skyTrack
                    if (trk != null && trk.size >= 4) {
                        val n = trk.size / 2
                        val pts = List(n) { Offset(c.x + trk[2 * it] * k, c.y - trk[2 * it + 1] * k) }
                        val ext = 4 * max(fw, fh)
                        fun dir(a: Offset, b: Offset): Offset { val l = hypot(b.x - a.x, b.y - a.y); return if (l == 0f) Offset(0f, 0f) else Offset((b.x - a.x) / l, (b.y - a.y) / l) }
                        val d0 = dir(pts[1], pts[0]); val d1 = dir(pts[n - 2], pts[n - 1])
                        val path = Path().apply {
                            moveTo(pts[0].x + d0.x * ext, pts[0].y + d0.y * ext)
                            for (p in pts) lineTo(p.x, p.y)
                            lineTo(pts[n - 1].x + d1.x * ext, pts[n - 1].y + d1.y * ext)
                        }
                        drawPath(path, C.Text.copy(alpha = 0.55f), style = Stroke(width = 1.dp.toPx()))

                        // silhuetas da ISS à escala (mínimo visível)
                        val sz = max(1.6.dp.toPx(), (t.satSizeArcsec * k).toFloat())
                        for (s in shots) {
                            val p = Offset(c.x + (s.x * k).toFloat(), c.y - (s.y * k).toFloat())
                            drawRect(C.Ink, Offset(p.x - sz * 0.7f, p.y - sz * 0.3f), Size(sz * 1.4f, sz * 0.6f))
                        }
                        // seta à saída do disco
                        var iMin = 0; var dMin = Float.MAX_VALUE
                        for (i in 0 until n) { val d = hypot(trk[2 * i], trk[2 * i + 1]); if (d < dMin) { dMin = d; iMin = i } }
                        val target = t.bodyRadiusArcsec * 1.25
                        val iA = (iMin until n).firstOrNull { hypot(trk[2 * it], trk[2 * it + 1]) > target } ?: (n - 1)
                        val u = dir(pts[(iA - 1).coerceAtLeast(0)], pts[iA])
                        drawChevron(pts[iA], u.x, u.y)
                    }
                }
                drawRect(
                    Color(0xFF3A4256), Offset(left, top), Size(fw, fh),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                )
            }
            if (tooBig) {
                Text(
                    stringResource(R.string.disc_too_big), color = C.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(6.dp)).background(C.Amber)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // orientação
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(false to R.string.horizontal, true to R.string.vertical).forEach { (portrait, label) ->
                val sel = settings.portrait == portrait
                Row(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(9.dp)).background(if (sel) C.Text else Color.Transparent)
                        .clickable { update(settings.copy(portrait = portrait)) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    val col = if (sel) C.Ink else C.Text
                    Canvas(Modifier.size(width = 22.dp, height = 22.dp)) {
                        val w = if (portrait) 12.dp.toPx() else 20.dp.toPx()
                        val h = if (portrait) 20.dp.toPx() else 12.dp.toPx()
                        drawRoundRect(col, Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h),
                            androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = Stroke(2.dp.toPx()))
                    }
                    Text("  " + stringResource(label), color = col, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // sensor
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface).clickable { sensorDialog = true }
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sensor), color = C.Muted, fontSize = 12.sp)
                Text(
                    stringResource(R.string.sensor_line, sensorName(settings.sensor), num(settings.widthMm), num(settings.heightMm), num(settings.megapixels)),
                    color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium
                )
            }
            Text(stringResource(R.string.change), color = C.Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }

        // focal e rajada
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1.55f).clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.focal), color = C.Muted, fontSize = 12.sp)
                    NumberField(focalText, "mm", 64) { txt ->
                        focalText = txt
                        txt.toIntOrNull()?.takeIf { it in 10..5000 }?.let { update(settings.copy(focalMm = it)) }
                    }
                }
                StepButton("−") { val n = (settings.focalMm - 100).coerceIn(10, 5000); focalText = n.toString(); update(settings.copy(focalMm = n)) }
                StepButton("+") { val n = (settings.focalMm + 100).coerceIn(10, 5000); focalText = n.toString(); update(settings.copy(focalMm = n)) }
            }
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(stringResource(R.string.burst), color = C.Muted, fontSize = 12.sp)
                NumberField(fpsText, "fps", 44) { txt ->
                    fpsText = txt
                    txt.toIntOrNull()?.takeIf { it in 1..120 }?.let { update(settings.copy(fps = it)) }
                }
            }
        }

        // números
        val pitch = settings.pixelPitchMm
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat2(stringResource(if (sun) R.string.sun_on_sensor else R.string.moon_on_sensor),
                stringResource(R.string.disc_line, discMm, (discMm / pitch).toInt(), (discMm / shortSide * 100).toInt()), Modifier.weight(1f))
            Stat2(stringResource(R.string.iss_on_sensor), stringResource(R.string.iss_line, issMm, (issMm / pitch).toInt()), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat2(stringResource(R.string.over_disc), stringResource(R.string.seconds_2, if (visible) t.durationObserverS else 0.0), Modifier.weight(1f))
            Stat2(stringResource(R.string.shots_on_disc), stringResource(R.string.shots_value, onDisc, settings.fps), Modifier.weight(1f))
        }
    }

    if (sensorDialog) SensorDialog(settings, onDismiss = { sensorDialog = false }) { update(it); sensorDialog = false }
}

private data class Pt(val x: Double, val y: Double)

/** Posições (″) da ISS em cada disparo da rajada, à volta da maior aproximação. */
private fun silhouettes(t: Transit, fps: Int): List<Pt> {
    val trk = t.skyTrack ?: return emptyList()
    val tt = t.skyTrackT ?: return emptyList()
    val n = tt.size
    if (n < 2) return emptyList()
    var iMin = 0; var dMin = Double.MAX_VALUE
    for (i in 0 until n) { val d = hypot(trk[2 * i].toDouble(), trk[2 * i + 1].toDouble()); if (d < dMin) { dMin = d; iMin = i } }
    val t0 = tt[iMin].toDouble()
    val half = max(if (t.visibleFromObserver()) t.durationObserverS else 1.0, 0.5) / 2 + 0.3
    val out = ArrayList<Pt>()
    val step = 1.0 / fps
    var k = -kotlin.math.floor(half / step).toInt()
    while (k * step <= half) {
        val time = t0 + k * step
        var j = 0
        while (j < n - 2 && tt[j + 1] < time) j++
        val ta = tt[j].toDouble(); val tb = tt[j + 1].toDouble()
        val f = if (tb == ta) 0.0 else (time - ta) / (tb - ta)
        val x = trk[2 * j] + f * (trk[2 * j + 2] - trk[2 * j])
        val y = trk[2 * j + 1] + f * (trk[2 * j + 3] - trk[2 * j + 1])
        out += Pt(x, y)
        k++
    }
    return out
}

private fun num(v: Double): String =
    if (v == Math.floor(v)) v.toInt().toString() else String.format(Locale.getDefault(), "%.1f", v)

@Composable
private fun sensorName(s: SensorType): String = stringResource(
    when (s) {
        SensorType.FULL_FRAME -> R.string.sensor_ff
        SensorType.APSC -> R.string.sensor_apsc
        SensorType.APSC_CANON -> R.string.sensor_apsc_canon
        SensorType.MICRO_43 -> R.string.sensor_m43
        SensorType.MEDIUM_FORMAT -> R.string.sensor_mf
        SensorType.CUSTOM -> R.string.sensor_custom
    }
)

@Composable
private fun NumberField(value: String, unit: String, widthDp: Int, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(4)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(color = C.Text, fontSize = 16.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(C.Amber),
            modifier = Modifier.width(widthDp.dp).clip(RoundedCornerShape(8.dp)).background(C.Ink)
                .border(1.dp, C.Line, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 6.dp)
        )
        Text("  $unit", color = C.Muted, fontSize = 13.sp)
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 4.dp).size(width = 40.dp, height = 44.dp).clip(RoundedCornerShape(10.dp))
            .border(1.dp, C.Line, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Stat2(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, color = C.Muted, fontSize = 12.sp)
        Text(value, color = C.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SensorDialog(current: PreviewSettings, onDismiss: () -> Unit, onConfirm: (PreviewSettings) -> Unit) {
    var type by remember { mutableStateOf(current.sensor) }
    var mp by remember { mutableStateOf(num(current.megapixels)) }
    var w by remember { mutableStateOf(num(current.customW)) }
    var h by remember { mutableStateOf(num(current.customH)) }
    fun parse(s: String) = s.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(stringResource(R.string.sensor), color = C.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SensorType.entries.forEach { s ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { type = s }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = type == s, onClick = { type = s },
                            colors = RadioButtonDefaults.colors(selectedColor = C.Amber, unselectedColor = C.Muted))
                        val dims = if (s == SensorType.CUSTOM) "" else " · ${num(s.widthMm)}×${num(s.heightMm)}"
                        Text(sensorName(s) + dims, color = C.Text, fontSize = 14.sp)
                    }
                }
                if (type == SensorType.CUSTOM) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Column { Text(stringResource(R.string.width_mm), color = C.Muted, fontSize = 12.sp); DecimalField(w) { w = it } }
                        Column { Text(stringResource(R.string.height_mm), color = C.Muted, fontSize = 12.sp); DecimalField(h) { h = it } }
                    }
                }
                Column(Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.megapixels), color = C.Muted, fontSize = 12.sp)
                    DecimalField(mp) { mp = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val m = parse(mp)?.takeIf { it in 1.0..500.0 } ?: current.megapixels
                val cw = parse(w)?.takeIf { it in 1.0..100.0 } ?: current.customW
                val ch = parse(h)?.takeIf { it in 1.0..100.0 } ?: current.customH
                onConfirm(current.copy(sensor = type, megapixels = m, customW = cw, customH = ch))
            }) { Text(stringResource(R.string.ok), color = C.Amber) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = C.Muted) } }
    )
}

@Composable
private fun DecimalField(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == ',' || it == '.' }.take(6)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = TextStyle(color = C.Text, fontSize = 16.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(C.Amber),
        modifier = Modifier.width(90.dp).clip(RoundedCornerShape(8.dp)).background(C.Ink)
            .border(1.dp, C.Line, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 8.dp)
    )
}
