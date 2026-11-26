package pt.gicristovao.sattransit.data

import android.content.Context
import kotlin.math.sqrt

/** Tipos de sensor; o tamanho físico é o que conta para o cálculo. */
enum class SensorType(val widthMm: Double, val heightMm: Double) {
    FULL_FRAME(36.0, 24.0),
    APSC(23.5, 15.6),
    APSC_CANON(22.3, 14.9),
    MICRO_43(17.3, 13.0),
    MEDIUM_FORMAT(44.0, 33.0),
    CUSTOM(36.0, 24.0),
}

data class PreviewSettings(
    val sensor: SensorType = SensorType.FULL_FRAME,
    val customW: Double = 36.0,
    val customH: Double = 24.0,
    val megapixels: Double = 45.0,
    val focalMm: Int = 1000,
    val fps: Int = 12,
    val portrait: Boolean = false,
) {
    val widthMm get() = if (sensor == SensorType.CUSTOM) customW else sensor.widthMm
    val heightMm get() = if (sensor == SensorType.CUSTOM) customH else sensor.heightMm
    /** Tamanho de um píxel (mm). */
    val pixelPitchMm get() = sqrt(widthMm * heightMm / (megapixels * 1e6))

    companion object {
        private const val PREFS = "preview"

        fun load(context: Context): PreviewSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = PreviewSettings()
            return PreviewSettings(
                sensor = runCatching { SensorType.valueOf(p.getString("sensor", d.sensor.name)!!) }.getOrDefault(d.sensor),
                customW = p.getFloat("customW", d.customW.toFloat()).toDouble(),
                customH = p.getFloat("customH", d.customH.toFloat()).toDouble(),
                megapixels = p.getFloat("mp", d.megapixels.toFloat()).toDouble(),
                focalMm = p.getInt("focal", d.focalMm),
                fps = p.getInt("fps", d.fps),
                portrait = p.getBoolean("portrait", d.portrait),
            )
        }

        fun save(context: Context, s: PreviewSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("sensor", s.sensor.name)
                .putFloat("customW", s.customW.toFloat())
                .putFloat("customH", s.customH.toFloat())
                .putFloat("mp", s.megapixels.toFloat())
                .putInt("focal", s.focalMm)
                .putInt("fps", s.fps)
                .putBoolean("portrait", s.portrait)
                .apply()
        }
    }
}
