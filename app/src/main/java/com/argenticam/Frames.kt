package com.argenticam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.roundToInt

/** Catégories de formats, pour s'y retrouver dans le sélecteur. */
enum class FormatCategory(val label: String) {
    STANDARD("STANDARD"),
    INSTANT("INSTANTANÉS"),
    FILM("PELLICULE"),
    FUN("FUN"),
}

/** Formats de prise de vue. */
enum class PhotoFormat(
    val label: String,
    val short: String,
    val category: FormatCategory,
    /** Nombre de déclenchements pour une photo (demi-format : 2, photomaton : 4). */
    val shots: Int = 1,
) {
    STANDARD("Standard 3:4", "3:4", FormatCategory.STANDARD),
    POLAROID("Polaroid 600", "POLAROID", FormatCategory.INSTANT),
    POLAROID_GO("Polaroid Go", "POLA GO", FormatCategory.INSTANT),
    SPECTRA("Polaroid Spectra", "SPECTRA", FormatCategory.INSTANT),
    INSTAX_MINI("Instax Mini", "INSTAX", FormatCategory.INSTANT),
    FILM35("35 mm", "35 MM", FormatCategory.FILM),
    HALF_FRAME("Demi-format", "½ FORMAT", FormatCategory.FILM, shots = 2),
    MEDIUM_6X6("Moyen format 6×6", "6×6", FormatCategory.FILM),
    DISPOSABLE("Jetable", "JETABLE", FormatCategory.FUN),
    PHOTOBOOTH("Photomaton", "PHOTOMATON", FormatCategory.FUN, shots = 4),
    ;

    /** Cadre dessiné autour d'une photo unique (null = photo pleine ou bande multi-photos). */
    val frame: PrintFrame?
        get() = when (this) {
            POLAROID -> PolaroidFrame
            POLAROID_GO -> PolaroidGoFrame
            SPECTRA -> SpectraFrame
            INSTAX_MINI -> InstaxMiniFrame
            FILM35 -> Film35Frame
            MEDIUM_6X6 -> Medium6x6Frame
            else -> null
        }

    /** Tirage instantané : il "sort de l'appareil" et se développe à l'écran. */
    val instantPrint: Boolean get() = frame is PaperFrame

    /** Famille de pellicules qui va avec ce format : le format et la pellicule vont ensemble. */
    val kind: FilmKind
        get() = when (this) {
            POLAROID, POLAROID_GO, SPECTRA, INSTAX_MINI -> FilmKind.INSTANT
            DISPOSABLE -> FilmKind.DISPOSABLE
            PHOTOBOOTH -> FilmKind.BOOTH
            else -> FilmKind.REGULAR
        }

    /** Pellicules proposées dans ce format. */
    fun allows(p: FilmPreset): Boolean = p.kind == kind

    /** Tirage instantané : on peut y écrire la date ou un petit mot, à la main sur le bord. */
    val canWrite: Boolean get() = kind == FilmKind.INSTANT

    /** Pellicule chargée en entrant dans ce format (si la recette actuelle ne convient pas). */
    val defaultBase: String
        get() = when (this) {
            POLAROID, POLAROID_GO, SPECTRA -> "polaroid600"
            INSTAX_MINI -> "instax"
            DISPOSABLE -> "disposable400"
            PHOTOBOOTH -> "boothbw"
            else -> "fuji_provia"
        }

    companion object {
        /**
         * Format qui va avec une pellicule, en gardant le format actuel s'il convient :
         * une pellicule Polaroid -> Polaroid (ou Go / Spectra si on y est déjà), Instax -> Instax Mini,
         * photomaton -> Photomaton, jetable -> Jetable, pellicule classique -> le format actuel
         * s'il est classique (3:4, 35 mm, demi-format, 6x6), sinon Standard.
         */
        fun forFilm(p: FilmPreset, current: PhotoFormat): PhotoFormat = when (p.kind) {
            FilmKind.BOOTH -> PHOTOBOOTH
            FilmKind.DISPOSABLE -> DISPOSABLE
            FilmKind.INSTANT -> when {
                p.id == "instax" -> INSTAX_MINI
                current == INSTAX_MINI -> POLAROID
                current.kind == FilmKind.INSTANT -> current
                else -> POLAROID
            }
            FilmKind.REGULAR -> if (current.kind == FilmKind.REGULAR) current else STANDARD
        }
    }
}

/** Familles de pellicules. */
enum class FilmKind { REGULAR, INSTANT, DISPOSABLE, BOOTH }

val FilmPreset.kind: FilmKind
    get() = when {
        instant -> FilmKind.INSTANT
        disposable -> FilmKind.DISPOSABLE
        booth -> FilmKind.BOOTH
        else -> FilmKind.REGULAR
    }

/** Ce qui est imprimé sur les bords d'une bande de film. */
data class EdgeInfo(
    val filmName: String,
    val frameNumber: Int,
    /** Cadre des tirages instantanés (couleur, dégradé, métal...) et fenêtre ronde. */
    val style: FrameStyle = FrameStyles.WHITE,
    val round: Boolean = false,
)

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

    /**
     * Fenêtre de l'image en fractions d'un viseur de proportions [aspect] (largeur / hauteur ;
     * 3:4 en portrait, 4:3 quand on tient le téléphone en paysage), origine en haut à gauche.
     */
    fun cropFraction(aspect: Float): RectF {
        val h = 1f / aspect
        return holeRect(1f, h).let { RectF(it.left, it.top / h, it.right, it.bottom / h) }
    }

    /**
     * Passage du tirage "tel que le voit le photographe" à l'écran : le téléphone tourné de
     * [rotation]° (0, 90, 180, 270), le tirage reste droit pour lui.
     */
    fun layoutMatrix(w: Float, h: Float, rotation: Int): android.graphics.Matrix {
        val (pw, ph) = physicalSize(w, h, rotation)
        return android.graphics.Matrix().apply {
            postTranslate(-pw / 2f, -ph / 2f)
            postRotate(-rotation.toFloat())
            postTranslate(w / 2f, h / 2f)
        }
    }

    /** Fenêtre de l'image sur l'écran, le téléphone tourné de [rotation]°. */
    fun holeRectOnScreen(w: Float, h: Float, rotation: Int): RectF {
        val (pw, ph) = physicalSize(w, h, rotation)
        return holeRect(pw, ph).also { layoutMatrix(w, h, rotation).mapRect(it) }
    }

    /** Même zone pour le shader de l'aperçu : x, y, l, h en fractions, origine en bas à gauche. */
    fun shaderFrameOnScreen(w: Float, h: Float, rotation: Int): FloatArray {
        val r = holeRectOnScreen(w, h, rotation)
        return floatArrayOf(r.left / w, 1f - r.bottom / h, r.width() / w, r.height() / h)
    }

    /** Recadre la photo (à l'endroit, en portrait ou en paysage) sur la fenêtre visée. */
    fun crop(src: Bitmap): Bitmap {
        val c = cropFraction(src.width.toFloat() / src.height)
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
        // fond sous la photo : le bord de l'image (à cheval sur deux pixels) se mêle au papier,
        // pas à du transparent qui deviendrait une fine ligne sombre dans le JPEG
        drawUnderlay(canvas, k, edge)
        canvas.drawBitmap(image, holeX * k, holeY * k, Paint(Paint.FILTER_BITMAP_FLAG))
        drawBorder(canvas, 0f, 0f, k, edge)
        return out
    }

    /** Peint le fond du tirage avant d'y poser la photo (rien par défaut). */
    protected open fun drawUnderlay(canvas: Canvas, k: Float, edge: EdgeInfo) {}

    /**
     * Dessine le cadre autour de la fenêtre de l'image, pour un tirage dont le coin est en
     * ([left], [top]) et où 1 mm = [k] pixels.
     */
    abstract fun drawBorder(canvas: Canvas, left: Float, top: Float, k: Float, edge: EdgeInfo)

    /**
     * Remplit le tirage autour de la fenêtre avec 4 rectangles pleins
     * (pas de forme trouée : certains téléphones les remplissent entièrement).
     */
    protected fun fillAroundHole(canvas: Canvas, left: Float, top: Float, k: Float, paint: Paint) =
        fillAround(canvas, left, top, printW * k, printH * k, RectF(left + holeX * k, top + holeY * k, left + (holeX + holeW) * k, top + (holeY + holeH) * k), paint)
}

/** Remplit un rectangle [w]x[h] autour d'une fenêtre [hole], avec 4 rectangles pleins. */
internal fun fillAround(canvas: Canvas, left: Float, top: Float, w: Float, h: Float, hole: RectF, paint: Paint) {
    // un seul aplat, la fenêtre exclue par découpe : pas de coutures entre plusieurs rectangles
    canvas.save()
    canvas.clipOutRect(hole)
    canvas.drawRect(left, top, left + w, top + h, paint)
    canvas.restore()
}

/** Marquages de bord de film (orangés), en texte vertical ou horizontal. */
internal object EdgePrint {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9E8A33C.toInt()
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.12f
    }

    fun vertical(canvas: Canvas, x: Float, y: Float, text: String) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(90f)
        canvas.drawText(text, 0f, 0f, paint)
        canvas.restore()
    }
}

internal val FilmBase = Paint().apply { color = 0xFF15100C.toInt() }
internal val Backlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEAE3.toInt() }

// ============================== Instantanés ==============================

/** Tirage instantané : papier autour de l'image, bord du bas plus épais (où l'on écrit). */
open class PaperFrame(
    printW: Float, printH: Float, holeX: Float, holeY: Float, holeW: Float, holeH: Float,
    /** Couleur du papier si aucun style n'est choisi (blanc). */
    @Suppress("unused") paperColor: Int,
) : PrintFrame(printW, printH, holeX, holeY, holeW, holeH) {
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG)

    @Synchronized
    override fun drawUnderlay(canvas: Canvas, k: Float, edge: EdgeInfo) {
        val print = RectF(0f, 0f, printW * k, printH * k)
        paper.style = Paint.Style.FILL
        edge.style.applyTo(paper, print, edge.frameNumber)
        canvas.drawRect(print, paper)
    }

    @Synchronized
    override fun drawBorder(canvas: Canvas, left: Float, top: Float, k: Float, edge: EdgeInfo) {
        val print = RectF(left, top, left + printW * k, top + printH * k)
        val hole = RectF(left + holeX * k, top + holeY * k, left + (holeX + holeW) * k, top + (holeY + holeH) * k)
        paper.style = Paint.Style.FILL
        edge.style.applyTo(paper, print, edge.frameNumber)
        // cadre d'un seul tenant (uni, dégradé ou métal), la fenêtre de l'image découpée
        canvas.save()
        canvas.clipOutRect(hole)
        canvas.drawRect(print, paper)
        canvas.restore()
        if (edge.round) {
            // Round Frame : le papier recouvre les coins de la fenêtre (anneau, sans forme trouée)
            val cx = hole.centerX()
            val cy = hole.centerY()
            val r = minOf(hole.width(), hole.height()) / 2f
            val outer = Math.hypot(hole.width() / 2.0, hole.height() / 2.0).toFloat() + 2f
            canvas.save()
            canvas.clipRect(hole)
            paper.style = Paint.Style.STROKE
            paper.strokeWidth = outer - r
            canvas.drawCircle(cx, cy, r + (outer - r) / 2f, paper)
            paper.style = Paint.Style.FILL
            canvas.restore()
        }
    }
}

/** Polaroid 600 / SX-70 / i-Type : 88 x 107 mm, image carrée de 79 mm. */
object PolaroidFrame : PaperFrame(88f, 107f, 4.5f, 6f, 79f, 79f, 0xFFF3F0E8.toInt())

/** Polaroid Go : 53,9 x 66,6 mm, image de 47 x 46 mm. */
object PolaroidGoFrame : PaperFrame(53.9f, 66.6f, 3.45f, 5f, 47f, 46f, 0xFFF3F0E8.toInt())

/** Polaroid Spectra / Image : 102 x 101 mm, image horizontale de 92 x 73 mm. */
object SpectraFrame : PaperFrame(102f, 101f, 5f, 5f, 92f, 73f, 0xFFF3F0E8.toInt())

/** Instax Mini : 54 x 86 mm (format carte de crédit), image de 46 x 62 mm, papier blanc. */
object InstaxMiniFrame : PaperFrame(54f, 86f, 4f, 7f, 46f, 62f, 0xFFF8F8F5.toInt())

// ============================== Pellicule ==============================

/**
 * Bande de pellicule 35 mm scannée, à la verticale : vue de 24 x 36 mm, perforations
 * de part et d'autre (pas de 4,75 mm), nom du film et numéros de vue imprimés sur les bords.
 */
object Film35Frame : PrintFrame(35f, 44f, 5.5f, 4f, 24f, 36f) {
    @Synchronized
    override fun drawBorder(canvas: Canvas, left: Float, top: Float, k: Float, edge: EdgeInfo) {
        fillAroundHole(canvas, left, top, k, FilmBase)
        val r = 0.45f * k
        var y = 0.6f
        while (y + 2.79f <= printH) {
            val yt = top + y * k
            val yb = yt + 2.79f * k
            canvas.drawRoundRect(left + 1.55f * k, yt, left + 3.53f * k, yb, r, r, Backlight)
            canvas.drawRoundRect(left + (printW - 3.53f) * k, yt, left + (printW - 1.55f) * k, yb, r, r, Backlight)
            y += 4.75f
        }
        EdgePrint.paint.textSize = 1.0f * k
        val n = edge.frameNumber
        EdgePrint.vertical(canvas, left + 0.45f * k, top + 1.5f * k, "${edge.filmName}  ◀  ARGENTICAM")
        EdgePrint.vertical(canvas, left + (printW - 1.3f) * k, top + 5f * k, "▶ $n")
        EdgePrint.vertical(canvas, left + (printW - 1.3f) * k, top + 26f * k, "${n}A")
    }
}

/**
 * Moyen format 6x6 (pellicule 120, 61,5 mm de large, sans perforations) : vue carrée de
 * 56 mm aux coins arrondis par le cache de l'appareil, marquages sur les bords.
 */
object Medium6x6Frame : PrintFrame(61.5f, 66f, 2.75f, 5f, 56f, 56f) {
    private val corner = Path()

    @Synchronized
    override fun drawBorder(canvas: Canvas, left: Float, top: Float, k: Float, edge: EdgeInfo) {
        fillAroundHole(canvas, left, top, k, FilmBase)
        // coins arrondis de la fenêtre (forme simple, pas de trou)
        val r = 1.2f * k
        val l = left + holeX * k
        val t = top + holeY * k
        val rr = l + holeW * k
        val b = t + holeH * k
        roundCorner(canvas, l, t, r, 1f, 1f)
        roundCorner(canvas, rr, t, r, -1f, 1f)
        roundCorner(canvas, l, b, r, 1f, -1f)
        roundCorner(canvas, rr, b, r, -1f, -1f)
        // marquages : 12 vues par rouleau de 120
        val n = (edge.frameNumber - 1) % 12 + 1
        EdgePrint.paint.textSize = 1.1f * k
        EdgePrint.vertical(canvas, left + 0.8f * k, top + 3f * k, "${edge.filmName} 120")
        EdgePrint.vertical(canvas, left + (printW - 1.9f) * k, top + 8f * k, "$n")
        EdgePrint.vertical(canvas, left + (printW - 1.9f) * k, top + 50f * k, "◀ $n")
    }

    /** Remplit le petit triangle courbe entre un coin de la fenêtre et un arc de rayon [r]. */
    private fun roundCorner(canvas: Canvas, x: Float, y: Float, r: Float, dx: Float, dy: Float) {
        corner.reset()
        corner.moveTo(x, y)
        corner.lineTo(x + dx * r, y)
        corner.quadTo(x, y, x, y + dy * r)
        corner.close()
        canvas.drawPath(corner, FilmBase)
    }
}

/**
 * Demi-format (Olympus Pen, Kodak Ektar H35) : deux vues verticales de 18 x 24 mm côte à côte
 * sur une seule vue de 35 mm, bande horizontale avec perforations en haut et en bas.
 * Chaque moitié est une photo 3:4 entière : pas de recadrage.
 */
object HalfFrameStrip {
    private const val W = 45f
    private const val H = 35f
    private const val HALF_W = 18f
    private const val HALF_H = 24f
    private const val X0 = 4f
    private const val Y0 = 5.5f
    private const val GAP = 1f

    fun compose(first: Bitmap, second: Bitmap, edge: EdgeInfo): Bitmap {
        if (first.width > first.height) {
            // tenu en paysage : on compose la bande "couchée" puis on la redresse (bande verticale)
            fun turn(b: Bitmap, deg: Float) =
                Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
            val a = turn(first, -90f)
            val b = turn(second, -90f)
            val strip = composeUpright(a, b, edge)
            a.recycle()
            b.recycle()
            return turn(strip, 90f).also { strip.recycle() }
        }
        return composeUpright(first, second, edge)
    }

    private fun composeUpright(first: Bitmap, second: Bitmap, edge: EdgeInfo): Bitmap {
        val k = first.width / HALF_W
        val out = Bitmap.createBitmap((W * k).roundToInt(), (H * k).roundToInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawRect(0f, 0f, W * k, H * k, FilmBase)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val y0 = Y0 * k
        val y1 = (Y0 + HALF_H) * k
        c.drawBitmap(first, null, RectF(X0 * k, y0, (X0 + HALF_W) * k, y1), paint)
        c.drawBitmap(second, null, RectF((X0 + HALF_W + GAP) * k, y0, (X0 + 2 * HALF_W + GAP) * k, y1), paint)
        // perforations en haut et en bas
        val r = 0.45f * k
        var x = 0.6f
        while (x + 2.79f <= W) {
            c.drawRoundRect(x * k, 1.55f * k, (x + 2.79f) * k, 3.53f * k, r, r, Backlight)
            c.drawRoundRect(x * k, (H - 3.53f) * k, (x + 2.79f) * k, (H - 1.55f) * k, r, r, Backlight)
            x += 4.75f
        }
        EdgePrint.paint.textSize = 1.0f * k
        val n = edge.frameNumber
        c.drawText("▶ $n", 5f * k, 1.2f * k, EdgePrint.paint)
        c.drawText("${n}A", 25f * k, 1.2f * k, EdgePrint.paint)
        c.drawText("${edge.filmName}  ◀  ARGENTICAM  ½", 4f * k, (H - 0.35f) * k, EdgePrint.paint)
        return out
    }
}

/**
 * Bande de photomaton : 4 photos verticales de 35 x 45 mm sur papier blanc,
 * comme dans les cabines photo.
 */
object PhotoboothStrip {
    const val FRAME_W = 35f
    const val FRAME_H = 45f
    private const val MARGIN = 3f
    private const val STRIP_W = FRAME_W + 2 * MARGIN
    private val paper = Paint().apply { color = 0xFFF5F5F2.toInt() }

    /** Recadre une photo 3:4 au format 35 x 45 (centré). */
    fun crop(src: Bitmap): Bitmap {
        val target = FRAME_W / FRAME_H
        return if (src.width.toFloat() / src.height > target) {
            val w = (src.height * target).roundToInt()
            Bitmap.createBitmap(src, (src.width - w) / 2, 0, w, src.height)
        } else {
            val h = (src.width / target).roundToInt()
            Bitmap.createBitmap(src, 0, (src.height - h) / 2, src.width, h)
        }
    }

    fun compose(frames: List<Bitmap>): Bitmap {
        val k = frames.first().width / FRAME_W
        val stripH = MARGIN + frames.size * (FRAME_H + MARGIN)
        val out = Bitmap.createBitmap((STRIP_W * k).roundToInt(), (stripH * k).roundToInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawRect(0f, 0f, STRIP_W * k, stripH * k, paper)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        frames.forEachIndexed { i, f ->
            val t = (MARGIN + i * (FRAME_H + MARGIN)) * k
            c.drawBitmap(f, null, Rect(
                (MARGIN * k).roundToInt(), t.roundToInt(),
                ((MARGIN + FRAME_W) * k).roundToInt(), (t + FRAME_H * k).roundToInt(),
            ), paint)
        }
        return out
    }
}

/** Taille du viseur "vue par le photographe" : largeur et hauteur échangées en paysage. */
fun physicalSize(w: Float, h: Float, rotation: Int): Pair<Float, Float> =
    if (rotation % 180 != 0) h to w else w to h

/**
 * Le cadre dessiné par-dessus le viseur : noir autour du tirage, puis le cadre lui-même,
 * toujours droit pour le photographe (le téléphone tourné de [rotation]°).
 */
@Composable
fun FrameViewfinderMask(frame: PrintFrame, edge: EdgeInfo, rotation: Int, modifier: Modifier = Modifier) {
    ComposeCanvas(modifier) {
        val (pw, ph) = physicalSize(size.width, size.height, rotation)
        val p = frame.printRect(pw, ph)
        val ink = Paint().apply { color = 0xFF0B0B0B.toInt() }
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.concat(frame.layoutMatrix(size.width, size.height, rotation))
            fillAround(nc, 0f, 0f, pw, ph, p, ink)
            frame.drawBorder(nc, p.left, p.top, p.width() / frame.printW, edge)
            nc.restore()
        }
    }
}
