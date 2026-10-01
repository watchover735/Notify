package com.notify.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notify.ui.theme.DarkBackground
import com.notify.ui.theme.DarkSurface
import com.notify.ui.theme.DarkSurfaceBorder
import com.notify.ui.theme.DarkSurfaceVariant
import com.notify.ui.theme.EmeraldAccent
import com.notify.ui.theme.ErrorRed
import com.notify.ui.theme.TextPrimary
import com.notify.ui.theme.TextSecondary

@Composable
fun ImportPlaylistScreen(
    onBack: () -> Unit,
    onImportYouTube: (String, (Boolean) -> Unit) -> Unit,
    onImportSpotify: (String, (Boolean) -> Unit) -> Unit,
    onImportFile: () -> Unit,
    isImporting: Boolean = false,
    importError: String? = null,
    onDismissError: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var showYouTubeDialog by remember { mutableStateOf(false) }
    var showSpotifyDialog by remember { mutableStateOf(false) }
    var showPlatformComingSoon by remember { mutableStateOf<String?>(null) }

    var youtubeUrl by remember { mutableStateOf("") }
    var spotifyUrl by remember { mutableStateOf("") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .testTag("import_playlist_screen_root")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Top Bar: Back Button & Screen Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Import Playlist",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Main Card Container with 5 Rows
            Surface(
                color = DarkSurface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Row 1: Import from File
                    ImportOptionRow(
                        title = "Import from File",
                        icon = {
                            BrandIconContainer(backgroundColor = Color(0xFF2C2C2C)) {
                                FileBrandLogo()
                            }
                        },
                        onClick = onImportFile,
                        modifier = Modifier.testTag("row_import_file")
                    )

                    HorizontalDivider(color = Color(0xFF252525), thickness = 0.8.dp)

                    // Row 2: Import from YouTube
                    ImportOptionRow(
                        title = "Import from YouTube",
                        icon = {
                            BrandIconContainer(backgroundColor = Color(0xFF242424)) {
                                YouTubeBrandLogo()
                            }
                        },
                        onClick = {
                            youtubeUrl = ""
                            onDismissError()
                            showYouTubeDialog = true
                        },
                        modifier = Modifier.testTag("row_import_youtube")
                    )

                    HorizontalDivider(color = Color(0xFF252525), thickness = 0.8.dp)

                    // Row 3: Import from Spotify
                    ImportOptionRow(
                        title = "Import from Spotify",
                        icon = {
                            BrandIconContainer(backgroundColor = Color(0xFF242424)) {
                                SpotifyBrandLogo()
                            }
                        },
                        onClick = {
                            spotifyUrl = ""
                            onDismissError()
                            showSpotifyDialog = true
                        },
                        modifier = Modifier.testTag("row_import_spotify")
                    )

                    HorizontalDivider(color = Color(0xFF252525), thickness = 0.8.dp)

                    // Row 4: Import from Resso
                    ImportOptionRow(
                        title = "Import from Resso",
                        icon = {
                            BrandIconContainer(backgroundColor = Color(0xFF242424)) {
                                RessoBrandLogo()
                            }
                        },
                        onClick = { showPlatformComingSoon = "Resso" },
                        modifier = Modifier.testTag("row_import_resso")
                    )

                    HorizontalDivider(color = Color(0xFF252525), thickness = 0.8.dp)

                    // Row 5: Import from Deezer
                    ImportOptionRow(
                        title = "Import from Deezer",
                        icon = {
                            BrandIconContainer(backgroundColor = Color(0xFF242424)) {
                                DeezerBrandLogo()
                            }
                        },
                        onClick = { showPlatformComingSoon = "Deezer" },
                        modifier = Modifier.testTag("row_import_deezer")
                    )
                }
            }
        }

        // Dialog: YouTube Import
        if (showYouTubeDialog) {
            AlertDialog(
                onDismissRequest = { if (!isImporting) showYouTubeDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandIconContainer(
                            modifier = Modifier.size(36.dp),
                            backgroundColor = Color(0xFF242424)
                        ) {
                            YouTubeBrandLogo()
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Import YouTube",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = TextPrimary
                        )
                    }
                },
                text = {
                    Column {
                        Text(
                            text = "Paste a public or unlisted YouTube playlist link (e.g. youtube.com/playlist?list=...).",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedTextField(
                            value = youtubeUrl,
                            onValueChange = {
                                youtubeUrl = it
                                if (importError != null) onDismissError()
                            },
                            placeholder = {
                                Text("https://www.youtube.com/playlist?list=...", color = TextSecondary, fontSize = 12.sp)
                            },
                            singleLine = true,
                            enabled = !isImporting,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val clipText = clipboardManager.getText()?.text
                                        if (!clipText.isNullOrBlank()) {
                                            youtubeUrl = clipText.trim()
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Paste",
                                        tint = EmeraldAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = DarkSurfaceVariant,
                                unfocusedContainerColor = DarkSurfaceVariant,
                                focusedBorderColor = EmeraldAccent,
                                unfocusedBorderColor = DarkSurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("import_youtube_url_field")
                        )

                        if (!importError.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = importError,
                                color = ErrorRed,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onImportYouTube(youtubeUrl) { success ->
                                if (success) {
                                    showYouTubeDialog = false
                                    onBack()
                                }
                            }
                        },
                        enabled = youtubeUrl.isNotBlank() && !isImporting,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("btn_confirm_import_youtube")
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Importing...", color = Color.Black, fontWeight = FontWeight.Bold)
                        } else {
                            Text("Import", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showYouTubeDialog = false },
                        enabled = !isImporting
                    ) {
                        Text("Cancel", color = TextSecondary)
                    }
                },
                containerColor = DarkSurface,
                shape = RoundedCornerShape(16.dp)
            )
        }

        // Dialog: Spotify Import
        if (showSpotifyDialog) {
            AlertDialog(
                onDismissRequest = { if (!isImporting) showSpotifyDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandIconContainer(
                            modifier = Modifier.size(36.dp),
                            backgroundColor = Color(0xFF242424)
                        ) {
                            SpotifyBrandLogo()
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Import Spotify",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = TextPrimary
                        )
                    }
                },
                text = {
                    Column {
                        Text(
                            text = "Paste a public Spotify playlist link (e.g. open.spotify.com/playlist/...).",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedTextField(
                            value = spotifyUrl,
                            onValueChange = {
                                spotifyUrl = it
                                if (importError != null) onDismissError()
                            },
                            placeholder = {
                                Text("https://open.spotify.com/playlist/...", color = TextSecondary, fontSize = 12.sp)
                            },
                            singleLine = true,
                            enabled = !isImporting,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val clipText = clipboardManager.getText()?.text
                                        if (!clipText.isNullOrBlank()) {
                                            spotifyUrl = clipText.trim()
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Paste",
                                        tint = EmeraldAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = DarkSurfaceVariant,
                                unfocusedContainerColor = DarkSurfaceVariant,
                                focusedBorderColor = EmeraldAccent,
                                unfocusedBorderColor = DarkSurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("import_spotify_url_field")
                        )

                        if (!importError.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = importError,
                                color = ErrorRed,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onImportSpotify(spotifyUrl) { success ->
                                if (success) {
                                    showSpotifyDialog = false
                                    onBack()
                                }
                            }
                        },
                        enabled = spotifyUrl.isNotBlank() && !isImporting,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("btn_confirm_import")
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Importing...", color = Color.Black, fontWeight = FontWeight.Bold)
                        } else {
                            Text("Import", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showSpotifyDialog = false },
                        enabled = !isImporting
                    ) {
                        Text("Cancel", color = TextSecondary)
                    }
                },
                containerColor = DarkSurface,
                shape = RoundedCornerShape(16.dp)
            )
        }

        // Dialog: Coming soon platform
        showPlatformComingSoon?.let { platform ->
            AlertDialog(
                onDismissRequest = { showPlatformComingSoon = null },
                title = { Text(text = "$platform Import", fontWeight = FontWeight.Bold, color = TextPrimary) },
                text = {
                    Text(
                        text = "$platform playlist import is currently in development and will be available in an upcoming update.",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showPlatformComingSoon = null }) {
                        Text("OK", color = EmeraldAccent)
                    }
                },
                containerColor = DarkSurface,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
private fun ImportOptionRow(
    title: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = Color(0xFF757575),
            modifier = Modifier.size(20.dp)
        )
    }
}
