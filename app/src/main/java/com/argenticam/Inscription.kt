package com.argenticam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ce qu'on imprime sur la photo. */
enum class InscriptionMode(val label: String, val hasDate: Boolean, val hasText: Boolean) {
    NONE("Rien", false, false),
    DATE("Date", true, false),
    TEXT("Petit mot", false, true),
    BOTH("Date + mot", true, true),
}

data class Inscription(val mode: InscriptionMode = InscriptionMode.NONE, val text: String = "") {
    val active: Boolean get() = mode != InscriptionMode.NONE && (mode.hasDate || text.isNotBlank())
}

object InscriptionStore {
    private const val PREFS = "inscription"

    fun load(context: Context): Inscription {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = InscriptionMode.entries.firstOrNull { it.name == p.getString("mode", null) } ?: InscriptionMode.NONE
        return Inscription(mode, p.getString("text", "") ?: "")
    }

    fun save(context: Context, i: Inscription) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", i.mode.name).putString("text", i.text).apply()
    }
}

/**
 * Dessin de l'inscription. Les mêmes fonctions servent pour l'aperçu du viseur (canvas Android
 * de Compose) et pour la photo finale (canvas d'un Bitmap) : ce qu'on voit est ce qu'on obtient.
 */
object Imprint {
    const val MAX_TEXT = 40

    /** Date façon compact argentique : '26 11 03 */
    fun stampDate(d: Date = Date()): String = SimpleDateFormat("''yy MM dd", Locale.US).format(d)

    /** Date écrite à la main sur un Polaroid : 3 nov. 2026 */
    fun handDate(d: Date = Date()): String = SimpleDateFormat("d MMM yyyy", Locale.FRENCH).format(d)

    private val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
        color = 0xFFFFA23A.toInt()
        letterSpacing = 0.08f
    }

    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // "cursive" / "casual" : polices manuscrites présentes sur la plupart des Android
        typeface = Typeface.create("cursive", Typeface.NORMAL).takeIf { it != Typeface.DEFAULT }
            ?: Typeface.create("casual", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        color = 0xD91F2A44.toInt() // encre bleu nuit
    }

    /**
     * Date orange lumineuse dans le coin inférieur droit (et le mot au-dessus), sur une photo
     * de [w] x [h] dessinée à partir de ([left], [top]).
     */
    @Synchronized
    fun drawStamp(canvas: Canvas, left: Float, top: Float, w: Float, h: Float, i: Inscription, date: Date = Date()) {
        if (!i.active) return
        val lines = buildList {
            if (i.mode.hasText && i.text.isNotBlank()) add(i.text.trim().uppercase(Locale.FRENCH))
            if (i.mode.hasDate) add(stampDate(date))
        }
        if (lines.isEmpty()) return
        val size = minOf(w, h) * 0.042f
        stampPaint.textSize = size
        // lueur des LED, comme sur les dos dateurs
        stampPaint.setShadowLayer(size * 0.35f, 0f, 0f, 0xCCFF5A00.toInt())
        val x = left + w - w * 0.06f
        var y = top + h - h * 0.045f
        // texte trop long : on réduit pour tenir dans 80 % de la largeur
        val widest = lines.maxOf { stampPaint.measureText(it) }
        if (widest > w * 0.8f) stampPaint.textSize = size * (w * 0.8f / widest)
        for (line in lines.asReversed()) {
            canvas.drawText(line, x, y, stampPaint)
            y -= stampPaint.textSize * 1.25f
        }
        stampPaint.clearShadowLayer()
    }

    /**
     * Légende manuscrite sur le bord du bas d'un Polaroid dont le tirage commence en
     * ([printLeft], [printTop]) et fait [printWidth] de large.
     */
    @Synchronized
    fun drawPolaroidCaption(canvas: Canvas, printLeft: Float, printTop: Float, printWidth: Float, i: Inscription, date: Date = Date()) {
        if (!i.active) return
        val parts = buildList {
            if (i.mode.hasText && i.text.isNotBlank()) add(i.text.trim())
            if (i.mode.hasDate) add(handDate(date))
        }
        if (parts.isEmpty()) return
        val line = parts.joinToString("  ·  ")
        val k = printWidth / Polaroid.PRINT_W
        val size = 6f * k
        handPaint.textSize = size
        val maxW = (Polaroid.PRINT_W - 2 * Polaroid.SIDE - 4f) * k
        val tw = handPaint.measureText(line)
        if (tw > maxW) handPaint.textSize = size * maxW / tw
        // au milieu du bord du bas (sous l'image de 79 mm)
        val bottomTop = printTop + (Polaroid.TOP + Polaroid.IMAGE) * k
        val bottomH = (Polaroid.PRINT_H - Polaroid.TOP - Polaroid.IMAGE) * k
        val baseline = bottomTop + bottomH * 0.5f + handPaint.textSize * 0.35f
        canvas.save()
        // légère inclinaison, comme écrit à la main
        canvas.rotate(-1.5f, printLeft + printWidth / 2f, baseline)
        canvas.drawText(line, printLeft + printWidth / 2f, baseline, handPaint)
        canvas.restore()
    }
}
