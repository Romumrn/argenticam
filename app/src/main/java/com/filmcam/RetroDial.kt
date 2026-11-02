package com.filmcam

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val Metal = Color(0xFF4A4A48)
private val MetalDark = Color(0xFF151515)

/**
 * Molette rétro façon boîtier argentique : le haut d'un gros disque métallique moleté dépasse
 * en bas de l'écran. On le fait tourner au doigt, il "clique" de cran en cran (vibration légère)
 * et se cale sur la valeur choisie, en face du repère ambre.
 *
 * @param count nombre de crans
 * @param index cran actuellement sélectionné
 * @param labelAt texte écrit sur le cran (null = simple graduation)
 */
@Composable
fun RetroDial(
    count: Int,
    index: Int,
    onIndexChange: (Int) -> Unit,
    labelAt: (Int) -> String?,
    modifier: Modifier = Modifier,
    spacing: Dp = 56.dp,
    enabled: Boolean = true,
    onTouched: () -> Unit = {},
    onReleased: () -> Unit = {},
) {
    if (count <= 0) return
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val last = (count - 1).toFloat()
    var position by remember { mutableFloatStateOf(index.coerceIn(0, count - 1).toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var lastTick by remember { mutableFloatStateOf(index.toFloat()) }

    val spacingPx = with(density) { spacing.toPx() }

    fun settleTo(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(position, target, animationSpec = tween(260, easing = FastOutSlowInEasing)) { v, _ ->
                position = v
            }
        }
    }

    // Si la valeur change de l'extérieur (ex. passage auto/manuel), la molette suit.
    LaunchedEffect(index, count) {
        if (!dragging && abs(position - index) > 0.01f) settleTo(index.coerceIn(0, count - 1).toFloat())
    }

    val dragState = rememberDraggableState { dx ->
        position = (position - dx / spacingPx).coerceIn(0f, last)
        val i = position.roundToInt()
        if (i.toFloat() != lastTick) {
            lastTick = i.toFloat()
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onIndexChange(i)
        }
    }

    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }

    Canvas(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = {
                    settleJob?.cancel()
                    dragging = true
                    onTouched()
                },
                onDragStopped = { velocity ->
                    // un peu d'inertie, puis on se cale sur le cran le plus proche
                    val target = (position - velocity * 0.12f / spacingPx).roundToInt().coerceIn(0, count - 1)
                    dragging = false
                    if (target.toFloat() != lastTick) {
                        lastTick = target.toFloat()
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onIndexChange(target)
                    settleTo(target.toFloat())
                    onReleased()
                },
            ),
    ) {
        val w = size.width
        val topGap = 20.dp.toPx()
        val radius = 420.dp.toPx()
        val cx = w / 2f
        val cy = topGap + radius
        val center = Offset(cx, cy)
        val perUnit = spacingPx / radius // radians par cran

        // Disque métallique
        drawCircle(
            brush = Brush.verticalGradient(
                0f to Metal, 0.35f to Color(0xFF2A2A29), 1f to MetalDark,
                startY = topGap, endY = topGap + 110.dp.toPx(),
            ),
            radius = radius, center = center,
        )
        drawCircle(Color.White.copy(alpha = 0.16f), radius, center, style = Stroke(1.5.dp.toPx()))

        // Moletage : fines stries sur le bord, qui défilent avec la rotation
        val knurlStep = Math.toRadians(1.6)
        val shift = (position * perUnit) % knurlStep
        val firstK = -ceil(Math.toRadians(40.0) / knurlStep).toInt()
        val lastK = -firstK
        for (k in firstK..lastK) {
            val rad = k * knurlStep - shift
            val deg = Math.toDegrees(rad).toFloat()
            rotate(deg, center) {
                drawLine(
                    Color.White.copy(alpha = 0.22f),
                    Offset(cx, topGap + 2.dp.toPx()), Offset(cx, topGap + 13.dp.toPx()),
                    strokeWidth = 1.1.dp.toPx(),
                )
            }
        }
        // Liseré sous le moletage
        drawCircle(Color.Black.copy(alpha = 0.5f), radius - 15.dp.toPx(), center, style = Stroke(1.dp.toPx()))

        // Graduations et chiffres
        val from = maxOf(0, floor(position - 8f).toInt())
        val to = minOf(count - 1, ceil(position + 8f).toInt())
        for (i in from..to) {
            val d = i - position
            val deg = Math.toDegrees((d * perUnit).toDouble()).toFloat()
            val dist = abs(d)
            val label = labelAt(i)
            val fade = (1f - dist / 5.5f).coerceIn(0.12f, 1f)
            val selected = dist < 0.5f
            rotate(deg, center) {
                val tickLen = if (label != null) 9.dp.toPx() else 5.dp.toPx()
                drawLine(
                    (if (selected && label != null) Amber else Color.White).copy(alpha = fade * 0.85f),
                    Offset(cx, topGap + 20.dp.toPx()), Offset(cx, topGap + 20.dp.toPx() + tickLen),
                    strokeWidth = if (label != null) 2.dp.toPx() else 1.2.dp.toPx(),
                )
                if (label != null) {
                    textPaint.color = (if (selected) Amber else Color.White).copy(alpha = fade).toArgb()
                    textPaint.textSize = (if (selected) 19f else 14f - minOf(dist, 3f)) * density.density
                    drawIntoCanvas { c ->
                        c.nativeCanvas.drawText(label, cx, topGap + 52.dp.toPx(), textPaint)
                    }
                }
            }
        }

        // Fenêtre de lecture + fondu sur les côtés pour l'effet cylindre
        drawRect(
            Color.White.copy(alpha = 0.05f),
            topLeft = Offset(cx - 34.dp.toPx(), topGap),
            size = Size(68.dp.toPx(), size.height - topGap),
        )
        drawRect(
            Brush.horizontalGradient(
                0f to Color(0xFF0B0B0B), 0.2f to Color(0x000B0B0B),
                0.8f to Color(0x000B0B0B), 1f to Color(0xFF0B0B0B),
            ),
            topLeft = Offset(0f, 0f), size = size,
        )

        // Repère ambre
        val marker = Path().apply {
            moveTo(cx - 8.dp.toPx(), 3.dp.toPx())
            lineTo(cx + 8.dp.toPx(), 3.dp.toPx())
            lineTo(cx, topGap - 1.dp.toPx())
            close()
        }
        drawPath(marker, Amber)
    }
}
