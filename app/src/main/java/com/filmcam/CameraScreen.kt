package com.filmcam

import android.util.Size
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

private val Amber = Color(0xFFE8C27A)

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val workExecutor = remember { Executors.newSingleThreadExecutor() }

    var preset by remember { mutableStateOf(Presets.all[1]) }
    var strength by remember { mutableStateOf(1f) }
    var grainScale by remember { mutableStateOf(1f) }
    var showTuning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val look = Look(preset, strength, grainScale)

    val previewView = remember { FilmPreviewView(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        // ~12 Mpx : assez pour une belle photo, assez petit pour tenir en texture GL.
                        ResolutionStrategy(Size(4032, 3024), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build(),
            )
            .build()
    }

    // Le look courant est lu au moment du clic via cette référence.
    val currentLook by rememberUpdatedLook(look)

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            val provider = providerFuture.get()
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1440, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                        )
                        .build(),
                )
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }
        providerFuture.addListener(listener, mainExecutor)
        onDispose { if (providerFuture.isDone) providerFuture.get().unbindAll() }
    }

    fun takePhoto() {
        if (busy) return
        busy = true
        val lookAtShutter = currentLook
        imageCapture.takePicture(
            workExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        PhotoPipeline.develop(context, image, lookAtShutter)
                        mainExecutor.execute {
                            Toast.makeText(context, "Photo enregistrée · ${lookAtShutter.preset.name}", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        mainExecutor.execute {
                            Toast.makeText(context, "Erreur : ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        busy = false
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    mainExecutor.execute {
                        Toast.makeText(context, "Erreur caméra : ${exception.message}", Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // Viseur
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
                update = { it.setLook(look) },
            )
            Column(
                Modifier.align(Alignment.TopStart).padding(16.dp),
            ) {
                Text(preset.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(preset.subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        }

        // Réglages
        if (showTuning) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                LabeledSlider("Intensité", strength, 0f..1f) { strength = it }
                LabeledSlider("Grain", grainScale, 0f..2.5f) { grainScale = it }
            }
        }

        // Carrousel de presets
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Presets.all, key = { it.id }) { p ->
                val selected = p.id == preset.id
                Column(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            BorderStroke(1.5.dp, if (selected) Amber else Color.White.copy(alpha = 0.15f)),
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { preset = p }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(p.name, color = if (selected) Amber else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(p.subtitle, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                }
            }
        }

        // Déclencheur
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(76.dp)
                    .clip(CircleShape)
                    .border(BorderStroke(4.dp, Color.White), CircleShape)
                    .padding(7.dp)
                    .clip(CircleShape)
                    .background(if (busy) Color.Gray else Color.White)
                    .clickable(enabled = !busy) { takePhoto() },
            )
            Text(
                "Réglages",
                color = if (showTuning) Amber else Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 28.dp)
                    .clickable { showTuning = !showTuning },
            )
        }
    }
}

@Composable
private fun rememberUpdatedLook(look: Look) =
    androidx.compose.runtime.rememberUpdatedState(look)

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.size(width = 70.dp, height = 20.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber),
        )
    }
}
