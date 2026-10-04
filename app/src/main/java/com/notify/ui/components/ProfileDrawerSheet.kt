package com.notify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.DarkSurfaceVariant
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.EmeraldLight
import com.notify.ui.theme.ErrorRed
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary

@Composable
fun ProfileDrawerSheet(
    nickname: String,
    email: String,
    keyStatusText: String,
    isGoogleUser: Boolean,
    versionName: String,
    onUpdateKeyClick: () -> Unit,
    onChangePasswordClick: () -> Unit,
    onLogoutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val initial = nickname.trim().firstOrNull()?.uppercaseChar()?.toString()
        ?: email.trim().firstOrNull()?.uppercaseChar()?.toString()
        ?: "N"

    ModalDrawerSheet(
        modifier = modifier
            .fillMaxWidth(0.80f)
            .fillMaxHeight(),
        drawerContainerColor = DarkSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            // ── User Header ──────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            brush = Brush.linearGradient(listOf(EmeraldAccent, EmeraldLight)),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initial,
                        color = Color.Black,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = nickname.ifBlank { "NotiFy User" },
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (email.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = email,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ── Read-only Key Status Pill ────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurfaceVariant, RoundedCornerShape(10.dp))
                    .border(1.dp, EmeraldAccent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = EmeraldAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = keyStatusText,
                        color = EmeraldAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            HorizontalDivider(
                color = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.padding(vertical = 18.dp)
            )

            // ── Menu Items ───────────────────────────────────────────────
            ProfileDrawerItem(
                icon = Icons.Default.Key,
                title = "Update key",
                onClick = onUpdateKeyClick
            )

            if (!isGoogleUser) {
                Spacer(modifier = Modifier.height(6.dp))
                ProfileDrawerItem(
                    icon = Icons.Default.Lock,
                    title = "Change password",
                    onClick = onChangePasswordClick
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            ProfileDrawerItem(
                icon = Icons.AutoMirrored.Filled.Logout,
                title = "Logout",
                titleColor = ErrorRed,
                iconColor = ErrorRed,
                onClick = onLogoutClick
            )

            Spacer(modifier = Modifier.weight(1f))

            // ── Footer ───────────────────────────────────────────────────
            if (versionName.isNotBlank()) {
                Text(
                    text = "NotiFy · v$versionName",
                    color = TextSecondary.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ProfileDrawerItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    titleColor: Color = TextPrimary,
    iconColor: Color = TextSecondary
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = iconColor,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            color = titleColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
