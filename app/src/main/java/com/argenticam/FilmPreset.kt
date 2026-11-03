package com.argenticam

/**
 * Un "look" argentique, décrit par quelques paramètres que le shader applique.
 *
 * Toutes les couleurs sont dans [0,1], les teintes (tint) sont ajoutées après correction.
 */
data class FilmPreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val bw: Boolean = false,
    /** Exposition en IL (stops). */
    val exposure: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    /** Noirs relevés (look "mat"). 0..0.15 */
    val fade: Float = 0f,
    /** Balance des blancs : gain RGB. */
    val gain: FloatArray = floatArrayOf(1f, 1f, 1f),
    val shadowTint: FloatArray = floatArrayOf(0f, 0f, 0f),
    val highlightTint: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** Intensité du grain 0..0.4 */
    val grain: Float = 0f,
    /** Taille du grain (1 = fin, 3 = gros). */
    val grainSize: Float = 1.2f,
    val vignette: Float = 0f,
    /** Halo rouge autour des hautes lumières (CineStill). 0..1 */
    val halation: Float = 0f,
    /** LUT 3D (assets/luts/<lut>.png) : simulation mesurée, appliquée avant les réglages. */
    val lut: String? = null,
    /** Simulation Fujifilm (affichée à part dans la liste). */
    val fuji: Boolean = false,
    /** Film instantané : seul proposé en mode Polaroid. */
    val instant: Boolean = false,
)

object Presets {
    private fun v(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

    /** Anciens identifiants (versions précédentes de l'appli) -> nouveaux. */
    private val aliases = mapOf(
        "classicchrome" to "fuji_classicchrome",
        "classicneg" to "fuji_classicneg",
        "velvia50" to "fuji_velvia",
        "acros" to "fuji_acros",
    )

    fun byId(id: String): FilmPreset {
        val real = aliases[id] ?: id
        return all.firstOrNull { it.id == real } ?: all[1]
    }

    private fun fuji(id: String, name: String, subtitle: String) =
        FilmPreset(id = "fuji_$id", name = name, subtitle = subtitle, lut = "fuji_$id", fuji = true)

    val original = FilmPreset(
        id = "original", name = "Original", subtitle = "sans filtre",
    )

    val all: List<FilmPreset> = listOf(
        original,
        // --- Simulations Fujifilm (LUT mesurées, voir assets/luts/LICENSE.md) ---
        fuji("provia", "Provia", "Fuji · standard"),
        fuji("velvia", "Velvia", "Fuji · éclatant"),
        fuji("astia", "Astia", "Fuji · doux"),
        fuji("classicchrome", "Classic Chrome", "Fuji · doc, désaturé"),
        fuji("realaace", "Reala Ace", "Fuji · naturel, fidèle"),
        fuji("pronehi", "Pro Neg Hi", "Fuji · portrait contrasté"),
        fuji("pronegstd", "Pro Neg Std", "Fuji · portrait doux"),
        fuji("classicneg", "Classic Neg", "Fuji · contraste, vert-magenta"),
        fuji("nostalgicneg", "Nostalgic Neg", "Fuji · ambré, années 70"),
        fuji("eterna", "Eterna", "Fuji · cinéma, plat"),
        fuji("bleachbypass", "Bleach Bypass", "Fuji · sans blanchiment"),
        FilmPreset(
            id = "fuji_acros", name = "Acros", subtitle = "Fuji · N&B fin et dense", fuji = true,
            bw = true, contrast = 1.14f, exposure = -0.05f,
            grain = 0.07f, grainSize = 1.0f,
        ),
        FilmPreset(
            id = "fuji_monochrome", name = "Monochrome", subtitle = "Fuji · N&B neutre", fuji = true,
            bw = true,
        ),
        // --- Pellicules ---
        FilmPreset(
            id = "polaroid600", name = "Polaroid 600", subtitle = "instantané · doux, cyan", instant = true,
            exposure = 0.12f, contrast = 0.88f, saturation = 0.86f, fade = 0.07f,
            gain = v(1.03f, 1.0f, 0.95f),
            shadowTint = v(-0.012f, 0.02f, 0.03f),
            highlightTint = v(0.03f, 0.018f, -0.012f),
            grain = 0.06f, grainSize = 1.3f, vignette = 0.32f,
        ),
        FilmPreset(
            id = "sx70", name = "SX-70", subtitle = "instantané · chaud, délavé", instant = true,
            exposure = 0.15f, contrast = 0.84f, saturation = 0.78f, fade = 0.1f,
            gain = v(1.08f, 1.0f, 0.87f),
            shadowTint = v(0.01f, 0.012f, 0.02f),
            highlightTint = v(0.035f, 0.02f, -0.015f),
            grain = 0.07f, grainSize = 1.4f, vignette = 0.4f,
        ),
        FilmPreset(
            id = "itype", name = "i-Type", subtitle = "instantané · net, plus contrasté", instant = true,
            exposure = 0.05f, contrast = 0.98f, saturation = 0.95f, fade = 0.05f,
            gain = v(1.02f, 1.0f, 0.97f),
            shadowTint = v(-0.008f, 0.012f, 0.022f),
            highlightTint = v(0.02f, 0.012f, -0.008f),
            grain = 0.05f, grainSize = 1.2f, vignette = 0.25f,
        ),
        FilmPreset(
            id = "polaroidbw", name = "Polaroid N&B", subtitle = "instantané · noir et blanc doux", instant = true,
            bw = true, exposure = 0.1f, contrast = 0.92f, fade = 0.08f,
            shadowTint = v(0.01f, 0.006f, 0.0f),
            highlightTint = v(0.015f, 0.01f, 0.0f),
            grain = 0.06f, grainSize = 1.3f, vignette = 0.35f,
        ),
        FilmPreset(
            id = "expired", name = "Polaroid périmé", subtitle = "instantané · dérive magenta, délavé", instant = true,
            exposure = 0.2f, contrast = 0.8f, saturation = 0.7f, fade = 0.14f,
            gain = v(1.1f, 0.98f, 0.82f),
            shadowTint = v(0.03f, -0.01f, 0.04f),
            highlightTint = v(0.04f, 0.03f, -0.02f),
            grain = 0.1f, grainSize = 1.5f, vignette = 0.55f,
        ),
        FilmPreset(
            id = "instax", name = "Instax", subtitle = "instantané · net, saturé", instant = true,
            exposure = 0.05f, contrast = 1.08f, saturation = 1.1f, fade = 0.03f,
            gain = v(0.99f, 1.0f, 1.02f),
            shadowTint = v(-0.01f, 0.005f, 0.015f),
            highlightTint = v(0.01f, 0.01f, 0.0f),
            grain = 0.04f, grainSize = 1.1f, vignette = 0.2f,
        ),
        FilmPreset(
            id = "portra400", name = "Portra 400", subtitle = "ISO 400 · portrait",
            contrast = 0.95f, saturation = 0.92f, fade = 0.025f, exposure = 0.1f,
            gain = v(1.03f, 1.0f, 0.96f),
            shadowTint = v(-0.004f, 0.008f, 0.012f),
            highlightTint = v(0.02f, 0.012f, -0.012f),
            grain = 0.09f, grainSize = 1.2f, halation = 0.08f,
        ),
        FilmPreset(
            id = "gold200", name = "Gold 200", subtitle = "ISO 200 · été",
            contrast = 1.06f, saturation = 1.18f, fade = 0.015f,
            gain = v(1.07f, 1.01f, 0.86f),
            shadowTint = v(0.012f, 0.004f, -0.01f),
            highlightTint = v(0.03f, 0.02f, -0.02f),
            grain = 0.12f, grainSize = 1.3f, halation = 0.1f, vignette = 0.12f,
        ),
        FilmPreset(
            id = "superia400", name = "Superia 400", subtitle = "ISO 400 · rue",
            contrast = 1.1f, saturation = 1.1f, fade = 0.02f,
            gain = v(0.98f, 1.03f, 1.0f),
            shadowTint = v(-0.01f, 0.018f, 0.012f),
            highlightTint = v(0.01f, 0.012f, 0.0f),
            grain = 0.16f, grainSize = 1.5f, vignette = 0.15f,
        ),
        FilmPreset(
            id = "trix400", name = "Tri-X 400", subtitle = "N&B · grain dur",
            bw = true, contrast = 1.32f, fade = 0.03f, exposure = 0.05f,
            grain = 0.26f, grainSize = 1.8f, vignette = 0.25f,
        ),
        FilmPreset(
            id = "cinestill800t", name = "CineStill 800T", subtitle = "tungstène · halation",
            contrast = 1.05f, saturation = 1.05f, fade = 0.02f,
            gain = v(0.9f, 1.0f, 1.12f),
            shadowTint = v(-0.01f, 0.004f, 0.025f),
            highlightTint = v(0.02f, 0.0f, 0.0f),
            grain = 0.17f, grainSize = 1.5f, halation = 0.75f, vignette = 0.1f,
        ),
        FilmPreset(
            id = "ektachrome", name = "Ektachrome", subtitle = "ISO 100 · diapo froide",
            contrast = 1.12f, saturation = 1.22f,
            gain = v(0.97f, 1.0f, 1.05f),
            highlightTint = v(-0.005f, 0.0f, 0.02f),
            grain = 0.04f, grainSize = 1.0f,
        ),
        FilmPreset(
            id = "lomo", name = "Lomo", subtitle = "vignette · saturé",
            contrast = 1.32f, saturation = 1.4f, fade = 0.02f,
            gain = v(1.02f, 1.0f, 0.95f),
            shadowTint = v(0.0f, 0.01f, 0.025f),
            grain = 0.14f, grainSize = 1.6f, vignette = 0.65f,
        ),
    )
}
