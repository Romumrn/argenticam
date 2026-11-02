package com.filmcam

import android.content.Context
import android.hardware.camera2.CaptureResult
import android.hardware.display.DisplayManager
import android.util.Size
import android.view.Display
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
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
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

val Amber = Color(0xFFE8C27A)
private val Ink = Color(0xFF0B0B0B)
private val Card = Color(0xFF161616)
private val Warn = Color(0xFFFF6B5B)

private enum class Tab(val label: String) {
    Recipe("RECETTE"), Lens("OBJECTIF"), Speed("VITESSE"), Iso("ISO"), Ev("EV"), Wb("BLANCS"), Focus("FOCUS")
}

private enum class FocusUi { Idle, Searching, Locked, Failed, Manual }

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

    // ---------- Recettes ----------
    var customRecipes by remember { mutableStateOf(RecipeStore.loadCustom(context)) }
    var saved by remember {
        val id = RecipeStore.activeId(context)
        mutableStateOf((customRecipes + Recipes.builtIns).firstOrNull { it.id == id } ?: Recipes.builtIns[1])
    }
    var recipe by remember { mutableStateOf(saved) } // copie de travail, modifiée en direct
    val modified = recipe != saved
    var editing by remember { mutableStateOf(false) }
    val renderParams = remember(recipe) { recipe.render() }

    fun selectRecipe(r: Recipe) {
        saved = r
        recipe = r
        RecipeStore.setActiveId(context, r.id)
    }

    fun persist(list: List<Recipe>) {
        customRecipes = list
        RecipeStore.saveCustom(context, list)
    }

    fun newId() = "u_${System.currentTimeMillis()}"

    fun saveRecipe() {
        if (recipe.builtIn) {
            val name = if (recipe.name == saved.name) "${recipe.name} perso".take(32) else recipe.name
            val n = recipe.copy(id = newId(), builtIn = false, name = name)
            persist(listOf(n) + customRecipes)
            selectRecipe(n)
        } else {
            persist(customRecipes.map { if (it.id == recipe.id) recipe else it })
            selectRecipe(recipe)
        }
        Toast.makeText(context, "Recette « ${recipe.name} » enregistrée", Toast.LENGTH_SHORT).show()
    }

    fun duplicateRecipe() {
        val n = recipe.copy(id = newId(), builtIn = false, name = "${recipe.name} copie".take(32))
        persist(listOf(n) + customRecipes)
        selectRecipe(n)
    }

    fun deleteRecipe() {
        persist(customRecipes.filter { it.id != recipe.id })
        selectRecipe(Recipes.builtIns[1])
    }

    // ---------- Prise de vue ----------
    var settings by remember { mutableStateOf(ManualSettings()) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val caps = remember(camera) { camera?.let { CameraCaps(it) } }
    var readout by remember { mutableStateOf(LiveReadout()) }
    var lenses by remember { mutableStateOf<List<Lens>>(emptyList()) }
    var lensSel by remember { mutableStateOf<Lens?>(null) }
    var boundLensId by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var peaking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.Recipe) }

    // ---------- Mise au point au doigt ----------
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusState by remember { mutableStateOf(FocusUi.Idle) }
    var focusTick by remember { mutableIntStateOf(0) }
    val reticleScale = remember { Animatable(1f) }
    var loupe by remember { mutableStateOf(false) }
    var loupeCenter by remember { mutableStateOf(Offset(0.5f, 0.5f)) } // 0..1, origine en bas à gauche
    var loupeJob by remember { mutableStateOf<Job?>(null) }

    val flash = remember { Animatable(0f) }
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

    BackHandler(enabled = editing) { editing = false }

    // La caméra ne tourne que quand l'écran caméra est visible.
    DisposableEffect(lifecycleOwner, active, boundLensId) {
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
                        .withReadout { r -> mainExecutor.execute { readout = r } }
                        .build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    provider.unbindAll()
                    val bound = try {
                        provider.bindToLifecycle(lifecycleOwner, selectorFor(boundLensId), preview, imageCapture)
                    } catch (e: Exception) {
                        // objectif refusé par le téléphone : retour à la caméra par défaut
                        Toast.makeText(context, "Cet objectif n'est pas accessible", Toast.LENGTH_SHORT).show()
                        boundLensId = null
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture,
                        )
                    }
                    camera = bound
                    if (lenses.isEmpty()) {
                        val found = discoverLenses(provider, bound)
                        lenses = found
                        if (lensSel == null) lensSel = defaultLens(found)
                    }
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

    // Réglages -> caméra (à chaque changement et après chaque reconnexion).
    LaunchedEffect(camera, settings, recipe.wb, recipe.noiseReduction) {
        val c = camera
        val k = caps
        if (c != null && k != null) {
            try {
                applyManual(c, settings, k, recipe)
            } catch (_: Exception) {
            }
        }
    }

    // Zoom = passage d'un objectif à l'autre sur les téléphones à caméra logique.
    LaunchedEffect(camera, zoom) {
        try {
            camera?.cameraControl?.setZoomRatio(zoom)
        } catch (_: Exception) {
        }
    }

    // Changement de mode de mise au point : on libère le point verrouillé.
    LaunchedEffect(settings.focusMode, camera) {
        focusPoint = null
        focusState = FocusUi.Idle
        try {
            camera?.cameraControl?.cancelFocusAndMetering()
        } catch (_: Exception) {
        }
    }

    // En AF-C, le cadre s'efface après quelques secondes (le suivi continue).
    LaunchedEffect(focusTick) {
        if (focusTick > 0 && settings.focusMode == FocusMode.AFC) {
            delay(2500)
            focusPoint = null
        }
    }

    // Avertit si le téléphone ignore un réglage manuel (on compare demandé / réellement appliqué).
    val mismatch: String? = run {
        val k = caps ?: return@run null
        val r = readout
        val calib = k.focusCalibrated
        when {
            settings.manual && k.canManualExposure && r.iso != null && abs(r.iso - settings.iso) > settings.iso * 0.3f ->
                "ISO demandé ${settings.iso} → réel ${r.iso}"
            settings.manual && k.canManualExposure && r.exposureNs != null &&
                abs(r.exposureNs - settings.shutterNs) > settings.shutterNs * 0.3f ->
                "vitesse demandée ${shutterLabel(settings.shutterNs)} → réelle ${shutterLabel(r.exposureNs)}"
            settings.focusMode == FocusMode.MF && k.canManualFocus && r.focusDiopters != null &&
                abs(r.focusDiopters - settings.focus * k.minFocusDistance) > 0.25f + 0.1f * k.minFocusDistance ->
                "MP demandée ${distanceLabel(settings.focus * k.minFocusDistance, calib)} → réelle ${distanceLabel(r.focusDiopters, calib)}"
            else -> null
        }
    }
    var showMismatch by remember { mutableStateOf(false) }
    LaunchedEffect(mismatch != null) {
        if (mismatch != null) {
            delay(1500)
            showMismatch = true
        } else {
            showMismatch = false
        }
    }

    fun enterManual() {
        val k = caps ?: return
        if (!k.canManualExposure) {
            Toast.makeText(context, "Ton téléphone ne laisse pas régler vitesse et ISO à la main", Toast.LENGTH_LONG).show()
            return
        }
        settings = settings.copy(
            manual = true,
            iso = k.nearestIso(readout.iso ?: settings.iso),
            shutterNs = k.nearestShutter(readout.exposureNs ?: settings.shutterNs),
        )
    }

    fun onViewfinderTap(pos: Offset, w: Int, h: Int) {
        focusPoint = pos
        loupeCenter = Offset(pos.x / w, 1f - pos.y / h)
        val tick = focusTick + 1
        focusTick = tick
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        scope.launch {
            reticleScale.snapTo(1.5f)
            reticleScale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 400f))
        }
        if (settings.focusMode == FocusMode.MF) {
            // En MF, toucher choisit l'endroit grossi par la loupe.
            focusState = FocusUi.Manual
            return
        }
        val cam = camera ?: return
        val display = previewView.display
            ?: (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val point = DisplayOrientedMeteringPointFactory(display, cam.cameraInfo, w.toFloat(), h.toFloat())
            .createPoint(pos.x, pos.y, 0.12f)
        val builder = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
        if (settings.focusMode == FocusMode.AFS) builder.disableAutoCancel() else builder.setAutoCancelDuration(4, TimeUnit.SECONDS)
        focusState = FocusUi.Searching
        val future = cam.cameraControl.startFocusAndMetering(builder.build())
        future.addListener(
            {
                if (focusTick == tick) {
                    focusState = try {
                        if (future.get().isFocusSuccessful) FocusUi.Locked else FocusUi.Failed
                    } catch (_: Exception) {
                        FocusUi.Failed
                    }
                }
            },
            mainExecutor,
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
        val params = renderParams
        val name = recipe.name
        imageCapture.takePicture(
            workExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        PhotoPipeline.develop(context, image, params, name)
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
    val calibrated = caps?.focusCalibrated ?: true
    val mfActive = settings.focusMode == FocusMode.MF

    Column(Modifier.fillMaxSize().background(Ink)) {
        // ================= Viseur =================
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
                    update = {
                        it.setRender(
                            renderParams,
                            peaking && mfActive,
                            if (loupe && mfActive) 3f else 1f,
                            loupeCenter.x, loupeCenter.y,
                        )
                    },
                )
                ViewfinderFrame()

                // Toucher = mise au point à cet endroit
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { pos -> onViewfinderTap(pos, size.width, size.height) }
                        },
                )

                // Cadre de mise au point
                focusPoint?.let { p ->
                    val color = when (focusState) {
                        FocusUi.Locked -> Amber
                        FocusUi.Failed -> Warn
                        FocusUi.Manual -> Color.White.copy(alpha = 0.6f)
                        else -> Color.White
                    }
                    Canvas(Modifier.fillMaxSize()) {
                        val half = 34.dp.toPx() * reticleScale.value
                        val len = 11.dp.toPx()
                        val sw = 2.dp.toPx()
                        fun seg(a: Offset, b: Offset) = drawLine(color, a, b, strokeWidth = sw, cap = StrokeCap.Round)
                        val l = p.x - half
                        val r = p.x + half
                        val t = p.y - half
                        val b = p.y + half
                        seg(Offset(l, t), Offset(l + len, t)); seg(Offset(l, t), Offset(l, t + len))
                        seg(Offset(r, t), Offset(r - len, t)); seg(Offset(r, t), Offset(r, t + len))
                        seg(Offset(l, b), Offset(l + len, b)); seg(Offset(l, b), Offset(l, b - len))
                        seg(Offset(r, b), Offset(r - len, b)); seg(Offset(r, b), Offset(r, b - len))
                        drawCircle(color, 2.dp.toPx(), p)
                    }
                    if (focusState == FocusUi.Locked && settings.focusMode == FocusMode.AFS) {
                        Text(
                            "AF-L",
                            color = Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            modifier = Modifier.offset {
                                IntOffset((p.x - 16.dp.toPx()).roundToInt(), (p.y + 40.dp.toPx()).roundToInt())
                            },
                        )
                    }
                }

                // En haut : nom, compteur de poses, éditeur de recette
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent)))
                        .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (loupe && mfActive) "LOUPE ×3" else "ARGENTICAM",
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
                        Modifier.size(40.dp).clip(CircleShape).clickable { editing = !editing },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Edit, contentDescription = "Modifier la recette",
                            tint = if (editing) Amber else Color.White, modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // En bas : recette + ce que le capteur fait réellement, comme l'écran d'un Fuji
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                        .padding(start = 18.dp, end = 18.dp, top = 28.dp, bottom = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(filmAccent(recipe.baseId)))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            recipe.name + if (modified) " •" else "",
                            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        )
                    }
                    val shutterTxt = readout.exposureNs?.let { shutterLabel(it) }
                        ?: if (settings.manual) shutterLabel(settings.shutterNs) else "AUTO"
                    val isoTxt = readout.iso?.toString() ?: if (settings.manual) settings.iso.toString() else "AUTO"
                    val evTxt = if (!settings.manual && settings.evIndex != 0) "  EV ${evLabel(settings.evIndex, evStep)}" else ""
                    Text(
                        "${if (settings.manual) "M" else "A"}  $shutterTxt  ISO $isoTxt$evTxt",
                        color = Amber, fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                    )
                    val afLocked = readout.afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        readout.afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                    Text(
                        "${settings.focusMode.label}  ${distanceLabel(readout.focusDiopters, calibrated)}" +
                            if (!mfActive && afLocked) "  ●" else "",
                        color = Amber.copy(alpha = 0.85f), fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                    )
                    if (showMismatch && mismatch != null) {
                        Text(
                            "⚠ ignoré par le téléphone : $mismatch",
                            color = Warn, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                // Flash blanc au déclenchement
                Box(Modifier.fillMaxSize().alpha(flash.value).background(Color.White))
            }
        }

        if (editing) {
            // ================= Éditeur de recette (aperçu en direct au-dessus) =================
            RecipeEditor(
                recipe = recipe,
                modified = modified,
                onChange = { recipe = it },
                onSave = { saveRecipe() },
                onDuplicate = { duplicateRecipe() },
                onReset = { recipe = saved },
                onDelete = if (!recipe.builtIn && customRecipes.any { it.id == recipe.id }) {
                    { deleteRecipe() }
                } else {
                    null
                },
                onClose = { editing = false },
                modifier = Modifier.fillMaxWidth().height(400.dp),
            )
        } else {
            // ================= Bande de réglages =================
            val strip = listOf(
                Tab.Recipe to (recipe.name + if (modified) " •" else ""),
                Tab.Lens to (lensSel?.label ?: "1×"),
                Tab.Speed to if (settings.manual) shutterLabel(settings.shutterNs) else "AUTO",
                Tab.Iso to if (settings.manual) settings.iso.toString() else "AUTO",
                Tab.Ev to if (settings.manual) "—" else evLabel(settings.evIndex, evStep),
                Tab.Wb to if (recipe.wb == WhiteBalance.KELVIN) "${recipe.kelvin}K" else recipe.wb.label.uppercase(),
                Tab.Focus to settings.focusMode.label,
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

            // ================= Sélecteur (hauteur fixe pour ne pas redimensionner le viseur) =================
            Box(Modifier.fillMaxWidth().height(84.dp).padding(top = 4.dp), contentAlignment = Alignment.CenterStart) {
                val k = caps
                when (tab) {
                    Tab.Recipe -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item(key = "edit") {
                            ActionCard("✎", "Éditer") { editing = true }
                        }
                        item(key = "new") {
                            ActionCard("+", "Nouvelle") {
                                duplicateRecipe()
                                editing = true
                            }
                        }
                        items(customRecipes + Recipes.builtIns, key = { it.id }) { r ->
                            val selected = r.id == saved.id
                            val accent = filmAccent(r.baseId)
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Card)
                                    .border(
                                        BorderStroke(1.5.dp, if (selected) accent else Color.White.copy(alpha = 0.08f)),
                                        RoundedCornerShape(14.dp),
                                    )
                                    .clickable { if (selected) editing = true else selectRecipe(r) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(9.dp).clip(CircleShape).background(accent))
                                Spacer(Modifier.width(9.dp))
                                Column {
                                    Text(
                                        (if (!r.builtIn) "★ " else "") + r.name,
                                        color = if (selected) accent else Color.White,
                                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    )
                                    val base = r.base()
                                    Text(
                                        if (r.builtIn && r.id == "base_${base.id}") base.subtitle else "base ${base.name}",
                                        color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp, maxLines = 1,
                                    )
                                }
                            }
                        }
                    }

                    Tab.Lens -> ValueRow(
                        values = lenses,
                        selected = lensSel,
                        label = { it.display },
                        onPick = { l ->
                            lensSel = l
                            if (l.cameraId != null) {
                                zoom = 1f
                                boundLensId = l.cameraId
                            } else {
                                boundLensId = null
                                zoom = l.zoom
                            }
                        },
                    )

                    Tab.Speed -> {
                        val stops = k?.shutterStops ?: emptyList()
                        when {
                            k != null && !k.canManualExposure ->
                                Unavailable("Ton téléphone ne laisse pas les applis régler la vitesse. Utilise EV.")
                            stops.isEmpty() -> Unavailable()
                            else -> RetroDial(
                                count = stops.size,
                                index = stops.indexOf(k!!.nearestShutter(settings.shutterNs)).coerceAtLeast(0),
                                onIndexChange = { settings = settings.copy(manual = true, shutterNs = stops[it]) },
                                labelAt = { shutterLabel(stops[it]) },
                                modifier = Modifier.fillMaxSize(),
                                spacing = 58.dp,
                            )
                        }
                    }

                    Tab.Iso -> {
                        val stops = k?.isoStops ?: emptyList()
                        when {
                            k != null && !k.canManualExposure ->
                                Unavailable("Ton téléphone ne laisse pas les applis régler l'ISO. Utilise EV.")
                            stops.isEmpty() -> Unavailable()
                            else -> RetroDial(
                                count = stops.size,
                                index = stops.indexOf(k!!.nearestIso(settings.iso)).coerceAtLeast(0),
                                onIndexChange = { settings = settings.copy(manual = true, iso = stops[it]) },
                                labelAt = { stops[it].toString() },
                                modifier = Modifier.fillMaxSize(),
                                spacing = 62.dp,
                            )
                        }
                    }

                    Tab.Ev -> {
                        val stops = k?.evIndexes ?: emptyList()
                        if (stops.isEmpty()) {
                            Unavailable()
                        } else {
                            RetroDial(
                                count = stops.size,
                                index = stops.indexOf(settings.evIndex).coerceAtLeast(0),
                                onIndexChange = { settings = settings.copy(manual = false, evIndex = stops[it]) },
                                labelAt = { evLabel(stops[it], evStep) },
                                modifier = Modifier.fillMaxSize(),
                                spacing = 50.dp,
                                enabled = !settings.manual,
                                // La correction d'expo n'a de sens qu'en auto : on y repasse.
                                onTouched = { if (settings.manual) settings = settings.copy(manual = false) },
                            )
                        }
                    }

                    Tab.Wb -> ValueRow(
                        values = WhiteBalance.entries,
                        selected = recipe.wb,
                        label = { if (it == WhiteBalance.KELVIN) "${recipe.kelvin}K" else it.label },
                        onPick = { recipe = recipe.copy(wb = it) },
                    )

                    Tab.Focus -> {
                        if (mfActive && k != null && k.canManualFocus) {
                            val units = 60
                            val marks = remember(k.minFocusDistance, calibrated) {
                                focusMarks(k.minFocusDistance, units, calibrated)
                            }
                            RetroDial(
                                count = units + 1,
                                index = (settings.focus * units).roundToInt().coerceIn(0, units),
                                onIndexChange = { settings = settings.copy(focus = it / units.toFloat()) },
                                labelAt = { marks[it] },
                                modifier = Modifier.fillMaxSize().padding(top = 18.dp),
                                spacing = 14.dp,
                                onTouched = {
                                    loupeJob?.cancel()
                                    loupe = true
                                },
                                onReleased = {
                                    loupeJob?.cancel()
                                    loupeJob = scope.launch {
                                        delay(900)
                                        loupe = false
                                    }
                                },
                            )
                        } else {
                            val hint = when {
                                mfActive -> "Ton téléphone bloque la mise au point manuelle.\nPasse en AF-S et touche le viseur."
                                settings.focusMode == FocusMode.AFS -> "Touche le viseur pour faire le point.\nLe point reste verrouillé (AF-L)."
                                else -> "Touche le viseur pour choisir le sujet.\nLa mise au point suit en continu."
                            }
                            Text(
                                hint,
                                color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
                            )
                        }
                        Row(
                            Modifier.align(Alignment.TopStart).padding(start = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FocusMode.entries.forEach { m ->
                                Pill(m.label, settings.focusMode == m) { settings = settings.copy(focusMode = m) }
                            }
                        }
                        if (mfActive) {
                            Pill("PEAK", peaking, Modifier.align(Alignment.TopEnd).padding(end = 10.dp)) {
                                peaking = !peaking
                            }
                        }
                    }
                }
            }

            // ================= Commandes : dernière photo | déclencheur | A/M =================
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
}

@Composable
private fun ActionCard(icon: String, text: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Amber.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, Amber.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, color = Amber, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(text, color = Amber, fontSize = 10.sp)
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

/** Rangée de valeurs à choisir (objectifs, balance des blancs...). */
@Composable
private fun <T> ValueRow(values: List<T>, selected: T?, label: (T) -> String, onPick: (T) -> Unit) {
    if (values.isEmpty()) {
        Unavailable()
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
private fun Unavailable(text: String = "Non disponible sur cet appareil") {
    Text(
        text,
        color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

/** Petite pastille cliquable (AF-S / AF-C / MF / PEAK). */
@Composable
private fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Amber else Color.White.copy(alpha = 0.8f),
        fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Amber.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.55f))
            .border(
                BorderStroke(1.dp, if (selected) Amber else Color.White.copy(alpha = 0.18f)),
                RoundedCornerShape(8.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** Repères de distance sur la bague de mise au point (en dioptries : 0 = infini). */
private fun focusMarks(minDiopters: Float, units: Int, calibrated: Boolean): Map<Int, String> {
    val marks = linkedMapOf<Int, String>()
    if (!calibrated) {
        // Échelle non étalonnée par le constructeur : pas de distances fiables à afficher.
        marks[0] = "∞"
        marks[units / 2] = "·"
        marks[units] = "proche"
        return marks
    }
    val candidates = listOf(
        0f to "∞", 0.2f to "5m", 0.5f to "2m", 1f to "1m", 2f to "50cm", 3.33f to "30cm", 5f to "20cm", 10f to "10cm",
    )
    for ((d, text) in candidates) {
        if (d <= minDiopters + 0.01f) marks[(d / minDiopters * units).roundToInt().coerceIn(0, units)] = text
    }
    return marks
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
        seg(Offset(0f, 0f), Offset(len, 0f)); seg(Offset(0f, 0f), Offset(0f, len))
        seg(Offset(w, 0f), Offset(w - len, 0f)); seg(Offset(w, 0f), Offset(w, len))
        seg(Offset(0f, h), Offset(len, h)); seg(Offset(0f, h), Offset(0f, h - len))
        seg(Offset(w, h), Offset(w - len, h)); seg(Offset(w, h), Offset(w, h - len))
        val faint = Color.White.copy(alpha = 0.14f)
        for (i in 1..2) {
            drawLine(faint, Offset(w * i / 3f, 0f), Offset(w * i / 3f, h), strokeWidth = 1.dp.toPx())
            drawLine(faint, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), strokeWidth = 1.dp.toPx())
        }
    }
}
