package com.soundsync.app.util

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.soundsync.app.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

object StorageHelper {

    private const val FOLDER_NAME = "SoundSync"

    /**
     * Gets the target directory in external Music folder.
     */
    fun getMusicDirectory(context: Context): File {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val targetDir = File(musicDir, FOLDER_NAME)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        return targetDir
    }

    /**
     * Creates a temporary file in app cache for downloading before tagging and moving.
     */
    fun createTempDownloadFile(context: Context, trackId: Long): File {
        val cacheDir = File(context.cacheDir, "downloads")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return File(cacheDir, "track_${trackId}_${System.currentTimeMillis()}.tmp")
    }

    /**
     * Generates a safe file name for a track: "Artist - Title.mp3".
     */
    fun sanitizeFileName(track: Track): String {
        val cleanArtist = sanitizeString(track.artist)
        val cleanTitle = sanitizeString(track.title)
        val baseName = if (cleanArtist.isNotBlank()) "$cleanArtist - $cleanTitle" else cleanTitle
        // Limit length to prevent filesystem limits
        val truncated = if (baseName.length > 120) baseName.take(120) else baseName
        return "$truncated.mp3"
    }

    private fun sanitizeString(input: String): String {
        return input.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Moves a tagged temporary file to the public Music/SoundSync directory and registers it with MediaStore.
     */
    suspend fun saveTrackToPublicStorage(
        context: Context,
        tempFile: File,
        track: Track
    ): Pair<String, Uri?> = withContext(Dispatchers.IO) {
        val targetFileName = sanitizeFileName(track)
        val targetDir = getMusicDirectory(context)
        val targetFile = File(targetDir, targetFileName)

        // Copy/Move temp file to destination
        FileInputStream(tempFile).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        tempFile.delete()

        // Scan file so MediaStore updates
        var mediaUri: Uri? = null
        MediaScannerConnection.scanFile(
            context,
            arrayOf(targetFile.absolutePath),
            arrayOf("audio/mpeg")
        ) { _, uri ->
            mediaUri = uri
        }

        Pair(targetFile.absolutePath, mediaUri)
    }

    /**
     * Deletes a local MP3 file and removes it from MediaStore.
     */
    suspend fun deleteTrackFile(
        context: Context,
        filePath: String?,
        mediaStoreUriString: String?
    ): Boolean = withContext(Dispatchers.IO) {
        var deleted = false

        // 1. Delete physical file if exists
        if (!filePath.isNullOrBlank()) {
            val file = File(filePath)
            if (file.exists()) {
                deleted = file.delete()
            }
        }

        // 2. Remove from MediaStore
        if (!mediaStoreUriString.isNullOrBlank()) {
            try {
                val uri = Uri.parse(mediaStoreUriString)
                context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                // MediaStore deletion might require user consent on some Android 11+ scopes
            }
        }

        deleted
    }
}
