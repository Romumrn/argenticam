package com.filmcam

import android.util.Size
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

val Amber = Color(0xFFE8C27A)
private val Ink = Color(0xFF0B0B0B)
private val Card = Color(0xFF161616)

private enum class Tab(val label: String) {
    Look("LOOK"), Speed("VITESSE"), Iso("ISO"), Ev("EV"), Wb("BLANCS"), Focus("FOCUS")
}

/** Couleur d'accent de chaque pellicule (petite pastille dans le carrousel). */
private fun accentFor(id: String): Color = when (id) {
    "portra400" -> Color(0xFFF2B8A0)
    "gold200" -> Color(0xFFF5C542)
    "superia400" -> Color(0xFF6FCF97)
    "velvia50" -> Color(0xFFEB5757)
    "classicchrome" -> Color(0xFF8FA3B8)
    "classicneg" -> Color(0xFF9BC4A5)
    "acros", "trix400" -> Color(0xFFD0D0D0)
    "cinestill800t" -> Color(0xFF5AA9FF)
    "ektachrome" -> Color(0xFF56CCF2)
    "lomo" -> Color(0xFFBB6BD9)
    else -> Color.White
}

@Composable
fun CameraScreen(
    active: Boolean,
    lastPhoto: Photo?,
    shots: Int,
    onSaved: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val workExecutor = remember { Executors.newSingleThreadExecutor() }

    var preset by remember { mutableStateOf(Presets.all[1]) }
    var strength by remember { mutableStateOf(1f) }
    var grainScale by remember { mutableStateOf(1f) }
    var showTuning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.Look) }
    var settings by remember { mutableStateOf(ManualSettings()) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val caps = remember(camera) { camera?.let { CameraCaps(it) } }

    val flash = remember { Animatable(0f) }
    val look = Look(preset, strength, grainScale)
    val currentLook by rememberUpdatedState(look)
    val currentOnSaved by rememberUpdatedState(onSaved)

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

    // La caméra ne tourne que quand l'écran caméra est visible.
    DisposableEffect(lifecycleOwner, active) {
        var disposed = false
        val providerFuture = ProcessCameraProvider.getInstance(context)
        if (active) {
            providerFuture.addListener(
                Runnable {
                    if (disposed) return@Runnable
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
                    camera = provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture,
                    )
                },
                mainExecutor,
            )
        }
        onDispose {
            disposed = true
            if (active && providerFuture.isDone) providerFuture.get().unbindAll()
            camera = null
        }
    }

    // Envoie les réglages manuels à la caméra à chaque changement (et après chaque reconnexion).
    LaunchedEffect(camera, settings) {
        val c = camera
        val k = caps
        if (c != null && k != null) {
            try {
                applyManual(c, settings, k)
            } catch (_: Exception) {
                // appareil qui refuse une clé : on garde l'aperçu en auto
            }
        }
    }

    fun enterManual() {
        val k = caps ?: return
        settings = settings.copy(
            manual = true,
            iso = k.nearestIso(settings.iso),
            shutterNs = k.nearestShutter(settings.shutterNs),
        )
    }

    fun takePhoto() {
        if (busy) return
        busy = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            flash.snapTo(0.8f)
            flash.animateTo(0f, tween(260))
        }
        val lookAtShutter = currentLook
        imageCapture.takePicture(
            workExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        PhotoPipeline.develop(context, image, lookAtShutter)
                        mainExecutor.execute { currentOnSaved() }
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

    val evStep = caps?.evStep ?: 0f
    val osd = if (settings.manual) {
        "M  ${shutterLabel(settings.shutterNs)}  ISO ${settings.iso}"
    } else {
        "A  AUTO  EV ${evLabel(settings.evIndex, evStep)}"
    }

    Column(Modifier.fillMaxSize().background(Ink)) {
        // Viseur : prend la place disponible en gardant le ratio 3:4 de l'aperçu
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            val w = minOf(maxWidth - 20.dp, maxHeight * 0.75f)
            Box(
                Modifier
                    .size(w, w * 4f / 3f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.Black),
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                    update = { it.setLook(look) },
                )
                ViewfinderFrame()

                // En haut : nom, compteur de poses, réglages du look
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent)))
                        .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "ARGENTICAM",
                        color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        RollCounter.label(shots),
                        color = Amber, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E1A12).copy(alpha = 0.85f))
                            .border(BorderStroke(1.dp, Amber.copy(alpha = 0.35f)), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable { showTuning = !showTuning },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Settings, contentDescription = "Intensité et grain",
                            tint = if (showTuning) Amber else Color.White, modifier = Modifier.size(22.dp),
                        )
                    }
                }

                // En bas : look + lecture des réglages, comme l'écran d'un Fuji
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                        .padding(start = 18.dp, end = 18.dp, top = 28.dp, bottom = 12.dp),
                ) {
                    Text(preset.name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(
                        osd,
                        color = Amber, fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                    )
                }

                // Intensité / grain en surimpression
                if (showTuning) {
                    Column(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 52.dp, start = 10.dp, end = 10.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.75f))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        LabeledSlider("Intensité", strength, 0f..1f) { strength = it }
                        LabeledSlider("Grain", grainScale, 0f..2.5f) { grainScale = it }
                    }
                }

                // Flash blanc au déclenchement
                Box(Modifier.fillMaxSize().alpha(flash.value).background(Color.White))
            }
        }

        // Bande de réglages
        val strip = listOf(
            Tab.Look to preset.name,
            Tab.Speed to if (settings.manual) shutterLabel(settings.shutterNs) else "AUTO",
            Tab.Iso to if (settings.manual) settings.iso.toString() else "AUTO",
            Tab.Ev to if (settings.manual) "—" else evLabel(settings.evIndex, evStep),
            Tab.Wb to settings.wb.label.uppercase(),
            Tab.Focus to if (settings.manualFocus) "MF" else "AF",
        )
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(strip, key = { it.first.name }) { (t, value) ->
                val selected = t == tab
                Column(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Amber.copy(alpha = 0.14f) else Card)
                        .border(
                            BorderStroke(1.dp, if (selected) Amber else Color.White.copy(alpha = 0.08f)),
                            RoundedCornerShape(10.dp),
                        )
                        .clickable {
                            tab = t
                            if ((t == Tab.Speed || t == Tab.Iso) && !settings.manual) enterManual()
                        }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(t.label, color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp, letterSpacing = 1.sp)
                    Text(
                        value, color = if (selected) Amber else Color.White,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                }
            }
        }

        // Sélecteur de la valeur choisie
        Box(Modifier.fillMaxWidth().height(54.dp).padding(top = 6.dp), contentAlignment = Alignment.CenterStart) {
            when (tab) {
                Tab.Look -> LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(Presets.all, key = { it.id }) { p ->
                        val selected = p.id == preset.id
                        val accent = accentFor(p.id)
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Card)
                                .border(
                                    BorderStroke(1.5.dp, if (selected) accent else Color.White.copy(alpha = 0.08f)),
                                    RoundedCornerShape(14.dp),
                                )
                                .clickable { preset = p }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(accent))
                            Spacer(Modifier.width(9.dp))
                            Column {
                                Text(
                                    p.name, color = if (selected) accent else Color.White,
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                )
                                Text(p.subtitle, color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp)
                            }
                        }
                    }
                }

                Tab.Speed -> ValueRow(
                    values = caps?.shutterStops ?: emptyList(),
                    selected = settings.shutterNs.takeIf { settings.manual },
                    label = { shutterLabel(it) },
                    onPick = { settings = settings.copy(manual = true, shutterNs = it) },
                )

                Tab.Iso -> ValueRow(
                    values = caps?.isoStops ?: emptyList(),
                    selected = settings.iso.takeIf { settings.manual },
                    label = { it.toString() },
                    onPick = { settings = settings.copy(manual = true, iso = it) },
                )

                Tab.Ev -> ValueRow(
                    values = caps?.evIndexes ?: emptyList(),
                    selected = settings.evIndex.takeIf { !settings.manual },
                    label = { evLabel(it, evStep) },
                    // La correction d'expo n'a de sens qu'en auto : on y repasse.
                    onPick = { settings = settings.copy(manual = false, evIndex = it) },
                )

                Tab.Wb -> ValueRow(
                    values = WhiteBalance.entries,
                    selected = settings.wb,
                    label = { it.label },
                    onPick = { settings = settings.copy(wb = it) },
                )

                Tab.Focus -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip("AF", !settings.manualFocus) { settings = settings.copy(manualFocus = false) }
                    if ((caps?.minFocusDistance ?: 0f) > 0f) {
                        Chip("MF", settings.manualFocus) { settings = settings.copy(manualFocus = true) }
                        if (settings.manualFocus) {
                            Text("∞", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                            Slider(
                                value = settings.focus,
                                onValueChange = { settings = settings.copy(focus = it) },
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Amber, activeTrackColor = Amber,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                                ),
                            )
                            Text("proche", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                    } else {
                        Text("Mise au point manuelle indisponible", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
                    }
                }
            }
        }

        // Commandes : dernière photo | déclencheur | A/M
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.25f)), RoundedCornerShape(14.dp))
                    .clickable { onOpenGallery() },
            ) {
                if (lastPhoto != null) {
                    PhotoThumb(lastPhoto, 200, Modifier.fillMaxSize())
                }
            }

            ShutterButton(busy = busy, onClick = { takePhoto() })

            // Dial A / M
            Box(
                Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(if (settings.manual) Amber.copy(alpha = 0.18f) else Card)
                    .border(
                        BorderStroke(1.5.dp, if (settings.manual) Amber else Color.White.copy(alpha = 0.15f)),
                        CircleShape,
                    )
                    .clickable {
                        if (settings.manual) settings = settings.copy(manual = false) else enterManual()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (settings.manual) "M" else "A",
                    color = if (settings.manual) Amber else Color.White,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Amber else Color.White,
        fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Amber.copy(alpha = 0.16f) else Card)
            .border(
                BorderStroke(1.dp, if (selected) Amber else Color.White.copy(alpha = 0.08f)),
                RoundedCornerShape(12.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** Rangée de valeurs à choisir (vitesses, ISO, EV...). */
@Composable
private fun <T> ValueRow(values: List<T>, selected: T?, label: (T) -> String, onPick: (T) -> Unit) {
    if (values.isEmpty()) {
        Text(
            "Non disponible sur cet appareil",
            color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        return
    }
    val state = rememberLazyListState()
    val index = values.indexOf(selected)
    LaunchedEffect(index, values.size) {
        if (index >= 0) state.animateScrollToItem((index - 2).coerceAtLeast(0))
    }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(values) { v -> Chip(label(v), v == selected) { onPick(v) } }
    }
}

@Composable
private fun ShutterButton(busy: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "shutter")
    Box(
        Modifier
            .size(80.dp)
            .scale(scale)
            .clip(CircleShape)
            .border(BorderStroke(3.dp, Color.White), CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(if (busy) Color(0xFF7A7A7A) else Color.White)
            .clickable(interactionSource = interaction, indication = null, enabled = !busy) { onClick() },
    )
}

/** Coins de viseur et grille des tiers, très discrets. */
@Composable
private fun ViewfinderFrame() {
    Canvas(Modifier.fillMaxSize().padding(14.dp)) {
        val line = Color.White.copy(alpha = 0.7f)
        val stroke = 2.dp.toPx()
        val len = 22.dp.toPx()
        val w = size.width
        val h = size.height
        fun seg(a: Offset, b: Offset) = drawLine(line, a, b, strokeWidth = stroke, cap = StrokeCap.Round)
        // coins
        seg(Offset(0f, 0f), Offset(len, 0f)); seg(Offset(0f, 0f), Offset(0f, len))
        seg(Offset(w, 0f), Offset(w - len, 0f)); seg(Offset(w, 0f), Offset(w, len))
        seg(Offset(0f, h), Offset(len, h)); seg(Offset(0f, h), Offset(0f, h - len))
        seg(Offset(w, h), Offset(w - len, h)); seg(Offset(w, h), Offset(w, h - len))
        // tiers
        val faint = Color.White.copy(alpha = 0.14f)
        for (i in 1..2) {
            drawLine(faint, Offset(w * i / 3f, 0f), Offset(w * i / 3f, h), strokeWidth = 1.dp.toPx())
            drawLine(faint, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), strokeWidth = 1.dp.toPx())
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.width(72.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Color.White.copy(alpha = 0.2f),
            ),
        )
    }
}
