package com.notify.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.notify.auth.RedeemResult
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.DarkSurfaceVariant
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.ErrorRed
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun UpdateKeyDialog(
    onDismiss: () -> Unit,
    onRedeem: suspend (String) -> Result<RedeemResult>,
    modifier: Modifier = Modifier
) {
    var keyInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val cleanKey = keyInput.trim().uppercase().replace("\\s+".toRegex(), "")

    fun submit() {
        if (cleanKey.isBlank() || isLoading) return
        focusManager.clearFocus()
        isLoading = true
        feedbackMessage = null
        isSuccess = false

        coroutineScope.launch {
            val res = onRedeem(cleanKey)
            isLoading = false
            if (res.isSuccess) {
                val redeem = res.getOrThrow()
                when (redeem.code) {
                    "ok" -> {
                        isSuccess = true
                        val expiryDesc = formatKeyExpiry(redeem.expiresAtEpochMs, redeem.serverTimeEpochMs)
                        feedbackMessage = "Key redeemed successfully! $expiryDesc"
                        keyInput = ""
                    }
                    "permanent_already" -> {
                        isSuccess = false
                        feedbackMessage = "You already have permanent access, key was not used"
                    }
                    "invalid" -> {
                        isSuccess = false
                        feedbackMessage = "Invalid key. Please check and try again"
                    }
                    "already_used" -> {
                        isSuccess = false
                        feedbackMessage = "This key has already been used"
                    }
                    "revoked" -> {
                        isSuccess = false
                        feedbackMessage = "Key has been revoked. Please contact admin"
                    }
                    "too_many_attempts" -> {
                        isSuccess = false
                        feedbackMessage = "Too many attempts. Please try again after 15 minutes"
                    }
                    else -> {
                        isSuccess = false
                        feedbackMessage = redeem.message.ifBlank { "Key verification failed" }
                    }
                }
            } else {
                isSuccess = false
                val error = res.exceptionOrNull()?.message ?: "Server is not responding"
                feedbackMessage = error
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = DarkSurface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header with close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(DarkSurfaceVariant, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = EmeraldAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = "Update Key",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        enabled = !isLoading,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Enter a new key. Additional time will be added (stacked) to your existing access.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Key text field
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = {
                        keyInput = it
                        feedbackMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(text = "XXXX-XXXX-XXXX-XXXX", color = TextSecondary.copy(alpha = 0.5f))
                    },
                    singleLine = true,
                    enabled = !isLoading,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { submit() }
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = EmeraldAccent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        cursorColor = EmeraldAccent,
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant
                    )
                )

                // Feedback Banner
                AnimatedVisibility(visible = feedbackMessage != null) {
                    feedbackMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSuccess) EmeraldAccent.copy(alpha = 0.12f) else ErrorRed.copy(alpha = 0.12f),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isSuccess) EmeraldAccent.copy(alpha = 0.3f) else ErrorRed.copy(alpha = 0.3f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isSuccess) EmeraldAccent else ErrorRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = msg,
                                color = if (isSuccess) EmeraldAccent else ErrorRed,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextSecondary
                        )
                    ) {
                        Text(text = if (isSuccess) "Close" else "Cancel")
                    }

                    Button(
                        onClick = { submit() },
                        enabled = cleanKey.isNotBlank() && !isLoading,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldAccent,
                            contentColor = Color.Black,
                            disabledContainerColor = EmeraldAccent.copy(alpha = 0.35f),
                            disabledContentColor = Color.Black.copy(alpha = 0.4f)
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(text = "Add Key", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun formatKeyExpiry(expiresAtMs: Long?, serverTimeMs: Long?): String {
    if (expiresAtMs == null) return "Permanent access active."
    val now = serverTimeMs ?: System.currentTimeMillis()
    val remainingMs = expiresAtMs - now
    if (remainingMs <= 0) return "Key has expired."
    val days = (remainingMs / (24 * 3600 * 1000L)).toInt()
    val hours = (remainingMs / (3600 * 1000L)).toInt()
    return when {
        days == 0 -> if (hours <= 1) "Expires in 1 hour." else "$hours hours remaining."
        days == 1 -> "Expires tomorrow."
        else -> "$days days of access remaining."
    }
}
