package com.argenticam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Une prise de vue brute : le JPEG du capteur et la rotation à appliquer. */
class Capture(val bytes: ByteArray, val rotation: Int)

/** Transforme des captures CameraX en photos "pellicule" enregistrées dans la galerie. */
object PhotoPipeline {

    /** Hauteur maximale d'une moitié de demi-format (le vrai demi-format est un petit négatif). */
    private const val HALF_MAX_PX = 3000

    /** Hauteur maximale d'une photo de photomaton (4 photos sur une petite bande). */
    private const val BOOTH_MAX_PX = 2000

    /** Lit le JPEG d'une capture et libère l'image tout de suite (pour enchaîner les poses). */
    fun readCapture(image: ImageProxy): Capture {
        val rotation = image.imageInfo.rotationDegrees
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        image.close()
        return Capture(bytes, rotation)
    }

    /** Métadonnées de la prise de vue (date, appareil, vitesse, ISO...), recopiées sur la photo finale. */
    private fun exifOf(c: Capture): ExifInterface? = try {
        ExifInterface(ByteArrayInputStream(c.bytes))
    } catch (_: Exception) {
        null
    }

    /** Décode (réduit si [maxPx] > 0) et remet la photo à l'endroit. */
    private fun decode(c: Capture, maxPx: Int = 0): Bitmap {
        val opts = BitmapFactory.Options()
        if (maxPx > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(c.bytes, 0, c.bytes.size, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            opts.inSampleSize = sample
        }
        var bmp = BitmapFactory.decodeByteArray(c.bytes, 0, c.bytes.size, opts)
            ?: error("Impossible de décoder la photo")
        if (maxPx > 0 && max(bmp.width, bmp.height) > maxPx) {
            val s = maxPx.toFloat() / max(bmp.width, bmp.height)
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
            bmp.recycle()
            bmp = scaled
        }
        if (c.rotation != 0) {
            val rotated = Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height,
                Matrix().apply { postRotate(c.rotation.toFloat()) }, true,
            )
            bmp.recycle()
            bmp = rotated
        }
        return bmp
    }

    /** Applique la recette (le même shader que l'aperçu). */
    private fun film(bmp: Bitmap, params: RenderParams): Bitmap {
        if (params.strength <= 0f) return bmp
        val out = FilmProcessor.process(bmp, params)
        bmp.recycle()
        return out
    }

    private fun stamp(bmp: Bitmap, inscription: Inscription): Bitmap {
        if (!inscription.active) return bmp
        val m = mutable(bmp)
        Imprint.drawStamp(android.graphics.Canvas(m), 0f, 0f, m.width.toFloat(), m.height.toFloat(), inscription)
        return m
    }

    /** Une photo unique (standard, jetable, Polaroid, Instax, 35 mm, 6x6). Retourne l'Uri enregistrée. */
    fun develop(
        context: Context,
        capture: Capture,
        params: RenderParams,
        name: String,
        format: PhotoFormat = PhotoFormat.STANDARD,
        inscription: Inscription = Inscription(),
        edge: EdgeInfo = EdgeInfo(name.uppercase(Locale.FRENCH), 1),
    ): Uri {
        val exif = exifOf(capture)
        val bmp = decode(capture)
        val frame = format.frame
        if (frame == null) {
            val result = stamp(film(bmp, params), inscription)
            try {
                return save(context, result, format, name, exif)
            } finally {
                result.recycle()
            }
        }

        // fenêtre visée dans le viseur, développée, puis posée dans son cadre
        val cropped = frame.crop(bmp)
        if (cropped !== bmp) bmp.recycle()
        var developed = film(cropped, params)
        // sur un négatif (35 mm, 6x6), la date est dans l'image ; sur un instantané, écrite sur le bord
        if (frame !is PaperFrame) developed = stamp(developed, inscription)
        val print = frame.compose(developed, edge)
        developed.recycle()
        if (frame is PaperFrame) {
            Imprint.drawCaption(android.graphics.Canvas(print), frame, 0f, 0f, print.width.toFloat(), inscription)
        }
        try {
            return save(context, print, format, name, exif)
        } finally {
            print.recycle()
        }
    }

    /** Petite image d'une capture (vignette), à l'endroit. */
    fun preview(c: Capture, maxPx: Int): Bitmap? = try {
        decode(c, maxPx)
    } catch (_: Exception) {
        null
    }

    /** Demi-format : les deux vues développées, côte à côte sur la bande, enregistrées. */
    fun developHalf(
        context: Context,
        first: Capture,
        second: Capture,
        params: RenderParams,
        name: String,
        inscription: Inscription,
        edge: EdgeInfo,
    ): Uri {
        val exif = exifOf(second)
        val left = film(decode(first, HALF_MAX_PX), params)
        val right = stamp(film(decode(second, HALF_MAX_PX), params), inscription)
        val strip = HalfFrameStrip.compose(left, right, edge)
        left.recycle()
        right.recycle()
        try {
            return save(context, strip, PhotoFormat.HALF_FRAME, name, exif)
        } finally {
            strip.recycle()
        }
    }

    /** Photomaton : 4 poses recadrées et développées, sur une bande de cabine photo. */
    fun developBooth(
        context: Context,
        captures: List<Capture>,
        params: RenderParams,
        name: String,
        inscription: Inscription,
    ): Uri {
        val exif = exifOf(captures.first())
        val frames = captures.mapIndexed { i, c ->
            val b = decode(c, BOOTH_MAX_PX)
            val cropped = PhotoboothStrip.crop(b)
            if (cropped !== b) b.recycle()
            val f = film(cropped, params)
            // la date / le mot sur la dernière photo de la bande
            if (i == captures.lastIndex) stamp(f, inscription) else f
        }
        val strip = PhotoboothStrip.compose(frames)
        frames.forEach { it.recycle() }
        try {
            return save(context, strip, PhotoFormat.PHOTOBOOTH, name, exif)
        } finally {
            strip.recycle()
        }
    }

    private fun mutable(b: Bitmap): Bitmap {
        if (b.isMutable) return b
        val copy = b.copy(Bitmap.Config.ARGB_8888, true)
        b.recycle()
        return copy
    }

    /**
     * Nom de fichier : ARGENTICAM_<format>_<pellicule>_<date>_<heure>.jpg
     * (la galerie s'en sert pour ranger les photos : voir PhotoLibrary.parseName).
     */
    private fun save(context: Context, bmp: Bitmap, format: PhotoFormat, recipe: String, exif: ExifInterface?): Uri {
        val slug = PhotoLibrary.slug(recipe)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ARGENTICAM_${PhotoLibrary.formatTag(format)}_${slug}_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ArgentiCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Impossible de créer le fichier dans la galerie")
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                check(bmp.compress(Bitmap.CompressFormat.JPEG, 100, out)) { "Échec de la compression" }
            }
            values.clear()
            copyExif(context, uri, exif, recipe)
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
    )

    /**
     * Recopie les EXIF de la capture d'origine. Les pixels sont déjà à l'endroit,
     * donc l'orientation est "normale" ; la recette est notée dans la description.
     */
    private fun copyExif(context: Context, uri: Uri, src: ExifInterface?, recipe: String) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val dst = ExifInterface(pfd.fileDescriptor)
                if (src != null) {
                    for (tag in EXIF_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
                }
                dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                dst.setAttribute(ExifInterface.TAG_SOFTWARE, "ArgentiCam")
                dst.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Recette : $recipe")
                dst.saveAttributes()
            }
        } catch (_: Exception) {
            // pas d'EXIF n'empêche pas d'avoir la photo
        }
    }
}
