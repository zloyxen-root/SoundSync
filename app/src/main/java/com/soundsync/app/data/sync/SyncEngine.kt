package com.soundsync.app.data.sync

import android.content.Context
import com.soundsync.app.data.local.SoundSyncDatabase
import com.soundsync.app.data.local.SyncStatus
import com.soundsync.app.data.local.TrackEntity
import com.soundsync.app.data.model.Track
import com.soundsync.app.data.preferences.UserPreferences
import com.soundsync.app.data.soundcloud.SoundCloudClient
import com.soundsync.app.util.Id3Tagger
import com.soundsync.app.util.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed class SyncState {
    object Idle : SyncState()
    data class FetchingLikes(val loadedCount: Int) : SyncState()
    data class Diffing(val toDownloadCount: Int, val toDeleteCount: Int) : SyncState()
    data class Downloading(
        val currentTrackIndex: Int,
        val totalTracksToDownload: Int,
        val currentTrackTitle: String,
        val currentTrackProgressBytes: Long,
        val currentTrackTotalBytes: Long
    ) : SyncState()
    data class Completed(
        val addedCount: Int,
        val deletedCount: Int,
        val failedCount: Int
    ) : SyncState()
    data class Error(val message: String) : SyncState()
}

class SyncEngine(private val context: Context) {

    private val db = SoundSyncDatabase.getInstance(context)
    private val trackDao = db.trackDao()
    private val prefs = UserPreferences(context)

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    @Volatile
    private var isCancelled = false

    fun cancelSync() {
        isCancelled = true
    }

    suspend fun performSync(): Result<SyncState.Completed> = withContext(Dispatchers.IO) {
        isCancelled = false
        val userId = prefs.userId
        if (userId <= 0L) {
            val err = "Пользователь не выбран. Укажите профиль SoundCloud в настройках."
            _syncState.value = SyncState.Error(err)
            return@withContext Result.failure(Exception(err))
        }

        val client = SoundCloudClient(
            customClientId = prefs.customClientId.ifBlank { null },
            oauthToken = prefs.oauthToken.ifBlank { null }
        )

        try {
            // 1. Fetch remote liked tracks
            _syncState.value = SyncState.FetchingLikes(0)
            val likesResult = client.fetchAllLikes(userId) { loadedCount ->
                _syncState.value = SyncState.FetchingLikes(loadedCount)
            }

            val remoteTracks = likesResult.getOrElse { error ->
                _syncState.value = SyncState.Error("Ошибка получения списка лайков: ${error.message}")
                return@withContext Result.failure(error)
            }

            if (isCancelled) {
                _syncState.value = SyncState.Idle
                return@withContext Result.failure(Exception("Синхронизация отменена"))
            }

            // 2. Fetch local tracks
            val localTracks = trackDao.getAllTracksList()
            val localMap = localTracks.associateBy { it.trackId }
            val remoteMap = remoteTracks.associateBy { it.id }

            // 3. Diffing
            // Tracks to delete: in local DB but absent in remote likes
            val toDeleteList = if (prefs.autoDeleteUnliked) {
                localTracks.filter { !remoteMap.containsKey(it.trackId) }
            } else {
                emptyList()
            }

            // Tracks to download: in remote likes but not in local DB (or file missing on storage)
            val toDownloadList = remoteTracks.filter { remoteTrack ->
                val local = localMap[remoteTrack.id]
                if (local == null) {
                    true
                } else {
                    // Check if file still exists on disk
                    val fileExists = local.localFilePath?.let { File(it).exists() } == true
                    !fileExists
                }
            }

            _syncState.value = SyncState.Diffing(
                toDownloadCount = toDownloadList.size,
                toDeleteCount = toDeleteList.size
            )

            // 4. Delete unliked tracks
            var deletedCount = 0
            for (localTrack in toDeleteList) {
                if (isCancelled) break
                StorageHelper.deleteTrackFile(context, localTrack.localFilePath, localTrack.mediaStoreUri)
                trackDao.deleteById(localTrack.trackId)
                deletedCount++
            }

            // 5. Download and tag new tracks
            var addedCount = 0
            var failedCount = 0

            for ((index, track) in toDownloadList.withIndex()) {
                if (isCancelled) break

                _syncState.value = SyncState.Downloading(
                    currentTrackIndex = index + 1,
                    totalTracksToDownload = toDownloadList.size,
                    currentTrackTitle = "${track.artist} - ${track.title}",
                    currentTrackProgressBytes = 0L,
                    currentTrackTotalBytes = 0L
                )

                val tempFile = StorageHelper.createTempDownloadFile(context, track.id)
                try {
                    // Download audio stream
                    FileOutputStream(tempFile).use { outStream ->
                        client.downloadTrackAudio(track, outStream) { bytesRead, totalBytes ->
                            _syncState.value = SyncState.Downloading(
                                currentTrackIndex = index + 1,
                                totalTracksToDownload = toDownloadList.size,
                                currentTrackTitle = "${track.artist} - ${track.title}",
                                currentTrackProgressBytes = bytesRead,
                                currentTrackTotalBytes = totalBytes
                            )
                        }
                    }

                    // Download artwork and embed ID3 tags
                    val artworkBytes = client.downloadArtwork(track.artworkUrl)
                    Id3Tagger.tagMp3File(
                        inputFile = tempFile,
                        title = track.title,
                        artist = track.artist,
                        album = "SoundCloud Likes",
                        artworkBytes = artworkBytes
                    )

                    // Move to public Music folder and scan MediaStore
                    val (savedPath, mediaUri) = StorageHelper.saveTrackToPublicStorage(
                        context = context,
                        tempFile = tempFile,
                        track = track
                    )

                    // Save record to Room DB
                    val entity = TrackEntity(
                        trackId = track.id,
                        title = track.title,
                        artist = track.artist,
                        durationMs = track.durationMs,
                        artworkUrl = track.artworkUrl,
                        permalinkUrl = track.permalinkUrl,
                        lastModified = track.lastModified,
                        localFilePath = savedPath,
                        mediaStoreUri = mediaUri?.toString(),
                        fileSizeBytes = File(savedPath).length(),
                        downloadedAt = System.currentTimeMillis(),
                        syncStatus = SyncStatus.SYNCED
                    )
                    trackDao.insertOrUpdate(entity)
                    addedCount++
                } catch (e: Exception) {
                    tempFile.delete()
                    failedCount++
                    // Record failure to DB
                    val failedEntity = TrackEntity(
                        trackId = track.id,
                        title = track.title,
                        artist = track.artist,
                        durationMs = track.durationMs,
                        artworkUrl = track.artworkUrl,
                        permalinkUrl = track.permalinkUrl,
                        lastModified = track.lastModified,
                        localFilePath = null,
                        mediaStoreUri = null,
                        fileSizeBytes = 0L,
                        syncStatus = SyncStatus.FAILED
                    )
                    trackDao.insertOrUpdate(failedEntity)
                }
            }

            prefs.lastSyncTimestamp = System.currentTimeMillis()
            val completed = SyncState.Completed(
                addedCount = addedCount,
                deletedCount = deletedCount,
                failedCount = failedCount
            )
            _syncState.value = completed
            Result.success(completed)

        } catch (e: Exception) {
            val errMsg = "Ошибка выполнения синхронизации: ${e.message}"
            _syncState.value = SyncState.Error(errMsg)
            Result.failure(e)
        }
    }
}
