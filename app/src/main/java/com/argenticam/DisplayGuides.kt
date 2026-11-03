package com.argenticam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2

/** Grilles de composition du viseur. */
enum class GridType(val label: String) {
    NONE("Aucune"),
    THIRDS("Tiers"),
    GOLDEN("Nombre d'or"),
    SQUARES("Quadrillage"),
    DIAGONALS("Diagonales"),
    CENTER("Centre"),
}

/** Ce qu'affiche le viseur (réglé par le bouton en haut à droite). */
data class DisplayPrefs(
    val grid: GridType = GridType.THIRDS,
    val level: Boolean = false,
    val info: Boolean = true,
    val corners: Boolean = true,
)

object DisplayStore {
    private const val PREFS = "display"

    fun load(context: Context): DisplayPrefs {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return DisplayPrefs(
            grid = GridType.entries.firstOrNull { it.name == p.getString("grid", null) } ?: GridType.THIRDS,
            level = p.getBoolean("level", false),
            info = p.getBoolean("info", true),
            corners = p.getBoolean("corners", true),
        )
    }

    fun save(context: Context, d: DisplayPrefs) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("grid", d.grid.name)
            .putBoolean("level", d.level)
            .putBoolean("info", d.info)
            .putBoolean("corners", d.corners)
            .apply()
    }
}

/**
 * Inclinaison du téléphone autour de l'axe de visée, en degrés (0 = droit en portrait),
 * ou null si le téléphone est presque à plat (le niveau n'a alors pas de sens).
 */
@Composable
fun rememberRoll(enabled: Boolean): State<Float?> {
    val context = LocalContext.current
    val roll = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(enabled) {
        if (!enabled) {
            roll.value = null
            return@DisposableEffect onDispose {}
        }
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var smooth: Float? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                if (abs(z) > 8.5f) {
                    roll.value = null
                    return
                }
                val r = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
                val s = smooth?.let { it + (r - it) * 0.25f } ?: r
                smooth = s
                roll.value = s
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return roll
}

/**
 * Aides à la composition, dessinées dans [area] (la fenêtre de l'image : tout le viseur,
 * ou le carré d'un Polaroid, la vue d'une bande 35 mm...).
 */
@Composable
fun CompositionGuides(prefs: DisplayPrefs, area: (Float, Float) -> Rect, roll: Float?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = area(size.width, size.height)
        val faint = Color.White.copy(alpha = 0.22f)
        val thin = 1.dp.toPx()
        fun v(x: Float) = drawLine(faint, Offset(x, r.top), Offset(x, r.bottom), strokeWidth = thin)
        fun h(y: Float) = drawLine(faint, Offset(r.left, y), Offset(r.right, y), strokeWidth = thin)

        when (prefs.grid) {
            GridType.NONE -> Unit
            GridType.THIRDS -> for (f in listOf(1f / 3f, 2f / 3f)) {
                v(r.left + r.width * f); h(r.top + r.height * f)
            }
            GridType.GOLDEN -> for (f in listOf(0.382f, 0.618f)) {
                v(r.left + r.width * f); h(r.top + r.height * f)
            }
            GridType.SQUARES -> {
                val cell = r.width / 4f
                var x = r.left + cell
                while (x < r.right - 1f) { v(x); x += cell }
                var y = r.top + cell
                while (y < r.bottom - 1f) { h(y); y += cell }
            }
            GridType.DIAGONALS -> {
                drawLine(faint, Offset(r.left, r.top), Offset(r.right, r.bottom), strokeWidth = thin)
                drawLine(faint, Offset(r.right, r.top), Offset(r.left, r.bottom), strokeWidth = thin)
            }
            GridType.CENTER -> {
                val c = r.center
                val l = 14.dp.toPx()
                val strong = Color.White.copy(alpha = 0.6f)
                drawLine(strong, Offset(c.x - l, c.y), Offset(c.x + l, c.y), strokeWidth = 1.5.dp.toPx())
                drawLine(strong, Offset(c.x, c.y - l), Offset(c.x, c.y + l), strokeWidth = 1.5.dp.toPx())
                drawCircle(faint, r.width * 0.18f, c, style = Stroke(thin))
            }
        }

        if (prefs.corners) corners(r)
        if (prefs.level && roll != null) level(r, roll)
    }
}

private fun DrawScope.corners(r: Rect) {
    val line = Color.White.copy(alpha = 0.7f)
    val inset = 14.dp.toPx()
    val l = r.left + inset
    val t = r.top + inset
    val rr = r.right - inset
    val b = r.bottom - inset
    val len = 22.dp.toPx()
    val sw = 2.dp.toPx()
    fun seg(a: Offset, c: Offset) = drawLine(line, a, c, strokeWidth = sw, cap = StrokeCap.Round)
    seg(Offset(l, t), Offset(l + len, t)); seg(Offset(l, t), Offset(l, t + len))
    seg(Offset(rr, t), Offset(rr - len, t)); seg(Offset(rr, t), Offset(rr, t + len))
    seg(Offset(l, b), Offset(l + len, b)); seg(Offset(l, b), Offset(l, b - len))
    seg(Offset(rr, b), Offset(rr - len, b)); seg(Offset(rr, b), Offset(rr, b - len))
}

/** Niveau : une ligne qui reste parallèle à l'horizon, ambre quand le téléphone est droit. */
private fun DrawScope.level(r: Rect, roll: Float) {
    val c = r.center
    val straight = abs(roll) < 1f
    val color = if (straight) Amber else Color.White.copy(alpha = 0.85f)
    val half = r.width * 0.22f
    val gap = 10.dp.toPx()
    val sw = 2.dp.toPx()
    // repères fixes de l'écran
    val tick = Color.White.copy(alpha = 0.5f)
    drawLine(tick, Offset(c.x - half - 16.dp.toPx(), c.y), Offset(c.x - half - 6.dp.toPx(), c.y), strokeWidth = sw)
    drawLine(tick, Offset(c.x + half + 6.dp.toPx(), c.y), Offset(c.x + half + 16.dp.toPx(), c.y), strokeWidth = sw)
    // l'horizon
    rotate(roll, c) {
        drawLine(color, Offset(c.x - half, c.y), Offset(c.x - gap, c.y), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(c.x + gap, c.y), Offset(c.x + half, c.y), strokeWidth = sw, cap = StrokeCap.Round)
    }
}
