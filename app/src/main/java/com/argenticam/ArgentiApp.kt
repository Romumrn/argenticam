package com.argenticam

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { Camera, Grid, Viewer }

@Composable
fun ArgentiApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var photos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var shots by remember { mutableIntStateOf(RollCounter.get(context)) }
    var screen by remember { mutableStateOf(Screen.Camera) }
    var viewerIndex by remember { mutableIntStateOf(0) }

    fun reload() {
        scope.launch { photos = withContext(Dispatchers.IO) { PhotoLibrary.load(context) } }
    }
    LaunchedEffect(Unit) { reload() }

    BackHandler(enabled = screen != Screen.Camera) {
        screen = if (screen == Screen.Viewer) Screen.Grid else Screen.Camera
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraScreen(
            active = screen == Screen.Camera,
            lastPhoto = photos.firstOrNull(),
            shots = shots,
            onSaved = {
                shots = RollCounter.increment(context)
                reload()
            },
            onOpenGallery = { screen = Screen.Grid },
        )

        when (screen) {
            Screen.Grid -> GalleryGrid(
                photos = photos,
                onBack = { screen = Screen.Camera },
                onOpen = { viewerIndex = it; screen = Screen.Viewer },
            )
            Screen.Viewer -> PhotoViewer(
                photos = photos,
                startIndex = viewerIndex,
                onBack = { screen = Screen.Grid },
                onDelete = { photo ->
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            try {
                                context.contentResolver.delete(photo.uri, null, null) > 0
                            } catch (_: Exception) {
                                false
                            }
                        }
                        if (deleted) {
                            photos = photos.filter { it.id != photo.id }
                            if (photos.isEmpty()) screen = Screen.Grid
                        } else {
                            Toast.makeText(context, "Impossible de supprimer cette photo", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
            Screen.Camera -> Unit
        }
    }
}
