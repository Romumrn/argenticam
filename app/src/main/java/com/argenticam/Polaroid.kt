package com.argenticam

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
}

/**
 * Le tirage sort de l'appareil puis se révèle lentement, du gris-vert à l'image,
 * comme un vrai instantané. Toucher pour fermer.
 */
@Composable
fun PrintDeveloping(uri: Uri, frame: PrintFrame?, onDone: () -> Unit) {
    val context = LocalContext.current
    val print: ImageBitmap? by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { PhotoLibrary.decode(context, uri, 1600)?.asImageBitmap() }
    }
    val slide = remember { Animatable(1f) }
    val reveal = remember { Animatable(if (frame == null) 1f else 0f) }
    LaunchedEffect(print) {
        if (print == null) return@LaunchedEffect
        slide.animateTo(0f, tween(900, easing = FastOutSlowInEasing))
        if (frame != null) launch { reveal.animateTo(1f, tween(5000, easing = LinearEasing)) }
        delay(if (frame != null) 6500 else 3500)
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
            val aspect = frame?.let { it.printW / it.printH } ?: (bmp.width.toFloat() / bmp.height)
            BoxWithConstraints(
                Modifier
                    // tirage large (Spectra) ou bande haute (photomaton) : on garde tout à l'écran
                    .fillMaxWidth(if (aspect < 0.4f) 0.3f else 0.78f)
                    .aspectRatio(aspect)
                    .offset { IntOffset(0, (slide.value * 2400).roundToInt()) }
                    .shadow(18.dp),
            ) {
                Image(bmp, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                // voile de développement sur la fenêtre de l'image
                if (frame != null) {
                    val k = maxWidth / frame.printW
                    Box(
                        Modifier
                            .offset(x = k * frame.holeX, y = k * frame.holeY)
                            .size(k * frame.holeW, k * frame.holeH)
                            .alpha(1f - reveal.value)
                            .background(Color(0xFF2B3633)),
                    )
                }
            }
        }
        Text(
            if (reveal.value < 1f) "Développement…" else "Toucher pour fermer",
            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
        )
    }
}
