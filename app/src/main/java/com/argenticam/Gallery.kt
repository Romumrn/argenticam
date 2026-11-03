package com.argenticam

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Taille de l'aperçu dans la visionneuse, et plafond de la pleine résolution (limite de texture GPU). */
private const val PREVIEW_PX = 2400
private const val FULL_PX = 4096

/** Miniature chargée en arrière-plan. */
@Composable
fun PhotoThumb(photo: Photo, px: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, photo.uri) {
        value = withContext(Dispatchers.IO) {
            PhotoLibrary.thumbnail(context, photo.uri, px)?.asImageBitmap()
        }
    }
    Box(modifier.background(Color(0xFF161616))) {
        bitmap?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private enum class GalleryTab(val label: String) { FILM("PELLICULE"), PRINTS("TIRAGES") }

private val SheetBg = Color(0xFF0B0B0B)
private val StripColor = Color(0xFF242424)
private val HoleColor = Color(0xFF0B0B0B)
private val EdgeText = Color(0xFF9A968E)

/** Nombre de vues par bande de la planche contact. */
private const val FRAMES_PER_STRIP = 3

@Composable
fun GalleryGrid(
    photos: List<Photo>,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    val classic = remember(photos) { photos.filter { it.isClassic } }
    val prints = remember(photos) { photos.filter { !it.isClassic } }
    var tab by remember {
        mutableStateOf(if (classic.isEmpty() && prints.isNotEmpty()) GalleryTab.PRINTS else GalleryTab.FILM)
    }
    val open: (Photo) -> Unit = { p -> onOpen(photos.indexOf(p).coerceAtLeast(0)) }

    Column(Modifier.fillMaxSize().background(SheetBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Color.White,
                modifier = Modifier.size(44.dp).clickable { onBack() }.padding(10.dp),
            )
            Column(Modifier.padding(start = 6.dp)) {
                Text("Galerie", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    (if (photos.size <= 1) "${photos.size} photo" else "${photos.size} photos") +
                        "  ·  ArgentiCam v${BuildConfig.VERSION_NAME}",
                    color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp,
                )
            }
        }

        // Deux catégories : la pellicule (photos classiques) et les tirages (Polaroid, bandes...)
        Row(
            Modifier
                .padding(horizontal = 14.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF161616))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GalleryTab.entries.forEach { t ->
                val n = if (t == GalleryTab.FILM) classic.size else prints.size
                val sel = t == tab
                Text(
                    "${t.label}  $n",
                    color = if (sel) Amber else Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (sel) Amber.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { tab = t }
                        .padding(vertical = 10.dp),
                )
            }
        }

        val shown = if (tab == GalleryTab.FILM) classic else prints
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (tab == GalleryTab.FILM) "Pas encore de photo sur la pellicule.\nVa en prendre une !"
                    else "Pas encore de tirage.\nEssaie le Polaroid, l'Instax ou le photomaton !",
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else if (tab == GalleryTab.FILM) {
            ContactSheet(classic, open)
        } else {
            PrintTable(prints, open)
        }
    }
}

/**
 * Planche contact : les photos classiques par rouleaux de 36 vues, en bandes de film
 * (perforations, nom de la pellicule sur le bord, numéro de vue sous chaque image).
 * Le rouleau le plus récent en haut ; dans un rouleau, les vues dans l'ordre de prise.
 */
@Composable
private fun ContactSheet(photos: List<Photo>, onOpen: (Photo) -> Unit) {
    // photos arrive du plus récent au plus ancien : on remet dans l'ordre de prise
    val rolls = remember(photos) { photos.reversed().chunked(RollCounter.ROLL_SIZE) }
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (r in rolls.indices.reversed()) {
            val roll = rolls[r]
            item(key = "roll_$r") {
                Text(
                    "ROULEAU ${r + 1}  ·  ${roll.size}/${RollCounter.ROLL_SIZE} VUES",
                    color = Amber.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
            val strips = roll.chunked(FRAMES_PER_STRIP)
            strips.forEachIndexed { si, strip ->
                val first = si * FRAMES_PER_STRIP + 1
                val end = si == strips.lastIndex && roll.size == RollCounter.ROLL_SIZE
                item(key = "strip_${r}_$si") { FilmStripRow(strip, first, end, onOpen) }
            }
        }
    }
}

@Composable
private fun FilmStripRow(frames: List<Photo>, firstNumber: Int, end: Boolean, onOpen: (Photo) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(StripColor)
            .border(androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333)))
            .padding(horizontal = 8.dp),
    ) {
        // bord du haut : nom de la pellicule au-dessus de chaque vue
        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
            for (i in 0 until FRAMES_PER_STRIP) {
                Text(
                    frames.getOrNull(i)?.film ?: "",
                    color = EdgeText, fontSize = 8.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1, letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f).padding(start = 18.dp),
                )
            }
            if (end) Text("END", color = EdgeText, fontSize = 8.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        Perforations()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until FRAMES_PER_STRIP) {
                val p = frames.getOrNull(i)
                Box(Modifier.weight(1f).aspectRatio(3f / 4f).background(Color(0xFF111111))) {
                    if (p != null) {
                        PhotoThumb(p, 360, Modifier.fillMaxSize().clickable { onOpen(p) })
                    }
                }
            }
        }
        Perforations()
        // bord du bas : numéros de vue
        Row(Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
            for (i in 0 until FRAMES_PER_STRIP) {
                Text(
                    if (i < frames.size) "${firstNumber + i}" else "",
                    color = EdgeText, fontSize = 9.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Rangée de perforations (les trous laissent voir le fond noir). */
@Composable
private fun Perforations() {
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val holeW = 7.dp.toPx()
        val holeH = 6.dp.toPx()
        val pitch = 13.dp.toPx()
        val y = (size.height - holeH) / 2f
        var x = 2.dp.toPx()
        val r = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx())
        while (x + holeW <= size.width) {
            drawRoundRect(HoleColor, topLeft = androidx.compose.ui.geometry.Offset(x, y), size = androidx.compose.ui.geometry.Size(holeW, holeH), cornerRadius = r)
            x += pitch
        }
    }
}

/**
 * Les tirages (Polaroid, Instax, photomaton, bandes 35 mm, 6x6, demi-format), chacun à ses vraies
 * proportions, posés en vrac sur deux colonnes, légèrement de travers comme sur une table.
 */
@Composable
private fun PrintTable(prints: List<Photo>, onOpen: (Photo) -> Unit) {
    androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid(
        columns = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalItemSpacing = 22.dp,
    ) {
        items(prints.size, key = { prints[it].id }) { i ->
            val p = prints[i]
            val tilt = ((p.id % 7) - 3) * 0.7f
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .graphicsLayer { rotationZ = tilt }
                        .shadow(8.dp)
                        .fillMaxWidth()
                        .aspectRatio(p.aspect.coerceIn(0.15f, 3f))
                        .clickable { onOpen(p) },
                ) {
                    PhotoThumb(p, 600, Modifier.fillMaxSize())
                }
                Text(
                    p.format.label + if (p.film.isNotBlank()) "  ·  ${p.film.lowercase().replaceFirstChar { it.titlecase() }}" else "",
                    color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, maxLines = 1,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
fun PhotoViewer(
    photos: List<Photo>,
    startIndex: Int,
    onBack: () -> Unit,
    onDelete: (Photo) -> Unit,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) {
        photos.size
    }
    var zoomed by remember { mutableStateOf(false) }
    // en changeant de photo, la nouvelle n'est pas zoomée
    LaunchedEffect(pagerState.currentPage) { zoomed = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            key = { photos.getOrNull(it)?.id ?: it },
            modifier = Modifier.fillMaxSize(),
            // pendant un zoom, glisser déplace l'image au lieu de changer de photo
            userScrollEnabled = !zoomed,
        ) { page ->
            val photo = photos.getOrNull(page)
            if (photo != null) {
                // Aperçu léger pour l'affichage normal...
                val preview: ImageBitmap? by produceState<ImageBitmap?>(null, photo.uri) {
                    value = withContext(Dispatchers.IO) {
                        PhotoLibrary.decode(context, photo.uri, PREVIEW_PX)?.asImageBitmap()
                    }
                }
                // ...et pleine résolution chargée seulement au premier zoom (libérée en quittant la photo).
                var wantFull by remember(photo.id) { mutableStateOf(false) }
                val full: ImageBitmap? by produceState<ImageBitmap?>(null, photo.uri, wantFull) {
                    value = if (wantFull) {
                        withContext(Dispatchers.IO) { PhotoLibrary.decode(context, photo.uri, FULL_PX)?.asImageBitmap() }
                    } else {
                        null
                    }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    (full ?: preview)?.let {
                        ZoomableImage(
                            it,
                            modifier = Modifier.fillMaxSize(),
                            onZoomChanged = { z ->
                                if (page == pagerState.currentPage) zoomed = z
                                if (z) wantFull = true
                            },
                        )
                    }
                    if (wantFull && full == null) {
                        Text(
                            "Chargement HD…",
                            color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 28.dp)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        // Barre du haut
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Color.White,
                modifier = Modifier.size(44.dp).clickable { onBack() }.padding(10.dp),
            )
            Text(
                "${pagerState.currentPage + 1} / ${photos.size}",
                color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            Icon(
                Icons.Default.Share, contentDescription = "Partager", tint = Color.White,
                modifier = Modifier.size(44.dp).clickable {
                    photos.getOrNull(pagerState.currentPage)?.let { p ->
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, p.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Partager la photo"))
                    }
                }.padding(10.dp),
            )
            Icon(
                Icons.Default.Delete, contentDescription = "Supprimer", tint = Color.White,
                modifier = Modifier.size(44.dp).clickable {
                    photos.getOrNull(pagerState.currentPage)?.let(onDelete)
                }.padding(10.dp),
            )
        }
    }
}
