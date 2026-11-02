package com.filmcam

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

enum class WhiteBalance(val label: String, val mode: Int) {
    AUTO("Auto", CaptureRequest.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT("Soleil", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Nuageux", CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    TUNGSTEN("Tungstène", CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("Fluo", CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT),

    /** Température réglée à la main : le téléphone est calé en lumière du jour, le shader corrige. */
    KELVIN("Kelvin", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
}

/** Le sélecteur de mise au point d'un Fuji : S / C / M. */
enum class FocusMode(val label: String) {
    AFS("AF-S"), AFC("AF-C"), MF("MF")
}

/** Réglages de prise de vue. `manual` = mode M (vitesse + ISO à la main), sinon mode A (auto). */
data class ManualSettings(
    val manual: Boolean = false,
    val iso: Int = 400,
    val shutterNs: Long = 1_000_000_000L / 125,
    /** Index de correction d'exposition (en pas de l'appareil). Utilisé en mode auto. */
    val evIndex: Int = 0,
    val focusMode: FocusMode = FocusMode.AFC,
    /** 0 = infini, 1 = mise au point la plus proche. */
    val focus: Float = 0f,
)

fun shutterLabel(ns: Long): String {
    val sec = ns / 1_000_000_000.0
    return if (sec >= 0.95) "${sec.roundToInt()}\"" else "1/${(1.0 / sec).roundToInt()}"
}

fun evLabel(index: Int, step: Float): String {
    if (index == 0) return "0"
    return "%+.1f".format(index * step)
}

/** Distance de mise au point lisible, à partir des dioptries (1/m) renvoyées par le capteur. */
fun distanceLabel(diopters: Float?, calibrated: Boolean): String = when {
    diopters == null -> "—"
    diopters < 0.05f -> "∞"
    !calibrated -> "%.1f".format(diopters)
    else -> {
        val m = 1f / diopters
        if (m >= 1f) "%.1fm".format(m) else "${(m * 100).roundToInt()}cm"
    }
}

/** Ce que le capteur de ce téléphone sait faire — et ce qu'il laisse faire aux applis. */
@OptIn(ExperimentalCamera2Interop::class)
class CameraCaps(camera: Camera) {
    private val info = Camera2CameraInfo.from(camera.cameraInfo)

    val isoStops: List<Int>
    val shutterStops: List<Long>
    val minFocusDistance: Float
    val evIndexes: List<Int>
    val evStep: Float

    /** Le téléphone accepte qu'on règle vitesse et ISO (capacité MANUAL_SENSOR). */
    val canManualExposure: Boolean

    /** Le téléphone accepte qu'on coupe l'autofocus et qu'on pilote l'objectif. */
    val canManualFocus: Boolean

    /** Les distances renvoyées sont en vraies dioptries (sinon : échelle arbitraire). */
    val focusCalibrated: Boolean
    val noiseReductionModes: Set<Int>

    init {
        val capabilities = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        canManualExposure = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)

        val isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val standardIso = listOf(50, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 12800)
        isoStops = when {
            isoRange == null -> emptyList()
            else -> standardIso.filter { isoRange.contains(it) }.ifEmpty { listOf(isoRange.lower, isoRange.upper) }
        }

        val expRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        // Les vitesses d'un dial Fuji, au tiers de diaphragme
        val dens = listOf(
            8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250,
            200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3, 2,
        )
        val stops = dens.map { 1_000_000_000L / it } + 1_000_000_000L
        shutterStops = if (expRange == null) emptyList() else stops.filter { expRange.contains(it) }

        minFocusDistance = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val afModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        canManualFocus = minFocusDistance > 0f && afModes.contains(CameraCharacteristics.CONTROL_AF_MODE_OFF)
        focusCalibrated = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) !=
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED

        noiseReductionModes = (
            info.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                ?: IntArray(0)
            ).toSet()

        val ev = camera.cameraInfo.exposureState
        evStep = ev.exposureCompensationStep.toFloat()
        val range = ev.exposureCompensationRange
        evIndexes = if (ev.isExposureCompensationSupported) (range.lower..range.upper).toList() else emptyList()
    }

    fun nearestIso(target: Int): Int = isoStops.minByOrNull { abs(it - target) } ?: target
    fun nearestShutter(target: Long): Long = shutterStops.minByOrNull { abs(it - target) } ?: target
}

/** Envoie les réglages à la caméra (aperçu ET photo). */
@OptIn(ExperimentalCamera2Interop::class)
fun applyManual(camera: Camera, s: ManualSettings, caps: CameraCaps, recipe: Recipe) {
    val b = CaptureRequestOptions.Builder()
    if (s.manual && caps.canManualExposure) {
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
        b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, s.shutterNs)
        // Pose longue = cadence d'aperçu plus lente, c'est normal.
        b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(s.shutterNs, 33_333_333L))
    }
    b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, recipe.wb.mode)
    if (s.focusMode == FocusMode.MF && caps.canManualFocus) {
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, s.focus * caps.minFocusDistance)
    }
    // Réduction du bruit de la recette -> traitement du téléphone
    val nr = when {
        recipe.noiseReduction <= -2 -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
        recipe.noiseReduction >= 2 -> CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
        else -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
    }
    if (nr in caps.noiseReductionModes) b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, nr)

    Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(b.build())
    if (caps.evIndexes.isNotEmpty()) {
        camera.cameraControl.setExposureCompensationIndex(if (s.manual) 0 else s.evIndex)
    }
}

/** Ce que le capteur fait VRAIMENT, image par image (et pas ce qu'on lui a demandé). */
data class LiveReadout(
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val focusDiopters: Float? = null,
    val afState: Int? = null,
)

/** Branche une lecture en direct des résultats de capture sur l'aperçu. */
@OptIn(ExperimentalCamera2Interop::class)
fun Preview.Builder.withReadout(onReadout: (LiveReadout) -> Unit): Preview.Builder {
    Camera2Interop.Extender(this).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
        private var last = 0L
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val now = SystemClock.uptimeMillis()
            if (now - last < 150) return
            last = now
            onReadout(
                LiveReadout(
                    iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                    exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                    afState = result.get(CaptureResult.CONTROL_AF_STATE),
                ),
            )
        }
    })
    return this
}

/**
 * Un objectif / une focale. Soit une vraie caméra physique (cameraId renseigné),
 * soit un cran de zoom de la caméra logique du téléphone (cameraId = null).
 */
data class Lens(val cameraId: String?, val label: String, val zoom: Float = 1f, val equivMm: Float = 0f) {
    val display: String get() = if (equivMm > 0f && cameraId == null) "$label · ${equivMm.roundToInt()}mm" else label
}

/** Sélecteur CameraX qui cible une caméra précise (par son id Camera2). */
@OptIn(ExperimentalCamera2Interop::class)
fun selectorFor(cameraId: String?): CameraSelector {
    if (cameraId == null) return CameraSelector.DEFAULT_BACK_CAMERA
    return CameraSelector.Builder()
        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
        .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId } }
        .build()
}

private fun zoomLabel(z: Float) = if (z == z.toInt().toFloat()) "${z.toInt()}×" else "$z×"

/**
 * Liste les objectifs disponibles. Si le téléphone expose plusieurs caméras arrière, on les
 * propose par focale équivalente 24x36. Sinon (cas fréquent : ultra grand-angle et télé sont
 * cachés derrière une seule caméra "logique"), on propose des crans de zoom qui font basculer
 * le téléphone d'un objectif à l'autre.
 */
@OptIn(ExperimentalCamera2Interop::class)
fun discoverLenses(provider: ProcessCameraProvider, bound: Camera): List<Lens> {
    val backs = provider.availableCameraInfos.mapNotNull { info ->
        val c2 = Camera2CameraInfo.from(info)
        if (c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
            return@mapNotNull null
        }
        val f = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val equiv = if (f != null && size != null) f * 43.27f / hypot(size.width, size.height) else 0f
        c2.cameraId to equiv
    }.filter { it.second > 0f }.sortedBy { it.second }

    val distinct = mutableListOf<Pair<String, Float>>()
    for (b in backs) if (distinct.none { abs(it.second - b.second) / b.second < 0.08f }) distinct += b

    if (distinct.size > 1) {
        return distinct.map { (id, mm) -> Lens(id, "${mm.roundToInt()}mm", 1f, mm) }
    }

    val zoom = bound.cameraInfo.zoomState.value ?: return emptyList()
    val mainMm = backs.firstOrNull()?.second ?: 0f
    val stops = listOf(0.5f, 0.6f, 0.7f, 1f, 2f, 3f, 5f, 10f)
        .filter { it >= zoom.minZoomRatio - 0.01f && it <= zoom.maxZoomRatio + 0.01f }
    return stops.map { z -> Lens(null, zoomLabel(z), z, mainMm * z) }
}

/** L'objectif "principal" (≈ 24-28 mm) : celui que le téléphone ouvre par défaut. */
fun defaultLens(lenses: List<Lens>): Lens? =
    lenses.firstOrNull { it.cameraId == null && it.zoom == 1f }
        ?: lenses.minByOrNull { abs(it.equivMm - 26f) }
