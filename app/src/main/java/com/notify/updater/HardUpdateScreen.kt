package com.notify.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notify.ui.theme.DarkBackground
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary

/**
 * Full-screen blocking UI shown when the app requires a mandatory (hard) update.
 * Prevents user from accessing any app features, menus, or playback.
 */
@Composable
fun HardUpdateScreen(
    config: AppConfig,
    reason: HardUpdateReason,
    onPausePlayback: () -> Unit = {}
) {
    val context = LocalContext.current
    var isCheckingOrDownloading by remember { mutableStateOf(false) }

    // Block system back navigation completely
    BackHandler {
        // Blocked: user must update to proceed
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Update Icon circle
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(DarkSurface, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = "Update Required",
                    tint = EmeraldAccent,
                    modifier = Modifier.size(42.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (reason == HardUpdateReason.URGENT) {
                    "Zaroori Update Available"
                } else {
                    "Update Karna Zaroori Hai"
                },
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = config.forceMessage.ifBlank {
                    "NotiFy ka naya update zaroori hai. App use karne ke liye kripya update karein."
                },
                color = TextSecondary,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            if (reason == HardUpdateReason.SKIPS_EXHAUSTED) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Aapne skip limit (${config.maxSkips}) poori kar li hai.",
                    color = Color(0xFFF59E0B), // Warm amber
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Primary: In-App Update / Download
            Button(
                onClick = {
                    if (isCheckingOrDownloading) return@Button
                    isCheckingOrDownloading = true
                    onPausePlayback()

                    handlePrimaryUpdateClick(context, config) {
                        isCheckingOrDownloading = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldAccent,
                    contentColor = Color.Black
                ),
                enabled = !isCheckingOrDownloading
            ) {
                Text(
                    text = if (isCheckingOrDownloading) "Checking / Starting..." else "Update Karo",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Secondary: Browser download fallback
            OutlinedButton(
                onClick = {
                    onPausePlayback()
                    openInBrowser(context, config.downloadUrl)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextSecondary
                )
            ) {
                Text(
                    text = "Browser me download karo",
                    fontSize = 15.sp
                )
            }
        }
    }
}

private fun handlePrimaryUpdateClick(
    context: Context,
    config: AppConfig,
    onComplete: () -> Unit
) {
    // 1. Check if installation permission is needed on Android 8.0+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Toast.makeText(
                context,
                "NotiFy ke liye 'Install unknown apps' allow karein, phir wapas aayein",
                Toast.LENGTH_LONG
            ).show()
            onComplete()
            return
        } catch (_: Exception) {
            // Fallthrough to standard updater
        }
    }

    // 2. Reuse AppUpdater logic if available
    val activeUpdateInfo = AppUpdater.updateAvailable.value
    if (activeUpdateInfo != null && activeUpdateInfo.apkAsset != null) {
        AppUpdater.onUpdateNowClicked(context, activeUpdateInfo)
        onComplete()
    } else {
        // If AppUpdater hasn't fetched release yet or has no asset, trigger check and fallback to browser
        AppUpdater.checkForUpdates(context, forceCheck = true)
        val browserUrl = config.downloadUrl?.takeIf { it.isNotBlank() } ?: AppUpdater.GITHUB_LATEST_RELEASE_URL
        openInBrowser(context, browserUrl)
        onComplete()
    }
}

private fun openInBrowser(context: Context, urlString: String?) {
    val targetUrl = urlString?.takeIf { it.isNotBlank() } ?: AppUpdater.GITHUB_LATEST_RELEASE_URL
    try {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(browserIntent)
    } catch (e: Exception) {
        Toast.makeText(context, "Browser open nahi ho saka: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
