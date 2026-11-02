package com.filmcam

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
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

@Composable
fun GalleryGrid(
    photos: List<Photo>,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0B))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Color.White,
                modifier = Modifier.size(44.dp).clickable { onBack() }.padding(10.dp),
            )
            Column(Modifier.padding(start = 6.dp)) {
                Text("Pellicule", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (photos.size <= 1) "${photos.size} photo" else "${photos.size} photos",
                    color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp,
                )
            }
        }
        if (photos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucune photo pour l'instant.\nVa en prendre une !", color = Color.White.copy(alpha = 0.5f))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(photos, key = { _, p -> p.id }) { index, photo ->
                    PhotoThumb(
                        photo, 360,
                        Modifier.aspectRatio(1f).clickable { onOpen(index) },
                    )
                }
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            key = { photos.getOrNull(it)?.id ?: it },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val photo = photos.getOrNull(page)
            if (photo != null) {
                val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, photo.uri) {
                    value = withContext(Dispatchers.IO) {
                        PhotoLibrary.decode(context, photo.uri, 2400)?.asImageBitmap()
                    }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    bitmap?.let {
                        Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
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
