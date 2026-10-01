package com.notify.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notify.core.playback.SleepTimerState
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.DarkSurfaceElevated
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary

sealed class SleepTimerOption(val title: String) {
    object Off : SleepTimerOption("Off")
    data class Minutes(val count: Int, val durationMs: Long) : SleepTimerOption("$count minutes")
    object OneHour : SleepTimerOption("1 hour")
    object EndOfTrack : SleepTimerOption("End of track")
}

val sleepTimerOptions: List<SleepTimerOption> = listOf(
    SleepTimerOption.Off,
    SleepTimerOption.Minutes(5, 5 * 60 * 1000L),
    SleepTimerOption.Minutes(10, 10 * 60 * 1000L),
    SleepTimerOption.Minutes(15, 15 * 60 * 1000L),
    SleepTimerOption.Minutes(30, 30 * 60 * 1000L),
    SleepTimerOption.Minutes(45, 45 * 60 * 1000L),
    SleepTimerOption.OneHour,
    SleepTimerOption.EndOfTrack
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerBottomSheet(
    currentState: SleepTimerState,
    onSelectOption: (SleepTimerOption) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(EmeraldAccent.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bedtime,
                        contentDescription = null,
                        tint = EmeraldAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Sleep Timer",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (currentState is SleepTimerState.Active) {
                        Text(
                            text = "Stopping in ${currentState.formattedRemaining}",
                            color = EmeraldAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    } else if (currentState is SleepTimerState.EndOfTrack) {
                        Text(
                            text = "Stopping after this track",
                            color = EmeraldAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Text(
                            text = "Music will turn off automatically",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Options List
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                sleepTimerOptions.forEach { option ->
                    val isSelected = when (option) {
                        is SleepTimerOption.Off -> currentState is SleepTimerState.Inactive
                        is SleepTimerOption.EndOfTrack -> currentState is SleepTimerState.EndOfTrack
                        is SleepTimerOption.OneHour -> {
                            currentState is SleepTimerState.Active &&
                                currentState.totalDurationMs == 60 * 60 * 1000L
                        }
                        is SleepTimerOption.Minutes -> {
                            currentState is SleepTimerState.Active &&
                                currentState.totalDurationMs == option.durationMs
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectOption(option)
                                onDismiss()
                            }
                            .background(
                                color = if (isSelected) DarkSurfaceElevated else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = option.title,
                            color = if (isSelected) EmeraldAccent else TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )

                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                onSelectOption(option)
                                onDismiss()
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = EmeraldAccent,
                                unselectedColor = TextSecondary
                            )
                        )
                    }
                }
            }
        }
    }
}
