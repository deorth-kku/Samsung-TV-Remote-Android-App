package com.vibecode.tvremote.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vibecode.tvremote.SamsungTvApp
import com.vibecode.tvremote.RemoteViewModel
import com.vibecode.tvremote.SamsungTvClient
import com.vibecode.tvremote.ui.theme.*

data class AppShortcut(
    val name: String,
    val id: String,
    val color: Color,
    val icon: ImageVector,
    val appType: Int? = null
)

private fun defaultQuickApps(): List<AppShortcut> = listOf(
    AppShortcut("YouTube", "11101200001", GlowCyan, Icons.Default.PlayArrow),
    AppShortcut("Netflix", "11101200007", PowerPink, Icons.Default.Movie),
    AppShortcut("Prime Video", "3201512006785", AccentOrange, Icons.Default.VideoLibrary),
    AppShortcut("Spotify", "3201606009684", Color(0xFF1DB954), Icons.Default.MusicNote),
    AppShortcut("Disney+", "3201901017640", GlowPurple, Icons.Default.Slideshow)
)

private fun appShortcutFor(app: SamsungTvApp): AppShortcut {
    val key = app.name.trim().lowercase()
    return when {
        key.contains("youtube") -> AppShortcut(app.name, app.appId, GlowCyan, Icons.Default.PlayArrow, app.appType)
        key.contains("netflix") -> AppShortcut(app.name, app.appId, PowerPink, Icons.Default.Movie, app.appType)
        key.contains("prime") -> AppShortcut(app.name, app.appId, AccentOrange, Icons.Default.VideoLibrary, app.appType)
        key.contains("spotify") -> AppShortcut(app.name, app.appId, Color(0xFF1DB954), Icons.Default.MusicNote, app.appType)
        key.contains("disney") -> AppShortcut(app.name, app.appId, GlowPurple, Icons.Default.Slideshow, app.appType)
        key.contains("youtube tv") -> AppShortcut(app.name, app.appId, GlowCyan, Icons.Default.LiveTv, app.appType)
        key.contains("browser") || key.contains("internet") -> AppShortcut(app.name, app.appId, GlowCyan, Icons.Default.Language, app.appType)
        key.contains("gallery") -> AppShortcut(app.name, app.appId, AccentOrange, Icons.Default.PhotoLibrary, app.appType)
        key.contains("app store") || key.contains("store") -> AppShortcut(app.name, app.appId, GlowPurple, Icons.Default.ShoppingBag, app.appType)
        else -> AppShortcut(app.name, app.appId, MutedText, Icons.Default.Apps, app.appType)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    viewModel: RemoteViewModel,
    onNavigateBack: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val connectionState = viewModel.connectionState
    val tvName = viewModel.currentTvName ?: "Samsung TV"

    var showKeyboardDialog by remember { mutableStateOf(false) }
    var keyboardInputText by remember { mutableStateOf(TextFieldValue("")) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showSecondaryPanel by remember { mutableStateOf(false) }
    var tvNameInput by remember { mutableStateOf("") }
    var tvIpInput by remember { mutableStateOf("") }
    var tvMacInput by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val availableApps = viewModel.installedApps.toList()
    val pinnedApps = remember(availableApps, viewModel.pinnedAppIds.toList()) {
        viewModel.pinnedAppIds.mapNotNull { pinnedId ->
            availableApps.firstOrNull { it.appId == pinnedId }
        }
    }
    val quickApps = if (pinnedApps.isNotEmpty()) {
        pinnedApps.map(::appShortcutFor)
    } else {
        defaultQuickApps()
    }

    val statusText = when {
        viewModel.isWaitingForWol -> "Waking TV..."
        connectionState == SamsungTvClient.State.CONNECTED -> "Connected"
        connectionState == SamsungTvClient.State.DISCONNECTED -> "Disconnected"
        connectionState == SamsungTvClient.State.CONNECTING -> "Connecting..."
        connectionState == SamsungTvClient.State.PAIRING -> "Pairing: Check TV!"
        connectionState == SamsungTvClient.State.ERROR -> "Connection Failed"
        else -> "Disconnected"
    }

    val statusColor = when {
        viewModel.isWaitingForWol -> AccentOrange
        connectionState == SamsungTvClient.State.CONNECTED -> GlowCyan
        connectionState == SamsungTvClient.State.DISCONNECTED -> MutedText
        connectionState == SamsungTvClient.State.CONNECTING -> AccentOrange
        connectionState == SamsungTvClient.State.PAIRING -> GlowPurple
        connectionState == SamsungTvClient.State.ERROR -> PowerPink
        else -> MutedText
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(if (showSettingsDialog) 20.dp else 0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(GlowPurple.copy(alpha = 0.12f), Color.Transparent),
                                radius = size.width * 0.7f
                            ),
                            center = Offset(size.width * 0.2f, size.height * 0.8f)
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(GlowCyan.copy(alpha = 0.12f), Color.Transparent),
                                radius = size.width * 0.7f
                            ),
                            center = Offset(size.width * 0.8f, size.height * 0.2f)
                        )
                    }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .systemBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onNavigateBack()
                    },
                    modifier = Modifier.background(GlassWhite, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = PureWhite
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        tvNameInput = viewModel.currentTvName ?: ""
                        tvIpInput = viewModel.currentTvIp ?: ""
                        tvMacInput = viewModel.currentTvMacAddress ?: ""
                        showSettingsDialog = true
                    }
                ) {
                    Text(
                        text = tvName,
                        color = PureWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                                .drawBehind {
                                    drawCircle(
                                        color = statusColor.copy(alpha = 0.5f),
                                        radius = size.maxDimension * 1.5f
                                    )
                                }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusText,
                            color = statusColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.forgetTv()
                        onNavigateBack()
                    },
                    modifier = Modifier.background(GlassWhite, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Forget",
                        tint = PowerPink
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedContent(
                targetState = showSecondaryPanel,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                transitionSpec = {
                    if (targetState) {
                        (slideInVertically(animationSpec = tween(260)) { fullHeight -> fullHeight } + fadeIn(animationSpec = tween(180))) togetherWith
                            (slideOutVertically(animationSpec = tween(260)) { fullHeight -> -fullHeight } + fadeOut(animationSpec = tween(180)))
                    } else {
                        (slideInVertically(animationSpec = tween(260)) { fullHeight -> -fullHeight } + fadeIn(animationSpec = tween(180))) togetherWith
                            (slideOutVertically(animationSpec = tween(260)) { fullHeight -> fullHeight } + fadeOut(animationSpec = tween(180)))
                    }
                },
                label = "KeyboardPanel"
            ) { secondaryPanel ->
                if (secondaryPanel) {
                    SecondaryKeyboardPanel(
                        modifier = Modifier.fillMaxSize(),
                        onBackToMain = { showSecondaryPanel = false },
                        onDigit = { digit -> viewModel.sendKey("KEY_$digit") },
                        onBackspace = { viewModel.sendKey("KEY_BACKSPACE") },
                        onReturn = { viewModel.sendKey("KEY_RETURN") }
                    )
                } else {
                    PrimaryKeyboardPanel(
                        modifier = Modifier.fillMaxSize(),
                        haptic = haptic,
                        connectionState = connectionState,
                        viewModel = viewModel,
                        quickApps = quickApps,
                        onOpenSecondary = { showSecondaryPanel = true },
                        onShowKeyboard = { showKeyboardDialog = true }
                    )
                }
            }

        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = if (showSettingsDialog) 0.28f else 0f))
        )

        if (showKeyboardDialog) {
            AlertDialog(
                onDismissRequest = { showKeyboardDialog = false },
                containerColor = Color(0xFF13131A),
                modifier = Modifier.border(1.dp, GlassBorder, RoundedCornerShape(28.dp)),
                title = {
                    Text(
                        text = "TV Keyboard Input",
                        color = PureWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Stream typed text directly into search fields on your TV.",
                            color = MutedText,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        OutlinedTextField(
                            value = keyboardInputText,
                            onValueChange = {
                                val oldVal = keyboardInputText.text
                                val newVal = it.text
                                keyboardInputText = it
                                
                                if (newVal.length > oldVal.length) {
                                    val addedChar = newVal.substring(oldVal.length)
                                    viewModel.sendText(addedChar)
                                } else if (newVal.length < oldVal.length) {
                                    viewModel.sendKey("KEY_BACKSPACE")
                                }
                            },
                            placeholder = { Text("Start typing...", color = MutedText.copy(alpha = 0.5f)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                focusManager.clearFocus()
                                showKeyboardDialog = false
                            }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = PureWhite,
                                unfocusedTextColor = PureWhite,
                                focusedBorderColor = GlowCyan,
                                unfocusedBorderColor = GlassBorder,
                                cursorColor = GlowCyan
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            keyboardInputText = TextFieldValue("")
                            showKeyboardDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GlowCyan)
                    ) {
                        Text("Done", color = ObsidianBg, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        LaunchedEffect(showSettingsDialog) {
            if (showSettingsDialog && connectionState == SamsungTvClient.State.CONNECTED && viewModel.installedApps.isEmpty() && !viewModel.isLoadingApps) {
                viewModel.refreshInstalledApps()
            }
        }

        // TV Settings Dialog
        if (showSettingsDialog) {
            Dialog(
                onDismissRequest = { showSettingsDialog = false },
                properties = DialogProperties(
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.92f)
                        .padding(horizontal = 20.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF1A1A24).copy(alpha = 0.72f),
                                    Color(0xFF0F0F15).copy(alpha = 0.92f)
                                )
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(28.dp))
                        .padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text("TV Settings", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "You can edit the TV connection here even when the TV is offline.",
                            color = MutedText,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = tvNameInput,
                            onValueChange = { tvNameInput = it },
                            label = { Text("TV Name") },
                            placeholder = { Text("Samsung TV") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = PureWhite,
                                unfocusedTextColor = PureWhite,
                                focusedBorderColor = GlowCyan,
                                unfocusedBorderColor = GlassBorder,
                                cursorColor = GlowCyan
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = tvIpInput,
                            onValueChange = { tvIpInput = it },
                            label = { Text("IP Address") },
                            placeholder = { Text("192.168.1.100") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = PureWhite,
                                unfocusedTextColor = PureWhite,
                                focusedBorderColor = GlowCyan,
                                unfocusedBorderColor = GlassBorder,
                                cursorColor = GlowCyan
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = tvMacInput,
                            onValueChange = { tvMacInput = it },
                            label = { Text("MAC Address (WOL)") },
                            placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = PureWhite,
                                unfocusedTextColor = PureWhite,
                                focusedBorderColor = GlowCyan,
                                unfocusedBorderColor = GlassBorder,
                                cursorColor = GlowCyan
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TextButton(
                                onClick = { showSettingsDialog = false },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cancel", color = MutedText)
                            }
                            Button(
                                onClick = {
                                    viewModel.currentTvIp?.let { ip ->
                                        if (tvNameInput != viewModel.currentTvName) {
                                            viewModel.setTvName(tvNameInput)
                                        }
                                        if (tvIpInput != viewModel.currentTvIp) {
                                            viewModel.setTvIp(ip, tvIpInput)
                                        }
                                        if (tvMacInput != viewModel.currentTvMacAddress) {
                                            viewModel.setTvMacAddress(tvMacInput)
                                        }
                                    }
                                    showSettingsDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GlowPurple),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Save", color = PureWhite, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        Divider(color = GlassBorder.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(20.dp))

                        Text("Quick Apps", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Pick which TV apps should appear in the bottom shortcut row.",
                            color = MutedText,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        if (connectionState != SamsungTvClient.State.CONNECTED) {
                            InfoBanner(
                                title = "TV offline",
                                body = "Connection settings can still be edited. App syncing will resume after the TV reconnects."
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { viewModel.refreshInstalledApps() },
                                enabled = connectionState == SamsungTvClient.State.CONNECTED && !viewModel.isLoadingApps,
                                colors = ButtonDefaults.buttonColors(containerColor = GlowCyan),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (viewModel.isLoadingApps) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = ObsidianBg
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text("Refresh apps", color = ObsidianBg, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { viewModel.setPinnedApps(emptyList()) },
                                enabled = viewModel.pinnedAppIds.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Clear pins", fontWeight = FontWeight.Bold)
                            }
                        }

                        if (viewModel.appLoadError != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            InfoBanner(
                                title = "App loading failed",
                                body = viewModel.appLoadError ?: "Unknown error"
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        val pinnedPreview = viewModel.pinnedAppIds.mapNotNull { pinnedId ->
                            availableApps.firstOrNull { it.appId == pinnedId }
                        }
                        if (pinnedPreview.isNotEmpty()) {
                            Text("Pinned shortcuts", color = MutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(pinnedPreview, key = { it.appId }) { app ->
                                    AppShortcutCapsule(app = appShortcutFor(app)) {
                                        viewModel.togglePinnedApp(app)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        Text(
                            text = if (availableApps.isEmpty()) "No installed apps loaded yet" else "Installed apps",
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (availableApps.isEmpty()) {
                            Text(
                                text = "Connect to the TV and tap Refresh apps. If the TV is offline, your connection details are still safe to edit.",
                                color = MutedText,
                                fontSize = 12.sp
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 320.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(availableApps, key = { it.appId }) { app ->
                                    AppSelectableRow(
                                        shortcut = appShortcutFor(app),
                                        pinned = viewModel.isPinned(app.appId),
                                        onToggle = { viewModel.togglePinnedApp(app) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    }
}

}

@Composable
fun RemoteCircleButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    glowColor: Color? = null,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(8.dp)
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(52.dp)
                .background(GlassWhite, CircleShape)
                .border(1.dp, GlassBorder, CircleShape)
                .drawBehind {
                    if (glowColor != null) {
                        drawCircle(
                            color = glowColor.copy(alpha = 0.15f),
                            radius = size.maxDimension * 0.7f
                        )
                    }
                }
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = MutedText,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun DpadDirectionButton(
    icon: ImageVector,
    modifier: Modifier,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(60.dp)
            .padding(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = PureWhite,
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
fun AppShortcutCapsule(
    app: AppShortcut,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(DarkCardBg)
            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = app.icon,
            contentDescription = app.name,
            tint = app.color,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = app.name,
            color = PureWhite,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@Composable
fun AppSelectableRow(
    shortcut: AppShortcut,
    pinned: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DarkCardBg)
            .border(1.dp, if (pinned) shortcut.color.copy(alpha = 0.7f) else GlassBorder, RoundedCornerShape(18.dp))
            .clickable { onToggle() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(shortcut.color.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = shortcut.icon,
                contentDescription = shortcut.name,
                tint = shortcut.color,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = shortcut.name,
                color = PureWhite,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Text(
                text = shortcut.id,
                color = MutedText,
                fontSize = 11.sp
            )
        }
        Checkbox(
            checked = pinned,
            onCheckedChange = { onToggle() }
        )
    }
}

@Composable
fun InfoBanner(
    title: String,
    body: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(GlassWhite)
            .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Text(title, color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(body, color = MutedText, fontSize = 12.sp)
    }
}

@Composable
fun PrimaryKeyboardPanel(
    modifier: Modifier = Modifier,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    connectionState: SamsungTvClient.State,
    viewModel: RemoteViewModel,
    quickApps: List<AppShortcut>,
    onOpenSecondary: () -> Unit,
    onShowKeyboard: () -> Unit
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    var dragDistance = 0f
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount ->
                            dragDistance += dragAmount
                        },
                        onDragEnd = {
                            if (dragDistance < -48f) {
                                onOpenSecondary()
                            }
                            dragDistance = 0f
                        },
                        onDragCancel = {
                            dragDistance = 0f
                        }
                    )
                }
        )

        Column(
            modifier = Modifier.matchParentSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkCardBg)
                    .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RemoteCircleButton(
                    icon = if (viewModel.isWaitingForWol) Icons.Default.Sync else Icons.Default.PowerSettingsNew,
                    label = if (viewModel.isWaitingForWol) "Waking..." else "Power",
                    tint = if (viewModel.isWaitingForWol) AccentOrange else PowerPink,
                    glowColor = if (viewModel.isWaitingForWol) AccentOrange else PowerPink,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (connectionState != SamsungTvClient.State.CONNECTED) {
                            viewModel.wakeTv()
                        } else {
                            viewModel.sendKey("KEY_POWER")
                        }
                    }
                )

                RemoteCircleButton(
                    icon = Icons.AutoMirrored.Filled.Input,
                    label = "Source",
                    tint = GlowCyan,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.sendKey("KEY_SOURCE")
                    }
                )

                RemoteCircleButton(
                    icon = Icons.Default.Keyboard,
                    label = "Keyboard",
                    tint = GlowPurple,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onShowKeyboard()
                    }
                )

                RemoteCircleButton(
                    icon = Icons.AutoMirrored.Filled.VolumeMute,
                    label = "Mute",
                    tint = MutedText,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.sendKey("KEY_MUTE")
                    }
                )
            }

            Spacer(modifier = Modifier.weight(0.5f))

            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(CircleShape)
                    .background(DarkCardBg)
                    .border(2.dp, GlassBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(GlowPurple.copy(alpha = 0.05f), Color.Transparent),
                                    radius = size.width * 0.5f
                                ),
                                center = Offset(size.width * 0.5f, size.height * 0.5f)
                            )
                        }
                )

                DpadDirectionButton(
                    icon = Icons.Default.KeyboardArrowUp,
                    modifier = Modifier.align(Alignment.TopCenter),
                    contentDescription = "Up",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.sendKey("KEY_UP")
                    }
                )

                DpadDirectionButton(
                    icon = Icons.Default.KeyboardArrowDown,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    contentDescription = "Down",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.sendKey("KEY_DOWN")
                    }
                )

                DpadDirectionButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    modifier = Modifier.align(Alignment.CenterStart),
                    contentDescription = "Left",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.sendKey("KEY_LEFT")
                    }
                )

                DpadDirectionButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    contentDescription = "Right",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.sendKey("KEY_RIGHT")
                    }
                )

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(GlowCyan.copy(alpha = 0.9f), GlowPurple.copy(alpha = 0.9f))
                            )
                        )
                        .border(1.dp, GlassBorder, CircleShape)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.sendKey("KEY_ENTER")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "OK",
                        color = ObsidianBg,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.5f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .width(72.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(DarkCardBg)
                        .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.sendKey("KEY_VOLUP")
                    }) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Vol Up", tint = GlowCyan)
                    }
                    Text(
                        text = "VOL",
                        color = MutedText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.sendKey("KEY_VOLDOWN")
                    }) {
                        Icon(imageVector = Icons.Default.Remove, contentDescription = "Vol Down", tint = GlowCyan)
                    }
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.sendKey("KEY_RETURN")
                        },
                        modifier = Modifier
                            .size(56.dp)
                            .background(DarkCardBg, CircleShape)
                            .border(1.dp, GlassBorder, CircleShape)
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Undo, contentDescription = "Back", tint = PureWhite)
                    }

                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.sendKey("KEY_HOME")
                        },
                        modifier = Modifier
                            .size(56.dp)
                            .background(DarkCardBg, CircleShape)
                            .border(1.dp, GlassBorder, CircleShape)
                    ) {
                        Icon(imageVector = Icons.Default.Home, contentDescription = "Home", tint = PureWhite)
                    }
                }

                Column(
                    modifier = Modifier
                        .width(72.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(DarkCardBg)
                        .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.sendKey("KEY_CHUP")
                    }) {
                        Icon(imageVector = Icons.Default.KeyboardArrowUp, contentDescription = "CH Up", tint = GlowPurple)
                    }
                    Text(
                        text = "CH",
                        color = MutedText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.sendKey("KEY_CHDOWN")
                    }) {
                        Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "CH Down", tint = GlowPurple)
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.5f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = "QUICK APPS",
                    color = MutedText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(quickApps) { app ->
                        AppShortcutCapsule(app = app) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.launchApp(app.id, app.appType)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SecondaryKeyboardPanel(
    modifier: Modifier = Modifier,
    onBackToMain: () -> Unit,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onReturn: () -> Unit
) {
    Column(
        modifier = modifier
            .pointerInput(Unit) {
                var dragDistance = 0f
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        dragDistance += dragAmount
                    },
                    onDragEnd = {
                        if (dragDistance > 48f) {
                            onBackToMain()
                        }
                        dragDistance = 0f
                    },
                    onDragCancel = {
                        dragDistance = 0f
                    }
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "NUMPAD",
                color = PureWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Swipe down to return",
                color = MutedText,
                fontSize = 11.sp
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            NumericKeypad(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                onDigit = onDigit,
                onBackspace = onBackspace,
                onReturn = onReturn
            )
        }

        TextButton(onClick = onBackToMain) {
            Text("Back to main", color = GlowCyan, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun NumericKeypad(
    modifier: Modifier = Modifier,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onReturn: () -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        NumericKeyRow(buttons = listOf("1", "2", "3"), onDigit = onDigit)
        NumericKeyRow(buttons = listOf("4", "5", "6"), onDigit = onDigit)
        NumericKeyRow(buttons = listOf("7", "8", "9"), onDigit = onDigit)
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeypadActionButton(
                icon = Icons.Default.Backspace,
                contentDescription = "Backspace",
                tint = GlowPurple,
                onClick = onBackspace
            )
            KeypadDigitButton(label = "0", onClick = { onDigit("0") })
            KeypadActionButton(
                icon = Icons.Default.KeyboardReturn,
                contentDescription = "Return",
                tint = GlowCyan,
                onClick = onReturn
            )
        }
    }
}

@Composable
private fun NumericKeyRow(
    buttons: List<String>,
    onDigit: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        buttons.forEach { digit ->
            KeypadDigitButton(
                label = digit,
                onClick = { onDigit(digit) }
            )
        }
    }
}

@Composable
private fun KeypadDigitButton(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(84.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(GlassWhite)
            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = PureWhite,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun KeypadActionButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(84.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(GlassWhite)
            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(32.dp)
        )
    }
}
