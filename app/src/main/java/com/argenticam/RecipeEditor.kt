package com.argenticam

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Panel = Color(0xFF111111)
private val Field = Color(0xFF1A1A1A)

/** Couleur d'accent de chaque pellicule de base. */
fun filmAccent(id: String): Color = when (id) {
    "portra400" -> Color(0xFFF2B8A0)
    "gold200" -> Color(0xFFF5C542)
    "superia400" -> Color(0xFF6FCF97)
    "velvia50" -> Color(0xFFEB5757)
    "classicchrome" -> Color(0xFF8FA3B8)
    "classicneg" -> Color(0xFF9BC4A5)
    "acros", "trix400" -> Color(0xFFD0D0D0)
    "cinestill800t" -> Color(0xFF5AA9FF)
    "ektachrome" -> Color(0xFF56CCF2)
    "lomo" -> Color(0xFFBB6BD9)
    "polaroid600" -> Color(0xFF7FD1C7)
    "sx70" -> Color(0xFFE9B872)
    "itype" -> Color(0xFF9AD0F5)
    "polaroidbw" -> Color(0xFFBDBDBD)
    "expired" -> Color(0xFFE48FB1)
    "instax" -> Color(0xFF8BE0A4)
    "disposable400" -> Color(0xFFFFB347)
    "disposable800" -> Color(0xFFB0E57C)
    "disposablebw" -> Color(0xFFCFCFCF)
    "boothbw" -> Color(0xFFE0E0E0)
    "boothcolor" -> Color(0xFFE8B98A)
    else -> Color.White
}

private fun signed(v: Int) = if (v > 0) "+$v" else v.toString()
private fun signedHalf(v: Float): String {
    val txt = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
    return if (v > 0f) "+$txt" else txt
}

/**
 * Éditeur de recette, façon menu "Réglages image" d'un Fuji. Chaque changement s'applique
 * en direct dans le viseur.
 */
@Composable
fun RecipeEditor(
    recipe: Recipe,
    modified: Boolean,
    onChange: (Recipe) -> Unit,
    onSave: () -> Unit,
    onDuplicate: () -> Unit,
    onReset: () -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Pellicules de base proposées (ex. seulement les instantanées en mode Polaroid). */
    baseFilter: (FilmPreset) -> Boolean = { true },
) {
    val r = recipe
    Column(modifier.background(Panel)) {
        // En-tête : nom + fermer
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = r.name,
                onValueChange = { onChange(r.copy(name = it.take(32))) },
                singleLine = true,
                label = { Text(if (r.builtIn) "Recette (modèle)" else "Ma recette") },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Amber,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                    focusedLabelColor = Amber,
                    unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                    cursorColor = Amber,
                ),
            )
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Close, contentDescription = "Fermer", tint = Color.White)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            Section("PELLICULE DE BASE")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Presets.all.filter(baseFilter).groupBy { it.category }.forEach { (cat, films) ->
                item(key = "bcat_$cat") {
                    Text(
                        cat, color = Amber.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 6.dp, end = 2.dp),
                    )
                }
                items(films, key = { it.id }) { p ->
                    val sel = p.id == r.baseId
                    val accent = filmAccent(p.id)
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Field)
                            .border(
                                BorderStroke(1.5.dp, if (sel) accent else Color.White.copy(alpha = 0.08f)),
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { onChange(r.copy(baseId = p.id)) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                        Spacer(Modifier.width(8.dp))
                        Text(p.name, color = if (sel) accent else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                }
            }

            if (r.base().lut != null) {
                Text(
                    "Simulation mesurée : LUT de abpy/FujifilmCameraProfiles (CC BY-NC-SA 4.0)",
                    color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                )
            }

            if (r.base().bw) {
                Section("NOIR ET BLANC")
                SegRow("Filtre", BwFilter.entries, r.bwFilter, { it.label }) { onChange(r.copy(bwFilter = it)) }
                Stepper("Teinte chaud / froid", signed(r.monoWarm), r.monoWarm > -9, r.monoWarm < 9,
                    onMinus = { onChange(r.copy(monoWarm = r.monoWarm - 1)) },
                    onPlus = { onChange(r.copy(monoWarm = r.monoWarm + 1)) },
                )
                Stepper("Teinte magenta / vert", signed(r.monoMagenta), r.monoMagenta > -9, r.monoMagenta < 9,
                    onMinus = { onChange(r.copy(monoMagenta = r.monoMagenta - 1)) },
                    onPlus = { onChange(r.copy(monoMagenta = r.monoMagenta + 1)) },
                )
            }

            Section("GRAIN · COULEUR")
            SegRow("Effet grain", Level.entries, r.grain, { it.label }) { onChange(r.copy(grain = it)) }
            SegRow("Taille du grain", listOf(false, true), r.grainLarge, { if (it) "Gros" else "Petit" }) {
                onChange(r.copy(grainLarge = it))
            }
            SegRow("Color Chrome", Level.entries, r.colorChrome, { it.label }) { onChange(r.copy(colorChrome = it)) }
            SegRow("FX Bleu", Level.entries, r.chromeBlue, { it.label }) { onChange(r.copy(chromeBlue = it)) }

            Section("BALANCE DES BLANCS")
            SegRow("Mode", WhiteBalance.entries, r.wb, { if (it == WhiteBalance.KELVIN) "K" else "Auto" }) {
                onChange(r.copy(wb = it))
            }
            if (r.wb == WhiteBalance.KELVIN) {
                Stepper("Température", "${r.kelvin}K", r.kelvin > KELVIN_MIN, r.kelvin < KELVIN_MAX,
                    onMinus = { onChange(r.copy(kelvin = (r.kelvin - KELVIN_STEP).coerceAtLeast(KELVIN_MIN))) },
                    onPlus = { onChange(r.copy(kelvin = (r.kelvin + KELVIN_STEP).coerceAtMost(KELVIN_MAX))) },
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(KelvinPresets) { (name, k) ->
                        SegChip("$name ${k}K", r.kelvin == k) { onChange(r.copy(kelvin = k)) }
                    }
                }
            }
            Stepper("Décalage rouge", signed(r.wbShiftR), r.wbShiftR > -9, r.wbShiftR < 9,
                onMinus = { onChange(r.copy(wbShiftR = r.wbShiftR - 1)) },
                onPlus = { onChange(r.copy(wbShiftR = r.wbShiftR + 1)) },
            )
            Stepper("Décalage bleu", signed(r.wbShiftB), r.wbShiftB > -9, r.wbShiftB < 9,
                onMinus = { onChange(r.copy(wbShiftB = r.wbShiftB - 1)) },
                onPlus = { onChange(r.copy(wbShiftB = r.wbShiftB + 1)) },
            )

            Section("TONALITÉ")
            SegRow("Plage dynamique", listOf(100, 200, 400), r.dynamicRange, { "DR$it" }) {
                onChange(r.copy(dynamicRange = it))
            }
            Stepper("Hautes lumières", signedHalf(r.highlight), r.highlight > -2f, r.highlight < 4f,
                onMinus = { onChange(r.copy(highlight = r.highlight - 0.5f)) },
                onPlus = { onChange(r.copy(highlight = r.highlight + 0.5f)) },
            )
            Stepper("Ombres", signedHalf(r.shadow), r.shadow > -2f, r.shadow < 4f,
                onMinus = { onChange(r.copy(shadow = r.shadow - 0.5f)) },
                onPlus = { onChange(r.copy(shadow = r.shadow + 0.5f)) },
            )
            Stepper("Couleur", signed(r.color), r.color > -4, r.color < 4,
                onMinus = { onChange(r.copy(color = r.color - 1)) },
                onPlus = { onChange(r.copy(color = r.color + 1)) },
            )
            Stepper("Netteté", signed(r.sharpness), r.sharpness > -4, r.sharpness < 4,
                onMinus = { onChange(r.copy(sharpness = r.sharpness - 1)) },
                onPlus = { onChange(r.copy(sharpness = r.sharpness + 1)) },
            )
            Stepper("Clarté", signed(r.clarity), r.clarity > -5, r.clarity < 5,
                onMinus = { onChange(r.copy(clarity = r.clarity - 1)) },
                onPlus = { onChange(r.copy(clarity = r.clarity + 1)) },
            )
            Stepper("Réduction du bruit", signed(r.noiseReduction), r.noiseReduction > -4, r.noiseReduction < 4,
                onMinus = { onChange(r.copy(noiseReduction = r.noiseReduction - 1)) },
                onPlus = { onChange(r.copy(noiseReduction = r.noiseReduction + 1)) },
            )

            Section("EFFETS")
            Stepper("Vignettage", r.vignette.toString(), r.vignette > 0, r.vignette < 5,
                onMinus = { onChange(r.copy(vignette = r.vignette - 1)) },
                onPlus = { onChange(r.copy(vignette = r.vignette + 1)) },
            )
            Stepper("Halation", r.halation.toString(), r.halation > 0, r.halation < 5,
                onMinus = { onChange(r.copy(halation = r.halation - 1)) },
                onPlus = { onChange(r.copy(halation = r.halation + 1)) },
            )
            Stepper("Intensité du film", "${r.strength * 10}%", r.strength > 0, r.strength < 10,
                onMinus = { onChange(r.copy(strength = r.strength - 1)) },
                onPlus = { onChange(r.copy(strength = r.strength + 1)) },
            )
        }

        // Actions
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B0B0B))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (modified) ActionButton("Annuler", primary = false, onClick = onReset)
            if (onDelete != null) ActionButton("Supprimer", primary = false, danger = true, onClick = onDelete)
            Spacer(Modifier.weight(1f))
            ActionButton("Dupliquer", primary = false, onClick = onDuplicate)
            ActionButton(
                if (r.builtIn) "Enregistrer comme perso" else "Enregistrer",
                primary = true,
                enabled = modified || r.builtIn,
                onClick = onSave,
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        color = Amber.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SegChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Text(
        text,
        color = if (selected) Amber else Color.White.copy(alpha = 0.85f),
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Amber.copy(alpha = 0.16f) else Field)
            .border(
                BorderStroke(1.dp, if (selected) Amber else Color.White.copy(alpha = 0.08f)),
                RoundedCornerShape(10.dp),
            )
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun <T> SegRow(label: String, values: List<T>, selected: T, text: (T) -> String, onPick: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { v -> SegChip(text(v), v == selected) { onPick(v) } }
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    canMinus: Boolean,
    canPlus: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        StepButton("−", canMinus) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onMinus()
        }
        Text(
            value,
            color = Amber, fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.width(72.dp),
        )
        StepButton("+", canPlus) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onPlus()
        }
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Field)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = if (enabled) 0.18f else 0.05f)), CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White.copy(alpha = if (enabled) 1f else 0.25f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ActionButton(
    text: String,
    primary: Boolean,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val fg = when {
        !enabled -> Color.White.copy(alpha = 0.3f)
        primary -> Color.Black
        danger -> Color(0xFFFF8A80)
        else -> Color.White
    }
    Text(
        text,
        color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary && enabled) Amber else Field)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
