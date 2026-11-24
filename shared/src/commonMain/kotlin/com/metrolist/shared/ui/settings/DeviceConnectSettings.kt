/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.metrolist.shared.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.deviceconnect.DcSyncState
import com.metrolist.shared.deviceconnect.DeviceConnectDirectConnection
import com.metrolist.shared.deviceconnect.DeviceConnectManager
import com.metrolist.shared.deviceconnect.DeviceConnectPlaybackStatus
import com.metrolist.shared.deviceconnect.deviceConnectDeviceMetadata
import com.metrolist.shared.deviceconnect.deviceConnectPeerStatus
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.sync.CrossDeviceSyncEngine
import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

data class DeviceConnectSettingsHost(
    val state: StateFlow<DeviceConnectManager.State>,
    val playbackState: StateFlow<DcSyncState>,
    val permissionRequired: Boolean = false,
    val onEnabledChange: (Boolean) -> Unit,
    val onRequestPermission: () -> Unit = {},
    val onUpdateName: (String) -> Unit,
    val onRescan: () -> Unit,
    val onConnectDirect: (String) -> Unit,
    val onPair: (String) -> Unit,
    val onCancelPairing: (String) -> Unit,
    val onForget: (String) -> Unit,
)

@Composable
@NonRestartableComposable
internal fun SettingsUiRegistryBuilder.deviceConnectPage(host: DeviceConnectSettingsHost) {
    // State changes must rebuild the registry that owns these rows.
    val state by host.state.collectAsState()
    val playbackState by host.playbackState.collectAsState()
    val enabled = Settings.shared.deviceConnectEnabled
    val pairedIds = state.pairedDevices.mapTo(mutableSetOf()) { it.deviceId }
    val visiblePeerIds = state.peers.mapTo(mutableSetOf()) { it.id }
    val offlinePairedDevices = state.pairedDevices.filterNot { it.deviceId in visiblePeerIds }

    page(
        id = SettingsPageId.DEVICE_CONNECT,
        title = stringResource(Res.string.device_connect_title),
        subtitle = settingsPageSubtitle(stringResource(Res.string.settings_device_connect_overview), integrationStatus(enabled)),
        icon = MetrolistIcons.Outlined.AccountTree,
        tone = SettingsTone.BLUE,
    ) {
        group("deviceConnectMaster", "") {
            mainSwitch(
                id = "deviceConnectEnabled",
                title = stringResource(Res.string.device_connect_enable),
                checked = enabled,
                description = stringResource(Res.string.device_connect_summary),
                onCheckedChange = host.onEnabledChange,
            )
        }
        group("deviceConnectSettings", stringResource(Res.string.settings), enabled = enabled) {
            text(
                id = "deviceConnectName",
                title = stringResource(Res.string.device_connect_name_label),
                description = Settings.shared.deviceConnectDeviceName.ifBlank { state.localDeviceName },
                value = Settings.shared.deviceConnectDeviceName,
                placeholder = stringResource(Res.string.device_connect_name_hint),
            ) { host.onUpdateName(it.trim()) }
            if (state.serverRunning) {
                action(
                    id = "deviceConnectThisDevice",
                    title = stringResource(Res.string.device_connect_this_device_label),
                    description = deviceConnectDeviceMetadata(state.localDeviceType),
                    enabled = false,
                ) {}
            }
            if (host.permissionRequired) {
                action(
                    id = "deviceConnectPermission",
                    title = stringResource(Res.string.device_connect_grant_permission),
                    description = stringResource(Res.string.device_connect_permission_needed),
                    onClick = host.onRequestPermission,
                )
            }
            if (state.serverFailed) {
                action(
                    id = "deviceConnectRetry",
                    title = stringResource(Res.string.retry),
                    description = stringResource(Res.string.device_connect_server_failed),
                ) { host.onEnabledChange(true) }
            }
            action(
                id = "deviceConnectRescan",
                title = stringResource(Res.string.device_connect_rescan),
                description = if (state.discoveryFailed) stringResource(Res.string.device_connect_discovery_failed) else null,
                enabled = state.serverRunning,
                onClick = host.onRescan,
            )
            if (playbackState.error != null || (playbackState.sessionId != null && !playbackState.playbackAvailable)) {
                custom("deviceConnectPlaybackStatus", stringResource(Res.string.device_connect_title)) {
                    Column(Modifier.padding(16.dp)) { DeviceConnectPlaybackStatus(playbackState) }
                }
            }
            if (state.peers.isEmpty() && offlinePairedDevices.isEmpty() && !state.serverFailed && !state.discoveryFailed) {
                action(
                    id = "deviceConnectNoDevices",
                    title = stringResource(Res.string.device_connect_no_devices),
                    enabled = false,
                ) {}
            }
            state.peers.forEach { peer ->
                val paired = peer.id in pairedIds
                val pairingRequest = state.pairingRequests.firstOrNull { it.deviceId == peer.id }
                val status =
                    deviceConnectPeerStatus(
                        peer,
                        paired,
                        pairingRequest,
                        showConnected = true,
                        showAvailable =
                            peer.state != DeviceConnectManager.PeerState.DISCONNECTED,
                    )
                action(
                    id = "deviceConnectPeer:${peer.id}",
                    title = peer.name.ifBlank { peer.id },
                    description =
                        stringResource(
                            Res.string.device_connect_peer_details,
                            deviceConnectDeviceMetadata(peer.deviceType, peer.model),
                            status,
                        ),
                    valueLabel = stringResource(if (paired) Res.string.device_connect_forget else Res.string.device_connect_pair),
                    enabled =
                        paired || (
                            pairingRequest == null && peer.state != DeviceConnectManager.PeerState.CONNECTING &&
                                peer.state != DeviceConnectManager.PeerState.HANDSHAKING &&
                                peer.state != DeviceConnectManager.PeerState.CONNECTED &&
                                peer.state != DeviceConnectManager.PeerState.DISCONNECTED &&
                                peer.state != DeviceConnectManager.PeerState.ACCOUNT_MISMATCH
                        ),
                    confirmationMessage = if (paired) stringResource(Res.string.device_connect_forget_message) else null,
                ) {
                    if (paired) host.onForget(peer.id) else host.onPair(peer.id)
                }
                if (pairingRequest != null) {
                    action(
                        id = "deviceConnectCancel:${peer.id}",
                        title = stringResource(Res.string.cancel),
                        description = peer.name.ifBlank { peer.id },
                    ) { host.onCancelPairing(pairingRequest.requestId) }
                }
            }
            offlinePairedDevices.forEach { device ->
                action(
                    id = "deviceConnectOffline:${device.deviceId}",
                    title = device.deviceName.ifBlank { device.deviceId },
                    description =
                        stringResource(
                            Res.string.device_connect_peer_details,
                            deviceConnectDeviceMetadata(device.deviceType, device.model),
                            stringResource(Res.string.device_connect_offline),
                        ),
                    valueLabel = stringResource(Res.string.device_connect_forget),
                    confirmationMessage = stringResource(Res.string.device_connect_forget_message),
                ) { host.onForget(device.deviceId) }
            }
        }
        group("librarySyncGroup", stringResource(Res.string.library_sync), enabled = enabled) {
            val syncResult by CrossDeviceSyncEngine.lastSyncResult.collectAsState()
            val isSyncing by CrossDeviceSyncEngine.isSyncing.collectAsState()

            action(
                id = "librarySyncSummary",
                title = stringResource(Res.string.library_sync),
                description = stringResource(Res.string.library_sync_summary),
                enabled = false,
            ) {}

            var syncAddressInput by remember { mutableStateOf("") }
            val syncScope = rememberCoroutineScope()

            custom("librarySyncCustomDirect", stringResource(Res.string.library_sync)) {
                val localEndpoints = remember {
                    CrossDeviceSyncEngine.localEndpointsProvider?.invoke().orEmpty()
                }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (localEndpoints.isNotEmpty()) {
                        Text(
                            "This Device Address: ${localEndpoints.first()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        stringResource(Res.string.library_sync_peer_address),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedTextField(
                        value = syncAddressInput,
                        onValueChange = { syncAddressInput = it },
                        placeholder = { Text(stringResource(Res.string.library_sync_peer_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            val db = CrossDeviceSyncEngine.localDatabaseProvider?.invoke() ?: return@Button
                            syncScope.launch {
                                val downloaded = CrossDeviceSyncEngine.localDownloadsProvider?.invoke().orEmpty()
                                CrossDeviceSyncEngine.syncWithPeer(
                                    peerEndpoint = syncAddressInput,
                                    database = db,
                                    deviceId = Settings.shared.deviceConnectDeviceId,
                                    deviceName = Settings.shared.deviceConnectDeviceName,
                                    downloadedIds = downloaded,
                                )
                            }
                        },
                        enabled = syncAddressInput.isNotBlank() && !isSyncing,
                        modifier = Modifier.fillMaxWidth(),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(
                            if (isSyncing) {
                                stringResource(Res.string.library_sync_syncing)
                            } else {
                                stringResource(Res.string.library_sync_connect_and_sync)
                            },
                        )
                    }
                    if (syncResult != null) {
                        val res = syncResult!!
                        if (res.isSuccess) {
                            Text(
                                stringResource(
                                    Res.string.library_sync_success,
                                    res.songsMerged,
                                    res.playlistsMerged,
                                    res.downloadsQueued,
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                res.errorMessage ?: stringResource(Res.string.library_sync_failed),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
        group("deviceConnectAdvanced", stringResource(Res.string.advanced), enabled = enabled) {
            custom("deviceConnectDirect", stringResource(Res.string.device_connect_direct_title), enabled = state.serverRunning) {
                if (enabled) {
                    Column(Modifier.padding(16.dp)) {
                        if (state.serverRunning) {
                            DeviceConnectDirectConnection(state, host.onConnectDirect)
                        } else {
                            Text(stringResource(Res.string.device_connect_direct_title))
                        }
                    }
                }
            }
            action(
                id = "deviceConnectInfo",
                title = stringResource(Res.string.device_connect_beta),
                description = if (state.anonymous) stringResource(Res.string.device_connect_signed_out_pairing) else null,
                enabled = false,
            ) {}
        }
    }
}
