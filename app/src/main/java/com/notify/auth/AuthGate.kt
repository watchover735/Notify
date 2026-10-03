package com.notify.auth

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notify.ui.theme.DarkBackground
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.ErrorRed
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary

@Composable
fun AuthGate(
    viewModel: AuthGateViewModel,
    content: @Composable () -> Unit
) {
    val gateState by viewModel.gateState.collectAsState()

    Crossfade(targetState = gateState, label = "AuthGateTransition") { state ->
        when (state) {
            is AuthGateState.Loading -> {
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

            is AuthGateState.NeedLogin -> {
                BackHandler {
                    // Prevent navigating to Home; stay on Login
                }
                LoginScreen(
                    isLoading = state.isLoading,
                    errorMessage = state.error,
                    onGoogleSignInClick = { viewModel.signInWithGoogle() },
                    onEmailSignIn = { email, pass -> viewModel.signInWithEmail(email, pass) },
                    onEmailSignUp = { email, pass -> viewModel.signUpWithEmail(email, pass) },
                    onDismissError = { viewModel.dismissError() }
                )
            }

            is AuthGateState.NeedNickname -> {
                BackHandler {
                    // Prevent navigating to Home; stay on Nickname
                }
                NicknameScreen(
                    isLoading = state.isLoading,
                    errorMessage = state.error,
                    onSaveNickname = { nickname -> viewModel.saveNickname(nickname) },
                    onDismissError = { viewModel.dismissError() }
                )
            }

            is AuthGateState.NeedKey -> {
                BackHandler {
                    // Prevent navigating to Home; stay on Key entry
                }
                KeyEntryScreen(
                    isLoading = state.isLoading,
                    statusMessage = state.message,
                    isError = state.isError,
                    onRedeemKey = { code -> viewModel.redeemKey(code) },
                    onDismissMessage = { viewModel.dismissError() }
                )
            }

            is AuthGateState.Ready -> {
                content()
            }

            is AuthGateState.Error -> {
                BackHandler {
                    // Prevent navigation
                }
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
                            text = state.message,
                            color = TextSecondary,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(28.dp))
                        Button(
                            onClick = state.onRetry,
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
