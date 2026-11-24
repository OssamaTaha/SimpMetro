package com.metrolist.shared.sync

import com.metrolist.shared.auth.AuthManager
import com.metrolist.shared.constants.PlaylistSortType
import com.metrolist.shared.constants.SongSortType
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.upsertHistorySong
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.settings.Settings
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

object CrossDeviceSyncEngine {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient by lazy { createHttpClient() }

    private val _lastSyncResult = MutableStateFlow<CrossDeviceSyncResult?>(null)
    val lastSyncResult = _lastSyncResult.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()

    var onNewDownloadsRequested: ((List<String>) -> Unit)? = null
    var localDatabaseProvider: (() -> MusicDatabase)? = null
    var localDownloadsProvider: (suspend () -> List<String>)? = null
    var localEndpointsProvider: (() -> List<String>)? = null

    suspend fun exportPayload(
        database: MusicDatabase,
        deviceId: String,
        deviceName: String,
        downloadedIds: List<String> = emptyList(),
    ): CrossDeviceSyncPayload =
        withContext(Dispatchers.Default) {
            val dao = database.dao()
            val now = Clock.System.now().toEpochMilliseconds()

            val librarySongs = dao.songs(SongSortType.CREATE_DATE, descending = false).first()
            val likedSongs = dao.likedSongs(SongSortType.CREATE_DATE, descending = false).first()

            val rawPlaylists = dao.playlists(PlaylistSortType.CREATE_DATE, descending = false).first()
            val allPlaylistSongIds = mutableSetOf<String>()
            val playlistsList =
                rawPlaylists.map { p ->
                    val songs = dao.playlistSongMaps(p.playlist.id, 0).map { it.songId }
                    allPlaylistSongIds.addAll(songs)
                    SyncPlaylist(
                        id = p.playlist.id,
                        name = p.playlist.name,
                        browseId = p.playlist.browseId,
                        isLocal = p.playlist.isLocal,
                        songIds = songs,
                        description = p.playlist.description,
                        thumbnailUrl = p.playlist.thumbnailUrl,
                    )
                }

            val songsMap = (librarySongs + likedSongs).distinctBy { it.id }.associateBy { it.id }.toMutableMap()
            val extraIds = (downloadedIds + allPlaylistSongIds).filterNot { it in songsMap }.distinct()
            extraIds.forEach { id ->
                dao.getSongById(id)?.let { songsMap[id] = it }
            }

            val songsList =
                songsMap.values.map { s ->
                    SyncSong(
                        id = s.id,
                        title = s.title,
                        artistsText = s.orderedArtists.joinToString(", ") { it.name },
                        albumText = s.album?.title,
                        thumbnailUrl = s.thumbnailUrl,
                        durationSeconds = s.song.duration,
                        liked = s.song.liked,
                        likedAt = s.song.likedDate?.let { toEpochMillis(it) },
                        inLibrary = s.song.inLibrary != null,
                        inLibraryAt = s.song.inLibrary?.let { toEpochMillis(it) },
                        totalPlayTimeMs = s.song.totalPlayTime,
                        isDownloaded = downloadedIds.contains(s.id),
                    )
                }

            val eventsList =
                dao.events().first().take(1000).map { e ->
                    SyncHistoryEntry(
                        songId = e.song.id,
                        title = e.song.title,
                        artistsText = e.song.artists.joinToString(", ") { it.name },
                        albumText = e.song.album?.title,
                        thumbnailUrl = e.song.thumbnailUrl,
                        playedAt = toEpochMillis(e.event.timestamp),
                        playTimeMs = e.event.playTime,
                    )
                }

            val artistsList =
                dao.artistsBookmarkedByCreateDateAsc().first().map { a ->
                    SyncBookmark(
                        id = a.id,
                        title = a.artist.name,
                        thumbnailUrl = a.artist.thumbnailUrl,
                        bookmarkedAt = a.artist.bookmarkedAt?.let { toEpochMillis(it) } ?: now,
                    )
                }

            val albumsList =
                dao.albumsLikedByCreateDateAsc(includeSaved = true).first().map { a ->
                    SyncBookmark(
                        id = a.id,
                        title = a.album.title,
                        thumbnailUrl = a.album.thumbnailUrl,
                        bookmarkedAt = a.album.bookmarkedAt?.let { toEpochMillis(it) } ?: now,
                    )
                }

            val authSession =
                if (Settings.shared.innerTubeCookie.isNotBlank()) {
                    SyncAuthSession(
                        cookie = Settings.shared.innerTubeCookie,
                        visitorData = Settings.shared.visitorData,
                        dataSyncId = Settings.shared.dataSyncId,
                        authUser = Settings.shared.innerTubeAuthUser,
                        accountName = Settings.shared.accountName,
                        accountEmail = Settings.shared.accountEmail,
                        accountChannelHandle = Settings.shared.accountChannelHandle,
                        accountThumbnail = Settings.shared.accountThumbnail,
                    )
                } else {
                    null
                }

            CrossDeviceSyncPayload(
                deviceId = deviceId,
                deviceName = deviceName,
                timestamp = now,
                songs = songsList,
                playlists = playlistsList,
                history = eventsList,
                artists = artistsList,
                albums = albumsList,
                downloadedSongIds = downloadedIds,
                authCookie = authSession?.cookie,
                authSession = authSession,
            )
        }

    suspend fun mergePayload(
        database: MusicDatabase,
        payload: CrossDeviceSyncPayload,
        onDownloadsDiscovered: (List<String>) -> Unit = { onNewDownloadsRequested?.invoke(it) },
    ): CrossDeviceSyncResult =
        withContext(Dispatchers.Default) {
            var songsCount = 0
            var playlistCount = 0
            var historyCount = 0
            val now = Clock.System.now().toEpochMilliseconds()

            database.withDatabaseTransaction {
                val dao = database.dao()
                payload.songs.forEach { s ->
                    val existing = dao.getSongById(s.id)
                    if (existing == null) {
                        dao.upsertHistorySong(
                            SongItem(
                                id = s.id,
                                title = s.title,
                                thumbnail = s.thumbnailUrl,
                                artists =
                                    s.artistsText
                                        .split(", ")
                                        .filter { it.isNotBlank() }
                                        .map { ArtistTiny(id = null, name = it) },
                                album = s.albumText?.let { AlbumTiny(id = null, name = it) },
                                duration = s.durationSeconds?.toLong(),
                            ),
                        )
                    }
                    val stored = checkNotNull(dao.getSongById(s.id)).song
                    val likedAt = s.likedAt ?: now
                    val merged =
                        stored.copy(
                            liked = stored.liked || s.liked,
                            likedDate = stored.likedDate ?: likedAt.takeIf { s.liked }?.let { toLocalDateTime(it) },
                            inLibrary =
                                stored.inLibrary ?: (
                                    s.inLibraryAt ?: likedAt.takeIf {
                                        s.liked || s.inLibrary
                                    }
                                )?.let { toLocalDateTime(it) },
                            totalPlayTime = maxOf(stored.totalPlayTime, s.totalPlayTimeMs),
                        )
                    if (merged != stored) {
                        dao.update(merged)
                        songsCount++
                    }
                }

                payload.history.forEach { h ->
                    val existing = dao.getSongById(h.songId)
                    if (existing == null) {
                        dao.upsertHistorySong(
                            SongItem(
                                id = h.songId,
                                title = h.title,
                                thumbnail = h.thumbnailUrl,
                                artists =
                                    h.artistsText
                                        .split(", ")
                                        .filter { it.isNotBlank() }
                                        .map { ArtistTiny(id = null, name = it) },
                                album = h.albumText?.let { AlbumTiny(id = null, name = it) },
                            ),
                        )
                    }
                    val event =
                        Event(
                            songId = h.songId,
                            timestamp = toLocalDateTime(h.playedAt),
                            playTime = h.playTimeMs.coerceAtLeast(0),
                        )
                    val eventId = dao.insert(event)
                    if (eventId > 0) historyCount++
                }

                payload.playlists.forEach { p ->
                    val name = p.name.trim()
                    if (name.isNotEmpty() || p.browseId != null) {
                        val existing =
                            if (p.browseId != null) {
                                dao.findPlaylistEntity(p.browseId)
                            } else {
                                dao.findEditableLocalPlaylistByName(name)
                            }
                        val target =
                            existing ?: PlaylistEntity(
                                id = p.id,
                                name = name.ifEmpty { checkNotNull(p.browseId) },
                                browseId = p.browseId,
                                isEditable = p.browseId == null || !p.isLocal,
                                isLocal = p.isLocal,
                                description = p.description,
                                thumbnailUrl = p.thumbnailUrl,
                                createdAt = toLocalDateTime(now),
                                lastUpdateTime = toLocalDateTime(now),
                            ).also { dao.insert(it) }

                        var position = dao.nextPlaylistPosition(target.id)
                        val existingSongIds = dao.playlistSongMaps(target.id, 0).map { it.songId }.toSet()
                        p.songIds.forEach { songId ->
                            if (songId !in existingSongIds) {
                                dao.insert(PlaylistSongMap(playlistId = target.id, songId = songId, position = position++))
                            }
                        }
                        playlistCount++
                    }
                }

                payload.artists.forEach { a ->
                    if (dao.getArtistById(a.id) == null) {
                        dao.upsert(
                            ArtistEntity(
                                id = a.id,
                                name = a.title,
                                thumbnailUrl = a.thumbnailUrl,
                                bookmarkedAt = toLocalDateTime(a.bookmarkedAt.takeIf { it > 0 } ?: now),
                            ),
                        )
                    }
                }

                payload.albums.forEach { a ->
                    val existing = dao.album(a.id).first()
                    if (existing == null) {
                        dao.upsert(
                            AlbumEntity(
                                id = a.id,
                                title = a.title,
                                thumbnailUrl = a.thumbnailUrl,
                                songCount = 0,
                                duration = 0,
                                bookmarkedAt = toLocalDateTime(a.bookmarkedAt.takeIf { it > 0 } ?: now),
                            ),
                        )
                    } else if (existing.album.bookmarkedAt == null) {
                        dao.update(
                            existing.album.copy(
                                bookmarkedAt = toLocalDateTime(a.bookmarkedAt.takeIf { it > 0 } ?: now),
                            ),
                        )
                    }
                }
            }

            if (payload.downloadedSongIds.isNotEmpty()) {
                onDownloadsDiscovered(payload.downloadedSongIds)
            }

            val auth = payload.authSession
            if (auth != null && auth.cookie.isNotBlank() && Settings.shared.innerTubeCookie.isBlank()) {
                Settings.shared.applyStringBatch(
                    mapOf(
                        "innerTubeCookie" to auth.cookie,
                        "visitorData" to auth.visitorData,
                        "dataSyncId" to auth.dataSyncId,
                        "innerTubeAuthUser" to auth.authUser,
                        "accountName" to auth.accountName,
                        "accountEmail" to auth.accountEmail,
                        "accountChannelHandle" to auth.accountChannelHandle,
                        "accountThumbnail" to auth.accountThumbnail,
                    ),
                )
                AuthManager().restorePersistedSession()
            }

            CrossDeviceSyncResult(
                songsMerged = songsCount,
                playlistsMerged = playlistCount,
                historyMerged = historyCount,
                downloadsQueued = payload.downloadedSongIds.size,
                isSuccess = true,
            )
        }

    suspend fun syncWithPeer(
        peerEndpoint: String,
        database: MusicDatabase,
        deviceId: String,
        deviceName: String,
        downloadedIds: List<String> = emptyList(),
    ): Result<CrossDeviceSyncResult> =
        withContext(Dispatchers.IO) {
            _isSyncing.value = true
            try {
                val clean =
                    peerEndpoint
                        .trim()
                        .removePrefix("http://")
                        .removePrefix("https://")
                        .substringBefore('/')
                val host =
                    if (clean.startsWith("[")) {
                        clean.substringAfter("[").substringBefore("]")
                    } else {
                        clean.substringBefore(':')
                    }
                val port = if (clean.contains(":")) clean.substringAfterLast(':').toIntOrNull() ?: 8765 else 8765
                val url = "http://$host:$port/sync/v1"

                val localPayload = exportPayload(database, deviceId, deviceName, downloadedIds)
                val response =
                    httpClient.post(url) {
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(localPayload))
                    }
                val responseText = response.bodyAsText()
                val peerPayload = json.decodeFromString<CrossDeviceSyncPayload>(responseText)

                val mergeResult = mergePayload(database, peerPayload)
                _lastSyncResult.value = mergeResult
                Result.success(mergeResult)
            } catch (e: Exception) {
                val errorResult =
                    CrossDeviceSyncResult(
                        isSuccess = false,
                        errorMessage = e.message ?: "Sync connection failed",
                    )
                _lastSyncResult.value = errorResult
                Result.failure(e)
            } finally {
                _isSyncing.value = false
            }
        }

    suspend fun handleIncomingSync(
        incomingJson: String,
        database: MusicDatabase,
        deviceId: String,
        deviceName: String,
        downloadedIds: List<String> = emptyList(),
    ): String =
        withContext(Dispatchers.Default) {
            val peerPayload = json.decodeFromString<CrossDeviceSyncPayload>(incomingJson)
            mergePayload(database, peerPayload)
            val responsePayload = exportPayload(database, deviceId, deviceName, downloadedIds)
            json.encodeToString(responsePayload)
        }

    private fun toLocalDateTime(epochMillis: Long): LocalDateTime =
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())

    private fun toEpochMillis(dateTime: LocalDateTime): Long = dateTime.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
}
