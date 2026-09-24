package com.notify.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notify.playback.ABRepeatState
import com.notify.ui.theme.DarkSurfaceBorder
import com.notify.ui.theme.DarkSurfaceVariant
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.EmeraldGlow
import com.notify.ui.theme.EmeraldLight
import kotlin.math.abs

private enum class DragTarget {
    NONE,
    HANDLE_A,
    HANDLE_B,
    PLAYHEAD
}

/**
 * Custom interactive seekbar supporting:
 * - Playhead dragging and click-to-seek.
 * - Draggable Marker A and Marker B handles with hit-testing priority (+/- 24dp).
 * - Real-time loop ribbon highlight between A and B.
 * - Staggered marker badge positioning to avoid occlusion when points are close.
 * - Strict gap enforcement (min 1000ms) and track boundary buffer (250ms).
 */
@Composable
fun ABRepeatSeekBar(
    currentPositionMs: Long,
    durationMs: Long,
    abRepeatState: ABRepeatState,
    onSeek: (Long) -> Unit,
    onUpdateABStart: (Long) -> Unit,
    onUpdateABEnd: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val textMeasurer = rememberTextMeasurer()

    var isDraggingPlayhead by remember { mutableStateOf(false) }
    var dragProgressFraction by remember { mutableFloatStateOf(0f) }

    var dragPreviewStartMs by remember { mutableStateOf<Long?>(null) }
    var dragPreviewEndMs by remember { mutableStateOf<Long?>(null) }

    val currentAbRepeatState by rememberUpdatedState(abRepeatState)

    val safeDuration = durationMs.coerceAtLeast(1L)
    val actualProgressFraction = if (durationMs > 0L) {
        (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val displayedProgressFraction = if (isDraggingPlayhead) dragProgressFraction else actualProgressFraction

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .pointerInput(enabled, durationMs) {
                if (!enabled || durationMs <= 0L) return@pointerInput

                val hitRadiusPx = 28.dp.toPx()

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat()
                    if (width <= 0f) return@awaitEachGesture

                    val downX = down.position.x
                    val startMs = currentAbRepeatState.abStartMs
                    val endMs = currentAbRepeatState.abEndMs

                    val xA = startMs?.let { (it.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f) * width }
                    val xB = endMs?.let { (it.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f) * width }
                    val xP = displayedProgressFraction * width

                    val distA = xA?.let { abs(downX - it) } ?: Float.MAX_VALUE
                    val distB = xB?.let { abs(downX - it) } ?: Float.MAX_VALUE
                    val distP = abs(downX - xP)

                    val target = when {
                        distA <= hitRadiusPx && distA <= distB -> DragTarget.HANDLE_A
                        distB <= hitRadiusPx -> DragTarget.HANDLE_B
                        else -> DragTarget.PLAYHEAD
                    }

                    val grabOffsetPx = when (target) {
                        DragTarget.HANDLE_A -> xA?.let { downX - it } ?: 0f
                        DragTarget.HANDLE_B -> xB?.let { downX - it } ?: 0f
                        else -> 0f
                    }

                    if (target == DragTarget.PLAYHEAD) {
                        isDraggingPlayhead = true
                        dragProgressFraction = (downX / width).coerceIn(0f, 1f)
                    } else if (target == DragTarget.HANDLE_A) {
                        dragPreviewStartMs = startMs
                    } else if (target == DragTarget.HANDLE_B) {
                        dragPreviewEndMs = endMs
                    }

                    var lastX = downX

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break

                            if (change.pressed) {
                                val rawX = change.position.x
                                lastX = rawX
                                val adjustedX = rawX - grabOffsetPx
                                val fraction = (adjustedX / width).coerceIn(0f, 1f)
                                val targetMs = (fraction * safeDuration).toLong()

                                when (target) {
                                    DragTarget.HANDLE_A -> {
                                        val maxA = ((endMs ?: safeDuration) - ABRepeatState.MIN_LOOP_GAP_MS).coerceAtLeast(0L)
                                        val clamped = targetMs.coerceIn(0L, maxA)
                                        dragPreviewStartMs = clamped
                                    }
                                    DragTarget.HANDLE_B -> {
                                        val minB = (startMs ?: 0L) + ABRepeatState.MIN_LOOP_GAP_MS
                                        val maxB = (safeDuration - ABRepeatState.TRACK_END_BUFFER_MS).coerceAtLeast(minB)
                                        val clamped = targetMs.coerceIn(minB, maxB)
                                        dragPreviewEndMs = clamped
                                    }
                                    DragTarget.PLAYHEAD -> {
                                        val playheadFraction = (rawX / width).coerceIn(0f, 1f)
                                        dragProgressFraction = playheadFraction
                                    }
                                    DragTarget.NONE -> {}
                                }
                                change.consume()
                            } else {
                                // Pointer released: commit final values once
                                val rawX = change.position.x
                                val adjustedX = rawX - grabOffsetPx
                                val fraction = (adjustedX / width).coerceIn(0f, 1f)
                                val targetMs = (fraction * safeDuration).toLong()

                                when (target) {
                                    DragTarget.PLAYHEAD -> {
                                        val finalFraction = (rawX / width).coerceIn(0f, 1f)
                                        val finalSeekMs = (finalFraction * safeDuration).toLong()
                                        onSeek(finalSeekMs)
                                        isDraggingPlayhead = false
                                    }
                                    DragTarget.HANDLE_A -> {
                                        val maxA = ((endMs ?: safeDuration) - ABRepeatState.MIN_LOOP_GAP_MS).coerceAtLeast(0L)
                                        val finalStartMs = targetMs.coerceIn(0L, maxA)
                                        onUpdateABStart(finalStartMs)
                                        dragPreviewStartMs = null
                                    }
                                    DragTarget.HANDLE_B -> {
                                        val minB = (startMs ?: 0L) + ABRepeatState.MIN_LOOP_GAP_MS
                                        val maxB = (safeDuration - ABRepeatState.TRACK_END_BUFFER_MS).coerceAtLeast(minB)
                                        val finalEndMs = targetMs.coerceIn(minB, maxB)
                                        onUpdateABEnd(finalEndMs)
                                        dragPreviewEndMs = null
                                    }
                                    DragTarget.NONE -> {}
                                }
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        isDraggingPlayhead = false
                        dragPreviewStartMs = null
                        dragPreviewEndMs = null
                    }
                }
            }
            .drawWithCache {
                val centerY = size.height / 2f
                val trackHeightPx = 4.dp.toPx()
                val cornerRadiusPx = 2.dp.toPx()
                val width = size.width

                val textStyle = TextStyle(
                    color = Color.Black,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                val measureA = textMeasurer.measure("A", textStyle)
                val measureB = textMeasurer.measure("B", textStyle)

                val badgeWidthPx = 18.dp.toPx()
                val badgeHeightPx = 15.dp.toPx()
                val badgeCornerRadiusPx = 3.dp.toPx()

                onDrawBehind {
                    // 1. Inactive background track
                    drawRoundRect(
                        color = DarkSurfaceVariant,
                        topLeft = Offset(0f, centerY - trackHeightPx / 2f),
                        size = Size(width, trackHeightPx),
                        cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                    )
                    drawRoundRect(
                        color = DarkSurfaceBorder,
                        topLeft = Offset(0f, centerY - trackHeightPx / 2f),
                        size = Size(width, trackHeightPx),
                        cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                        style = Stroke(width = 0.5.dp.toPx())
                    )

                    // 2. Loop range ribbon (uses local drag preview if dragging, else hoisted state)
                    val startMs = dragPreviewStartMs ?: abRepeatState.abStartMs
                    val endMs = dragPreviewEndMs ?: abRepeatState.abEndMs
                    val xA = startMs?.let { (it.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f) * width }
                    val xB = endMs?.let { (it.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f) * width }

                    if (xA != null && xB != null && xB > xA) {
                        val ribbonHeightPx = 8.dp.toPx()
                        drawRoundRect(
                            color = EmeraldGlow.copy(alpha = 0.45f),
                            topLeft = Offset(xA, centerY - ribbonHeightPx / 2f),
                            size = Size(xB - xA, ribbonHeightPx),
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                        )
                        drawRoundRect(
                            color = EmeraldAccent.copy(alpha = 0.7f),
                            topLeft = Offset(xA, centerY - ribbonHeightPx / 2f),
                            size = Size(xB - xA, ribbonHeightPx),
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    } else if (xA != null && xB == null) {
                        // Point A marked, Point B pending: subtle guide ribbon towards current position
                        val currentPlayX = displayedProgressFraction * width
                        if (currentPlayX > xA) {
                            val ribbonHeightPx = 6.dp.toPx()
                            drawRoundRect(
                                color = EmeraldGlow.copy(alpha = 0.25f),
                                topLeft = Offset(xA, centerY - ribbonHeightPx / 2f),
                                size = Size(currentPlayX - xA, ribbonHeightPx),
                                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                            )
                        }
                    }

                    // 3. Elapsed progress track
                    val playheadX = displayedProgressFraction * width
                    if (playheadX > 0f) {
                        drawRoundRect(
                            color = EmeraldAccent,
                            topLeft = Offset(0f, centerY - trackHeightPx / 2f),
                            size = Size(playheadX, trackHeightPx),
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                        )
                    }

                    // 4. Marker A (Handle & Pill Badge)
                    if (xA != null) {
                        // Vertical stem line
                        drawLine(
                            color = EmeraldLight,
                            start = Offset(xA, centerY - 12.dp.toPx()),
                            end = Offset(xA, centerY + 12.dp.toPx()),
                            strokeWidth = 2.dp.toPx()
                        )

                        // Top badge pill
                        val badgeLeft = (xA - badgeWidthPx / 2f).coerceIn(0f, width - badgeWidthPx)
                        val badgeTop = centerY - 14.dp.toPx() - badgeHeightPx
                        drawRoundRect(
                            color = EmeraldAccent,
                            topLeft = Offset(badgeLeft, badgeTop),
                            size = Size(badgeWidthPx, badgeHeightPx),
                            cornerRadius = CornerRadius(badgeCornerRadiusPx, badgeCornerRadiusPx)
                        )
                        val textOffsetX = badgeLeft + (badgeWidthPx - measureA.size.width) / 2f
                        val textOffsetY = badgeTop + (badgeHeightPx - measureA.size.height) / 2f
                        drawText(
                            textLayoutResult = measureA,
                            topLeft = Offset(textOffsetX, textOffsetY)
                        )
                    }

                    // 5. Marker B (Handle & Pill Badge)
                    if (xB != null) {
                        // Vertical stem line
                        drawLine(
                            color = EmeraldLight,
                            start = Offset(xB, centerY - 12.dp.toPx()),
                            end = Offset(xB, centerY + 12.dp.toPx()),
                            strokeWidth = 2.dp.toPx()
                        )

                        // Stagger badge position if A and B are very close (< 24dp)
                        val isCloseToA = xA != null && (xB - xA) < 22.dp.toPx()
                        val badgeLeft = (xB - badgeWidthPx / 2f).coerceIn(0f, width - badgeWidthPx)
                        val badgeTop = if (isCloseToA) {
                            centerY + 14.dp.toPx() // Bottom badge to prevent overlap
                        } else {
                            centerY - 14.dp.toPx() - badgeHeightPx // Top badge
                        }

                        drawRoundRect(
                            color = EmeraldAccent,
                            topLeft = Offset(badgeLeft, badgeTop),
                            size = Size(badgeWidthPx, badgeHeightPx),
                            cornerRadius = CornerRadius(badgeCornerRadiusPx, badgeCornerRadiusPx)
                        )
                        val textOffsetX = badgeLeft + (badgeWidthPx - measureB.size.width) / 2f
                        val textOffsetY = badgeTop + (badgeHeightPx - measureB.size.height) / 2f
                        drawText(
                            textLayoutResult = measureB,
                            topLeft = Offset(textOffsetX, textOffsetY)
                        )
                    }

                    // 6. Playhead Thumb
                    if (durationMs > 0L) {
                        // Outer halo
                        drawCircle(
                            color = Color.White.copy(alpha = 0.2f),
                            radius = 9.dp.toPx(),
                            center = Offset(playheadX, centerY)
                        )
                        // Inner solid thumb
                        drawCircle(
                            color = Color.White,
                            radius = 6.dp.toPx(),
                            center = Offset(playheadX, centerY)
                        )
                    }
                }
            }
    )
}
