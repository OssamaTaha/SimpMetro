/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Device Connect controls and paired devices.
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import com.metrolist.android.deviceconnect.AndroidDeviceConnect
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.ui.component.AdaptiveModalBottomSheet
import com.metrolist.android.ui.components.QrCameraScannerDialog
import com.metrolist.android.ui.components.QrDecoder
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.library_sync_camera_denied
import com.metrolist.shared.generated.resources.library_sync_clipboard_empty
import com.metrolist.shared.generated.resources.library_sync_qr_not_found
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.ConnectedDevicesContent
import com.metrolist.shared.ui.settings.DeviceConnectSettingsHost
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import com.metrolist.shared.ui.components.rememberModalSheetState as rememberBottomSheetState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerDeviceConnectSheet(
    onDismissRequest: () -> Unit,
    onNavigateToIntegrations: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current
    val castState = playerConnection?.castState?.collectAsState()?.value ?: CastState()
    val castDevices =
        playerConnection
            ?.castDevices
            ?.collectAsState()
            ?.value
            .orEmpty()
    LifecycleStartEffect(playerConnection, Settings.shared.castEnabled) {
        if (Settings.shared.castEnabled) playerConnection?.startCastDiscovery()
        onStopOrDispose { playerConnection?.stopCastDiscovery() }
    }
    val context = LocalContext.current
    val manager = AndroidDeviceConnect.manager
    val state by manager.state.collectAsState()
    val playbackState by manager.remote.state.collectAsState()
    val scope = rememberCoroutineScope()
    val requiredPermission = AndroidDeviceConnect.requiredLocalNetworkPermission()
    var permissionGranted by remember { mutableStateOf(AndroidDeviceConnect.canUseLocalNetwork(context)) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGranted = granted
            if (granted) scope.launch { manager.enable(context.applicationContext) }
        }

    LaunchedEffect(Unit) {
        manager.refreshPairingState()
        if (Settings.shared.deviceConnectEnabled && permissionGranted && !manager.state.value.enabled) {
            manager.restoreIfEnabled(context.applicationContext)
        }
    }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState =
            rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
    ) {
        ConnectedDevicesContent(
            modifier = Modifier.fillMaxHeight(),
            devices = castDevices,
            castState = castState,
            onDeviceClick = { playerConnection?.castTo(it) },
            onDisconnectCast = { playerConnection?.disconnectCast() },
            deviceState = state,
            syncState = playbackState,
            onEnableDeviceConnect = {
                if (permissionGranted || requiredPermission == null) {
                    scope.launch { manager.enable(context.applicationContext) }
                } else {
                    launcher.launch(requiredPermission)
                }
            },
            onTransferTo = manager.remote::transferTo,
            onClose = onDismissRequest,
            permissionRequired = !permissionGranted && requiredPermission != null,
            onOpenIntegrations = onNavigateToIntegrations,
        )
    }
}

@Composable
fun rememberDeviceConnectSettingsHost(): DeviceConnectSettingsHost {
    val context = LocalContext.current
    val manager = AndroidDeviceConnect.manager
    val scope = rememberCoroutineScope()
    val requiredPermission = AndroidDeviceConnect.requiredLocalNetworkPermission()
    var permissionGranted by remember { mutableStateOf(AndroidDeviceConnect.canUseLocalNetwork(context)) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGranted = granted
            if (granted && !manager.state.value.enabled) scope.launch { manager.enable(context.applicationContext) }
        }
    LaunchedEffect(Settings.shared.deviceConnectEnabled) {
        if (Settings.shared.deviceConnectEnabled) manager.refreshPairingState()
    }
    var showQrScanner by remember { mutableStateOf(false) }
    var pendingQrCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val qrNotFoundMessage = stringResource(Res.string.library_sync_qr_not_found)
    val cameraDeniedMessage = stringResource(Res.string.library_sync_camera_denied)
    val clipboardEmptyMessage = stringResource(Res.string.library_sync_clipboard_empty)

    val qrImagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                val decoded = QrDecoder.decodeUri(context, uri)
                if (decoded != null) {
                    pendingQrCallback?.invoke(decoded)
                    pendingQrCallback = null
                } else {
                    Toast.makeText(context, qrNotFoundMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showQrScanner = true
            } else {
                Toast.makeText(context, cameraDeniedMessage, Toast.LENGTH_SHORT).show()
            }
        }
    if (showQrScanner) {
        QrCameraScannerDialog(
            onQrDecoded = { decoded ->
                showQrScanner = false
                pendingQrCallback?.invoke(decoded)
                pendingQrCallback = null
            },
            onPickFromGallery = { qrImagePicker.launch("image/*") },
            onDismiss = {
                showQrScanner = false
                pendingQrCallback = null
            },
        )
    }
    return DeviceConnectSettingsHost(
        state = manager.state,
        playbackState = manager.remote.state,
        permissionRequired = !permissionGranted && requiredPermission != null,
        onEnabledChange = { enabled ->
            if (!enabled) {
                manager.disable()
            } else if (AndroidDeviceConnect.canUseLocalNetwork(context) || requiredPermission == null) {
                scope.launch { manager.enable(context.applicationContext) }
            } else {
                launcher.launch(requiredPermission)
            }
        },
        onRequestPermission = { requiredPermission?.let(launcher::launch) },
        onUpdateName = manager::updateDeviceName,
        onRescan = manager::rescan,
        onConnectDirect = manager::connectDirect,
        onPair = manager::pair,
        onCancelPairing = manager::rejectPairing,
        onForget = { id -> scope.launch { manager.forget(id) } },
        onScanQr = { callback ->
            pendingQrCallback = callback
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                showQrScanner = true
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        },
        onPasteAddress = {
            val clip = QrDecoder.getClipboardEndpoint(context)
            if (clip == null) {
                Toast.makeText(context, clipboardEmptyMessage, Toast.LENGTH_SHORT).show()
            }
            clip
        },
    )
}
