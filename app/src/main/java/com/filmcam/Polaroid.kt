package com.filmcam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Format Polaroid (type 600) : tirage 88 x 107 mm, image carrée de 79 mm,
 * bords de 4,5 mm sur les côtés, 6 mm en haut et ~22 mm en bas.
 */
object Polaroid {
    const val PRINT_W = 88f
    const val PRINT_H = 107f
    const val IMAGE = 79f
    const val SIDE = 4.5f
    const val TOP = 6f

    val Paper = Color(0xFFF3F0E8)
    private const val PAPER_ARGB = 0xFFF3F0E8.toInt()

    /** Largeur du tirage dans le viseur, en fraction de la largeur du viseur (3:4). */
    private const val FRAME_IN_VIEW = 0.9f

    /** Tirage dans le viseur (fractions de la largeur w ; le viseur fait w x 4/3 w). */
    fun printRect(w: Float, h: Float): RectF {
        val pw = w * FRAME_IN_VIEW
        val ph = pw * PRINT_H / PRINT_W
        val l = (w - pw) / 2f
        val t = (h - ph) / 2f
        return RectF(l, t, l + pw, t + ph)
    }

    /** Fenêtre de l'image (le carré) dans le viseur. */
    fun holeRect(w: Float, h: Float): RectF {
        val p = printRect(w, h)
        val k = p.width() / PRINT_W
        val l = p.left + SIDE * k
        val t = p.top + TOP * k
        return RectF(l, t, l + IMAGE * k, t + IMAGE * k)
    }

    /** Fenêtre du carré en fractions du viseur, origine en haut à gauche (pour recadrer la photo). */
    val cropFraction: RectF = holeRect(1f, 4f / 3f).let { RectF(it.left, it.top / (4f / 3f), it.right, it.bottom / (4f / 3f)) }

    /** Même zone pour le shader de l'aperçu : x, y, l, h avec l'origine en bas à gauche. */
    val shaderFrame: FloatArray = cropFraction.let {
        floatArrayOf(it.left, 1f - it.bottom, it.width(), it.height())
    }

    /** Recadre la photo (3:4 portrait, à l'endroit) sur le carré visé. */
    fun cropSquare(src: Bitmap): Bitmap {
        val c = cropFraction
        val x = (c.left * src.width).roundToInt()
        val y = (c.top * src.height).roundToInt()
        val side = minOf((c.width() * src.width).roundToInt(), src.width - x, src.height - y)
        return Bitmap.createBitmap(src, x, y, side, side)
    }

    /** Pose l'image développée (carrée) sur le cadre papier. */
    fun compose(image: Bitmap): Bitmap {
        val s = image.width.toFloat()
        val k = s / IMAGE
        val w = (PRINT_W * k).roundToInt()
        val h = (PRINT_H * k).roundToInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(PAPER_ARGB)
        val left = SIDE * k
        val top = TOP * k
        canvas.drawBitmap(image, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        // léger liseré sombre à la jonction image / papier, comme sur un vrai tirage
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = s * 0.0025f
            color = 0x26000000
        }
        canvas.drawRect(left, top, left + s, top + s, edge)
        return out
    }
}

/** Le cadre Polaroid dessiné par-dessus le viseur : noir autour, papier crème, fenêtre carrée. */
@Composable
fun PolaroidViewfinderMask(modifier: Modifier = Modifier) {
    ComposeCanvas(modifier) {
        val p = Polaroid.printRect(size.width, size.height)
        val hole = Polaroid.holeRect(size.width, size.height)
        // Uniquement des rectangles pleins autour du carré : pas de forme "trouée" (even-odd),
        // que certains moteurs de rendu (Xiaomi) remplissent entièrement.
        fun band(color: Color, l: Float, t: Float, r: Float, b: Float) {
            if (r > l && b > t) drawRect(color, topLeft = Offset(l, t), size = Size(r - l, b - t))
        }
        val ink = Color(0xFF0B0B0B)
        // noir autour du tirage
        band(ink, 0f, 0f, size.width, p.top)
        band(ink, 0f, p.bottom, size.width, size.height)
        band(ink, 0f, p.top, p.left, p.bottom)
        band(ink, p.right, p.top, size.width, p.bottom)
        // papier autour du carré
        val paper = Polaroid.Paper
        band(paper, p.left, p.top, p.right, hole.top)
        band(paper, p.left, hole.bottom, p.right, p.bottom)
        band(paper, p.left, hole.top, hole.left, hole.bottom)
        band(paper, hole.right, hole.top, p.right, hole.bottom)
        drawRect(
            Color.Black.copy(alpha = 0.18f),
            topLeft = Offset(hole.left, hole.top),
            size = Size(hole.width(), hole.height()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
        )
    }
}

/**
 * Le tirage sort de l'appareil puis se révèle lentement, du gris-vert à l'image,
 * comme un vrai instantané. Toucher pour fermer.
 */
@Composable
fun PolaroidDeveloping(uri: Uri, onDone: () -> Unit) {
    val context = LocalContext.current
    val print: ImageBitmap? by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { PhotoLibrary.decode(context, uri, 1600)?.asImageBitmap() }
    }
    val slide = remember { Animatable(1f) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(print) {
        if (print == null) return@LaunchedEffect
        slide.animateTo(0f, tween(900, easing = FastOutSlowInEasing))
        launch { reveal.animateTo(1f, tween(5000, easing = LinearEasing)) }
        delay(6500)
        onDone()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDone() },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = print
        if (bmp != null) {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth(0.78f)
                    .aspectRatio(Polaroid.PRINT_W / Polaroid.PRINT_H)
                    .offset { IntOffset(0, (slide.value * 1400).roundToInt()) }
                    .shadow(18.dp),
            ) {
                Image(bmp, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                // voile de développement sur la fenêtre de l'image
                val k = maxWidth / Polaroid.PRINT_W
                Box(
                    Modifier
                        .offset(x = k * Polaroid.SIDE, y = k * Polaroid.TOP)
                        .size(k * Polaroid.IMAGE)
                        .alpha(1f - reveal.value)
                        .background(Color(0xFF2B3633)),
                )
            }
        }
        Text(
            if (reveal.value < 1f) "Développement…" else "Toucher pour fermer",
            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
        )
    }
}
