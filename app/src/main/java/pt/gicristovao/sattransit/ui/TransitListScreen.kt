package pt.gicristovao.sattransit.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import pt.gicristovao.sattransit.data.Reliability
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.gicristovao.sattransit.MainViewModel
import pt.gicristovao.sattransit.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import java.text.DecimalFormatSymbols
import pt.gicristovao.sattransit.UiState
import pt.gicristovao.sattransit.UpdateState
import androidx.compose.runtime.LaunchedEffect
import pt.gicristovao.sattransit.engine.Ephemeris
import pt.gicristovao.sattransit.engine.Transit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
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


@Composable
fun TransitListScreen(vm: MainViewModel, onRequestPermission: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val otherPlace by vm.otherMode.collectAsStateWithLifecycle()
    val chosen by vm.chosen.collectAsStateWithLifecycle()
    var showLocal by remember { mutableStateOf(false) }
    var radiusSheet by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<Ephemeris.Body?>(null) }
    var savedOnly by remember { mutableStateOf(false) }
    val savedRecords by vm.saved.collectAsStateWithLifecycle()
    var savedList by remember { mutableStateOf<List<Pair<pt.gicristovao.sattransit.data.SavedTransit, Transit>>?>(null) }
    var selectedPlace by remember { mutableStateOf<pt.gicristovao.sattransit.data.Place?>(null) }
    LaunchedEffect(savedOnly) {
        if (savedOnly) { savedList = null; savedList = vm.savedTransits() }
    }
    val context = LocalContext.current
    val update by vm.update.collectAsStateWithLifecycle()
    val sheetOpen by vm.sheetOpen.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    LaunchedEffect(toast) {
        toast?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.toastShown() }
    }

    var selected by remember { mutableStateOf<Transit?>(null) }
    val ready = state as? UiState.Ready
    val sel = selected
    if (showLocal) {
        LocalScreen(vm, onBack = { showLocal = false }, onDone = { showLocal = false })
        return
    }
    if (sel != null && ready != null) {
        val place = selectedPlace ?: ready.place
        val isGps = selectedPlace == null && ready.isGps
        TransitDetailScreen(
            sel, place, isGps, { la, lo -> vm.evaluateAt(sel, la, lo) },
            saved = savedRecords.firstOrNull { it.matches(sel) },
            tleEpochMillis = vm.tleEpochMillis,
            onToggleSave = { la, lo -> vm.toggleSave(sel, la, lo, place.name ?: "") },
        ) { selected = null; selectedPlace = null }
        return
    }

    Box(Modifier.fillMaxSize()) {
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
        Text(stringResource(R.string.upcoming_transits), color = C.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 33.sp)
        Spacer(Modifier.height(14.dp))
        UpdateBanner(update) { vm.openSheet() }
        Segmented(left = stringResource(R.string.where_i_am), right = stringResource(R.string.other_place), rightSelected = otherPlace) { other ->
            if (!other) vm.useHere()
            else if (!vm.selectOther()) showLocal = true
        }
        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            is UiState.NeedPermission -> Message(
                stringResource(R.string.need_location),
                stringResource(R.string.allow_location), onRequestPermission
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
            is UiState.Error -> Message(s.message, stringResource(R.string.try_again)) { onRequestPermission() }
            is UiState.NeedPlace -> Message(stringResource(R.string.choose_place_prompt), stringResource(R.string.choose_place)) { showLocal = true }
            is UiState.Ready -> {
                if (s.isGps) LocationRow(s) { radiusSheet = true } else OtherPlaceRow(s) { showLocal = true }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Chip(stringResource(R.string.filter_all), filter == null && !savedOnly) { filter = null; savedOnly = false }
                    Chip(stringResource(R.string.sun), filter == Ephemeris.Body.SUN && !savedOnly) { filter = Ephemeris.Body.SUN; savedOnly = false }
                    Chip(stringResource(R.string.moon), filter == Ephemeris.Body.MOON && !savedOnly) { filter = Ephemeris.Body.MOON; savedOnly = false }
                    Chip(stringResource(R.string.filter_saved), savedOnly, icon = { BookmarkIcon(filled = true, sizeDp = 14) }) { savedOnly = true; filter = null }
                }
                Spacer(Modifier.height(16.dp))
                val list = if (savedOnly) savedList?.map { it.second } ?: emptyList()
                    else s.transits.filter { filter == null || it.body == filter }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (savedOnly && list.isEmpty()) {
                        item {
                            Text(
                                if (savedList == null) stringResource(R.string.calculating) else stringResource(R.string.no_saved),
                                color = C.Muted, fontSize = 15.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 32.dp)
                            )
                        }
                    } else if (list.isEmpty()) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(top = 32.dp, start = 8.dp, end = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    stringResource(R.string.none_found, s.days, s.radiusKm) + "\n" + stringResource(R.string.none_hint),
                                    color = C.Muted, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp
                                )
                                if (s.transits.isEmpty()) {
                                    Button(
                                        onClick = { if (s.isGps) radiusSheet = true else showLocal = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = C.Amber, contentColor = C.Ink),
                                        shape = RoundedCornerShape(14.dp), modifier = Modifier.heightIn(min = 48.dp)
                                    ) { Text(stringResource(R.string.increase_radius), fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
                                }
                            }
                        }
                    }
                    items(list) { t ->
                        val rec = savedRecords.firstOrNull { it.matches(t) }
                        TransitCard(t, saved = rec != null) {
                            selected = t
                            selectedPlace = if (savedOnly && rec != null) pt.gicristovao.sattransit.data.Place(rec.lat, rec.lon, 0.0, rec.placeName) else null
                        }
                    }
                    item {
                        val h = (System.currentTimeMillis() - s.tleEpochMillis) / 3_600_000.0
                        Text(
                            stringResource(R.string.orbit_updated, formatHours(h)),
                            color = C.Faint, fontSize = 12.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clickable { vm.refresh(force = true) }.padding(8.dp)
                        )
                    }
                    item {
                        Text(
                            stringResource(R.string.version_check, vm.updater.installedVersionName()),
                            color = C.Faint, fontSize = 12.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clickable { vm.checkUpdate(manual = true) }.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
    val readyNow = state as? UiState.Ready
    if (radiusSheet && readyNow != null) {
        RadiusSheet(readyNow.radiusKm, onClose = { radiusSheet = false }) { km -> radiusSheet = false; vm.setHereRadius(km) }
    }
    if (sheetOpen && update !is UpdateState.None) {
        UpdateSheet(
            state = update,
            installedVersion = vm.updater.installedVersionName(),
            onDownload = vm::startDownload,
            onInstall = vm::install,
            onClose = vm::closeSheet,
        )
    }
    }
}

@Composable
private fun UpdateBanner(update: UpdateState, onClick: () -> Unit) {
    val label = when (update) {
        is UpdateState.Available -> stringResource(R.string.update_available, update.release.versionName)
        is UpdateState.Downloading -> stringResource(R.string.update_downloading, update.release.versionName, (update.progress * 100).toInt())
        is UpdateState.Ready -> stringResource(R.string.update_ready, update.release.versionName)
        is UpdateState.Failed -> stringResource(R.string.update_failed, update.release.versionName)
        UpdateState.None -> return
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1E1708)).border(1.dp, C.Amber, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.minDimension
            val sw = 2.dp.toPx()
            drawLine(C.Amber, Offset(w / 2, w * 0.15f), Offset(w / 2, w * 0.62f), strokeWidth = sw)
            drawLine(C.Amber, Offset(w * 0.28f, w * 0.42f), Offset(w / 2, w * 0.64f), strokeWidth = sw)
            drawLine(C.Amber, Offset(w * 0.72f, w * 0.42f), Offset(w / 2, w * 0.64f), strokeWidth = sw)
            drawLine(C.Amber, Offset(w * 0.2f, w * 0.85f), Offset(w * 0.8f, w * 0.85f), strokeWidth = sw)
        }
        Spacer(Modifier.size(12.dp))
        Text(label, color = Color(0xFFF1D9A8), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (update is UpdateState.Available || update is UpdateState.Ready) {
            Text(if (update is UpdateState.Ready) stringResource(R.string.install) else stringResource(R.string.update_action), color = C.Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun UpdateSheet(
    state: UpdateState,
    installedVersion: String,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onClose: () -> Unit,
) {
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Ready -> state.release
        is UpdateState.Failed -> state.release
        UpdateState.None -> return
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xB805070B)).clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(C.Surface)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A4256)))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("SatTransit ${release.versionName}", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                val mb = if (release.sizeBytes > 0) " · ${release.sizeBytes / 1_000_000} MB" else ""
                Text(stringResource(R.string.you_have, installedVersion, mb), color = C.Muted, fontSize = 13.sp)
            }
            if (release.notes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.whats_new), color = C.Faint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    release.notes.take(8).forEach { Text("· $it", color = Color(0xFFD3D7E0), fontSize = 14.sp, lineHeight = 20.sp) }
                }
            }
            when (state) {
                is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row {
                        Text(stringResource(R.string.downloading), color = C.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("${(state.progress * 100).toInt()}%", color = C.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    }
                    LinearProgressIndicator(progress = { state.progress }, color = C.Amber, trackColor = C.Ink, modifier = Modifier.fillMaxWidth().height(8.dp))
                }
                is UpdateState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.install_note), color = Color(0xFFD3D7E0), fontSize = 14.sp, lineHeight = 20.sp)
                    PrimaryButton(stringResource(R.string.install), onInstall)
                }
                is UpdateState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.message, color = Color(0xFFF1D9A8), fontSize = 14.sp)
                    PrimaryButton(stringResource(R.string.try_again), onDownload)
                    SecondaryButton(stringResource(R.string.later), onClose)
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(stringResource(R.string.download_and_install), onDownload)
                    SecondaryButton(stringResource(R.string.later), onClose)
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = C.Amber, contentColor = C.Ink),
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, border = BorderStroke(1.dp, C.Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = C.Text),
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(label, fontWeight = FontWeight.Medium, fontSize = 16.sp)
    }
}

@Composable
private fun formatHours(h: Double): String = when {
    h < 1 -> stringResource(R.string.less_than_hour)
    h < 48 -> stringResource(R.string.n_hours, h.toInt())
    else -> stringResource(R.string.n_days, (h / 24).toInt())
}

@Composable
private fun LocationRow(s: UiState.Ready, onRadius: () -> Unit) {
    val p = s.place
    val west = stringArrayResource(R.array.compass)[12]
    val coords = String.format(Locale.getDefault(), "%.3f° %s · %.3f° %s", abs(p.lat), if (p.lat >= 0) "N" else "S", abs(p.lon), if (p.lon >= 0) "E" else west)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, C.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onRadius).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(18.dp)) {
            val st = Stroke(width = 2.dp.toPx())
            drawCircle(C.Moon, radius = size.minDimension * 0.16f, style = st)
            drawCircle(C.Moon, radius = size.minDimension * 0.34f, style = st)
        }
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.location_line, p.name ?: coords, s.radiusKm, s.days),
            color = C.Muted, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f)
        )
        Text(stringResource(R.string.radius_btn), color = C.Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Painel para mudar o raio de "Onde estou". */
@Composable
private fun RadiusSheet(current: Int, onClose: () -> Unit, onApply: (Int) -> Unit) {
    var draft by remember { mutableStateOf(current) }
    Box(Modifier.fillMaxSize().background(Color(0xB805070B)).clickable(onClick = onClose), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(C.Surface)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A4256)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.search_radius), color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("$draft km", color = C.Text, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
            }
            androidx.compose.material3.Slider(
                value = draft.toFloat(), onValueChange = { draft = ((it / 5).roundToInt() * 5).coerceIn(5, 200) },
                valueRange = 5f..200f,
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = C.Amber, activeTrackColor = C.Amber, inactiveTrackColor = C.Line)
            )
            Text(stringResource(R.string.radius_here_note), color = C.Muted, fontSize = 13.sp, lineHeight = 18.sp)
            PrimaryButton(stringResource(R.string.apply)) { onApply(draft) }
        }
    }
}

@Composable
private fun OtherPlaceRow(s: UiState.Ready, onChange: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, C.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onChange).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(16.dp)) {
            val w = size.minDimension
            drawCircle(C.Text, radius = w * 0.3f, center = Offset(w * 0.45f, w * 0.45f), style = Stroke(2.dp.toPx()))
            drawLine(C.Text, Offset(w * 0.67f, w * 0.67f), Offset(w * 0.95f, w * 0.95f), strokeWidth = 2.dp.toPx())
        }
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.other_line, (s.place.name ?: "").substringBefore(","), s.radiusKm, s.days),
            color = C.Text, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f)
        )
        Text(stringResource(R.string.change_place), color = C.Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
private fun Chip(label: String, selected: Boolean, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (selected) C.Text else Color.Transparent)
            .border(if (selected) BorderStroke(0.dp, Color.Transparent) else BorderStroke(1.dp, C.Line), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        icon?.invoke()
        Text(label, color = if (selected) C.Ink else C.Text, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransitCard(t: Transit, saved: Boolean = false, onClick: () -> Unit) {
    val visible = t.visibleFromObserver()
    val date = Date(Transit.jdToMillis(t.jdObserver))
    val day = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(date).replace(".", "")
    val hms = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(date)
    val sun = t.body == Ephemeris.Body.SUN
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface)
            .border(1.dp, if (visible) C.Amber else C.CardLine, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BodyIcon(t)
                Spacer(Modifier.size(8.dp))
                Text("${stringResource(if (sun) R.string.sun else R.string.moon)} · $day", color = C.Text, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                if (saved) BookmarkIcon(filled = true, sizeDp = 14)
            }
            Text(hms, color = C.Text, fontSize = 28.sp, fontFamily = FontFamily.Monospace, letterSpacing = (-0.5).sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val conf = Reliability.level(t)
                val cc = confidenceColor(conf)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.align(Alignment.CenterVertically)) {
                    ConfidenceDot(conf, cc)
                    Text(stringResource(R.string.conf_chip, confidenceLabel(conf), kotlin.math.ceil(Reliability.marginSeconds(t)).toInt()), color = cc, fontSize = 13.sp)
                }
                if (visible) {
                    Text(stringResource(R.string.visible_here), color = C.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.CenterVertically).clip(RoundedCornerShape(5.dp)).background(C.Amber)
                            .padding(horizontal = 6.dp, vertical = 2.dp))
                }
                val dur = if (visible) t.durationObserverS else t.durationCenterS
                Meta(stringResource(R.string.seconds_1, dur))
                if (!visible) Meta(stringResource(R.string.km_from_band, t.distanceToBandKm()))
            }
        }
        SkyThumbnail(t)
    }
}

@Composable
private fun Meta(s: String) = Text(s, color = C.Muted, fontSize = 13.sp)

@Composable
private fun BodyIcon(t: Transit) {
    if (t.body == Ephemeris.Body.SUN) SunIcon() else MoonPhaseIcon(t.moonIllumination.toFloat(), t.moonWaxing)
}

@Composable
fun SunIcon() {
    Canvas(Modifier.size(28.dp)) {
        val w = size.minDimension
        val st = Stroke(width = 2.dp.toPx())
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
    }
}

/** Lua com a fase real: illum 0 = nova, 1 = cheia; a crescer = iluminada à direita (hemisfério norte). */
@Composable
fun MoonPhaseIcon(illum: Float, waxing: Boolean, sizeDp: Int = 28) {
    Canvas(Modifier.size(sizeDp.dp)) {
        val w = size.minDimension
        val r = w * 0.375f
        val cx = w / 2; val cy = w / 2
        drawCircle(MoonDark, radius = r, center = Offset(cx, cy))
        drawCircle(MoonEdge, radius = r, center = Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
        val k = illum.coerceIn(0f, 1f)
        if (k < 0.01f) return@Canvas
        if (k > 0.99f) { drawCircle(MoonLit, radius = r, center = Offset(cx, cy)); return@Canvas }
        val e = r * kotlin.math.abs(1f - 2f * k)
        val path = androidx.compose.ui.graphics.Path().apply {
            arcTo(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r), -90f, 180f, true)
            arcTo(androidx.compose.ui.geometry.Rect(cx - e, cy - r, cx + e, cy + r), 90f, if (k < 0.5f) -180f else 180f, false)
            close()
        }
        if (waxing) {
            drawPath(path, MoonLit)
        } else {
            scale(-1f, 1f, pivot = Offset(cx, cy)) { drawPath(path, MoonLit) }
        }
    }
}

private val MoonDark = Color(0xFF232A38)
private val MoonEdge = Color(0xFF3A4256)
private val MoonLit = Color(0xFFC9D3E6)

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
