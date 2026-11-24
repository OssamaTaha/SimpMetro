/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.metrolist.desktop.deviceconnect.DesktopDeviceConnect
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.utils.pickDesktopFile
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.library_sync_clipboard_empty
import com.metrolist.shared.generated.resources.library_sync_qr_not_found
import com.metrolist.shared.generated.resources.library_sync_scan_qr_image
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.settings.DeviceConnectSettingsHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import javax.imageio.ImageIO

@Composable
internal fun rememberDeviceConnectSettingsHost(): DeviceConnectSettingsHost {
    val manager = DesktopDeviceConnect.manager
    val scope = rememberCoroutineScope()
    val scanQrTitle = stringResource(Res.string.library_sync_scan_qr_image)
    val qrNotFoundMessage = stringResource(Res.string.library_sync_qr_not_found)
    val clipboardEmptyMessage = stringResource(Res.string.library_sync_clipboard_empty)
    LaunchedEffect(Settings.shared.deviceConnectEnabled) {
        if (Settings.shared.deviceConnectEnabled) manager.refreshPairingState()
    }
    return DeviceConnectSettingsHost(
        state = manager.state,
        playbackState = manager.remote.state,
        onEnabledChange = { enabled ->
            if (enabled) scope.launch { manager.enable(Unit) } else manager.disable()
        },
        onUpdateName = manager::updateDeviceName,
        onRescan = manager::rescan,
        onConnectDirect = manager::connectDirect,
        onPair = manager::pair,
        onCancelPairing = manager::rejectPairing,
        onForget = { id -> scope.launch { manager.forget(id) } },
        onScanQr = { callback ->
            scope.launch {
                val picked = pickDesktopFile(scanQrTitle, listOf("png", "jpg", "jpeg", "webp"))
                if (picked != null) {
                    val decoded =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val image = ImageIO.read(picked) ?: return@runCatching null
                                val w = image.width
                                val h = image.height
                                val pixels = image.getRGB(0, 0, w, h, null, 0, w)
                                val source = RGBLuminanceSource(w, h, pixels)
                                val bitmap = BinaryBitmap(HybridBinarizer(source))
                                MultiFormatReader()
                                    .decode(
                                        bitmap,
                                        mapOf(DecodeHintType.TRY_HARDER to true),
                                    ).text
                            }.getOrNull()
                        }
                    if (decoded != null) {
                        callback(decoded)
                    } else {
                        AppState.snackbarHostState.showSnackbar(qrNotFoundMessage)
                    }
                }
            }
        },
        onPasteAddress = {
            val clipText =
                runCatching {
                    Toolkit
                        .getDefaultToolkit()
                        .systemClipboard
                        .getData(DataFlavor.stringFlavor) as? String
                }.getOrNull()?.trim().orEmpty()
            if (clipText.startsWith("http://") || clipText.startsWith("https://") || (':' in clipText && '.' in clipText)) {
                clipText
            } else {
                scope.launch { AppState.snackbarHostState.showSnackbar(clipboardEmptyMessage) }
                null
            }
        },
    )
}
