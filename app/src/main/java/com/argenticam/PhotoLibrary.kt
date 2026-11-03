package com.argenticam

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import kotlin.math.max

data class Photo(
    val uri: Uri,
    val id: Long,
    /** Format de prise de vue, retrouvé dans le nom du fichier. */
    val format: PhotoFormat = PhotoFormat.STANDARD,
    /** Nom de la pellicule / recette, pour le bord de la planche contact. */
    val film: String = "",
    val width: Int = 0,
    val height: Int = 0,
) {
    /** Photo "classique" (rangée sur la planche contact) ou tirage encadré (Polaroid, bandes...). */
    val isClassic: Boolean get() = format == PhotoFormat.STANDARD || format == PhotoFormat.DISPOSABLE

    /** Proportions largeur / hauteur (3:4 par défaut). */
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 0.75f
}

/** Lit les photos prises par l'appli dans la galerie (MediaStore). */
object PhotoLibrary {

    fun load(context: Context): List<Photo> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        val path = MediaStore.Images.Media.RELATIVE_PATH
        val selection = "$path LIKE ? OR $path LIKE ?"
        // "FilmCam" = ancien nom du dossier, on le garde pour retrouver les premiers tests.
        val args = arrayOf("Pictures/ArgentiCam%", "Pictures/FilmCam%")
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"

        val result = ArrayList<Photo>()
        context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val (format, film) = parseName(c.getString(nameCol) ?: "")
                result.add(
                    Photo(
                        ContentUris.withAppendedId(collection, id), id, format, film,
                        c.getInt(wCol), c.getInt(hCol),
                    ),
                )
            }
        }
        return result
    }

    fun slug(name: String): String =
        name.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "photo" }

    fun formatTag(f: PhotoFormat): String = f.name.lowercase(java.util.Locale.ROOT).replace('_', '-')

    private val tags = PhotoFormat.entries.associateBy { formatTag(it) }

    /** Anciens noms ("ARGENTICAM_polaroid-portra-400_...") : le format était au début du nom de pellicule. */
    private val oldPrefixes = listOf(
        "polaroid-go-" to PhotoFormat.POLAROID_GO,
        "instax-mini-" to PhotoFormat.INSTAX_MINI,
        "medium-6x6-" to PhotoFormat.MEDIUM_6X6,
        "demi-format-" to PhotoFormat.HALF_FRAME,
        "photomaton-" to PhotoFormat.PHOTOBOOTH,
        "spectra-" to PhotoFormat.SPECTRA,
        "film35-" to PhotoFormat.FILM35,
        "polaroid-" to PhotoFormat.POLAROID,
    )

    /** Retrouve le format et le nom de pellicule à partir du nom de fichier. */
    fun parseName(displayName: String): Pair<PhotoFormat, String> {
        val parts = displayName.substringBeforeLast('.').split('_')
        var format = PhotoFormat.STANDARD
        var slug = parts.getOrNull(1) ?: ""
        if (parts.size >= 5 && tags.containsKey(parts[1])) {
            format = tags.getValue(parts[1])
            slug = parts[2]
        } else {
            oldPrefixes.firstOrNull { slug.startsWith(it.first) }?.let { (prefix, f) ->
                format = f
                slug = slug.removePrefix(prefix)
            }
        }
        return format to slug.replace('-', ' ').uppercase(java.util.Locale.FRENCH)
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
