package com.notify.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 44dp rounded square container for brand logos.
 * Does not tint the logo with a single color; keeps its authentic brand colors.
 */
@Composable
fun BrandIconContainer(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color(0xFF242424),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/**
 * Spotify Brand Logo:
 * Official Spotify Green (#1DB954) circle with three curved horizontal sound waves.
 */
@Composable
fun SpotifyBrandLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(28.dp)) {
        val diameter = size.minDimension
        val radius = diameter / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Green base circle
        drawCircle(
            color = Color(0xFF1DB954),
            radius = radius,
            center = center
        )

        val barColor = Color(0xFF121212)

        // Arc 1 (Top / longest wave)
        val arc1Width = diameter * 0.62f
        val arc1Height = diameter * 0.46f
        val arc1TopLeft = Offset(center.x - arc1Width / 2f, center.y - arc1Height / 2f - diameter * 0.13f)
        drawArc(
            color = barColor,
            startAngle = 208f,
            sweepAngle = 64f,
            useCenter = false,
            topLeft = arc1TopLeft,
            size = Size(arc1Width, arc1Height),
            style = Stroke(width = diameter * 0.088f, cap = StrokeCap.Round)
        )

        // Arc 2 (Middle wave)
        val arc2Width = diameter * 0.52f
        val arc2Height = diameter * 0.40f
        val arc2TopLeft = Offset(center.x - arc2Width / 2f, center.y - arc2Height / 2f + diameter * 0.03f)
        drawArc(
            color = barColor,
            startAngle = 210f,
            sweepAngle = 60f,
            useCenter = false,
            topLeft = arc2TopLeft,
            size = Size(arc2Width, arc2Height),
            style = Stroke(width = diameter * 0.082f, cap = StrokeCap.Round)
        )

        // Arc 3 (Bottom / shortest wave)
        val arc3Width = diameter * 0.42f
        val arc3Height = diameter * 0.34f
        val arc3TopLeft = Offset(center.x - arc3Width / 2f, center.y - arc3Height / 2f + diameter * 0.18f)
        drawArc(
            color = barColor,
            startAngle = 212f,
            sweepAngle = 56f,
            useCenter = false,
            topLeft = arc3TopLeft,
            size = Size(arc3Width, arc3Height),
            style = Stroke(width = diameter * 0.076f, cap = StrokeCap.Round)
        )
    }
}

/**
 * YouTube Brand Logo:
 * Official red rounded rectangle (#FF0000) with a centered white play triangle.
 */
@Composable
fun YouTubeBrandLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(28.dp)) {
        val rectWidth = size.width * 0.90f
        val rectHeight = size.height * 0.64f
        val left = (size.width - rectWidth) / 2f
        val top = (size.height - rectHeight) / 2f
        val corner = rectHeight * 0.28f

        // Red rounded rectangle
        drawRoundRect(
            color = Color(0xFFFF0000),
            topLeft = Offset(left, top),
            size = Size(rectWidth, rectHeight),
            cornerRadius = CornerRadius(corner, corner)
        )

        // Centered white play triangle
        val triWidth = rectWidth * 0.32f
        val triHeight = rectHeight * 0.46f
        val cx = size.width / 2f
        val cy = size.height / 2f

        val path = Path().apply {
            moveTo(cx - triWidth * 0.38f, cy - triHeight / 2f)
            lineTo(cx + triWidth * 0.62f, cy)
            lineTo(cx - triWidth * 0.38f, cy + triHeight / 2f)
            close()
        }
        drawPath(path, color = Color.White)
    }
}

/**
 * Deezer Brand Logo:
 * Multicolor equalizer bars using Deezer's official spectrum (pink, orange, yellow, green, cyan).
 */
@Composable
fun DeezerBrandLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(28.dp)) {
        val colors = listOf(
            Color(0xFFEF5466), // Pink/Red
            Color(0xFFFF7700), // Orange
            Color(0xFFFFE600), // Yellow
            Color(0xFFA0EB34), // Green
            Color(0xFF2CE6E6)  // Cyan
        )
        val heights = listOf(0.40f, 0.72f, 1.00f, 0.62f, 0.84f)
        val barCount = colors.size
        val totalWidth = size.width * 0.85f
        val maxHeight = size.height * 0.75f
        val barWidth = totalWidth / (barCount * 1.6f)
        val gap = (totalWidth - (barWidth * barCount)) / (barCount - 1)
        val startX = (size.width - totalWidth) / 2f
        val bottomY = size.height / 2f + maxHeight / 2f

        for (i in 0 until barCount) {
            val barH = maxHeight * heights[i]
            val x = startX + i * (barWidth + gap)
            val y = bottomY - barH
            val corner = barWidth * 0.35f
            drawRoundRect(
                color = colors[i],
                topLeft = Offset(x, y),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(corner, corner)
            )
        }
    }
}

/**
 * Resso Brand Logo:
 * Vibrant red-orange diagonal gradient with stylized rhythm wave.
 */
@Composable
fun RessoBrandLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(28.dp)) {
        val diameter = size.minDimension
        val radius = diameter / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Red/Orange gradient base
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFFF1E56), Color(0xFFFF6200)),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height)
            ),
            radius = radius,
            center = center
        )

        // Stylized Resso rhythm wave (sound curves in white)
        val waveW = diameter * 0.44f
        val waveH = diameter * 0.36f
        val stroke = diameter * 0.09f

        // Top arc
        drawArc(
            color = Color.White,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(center.x - waveW / 2f, center.y - waveH * 0.8f),
            size = Size(waveW, waveH),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        // Bottom reversed arc
        drawArc(
            color = Color.White,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(center.x - waveW / 2f, center.y - waveH * 0.2f),
            size = Size(waveW, waveH),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

/**
 * Neutral File Import Icon:
 * Clean box with right-arrow entry icon.
 */
@Composable
fun FileBrandLogo(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.Input,
        contentDescription = "File Import",
        tint = Color(0xFFDDDDDD),
        modifier = modifier.size(22.dp)
    )
}
