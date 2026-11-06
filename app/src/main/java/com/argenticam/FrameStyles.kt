package com.argenticam

import android.content.Context
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * Couleur du cadre des tirages instantanés, d'après les éditions spéciales de films Polaroid :
 * Color Frames (un cadre de couleur différente à chaque photo), Gradient Frames (une tranche
 * du spectre à chaque photo), Black Frame, Metallic (Golden Moments, Silver Linings,
 * Metallic Nights)... La forme ronde reprend la Round Frame Edition.
 */
enum class FrameKind { SOLID, GRADIENT, METALLIC, COLOR_PACK, SPECTRUM }

class FrameStyle(
    val id: String,
    val label: String,
    val kind: FrameKind,
    /** Couleurs ARGB : une (uni), deux ou plus (dégradé / métal). */
    val colors: IntArray,
    /** Couleur de l'encre de la légende manuscrite (blanche sur un cadre sombre). */
    val ink: Int = INK_DARK,
) {
    /** Couleurs effectives pour la photo n° [frameNumber] (les packs changent à chaque vue). */
    fun colorsFor(frameNumber: Int): IntArray = when (kind) {
        FrameKind.COLOR_PACK -> intArrayOf(colors[Math.floorMod(frameNumber - 1, colors.size)])
        FrameKind.SPECTRUM -> {
            // chaque photo prend la tranche suivante du spectre
            val h = Math.floorMod((frameNumber - 1) * 36, 360).toFloat()
            intArrayOf(
                AColor.HSVToColor(floatArrayOf(h, 0.55f, 0.97f)),
                AColor.HSVToColor(floatArrayOf((h + 45f) % 360f, 0.6f, 0.92f)),
            )
        }
        else -> colors
    }

    /** Encre adaptée à la couleur de cette photo. */
    fun inkFor(frameNumber: Int): Int {
        if (kind != FrameKind.COLOR_PACK) return ink
        val c = colorsFor(frameNumber)[0]
        val lum = 0.2126 * AColor.red(c) + 0.7152 * AColor.green(c) + 0.0722 * AColor.blue(c)
        return if (lum < 110) INK_LIGHT else INK_DARK
    }

    /** Prépare [paint] pour peindre le cadre du tirage [print]. */
    fun applyTo(paint: Paint, print: RectF, frameNumber: Int) {
        val c = colorsFor(frameNumber)
        paint.shader = null
        when {
            c.size == 1 -> paint.color = c[0]
            kind == FrameKind.METALLIC -> {
                // reflet en biais, comme une feuille métallisée
                paint.color = AColor.WHITE
                paint.shader = LinearGradient(
                    print.left, print.top, print.right, print.bottom,
                    c, null, Shader.TileMode.CLAMP,
                )
            }
            else -> {
                paint.color = AColor.WHITE
                paint.shader = LinearGradient(
                    print.left, print.top, print.left, print.bottom,
                    c, null, Shader.TileMode.CLAMP,
                )
            }
        }
    }

    companion object {
        const val INK_DARK = 0xD91F2A44.toInt()   // bleu nuit
        const val INK_LIGHT = 0xE6F4F1EA.toInt()  // blanc cassé
    }
}

private fun c(hex: Long) = hex.toInt()

object FrameStyles {
    val WHITE = FrameStyle("white", "Blanc", FrameKind.SOLID, intArrayOf(c(0xFFF7F6F2)))

    val all: List<FrameStyle> = listOf(
        WHITE,
        FrameStyle("cream", "Crème", FrameKind.SOLID, intArrayOf(c(0xFFF1E9D8))),
        FrameStyle("black", "Noir", FrameKind.SOLID, intArrayOf(c(0xFF151515)), FrameStyle.INK_LIGHT),
        // Color Frames : une couleur unie au choix...
        FrameStyle("red", "Rouge", FrameKind.SOLID, intArrayOf(c(0xFFD8463B)), FrameStyle.INK_LIGHT),
        FrameStyle("orange", "Orange", FrameKind.SOLID, intArrayOf(c(0xFFEE8A2C))),
        FrameStyle("yellow", "Jaune", FrameKind.SOLID, intArrayOf(c(0xFFF3C84A))),
        FrameStyle("green", "Vert", FrameKind.SOLID, intArrayOf(c(0xFF3F9C6B)), FrameStyle.INK_LIGHT),
        FrameStyle("blue", "Bleu", FrameKind.SOLID, intArrayOf(c(0xFF2F6DB0)), FrameStyle.INK_LIGHT),
        FrameStyle("purple", "Violet", FrameKind.SOLID, intArrayOf(c(0xFF7A4EA3)), FrameStyle.INK_LIGHT),
        FrameStyle("pink", "Rose", FrameKind.SOLID, intArrayOf(c(0xFFEE93B6))),
        // ... ou le pack mélangé : une couleur différente à chaque photo
        FrameStyle(
            "colorpack", "Color Frames", FrameKind.COLOR_PACK,
            intArrayOf(c(0xFFD8463B), c(0xFFEE8A2C), c(0xFFF3C84A), c(0xFF3F9C6B), c(0xFF2F6DB0), c(0xFF7A4EA3), c(0xFFEE93B6), c(0xFF151515)),
        ),
        // Gradient Frames : une tranche du spectre à chaque photo
        FrameStyle("spectrum", "Gradient Frames", FrameKind.SPECTRUM, intArrayOf(c(0xFFF7B267), c(0xFFF25F5C))),
        FrameStyle("sunset", "Coucher de soleil", FrameKind.GRADIENT, intArrayOf(c(0xFFFFD27A), c(0xFFF4845F), c(0xFFC0397B))),
        FrameStyle("ocean", "Océan", FrameKind.GRADIENT, intArrayOf(c(0xFF7FE0D0), c(0xFF2C7DA0), c(0xFF1B3A6B)), FrameStyle.INK_LIGHT),
        FrameStyle("aurora", "Aurore", FrameKind.GRADIENT, intArrayOf(c(0xFFF9C6D9), c(0xFFB79CED), c(0xFF6F8FE8))),
        // Metallic
        FrameStyle(
            "gold", "Or (Golden Moments)", FrameKind.METALLIC,
            intArrayOf(c(0xFF9C7322), c(0xFFF3DC8A), c(0xFFB88D2E), c(0xFFF7E7A6), c(0xFF8A651C)),
        ),
        FrameStyle(
            "silver", "Argent (Silver Linings)", FrameKind.METALLIC,
            intArrayOf(c(0xFF8D9095), c(0xFFF3F4F6), c(0xFFA3A6AB), c(0xFFFAFAFB), c(0xFF75787D)),
        ),
        FrameStyle(
            "nights", "Metallic Nights", FrameKind.METALLIC,
            intArrayOf(c(0xFF1C2A55), c(0xFF5A6FB0), c(0xFF3B2465), c(0xFF8A5BB8), c(0xFF1A1F3D)),
            FrameStyle.INK_LIGHT,
        ),
    )

    fun byId(id: String?): FrameStyle = all.firstOrNull { it.id == id } ?: WHITE
}

/** Choix du cadre, gardé d'une fois sur l'autre. */
object FrameStyleStore {
    private const val PREFS = "frame_style"

    fun load(context: Context): Pair<FrameStyle, Boolean> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return FrameStyles.byId(p.getString("style", null)) to p.getBoolean("round", false)
    }

    fun save(context: Context, style: FrameStyle, round: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("style", style.id).putBoolean("round", round).apply()
    }
}
