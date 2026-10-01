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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.notify.core.playback.ArtworkResolution
import com.notify.ui.artwork.LocalArtworkLoader
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.DarkSurfaceBorder
import com.notify.ui.theme.DarkSurfaceElevated
import com.notify.ui.theme.DarkSurfaceVariant
import com.notify.ui.theme.EmeraldAccent

/**
 * Renders album artwork with priority:
 * 1. High-resolution provider artwork (via ArtworkResolution and Coil fallback chain)
 * 2. Local MediaStore / SAF thumbnail (via LocalArtworkLoader)
 * 3. Tasteful gradient / music-note placeholder
 *
 * Invariant: Never calls MediaMetadataRetriever on remote audio streams.
 */
@Composable
fun AlbumArtwork(
    track: Track? = null,
    trackId: String? = track?.id?.rawId,
    artworkUri: String? = track?.artworkUri,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    targetSizePx: Int = 512,
    contentDescription: String? = null
) {
    val rawUri = artworkUri ?: track?.artworkUri
    val context = LocalContext.current
    val effectiveTrackId = trackId ?: track?.id?.rawId
    val localCachedUri = remember(effectiveTrackId, rawUri) {
        val id = effectiveTrackId ?: if (rawUri != null && !rawUri.startsWith("http")) rawUri else null
        if (id != null) {
            com.notify.core.playback.LocalArtworkStore.getArtworkUri(id, context)?.toString()
        } else null
    }
    val effectiveUri = localCachedUri ?: rawUri
    val isRemote = track?.source is AudioSource.Remote && localCachedUri == null

    Box(
        modifier = modifier
            .clip(shape)
            .background(DarkSurface)
            .border(1.dp, DarkSurfaceBorder, shape),
        contentAlignment = Alignment.Center
    ) {
        if (!effectiveUri.isNullOrBlank()) {
            val candidates = remember(effectiveUri, targetSizePx) {
                ArtworkResolution.highResArtworkCandidates(effectiveUri, targetSizePx)
            }
            var candidateIndex by remember(candidates) { mutableStateOf(0) }
            val currentUrl = candidates.getOrNull(candidateIndex) ?: effectiveUri

            val context = LocalContext.current
            val imageRequest = remember(currentUrl, targetSizePx) {
                ImageRequest.Builder(context)
                    .data(currentUrl)
                    .crossfade(true)
                    .apply {
                        if (targetSizePx >= 800) {
                            size(coil.size.Size.ORIGINAL)
                        } else {
                            size(targetSizePx, targetSizePx)
                        }
                    }
                    .build()
            }

            SubcomposeAsyncImage(
                model = imageRequest,
                contentDescription = contentDescription ?: track?.title ?: "Album Artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    ArtworkPlaceholder()
                },
                error = {
                    if (candidateIndex < candidates.lastIndex) {
                        // Resilient fallback: Try next candidate in resolution chain (e.g. maxres -> sd -> hq)
                        DisposableEffect(candidateIndex) {
                            candidateIndex++
                            onDispose {}
                        }
                    } else {
                        ArtworkPlaceholder()
                    }
                }
            )
        } else if (track != null && !isRemote) {
            // Local Storage / MediaStore artwork (only for local tracks!)
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
            // Generated placeholder (remote stream with no artwork or null track)
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
