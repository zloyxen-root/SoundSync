package com.soundsync.app.data.soundcloud

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.soundsync.app.data.model.LikesResult
import com.soundsync.app.data.model.SoundCloudUserProfile
import com.soundsync.app.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class SoundCloudClient(
    private val customClientId: String? = null,
    private val oauthToken: String? = null
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    private var activeClientId: String = customClientId ?: "jZTe9uYwS5kZ5Y0hHlY5hZ4QcW2sZk9a"

    companion object {
        private const val API_BASE = "https://api-v2.soundcloud.com"
        private const val DEFAULT_FALLBACK_CLIENT_ID = "iZIs9mchVcX5lhVRphAREBIC02ozgHpA"
    }

    /**
     * Resolves a public user profile from URL or username/permalink.
     */
    suspend fun resolveUserProfile(input: String): Result<SoundCloudUserProfile> = withContext(Dispatchers.IO) {
        try {
            val permalink = extractPermalink(input)
            val clientId = getValidClientId()
            
            val url = "$API_BASE/resolve?url=https://soundcloud.com/$permalink&client_id=$clientId"
            val requestBuilder = Request.Builder().url(url)
            oauthToken?.let { requestBuilder.addHeader("Authorization", "OAuth $it") }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Не удалось найти профиль: HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Пустой ответ от сервера"))
                val json = JsonParser.parseString(body).asJsonObject

                val id = json.optLong("id") ?: return@withContext Result.failure(Exception("ID пользователя не найден"))
                val username = json.optString("username") ?: permalink
                val avatarUrl = json.optString("avatar_url")
                val likesCount = json.optInt("likes_count") ?: json.optInt("public_favorites_count") ?: 0

                Result.success(
                    SoundCloudUserProfile(
                        id = id,
                        username = username,
                        permalink = permalink,
                        avatarUrl = avatarUrl,
                        likesCount = likesCount
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches all liked tracks with automatic pagination.
     */
    suspend fun fetchAllLikes(
        userId: Long,
        onProgress: (loadedCount: Int) -> Unit = {}
    ): Result<List<Track>> = withContext(Dispatchers.IO) {
        try {
            val allTracks = mutableListOf<Track>()
            var nextUrl: String? = null
            val clientId = getValidClientId()

            if (oauthToken.isNullOrBlank()) {
                nextUrl = "$API_BASE/users/$userId/likes?limit=50&client_id=$clientId"
            } else {
                nextUrl = "$API_BASE/me/likes/tracks?limit=50&client_id=$clientId"
            }

            while (nextUrl != null) {
                // Ensure client_id is present in nextUrl
                val finalUrl = if (!nextUrl.contains("client_id=")) {
                    val sep = if (nextUrl.contains("?")) "&" else "?"
                    "$nextUrl${sep}client_id=$clientId"
                } else {
                    nextUrl
                }

                val reqBuilder = Request.Builder().url(finalUrl)
                oauthToken?.let { reqBuilder.addHeader("Authorization", "OAuth $it") }

                val pageResult = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw Exception("Ошибка загрузки лайков: HTTP ${resp.code}")
                    }
                    val body = resp.body?.string() ?: throw Exception("Пустой ответ от API")
                    parseLikesResponse(body)
                }

                allTracks.addAll(pageResult.tracks)
                onProgress(allTracks.size)
                nextUrl = pageResult.nextHref
            }

            Result.success(allTracks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseLikesResponse(jsonString: String): LikesResult {
        val tracks = mutableListOf<Track>()
        val root = JsonParser.parseString(jsonString).asJsonObject
        
        val collection = if (root.has("collection") && !root.get("collection").isJsonNull && root.get("collection").isJsonArray) {
            root.getAsJsonArray("collection")
        } else {
            JsonArray()
        }

        for (elem in collection) {
            if (elem == null || elem.isJsonNull || !elem.isJsonObject) continue
            val item = elem.asJsonObject
            // Item can be track directly or like wrapper { "track": {...} }
            val trackObj = if (item.has("track") && !item.get("track").isJsonNull && item.get("track").isJsonObject) {
                item.getAsJsonObject("track")
            } else {
                item
            }
            
            if (trackObj != null && trackObj.has("id") && !trackObj.get("id").isJsonNull) {
                parseSingleTrack(trackObj)?.let { tracks.add(it) }
            }
        }

        val nextHref = if (root.has("next_href") && !root.get("next_href").isJsonNull) {
            root.get("next_href").asString
        } else {
            null
        }
        return LikesResult(tracks = tracks, nextHref = nextHref)
    }

    private fun parseSingleTrack(json: JsonObject): Track? {
        val id = json.optLong("id") ?: return null
        val title = json.optString("title") ?: "Untitled"
        
        // Artist logic: use user.username or extract from title
        val userObj = if (json.has("user") && !json.get("user").isJsonNull && json.get("user").isJsonObject) {
            json.getAsJsonObject("user")
        } else null
        val artist = userObj?.optString("username") ?: "Unknown Artist"
        
        val durationMs = json.optLong("duration") ?: 0L
        var artworkUrl = json.optString("artwork_url")
        if (artworkUrl == null) {
            artworkUrl = userObj?.optString("avatar_url")
        }
        // Upgrade artwork to t500x500 for best quality
        artworkUrl = artworkUrl?.replace("large.jpg", "t500x500.jpg")
            ?.replace("large.png", "t500x500.png")

        val permalinkUrl = json.optString("permalink_url")
        val genre = json.optString("genre")
        val lastModified = json.optString("last_modified")

        // Media transcodings
        var progressiveUrl: String? = null
        var hlsUrl: String? = null

        val media = if (json.has("media") && !json.get("media").isJsonNull && json.get("media").isJsonObject) {
            json.getAsJsonObject("media")
        } else null

        if (media != null && media.has("transcodings") && !media.get("transcodings").isJsonNull && media.get("transcodings").isJsonArray) {
            val transcodings = media.getAsJsonArray("transcodings")
            for (t in transcodings) {
                if (t == null || t.isJsonNull || !t.isJsonObject) continue
                val tObj = t.asJsonObject
                val url = tObj.optString("url") ?: continue
                val format = if (tObj.has("format") && !tObj.get("format").isJsonNull && tObj.get("format").isJsonObject) {
                    tObj.getAsJsonObject("format")
                } else null
                val protocol = format?.optString("protocol") ?: ""
                val mimeType = format?.optString("mime_type") ?: ""

                if (protocol == "progressive" && mimeType.contains("mpeg")) {
                    progressiveUrl = url
                } else if (protocol == "hls" && hlsUrl == null) {
                    hlsUrl = url
                }
            }
        }

        return Track(
            id = id,
            title = title,
            artist = artist,
            durationMs = durationMs,
            artworkUrl = artworkUrl,
            permalinkUrl = permalinkUrl,
            genre = genre,
            lastModified = lastModified,
            transcodingProgressiveUrl = progressiveUrl,
            transcodingHlsUrl = hlsUrl
        )
    }

    /**
     * Resolves the actual streaming media URL from transcoding URL.
     */
    private suspend fun resolveStreamDownloadUrl(transcodingUrl: String): String = withContext(Dispatchers.IO) {
        val clientId = getValidClientId()
        val sep = if (transcodingUrl.contains("?")) "&" else "?"
        val url = "$transcodingUrl${sep}client_id=$clientId"

        val reqBuilder = Request.Builder().url(url)
        oauthToken?.let { reqBuilder.addHeader("Authorization", "OAuth $it") }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Ошибка получения стрим-ссылки: HTTP ${resp.code}")
            }
            val body = resp.body?.string() ?: throw Exception("Пустой ответ стрима")
            val json = JsonParser.parseString(body).asJsonObject
            val streamUrl = if (json.has("url") && !json.get("url").isJsonNull) json.get("url").asString else null
            streamUrl ?: throw Exception("Поле url отсутствует в ответе")
        }
    }

    /**
     * Downloads track audio to the output stream (handling progressive or HLS segments).
     */
    suspend fun downloadTrackAudio(
        track: Track,
        outputStream: OutputStream,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Unit = withContext(Dispatchers.IO) {
        val progressiveTranscoding = track.transcodingProgressiveUrl
        val hlsTranscoding = track.transcodingHlsUrl

        when {
            progressiveTranscoding != null -> {
                val directUrl = resolveStreamDownloadUrl(progressiveTranscoding)
                downloadDirectStream(directUrl, outputStream, onProgress)
            }
            hlsTranscoding != null -> {
                val playlistUrl = resolveStreamDownloadUrl(hlsTranscoding)
                downloadHlsStream(playlistUrl, outputStream, onProgress)
            }
            else -> {
                throw Exception("Для трека '${track.title}' нет доступных потоков воспроизведения")
            }
        }
    }

    private fun downloadDirectStream(
        directUrl: String,
        outputStream: OutputStream,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ) {
        val req = Request.Builder().url(directUrl).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Ошибка загрузки файла: HTTP ${resp.code}")
            val body = resp.body ?: throw Exception("Тело ответа пустое")
            val contentLength = body.contentLength()
            
            body.byteStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                var bytesReadTotal = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                    bytesReadTotal += read
                    onProgress(bytesReadTotal, contentLength)
                }
                outputStream.flush()
            }
        }
    }

    private fun downloadHlsStream(
        m3u8Url: String,
        outputStream: OutputStream,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ) {
        // 1. Fetch M3U8 Playlist
        val playlistReq = Request.Builder().url(m3u8Url).build()
        val segmentUrls = mutableListOf<String>()
        httpClient.newCall(playlistReq).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Ошибка загрузки HLS плейлиста: HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw Exception("M3U8 пустой")
            
            val lines = body.lines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    // Segment URL (absolute or relative)
                    val fullUrl = if (trimmed.startsWith("http")) {
                        trimmed
                    } else {
                        val base = m3u8Url.substringBeforeLast('/')
                        "$base/$trimmed"
                    }
                    segmentUrls.add(fullUrl)
                }
            }
        }

        if (segmentUrls.isEmpty()) {
            throw Exception("HLS плейлист не содержит сегментов")
        }

        // 2. Download all segments in order
        var bytesReadTotal = 0L
        val buffer = ByteArray(32 * 1024)

        for ((index, segUrl) in segmentUrls.withIndex()) {
            val segReq = Request.Builder().url(segUrl).build()
            httpClient.newCall(segReq).execute().use { segResp ->
                if (segResp.isSuccessful) {
                    segResp.body?.byteStream()?.use { input ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                            bytesReadTotal += read
                        }
                    }
                }
            }
            onProgress(index.toLong() + 1L, segmentUrls.size.toLong())
        }
        outputStream.flush()
    }

    /**
     * Downloads artwork image as byte array.
     */
    suspend fun downloadArtwork(artworkUrl: String?): ByteArray? = withContext(Dispatchers.IO) {
        if (artworkUrl.isNullOrBlank()) return@withContext null
        try {
            val req = Request.Builder().url(artworkUrl).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.bytes()
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getValidClientId(): String {
        if (customClientId != null) return customClientId
        return activeClientId
    }

    private fun extractPermalink(input: String): String {
        var clean = input.trim()
        if (clean.contains("soundcloud.com/")) {
            clean = clean.substringAfter("soundcloud.com/").substringBefore("?").substringBefore("/")
        }
        return clean.trim('/')
    }

    private fun JsonObject.optString(key: String): String? {
        if (!has(key)) return null
        val elem = get(key)
        if (elem == null || elem.isJsonNull) return null
        return try { elem.asString } catch (e: Exception) { null }
    }

    private fun JsonObject.optLong(key: String): Long? {
        if (!has(key)) return null
        val elem = get(key)
        if (elem == null || elem.isJsonNull) return null
        return try { elem.asLong } catch (e: Exception) { null }
    }

    private fun JsonObject.optInt(key: String): Int? {
        if (!has(key)) return null
        val elem = get(key)
        if (elem == null || elem.isJsonNull) return null
        return try { elem.asInt } catch (e: Exception) { null }
    }
}
