package com.soundsync.app.data.soundcloud

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.soundsync.app.data.model.LikesResult
import com.soundsync.app.data.model.SoundCloudUserProfile
import com.soundsync.app.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class SoundCloudClient(
    private val customClientId: String? = null,
    private val oauthToken: String? = null
) {
    companion object {
        private const val API_BASE = "https://api-v2.soundcloud.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        // Known fallback client IDs extracted from active web clients
        private val FALLBACK_CLIENT_IDS = listOf(
            "iZIs9mchVcX5lhVRphAREBIC02ozgHpA",
            "2t9loNfhdpU0lqqRDTggmgrvd8xyUSnu",
            "a3e059563d7fd3372b49b37f00a00bcf",
            "N2A9N4S1Z4S7Y0hHlY5hZ4QcW2sZk9a1"
        )

        @Volatile
        private var cachedClientId: String? = null
        private val clientIdMutex = Mutex()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", "https://soundcloud.com")
                .header("Referer", "https://soundcloud.com/")

            if (original.header("Accept") == null) {
                requestBuilder.header("Accept", "application/json, text/javascript, */*; q=0.01")
            }
            chain.proceed(requestBuilder.build())
        }
        .build()

    /**
     * Resolves a public user profile from URL or username/permalink.
     */
    suspend fun resolveUserProfile(input: String): Result<SoundCloudUserProfile> = withContext(Dispatchers.IO) {
        val permalink = extractPermalink(input)
        if (permalink.isBlank()) {
            return@withContext Result.failure(Exception("Не удалось определить имя пользователя из ссылки"))
        }

        executeWithAutoRecovery { clientId ->
            val targetUrl = "https://soundcloud.com/$permalink"
            val encodedTarget = URLEncoder.encode(targetUrl, "UTF-8")
            val url = "$API_BASE/resolve?url=$encodedTarget&client_id=$clientId"

            val reqBuilder = Request.Builder().url(url)
            oauthToken?.let { reqBuilder.addHeader("Authorization", "OAuth $it") }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (response.code == 401) {
                    return@use Result.failure(Exception("HTTP 401 Unauthorized"))
                }
                if (!response.isSuccessful) {
                    return@use Result.failure(Exception("Не удалось найти профиль: HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: return@use Result.failure(Exception("Пустой ответ от сервера"))
                val json = JsonParser.parseString(body).asJsonObject

                val id = json.optLong("id") ?: return@use Result.failure(Exception("ID пользователя не найден"))
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
        }
    }

    /**
     * Fetches all liked tracks with automatic pagination.
     */
    suspend fun fetchAllLikes(
        userId: Long,
        onProgress: (loadedCount: Int) -> Unit = {}
    ): Result<List<Track>> = withContext(Dispatchers.IO) {
        executeWithAutoRecovery { clientId ->
            try {
                val allTracks = mutableListOf<Track>()
                var nextUrl: String? = if (oauthToken.isNullOrBlank()) {
                    "$API_BASE/users/$userId/likes?limit=50&client_id=$clientId"
                } else {
                    "$API_BASE/me/likes/tracks?limit=50&client_id=$clientId"
                }

                while (nextUrl != null) {
                    val finalUrl = if (!nextUrl.contains("client_id=")) {
                        val sep = if (nextUrl.contains("?")) "&" else "?"
                        "$nextUrl${sep}client_id=$clientId"
                    } else {
                        nextUrl.replace(Regex("client_id=[^&]+"), "client_id=$clientId")
                    }

                    val reqBuilder = Request.Builder().url(finalUrl)
                    oauthToken?.let { reqBuilder.addHeader("Authorization", "OAuth $it") }

                    val pageResult = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        if (resp.code == 401) {
                            throw Exception("HTTP 401 Unauthorized")
                        }
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

        val userObj = if (json.has("user") && !json.get("user").isJsonNull && json.get("user").isJsonObject) {
            json.getAsJsonObject("user")
        } else null
        val artist = userObj?.optString("username") ?: "Unknown Artist"

        val durationMs = json.optLong("duration") ?: 0L
        var artworkUrl = json.optString("artwork_url")
        if (artworkUrl == null) {
            artworkUrl = userObj?.optString("avatar_url")
        }
        artworkUrl = artworkUrl?.replace("large.jpg", "t500x500.jpg")
            ?.replace("large.png", "t500x500.png")

        val permalinkUrl = json.optString("permalink_url")
        val genre = json.optString("genre")
        val lastModified = json.optString("last_modified")

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
     * Resolves the actual streaming media URL from transcoding URL with auto-recovery.
     */
    private suspend fun resolveStreamDownloadUrl(transcodingUrl: String): String = withContext(Dispatchers.IO) {
        val result = executeWithAutoRecovery { clientId ->
            val sep = if (transcodingUrl.contains("?")) "&" else "?"
            val url = if (!transcodingUrl.contains("client_id=")) {
                "$transcodingUrl${sep}client_id=$clientId"
            } else {
                transcodingUrl.replace(Regex("client_id=[^&]+"), "client_id=$clientId")
            }

            val reqBuilder = Request.Builder().url(url)
            oauthToken?.let { reqBuilder.addHeader("Authorization", "OAuth $it") }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code == 401) {
                    return@use Result.failure(Exception("HTTP 401 Unauthorized"))
                }
                if (!resp.isSuccessful) {
                    return@use Result.failure(Exception("Ошибка получения стрим-ссылки: HTTP ${resp.code}"))
                }
                val body = resp.body?.string() ?: return@use Result.failure(Exception("Пустой ответ стрима"))
                val json = JsonParser.parseString(body).asJsonObject
                val streamUrl = if (json.has("url") && !json.get("url").isJsonNull) json.get("url").asString else null
                if (streamUrl != null) {
                    Result.success(streamUrl)
                } else {
                    Result.failure(Exception("Поле url отсутствует в ответе"))
                }
            }
        }
        result.getOrThrow()
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
        val playlistReq = Request.Builder().url(m3u8Url).build()
        val segmentUrls = mutableListOf<String>()
        httpClient.newCall(playlistReq).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Ошибка загрузки HLS плейлиста: HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw Exception("M3U8 пустой")

            val lines = body.lines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
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

    /**
     * Executes an API block with automatic retry and Client ID refreshment on 401 errors.
     */
    private suspend fun <T> executeWithAutoRecovery(
        block: suspend (clientId: String) -> Result<T>
    ): Result<T> {
        var clientId = getValidClientId(forceRefresh = false)
        var result = block(clientId)

        // If request returned 401 Unauthorized and not using a fixed custom client ID, refresh & retry
        if (result.isFailure && customClientId.isNullOrBlank()) {
            val errorMsg = result.exceptionOrNull()?.message ?: ""
            if (errorMsg.contains("401") || errorMsg.contains("Unauthorized", ignoreCase = true)) {
                clientId = getValidClientId(forceRefresh = true)
                result = block(clientId)
            }
        }
        return result
    }

    private suspend fun getValidClientId(forceRefresh: Boolean = false): String {
        if (!customClientId.isNullOrBlank()) return customClientId

        clientIdMutex.withLock {
            if (!forceRefresh && cachedClientId != null) {
                return cachedClientId!!
            }

            // 1. Try to scrape live client_id from soundcloud.com scripts
            val scraped = scrapeClientIdFromWeb()
            if (!scraped.isNullOrBlank()) {
                cachedClientId = scraped
                return scraped
            }

            // 2. Try fallbacks
            for (fallback in FALLBACK_CLIENT_IDS) {
                if (verifyClientId(fallback)) {
                    cachedClientId = fallback
                    return fallback
                }
            }

            // Fallback to first
            val fallback = FALLBACK_CLIENT_IDS.first()
            cachedClientId = fallback
            return fallback
        }
    }

    /**
     * Scrapes a live Client ID from SoundCloud's web bundles.
     */
    private suspend fun scrapeClientIdFromWeb(): String? = withContext(Dispatchers.IO) {
        val entryUrls = listOf("https://soundcloud.com/discover", "https://soundcloud.com")
        for (entryUrl in entryUrls) {
            try {
                val webReq = Request.Builder().url(entryUrl).build()
                val html = httpClient.newCall(webReq).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                } ?: continue

                // Find script URLs
                val scriptMatcher = Pattern.compile("""src="([^"]*(?:sndcdn\.com/assets/|/assets/)[^"]+\.js[^"]*)"""").matcher(html)
                val scriptUrls = mutableListOf<String>()
                while (scriptMatcher.find()) {
                    var scriptUrl = scriptMatcher.group(1) ?: continue
                    if (scriptUrl.startsWith("//")) {
                        scriptUrl = "https:$scriptUrl"
                    } else if (scriptUrl.startsWith("/")) {
                        scriptUrl = "https://soundcloud.com$scriptUrl"
                    }
                    scriptUrls.add(scriptUrl)
                }

                val clientIdPatterns = listOf(
                    Pattern.compile("""client_id\s*:\s*["']([a-zA-Z0-9]{32})["']"""),
                    Pattern.compile("""["']client_id["']\s*:\s*["']([a-zA-Z0-9]{32})["']"""),
                    Pattern.compile("""client_id\s*=\s*["']([a-zA-Z0-9]{32})["']"""),
                    Pattern.compile("""clientId\s*[:=]\s*["']([a-zA-Z0-9]{32})["']"""),
                    Pattern.compile("""["']clientId["']\s*:\s*["']([a-zA-Z0-9]{32})["']"""),
                    Pattern.compile("""client_id=([a-zA-Z0-9]{32})""")
                )

                // Inspect scripts in reverse (app bundle scripts are loaded last)
                for (scriptUrl in scriptUrls.reversed()) {
                    try {
                        val jsReq = Request.Builder().url(scriptUrl).build()
                        val jsContent = httpClient.newCall(jsReq).execute().use { resp ->
                            if (resp.isSuccessful) resp.body?.string() else null
                        } ?: continue

                        for (pattern in clientIdPatterns) {
                            val m = pattern.matcher(jsContent)
                            if (m.find()) {
                                val candidate = m.group(1)
                                if (!candidate.isNullOrBlank() && candidate.length == 32) {
                                    if (verifyClientId(candidate)) {
                                        return@withContext candidate
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Continue to next script
                    }
                }
            } catch (e: Exception) {
                // Continue to next entry url
            }
        }
        null
    }

    private suspend fun verifyClientId(clientId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val testUrl = "$API_BASE/resolve?url=https%3A%2F%2Fsoundcloud.com%2Fsoundcloud&client_id=$clientId"
            val req = Request.Builder().url(testUrl).build()
            httpClient.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun extractPermalink(input: String): String {
        var clean = input.trim()
        clean = clean.removePrefix("https://").removePrefix("http://")
        clean = clean.removePrefix("www.").removePrefix("m.").removePrefix("soundcloud.com/")
        if (clean.contains("soundcloud.com/")) {
            clean = clean.substringAfter("soundcloud.com/")
        }
        clean = clean.substringBefore("?").substringBefore("#")

        val subpathsToRemove = listOf("/likes", "/tracks", "/sets", "/albums", "/reposts", "/followers", "/following")
        for (sub in subpathsToRemove) {
            if (clean.endsWith(sub, ignoreCase = true)) {
                clean = clean.dropLast(sub.length)
            }
        }
        return clean.trim('/', '@', ' ')
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

