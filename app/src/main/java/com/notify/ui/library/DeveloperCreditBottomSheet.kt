package com.notify.ui.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.notify.DeveloperConfig
import com.notify.R
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.DarkSurfaceVariant
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary
import com.notify.ui.theme.TextTertiary
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import androidx.compose.foundation.BorderStroke

private const val TAG = "DeveloperCredit"

// ──────────────────────────────────────────────────────────────────────────────
// Reusable developer avatar — photo, circular crop, "R" fallback on error.
// Matches artist photo style (ContentScale.Crop inside clip(CircleShape)).
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Circular avatar showing [R.drawable.developer_avatar].
 * On load failure, shows a gradient circle with the letter "R" — no crash.
 */
@Composable
fun DeveloperAvatar(size: Dp = 40.dp) {
    SubcomposeAsyncImage(
        model = R.drawable.developer_avatar,
        contentDescription = "Rahul's avatar",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(CircleShape),
        loading = {
            // Gradient "R" placeholder while loading
            DeveloperAvatarFallback(size = size)
        },
        error = {
            // Gradient "R" if drawable is missing or corrupt
            DeveloperAvatarFallback(size = size)
        },
        success = {
            // Photo loaded fine — show it cropped in the circle
            SubcomposeAsyncImageContent()
        }
    )
}

@Composable
private fun DeveloperAvatarFallback(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(listOf(EmeraldAccent, Color(0xFF007A5A)))
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "R",
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.43f).sp
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Library card row
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Premium pinned profile row for "Developed by Rahul" with avatar, blue verified badge, and contact action.
 */
@Composable
fun DeveloperCreditRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = DarkSurfaceVariant,
        border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.25f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Developer avatar (photo or "R" fallback)
            DeveloperAvatar(size = 40.dp)

            Spacer(modifier = Modifier.width(12.dp))

            // Center: Title + Blue Verified Badge + Subtitle
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = DeveloperConfig.DEVELOPED_BY,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    // Blue Instagram-style verified badge
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_verified_badge),
                        contentDescription = "Verified Developer",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Connect on Instagram / WhatsApp",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Right: Chevron
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextSecondary.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Bottom sheet
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Bottom sheet displaying contact options: Instagram and WhatsApp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperCreditBottomSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            // Drag indicator
            Box(
                modifier = Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
                    .align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Header row: avatar + name + badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeveloperAvatar(size = 52.dp)
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = DeveloperConfig.DEVELOPED_BY,
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_verified_badge),
                            contentDescription = "Verified Developer",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = "Connect or share feedback",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Instagram Option
            ContactOptionItem(
                title = "Instagram",
                subtitle = "@${DeveloperConfig.INSTAGRAM_HANDLE}",
                icon = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE1306C).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Instagram",
                            tint = Color(0xFFE1306C),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                onClick = {
                    openInstagram(context, DeveloperConfig.INSTAGRAM_HANDLE, DeveloperConfig.INSTAGRAM_URL)
                    onDismiss()
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // WhatsApp Option
            ContactOptionItem(
                title = "WhatsApp",
                subtitle = "Send a message",
                icon = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(EmeraldAccent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "WhatsApp",
                            tint = EmeraldAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                onClick = {
                    openWhatsApp(
                        context = context,
                        phone = DeveloperConfig.WHATSAPP_NUMBER,
                        message = DeveloperConfig.WHATSAPP_PREFILLED_MESSAGE
                    )
                    onDismiss()
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ContactOptionItem(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = DarkSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    color = TextTertiary,
                    fontSize = 12.sp
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Intent helpers
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Opens Instagram app if installed (app URI with handle); falls back to [instagramUrl] in browser.
 * ActivityNotFoundException and all other exceptions are caught — no crash.
 */
fun openInstagram(context: Context, handle: String = DeveloperConfig.INSTAGRAM_HANDLE, instagramUrl: String = DeveloperConfig.INSTAGRAM_URL) {
    val cleanHandle = handle.trim().removePrefix("@")
    val webUri = Uri.parse(instagramUrl.ifBlank { "https://www.instagram.com/$cleanHandle/" })

    if (cleanHandle.isNotBlank()) {
        try {
            val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/_u/$cleanHandle")).apply {
                setPackage("com.instagram.android")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(appIntent)
            return
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "Instagram app not installed, falling back to browser")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open Instagram app: ${e.message}")
        }
    }

    // Browser fallback using full profile URL
    try {
        val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(webIntent)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open Instagram profile in browser", e)
    }
}

/**
 * Opens WhatsApp chat with pre-filled message via wa.me link.
 * Falls back to browser if WhatsApp is not installed.
 * NOTE: Does NOT auto-send — the user reviews and sends manually.
 */
fun openWhatsApp(
    context: Context,
    phone: String = DeveloperConfig.WHATSAPP_NUMBER,
    message: String = DeveloperConfig.WHATSAPP_PREFILLED_MESSAGE
) {
    val cleanPhone = phone.filter { it.isDigit() }
    val encodedMessage = try {
        URLEncoder.encode(message, StandardCharsets.UTF_8.name())
    } catch (e: Exception) {
        Log.w(TAG, "Failed to URL-encode message, using raw text", e)
        message
    }

    val uriString = if (cleanPhone.isNotEmpty()) {
        "https://wa.me/$cleanPhone?text=$encodedMessage"
    } else {
        "https://wa.me/?text=$encodedMessage"
    }
    val uri = Uri.parse(uriString)

    try {
        val appIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(appIntent)
    } catch (e: android.content.ActivityNotFoundException) {
        Log.w(TAG, "WhatsApp not installed, falling back to browser")
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        } catch (e2: Exception) {
            Log.e(TAG, "Failed to open WhatsApp in browser", e2)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Unexpected error opening WhatsApp", e)
    }
}
