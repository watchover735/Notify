package com.notify.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.notify.core.model.RepeatMode
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.notify.playback.ABRepeatState
import com.notify.ui.theme.DarkSurfaceBorder
import com.notify.ui.theme.EmeraldGlow

@Composable
fun FullPlaybackControls(
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    abRepeatState: ABRepeatState = ABRepeatState(),
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleABRepeat: () -> Unit = {},
    onClearABRepeat: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Shuffle Button
        IconButton(
            onClick = onToggleShuffle,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = if (shuffleEnabled) "Shuffle Enabled" else "Shuffle Disabled",
                tint = if (shuffleEnabled) EmeraldAccent else TextSecondary,
                modifier = Modifier.size(22.dp)
            )
        }

        // Previous Button
        IconButton(
            onClick = onPrevious,
            modifier = Modifier.size(44.dp)
        ) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Previous Track",
                tint = TextPrimary,
                modifier = Modifier.size(30.dp)
            )
        }

        // Play/Pause Main Circle Button
        IconButton(
            onClick = onTogglePlayPause,
            modifier = Modifier
                .size(60.dp)
                .background(EmeraldAccent, CircleShape)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.Black,
                modifier = Modifier.size(34.dp)
            )
        }

        // Next Button
        IconButton(
            onClick = onNext,
            modifier = Modifier.size(44.dp)
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next Track",
                tint = TextPrimary,
                modifier = Modifier.size(30.dp)
            )
        }

        // Repeat Button
        IconButton(
            onClick = onCycleRepeat,
            modifier = Modifier.size(40.dp)
        ) {
            val (icon, tint) = when (repeatMode) {
                RepeatMode.OFF -> Icons.Default.Repeat to TextSecondary
                RepeatMode.ALL -> Icons.Default.Repeat to EmeraldAccent
                RepeatMode.ONE -> Icons.Default.RepeatOne to EmeraldAccent
            }
            Icon(
                imageVector = icon,
                contentDescription = "Repeat: $repeatMode",
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }

        // A-B Repeat Section Loop Button
        ABRepeatButton(
            state = abRepeatState,
            onToggle = onToggleABRepeat,
            onClear = onClearABRepeat
        )
    }
}

/**
 * Modern pill-style button for A-B Section Repeat:
 * - Inactive: Dim outline with "A-B".
 * - Point A Set: Highlighted outline + subtle glow with "A→".
 * - Both Set (Looping): Solid Emerald pill with "A⇄B".
 * - Short click: Toggles next state in sequence.
 * - Long click: Immediately resets/clears points.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ABRepeatButton(
    state: ABRepeatState,
    onToggle: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    val isLoopActive = state.isBothMarked
    val isStartOnly = state.isStartMarked && !state.isEndMarked

    val bg = when {
        isLoopActive -> EmeraldAccent
        isStartOnly -> EmeraldGlow
        else -> Color.Transparent
    }
    val borderColor = when {
        isLoopActive -> Color.Transparent
        isStartOnly -> EmeraldAccent
        else -> DarkSurfaceBorder
    }
    val textColor = when {
        isLoopActive -> Color.Black
        isStartOnly -> EmeraldAccent
        else -> TextSecondary
    }
    val label = when {
        isLoopActive -> "A⇄B"
        isStartOnly -> "A→"
        else -> "A-B"
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .then(
                if (borderColor != Color.Transparent) Modifier.border(1.dp, borderColor, RoundedCornerShape(12.dp))
                else Modifier
            )
            .combinedClickable(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClear()
                }
            )
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
