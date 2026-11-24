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
 * personalized to the user's taste (Dashboard Most Played history, Liked Songs,
 * taste-seeded Radio recommendations, and optionally user playlists).
 */
object DesktopSmartDownloadManager {
    private const val MAX_BATCH_PER_SYNC = 25

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private var periodicJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (periodicJob != null) return
        periodicJob =
            scope.launch(Dispatchers.IO) {
                delay(30_000)
                while (isActive) {
                    if (Settings.shared.smartDownloadEnabled) {
                        runCatching {
                            sync(
                                quota = Settings.shared.smartDownloadQuota,
                                maxStorageLimitGb = Settings.shared.smartDownloadStorageLimitGb,
                            )
                        }
                    }
                    delay(4 * 60 * 60 * 1000L)
                }
            }
    }

    private fun readSmartIds(): MutableList<String> =
        Settings.shared.smartDownloadedSongIds
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()

    private fun saveSmartIds(ids: List<String>) {
        Settings.shared.smartDownloadedSongIds = ids.distinct().joinToString(",")
    }

    suspend fun pruneToQuota(
        quota: Int = Settings.shared.smartDownloadQuota,
        maxStorageLimitGb: Int = Settings.shared.smartDownloadStorageLimitGb,
    ): Int =
        withContext(Dispatchers.IO) {
            val smartIds = readSmartIds()
            if (smartIds.isEmpty()) return@withContext 0

            val downloadedById = DownloadManager.downloadedFiles.value.associateBy { it.songId }
            val activeSmartIds = smartIds.filter { it in downloadedById }.toMutableList()
            val toDelete = mutableListOf<String>()

            if (quota > 0 && activeSmartIds.size > quota) {
                val excessCount = activeSmartIds.size - quota
                val trimmed = activeSmartIds.take(excessCount)
                toDelete.addAll(trimmed)
                activeSmartIds.removeAll(trimmed.toSet())
            }

            val maxAllowedBytes = maxStorageLimitGb.toLong() * 1024L * 1024L * 1024L
            var currentDownloadSizeBytes =
                DownloadManager.downloadedFiles.value.sumOf {
                    File(it.audioFilePath).length() + (it.thumbnailFilePath?.let { path -> File(path).length() } ?: 0L)
                }
            while (currentDownloadSizeBytes > maxAllowedBytes && activeSmartIds.isNotEmpty()) {
                val oldestId = activeSmartIds.removeAt(0)
                val file = downloadedById[oldestId]
                val fileSize =
                    if (file != null) {
                        File(file.audioFilePath).length() + (file.thumbnailFilePath?.let { path -> File(path).length() } ?: 0L)
                    } else {
                        0L
                    }
                toDelete.add(oldestId)
                currentDownloadSizeBytes = (currentDownloadSizeBytes - fileSize).coerceAtLeast(0L)
            }

            if (toDelete.isNotEmpty()) {
                DownloadManager.deleteDownloadsAndAwait(toDelete)
            }
            saveSmartIds(activeSmartIds)
            toDelete.size
        }

    suspend fun resolveCandidates(
        targetCount: Int = 0,
        includePlaylists: Boolean = Settings.shared.smartDownloadIncludePlaylists,
    ): List<SongItem> =
        withContext(Dispatchers.IO) {
            val database = GlobalContext.get().getOrNull<MusicDatabase>() ?: return@withContext emptyList()
            val isUnlimited = targetCount <= 0
            val count = if (isUnlimited) 5000 else targetCount.coerceIn(20, 5000)
            val candidateList = mutableListOf<SongItem>()

            // 1. Most Played tracks from playback history (Dashboard stats)
            try {
                val historyStats = database.historySongStatistics(0L, Long.MAX_VALUE)
                if (historyStats.isNotEmpty()) {
                    val topSongs = database.statisticsSongs(historyStats.take(count))
                    candidateList.addAll(topSongs)
                }
            } catch (_: Exception) {
            }

            // 2. Liked / favorite tracks
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

            // 3. Optional User Playlists (only when explicitly enabled by user)
            if (includePlaylists) {
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
            }

            val distinctCandidates = candidateList.distinctBy { it.id }.toMutableList()

            // 4. Taste Discovery seeded from user's top played & liked tracks
            val remainingSlots = count - distinctCandidates.size
            if (remainingSlots > 0 && distinctCandidates.isNotEmpty()) {
                try {
                    val seedSong = distinctCandidates.take(15).shuffled().firstOrNull()
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
        quota: Int = Settings.shared.smartDownloadQuota,
        maxStorageLimitGb: Int = Settings.shared.smartDownloadStorageLimitGb,
    ): Int =
        withContext(Dispatchers.IO) {
            if (_isSyncing.value) return@withContext 0
            _isSyncing.value = true
            try {
                pruneToQuota(quota = quota, maxStorageLimitGb = maxStorageLimitGb)

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

                val desiredIds = candidates.mapTo(mutableSetOf()) { it.id }
                val smartIds = readSmartIds().filter { DownloadManager.isDownloaded(it) }.toMutableList()
                val unDownloadedAll = candidates.filter { !DownloadManager.isDownloaded(it.id) }

                if (unDownloadedAll.isEmpty()) return@withContext 0

                // Rotate stale smart-downloaded songs when quota is reached so new favorites/top tracks replace old ones
                val slotsNeeded =
                    if (quota <= 0) {
                        0
                    } else {
                        (smartIds.size + minOf(unDownloadedAll.size, MAX_BATCH_PER_SYNC) - quota).coerceAtLeast(0)
                    }
                if (slotsNeeded > 0) {
                    val staleCandidates =
                        smartIds.filterNot { it in desiredIds } +
                            smartIds.filter { it in desiredIds }.reversed()
                    val toRotate = staleCandidates.distinct().take(minOf(slotsNeeded, MAX_BATCH_PER_SYNC))
                    if (toRotate.isNotEmpty()) {
                        DownloadManager.deleteDownloadsAndAwait(toRotate)
                        smartIds.removeAll(toRotate.toSet())
                        saveSmartIds(smartIds)
                    }
                }

                val remainingQuotaSlots =
                    if (quota <= 0) {
                        MAX_BATCH_PER_SYNC
                    } else {
                        (quota - smartIds.size).coerceIn(0, MAX_BATCH_PER_SYNC)
                    }
                if (remainingQuotaSlots <= 0) return@withContext 0

                val unDownloaded = unDownloadedAll.take(remainingQuotaSlots)
                if (unDownloaded.isNotEmpty()) {
                    saveSmartIds(smartIds + unDownloaded.map { it.id })
                    DownloadManager.downloadAll(unDownloaded)
                }
                unDownloaded.size
            } finally {
                _isSyncing.value = false
            }
        }
}
