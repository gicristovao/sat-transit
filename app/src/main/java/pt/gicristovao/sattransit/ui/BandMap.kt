package pt.gicristovao.sattransit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import pt.gicristovao.sattransit.engine.Ephemeris
import pt.gicristovao.sattransit.engine.Transit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

const val MAP_STYLE = "https://tiles.openfreemap.org/styles/dark"

/** Mapa com a faixa de visibilidade, a linha central e a posição do observador. */
@Composable
fun BandMap(t: Transit, obsLat: Double, obsLon: Double, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

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
            map.setStyle(Style.Builder().fromUri(MAP_STYLE)) { style ->
                val color = if (t.body == Ephemeris.Body.SUN) "#F5A524" else "#9DB4E8"
                val pts = (0 until t.centerline.size / 2).map { LatLng(t.centerline[2 * it], t.centerline[2 * it + 1]) }
                if (pts.size >= 2) {
                    val right = ArrayList<Point>()
                    val left = ArrayList<Point>()
                    for (i in pts.indices) {
                        val a = pts[maxOf(i - 1, 0)]
                        val b = pts[minOf(i + 1, pts.size - 1)]
                        val brg = bearing(a, b)
                        right += offset(pts[i], brg + 90, t.halfWidthRightKm)
                        left += offset(pts[i], brg - 90, t.halfWidthLeftKm)
                    }
                    val ring = ArrayList<Point>(right)
                    ring.addAll(left.reversed())
                    ring.add(right.first())
                    style.addSource(GeoJsonSource("band", Polygon.fromLngLats(listOf(ring))))
                    style.addLayer(FillLayer("band-fill", "band").withProperties(fillColor(color), fillOpacity(0.22f)))
                    style.addSource(GeoJsonSource("center", LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) })))
                    style.addLayer(
                        LineLayer("center-line", "center").withProperties(
                            lineColor(color), lineWidth(2f), lineOpacity(0.95f), lineDasharray(arrayOf(3f, 2f))
                        )
                    )
                }
                style.addSource(GeoJsonSource("obs", Point.fromLngLat(obsLon, obsLat)))
                style.addLayer(
                    CircleLayer("obs-pt", "obs").withProperties(
                        circleRadius(7f), circleColor("#9DB4E8"), circleStrokeColor("#0B0E14"), circleStrokeWidth(3f)
                    )
                )
                val foot = LatLng(t.centerLat, t.centerLon)
                val b = LatLngBounds.Builder()
                    .include(LatLng(obsLat, obsLon))
                    .include(foot)
                val trk = t.trackBearingDeg
                val margin = maxOf(t.halfWidthLeftKm, t.halfWidthRightKm) + 3.0
                listOf(trk to 15.0, trk + 180 to 15.0, trk + 90 to margin, trk - 90 to margin).forEach { (dir, km) ->
                    val p = offset(foot, dir, km)
                    b.include(LatLng(p.latitude(), p.longitude()))
                }
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 60))
            }
        }
    }
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
