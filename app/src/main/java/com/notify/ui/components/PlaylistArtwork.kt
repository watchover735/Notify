package com.notify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.DarkSurfaceBorder
import com.notify.ui.theme.DarkSurfaceElevated
import com.notify.ui.theme.DarkSurfaceVariant
import com.notify.ui.theme.EmeraldAccent

/**
 * Renders playlist artwork following strict priority:
 * 1. Explicit artworkUrl
 * 2. Explicit artworkUri
 * 3. First track artworkUrl / artworkUri
 * 4. Optional four-track mosaic (if 4 valid URLs provided)
 * 5. Generated gradient placeholder with music-library icon
 */
@Composable
fun PlaylistArtwork(
    artworkUrl: String? = null,
    artworkUri: String? = null,
    firstTrackArtworkUrl: String? = null,
    mosaicTrackArtworkUrls: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    targetSizePx: Int = 512,
    contentDescription: String? = null
) {
    val effectiveUrl = artworkUrl?.takeIf { it.isNotBlank() }
        ?: artworkUri?.takeIf { it.isNotBlank() }
        ?: firstTrackArtworkUrl?.takeIf { it.isNotBlank() }

    val validMosaic = mosaicTrackArtworkUrls.filter { it.isNotBlank() }.take(4)

    Box(
        modifier = modifier
            .clip(shape)
            .background(DarkSurface)
            .border(1.dp, DarkSurfaceBorder, shape),
        contentAlignment = Alignment.Center
    ) {
        when {
            effectiveUrl != null -> {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(effectiveUrl)
                        .crossfade(true)
                        .size(targetSizePx, targetSizePx)
                        .build(),
                    contentDescription = contentDescription ?: "Playlist Artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = { PlaylistArtworkPlaceholder() },
                    error = { PlaylistArtworkPlaceholder() }
                )
            }
            validMosaic.size == 4 -> {
                // 2x2 mosaic
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f)) {
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(validMosaic[0])
                                .crossfade(true)
                                .size(targetSizePx / 2, targetSizePx / 2)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            loading = { PlaylistArtworkPlaceholder() },
                            error = { PlaylistArtworkPlaceholder() }
                        )
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(validMosaic[1])
                                .crossfade(true)
                                .size(targetSizePx / 2, targetSizePx / 2)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            loading = { PlaylistArtworkPlaceholder() },
                            error = { PlaylistArtworkPlaceholder() }
                        )
                    }
                    Row(modifier = Modifier.weight(1f)) {
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(validMosaic[2])
                                .crossfade(true)
                                .size(targetSizePx / 2, targetSizePx / 2)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            loading = { PlaylistArtworkPlaceholder() },
                            error = { PlaylistArtworkPlaceholder() }
                        )
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(validMosaic[3])
                                .crossfade(true)
                                .size(targetSizePx / 2, targetSizePx / 2)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            loading = { PlaylistArtworkPlaceholder() },
                            error = { PlaylistArtworkPlaceholder() }
                        )
                    }
                }
            }
            else -> {
                PlaylistArtworkPlaceholder()
            }
        }
    }
}

@Composable
fun PlaylistArtworkPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(DarkSurfaceVariant, DarkSurfaceElevated)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.LibraryMusic,
            contentDescription = "No Playlist Artwork",
            tint = EmeraldAccent.copy(alpha = 0.7f),
            modifier = Modifier.size(32.dp)
        )
    }
}
