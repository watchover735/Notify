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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary

/**
 * Presentational dialog for soft (deferrable) updates.
 * User can tap "Baad me" up to [skipsLeft] times before update becomes mandatory.
 * Cannot be dismissed via outside tap or back press.
 */
@Composable
fun SoftUpdateDialog(
    config: AppConfig,
    skipsLeft: Int,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            // Explicitly do nothing: outside tap / back gesture cannot dismiss dialog
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        title = {
            Text(
                text = "Naya version available",
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
                Text(
                    text = config.forceMessage.ifBlank {
                        "NotiFy ka naya update aa chuka hai. Behtar features ke liye update karein."
                    },
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Skip karne ke $skipsLeft mauke bache",
                    color = EmeraldAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onUpdateNow,
                colors = ButtonDefaults.textButtonColors(contentColor = EmeraldAccent)
            ) {
                Text(
                    text = "Update karo",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onLater,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
            ) {
                Text(text = "Baad me")
            }
        },
        containerColor = DarkSurface,
        tonalElevation = 6.dp
    )
}
