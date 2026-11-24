/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.deviceconnect

import kotlinx.coroutines.CancellationException

/**
 * Ensures the cross-device sync HTTP endpoint (/sync/v1) is always listening
 * on a known port (default 8765) on JVM platforms (Android & Desktop),
 * independent of whether DeviceConnect playback remote is enabled.
 */
object CrossDeviceSyncServer {
    private val lock = Any()
    private var server: LocalDeviceServer? = null

    @Volatile
    var activePort: Int = 8765
        private set

    fun ensureStarted(preferredPort: Int = 8765): Int {
        synchronized(lock) {
            if (server != null) return activePort
            val srv = LocalDeviceServer()
            val boundPort =
                runCatching {
                    srv.start(
                        port = preferredPort,
                        onFailure = {},
                        onChannel = {},
                    )
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    runCatching { srv.stop() }
                    runCatching {
                        srv.start(
                            port = 0,
                            onFailure = {},
                            onChannel = {},
                        )
                    }.getOrDefault(-1)
                }
            if (boundPort > 0) {
                server = srv
                activePort = boundPort
                return boundPort
            }
            return preferredPort
        }
    }

    fun availableEndpoints(): List<String> = getAvailableSyncEndpoints(activePort)
}

