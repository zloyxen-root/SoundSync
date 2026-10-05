package com.soundsync.app.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

object Id3Tagger {

    /**
     * Tags an MP3 file with title, artist, album and artwork.
     * Rewrites ID3v2.3 header at the beginning of the file safely.
     */
    fun tagMp3File(
        inputFile: File,
        title: String,
        artist: String,
        album: String = "SoundCloud Likes",
        artworkBytes: ByteArray? = null
    ) {
        if (!inputFile.exists() || inputFile.length() == 0L) return

        val tagBytes = createId3v2Tag(title, artist, album, artworkBytes)

        // Read audio stream skipping existing ID3 header if present
        var audioOffset = 0L
        FileInputStream(inputFile).use { fis ->
            val header = ByteArray(10)
            val read = fis.read(header)
            if (read == 10 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                val tagSize = decodeSynchsafeInt(header, 6)
                audioOffset = 10L + tagSize
            }
        }

        val tempFile = File(inputFile.parentFile, "${inputFile.name}.tagged.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                // Write ID3 tag
                fos.write(tagBytes)
                
                // Copy original audio stream
                FileInputStream(inputFile).use { fis ->
                    if (audioOffset > 0) {
                        fis.skip(audioOffset)
                    }
                    val buffer = ByteArray(64 * 1024)
                    var len: Int
                    while (fis.read(buffer).also { len = it } != -1) {
                        fos.write(buffer, 0, len)
                    }
                }
                fos.flush()
            }

            if (tempFile.exists() && tempFile.length() > tagBytes.size) {
                inputFile.delete()
                tempFile.renameTo(inputFile)
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun createId3v2Tag(
        title: String,
        artist: String,
        album: String,
        artworkBytes: ByteArray?
    ): ByteArray {
        val framesStream = ByteArrayOutputStream()

        // TIT2 (Title)
        writeTextFrame(framesStream, "TIT2", title)
        // TPE1 (Lead performer / Artist)
        writeTextFrame(framesStream, "TPE1", artist)
        // TALB (Album)
        writeTextFrame(framesStream, "TALB", album)

        // APIC (Attached picture)
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            writePictureFrame(framesStream, artworkBytes)
        }

        val framesData = framesStream.toByteArray()
        val tagHeader = ByteArray(10)
        tagHeader[0] = 'I'.code.toByte()
        tagHeader[1] = 'D'.code.toByte()
        tagHeader[2] = '3'.code.toByte()
        tagHeader[3] = 3 // ID3v2.3
        tagHeader[4] = 0 // revision
        tagHeader[5] = 0 // flags

        encodeSynchsafeInt(framesData.size, tagHeader, 6)

        val fullTag = ByteArrayOutputStream()
        fullTag.write(tagHeader)
        fullTag.write(framesData)
        return fullTag.toByteArray()
    }

    private fun writeTextFrame(stream: ByteArrayOutputStream, frameId: String, text: String) {
        val frameIdBytes = frameId.toByteArray(StandardCharsets.US_ASCII)
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArrayOutputStream()
        payload.write(3) // UTF-8 encoding flag in ID3v2.4 or 1/3 in v2.3
        payload.write(textBytes)

        val payloadBytes = payload.toByteArray()
        stream.write(frameIdBytes)
        // 4 bytes frame size (standard int in ID3v2.3)
        stream.write((payloadBytes.size shr 24).toByte().toInt())
        stream.write((payloadBytes.size shr 16).toByte().toInt())
        stream.write((payloadBytes.size shr 8).toByte().toInt())
        stream.write(payloadBytes.size.toByte().toInt())
        // 2 bytes flags
        stream.write(0)
        stream.write(0)
        // Payload
        stream.write(payloadBytes)
    }

    private fun writePictureFrame(stream: ByteArrayOutputStream, pictureData: ByteArray) {
        val frameIdBytes = "APIC".toByteArray(StandardCharsets.US_ASCII)
        val isPng = pictureData.size > 8 && pictureData[0] == 0x89.toByte() && pictureData[1] == 0x50.toByte()
        val mimeType = if (isPng) "image/png" else "image/jpeg"
        val mimeBytes = mimeType.toByteArray(StandardCharsets.ISO_8859_1)

        val payload = ByteArrayOutputStream()
        payload.write(0) // ISO-8859-1 encoding for description/mime
        payload.write(mimeBytes)
        payload.write(0) // Null-terminator for MIME string
        payload.write(3) // Picture type: 3 = Cover (front)
        payload.write(0) // Description null-terminator (empty description)
        payload.write(pictureData)

        val payloadBytes = payload.toByteArray()
        stream.write(frameIdBytes)
        stream.write((payloadBytes.size shr 24).toByte().toInt())
        stream.write((payloadBytes.size shr 16).toByte().toInt())
        stream.write((payloadBytes.size shr 8).toByte().toInt())
        stream.write(payloadBytes.size.toByte().toInt())
        stream.write(0)
        stream.write(0)
        stream.write(payloadBytes)
    }

    private fun decodeSynchsafeInt(bytes: ByteArray, offset: Int): Int {
        var result = 0
        for (i in 0 until 4) {
            result = (result shl 7) or (bytes[offset + i].toInt() and 0x7F)
        }
        return result
    }

    private fun encodeSynchsafeInt(value: Int, dest: ByteArray, offset: Int) {
        dest[offset] = ((value shr 21) and 0x7F).toByte()
        dest[offset + 1] = ((value shr 14) and 0x7F).toByte()
        dest[offset + 2] = ((value shr 7) and 0x7F).toByte()
        dest[offset + 3] = (value and 0x7F).toByte()
    }
}
