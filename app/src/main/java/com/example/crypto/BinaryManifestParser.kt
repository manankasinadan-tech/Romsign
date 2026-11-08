package com.example.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight binary Android XML parser to extract package name,
 * sharedUserId, versionName, and versionCode from compiled AndroidManifest.xml.
 */
object BinaryManifestParser {

    private const val CHUNK_AXML_HEADER = 0x00080003
    private const val CHUNK_STRING_POOL = 0x001C0001
    private const val CHUNK_START_TAG = 0x00100102
    private const val CHUNK_END_TAG = 0x00100103

    data class ManifestInfo(
        val packageName: String? = null,
        val sharedUserId: String? = null,
        val versionName: String? = null,
        val versionCode: Long? = null
    )

    fun parse(manifestBytes: ByteArray): ManifestInfo {
        if (manifestBytes.size < 8) return ManifestInfo()
        val buffer = ByteBuffer.wrap(manifestBytes).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buffer.int
        if (magic != CHUNK_AXML_HEADER) {
            // Might be plain text XML in uncompiled development archives
            return parsePlainTextXml(manifestBytes)
        }

        buffer.int // chunkSize

        val stringPool = mutableListOf<String>()
        var packageName: String? = null
        var sharedUserId: String? = null
        var versionName: String? = null
        var versionCode: Long? = null

        while (buffer.hasRemaining() && buffer.remaining() >= 8) {
            val chunkType = buffer.int
            val chunkSize = buffer.int
            val chunkStart = buffer.position() - 8

            when (chunkType) {
                CHUNK_STRING_POOL -> {
                    parseStringPool(buffer, chunkStart, chunkSize, stringPool)
                    buffer.position(chunkStart + chunkSize)
                }

                CHUNK_START_TAG -> {
                    if (buffer.remaining() < 20) break
                    buffer.int // lineNumber
                    buffer.int // comment
                    val uriIdx = buffer.int
                    val nameIdx = buffer.int
                    val flags = buffer.short
                    val attrCount = buffer.short
                    buffer.short // idIndex
                    buffer.short // classIndex
                    buffer.short // styleIndex

                    val tagName = stringPool.getOrNull(nameIdx)

                    if (tagName == "manifest") {
                        for (i in 0 until attrCount) {
                            if (buffer.remaining() < 20) break
                            val attrUri = buffer.int
                            val attrNameIdx = buffer.int
                            val attrValStrIdx = buffer.int
                            val attrType = buffer.int
                            val attrData = buffer.int

                            val attrName = stringPool.getOrNull(attrNameIdx)
                            val attrVal = stringPool.getOrNull(attrValStrIdx)

                            when (attrName) {
                                "package" -> {
                                    packageName = attrVal ?: packageName
                                }
                                "sharedUserId" -> {
                                    sharedUserId = attrVal ?: sharedUserId
                                }
                                "versionName" -> {
                                    versionName = attrVal ?: versionName
                                }
                                "versionCode" -> {
                                    versionCode = attrVal?.toLongOrNull() ?: attrData.toLong()
                                }
                            }
                        }
                    }
                    buffer.position(chunkStart + chunkSize)
                }

                else -> {
                    val nextPos = chunkStart + chunkSize
                    if (nextPos in (buffer.position() + 1)..buffer.capacity()) {
                        buffer.position(nextPos)
                    } else {
                        break
                    }
                }
            }
        }

        return ManifestInfo(
            packageName = packageName,
            sharedUserId = sharedUserId,
            versionName = versionName,
            versionCode = versionCode
        )
    }

    private fun parseStringPool(
        buffer: ByteBuffer,
        chunkStart: Int,
        chunkSize: Int,
        outPool: MutableList<String>
    ) {
        val stringCount = buffer.int
        val styleCount = buffer.int
        val flags = buffer.int
        val stringsStart = buffer.int
        val stylesStart = buffer.int

        val isUtf8 = (flags and (1 shl 8)) != 0
        val stringOffsets = IntArray(stringCount)
        for (i in 0 until stringCount) {
            stringOffsets[i] = buffer.int
        }

        val stringsAbsoluteStart = chunkStart + stringsStart

        for (offset in stringOffsets) {
            val strPos = stringsAbsoluteStart + offset
            if (strPos in 0 until buffer.capacity()) {
                buffer.position(strPos)
                val str = if (isUtf8) readUtf8String(buffer) else readUtf16String(buffer)
                outPool.add(str)
            } else {
                outPool.add("")
            }
        }
    }

    private fun readUtf8String(buffer: ByteBuffer): String {
        // UTF-8 length prefix (can be 1 or 2 bytes)
        var charCount = buffer.get().toInt() and 0xFF
        if ((charCount and 0x80) != 0) {
            charCount = ((charCount and 0x7F) shl 8) or (buffer.get().toInt() and 0xFF)
        }
        var byteCount = buffer.get().toInt() and 0xFF
        if ((byteCount and 0x80) != 0) {
            byteCount = ((byteCount and 0x7F) shl 8) or (buffer.get().toInt() and 0xFF)
        }
        val bytes = ByteArray(byteCount.coerceAtMost(buffer.remaining()))
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readUtf16String(buffer: ByteBuffer): String {
        var charCount = buffer.short.toInt() and 0xFFFF
        if ((charCount and 0x8000) != 0) {
            charCount = ((charCount and 0x7FFF) shl 16) or (buffer.short.toInt() and 0xFFFF)
        }
        val chars = CharArray(charCount.coerceAtMost(buffer.remaining() / 2))
        for (i in chars.indices) {
            chars[i] = buffer.char
        }
        return String(chars)
    }

    private fun parsePlainTextXml(bytes: ByteArray): ManifestInfo {
        return try {
            val text = String(bytes, Charsets.UTF_8)
            val packageRegex = Regex("""package\s*=\s*["']([^"']+)["']""")
            val sharedUserRegex = Regex("""sharedUserId\s*=\s*["']([^"']+)["']""")
            val versionNameRegex = Regex("""versionName\s*=\s*["']([^"']+)["']""")
            val versionCodeRegex = Regex("""versionCode\s*=\s*["']([^"']+)["']""")

            ManifestInfo(
                packageName = packageRegex.find(text)?.groupValues?.get(1),
                sharedUserId = sharedUserRegex.find(text)?.groupValues?.get(1),
                versionName = versionNameRegex.find(text)?.groupValues?.get(1),
                versionCode = versionCodeRegex.find(text)?.groupValues?.get(1)?.toLongOrNull()
            )
        } catch (_: Exception) {
            ManifestInfo()
        }
    }
}
