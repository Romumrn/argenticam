package com.argenticam

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Vignettes des recettes : une petite scène de référence (assets/sample.jpg) développée avec
 * la recette, par le même shader que l'appareil. Les instantanés sont montrés dans leur cadre.
 */
object RecipeThumbs {
    /** Un seul fil pour le GPU hors écran : les vignettes se calculent l'une après l'autre. */
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val cache = ConcurrentHashMap<Recipe, ImageBitmap>()

    /** Clé = uniquement ce qui change le rendu (pas l'id ni le nom). */
    private fun key(r: Recipe) = r.copy(id = "", name = "", builtIn = false)

    fun cached(r: Recipe): ImageBitmap? = cache[key(r)]

    suspend fun render(r: Recipe): ImageBitmap? {
        cache[key(r)]?.let { return it }
        return withContext(dispatcher) {
            cache[key(r)] ?: try {
                val sample = LutCache.asset("sample.jpg") ?: return@withContext null
                val developed = FilmProcessor.process(sample, r.render())
                val base = r.base()
                val result: Bitmap = if (base.instant) {
                    val frame = if (base.id == "instax") InstaxMiniFrame else PolaroidFrame
                    val cropped = frame.crop(developed)
                    val print = frame.compose(cropped, EdgeInfo(base.name.uppercase(), 1))
                    if (cropped !== developed) cropped.recycle()
                    developed.recycle()
                    print
                } else {
                    developed
                }
                result.asImageBitmap().also { cache[key(r)] = it }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Petite vignette d'une recette : la scène de référence avec ce rendu, le tirage instantané
 * pour les Polaroid / Instax, une icône de bande de photos pour le photomaton.
 */
@Composable
fun RecipeThumb(recipe: Recipe, width: Dp = 40.dp, height: Dp = 52.dp, modifier: Modifier = Modifier) {
    if (recipe.base().booth) {
        BoothIcon(modifier.size(width, height))
        return
    }
    val bmp: ImageBitmap? by produceState(RecipeThumbs.cached(recipe), recipe) {
        value = RecipeThumbs.render(recipe)
    }
    Box(
        modifier.size(width, height).clip(RoundedCornerShape(5.dp)).background(Color(0xFF222222)),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            Image(
                it, contentDescription = null,
                contentScale = if (recipe.base().instant) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Icône "bande de photomaton" : papier blanc, trois petites photos. */
@Composable
private fun BoothIcon(modifier: Modifier) {
    Canvas(modifier) {
        val stripW = size.height * 0.42f
        val left = (size.width - stripW) / 2f
        drawRoundRect(Color(0xFFF2F2EE), Offset(left, 0f), Size(stripW, size.height), CornerRadius(2.dp.toPx()))
        val m = stripW * 0.14f
        val fh = (size.height - m * 4) / 3f
        for (i in 0 until 3) {
            drawRect(
                Color(0xFF3A3A3A),
                topLeft = Offset(left + m, m + i * (fh + m)),
                size = Size(stripW - 2 * m, fh),
            )
        }
    }
}
