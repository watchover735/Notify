package com.notify.updater

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary

/**
 * Host composable that observes [AppUpdater.updateAvailable] and renders the update prompt dialog.
 * Dismissing the dialog or tapping "Later" ensures no further nag in the current session.
 */
@Composable
fun AppUpdateDialogHost() {
    val updateInfo by AppUpdater.updateAvailable.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val info = updateInfo ?: return

    AppUpdateDialog(
        updateInfo = info,
        onUpdateNow = {
            AppUpdater.onUpdateNowClicked(context, info)
        },
        onLater = {
            AppUpdater.onLaterClicked()
        }
    )
}

/**
 * Presentational dialog for in-app updates.
 */
@Composable
fun AppUpdateDialog(
    updateInfo: UpdateInfo,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = {
            Text(
                text = "Update Available: ${updateInfo.versionName}",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (updateInfo.changelog.isNotBlank()) {
                    Text(
                        text = "What's New:",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = updateInfo.changelog,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        text = "A new version of NotiFy (${updateInfo.versionName}) is available. Would you like to update now?",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onUpdateNow,
                colors = ButtonDefaults.textButtonColors(contentColor = EmeraldAccent)
            ) {
                Text(
                    text = "Update Now",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onLater,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
            ) {
                Text(text = "Later")
            }
        },
        containerColor = DarkSurface,
        tonalElevation = 6.dp
    )
}
