package com.soundsync.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Clean data model representing a SoundCloud track for syncing and storage.
 */
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val artworkUrl: String?,
    val permalinkUrl: String?,
    val genre: String?,
    val lastModified: String?,
    val transcodingProgressiveUrl: String? = null,
    val transcodingHlsUrl: String? = null
)

/**
 * Information about the SoundCloud user profile.
 */
data class SoundCloudUserProfile(
    val id: Long,
    val username: String,
    val permalink: String,
    val avatarUrl: String?,
    val likesCount: Int
)

/**
 * Result wrapper for fetching likes with pagination support.
 */
data class LikesResult(
    val tracks: List<Track>,
    val nextHref: String?
)
