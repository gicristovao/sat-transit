package pt.gicristovao.sattransit.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import pt.gicristovao.sattransit.engine.Ephemeris
import pt.gicristovao.sattransit.engine.Transit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

/**
 * Mapa com a faixa de visibilidade, a linha central e a posição do observador.
 * interactive = false: sem gestos (o mapa pequeno só serve para abrir o grande).
 * pin + onLongPress: pino que se move com toque longo.
 */
@Composable
fun BandMap(
    t: Transit,
    obsLat: Double,
    obsLon: Double,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    pin: LatLng? = null,
    onLongPress: ((LatLng) -> Unit)? = null,
    topPaddingDp: Int = 28,
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var pinSource by remember { mutableStateOf<GeoJsonSource?>(null) }
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
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)

    LaunchedEffect(t) {
        mapView.getMapAsync { map ->
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.uiSettings.isCompassEnabled = false
            if (!interactive) map.uiSettings.setAllGesturesEnabled(false)
            map.addOnMapLongClickListener { p -> longPress?.invoke(p); longPress != null }
            map.setStyle(Style.Builder().fromUri(MAP_STYLE)) { style ->
                val color = if (t.body == Ephemeris.Body.SUN) "#E39A0B" else "#3D6FD1"
                val pts = centerlinePoints(t)
                if (pts.size >= 2) {
                    val right = ArrayList<Point>()
                    val left = ArrayList<Point>()
                    for (i in pts.indices) {
                        val brg = bearing(pts[maxOf(i - 1, 0)], pts[minOf(i + 1, pts.size - 1)])
                        right += offset(pts[i], brg + 90, t.halfWidthRightKm)
                        left += offset(pts[i], brg - 90, t.halfWidthLeftKm)
                    }
                    val ring = ArrayList<Point>(right)
                    ring.addAll(left.reversed())
                    ring.add(right.first())
                    style.addSource(GeoJsonSource("band", Polygon.fromLngLats(listOf(ring))))
                    style.addLayer(FillLayer("band-fill", "band").withProperties(fillColor(color), fillOpacity(0.25f)))
                    style.addSource(GeoJsonSource("center", LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) })))
                    style.addLayer(
                        LineLayer("center-line", "center").withProperties(
                            lineColor(color), lineWidth(2.5f), lineOpacity(0.95f), lineDasharray(arrayOf(3f, 2f))
                        )
                    )
                }
                style.addSource(GeoJsonSource("obs", Point.fromLngLat(obsLon, obsLat)))
                style.addLayer(
                    CircleLayer("obs-pt", "obs").withProperties(
                        circleRadius(7f), circleColor("#2F6FEB"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)
                    )
                )
                if (onLongPress != null) {
                    style.addImage("pin", pinBitmap(context.resources.displayMetrics.density))
                    val src = GeoJsonSource("pin-src")
                    style.addSource(src)
                    style.addLayer(
                        SymbolLayer("pin-layer", "pin-src").withProperties(
                            iconImage("pin"), iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                            iconAllowOverlap(true), iconIgnorePlacement(true)
                        )
                    )
                    pinSource = src
                }
                val foot = LatLng(t.centerLat, t.centerLon)
                val b = LatLngBounds.Builder().include(LatLng(obsLat, obsLon)).include(foot)
                val trk = t.trackBearingDeg
                val margin = maxOf(t.halfWidthLeftKm, t.halfWidthRightKm) + 3.0
                listOf(trk to 15.0, trk + 180 to 15.0, trk + 90 to margin, trk - 90 to margin).forEach { (dir, km) ->
                    val p = offset(foot, dir, km)
                    b.include(LatLng(p.latitude(), p.longitude()))
                }
                // Margens em dp: a tua posição e a faixa ficam sempre visíveis, longe das bordas.
                val d = context.resources.displayMetrics.density
                val side = (28 * d).toInt()
                map.moveCamera(
                    CameraUpdateFactory.newLatLngBounds(b.build(), side, (topPaddingDp * d).toInt(), side, (36 * d).toInt())
                )
            }
        }
    }

    LaunchedEffect(pin, pinSource) {
        val src = pinSource ?: return@LaunchedEffect
        if (pin != null) src.setGeoJson(Point.fromLngLat(pin.longitude, pin.latitude))
    }
}

fun centerlinePoints(t: Transit): List<LatLng> =
    (0 until t.centerline.size / 2).map { LatLng(t.centerline[2 * it], t.centerline[2 * it + 1]) }

/** Distância (km) de um ponto à faixa de visibilidade; 0 se estiver dentro. */
fun distanceToBandKm(t: Transit, lat: Double, lon: Double): Double {
    val pts = centerlinePoints(t)
    if (pts.size < 2) return Double.MAX_VALUE
    val kx = 111.32 * cos(Math.toRadians(lat))
    val ky = 110.57
    var best = Double.MAX_VALUE
    var side = 1
    for (i in 0 until pts.size - 1) {
        val x1 = (pts[i].longitude - lon) * kx; val y1 = (pts[i].latitude - lat) * ky
        val x2 = (pts[i + 1].longitude - lon) * kx; val y2 = (pts[i + 1].latitude - lat) * ky
        val vx = x2 - x1; val vy = y2 - y1
        val vv = vx * vx + vy * vy
        val u = if (vv == 0.0) 0.0 else (-(x1 * vx + y1 * vy) / vv).coerceIn(0.0, 1.0)
        val fx = x1 + u * vx; val fy = y1 + u * vy
        val d = hypot(fx, fy)
        if (d < best) {
            best = d
            side = if ((-fx * vy + fy * vx) >= 0) 1 else -1
        }
    }
    val half = if (side >= 0) t.halfWidthRightKm else t.halfWidthLeftKm
    return maxOf(0.0, best - half)
}

internal fun pinBitmap(density: Float): Bitmap {
    val w = (30 * density).toInt()
    val h = (40 * density).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val r = w * 0.42f
    val cx = w / 2f
    val cy = r + 2 * density
    val path = Path().apply {
        moveTo(cx, h - 1f)
        lineTo(cx - r * 0.82f, cy + r * 0.55f)
        addCircle(cx, cy, r, Path.Direction.CW)
        moveTo(cx, h - 1f)
        lineTo(cx + r * 0.82f, cy + r * 0.55f)
        lineTo(cx - r * 0.82f, cy + r * 0.55f)
        close()
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5A524.toInt(); style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0E14.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * density }
    c.drawPath(path, fill)
    c.drawCircle(cx, cy, r, stroke)
    c.drawCircle(cx, cy, r * 0.38f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0E14.toInt() })
    return bmp
}

private fun bearing(a: LatLng, b: LatLng): Double {
    val dx = (b.longitude - a.longitude) * cos(Math.toRadians(a.latitude))
    val dy = b.latitude - a.latitude
    return Math.toDegrees(atan2(dx, dy))
}

private fun offset(p: LatLng, bearingDeg: Double, km: Double): Point {
    val r = Math.toRadians(bearingDeg)
    val lat = p.latitude + km * cos(r) / 110.57
    val lon = p.longitude + km * sin(r) / (111.32 * cos(Math.toRadians(p.latitude)))
    return Point.fromLngLat(lon, lat)
}
