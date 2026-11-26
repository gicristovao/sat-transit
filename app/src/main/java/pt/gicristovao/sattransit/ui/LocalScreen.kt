package pt.gicristovao.sattransit.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import pt.gicristovao.sattransit.MainViewModel
import pt.gicristovao.sattransit.R
import pt.gicristovao.sattransit.data.SavedPlace
import pt.gicristovao.sattransit.engine.Ephemeris
import pt.gicristovao.sattransit.engine.Transit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Ecrã "Outro local": pesquisa, pino no mapa, raio, locais guardados. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun LocalScreen(vm: MainViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val chosenPoint = stringResource(R.string.chosen_point)

    val start = remember {
        vm.chosen.value ?: vm.lastGps?.let { SavedPlace(it.name ?: chosenPoint, it.lat, it.lon) }
        ?: SavedPlace("Portugal", 39.6, -8.0)
    }
    var pin by remember { mutableStateOf(start) }
    var radius by remember { mutableIntStateOf(vm.places.radiusKm) }
    var recenter by remember { mutableIntStateOf(0) }
    var saved by remember { mutableStateOf(vm.places.saved()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SavedPlace>?>(null) }
    var lines by remember { mutableStateOf<List<Transit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    // linhas centrais dos trânsitos à volta do pino (recalculadas quando o pino ou o raio mudam)
    LaunchedEffect(pin.lat, pin.lon, radius) {
        delay(600)
        busy = true
        lines = runCatching { vm.transitsAt(pin.lat, pin.lon, radius) }.getOrDefault(emptyList())
        busy = false
    }

    fun runSearch() {
        focus.clearFocus()
        scope.launch { results = vm.placeSearch.search(query) }
    }

    Column(
        Modifier.fillMaxSize().background(C.Ink).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
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
            Text(stringResource(R.string.other_place), color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }

        // pesquisa
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(C.Surface)
                .border(1.dp, C.CardLine, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(Modifier.size(18.dp)) {
                val w = size.minDimension
                drawCircle(C.Muted, radius = w * 0.3f, center = Offset(w * 0.45f, w * 0.45f), style = Stroke(2.dp.toPx()))
                drawLine(C.Muted, Offset(w * 0.67f, w * 0.67f), Offset(w * 0.95f, w * 0.95f), strokeWidth = 2.dp.toPx())
            }
            Box(Modifier.weight(1f).padding(start = 10.dp)) {
                if (query.isEmpty()) Text(stringResource(R.string.search_place), color = C.Faint, fontSize = 15.sp)
                BasicTextField(
                    value = query, onValueChange = { query = it; results = null }, singleLine = true,
                    textStyle = TextStyle(color = C.Text, fontSize = 15.sp), cursorBrush = SolidColor(C.Amber),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).border(1.dp, C.CardLine, RoundedCornerShape(16.dp))) {
                    PlaceMap(pin, radius, lines, recenter, Modifier.fillMaxSize()) { ll ->
                        pin = SavedPlace(chosenPoint, ll.latitude, ll.longitude)
                        scope.launch { vm.placeSearch.nameOf(ll.latitude, ll.longitude)?.let { n -> if (pin.lat == ll.latitude) pin = pin.copy(name = n) } }
                    }
                    Text(
                        stringResource(R.string.long_press_place), color = C.Text, fontSize = 12.sp,
                        modifier = Modifier.padding(10.dp).clip(RoundedCornerShape(6.dp)).background(C.Ink.copy(alpha = 0.85f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    LegendLine(Color(0xFFE39A0B), stringResource(R.string.sun))
                    LegendLine(Color(0xFF3D6FD1), stringResource(R.string.moon))
                    Text(
                        if (busy) stringResource(R.string.calculating) else stringResource(R.string.centerlines_legend, MainViewModel.DAYS),
                        color = C.Muted, fontSize = 12.sp
                    )
                }
            }
            // resultados da pesquisa por cima do mapa
            results?.let { list ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface).border(1.dp, C.Line, RoundedCornerShape(12.dp))
                ) {
                    if (list.isEmpty()) {
                        Text(stringResource(R.string.no_places_found), color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(14.dp))
                    }
                    list.forEach { p ->
                        Text(
                            p.name, color = C.Text, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().clickable {
                                pin = p; recenter++; results = null; query = ""
                            }.padding(horizontal = 14.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }

        // local escolhido
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(pin.name, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val west = androidx.compose.ui.res.stringArrayResource(R.array.compass)[12]
            Text(
                String.format(Locale.getDefault(), "%.3f° %s · %.3f° %s", abs(pin.lat), if (pin.lat >= 0) "N" else "S", abs(pin.lon), if (pin.lon >= 0) "E" else west),
                color = C.Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace
            )
        }

        // raio
        Column {
            Row {
                Text(stringResource(R.string.search_radius), color = C.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("$radius km", color = C.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }
            Slider(
                value = radius.toFloat(), onValueChange = { radius = ((it / 5).roundToInt() * 5).coerceIn(5, 200) },
                valueRange = 5f..200f,
                colors = SliderDefaults.colors(thumbColor = C.Amber, activeTrackColor = C.Amber, inactiveTrackColor = C.Line)
            )
        }

        // locais guardados
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            saved.forEach { p ->
                Box(
                    Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, C.Line, RoundedCornerShape(20.dp))
                        .combinedClickable(
                            onClick = { pin = p; recenter++ },
                            onLongClick = {
                                saved = saved - p
                                vm.places.setSaved(saved)
                                Toast.makeText(context, context.getString(R.string.place_removed, p.name), Toast.LENGTH_SHORT).show()
                            }
                        ).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) { Text(p.name.substringBefore(","), color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            }
            Box(
                Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, C.Line, RoundedCornerShape(20.dp))
                    .clickable {
                        if (saved.none { it.lat == pin.lat && it.lon == pin.lon }) {
                            saved = saved + pin
                            vm.places.setSaved(saved)
                            Toast.makeText(context, context.getString(R.string.place_saved), Toast.LENGTH_SHORT).show()
                        }
                    }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) { Text(stringResource(R.string.save_place), color = C.Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }

        Button(
            onClick = { vm.useOther(pin, radius); onDone() },
            colors = ButtonDefaults.buttonColors(containerColor = C.Amber, contentColor = C.Ink),
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) { Text(stringResource(R.string.see_transits_here), fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
    }
}

@Composable
private fun LegendLine(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.width(16.dp).size(width = 16.dp, height = 4.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width * 0.4f, size.height / 2), strokeWidth = 3.dp.toPx())
            drawLine(color, Offset(size.width * 0.6f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 3.dp.toPx())
        }
        Text(label, color = C.Muted, fontSize = 12.sp)
    }
}

/** Mapa do outro local: pino, círculo do raio e linhas centrais dos trânsitos. */
@Composable
private fun PlaceMap(
    pin: SavedPlace,
    radiusKm: Int,
    lines: List<Transit>,
    recenterKey: Int,
    modifier: Modifier,
    onLongPress: (LatLng) -> Unit,
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var style by remember { mutableStateOf<Style?>(null) }
    var map by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    val longPress by rememberUpdatedState(onLongPress)

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)

    LaunchedEffect(Unit) {
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.addOnMapLongClickListener { p -> longPress(p); true }
            m.setStyle(Style.Builder().fromUri(MAP_STYLE)) { st ->
                st.addSource(GeoJsonSource("radius"))
                st.addLayer(FillLayer("radius-fill", "radius").withProperties(fillColor("#2F6FEB"), fillOpacity(0.06f)))
                st.addLayer(LineLayer("radius-line", "radius").withProperties(lineColor("#2F6FEB"), lineWidth(1.5f), lineDasharray(arrayOf(3f, 2f))))
                st.addSource(GeoJsonSource("sun-lines"))
                st.addLayer(LineLayer("sun-l", "sun-lines").withProperties(lineColor("#E39A0B"), lineWidth(2.5f), lineDasharray(arrayOf(3f, 2f))))
                st.addSource(GeoJsonSource("moon-lines"))
                st.addLayer(LineLayer("moon-l", "moon-lines").withProperties(lineColor("#3D6FD1"), lineWidth(2.5f), lineDasharray(arrayOf(3f, 2f))))
                st.addImage("pin", pinBitmap(context.resources.displayMetrics.density))
                st.addSource(GeoJsonSource("pin-src"))
                st.addLayer(
                    SymbolLayer("pin-layer", "pin-src").withProperties(
                        iconImage("pin"), iconAnchor(Property.ICON_ANCHOR_BOTTOM), iconAllowOverlap(true), iconIgnorePlacement(true)
                    )
                )
                style = st
                map = m
            }
        }
    }

    // pino e círculo
    LaunchedEffect(style, pin.lat, pin.lon, radiusKm) {
        val st = style ?: return@LaunchedEffect
        (st.getSource("pin-src") as? GeoJsonSource)?.setGeoJson(Point.fromLngLat(pin.lon, pin.lat))
        val ring = (0..64).map { i ->
            val a = Math.toRadians(i * 360.0 / 64)
            Point.fromLngLat(
                pin.lon + radiusKm * sin(a) / (111.32 * cos(Math.toRadians(pin.lat))),
                pin.lat + radiusKm * cos(a) / 110.57
            )
        }
        (st.getSource("radius") as? GeoJsonSource)?.setGeoJson(Polygon.fromLngLats(listOf(ring)))
    }

    // linhas centrais
    LaunchedEffect(style, lines) {
        val st = style ?: return@LaunchedEffect
        fun fc(body: Ephemeris.Body) = FeatureCollection.fromFeatures(
            lines.filter { it.body == body && it.centerline.size >= 4 }.map { t ->
                Feature.fromGeometry(LineString.fromLngLats((0 until t.centerline.size / 2).map { Point.fromLngLat(t.centerline[2 * it + 1], t.centerline[2 * it]) }))
            }
        )
        (st.getSource("sun-lines") as? GeoJsonSource)?.setGeoJson(fc(Ephemeris.Body.SUN))
        (st.getSource("moon-lines") as? GeoJsonSource)?.setGeoJson(fc(Ephemeris.Body.MOON))
    }

    // centrar no pino com o raio inteiro visível (no início e ao escolher pesquisa ou local guardado)
    LaunchedEffect(map, recenterKey) {
        val m = map ?: return@LaunchedEffect
        val dLat = radiusKm / 110.57
        val dLon = radiusKm / (111.32 * cos(Math.toRadians(pin.lat)))
        val b = LatLngBounds.Builder()
            .include(LatLng(pin.lat + dLat, pin.lon + dLon))
            .include(LatLng(pin.lat - dLat, pin.lon - dLon))
            .build()
        val d = context.resources.displayMetrics.density
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, (16 * d).toInt(), (44 * d).toInt(), (16 * d).toInt(), (16 * d).toInt()))
    }
}
