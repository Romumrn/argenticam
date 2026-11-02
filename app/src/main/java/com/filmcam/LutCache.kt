package com.filmcam

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * LUT 3D des simulations (assets/luts/<nom>.png, 32 tranches de 32x32 côte à côte : 1024x32).
 * Décodées une seule fois, partagées entre l'aperçu et le développement des photos.
 */
object LutCache {
    const val SIZE = 32

    @Volatile private var assets: AssetManager? = null
    private val cache = HashMap<String, Bitmap>()

    fun init(assetManager: AssetManager) {
        assets = assetManager
    }

    @Synchronized
    fun bitmap(name: String): Bitmap? {
        cache[name]?.let { return it }
        val am = assets ?: return null
        val bmp = try {
            am.open("luts/$name.png").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false })
            }
        } catch (_: Exception) {
            null
        } ?: return null
        if (bmp.width != SIZE * SIZE || bmp.height != SIZE) return null
        cache[name] = bmp
        return bmp
    }
}
