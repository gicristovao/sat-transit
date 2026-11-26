package pt.gicristovao.sattransit.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pt.gicristovao.sattransit.data.Place
import org.maplibre.android.geometry.LatLng
import pt.gicristovao.sattransit.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import java.text.DecimalFormatSymbols
import pt.gicristovao.sattransit.engine.Ephemeris
import pt.gicristovao.sattransit.engine.Transit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@Composable
fun TransitDetailScreen(
    t: Transit,
    place: Place,
    isGps: Boolean,
    evaluate: suspend (Double, Double) -> Transit?,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var mapOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = mapOpen) { mapOpen = false }
    var preview by remember { mutableStateOf<Pair<Transit, Boolean>?>(null) }
    val context = LocalContext.current
    val sun = t.body == Ephemeris.Body.SUN
    val visible = t.visibleFromObserver()
    val whenMs = Transit.jdToMillis(t.jdObserver)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(100) }
    }
    val date = Date(whenMs)
    val day = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(date).replace(".", "")
    val dec = DecimalFormatSymbols.getInstance().decimalSeparator
    val hms = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(date) + dec + ((whenMs % 1000) / 100)

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().background(C.Ink).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, C.Line, RoundedCornerShape(12.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(20.dp)) {
                    val w = size.minDimension
                    drawLine(C.Text, Offset(w * 0.62f, w * 0.2f), Offset(w * 0.3f, w * 0.5f), strokeWidth = 2.dp.toPx())
                    drawLine(C.Text, Offset(w * 0.3f, w * 0.5f), Offset(w * 0.62f, w * 0.8f), strokeWidth = 2.dp.toPx())
                }
            }
            if (sun) SunIcon() else MoonPhaseIcon(t.moonIllumination.toFloat(), t.moonWaxing)
            Column {
                Text("${stringResource(if (sun) R.string.sun else R.string.moon)} · $day", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("ISS · ${countdown(whenMs - now, dec)}", color = C.Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, C.CardLine, RoundedCornerShape(16.dp))) {
                BandMap(t, place.lat, place.lon, Modifier.fillMaxSize(), topPaddingDp = 52)
                Box(Modifier.matchParentSize().clickable { mapOpen = true })
                // ícone de ampliar: o mapa abre em grande ao tocar
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp).clip(RoundedCornerShape(10.dp))
                        .background(C.Ink.copy(alpha = 0.85f)).clickable { mapOpen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(18.dp)) {
                        val w = size.minDimension
                        val sw = 2.dp.toPx()
                        val a = w * 0.38f
                        drawLine(C.Text, Offset(w * 0.6f, w * 0.4f), Offset(w, 0f), strokeWidth = sw)
                        drawLine(C.Text, Offset(w - a, 0f), Offset(w, 0f), strokeWidth = sw)
                        drawLine(C.Text, Offset(w, 0f), Offset(w, a), strokeWidth = sw)
                        drawLine(C.Text, Offset(w * 0.4f, w * 0.6f), Offset(0f, w), strokeWidth = sw)
                        drawLine(C.Text, Offset(0f, w - a), Offset(0f, w), strokeWidth = sw)
                        drawLine(C.Text, Offset(0f, w), Offset(a, w), strokeWidth = sw)
                    }
                }
            }
            MapLegend(t, visible, isGps)
        }

        val durLabel = stringResource(if (visible) R.string.duration_here else R.string.duration_center)
        val dur = if (visible) t.durationObserverS else t.durationCenterS
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(stringResource(R.string.time), hms, Modifier.weight(1f))
                Stat(durLabel, stringResource(R.string.seconds_2, dur), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(stringResource(R.string.elevation_azimuth), String.format(Locale.getDefault(), "%.0f° · %.0f° %s", t.altitudeDeg, t.azimuthDeg, compass(t.azimuthDeg)), Modifier.weight(1f))
                Stat(stringResource(R.string.distance_to_iss), String.format(Locale.getDefault(), "%.0f km", t.rangeKm), Modifier.weight(1f))
            }
        }
        if (!visible) {
            Text(
                stringResource(R.string.outside_note),
                color = C.Muted, fontSize = 13.sp, lineHeight = 18.sp
            )
        }

        if (sun) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E1708))
                    .border(1.dp, Color(0xFF5C4313), RoundedCornerShape(12.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Canvas(Modifier.size(20.dp)) {
                    val w = size.minDimension
                    val sw = 2.dp.toPx()
                    drawLine(C.Amber, Offset(w / 2, w * 0.1f), Offset(w * 0.05f, w * 0.88f), strokeWidth = sw)
                    drawLine(C.Amber, Offset(w / 2, w * 0.1f), Offset(w * 0.95f, w * 0.88f), strokeWidth = sw)
                    drawLine(C.Amber, Offset(w * 0.05f, w * 0.88f), Offset(w * 0.95f, w * 0.88f), strokeWidth = sw)
                    drawLine(C.Amber, Offset(w / 2, w * 0.4f), Offset(w / 2, w * 0.6f), strokeWidth = sw)
                    drawCircle(C.Amber, radius = sw * 0.6f, center = Offset(w / 2, w * 0.74f))
                }
                Text(
                    stringResource(R.string.solar_warning),
                    color = Color(0xFFF1D9A8), fontSize = 13.sp, lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { preview = t to false },
            colors = ButtonDefaults.buttonColors(containerColor = C.Amber, contentColor = C.Ink),
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) { Text(stringResource(R.string.preview), fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
        Spacer(Modifier.height(8.dp))
    }
    if (mapOpen) ExpandedMap(t, place, evaluate, onPreview = { preview = it to true }) { mapOpen = false }
    preview?.let { (pt, fromPin) -> PreviewScreen(pt, fromPin, isGps) { preview = null } }
    }
}

@Composable
private fun ExpandedMap(t: Transit, place: Place, evaluate: suspend (Double, Double) -> Transit?, onPreview: (Transit) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val sun = t.body == Ephemeris.Body.SUN
    var pin by remember { mutableStateOf(LatLng(t.centerLat, t.centerLon)) }
    var eval by remember { mutableStateOf<Transit?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(pin) {
        busy = true
        eval = evaluate(pin.latitude, pin.longitude)
        busy = false
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(96.dp).background(Color(0xB805070B)).clickable(onClick = onClose))
        Column(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(C.Surface).navigationBarsPadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.band_width, t.bandWidthKm), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, C.Line, RoundedCornerShape(12.dp)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(16.dp)) {
                        val w = size.minDimension
                        drawLine(C.Text, Offset(0f, 0f), Offset(w, w), strokeWidth = 2.dp.toPx())
                        drawLine(C.Text, Offset(w, 0f), Offset(0f, w), strokeWidth = 2.dp.toPx())
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                BandMap(t, place.lat, place.lon, Modifier.fillMaxSize(), interactive = true, pin = pin, onLongPress = { pin = it }, topPaddingDp = 64)
                Text(
                    stringResource(R.string.map_hint), color = C.Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp).clip(RoundedCornerShape(6.dp)).background(C.Ink).padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val e = eval
                val inside = e != null && e.visibleFromObserver()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (inside) (if (sun) C.Amber else C.Moon) else C.Faint))
                    Text(
                        when { busy || e == null -> stringResource(R.string.calculating); inside -> stringResource(R.string.pin_inside); else -> stringResource(R.string.pin_outside) },
                        color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                if (e != null && !busy) {
                    val info = if (inside) {
                        val ms = Transit.jdToMillis(e.jdObserver)
                        val hms = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms)) +
                            DecimalFormatSymbols.getInstance().decimalSeparator + ((ms % 1000) / 100)
                        val pct = (e.minSeparationArcsec / e.bodyRadiusArcsec * 100).toInt()
                        stringResource(R.string.pin_info_inside, hms, e.durationObserverS, pct)
                    } else {
                        stringResource(R.string.pin_info_outside, distanceToBandKm(t, pin.latitude, pin.longitude))
                    }
                    Text(info, color = C.Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val uri = Uri.parse(String.format(Locale.US, "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f", pin.latitude, pin.longitude))
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        },
                        border = BorderStroke(1.dp, C.Line), colors = ButtonDefaults.outlinedButtonColors(contentColor = C.Text),
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    ) { Text(stringResource(R.string.navigate_pin), fontWeight = FontWeight.Medium, fontSize = 15.sp) }
                    Button(
                        onClick = { eval?.let(onPreview) },
                        colors = ButtonDefaults.buttonColors(containerColor = C.Amber, contentColor = C.Ink),
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    ) { Text(stringResource(R.string.preview), fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
                }
            }
        }
    }
}

/** Legenda por baixo do mapa: nada fica por cima da tua posição. */
@Composable
private fun MapLegend(t: Transit, visible: Boolean, isGps: Boolean) {
    val sun = t.body == Ephemeris.Body.SUN
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(if (sun) Color(0xFFE39A0B) else Color(0xFF3D6FD1)))
            Text(stringResource(R.string.band_width, t.bandWidthKm), color = C.Muted, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2F6FEB)).border(2.dp, Color.White, RoundedCornerShape(6.dp)))
            Text(
                when {
                    isGps && visible -> stringResource(R.string.you_inside)
                    isGps -> stringResource(R.string.you_outside, t.distanceToBandKm())
                    visible -> stringResource(R.string.place_inside)
                    else -> stringResource(R.string.place_outside, t.distanceToBandKm())
                },
                color = C.Muted, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, color = C.Muted, fontSize = 12.sp)
        Text(value, color = C.Text, fontSize = 17.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun compass(az: Double): String {
    val names = stringArrayResource(R.array.compass)
    return names[((az / 22.5) + 0.5).toInt() % 16]
}

@Composable
private fun countdown(ms: Long, dec: Char): String {
    if (ms <= 0) return stringResource(if (ms > -5000) R.string.now_happening else R.string.already_passed)
    val totalS = ms / 1000
    val d = totalS / 86400
    val h = (totalS % 86400) / 3600
    val m = (totalS % 3600) / 60
    val s = totalS % 60
    return when {
        d > 0 -> stringResource(R.string.left_days, d.toInt(), h.toInt(), m.toInt())
        h > 0 -> stringResource(R.string.left_hours, h.toInt(), m.toInt(), s.toInt())
        else -> stringResource(R.string.left_minutes, m.toInt(), s.toInt(), dec.toString(), ((ms % 1000) / 100).toInt())
    }
}
