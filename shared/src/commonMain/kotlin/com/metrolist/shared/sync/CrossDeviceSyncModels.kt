package com.metrolist.shared.sync

import kotlinx.serialization.Serializable

@Serializable
data class SyncSong(
    val id: String,
    val title: String,
    val artistsText: String = "",
    val albumText: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Int? = null,
    val liked: Boolean = false,
    val likedAt: Long? = null,
    val inLibrary: Boolean = false,
    val inLibraryAt: Long? = null,
    val totalPlayTimeMs: Long = 0L,
    val isDownloaded: Boolean = false,
)

@Serializable
data class SyncPlaylist(
    val id: String,
    val name: String,
    val browseId: String? = null,
    val isLocal: Boolean = true,
    val songIds: List<String> = emptyList(),
    val description: String? = null,
    val thumbnailUrl: String? = null,
)

@Serializable
data class SyncHistoryEntry(
    val songId: String,
    val title: String,
    val artistsText: String,
    val albumText: String? = null,
    val thumbnailUrl: String? = null,
    val playedAt: Long,
    val playTimeMs: Long = 0L,
)

@Serializable
data class SyncBookmark(
    val id: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val bookmarkedAt: Long = 0L,
)

@Serializable
data class SyncAuthSession(
    val cookie: String = "",
    val visitorData: String = "",
    val dataSyncId: String = "",
    val authUser: String = "",
    val accountName: String = "",
    val accountEmail: String = "",
    val accountChannelHandle: String = "",
    val accountThumbnail: String = "",
)

@Serializable
data class CrossDeviceSyncPayload(
    val version: Int = 1,
    val deviceId: String,
    val deviceName: String,
    val timestamp: Long,
    val songs: List<SyncSong> = emptyList(),
    val playlists: List<SyncPlaylist> = emptyList(),
    val history: List<SyncHistoryEntry> = emptyList(),
    val artists: List<SyncBookmark> = emptyList(),
    val albums: List<SyncBookmark> = emptyList(),
    val downloadedSongIds: List<String> = emptyList(),
    val authCookie: String? = null,
    val authSession: SyncAuthSession? = null,
)

@Serializable
data class CrossDeviceSyncResult(
    val songsMerged: Int = 0,
    val playlistsMerged: Int = 0,
    val historyMerged: Int = 0,
    val downloadsQueued: Int = 0,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null,
)
