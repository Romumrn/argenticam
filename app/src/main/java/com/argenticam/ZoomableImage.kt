package com.argenticam

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Image zoomable : pincer pour zoomer (jusqu'à ×5, centré sous les doigts), glisser pour se
 * déplacer, double-tap pour zoomer / revenir. À ×1, un glissé à un doigt n'est pas consommé :
 * il reste disponible pour le pager (passer à la photo suivante).
 *
 * @param onZoomChanged true dès que l'image est zoomée (pour bloquer le swipe du pager).
 */
@Composable
fun ZoomableImage(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    onZoomChanged: (Boolean) -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val notify by rememberUpdatedState(onZoomChanged)

    LaunchedEffect(scale > 1.01f) { notify(scale > 1.01f) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = (s - 1f) * size.width / 2f
        val maxY = (s - 1f) * size.height / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    /** Nouveau décalage pour que le point sous [centroid] reste sous les doigts. */
    fun zoomAround(centroid: Offset, from: Float, to: Float, o: Offset): Offset {
        val center = Offset(size.width / 2f, size.height / 2f)
        val c = centroid - center
        return c - (c - o) * (to / from)
    }

    Box(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { pos ->
                        val fromScale = scale
                        val fromOffset = offset
                        val toScale = if (scale > 1.01f) 1f else DOUBLE_TAP_ZOOM
                        val toOffset = if (toScale == 1f) Offset.Zero else clamp(zoomAround(pos, 1f, toScale, Offset.Zero), toScale)
                        scope.launch {
                            animate(0f, 1f, animationSpec = tween(260)) { t, _ ->
                                scale = fromScale + (toScale - fromScale) * t
                                offset = fromOffset + (toOffset - fromOffset) * t
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        // On ne prend la main que pour un pincement, ou pour se déplacer dans une image zoomée.
                        if (fingers >= 2 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            val moved = if (centroid != Offset.Unspecified) {
                                zoomAround(centroid, scale, newScale, offset)
                            } else {
                                offset
                            }
                            offset = clamp(moved + pan, newScale)
                            scale = newScale
                            if (scale <= 1.01f) offset = Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Image(
            bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
