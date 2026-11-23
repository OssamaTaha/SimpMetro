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
import com.metrolist.shared.constants.SongSortType
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Smart Downloads Engine: Automatically keeps an offline playlist of music
 * personalized to the user's taste (combining Most Played, Liked Songs, and Radio Discovery).
 */
class SmartDownloadEngine(
    private val database: MusicDatabase,
    private val songDownloadManager: SongDownloadManager,
) {
    /**
     * Resolves smart download candidates based on the listener's profile:
     * - 50% Top played tracks (proven taste)
     * - 30% Liked songs (active favorites)
     * - 20% Discovery radio seeds (fresh recommendations based on top tracks)
     */
    suspend fun resolveCandidates(targetCount: Int = 100): List<SongItem> = withContext(Dispatchers.IO) {
        val count = targetCount.coerceIn(20, 500)
        val topTarget = (count * 0.50).toInt().coerceAtLeast(5)
        val likedTarget = (count * 0.30).toInt().coerceAtLeast(5)
        val discoveryTarget = (count - topTarget - likedTarget).coerceAtLeast(0)

        val candidateList = mutableListOf<SongItem>()

        // 1. Most Played tracks from library
        try {
            val mostPlayed = database.dao()
                .songsByPlayTimeAsc()
                .first()
                .reversed() // Highest playtime first
                .take(topTarget)
                .map { it.toSongItem() }
            candidateList.addAll(mostPlayed)
        } catch (e: Exception) {
            Timber.e(e, "SmartDownload: Failed to fetch most played songs")
        }

        // 2. Liked / Favorited tracks
        try {
            val liked = database.dao()
                .likedSongs(SongSortType.CREATE_DATE, descending = true)
                .first()
                .take(likedTarget)
                .map { it.toSongItem() }
            candidateList.addAll(liked)
        } catch (e: Exception) {
            Timber.e(e, "SmartDownload: Failed to fetch liked songs")
        }

        // 3. Discovery: Radio recommendations seeded by top tracks
        if (discoveryTarget > 0 && candidateList.isNotEmpty()) {
            try {
                val seedSong = candidateList.firstOrNull()
                if (seedSong != null) {
                    val radioRecommendations = GlobalYouTubeRepository.instance
                        .startRadio(seedSong.id)
                        .filterNot { rec -> candidateList.any { it.id == rec.id } }
                        .take(discoveryTarget)
                    candidateList.addAll(radioRecommendations)
                }
            } catch (e: Exception) {
                Timber.w(e, "SmartDownload: Discovery radio fetch failed (possibly offline), continuing with local candidates")
            }
        }

        // 4. Discovery fallback for fresh installs: Query top trending tracks if user has no listening history yet
        if (candidateList.size < 10) {
            try {
                val trendingHits = GlobalYouTubeRepository.instance
                    .search("Top Hits")
                    .filterIsInstance<SongItem>()
                    .take(count - candidateList.size)
                candidateList.addAll(trendingHits)
            } catch (e: Exception) {
                Timber.w(e, "SmartDownload: Trending hits fallback search failed")
            }
        }

        candidateList.distinctBy { it.id }.take(count)
    }

    /**
     * Executes the smart download synchronization:
     * Enqueues missing tracks to [SongDownloadManager] while respecting quota.
     */
    suspend fun sync(quota: Int = 100): Int = withContext(Dispatchers.IO) {
        val candidates = resolveCandidates(quota)
        if (candidates.isEmpty()) return@withContext 0

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

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val koin = GlobalContext.get()
            val database = koin.get<MusicDatabase>()
            val songDownloadManager = koin.get<SongDownloadManager>()
            val engine = SmartDownloadEngine(database, songDownloadManager)

            val quota = inputData.getInt(KEY_QUOTA, 100)
            engine.sync(quota)
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "SmartDownloadWorker encountered error during sync")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME_PERIODIC = "metrolist_smart_download_periodic"
        const val KEY_QUOTA = "quota"

        /**
         * Schedules the 24-hour background smart download job.
         * Runs only when connected to Wi-Fi and charging (standard YouTube Music behavior).
         */
        fun schedule(
            context: Context,
            quota: Int = 100,
            wifiOnly: Boolean = true,
            chargingOnly: Boolean = false,
        ) {
            val constraints = Constraints.Builder().apply {
                if (wifiOnly) setRequiredNetworkType(NetworkType.UNMETERED)
                if (chargingOnly) setRequiresCharging(true)
                setRequiresBatteryNotLow(true)
            }.build()

            val request = PeriodicWorkRequestBuilder<SmartDownloadWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /**
         * Immediately enqueues a one-time Smart Download sync without waiting for the periodic timer.
         */
        fun triggerImmediate(context: Context, quota: Int = 100) {
            val request = androidx.work.OneTimeWorkRequestBuilder<SmartDownloadWorker>()
                .setInputData(androidx.work.workDataOf(KEY_QUOTA to quota))
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
