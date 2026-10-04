package com.notify.auth

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.notify.ui.components.ForgotPasswordDialog
import com.notify.ui.theme.DarkBackground
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.ErrorRed
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary
import com.notify.updater.AppUpdater
import com.notify.updater.HardUpdateScreen
import com.notify.updater.SoftUpdateDialog

internal enum class AuthGateScreenKey {
    Loading,
    NeedLogin,
    NeedEmailOtp,
    NeedNickname,
    NeedKey,
    Ready,
    Error
}

internal fun AuthGateState.toScreenKey(): AuthGateScreenKey = when (this) {
    is AuthGateState.Loading -> AuthGateScreenKey.Loading
    is AuthGateState.NeedLogin -> AuthGateScreenKey.NeedLogin
    is AuthGateState.NeedEmailOtp -> AuthGateScreenKey.NeedEmailOtp
    is AuthGateState.NeedNickname -> AuthGateScreenKey.NeedNickname
    is AuthGateState.NeedKey -> AuthGateScreenKey.NeedKey
    is AuthGateState.Ready -> AuthGateScreenKey.Ready
    is AuthGateState.Error -> AuthGateScreenKey.Error
}

@Composable
fun AuthGate(
    viewModel: AuthGateViewModel,
    content: @Composable () -> Unit
) {
    // 1. Mandatory HARD update takes highest precedence over all states
    val hardUpdate by viewModel.hardUpdateState.collectAsState()
    if (hardUpdate != null) {
        HardUpdateScreen(
            config = hardUpdate!!.config,
            reason = hardUpdate!!.reason,
            onPausePlayback = { viewModel.pausePlayback() }
        )
        return
    }

    val gateState by viewModel.gateState.collectAsState()
    val screenKey = gateState.toScreenKey()

    Crossfade(targetState = screenKey, label = "AuthGateTransition") { targetScreen ->
        when (targetScreen) {
            AuthGateScreenKey.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBackground),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = EmeraldAccent,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            AuthGateScreenKey.NeedLogin -> {
                BackHandler {
                    // Prevent navigating to Home; stay on Login
                }
                var showForgotPasswordDialog by remember { mutableStateOf(false) }
                val currentLoginState = gateState as? AuthGateState.NeedLogin
                val cooldown by viewModel.resendCooldownSeconds.collectAsState()

                LoginScreen(
                    isLoading = currentLoginState?.isLoading == true,
                    errorMessage = currentLoginState?.error,
                    onGoogleSignInClick = { viewModel.signInWithGoogle() },
                    onEmailSignIn = { email, pass -> viewModel.signInWithEmail(email, pass) },
                    onEmailSignUp = { email, pass -> viewModel.signUpWithEmail(email, pass) },
                    onForgotPasswordClick = { showForgotPasswordDialog = true },
                    onDismissError = { viewModel.dismissError() }
                )

                if (showForgotPasswordDialog) {
                    ForgotPasswordDialog(
                        initialEmail = "",
                        cooldownSeconds = cooldown,
                        onDismiss = { showForgotPasswordDialog = false },
                        onSendRecoveryEmail = { email -> viewModel.sendPasswordRecovery(email) },
                        onResetPassword = { email, token, newPass, confPass ->
                            viewModel.verifyRecoveryAndResetPassword(email, token, newPass, confPass)
                        }
                    )
                }
            }

            AuthGateScreenKey.NeedEmailOtp -> {
                BackHandler {
                    viewModel.backToLogin()
                }
                val otpState = gateState as? AuthGateState.NeedEmailOtp
                val cooldown by viewModel.resendCooldownSeconds.collectAsState()
                if (otpState != null) {
                    OtpScreen(
                        email = otpState.email,
                        isLoading = otpState.isLoading,
                        errorMessage = otpState.error,
                        infoMessage = otpState.message,
                        cooldownSeconds = cooldown,
                        onVerify = { code -> viewModel.verifyEmailOtp(otpState.email, code) },
                        onResend = { viewModel.resendEmailOtp(otpState.email) },
                        onChangeEmail = { viewModel.backToLogin() },
                        onDismissError = { viewModel.dismissError() }
                    )
                }
            }

            AuthGateScreenKey.NeedNickname -> {
                BackHandler {
                    // Prevent navigating to Home; stay on Nickname
                }
                val currentNicknameState = gateState as? AuthGateState.NeedNickname
                NicknameScreen(
                    isLoading = currentNicknameState?.isLoading == true,
                    errorMessage = currentNicknameState?.error,
                    onSaveNickname = { nickname -> viewModel.saveNickname(nickname) },
                    onDismissError = { viewModel.dismissError() }
                )
            }

            AuthGateScreenKey.NeedKey -> {
                BackHandler {
                    // Prevent navigating to Home; stay on Key entry
                }
                val currentKeyState = gateState as? AuthGateState.NeedKey
                KeyEntryScreen(
                    isLoading = currentKeyState?.isLoading == true,
                    statusMessage = currentKeyState?.message,
                    isError = currentKeyState?.isError == true,
                    onRedeemKey = { code -> viewModel.redeemKey(code) },
                    onDismissMessage = { viewModel.dismissError() }
                )
            }

            AuthGateScreenKey.Ready -> {
                content()

                // Soft update dialog only shown in Ready state
                val softUpdate by viewModel.softUpdateState.collectAsState()
                val context = LocalContext.current
                val currentSoft = softUpdate
                if (currentSoft != null) {
                    SoftUpdateDialog(
                        config = currentSoft.config,
                        skipsLeft = currentSoft.skipsLeft,
                        onUpdateNow = {
                            viewModel.onAcceptSoftUpdate()
                            val activeInfo = AppUpdater.updateAvailable.value
                            if (activeInfo != null && activeInfo.apkAsset != null) {
                                AppUpdater.onUpdateNowClicked(context, activeInfo)
                            } else {
                                AppUpdater.checkForUpdates(context, forceCheck = true)
                                val targetUrl = currentSoft.config.downloadUrl?.takeIf { it.isNotBlank() }
                                    ?: AppUpdater.GITHUB_LATEST_RELEASE_URL
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }
                        },
                        onLater = {
                            viewModel.onDismissSoftUpdate()
                        }
                    )
                }
            }

            AuthGateScreenKey.Error -> {
                BackHandler {
                    // Prevent navigation
                }
                val currentErrorState = gateState as? AuthGateState.Error
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBackground)
                        .padding(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = ErrorRed,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Connection Error",
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = currentErrorState?.message.orEmpty(),
                            color = TextSecondary,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(28.dp))
                        Button(
                            onClick = { currentErrorState?.onRetry?.invoke() },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldAccent,
                                contentColor = Color.Black
                            )
                        ) {
                            Text("Try Again", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
