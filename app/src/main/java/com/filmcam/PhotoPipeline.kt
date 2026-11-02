package com.filmcam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.ImageProxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Transforme une capture CameraX en photo "pellicule" enregistrée dans la galerie. */
object PhotoPipeline {

    /** Retourne l'Uri de la photo enregistrée. À appeler hors du thread principal. */
    fun develop(context: Context, image: ImageProxy, look: Look): Uri {
        val rotation = image.imageInfo.rotationDegrees
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        image.close()

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

        val result = if (look.preset == Presets.original) bmp else FilmProcessor.process(bmp, look)
        if (result !== bmp) bmp.recycle()

        try {
            return save(context, result, look.preset)
        } finally {
            result.recycle()
        }
    }

    private fun save(context: Context, bmp: Bitmap, preset: FilmPreset): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ARGENTICAM_${preset.id}_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ArgentiCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Impossible de créer le fichier dans la galerie")
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                check(bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)) { "Échec de la compression" }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
