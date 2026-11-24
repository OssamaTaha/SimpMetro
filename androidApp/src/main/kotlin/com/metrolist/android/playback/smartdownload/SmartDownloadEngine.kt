package com.metrolist.android.playback.smartdownload

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.metrolist.android.playback.AudioCacheManager
import com.metrolist.android.playback.SongDownloadManager
import com.metrolist.shared.constants.PlaylistSortType
import com.metrolist.shared.constants.SongSortType
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.historySongStatistics
import com.metrolist.shared.db.statisticsSongs
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Smart Downloads Engine: Automatically keeps an offline mix of music
 * personalized to the user's taste (Dashboard Most Played history, Liked Songs,
 * taste-seeded Radio recommendations, and optionally user playlists).
 */
class SmartDownloadEngine(
    private val database: MusicDatabase,
    private val songDownloadManager: SongDownloadManager,
) {
    private fun readSmartIds(): MutableList<String> =
        Settings.shared.smartDownloadedSongIds
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()

    private fun saveSmartIds(ids: List<String>) {
        Settings.shared.smartDownloadedSongIds = ids.distinct().joinToString(",")
    }

    /**
     * Prunes excess smart-downloaded tracks when the user lowers the song quota
     * or storage limit. Never deletes manually downloaded tracks.
     */
    suspend fun pruneToQuota(
        quota: Int = Settings.shared.smartDownloadQuota,
        maxStorageLimitGb: Int = Settings.shared.smartDownloadStorageLimitGb,
    ): Int =
        withContext(Dispatchers.IO) {
            val smartIds = readSmartIds()
            if (smartIds.isEmpty()) return@withContext 0

            val audioCacheManager = GlobalContext.get().getOrNull<AudioCacheManager>()
            val activeSmartIds =
                if (audioCacheManager != null) {
                    val downloaded = audioCacheManager.downloadedIds(smartIds)
                    smartIds.filter { it in downloaded }.toMutableList()
                } else {
                    smartIds.toMutableList()
                }

            val toDelete = mutableListOf<String>()
            if (quota > 0 && activeSmartIds.size > quota) {
                val excessCount = activeSmartIds.size - quota
                val trimmed = activeSmartIds.take(excessCount)
                toDelete.addAll(trimmed)
                activeSmartIds.removeAll(trimmed.toSet())
            }

            if (audioCacheManager != null) {
                val maxAllowedBytes = maxStorageLimitGb.toLong() * 1024L * 1024L * 1024L
                while (audioCacheManager.getDownloadSizeBytes() > maxAllowedBytes && activeSmartIds.isNotEmpty()) {
                    val oldestId = activeSmartIds.removeAt(0)
                    val song = database.dao().getSongById(oldestId)?.toSongItem()
                    if (song != null) {
                        songDownloadManager.removeDownload(song)
                    }
                    toDelete.add(oldestId)
                }
            }

            for (id in toDelete) {
                val song = database.dao().getSongById(id)?.toSongItem()
                if (song != null) {
                    songDownloadManager.removeDownload(song)
                }
            }

            saveSmartIds(activeSmartIds)
            toDelete.size
        }

    /**
     * Resolves smart download candidates based on the listener's actual music profile:
     * - Top played tracks from playback history (exact data from Dashboard stats)
     * - Liked songs (active favorites)
     * - Optional user playlists (only when [includePlaylists] is enabled)
     * - Taste discovery (seeded strictly from the user's top/favorite tracks)
     * Never queries generic trending hits.
     */
    suspend fun resolveCandidates(
        targetCount: Int = 0,
        includePlaylists: Boolean = Settings.shared.smartDownloadIncludePlaylists,
    ): List<SongItem> =
        withContext(Dispatchers.IO) {
            val isUnlimited = targetCount <= 0
            val count = if (isUnlimited) 5000 else targetCount.coerceIn(20, 5000)
            val candidateList = mutableListOf<SongItem>()

            // 1. Most Played tracks from playback history (the exact music data shown on the Dashboard)
            try {
                val historyStats = database.historySongStatistics(0L, Long.MAX_VALUE)
                if (historyStats.isNotEmpty()) {
                    val topSongs = database.statisticsSongs(historyStats.take(count))
                    candidateList.addAll(topSongs)
                    Timber.d("SmartDownload: Resolved ${topSongs.size} dashboard top songs")
                }
            } catch (e: Exception) {
                Timber.e(e, "SmartDownload: Failed to fetch dashboard top songs")
            }

            // 2. Liked / Favorited tracks
            try {
                val liked =
                    database
                        .dao()
                        .likedSongs(SongSortType.CREATE_DATE, descending = true)
                        .first()
                        .map { it.toSongItem() }
                candidateList.addAll(liked)
                Timber.d("SmartDownload: Resolved ${liked.size} liked songs")
            } catch (e: Exception) {
                Timber.e(e, "SmartDownload: Failed to fetch liked songs")
            }

            // 3. Optional User Playlists (disabled by default; user can opt in)
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
                    Timber.d("SmartDownload: Resolved tracks from ${playlists.size} playlists")
                } catch (e: Exception) {
                    Timber.e(e, "SmartDownload: Failed to fetch playlist tracks")
                }
            }

            val distinctCandidates = candidateList.distinctBy { it.id }.toMutableList()

            // 4. Taste Discovery: Seed recommendations strictly from user's top/favorite tracks
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
                        Timber.d("SmartDownload: Added ${radioRecommendations.size} taste discovery songs seeded by ${seedSong.title}")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "SmartDownload: Taste discovery radio fetch failed, continuing with user music")
                }
            }

            distinctCandidates.distinctBy { it.id }.take(count)
        }

    /**
     * Executes the smart download synchronization:
     * Prunes excess smart-downloaded tracks if quota/storage cap was lowered,
     * then enqueues missing tracks in polite batches while respecting quota and storage limits.
     */
    suspend fun sync(
        quota: Int = 100,
        maxStorageLimitGb: Int = 2,
    ): Int =
        withContext(Dispatchers.IO) {
            pruneToQuota(quota = quota, maxStorageLimitGb = maxStorageLimitGb)

            val audioCacheManager = GlobalContext.get().getOrNull<AudioCacheManager>()
            if (audioCacheManager != null) {
                val currentDownloadSizeBytes = audioCacheManager.getDownloadSizeBytes()
                val maxAllowedBytes = maxStorageLimitGb.toLong() * 1024L * 1024L * 1024L
                if (currentDownloadSizeBytes >= maxAllowedBytes) {
                    Timber.w("SmartDownload: Aborting sync - current offline downloads reached configured storage cap ($maxStorageLimitGb GB)")
                    return@withContext 0
                }

                val downloadDir = audioCacheManager.downloadDirectory()
                val freeSpaceBytes = downloadDir.freeSpace
                val minSafeFreeSpaceBytes = 1024L * 1024L * 1024L // 1 GB safety buffer
                if (freeSpaceBytes < minSafeFreeSpaceBytes) {
                    Timber.w("SmartDownload: Aborting sync - low device storage (${freeSpaceBytes / (1024 * 1024)} MB free).")
                    return@withContext 0
                }
            }

            val candidates = resolveCandidates(quota)
            if (candidates.isEmpty()) {
                Timber.d("SmartDownload: No candidate tracks resolved (waiting for user listening data)")
                return@withContext 0
            }

            val smartIds =
                readSmartIds().let { ids ->
                    if (audioCacheManager != null) {
                        val active = audioCacheManager.downloadedIds(ids)
                        ids.filter { it in active }.toMutableList()
                    } else {
                        ids.toMutableList()
                    }
                }
            val downloadedSet = audioCacheManager?.downloadedIds(candidates.map { it.id }) ?: emptySet()
            val unDownloadedAll = candidates.filterNot { it.id in downloadedSet }
            if (unDownloadedAll.isEmpty()) return@withContext 0

            // Rotate stale smart-downloaded tracks when quota is full so newly liked/played songs replace old ones
            val desiredIds = candidates.mapTo(mutableSetOf()) { it.id }
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
                for (id in toRotate) {
                    database.dao().getSongById(id)?.toSongItem()?.let { songDownloadManager.removeDownload(it) }
                }
                smartIds.removeAll(toRotate.toSet())
                saveSmartIds(smartIds)
            }

            val remainingQuotaSlots =
                if (quota <= 0) {
                    MAX_BATCH_PER_SYNC
                } else {
                    (quota - smartIds.size).coerceIn(0, MAX_BATCH_PER_SYNC)
                }
            if (remainingQuotaSlots <= 0) return@withContext 0

            val toEnqueue = unDownloadedAll.take(remainingQuotaSlots)
            if (toEnqueue.isEmpty()) return@withContext 0

            saveSmartIds(smartIds + toEnqueue.map { it.id })
            val enqueuedCount = songDownloadManager.downloadAll(toEnqueue)
            Timber.d("SmartDownload: Synchronized ${toEnqueue.size} songs, enqueued $enqueuedCount new tracks")
            enqueuedCount
        }

    companion object {
        private const val MAX_BATCH_PER_SYNC = 25
    }
}

/**
 * Background WorkManager Worker running Smart Download sync automatically.
 */
class SmartDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            try {
                val koin = GlobalContext.get()
                val database = koin.get<MusicDatabase>()
                val songDownloadManager = koin.get<SongDownloadManager>()
                val engine = SmartDownloadEngine(database, songDownloadManager)

                val quota = inputData.getInt(KEY_QUOTA, Settings.shared.smartDownloadQuota)
                val storageLimitGb = inputData.getInt(KEY_STORAGE_LIMIT_GB, Settings.shared.smartDownloadStorageLimitGb)
                engine.sync(quota = quota, maxStorageLimitGb = storageLimitGb)
                Result.success()
            } catch (e: Exception) {
                Timber.e(e, "SmartDownloadWorker encountered error during sync")
                Result.retry()
            }
        }

    companion object {
        const val WORK_NAME_PERIODIC = "metrolist_smart_download_periodic"
        const val KEY_QUOTA = "quota"
        const val KEY_STORAGE_LIMIT_GB = "storage_limit_gb"

        fun schedule(
            context: Context,
            quota: Int = 100,
            storageLimitGb: Int = 2,
            wifiOnly: Boolean = true,
            chargingOnly: Boolean = false,
        ) {
            val constraints =
                Constraints
                    .Builder()
                    .apply {
                        if (wifiOnly) setRequiredNetworkType(NetworkType.UNMETERED)
                        if (chargingOnly) setRequiresCharging(true)
                        setRequiresBatteryNotLow(true)
                    }.build()

            val request =
                PeriodicWorkRequestBuilder<SmartDownloadWorker>(24, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setInputData(
                        androidx.work.workDataOf(
                            KEY_QUOTA to quota,
                            KEY_STORAGE_LIMIT_GB to storageLimitGb,
                        ),
                    ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun triggerImmediate(
            context: Context,
            quota: Int = 100,
            storageLimitGb: Int = 2,
        ) {
            val request =
                androidx.work
                    .OneTimeWorkRequestBuilder<SmartDownloadWorker>()
                    .setInputData(
                        androidx.work.workDataOf(
                            KEY_QUOTA to quota,
                            KEY_STORAGE_LIMIT_GB to storageLimitGb,
                        ),
                    ).build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
        }
    }
}
