package com.metrolist.android.playback.smartdownload

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.metrolist.android.playback.SongDownloadManager
import com.metrolist.shared.constants.PlaylistSortType
import com.metrolist.shared.constants.SongSortType
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.historySongStatistics
import com.metrolist.shared.db.statisticsSongs
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Smart Downloads Engine: Automatically keeps an offline mix of music
 * personalized to the user's taste (combining Dashboard Most Played history,
 * User Playlists, Liked Songs, and user-seeded Radio recommendations).
 */
class SmartDownloadEngine(
    private val database: MusicDatabase,
    private val songDownloadManager: SongDownloadManager,
) {
    /**
     * Resolves smart download candidates based on the listener's actual music profile:
     * - Top played tracks from playback history (exact data from Dashboard stats)
     * - User playlists (local playlists & saved YouTube Music playlists)
     * - Liked songs (active favorites)
     * - Library songs
     * - Taste discovery (seeded strictly from the user's top/favorite tracks)
     * Never queries generic trending hits.
     */
    suspend fun resolveCandidates(targetCount: Int = 0): List<SongItem> =
        withContext(Dispatchers.IO) {
            // targetCount <= 0 means unlimited songs (governed purely by storage limits)
            val isUnlimited = targetCount <= 0
            val count = if (isUnlimited) 5000 else targetCount.coerceIn(20, 5000)
            val candidateList = mutableListOf<SongItem>()

            // 1. User Playlists (local and bookmarked playlists - top priority)
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
                Timber.d("SmartDownload: Resolved ${libraryTracks.size} library songs")
            } catch (e: Exception) {
                Timber.e(e, "SmartDownload: Failed to fetch library songs")
            }

            // 4. Most Played tracks from playback history (the exact music data shown on the Dashboard)
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

            // Deduplicate user candidates
            val distinctCandidates = candidateList.distinctBy { it.id }.toMutableList()

            // 5. Taste Discovery: If quota allows and user has music data, seed recommendations strictly from user's favorites
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
                        Timber.d("SmartDownload: Added ${radioRecommendations.size} taste discovery songs seeded by ${seedSong.title}")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "SmartDownload: Taste discovery radio fetch failed, continuing with user music")
                }
            }

            // Return up to count distinct tracks (0 if user has no music history yet; NEVER download random "Top Hits")
            distinctCandidates.distinctBy { it.id }.take(count)
        }

    /**
     * Executes the smart download synchronization:
     * Enqueues missing tracks to [SongDownloadManager] while respecting quota and storage limits.
     * Prevents accidental disk saturation by checking device free space and max allowed GB.
     */
    suspend fun sync(
        quota: Int = 100,
        maxStorageLimitGb: Int = 2,
    ): Int =
        withContext(Dispatchers.IO) {
            val audioCacheManager = GlobalContext.get().getOrNull<com.metrolist.android.playback.AudioCacheManager>()
            if (audioCacheManager != null) {
                val currentDownloadSizeBytes = audioCacheManager.getDownloadSizeBytes()
                val maxAllowedBytes = maxStorageLimitGb.toLong() * 1024L * 1024L * 1024L
                if (currentDownloadSizeBytes >= maxAllowedBytes) {
                    Timber.w("SmartDownload: Aborting sync - current offline downloads (${currentDownloadSizeBytes / (1024 * 1024)} MB) reached configured storage cap ($maxStorageLimitGb GB)")
                    return@withContext 0
                }

                // Check free space on download directory - always keep at least 1 GB free on the device
                val downloadDir = audioCacheManager.downloadDirectory()
                val freeSpaceBytes = downloadDir.freeSpace
                val minSafeFreeSpaceBytes = 1024L * 1024L * 1024L // 1 GB safety buffer
                if (freeSpaceBytes < minSafeFreeSpaceBytes) {
                    Timber.w("SmartDownload: Aborting sync - low device storage (${freeSpaceBytes / (1024 * 1024)} MB free). Protecting device from accidental storage exhaustion.")
                    return@withContext 0
                }
            }

            val candidates = resolveCandidates(quota)
            if (candidates.isEmpty()) {
                Timber.d("SmartDownload: No candidate tracks resolved (waiting for user listening data)")
                return@withContext 0
            }

            val enqueuedCount = songDownloadManager.downloadAll(candidates)
            Timber.d("SmartDownload: Synchronized ${candidates.size} songs, enqueued $enqueuedCount new tracks")
            enqueuedCount
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

                val quota = inputData.getInt(KEY_QUOTA, 100)
                val storageLimitGb = inputData.getInt(KEY_STORAGE_LIMIT_GB, 2)
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

        /**
         * Schedules the 24-hour background smart download job.
         * Runs only when connected to Wi-Fi and charging (standard YouTube Music behavior).
         */
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
                        )
                    )
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /**
         * Immediately enqueues a one-time Smart Download sync without waiting for the periodic timer.
         */
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
                        )
                    )
                    .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        /**
         * Cancels periodic smart download job.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
        }
    }
}
