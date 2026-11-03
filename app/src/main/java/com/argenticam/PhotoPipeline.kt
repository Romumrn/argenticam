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

/** Transforme une capture CameraX en photo "pellicule" enregistrée dans la galerie. */
object PhotoPipeline {

    /** Retourne l'Uri de la photo enregistrée. À appeler hors du thread principal. */
    fun develop(
        context: Context,
        image: ImageProxy,
        params: RenderParams,
        name: String,
        polaroid: Boolean = false,
        inscription: Inscription = Inscription(),
    ): Uri {
        val rotation = image.imageInfo.rotationDegrees
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        image.close()
        // Métadonnées de la prise de vue (date, appareil, vitesse, ISO...), recopiées sur la photo finale
        val exif = try {
            ExifInterface(ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            null
        }

        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Impossible de décoder la photo")
        if (rotation != 0) {
            val rotated = Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true,
            )
            bmp.recycle()
            bmp = rotated
        }

        if (polaroid) {
            // carré visé dans le viseur, développé, puis posé sur le cadre papier
            val square = Polaroid.cropSquare(bmp)
            if (square !== bmp) bmp.recycle()
            val developed = if (params.strength <= 0f) square else FilmProcessor.process(square, params)
            if (developed !== square) square.recycle()
            bmp = Polaroid.compose(developed)
            developed.recycle()
            Imprint.drawPolaroidCaption(android.graphics.Canvas(bmp), 0f, 0f, bmp.width.toFloat(), inscription)
            try {
                return save(context, bmp, "polaroid-$name", exif, name)
            } finally {
                bmp.recycle()
            }
        }

        var result = if (params.strength <= 0f) bmp else FilmProcessor.process(bmp, params)
        if (result !== bmp) bmp.recycle()
        if (inscription.active) {
            if (!result.isMutable) {
                val copy = result.copy(Bitmap.Config.ARGB_8888, true)
                result.recycle()
                result = copy
            }
            Imprint.drawStamp(
                android.graphics.Canvas(result), 0f, 0f, result.width.toFloat(), result.height.toFloat(), inscription,
            )
        }

        try {
            return save(context, result, name, exif, name)
        } finally {
            result.recycle()
        }
    }

    private fun save(context: Context, bmp: Bitmap, name: String, exif: ExifInterface?, recipe: String): Uri {
        val slug = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "photo" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ARGENTICAM_${slug}_$stamp.jpg")
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
