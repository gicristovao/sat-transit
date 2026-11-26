package pt.gicristovao.sattransit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pt.gicristovao.sattransit.engine.Ephemeris
import pt.gicristovao.sattransit.engine.Transit
import kotlin.math.abs
import kotlin.math.hypot

private val SkyBg = Color(0xFF05070B)
private val TrackColor = Color(0xFFE6E8EE)

/** Vista do céu à volta do disco: Sol ou Lua (com a fase) e a passagem da ISS com o sentido do movimento. */
@Composable
fun SkyThumbnail(t: Transit, size: Dp = 104.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).clip(RoundedCornerShape(12.dp)).background(SkyBg)) {
        val w = this.size.minDimension
        val c = Offset(w / 2, w / 2)
        val field = if (t.skyFieldArcsec > 0) t.skyFieldArcsec else t.bodyRadiusArcsec * 1.7
        val k = (w / 2) / field.toFloat() // px por segundo de arco
        val r = maxOf(2.dp.toPx(), (t.bodyRadiusArcsec * k).toFloat())

        if (t.body == Ephemeris.Body.SUN) {
            drawCircle(C.Amber, radius = r, center = c)
        } else {
            drawMoon(c, r, t.moonIllumination.toFloat(), Math.toDegrees(t.moonBrightLimbRad).toFloat())
        }

        val trk = t.skyTrack ?: return@Canvas
        val n = trk.size / 2
        if (n < 2) return@Canvas
        val pts = List(n) { Offset(c.x + trk[2 * it] * k, c.y - trk[2 * it + 1] * k) }
        clipRect(0f, 0f, w, w) {
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until n) lineTo(pts[i].x, pts[i].y)
            }
            drawPath(path, TrackColor, style = Stroke(width = 1.4.dp.toPx()))

            // seta no ponto de maior aproximação, a apontar no sentido do movimento
            var iMin = 0
            var dMin = Float.MAX_VALUE
            for (i in 0 until n) {
                val d = hypot(trk[2 * i], trk[2 * i + 1])
                if (d < dMin) { dMin = d; iMin = i }
            }
            val i0 = (iMin - 2).coerceAtLeast(0)
            val i1 = (iMin + 2).coerceAtMost(n - 1)
            var ux = pts[i1].x - pts[i0].x
            var uy = pts[i1].y - pts[i0].y
            val len = hypot(ux, uy)
            if (len > 0f) {
                ux /= len; uy /= len
                // se o ponto mais próximo ficar dentro do disco, a seta vai para fora dele, à frente
                var base = pts[iMin]
                if (hypot(base.x - c.x, base.y - c.y) < r + 6.dp.toPx()) {
                    val ahead = (iMin until n).firstOrNull { hypot(pts[it].x - c.x, pts[it].y - c.y) > r + 10.dp.toPx() }
                    if (ahead != null) base = pts[ahead]
                }
                drawChevron(base, ux, uy)
            }
        }
    }
}

private fun DrawScope.drawChevron(at: Offset, ux: Float, uy: Float) {
    val nx = -uy
    val ny = ux
    val tip = Offset(at.x + ux * 4.dp.toPx(), at.y + uy * 4.dp.toPx())
    val back = Offset(tip.x - ux * 7.dp.toPx(), tip.y - uy * 7.dp.toPx())
    val wing = 4.5.dp.toPx()
    val p = Path().apply {
        moveTo(back.x + nx * wing, back.y + ny * wing)
        lineTo(tip.x, tip.y)
        lineTo(back.x - nx * wing, back.y - ny * wing)
    }
    drawPath(p, TrackColor, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Lua com a fase, com o lado iluminado virado para limbDeg (0 = direita, 90 = cima). */
private fun DrawScope.drawMoon(c: Offset, r: Float, illum: Float, limbDeg: Float) {
    drawCircle(Color(0xFF232A38), radius = r, center = c)
    drawCircle(Color(0xFF3A4256), radius = r, center = c, style = Stroke(width = 1f))
    val kf = illum.coerceIn(0f, 1f)
    if (kf < 0.01f) return
    val lit = Color(0xFFC9D3E6)
    if (kf > 0.99f) { drawCircle(lit, radius = r, center = c); return }
    val e = r * abs(1f - 2f * kf)
    val path = Path().apply {
        arcTo(Rect(c.x - r, c.y - r, c.x + r, c.y + r), -90f, 180f, true)
        arcTo(Rect(c.x - e, c.y - r, c.x + e, c.y + r), 90f, if (kf < 0.5f) -180f else 180f, false)
        close()
    }
    // o caminho tem o lado iluminado à direita (0°); no ecrã o y cresce para baixo
    rotate(degrees = -limbDeg, pivot = c) { drawPath(path, lit) }
}
