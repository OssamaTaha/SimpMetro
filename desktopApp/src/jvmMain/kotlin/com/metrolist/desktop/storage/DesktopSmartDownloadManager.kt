/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.storage

import com.metrolist.shared.constants.PlaylistSortType
import com.metrolist.shared.constants.SongSortType
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.historySongStatistics
import com.metrolist.shared.db.statisticsSongs
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.File

/**
 * Desktop Smart Download Engine: Automatically keeps an offline mix of music
 * personalized to the user's taste (combining Dashboard Most Played history,
 * User Playlists, Liked Songs, and user-seeded Radio recommendations).
 */
object DesktopSmartDownloadManager {
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private var periodicJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (periodicJob != null) return
        periodicJob =
            scope.launch(Dispatchers.IO) {
                // Initial delay to avoid slowing down app startup
                delay(15_000)
                while (isActive) {
                    if (Settings.shared.smartDownloadEnabled) {
                        runCatching {
                            sync(
                                quota = Settings.shared.smartDownloadQuota,
                                maxStorageLimitGb = Settings.shared.smartDownloadStorageLimitGb,
                            )
                        }
                    }
                    // Run every 4 hours while app is open
                    delay(4 * 60 * 60 * 1000L)
                }
            }
    }

    suspend fun resolveCandidates(targetCount: Int = 0): List<SongItem> =
        withContext(Dispatchers.IO) {
            val database = GlobalContext.get().getOrNull<MusicDatabase>() ?: return@withContext emptyList()
            val isUnlimited = targetCount <= 0
            val count = if (isUnlimited) 5000 else targetCount.coerceIn(20, 5000)
            val candidateList = mutableListOf<SongItem>()

            // 1. User Playlists
            try {
                val playlists = database.dao().playlists(PlaylistSortType.CREATE_DATE, descending = true).first()
                for (playlist in playlists.take(30)) {
                    val pSongs =
                        database
                            .dao()
                            .playlistSongs(playlist.id)
                            .first()
                            .map { it.toSongItem() }
                    candidateList.addAll(pSongs)
                }
            } catch (_: Exception) {
            }

            // 2. Liked songs
            try {
                val liked =
                    database
                        .dao()
                        .likedSongs(SongSortType.CREATE_DATE, descending = true)
                        .first()
                        .map { it.toSongItem() }
                candidateList.addAll(liked)
            } catch (_: Exception) {
            }

            // 3. Library tracks
            try {
                val libraryTracks =
                    database
                        .dao()
                        .songsByPlayTimeAsc()
                        .first()
                        .reversed()
                        .map { it.toSongItem() }
                candidateList.addAll(libraryTracks)
            } catch (_: Exception) {
            }

            // 4. Most Played tracks from playback history (exact data from Dashboard stats)
            try {
                val historyStats = database.historySongStatistics(0L, Long.MAX_VALUE)
                if (historyStats.isNotEmpty()) {
                    val topSongs = database.statisticsSongs(historyStats.take(count))
                    candidateList.addAll(topSongs)
                }
            } catch (_: Exception) {
            }

            val distinctCandidates = candidateList.distinctBy { it.id }.toMutableList()

            // 5. Taste Discovery seeded strictly from user's favorites
            val remainingSlots = count - distinctCandidates.size
            if (remainingSlots > 0 && distinctCandidates.isNotEmpty()) {
                try {
                    val seedSong = distinctCandidates.shuffled().firstOrNull()
                    if (seedSong != null) {
                        val radioRecommendations =
                            GlobalYouTubeRepository.instance
                                .startRadio(seedSong.id)
                                .filterNot { rec -> distinctCandidates.any { it.id == rec.id } }
                                .take(remainingSlots)
                        distinctCandidates.addAll(radioRecommendations)
                    }
                } catch (_: Exception) {
                }
            }

            distinctCandidates.distinctBy { it.id }.take(count)
        }

    suspend fun sync(
        quota: Int = 100,
        maxStorageLimitGb: Int = 2,
    ): Int =
        withContext(Dispatchers.IO) {
            if (_isSyncing.value) return@withContext 0
            _isSyncing.value = true
            try {
                val downloadDir = DownloadManager.getDownloadDir()
                val currentDownloadSizeBytes =
                    DownloadManager.downloadedFiles.value.sumOf {
                        File(it.audioFilePath).length() + (it.thumbnailFilePath?.let { path -> File(path).length() } ?: 0L)
                    }
                val maxAllowedBytes = maxStorageLimitGb.toLong() * 1024L * 1024L * 1024L
                if (currentDownloadSizeBytes >= maxAllowedBytes) {
                    return@withContext 0
                }

                val freeSpaceBytes = downloadDir.freeSpace
                val minSafeFreeSpaceBytes = 1024L * 1024L * 1024L // 1 GB safety buffer
                if (freeSpaceBytes < minSafeFreeSpaceBytes) {
                    return@withContext 0
                }

                val candidates = resolveCandidates(quota)
                if (candidates.isEmpty()) return@withContext 0

                val unDownloaded = candidates.filter { !DownloadManager.isDownloaded(it.id) }
                if (unDownloaded.isNotEmpty()) {
                    DownloadManager.downloadAll(unDownloaded)
                }
                unDownloaded.size
            } finally {
                _isSyncing.value = false
            }
        }
}
