package com.filmcam

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import kotlin.math.max

data class Photo(val uri: Uri, val id: Long)

/** Lit les photos prises par l'appli dans la galerie (MediaStore). */
object PhotoLibrary {

    fun load(context: Context): List<Photo> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val path = MediaStore.Images.Media.RELATIVE_PATH
        val selection = "$path LIKE ? OR $path LIKE ?"
        // "FilmCam" = ancien nom du dossier, on le garde pour retrouver les premiers tests.
        val args = arrayOf("Pictures/ArgentiCam%", "Pictures/FilmCam%")
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"

        val result = ArrayList<Photo>()
        context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                result.add(Photo(ContentUris.withAppendedId(collection, id), id))
            }
        }
        return result
    }

    fun thumbnail(context: Context, uri: Uri, px: Int): Bitmap? = try {
        context.contentResolver.loadThumbnail(uri, Size(px, px), null)
    } catch (_: Exception) {
        null
    }

    /** Décode l'image en la réduisant pour qu'elle ne dépasse pas ~maxDim pixels. */
    fun decode(context: Context, uri: Uri, maxDim: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Exception) {
        null
    }
}

/** Compteur de poses façon rouleau de 36. */
object RollCounter {
    const val ROLL_SIZE = 36
    private const val PREFS = "roll"
    private const val KEY = "shots"

    fun get(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0)

    fun increment(context: Context): Int {
        val next = get(context) + 1
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, next).apply()
        return next
    }

    /** "07/36" : numéro de la pose sur le rouleau en cours. */
    fun label(totalShots: Int): String {
        val frame = if (totalShots == 0) 0 else (totalShots - 1) % ROLL_SIZE + 1
        return "%02d/%d".format(frame, ROLL_SIZE)
    }
}
