package pt.gicristovao.sattransit.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.gicristovao.sattransit.MainViewModel
import pt.gicristovao.sattransit.UiState
import pt.gicristovao.sattransit.engine.Ephemeris
import pt.gicristovao.sattransit.engine.Transit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object C {
    val Ink = Color(0xFF0B0E14)
    val Surface = Color(0xFF161B26)
    val Line = Color(0xFF2A3140)
    val CardLine = Color(0xFF222938)
    val Text = Color(0xFFE6E8EE)
    val Muted = Color(0xFFA9B0BE)
    val Faint = Color(0xFF7D8597)
    val Amber = Color(0xFFF5A524)
    val Moon = Color(0xFF9DB4E8)
}

private val PT = Locale("pt", "PT")

@Composable
fun TransitListScreen(vm: MainViewModel, onRequestPermission: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var otherPlace by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<Ephemeris.Body?>(null) }
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(C.Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Text("SATTRANSIT", color = C.Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.7.sp)
        Spacer(Modifier.height(10.dp))
        Text("Próximos trânsitos", color = C.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 33.sp)
        Spacer(Modifier.height(14.dp))
        Segmented(left = "Onde estou", right = "Outro local", rightSelected = otherPlace) { otherPlace = it }
        Spacer(Modifier.height(8.dp))

        if (otherPlace) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Text(
                    "A pesquisa de outros locais chega na próxima versão.",
                    color = C.Muted, fontSize = 15.sp, textAlign = TextAlign.Center
                )
            }
            return@Column
        }

        when (val s = state) {
            is UiState.NeedPermission -> Message(
                "Para encontrar trânsitos onde estás, a app precisa da tua localização.",
                "Permitir localização", onRequestPermission
            )
            is UiState.Loading -> Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.message, color = C.Muted, fontSize = 15.sp)
                Spacer(Modifier.height(14.dp))
                if (s.progress != null) {
                    LinearProgressIndicator(progress = { s.progress }, color = C.Amber, trackColor = C.Surface, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(color = C.Amber, trackColor = C.Surface, modifier = Modifier.fillMaxWidth())
                }
            }
            is UiState.Error -> Message(s.message, "Tentar de novo") { onRequestPermission() }
            is UiState.Ready -> {
                LocationRow(s)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Todos", filter == null) { filter = null }
                    Chip("Sol", filter == Ephemeris.Body.SUN) { filter = Ephemeris.Body.SUN }
                    Chip("Lua", filter == Ephemeris.Body.MOON) { filter = Ephemeris.Body.MOON }
                }
                Spacer(Modifier.height(16.dp))
                val list = s.transits.filter { filter == null || it.body == filter }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (list.isEmpty()) {
                        item {
                            Text(
                                "Nenhum trânsito nos próximos ${s.days} dias num raio de ${s.radiusKm} km.",
                                color = C.Muted, fontSize = 15.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 32.dp)
                            )
                        }
                    }
                    items(list) { t ->
                        TransitCard(t) {
                            Toast.makeText(context, "O detalhe do trânsito chega na próxima versão.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    item {
                        val h = (System.currentTimeMillis() - s.tleEpochMillis) / 3_600_000.0
                        Text(
                            "Órbita atualizada há ${formatHours(h)}",
                            color = C.Faint, fontSize = 12.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clickable { vm.refresh(force = true) }.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatHours(h: Double): String = when {
    h < 1 -> "menos de 1 h"
    h < 48 -> "${h.toInt()} h"
    else -> "${(h / 24).toInt()} dias"
}

@Composable
private fun LocationRow(s: UiState.Ready) {
    val p = s.place
    val coords = String.format(PT, "%.3f° %s · %.3f° %s", abs(p.lat), if (p.lat >= 0) "N" else "S", abs(p.lon), if (p.lon >= 0) "E" else "O")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 44.dp)) {
        Canvas(Modifier.size(18.dp)) {
            val st = Stroke(width = 2.dp.toPx())
            drawCircle(C.Moon, radius = size.minDimension * 0.16f, style = st)
            drawCircle(C.Moon, radius = size.minDimension * 0.34f, style = st)
        }
        Spacer(Modifier.size(8.dp))
        Text(
            "GPS · ${p.name ?: coords} · raio de ${s.radiusKm} km · ${s.days} dias",
            color = C.Muted, fontSize = 14.sp
        )
    }
}

@Composable
private fun Segmented(left: String, right: String, rightSelected: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(left to false, right to true).forEach { (label, value) ->
            val sel = rightSelected == value
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (sel) C.Text else Color.Transparent)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (sel) C.Ink else C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (selected) C.Text else Color.Transparent)
            .border(if (selected) BorderStroke(0.dp, Color.Transparent) else BorderStroke(1.dp, C.Line), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) C.Ink else C.Text, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun TransitCard(t: Transit, onClick: () -> Unit) {
    val visible = t.visibleFromObserver()
    val date = Date(Transit.jdToMillis(t.jdObserver))
    val day = SimpleDateFormat("EEE, d MMM", PT).format(date).replace(".", "")
    val hms = SimpleDateFormat("HH:mm:ss", PT).format(date)
    val tenths = ((Transit.jdToMillis(t.jdObserver) % 1000) / 100).toInt()
    val sun = t.body == Ephemeris.Body.SUN
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface)
            .border(1.dp, if (visible) C.Amber else C.CardLine, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BodyIcon(sun)
            Spacer(Modifier.size(10.dp))
            Text("${if (sun) "Sol" else "Lua"} · $day", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (visible) {
                Text("Junto à linha", color = C.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(C.Amber).padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        Text("$hms,$tenths", color = C.Text, fontSize = 30.sp, fontFamily = FontFamily.Monospace, letterSpacing = (-0.5).sp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val dur = if (visible) t.durationObserverS else t.durationCenterS
            Meta(String.format(PT, "%.1f s", dur))
            Meta(String.format(PT, "elev. %.0f°", t.altitudeDeg))
            Meta(String.format(PT, "%.1f km da linha", t.centerlineDistanceKm))
        }
    }
}

@Composable
private fun Meta(s: String) = Text(s, color = C.Muted, fontSize = 13.sp)

@Composable
private fun BodyIcon(sun: Boolean) {
    Canvas(Modifier.size(28.dp)) {
        val w = size.minDimension
        val st = Stroke(width = 2.dp.toPx())
        if (sun) {
            val c = Offset(w / 2, w / 2)
            drawCircle(C.Amber, radius = w * 0.19f, center = c, style = st)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val r1 = w * 0.32f; val r2 = w * 0.44f
                drawLine(C.Amber,
                    Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()),
                    Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()),
                    strokeWidth = 2.dp.toPx())
            }
        } else {
            drawCircle(C.Moon, radius = w * 0.34f, style = st)
            drawCircle(C.Surface, radius = w * 0.30f, center = Offset(w * 0.64f, w * 0.38f))
        }
    }
}

@Composable
private fun Message(text: String, action: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = C.Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = C.Amber, contentColor = C.Ink),
            shape = RoundedCornerShape(14.dp), modifier = Modifier.heightIn(min = 52.dp)) {
            Text(action, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}
