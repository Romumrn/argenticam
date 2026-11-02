package com.filmcam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

enum class Level(val label: String, val amount: Float) {
    OFF("Off", 0f), WEAK("Faible", 0.5f), STRONG("Fort", 1f)
}

/**
 * Une recette, au sens Fujifilm : une pellicule de base + tous les réglages qui la personnalisent.
 * Les plages reprennent celles des boîtiers Fuji.
 */
data class Recipe(
    val id: String,
    val name: String,
    /** Pellicule de base ([FilmPreset.id]). */
    val baseId: String,
    val builtIn: Boolean = false,
    // Balance des blancs
    val wb: WhiteBalance = WhiteBalance.AUTO,
    val kelvin: Int = 5500,          // utilisé si wb == KELVIN, 2500..10000
    val wbShiftR: Int = 0,           // -9..+9
    val wbShiftB: Int = 0,           // -9..+9
    // Tonalité
    val dynamicRange: Int = 100,     // 100, 200, 400
    val highlight: Float = 0f,       // -2..+4, pas de 0.5
    val shadow: Float = 0f,          // -2..+4, pas de 0.5
    val color: Int = 0,              // -4..+4
    val sharpness: Int = 0,          // -4..+4
    val clarity: Int = 0,            // -5..+5
    val noiseReduction: Int = 0,     // -4..+4 (appliqué par le téléphone)
    // Effets
    val grain: Level = Level.OFF,
    val grainLarge: Boolean = false,
    val colorChrome: Level = Level.OFF,
    val chromeBlue: Level = Level.OFF,
    val vignette: Int = 0,           // 0..5
    val halation: Int = 0,           // 0..5
    val strength: Int = 10,          // 0..10 (×10 %)
)

/** Tous les paramètres envoyés au shader. */
class RenderParams(
    val exposure: Float,
    val contrast: Float,
    val saturation: Float,
    val fade: Float,
    val bw: Float,
    val gain: FloatArray,
    val shadowTint: FloatArray,
    val highlightTint: FloatArray,
    val grain: Float,
    val grainSize: Float,
    val vignette: Float,
    val halation: Float,
    val strength: Float,
    val sharpness: Float,
    val clarity: Float,
    val chrome: Float,
    val chromeBlue: Float,
    val dynamicRange: Float,
    val highlight: Float,
    val shadow: Float,
)

fun Recipe.base(): FilmPreset = Presets.byId(baseId)

fun Recipe.render(): RenderParams {
    val p = base()
    val wb = wbGain()
    return RenderParams(
        exposure = p.exposure,
        contrast = p.contrast,
        saturation = (p.saturation * (1f + 0.09f * color)).coerceAtLeast(0f),
        fade = p.fade,
        bw = if (p.bw) 1f else 0f,
        gain = floatArrayOf(p.gain[0] * wb[0], p.gain[1] * wb[1], p.gain[2] * wb[2]),
        shadowTint = p.shadowTint,
        highlightTint = p.highlightTint,
        grain = when (grain) {
            Level.OFF -> 0f
            Level.WEAK -> 0.085f
            Level.STRONG -> 0.17f
        },
        grainSize = if (grainLarge) 1.9f else 1.15f,
        vignette = vignette * 0.12f,
        halation = halation * 0.15f,
        strength = strength / 10f,
        sharpness = sharpness / 4f,
        clarity = clarity / 5f,
        chrome = colorChrome.amount,
        chromeBlue = chromeBlue.amount,
        dynamicRange = when (dynamicRange) {
            400 -> 2f
            200 -> 1f
            else -> 0f
        },
        highlight = highlight,
        shadow = shadow,
    )
}

/** Gain RGB de la balance des blancs "logicielle" : Kelvin + décalage R/B. */
fun Recipe.wbGain(): FloatArray {
    val g = if (wb == WhiteBalance.KELVIN) kelvinGain(kelvin) else floatArrayOf(1f, 1f, 1f)
    g[0] *= 1f + 0.022f * wbShiftR
    g[2] *= 1f + 0.022f * wbShiftB
    return g
}

/** Couleur approximative d'un corps noir (algorithme de Tanner Helland), 0..1. */
private fun kelvinRgb(k: Int): FloatArray {
    val t = k / 100.0
    val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
    val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
    val b = when {
        t >= 66 -> 255.0
        t <= 19 -> 1.0
        else -> 138.5177312231 * ln(t - 10) - 305.0447927307
    }
    return floatArrayOf(
        (r.coerceIn(1.0, 255.0) / 255).toFloat(),
        (g.coerceIn(1.0, 255.0) / 255).toFloat(),
        (b.coerceIn(1.0, 255.0) / 255).toFloat(),
    )
}

/**
 * Le téléphone est calé en lumière du jour (~5500 K). Régler 3200 K doit donc bleuir l'image,
 * régler 8000 K la réchauffer, comme sur un boîtier.
 */
private fun kelvinGain(k: Int): FloatArray {
    val ref = kelvinRgb(5500)
    val c = kelvinRgb(k)
    val r = ref[0] / c[0]
    val g = ref[1] / c[1]
    val b = ref[2] / c[2]
    return floatArrayOf(r / g, 1f, b / g)
}

object Recipes {
    private fun fromPreset(p: FilmPreset) = Recipe(
        id = "base_${p.id}",
        name = p.name,
        baseId = p.id,
        builtIn = true,
        grain = when {
            p.grain >= 0.15f -> Level.STRONG
            p.grain >= 0.05f -> Level.WEAK
            else -> Level.OFF
        },
        grainLarge = p.grainSize >= 1.5f,
        vignette = (p.vignette / 0.12f).roundToInt().coerceIn(0, 5),
        halation = (p.halation / 0.15f).roundToInt().coerceIn(0, 5),
    )

    /** Pellicules de base + quelques recettes inspirées de recettes Fuji populaires. */
    val builtIns: List<Recipe> = Presets.all.map { fromPreset(it) } + listOf(
        Recipe(
            id = "r_kodachrome", name = "Kodachrome 64", baseId = "classicchrome", builtIn = true,
            wb = WhiteBalance.DAYLIGHT, wbShiftR = 2, wbShiftB = -5,
            dynamicRange = 200, shadow = 0.5f, color = 2, sharpness = 1,
            grain = Level.WEAK, colorChrome = Level.STRONG,
        ),
        Recipe(
            id = "r_portra", name = "Portra (recette)", baseId = "classicchrome", builtIn = true,
            wb = WhiteBalance.KELVIN, kelvin = 5200, wbShiftR = 1, wbShiftB = -6,
            dynamicRange = 400, highlight = -1f, shadow = -1f, color = 2, sharpness = -2,
            grain = Level.STRONG, colorChrome = Level.STRONG, chromeBlue = Level.WEAK,
        ),
        Recipe(
            id = "r_70s", name = "Nostalgie 70s", baseId = "classicneg", builtIn = true,
            wbShiftR = 2, wbShiftB = -4, dynamicRange = 400, highlight = -1f, shadow = 1f,
            color = -1, sharpness = -2, clarity = -2, grain = Level.STRONG, grainLarge = true, vignette = 2,
        ),
        Recipe(
            id = "r_tungsten", name = "Nuit tungstène", baseId = "cinestill800t", builtIn = true,
            wb = WhiteBalance.KELVIN, kelvin = 3200, dynamicRange = 400, highlight = -1f,
            color = 1, sharpness = -1, grain = Level.STRONG, halation = 5,
        ),
    )
}

/** Sauvegarde des recettes perso (JSON dans les préférences). */
object RecipeStore {
    private const val PREFS = "recipes"
    private const val KEY_CUSTOM = "custom"
    private const val KEY_ACTIVE = "active"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadCustom(context: Context): List<Recipe> = try {
        val arr = JSONArray(prefs(context).getString(KEY_CUSTOM, "[]"))
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    fun saveCustom(context: Context, list: List<Recipe>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs(context).edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    fun activeId(context: Context): String? = prefs(context).getString(KEY_ACTIVE, null)

    fun setActiveId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    private fun toJson(r: Recipe) = JSONObject().apply {
        put("id", r.id)
        put("name", r.name)
        put("baseId", r.baseId)
        put("wb", r.wb.name)
        put("kelvin", r.kelvin)
        put("wbShiftR", r.wbShiftR)
        put("wbShiftB", r.wbShiftB)
        put("dynamicRange", r.dynamicRange)
        put("highlight", r.highlight.toDouble())
        put("shadow", r.shadow.toDouble())
        put("color", r.color)
        put("sharpness", r.sharpness)
        put("clarity", r.clarity)
        put("noiseReduction", r.noiseReduction)
        put("grain", r.grain.name)
        put("grainLarge", r.grainLarge)
        put("colorChrome", r.colorChrome.name)
        put("chromeBlue", r.chromeBlue.name)
        put("vignette", r.vignette)
        put("halation", r.halation)
        put("strength", r.strength)
    }

    private fun level(name: String) = Level.entries.firstOrNull { it.name == name } ?: Level.OFF

    private fun fromJson(o: JSONObject) = Recipe(
        id = o.getString("id"),
        name = o.optString("name", "Recette"),
        baseId = o.optString("baseId", "portra400"),
        builtIn = false,
        wb = WhiteBalance.entries.firstOrNull { it.name == o.optString("wb") } ?: WhiteBalance.AUTO,
        kelvin = o.optInt("kelvin", 5500),
        wbShiftR = o.optInt("wbShiftR", 0),
        wbShiftB = o.optInt("wbShiftB", 0),
        dynamicRange = o.optInt("dynamicRange", 100),
        highlight = o.optDouble("highlight", 0.0).toFloat(),
        shadow = o.optDouble("shadow", 0.0).toFloat(),
        color = o.optInt("color", 0),
        sharpness = o.optInt("sharpness", 0),
        clarity = o.optInt("clarity", 0),
        noiseReduction = o.optInt("noiseReduction", 0),
        grain = level(o.optString("grain")),
        grainLarge = o.optBoolean("grainLarge", false),
        colorChrome = level(o.optString("colorChrome")),
        chromeBlue = level(o.optString("chromeBlue")),
        vignette = o.optInt("vignette", 0),
        halation = o.optInt("halation", 0),
        strength = o.optInt("strength", 10),
    )
}
