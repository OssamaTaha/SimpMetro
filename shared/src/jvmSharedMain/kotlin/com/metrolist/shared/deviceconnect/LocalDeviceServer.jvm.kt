/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * JVM local WebSocket server for DeviceConnect.
 */

package com.metrolist.shared.deviceconnect

import com.metrolist.shared.settings.Settings
import com.metrolist.shared.sync.CrossDeviceSyncEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

actual class LocalDeviceServer {
    private val lock = Any()
    private var engine: EmbeddedServer<*, *>? = null
    private var serverScope: CoroutineScope? = null

    @Volatile
    private var generation = 0L

    actual fun start(
        port: Int,
        onFailure: (Throwable) -> Unit,
        onChannel: suspend (DeviceChannel) -> Unit,
    ): Int {
        stop()
        val currentGeneration = synchronized(lock) { ++generation }
        val scope =
            CoroutineScope(
                SupervisorJob() +
                    CoroutineExceptionHandler { _, error ->
                        if (generation == currentGeneration) onFailure(error)
                    },
            )
        val config =
            serverConfig {
                parentCoroutineContext = scope.coroutineContext
                developmentMode = false
                watchPaths = emptyList()
                module {
                    install(WebSockets) { maxFrameSize = DEVICE_CONNECT_MAX_FRAME_BYTES }
                    routing {
                        webSocket("/connect") {
                            val channel = ServerDeviceChannel(this)
                            if (!isDeviceConnectAllowedClientAddress(channel.remoteHost)) {
                                channel.close()
                                return@webSocket
                            }
                            onChannel(channel)
                        }
                        post("/sync/v1") {
                            val clientAddress = call.request.local.remoteAddress
                            if (!isDeviceConnectAllowedClientAddress(clientAddress)) {
                                call.respond(HttpStatusCode.Forbidden, "Forbidden")
                                return@post
                            }
                            val incomingText = call.receiveText()
                            val db = CrossDeviceSyncEngine.localDatabaseProvider?.invoke()
                            if (db == null) {
                                call.respond(HttpStatusCode.ServiceUnavailable, "Database unavailable")
                                return@post
                            }
                            val downloaded = CrossDeviceSyncEngine.localDownloadsProvider?.invoke().orEmpty()
                            val responseText =
                                CrossDeviceSyncEngine.handleIncomingSync(
                                    incomingJson = incomingText,
                                    database = db,
                                    deviceId = Settings.shared.deviceConnectDeviceId,
                                    deviceName = Settings.shared.deviceConnectDeviceName,
                                    downloadedIds = downloaded,
                                )
                            call.respondText(responseText, ContentType.Application.Json)
                        }
                    }
                }
            }
        val server =
            embeddedServer(CIO, config) {
                connector {
                    host = "::"
                    this.port = port
                }
            }
        try {
            server.start(wait = false)
            val actualPort =
                runBlocking {
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                }
            synchronized(lock) {
                check(generation == currentGeneration) { "Server start was cancelled." }
                engine = server
                serverScope = scope
            }
            return actualPort
        } catch (error: Throwable) {
            synchronized(lock) {
                if (generation == currentGeneration) generation++
            }
            runCatching { server.stop(0, 0) }
            scope.cancel()
            throw error
        }
    }

    actual fun stop() {
        val (stoppingEngine, stoppingScope) =
            synchronized(lock) {
                generation++
                (engine to serverScope).also {
                    engine = null
                    serverScope = null
                }
            }
        try {
            stoppingEngine?.stop(1000, 2000)
        } finally {
            stoppingScope?.cancel()
        }
    }

    private class ServerDeviceChannel(
        private val session: DefaultWebSocketServerSession,
    ) : DeviceChannel {
        override val remoteHost: String? =
            runCatching { session.call.request.local.remoteAddress }.getOrNull()

        override val incoming =
            flow {
                session.incoming.consumeEach { frame ->
                    if (frame is Frame.Text) emit(frame.readText())
                }
            }

        override suspend fun send(text: String) {
            session.send(Frame.Text(text))
        }

        override suspend fun close() {
            runCatching { session.close() }
        }
    }
}
