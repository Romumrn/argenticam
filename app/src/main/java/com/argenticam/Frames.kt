package com.argenticam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.roundToInt

/** Formats de prise de vue. */
enum class PhotoFormat(val label: String, val short: String) {
    STANDARD("Standard 3:4", "3:4"),
    POLAROID("Polaroid ▢", "POLAROID"),
    FILM35("Pellicule 35 mm", "35 MM"),
    DISPOSABLE("Jetable", "JETABLE"),
    ;

    /** Cadre dessiné autour de la photo (null = photo pleine, sans cadre). */
    val frame: PrintFrame?
        get() = when (this) {
            POLAROID -> PolaroidFrame
            FILM35 -> Film35Frame
            else -> null
        }
}

/** Ce qui est imprimé sur les bords d'une bande de film. */
data class EdgeInfo(val filmName: String, val frameNumber: Int)

/**
 * Un tirage "encadré" : dimensions en millimètres et fenêtre de l'image.
 * Le même dessin sert pour le viseur (canvas de Compose) et pour la photo finale (canvas d'un
 * Bitmap) : ce qu'on voit dans le viseur est ce qu'on obtient.
 */
abstract class PrintFrame(
    val printW: Float,
    val printH: Float,
    val holeX: Float,
    val holeY: Float,
    val holeW: Float,
    val holeH: Float,
) {
    /** Place du tirage dans le viseur (centré, un peu de marge). */
    fun printRect(w: Float, h: Float): RectF {
        val k = minOf(w * 0.9f / printW, h * 0.94f / printH)
        val pw = printW * k
        val ph = printH * k
        val l = (w - pw) / 2f
        val t = (h - ph) / 2f
        return RectF(l, t, l + pw, t + ph)
    }

    /** Fenêtre de l'image dans le viseur. */
    fun holeRect(w: Float, h: Float): RectF {
        val p = printRect(w, h)
        val k = p.width() / printW
        val l = p.left + holeX * k
        val t = p.top + holeY * k
        return RectF(l, t, l + holeW * k, t + holeH * k)
    }

    /** Fenêtre de l'image en fractions du viseur 3:4 (origine en haut à gauche) : sert à recadrer la photo. */
    val cropFraction: RectF by lazy {
        holeRect(1f, 4f / 3f).let { RectF(it.left, it.top / (4f / 3f), it.right, it.bottom / (4f / 3f)) }
    }

    /** Même zone pour le shader de l'aperçu : x, y, l, h avec l'origine en bas à gauche. */
    val shaderFrame: FloatArray by lazy {
        cropFraction.let { floatArrayOf(it.left, 1f - it.bottom, it.width(), it.height()) }
    }

    /** Recadre la photo (3:4 portrait, à l'endroit) sur la fenêtre visée. */
    fun crop(src: Bitmap): Bitmap {
        val c = cropFraction
        val x = (c.left * src.width).roundToInt()
        val y = (c.top * src.height).roundToInt()
        val w = minOf((c.width() * src.width).roundToInt(), src.width - x)
        val h = minOf((c.height() * src.height).roundToInt(), src.height - y)
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    /** Pose l'image développée dans le cadre, à pleine résolution. */
    fun compose(image: Bitmap, edge: EdgeInfo): Bitmap {
        val k = image.width / holeW
        val w = (printW * k).roundToInt()
        val h = (printH * k).roundToInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(image, holeX * k, holeY * k, Paint(Paint.FILTER_BITMAP_FLAG))
        drawBorder(canvas, 0f, 0f, k, edge)
        return out
    }

    /**
     * Dessine tout le cadre SAUF la fenêtre de l'image (qui reste intacte), pour un tirage dont
     * le coin est en ([left], [top]) et où 1 mm = [k] pixels.
     */
    abstract fun drawBorder(canvas: Canvas, left: Float, top: Float, k: Float, edge: EdgeInfo)

    /**
     * Remplit le tirage autour de la fenêtre avec 4 rectangles pleins
     * (pas de forme trouée : certains téléphones les remplissent entièrement).
     */
    protected fun fillAroundHole(canvas: Canvas, left: Float, top: Float, k: Float, paint: Paint) {
        val pl = left
        val pt = top
        val pr = left + printW * k
        val pb = top + printH * k
        val hl = left + holeX * k
        val ht = top + holeY * k
        val hr = hl + holeW * k
        val hb = ht + holeH * k
        canvas.drawRect(pl, pt, pr, ht, paint)
        canvas.drawRect(pl, hb, pr, pb, paint)
        canvas.drawRect(pl, ht, hl, hb, paint)
        canvas.drawRect(hr, ht, pr, hb, paint)
    }
}

/** Polaroid type 600 : 88 x 107 mm, image carrée de 79 mm. */
object PolaroidFrame : PrintFrame(
    Polaroid.PRINT_W, Polaroid.PRINT_H, Polaroid.SIDE, Polaroid.TOP, Polaroid.IMAGE, Polaroid.IMAGE,
) {
    private val paper = Paint().apply { color = 0xFFF3F0E8.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x26000000
    }

    override fun drawBorder(canvas: Canvas, left: Float, top: Float, k: Float, edge: EdgeInfo) {
        fillAroundHole(canvas, left, top, k, paper)
        // léger liseré sombre à la jonction image / papier, comme sur un vrai tirage
        line.strokeWidth = maxOf(1f, k * 0.2f)
        val l = left + holeX * k
        val t = top + holeY * k
        canvas.drawRect(l, t, l + holeW * k, t + holeH * k, line)
    }
}

/**
 * Bande de pellicule 35 mm scannée, à la verticale : vue de 24 x 36 mm, perforations
 * de part et d'autre (pas de 4,75 mm), nom du film et numéros de vue imprimés sur les bords.
 */
object Film35Frame : PrintFrame(35f, 44f, 5.5f, 4f, 24f, 36f) {
    private val film = Paint().apply { color = 0xFF15100C.toInt() }
    private val perf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEAE3.toInt() }
    private val print = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9E8A33C.toInt() // marquage orangé du bord de film
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.12f
    }

    @Synchronized
    override fun drawBorder(canvas: Canvas, left: Float, top: Float, k: Float, edge: EdgeInfo) {
        fillAroundHole(canvas, left, top, k, film)

        // Perforations (lumière du scanner à travers les trous)
        val r = 0.45f * k
        var y = 0.6f
        while (y + 2.79f <= printH) {
            val yt = top + y * k
            val yb = yt + 2.79f * k
            canvas.drawRoundRect(left + 1.55f * k, yt, left + 3.53f * k, yb, r, r, perf)
            canvas.drawRoundRect(left + (printW - 3.53f) * k, yt, left + (printW - 1.55f) * k, yb, r, r, perf)
            y += 4.75f
        }

        // Marquages le long des bords (texte vertical, de haut en bas)
        print.textSize = 1.0f * k
        val n = edge.frameNumber
        vertical(canvas, left + 0.45f * k, top + 1.5f * k, "${edge.filmName}  ◀  ARGENTICAM")
        vertical(canvas, left + (printW - 1.3f) * k, top + 5f * k, "▶ $n")
        vertical(canvas, left + (printW - 1.3f) * k, top + 26f * k, "${n}A")
    }

    private fun vertical(canvas: Canvas, x: Float, y: Float, text: String) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(90f)
        canvas.drawText(text, 0f, 0f, print)
        canvas.restore()
    }
}

/** Le cadre dessiné par-dessus le viseur : noir autour du tirage, puis le cadre lui-même. */
@Composable
fun FrameViewfinderMask(frame: PrintFrame, edge: EdgeInfo, modifier: Modifier = Modifier) {
    ComposeCanvas(modifier) {
        val p = frame.printRect(size.width, size.height)
        val ink = Paint().apply { color = 0xFF0B0B0B.toInt() }
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.drawRect(0f, 0f, size.width, p.top, ink)
            nc.drawRect(0f, p.bottom, size.width, size.height, ink)
            nc.drawRect(0f, p.top, p.left, p.bottom, ink)
            nc.drawRect(p.right, p.top, size.width, p.bottom, ink)
            frame.drawBorder(nc, p.left, p.top, p.width() / frame.printW, edge)
        }
    }
}
