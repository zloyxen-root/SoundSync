package com.soundsync.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SyncStatus {
    SYNCED,
    DOWNLOADING,
    FAILED
}

@Entity(tableName = "synced_tracks")
data class TrackEntity(
    @PrimaryKey
    val trackId: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val artworkUrl: String?,
    val permalinkUrl: String?,
    val lastModified: String?,
    val localFilePath: String?,
    val mediaStoreUri: String?,
    val fileSizeBytes: Long,
    val downloadedAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.SYNCED
)
