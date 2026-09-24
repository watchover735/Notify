package com.notify.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.notify.core.model.AudioSource
import com.notify.core.model.Track
import com.notify.ui.artwork.LocalArtworkLoader
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.DarkSurfaceBorder
import com.notify.ui.theme.DarkSurfaceElevated
import com.notify.ui.theme.DarkSurfaceVariant
import com.notify.ui.theme.EmeraldAccent

/**
 * Renders album artwork with priority:
 * 1. Spotify imported track/album artwork or explicit artworkUri (via Coil)
 * 2. Selected YouTube candidate thumbnail
 * 3. Local MediaStore / SAF thumbnail (via LocalArtworkLoader)
 * 4. Tasteful gradient / music-note placeholder
 *
 * Invariant: Never calls MediaMetadataRetriever on remote audio streams.
 */
@Composable
fun AlbumArtwork(
    track: Track? = null,
    artworkUri: String? = track?.artworkUri,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    targetSizePx: Int = 512,
    contentDescription: String? = null
) {
    val effectiveUri = artworkUri ?: track?.artworkUri
    val isRemote = track?.source is AudioSource.Remote

    Box(
        modifier = modifier
            .clip(shape)
            .background(DarkSurface)
            .border(1.dp, DarkSurfaceBorder, shape),
        contentAlignment = Alignment.Center
    ) {
        if (!effectiveUri.isNullOrBlank()) {
            // Priority 1 & 2: Remote/Explicit Artwork URL loaded via Coil
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(effectiveUri)
                    .crossfade(true)
                    .size(targetSizePx, targetSizePx)
                    .build(),
                contentDescription = contentDescription ?: track?.title ?: "Album Artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    ArtworkPlaceholder()
                },
                error = {
                    ArtworkPlaceholder()
                }
            )
        } else if (track != null && !isRemote) {
            // Priority 3: Local Storage / MediaStore artwork (only for local tracks!)
            val context = LocalContext.current.applicationContext
            val artworkBitmap = produceState<Bitmap?>(initialValue = null, key1 = track.id.rawId) {
                value = LocalArtworkLoader.getInstance(context).loadArtwork(track, targetSizePx)
            }

            val bitmap = artworkBitmap.value
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = contentDescription ?: track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                ArtworkPlaceholder()
            }
        } else {
            // Priority 4: Generated placeholder (remote stream with no artwork or null track)
            ArtworkPlaceholder()
        }
    }
}

@Composable
private fun ArtworkPlaceholder() {
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
            imageVector = Icons.Default.MusicNote,
            contentDescription = "No Artwork",
            tint = EmeraldAccent.copy(alpha = 0.7f),
            modifier = Modifier.size(24.dp)
        )
    }
}
