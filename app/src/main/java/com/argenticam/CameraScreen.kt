package com.argenticam

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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
    Recipe("RECETTE"), Format("FORMAT"), Text("TEXTE"), Lens("OBJECTIF"), Speed("VITESSE"), Iso("ISO"), Ev("EV"), Wb("BLANCS"), Focus("FOCUS")
}

private enum class Sheet { Settings, Library, Display }

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

    // dernière recette choisie dans chaque famille (classique, instantané...) : on la retrouve
    // en revenant à un format de cette famille
    val lastByKind = remember { mutableMapOf<FilmKind, String>() }

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
    var tab by remember { mutableStateOf(Tab.Recipe) } // Tab.Recipe = aucun réglage déplié
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var display by remember { mutableStateOf(DisplayStore.load(context)) }
    fun setDisplay(d: DisplayPrefs) {
        display = d
        DisplayStore.save(context, d)
    }
    val roll by rememberRoll(display.level && active)
    var format by remember { mutableStateOf(PhotoFormat.STANDARD) }
    val polaroid = format == PhotoFormat.POLAROID
    var flashOn by remember { mutableStateOf(false) }
    // tirage qui "sort de l'appareil" après la prise de vue (instantanés, photomaton)
    var developing by remember { mutableStateOf<Pair<android.net.Uri, PrintFrame?>?>(null) }
    // demi-format : première vue en attente de la seconde
    var pendingHalf by remember { mutableStateOf<Capture?>(null) }
    // photos en cours de développement (en arrière-plan : on peut déjà reprendre une photo)
    var developingCount by remember { mutableIntStateOf(0) }
    // un seul développement à la fois (mémoire), dans l'ordre des prises de vue
    val developDispatcher = remember {
        java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    }
    // photomaton : compte à rebours et numéro de pose en cours
    var countdown by remember { mutableIntStateOf(0) }
    var boothPose by remember { mutableIntStateOf(0) }
    var inscription by remember { mutableStateOf(InscriptionStore.load(context)) }
    var typingNote by remember { mutableStateOf(false) }

    fun setInscription(i: Inscription) {
        inscription = i
        InscriptionStore.save(context, i)
    }

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
            // Latence minimale : la photo part au moment de l'appui (pas de nouvelle mise au point /
            // mesure avant de déclencher). La qualité du fichier reste maximale (JPEG 100, développement par tuiles).
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(100)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    // Toujours la meilleure qualité : la plus haute résolution que le téléphone
                    // accepte de donner (y compris les modes haute résolution des gros capteurs).
                    // Le développement se fait par tuiles, la taille n'est donc pas limitée.
                    // la plus haute résolution du mode normal du capteur (les modes "ultra haute résolution"
                    // des gros capteurs mettent jusqu'à 1-2 s à s'activer à chaque photo)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build(),
            )
            .build()
    }

    // ---------- Orientation : l'écran reste en portrait, les commandes pivotent ----------
    // rotation du téléphone en degrés (0 = portrait, 90 = tourné vers la droite, 270 = vers la gauche)
    var deviceRot by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener = object : android.view.OrientationEventListener(context) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val target = ((o + 45) / 90 % 4) * 90
                // un peu d'hystérésis : on ne bascule que bien engagé dans la nouvelle position
                if (target != deviceRot && abs(((o - target + 540) % 360) - 180) < 30) deviceRot = target
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }
    // la photo est enregistrée dans le sens où l'on tient le téléphone
    LaunchedEffect(deviceRot) {
        imageCapture.targetRotation = when (deviceRot) {
            90 -> android.view.Surface.ROTATION_270
            180 -> android.view.Surface.ROTATION_180
            270 -> android.view.Surface.ROTATION_90
            else -> android.view.Surface.ROTATION_0
        }
    }
    val uiAngle by animateFloatAsState(
        when (deviceRot) {
            90 -> -90f
            180 -> 180f
            270 -> 90f
            else -> 0f
        },
        label = "rotation",
    )
    val landscape = deviceRot % 180 != 0

    BackHandler(enabled = editing) { editing = false }
    BackHandler(enabled = sheet != null) { sheet = null }

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

    /** Pellicules proposées selon le format : instantanées en Polaroid, jetables en Jetable. */
    fun allowedBase(p: FilmPreset): Boolean = format.allows(p)

    fun setFormat(f: PhotoFormat) {
        if (f != PhotoFormat.HALF_FRAME && pendingHalf != null) {
            pendingHalf = null
        }
        format = f
        // le format impose sa famille de pellicule : on reprend la dernière utilisée, sinon celle par défaut
        if (!f.allows(recipe.base())) {
            lastByKind[recipe.base().kind] = recipe.id
            val all = customRecipes + Recipes.builtIns
            val next = lastByKind[f.kind]?.let { id -> all.firstOrNull { it.id == id } }
                ?.takeIf { f.allows(it.base()) }
                ?: Recipes.builtIns.firstOrNull { it.id == "base_${f.defaultBase}" }
            next?.let { selectRecipe(it) }
        }
    }

    /** Choisir une pellicule fait passer au format qui va avec (Polaroid, photomaton, jetable...). */
    fun pickFilm(r: Recipe) {
        val f = PhotoFormat.forFilm(r.base(), format)
        if (f != format) {
            if (f != PhotoFormat.HALF_FRAME) pendingHalf = null
            lastByKind[recipe.base().kind] = recipe.id
            format = f
        }
        selectRecipe(r)
    }

    /** Déclenche et récupère le JPEG (sans attendre le développement, pour enchaîner les poses). */
    suspend fun capture(): Capture = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        imageCapture.takePicture(
            workExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    cont.resumeWith(Result.success(PhotoPipeline.readCapture(image)))
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWith(Result.failure(exception))
                }
            },
        )
    }

    fun shutterFx() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            flash.snapTo(0.8f)
            flash.animateTo(0f, tween(260))
        }
    }

    fun takePhoto() {
        if (busy) return
        busy = true
        val params = renderParams
        val name = recipe.name
        val fmt = format
        val note = if (fmt.canWrite) inscription else Inscription()
        val edge = EdgeInfo(recipe.base().name.uppercase(), shots % RollCounter.ROLL_SIZE + 1)
        // Jetable et photomaton : le vrai flash du téléphone, si on l'a allumé
        imageCapture.flashMode =
            if ((fmt == PhotoFormat.DISPOSABLE || fmt == PhotoFormat.PHOTOBOOTH) && flashOn) {
                ImageCapture.FLASH_MODE_ON
            } else {
                ImageCapture.FLASH_MODE_OFF
            }
        scope.launch {
            // Développe en arrière-plan : le déclencheur est libre dès que la photo est prise.
            suspend fun <T> develop(block: () -> T): T {
                developingCount++
                try {
                    return withContext(developDispatcher) { block() }
                } finally {
                    developingCount--
                }
            }
            try {
                when (fmt) {
                    PhotoFormat.PHOTOBOOTH -> {
                        // 4 poses avec compte à rebours, comme dans une cabine
                        val captures = mutableListOf<Capture>()
                        for (pose in 1..fmt.shots) {
                            boothPose = pose
                            for (n in (if (pose == 1) 3 else 2) downTo 1) {
                                countdown = n
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                delay(800)
                            }
                            countdown = 0
                            shutterFx()
                            captures += capture()
                        }
                        boothPose = 0
                        busy = false
                        val uri = develop { PhotoPipeline.developBooth(context, captures, params, name, note) }
                        currentOnSaved()
                        developing = uri to null
                    }

                    PhotoFormat.HALF_FRAME -> {
                        shutterFx()
                        val shot = capture()
                        busy = false
                        val first = pendingHalf
                        if (first == null) {
                            pendingHalf = shot
                        } else {
                            pendingHalf = null
                            develop { PhotoPipeline.developHalf(context, first, shot, params, name, note, edge) }
                            currentOnSaved()
                        }
                    }

                    else -> {
                        shutterFx()
                        val shot = capture()
                        busy = false
                        val uri = develop { PhotoPipeline.develop(context, shot, params, name, fmt, note, edge) }
                        currentOnSaved()
                        if (fmt.instantPrint) developing = uri to fmt.frame
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Erreur : ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
                countdown = 0
                boothPose = 0
            }
        }
    }

    val evStep = caps?.evStep ?: 0f
    val calibrated = caps?.focusCalibrated ?: true
    val mfActive = settings.focusMode == FocusMode.MF

    // Sélecteur d'un réglage (molette, liste...) : affiché dans le tiroir sous la case touchée
    val dialArea: @Composable (Tab) -> Unit = { t ->
            Box(Modifier.fillMaxWidth().height(84.dp).padding(top = 4.dp), contentAlignment = Alignment.CenterStart) {
                val k = caps
                when (t) {
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
                        // En Polaroid, uniquement les recettes sur pellicule instantanée
                        // Selon le format : seulement les instantanés (Polaroid) ou les jetables (Jetable)
                        val shown = (customRecipes + Recipes.builtIns).filter { allowedBase(it.base()) }
                        // rangées par catégorie : mes recettes, Fujifilm, recettes, pellicules, instantanés...
                        val grouped = shown.groupBy { recipeCategory(it) }.toList()
                            .sortedBy { RecipeCategoryOrder.indexOf(it.first) }
                        grouped.forEach { (cat, list) ->
                        item(key = "rcat_$cat") { CategoryLabel(cat) }
                        items(list, key = { it.id }) { r ->
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
                    }

                    Tab.Text -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(InscriptionMode.entries) { m ->
                            Chip(m.label, inscription.mode == m) {
                                setInscription(inscription.copy(mode = m))
                                // un mot à écrire et rien d'écrit : on ouvre le clavier
                                if (m.hasText && inscription.text.isBlank()) typingNote = true
                            }
                        }
                        item {
                            ActionCard("✎", if (inscription.text.isBlank()) "Écrire" else "« ${inscription.text.take(12)} »") {
                                typingNote = true
                            }
                        }
                    }

                    Tab.Format -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FormatCategory.entries.forEach { cat ->
                            item(key = "cat_${cat.name}") { CategoryLabel(cat.label) }
                            items(PhotoFormat.entries.filter { it.category == cat }, key = { it.name }) { f ->
                                Chip(f.label, f == format) { setFormat(f) }
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

                    Tab.Wb -> {
                        // Molette en Kelvin : tourner passe en K (comme la bague de MP passe en MF)
                        val count = (KELVIN_MAX - KELVIN_MIN) / KELVIN_STEP + 1
                        RetroDial(
                            count = count,
                            index = ((recipe.kelvin - KELVIN_MIN) / KELVIN_STEP).coerceIn(0, count - 1),
                            onIndexChange = {
                                recipe = recipe.copy(wb = WhiteBalance.KELVIN, kelvin = KELVIN_MIN + it * KELVIN_STEP)
                            },
                            labelAt = { i ->
                                val k = KELVIN_MIN + i * KELVIN_STEP
                                if (k % 500 == 0) k.toString() else null
                            },
                            modifier = Modifier.fillMaxSize().padding(top = 18.dp),
                            spacing = 16.dp,
                            enabled = recipe.wb == WhiteBalance.KELVIN,
                            onTouched = { if (recipe.wb != WhiteBalance.KELVIN) recipe = recipe.copy(wb = WhiteBalance.KELVIN) },
                        )
                        Row(
                            Modifier.align(Alignment.TopStart).padding(start = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Pill("AUTO", recipe.wb == WhiteBalance.AUTO) { recipe = recipe.copy(wb = WhiteBalance.AUTO) }
                            Pill("K", recipe.wb == WhiteBalance.KELVIN) { recipe = recipe.copy(wb = WhiteBalance.KELVIN) }
                        }
                        // raccourci affiché quand la température tombe sur un préréglage
                        KelvinPresets.firstOrNull { it.second == recipe.kelvin && recipe.wb == WhiteBalance.KELVIN }?.let {
                            Text(
                                it.first.uppercase(),
                                color = Amber.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                                modifier = Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 3.dp),
                            )
                        }
                    }

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
    }

    // Éditeur de recette : sous le viseur en portrait, en panneau pivoté en paysage
    val editorPanel: @Composable (Modifier) -> Unit = { m ->
        RecipeEditor(
            recipe = recipe,
            modified = modified,
            onChange = { r ->
                if (r.baseId != recipe.baseId) {
                    val f = PhotoFormat.forFilm(r.base(), format)
                    if (f != format) format = f
                }
                recipe = r
            },
            onSave = { saveRecipe() },
            onDuplicate = { duplicateRecipe() },
            onReset = { recipe = saved },
            onDelete = if (!recipe.builtIn && customRecipes.any { it.id == recipe.id }) {
                { deleteRecipe() }
            } else {
                null
            },
            onClose = { editing = false },
            modifier = m,
            baseFilter = { allowedBase(it) },
        )
    }

    Box(Modifier.fillMaxSize()) {
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
                            format.frame?.takeIf { _ -> it.width > 0 && it.height > 0 }
                                ?.shaderFrameOnScreen(it.width.toFloat(), it.height.toFloat(), deviceRot)
                                ?: FilmProgram.FULL_FRAME,
                        )
                    },
                )
                val viewEdge = EdgeInfo(recipe.base().name.uppercase(), shots % RollCounter.ROLL_SIZE + 1)
                format.frame?.let { FrameViewfinderMask(it, viewEdge, deviceRot, Modifier.fillMaxSize()) }
                // aides à la composition (grille, coins, niveau), dans la fenêtre de l'image
                CompositionGuides(
                    display,
                    area = { w, h ->
                        format.frame?.holeRectOnScreen(w, h, deviceRot)
                            ?.let { androidx.compose.ui.geometry.Rect(it.left, it.top, it.right, it.bottom) }
                            ?: androidx.compose.ui.geometry.Rect(0f, 0f, w, h)
                    },
                    roll = roll,
                    rotation = deviceRot,
                    modifier = Modifier.fillMaxSize(),
                )

                // Date / petit mot (tirages instantanés), dessinés avec le même code que sur la photo finale
                if (inscription.active && format.canWrite) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas { c ->
                            val fr = format.frame
                            if (fr is PaperFrame) {
                                val (pw, ph) = physicalSize(size.width, size.height, deviceRot)
                                val p = fr.printRect(pw, ph)
                                c.nativeCanvas.save()
                                c.nativeCanvas.concat(fr.layoutMatrix(size.width, size.height, deviceRot))
                                Imprint.drawCaption(c.nativeCanvas, fr, p.left, p.top, p.width(), inscription)
                                c.nativeCanvas.restore()
                            } else if (fr != null) {
                                val h = fr.holeRect(size.width, size.height)
                                Imprint.drawStamp(c.nativeCanvas, h.left, h.top, h.width(), h.height(), inscription)
                            } else {
                                Imprint.drawStamp(c.nativeCanvas, 0f, 0f, size.width, size.height, inscription)
                            }
                        }
                    }
                }

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

                // Infos du viseur (compteur, affichage, exposition...) : pivotent avec le téléphone
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .requiredSize(if (landscape) w * 4f / 3f else w, if (landscape) w else w * 4f / 3f)
                        .graphicsLayer { rotationZ = uiAngle },
                ) {
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
                    // Jetable : la pastille du flash (le vrai flash du téléphone)
                    if ((format == PhotoFormat.DISPOSABLE || format == PhotoFormat.PHOTOBOOTH) &&
                        camera?.cameraInfo?.hasFlashUnit() == true
                    ) {
                        Pill("⚡ FLASH", flashOn, Modifier.padding(end = 8.dp)) { flashOn = !flashOn }
                    }
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
                        Modifier.size(40.dp).clip(CircleShape).clickable {
                            sheet = if (sheet == Sheet.Display) null else Sheet.Display
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        GridIcon(sheet == Sheet.Display)
                    }
                }

                // En bas : ce que le capteur fait réellement, comme l'écran d'un Fuji
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 18.dp, top = 28.dp, bottom = 12.dp),
                ) {
                  if (display.info) {
                    val shutterTxt = readout.exposureNs?.let { shutterLabel(it) }
                        ?: if (settings.manual) shutterLabel(settings.shutterNs) else "AUTO"
                    val isoTxt = readout.iso?.toString() ?: if (settings.manual) settings.iso.toString() else "AUTO"
                    val evTxt = if (!settings.manual && settings.evIndex != 0) "  EV ${evLabel(settings.evIndex, evStep)}" else ""
                    Text(
                        "${if (settings.manual) "M" else "A"}  $shutterTxt  ISO $isoTxt$evTxt",
                        color = Amber, fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                        style = OsdShadow,
                    )
                    val afLocked = readout.afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        readout.afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                    Text(
                        "${settings.focusMode.label}  ${distanceLabel(readout.focusDiopters, calibrated)}" +
                            if (!mfActive && afLocked) "  ●" else "",
                        color = Amber.copy(alpha = 0.85f), fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                        style = OsdShadow,
                    )
                  }
                    if (showMismatch && mismatch != null) {
                        Text(
                            "⚠ ignoré par le téléphone : $mismatch",
                            color = Warn, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp), style = OsdShadow,
                        )
                    }
                }

                // Demi-format : quelle vue, et la première en vignette
                if (format == PhotoFormat.HALF_FRAME) {
                    val first = pendingHalf
                    val thumb: androidx.compose.ui.graphics.ImageBitmap? by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, first) {
                        value = first?.let { withContext(Dispatchers.IO) { PhotoPipeline.preview(it, 256)?.asImageBitmap() } }
                    }
                    Column(
                        Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 52.dp),
                    ) {
                        Pill(if (first == null) "VUE 1/2" else "VUE 2/2", true) {}
                        thumb?.let {
                            androidx.compose.foundation.Image(
                                it, contentDescription = "Première vue",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .size(48.dp, 64.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .border(BorderStroke(1.dp, Amber), RoundedCornerShape(4.dp)),
                            )
                        }
                    }
                }

                // Photomaton : compte à rebours géant
                if (format == PhotoFormat.PHOTOBOOTH && boothPose > 0) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (countdown > 0) countdown.toString() else "★",
                            color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold,
                        )
                        Text("POSE $boothPose / ${PhotoFormat.PHOTOBOOTH.shots}", color = Amber, fontSize = 14.sp, letterSpacing = 3.sp)
                    }
                }

                }

                // Flash blanc au déclenchement
                Box(Modifier.fillMaxSize().alpha(flash.value).background(Color.White))
            }
        }

        if (editing && !landscape) {
            // ================= Éditeur de recette (aperçu en direct au-dessus) =================
            editorPanel(Modifier.fillMaxWidth().height(400.dp))
        } else {
            // ================= Minimal : pellicule, commandes, tiroir =================
            Column(
                Modifier
                    .fillMaxWidth()
                    // glisser vers le haut ouvre le tiroir des réglages
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dy -> if (dy < -12f) sheet = Sheet.Settings }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Pellicule en cours : toucher ouvre la bibliothèque
                Row(
                    Modifier
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Card)
                        .border(BorderStroke(1.5.dp, filmAccent(recipe.baseId)), RoundedCornerShape(999.dp))
                        .clickable { sheet = Sheet.Library }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RecipeThumb(recipe, 22.dp, 28.dp, Modifier.graphicsLayer { rotationZ = uiAngle })
                    // en paysage, la pastille se réduit à la vignette (le texte serait de travers)
                    if (!landscape) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        recipe.name + if (modified) " •" else "",
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    )
                    }
                    // le format, s'il n'est pas le standard (il se choisit dans la bibliothèque)
                    if (format != PhotoFormat.STANDARD && !landscape) {
                        Text(
                            "  ·  ${format.short}",
                            color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("▾", color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp)
                }

                // Commandes : dernière photo | déclencheur | réglages
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(
                        Modifier
                            .size(54.dp)
                            .graphicsLayer { rotationZ = uiAngle }
                            .clip(RoundedCornerShape(14.dp))
                            .border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.25f)), RoundedCornerShape(14.dp))
                            .clickable { onOpenGallery() },
                    ) {
                        if (lastPhoto != null) {
                            PhotoThumb(lastPhoto, 200, Modifier.fillMaxSize())
                        }
                        // une photo se développe en arrière-plan
                        if (developingCount > 0) {
                            Box(
                                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    color = Amber, strokeWidth = 2.dp, modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }

                    ShutterButton(busy = busy, onClick = { takePhoto() })

                    Box(Modifier.graphicsLayer { rotationZ = uiAngle }) {
                        SlidersButton(active = sheet == Sheet.Settings) {
                            sheet = if (sheet == Sheet.Settings) null else Sheet.Settings
                        }
                    }
                }

                // Poignée du tiroir
                Column(
                    Modifier.clickable { sheet = Sheet.Settings }.padding(bottom = 8.dp, top = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(38.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f)))
                    Text(
                        "GLISSER POUR LES RÉGLAGES",
                        color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp, letterSpacing = 2.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    // ================= Tiroir des réglages / bibliothèque des recettes =================
    if (sheet != null) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { sheet = null },
        )
    }
    // Les panneaux pivotent avec le téléphone : en paysage, ils montent depuis le bas du photographe
    Oriented(uiAngle, landscape) {
        if (sheet == Sheet.Settings) {
            BottomSheet(Modifier.align(Alignment.BottomCenter), onClose = { sheet = null }) {
                fun pick(t: Tab) {
                    tab = if (tab == t) Tab.Recipe else t
                    if ((t == Tab.Speed || t == Tab.Iso) && !settings.manual) enterManual()
                }

                SheetSection("EXPOSITION")
                TileRow {
                    SheetTile("MODE", if (settings.manual) "M" else "A", settings.manual) {
                        if (settings.manual) settings = settings.copy(manual = false) else enterManual()
                    }
                    SheetTile("VITESSE", if (settings.manual) shutterLabel(settings.shutterNs) else "AUTO", tab == Tab.Speed) { pick(Tab.Speed) }
                    SheetTile("ISO", if (settings.manual) settings.iso.toString() else "AUTO", tab == Tab.Iso) { pick(Tab.Iso) }
                    SheetTile("EV", if (settings.manual) "—" else evLabel(settings.evIndex, evStep), tab == Tab.Ev) { pick(Tab.Ev) }
                }
                if (tab == Tab.Speed || tab == Tab.Iso || tab == Tab.Ev) dialArea(tab)

                SheetSection("MISE AU POINT · OBJECTIF")
                TileRow {
                    SheetTile("FOCUS", settings.focusMode.label, tab == Tab.Focus) { pick(Tab.Focus) }
                    SheetTile("OBJECTIF", lensSel?.label ?: "1×", tab == Tab.Lens) { pick(Tab.Lens) }
                }
                if (tab == Tab.Focus || tab == Tab.Lens) dialArea(tab)

                // écrire sur le bord : seulement sur un tirage instantané (le format se choisit avec la pellicule)
                if (format.canWrite) {
                    SheetSection("TIRAGE")
                    TileRow {
                        SheetTile("TEXTE", if (inscription.active) inscription.mode.label else "—", tab == Tab.Text) { pick(Tab.Text) }
                    }
                    if (tab == Tab.Text) dialArea(tab)
                }

                SheetSection("IMAGE")
                TileRow {
                    SheetTile("BLANCS", if (recipe.wb == WhiteBalance.KELVIN) "${recipe.kelvin}K" else "AUTO", tab == Tab.Wb) { pick(Tab.Wb) }
                    SheetTile("RECETTE", "Éditer", false) {
                        sheet = null
                        editing = true
                    }
                }
                if (tab == Tab.Wb) dialArea(tab)
            }
        }
        if (sheet == Sheet.Display) {
            BottomSheet(Modifier.align(Alignment.BottomCenter), onClose = { sheet = null }) {
                SheetSection("GRILLE DE COMPOSITION")
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(GridType.entries) { g -> Chip(g.label, display.grid == g) { setDisplay(display.copy(grid = g)) } }
                }
                SheetSection("AIDES")
                ToggleRow("Niveau (horizon)", "La ligne devient ambre quand le téléphone est droit", display.level) {
                    setDisplay(display.copy(level = it))
                }
                ToggleRow("Infos d'exposition", "Vitesse, ISO et mise au point en bas du viseur", display.info) {
                    setDisplay(display.copy(info = it))
                }
                ToggleRow("Coins du viseur", "Les repères dans les angles", display.corners) {
                    setDisplay(display.copy(corners = it))
                }
            }
        }
        if (sheet == Sheet.Library) {
            RecipeLibrary(
                modifier = Modifier.align(Alignment.BottomCenter),
                recipes = customRecipes + Recipes.builtIns,
                selectedId = saved.id,
                formats = PhotoFormat.entries.filter { it.allows(recipe.base()) },
                format = format,
                onFormat = { setFormat(it) },
                onPick = { r ->
                    if (r.id == saved.id) {
                        sheet = null
                        editing = true
                    } else {
                        pickFilm(r)
                        sheet = null
                    }
                },
                onEdit = {
                    sheet = null
                    editing = true
                },
                onNew = {
                    duplicateRecipe()
                    sheet = null
                    editing = true
                },
                onClose = { sheet = null },
            )
        }
    if (editing && landscape) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { editing = false },
        )
        editorPanel(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)),
        )
    }
    }

    // Saisie du petit mot (clavier Android)
    if (typingNote) {
        var draft by remember { mutableStateOf(inscription.text) }
        AlertDialog(
            onDismissRequest = { typingNote = false },
            title = { Text(if (polaroid) "Écrire sur le Polaroid" else "Texte imprimé sur la photo") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(Imprint.MAX_TEXT) },
                    singleLine = true,
                    placeholder = { Text("Paris, été 2026") },
                    supportingText = { Text("${draft.length}/${Imprint.MAX_TEXT}") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val mode = when {
                        draft.isBlank() && inscription.mode == InscriptionMode.TEXT -> InscriptionMode.NONE
                        draft.isBlank() && inscription.mode == InscriptionMode.BOTH -> InscriptionMode.DATE
                        draft.isNotBlank() && inscription.mode == InscriptionMode.NONE -> InscriptionMode.TEXT
                        draft.isNotBlank() && inscription.mode == InscriptionMode.DATE -> InscriptionMode.BOTH
                        else -> inscription.mode
                    }
                    setInscription(Inscription(mode, draft.trim()))
                    typingNote = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { typingNote = false }) { Text("Annuler") } },
        )
    }

    // Tirage Polaroid qui se développe après la prise de vue
    developing?.let { (uri, frame) ->
        Oriented(uiAngle, landscape) { PrintDeveloping(uri, frame) { developing = null } }
    }
    }
}

/**
 * Conteneur plein écran qui pivote avec le téléphone : en paysage, son contenu est dessiné
 * droit pour le photographe (largeur et hauteur échangées).
 */
@Composable
private fun Oriented(angle: Float, landscape: Boolean, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = if (landscape) maxHeight else maxWidth
        val h = if (landscape) maxWidth else maxHeight
        Box(
            Modifier
                .align(Alignment.Center)
                .requiredSize(w, h)
                .graphicsLayer { rotationZ = angle },
            content = content,
        )
    }
}

/** Feuille qui monte du bas de l'écran (tiroir des réglages, bibliothèque). Glisser vers le bas ferme. */
@Composable
private fun BottomSheet(
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 600.dp)
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(Color(0xFF121212))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy > 14f) onClose() } }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(38.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f)))
        }
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = 22.dp),
            content = content,
        )
    }
}

@Composable
private fun SheetSection(title: String) {
    Text(
        title,
        color = Color(0xFFC9A86A), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp, start = 2.dp),
    )
}

@Composable
private fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

/** Case d'un réglage : nom en petit, valeur en gros. Ambre quand sa molette est ouverte. */
@Composable
private fun RowScope.SheetTile(label: String, value: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Amber.copy(alpha = 0.16f) else Color(0xFF1A1A1A))
            .border(BorderStroke(1.dp, if (selected) Amber else Color.White.copy(alpha = 0.08f)), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 9.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = if (selected) Amber else Color.White.copy(alpha = 0.55f), fontSize = 9.sp, letterSpacing = 1.sp, maxLines = 1)
        Text(
            value, color = if (selected) Amber else Color.White,
            fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        )
    }
}

private val OsdShadow = androidx.compose.ui.text.TextStyle(
    shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 1.5f), 6f),
)

/** Ligne avec un interrupteur (panneau Affichage). */
@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp)
            Text(subtitle, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        }
        androidx.compose.material3.Switch(
            checked = checked, onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.Black, checkedTrackColor = Amber,
            ),
        )
    }
}

/** Icône "grille" (bouton Affichage, en haut à droite du viseur). */
@Composable
private fun GridIcon(active: Boolean) {
    val c = if (active) Amber else Color.White
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val sw = 1.6.dp.toPx()
        drawRect(c, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        for (f in listOf(1f / 3f, 2f / 3f)) {
            drawLine(c, Offset(w * f, 0f), Offset(w * f, w), strokeWidth = sw)
            drawLine(c, Offset(0f, w * f), Offset(w, w * f), strokeWidth = sw)
        }
    }
}

/** Bouton "réglages" (deux curseurs). */
@Composable
private fun SlidersButton(active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(if (active) Amber.copy(alpha = 0.18f) else Card)
            .border(BorderStroke(1.dp, if (active) Amber else Color.White.copy(alpha = 0.15f)), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        val c = if (active) Amber else Color.White
        Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val sw = 2.dp.toPx()
            drawLine(c, Offset(0f, w * 0.3f), Offset(w, w * 0.3f), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(c, Offset(0f, w * 0.72f), Offset(w, w * 0.72f), strokeWidth = sw, cap = StrokeCap.Round)
            drawCircle(Card, w * 0.13f, Offset(w * 0.66f, w * 0.3f))
            drawCircle(c, w * 0.13f, Offset(w * 0.66f, w * 0.3f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
            drawCircle(Card, w * 0.13f, Offset(w * 0.36f, w * 0.72f))
            drawCircle(c, w * 0.13f, Offset(w * 0.36f, w * 0.72f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        }
    }
}

/** Bibliothèque des recettes, rangées par catégorie. */
@Composable
private fun RecipeLibrary(
    modifier: Modifier,
    recipes: List<Recipe>,
    selectedId: String,
    formats: List<PhotoFormat>,
    format: PhotoFormat,
    onFormat: (PhotoFormat) -> Unit,
    onPick: (Recipe) -> Unit,
    onEdit: () -> Unit,
    onNew: () -> Unit,
    onClose: () -> Unit,
) {
    val grouped = recipes.groupBy { recipeCategory(it) }.toList().sortedBy { RecipeCategoryOrder.indexOf(it.first) }
    BottomSheet(modifier, onClose) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PELLICULES", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, modifier = Modifier.weight(1f))
            ActionCard("✎", "Éditer", onEdit)
            ActionCard("+", "Nouvelle", onNew)
        }
        // Le format va avec la pellicule : on ne propose que ses variantes (35 mm, 6x6, Go, Spectra...)
        if (formats.size > 1) {
            SheetSection("FORMAT")
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(formats) { f -> Chip(f.label, f == format) { onFormat(f) } }
            }
        }
        grouped.forEach { (cat, list) ->
            SheetSection(cat)
            list.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pair.forEach { r ->
                        val selected = r.id == selectedId
                        val accent = filmAccent(r.baseId)
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1A1A1A))
                                .border(
                                    BorderStroke(1.5.dp, if (selected) accent else Color.White.copy(alpha = 0.08f)),
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { onPick(r) }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RecipeThumb(r, 34.dp, 44.dp)
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
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Petite étiquette de catégorie dans les listes horizontales. */
@Composable
private fun CategoryLabel(text: String) {
    Text(
        text,
        color = Amber.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 6.dp, end = 2.dp),
    )
}

/** Catégorie d'une recette dans le carrousel. */
private fun recipeCategory(r: Recipe): String = when {
    !r.builtIn -> "MES RECETTES"
    r.id.startsWith("r_") -> "RECETTES"
    else -> r.base().category
}

private val RecipeCategoryOrder = listOf(
    "MES RECETTES", "FUJIFILM", "RECETTES", "PELLICULES", "INSTANTANÉS", "JETABLES", "PHOTOMATON", "SANS FILTRE",
)

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
