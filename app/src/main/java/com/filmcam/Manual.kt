package com.filmcam

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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
}

/** Réglages "à la Fujifilm". `manual` = mode M (vitesse + ISO à la main), sinon mode A (auto). */
data class ManualSettings(
    val manual: Boolean = false,
    val iso: Int = 400,
    val shutterNs: Long = 1_000_000_000L / 125,
    /** Index de correction d'exposition (en pas de l'appareil). Utilisé en mode auto. */
    val evIndex: Int = 0,
    val wb: WhiteBalance = WhiteBalance.AUTO,
    val manualFocus: Boolean = false,
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

/** Ce que le capteur de ce téléphone sait faire. */
@OptIn(ExperimentalCamera2Interop::class)
class CameraCaps(camera: Camera) {
    private val info = Camera2CameraInfo.from(camera.cameraInfo)

    val isoStops: List<Int>
    val shutterStops: List<Long>
    val minFocusDistance: Float
    val evIndexes: List<Int>
    val evStep: Float

    init {
        val isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val standardIso = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
        isoStops = when {
            isoRange == null -> emptyList()
            else -> standardIso.filter { isoRange.contains(it) }.ifEmpty { listOf(isoRange.lower, isoRange.upper) }
        }

        val expRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val dens = listOf(8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2)
        val stops = dens.map { 1_000_000_000L / it } + 1_000_000_000L
        shutterStops = if (expRange == null) emptyList() else stops.filter { expRange.contains(it) }

        minFocusDistance = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

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
fun applyManual(camera: Camera, s: ManualSettings, caps: CameraCaps) {
    val b = CaptureRequestOptions.Builder()
    if (s.manual) {
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
        b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, s.shutterNs)
        // Pose longue = cadence d'aperçu plus lente, c'est normal.
        b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(s.shutterNs, 33_333_333L))
    }
    b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, s.wb.mode)
    if (s.manualFocus && caps.minFocusDistance > 0f) {
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, s.focus * caps.minFocusDistance)
    }
    Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(b.build())
    if (caps.evIndexes.isNotEmpty()) {
        camera.cameraControl.setExposureCompensationIndex(if (s.manual) 0 else s.evIndex)
    }
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
