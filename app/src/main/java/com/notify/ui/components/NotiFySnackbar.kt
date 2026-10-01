package com.notify.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notify.ui.theme.DarkSurfaceElevated
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * A NotiFy-styled Material Snackbar shown above the MiniPlayer.
 *
 * - [message]    The text to display.
 * - [actionLabel] Optional action button label (e.g. "Retry", "Undo").
 * - [onAction]   Called when user taps the action button.
 * - [onDismiss]  Called after auto-dismiss delay or after action tap.
 * - [autoDismissMs] How long to keep the snackbar visible (default 3.5s).
 */
@Composable
fun NotiFySnackbar(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    autoDismissMs: Long = 3_500L,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(message) {
        visible = true
        delay(autoDismissMs)
        visible = false
        delay(300) // allow slide-out animation
        onDismiss()
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            shape = RoundedCornerShape(10.dp),
            color = DarkSurfaceElevated,
            shadowElevation = 8.dp,
            tonalElevation = 4.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
            ) {
                Text(
                    text = message,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                    lineHeight = 17.sp
                )
                if (actionLabel != null && onAction != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            visible = false
                            onAction()
                            onDismiss()
                        }
                    ) {
                        Text(
                            text = actionLabel,
                            color = EmeraldAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
