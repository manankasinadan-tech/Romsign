package com.example.crypto

import com.example.model.ApkDiagnosis
import com.example.model.CertificateDetail
import com.example.model.SignatureSchemeInfo
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Robust APK signature parser detecting V1 (JAR), V2 (APK Block),
 * V3 (Key Rotation), and V4 (fs-verity) schemes, and extracting cryptographic certificates.
 */
object ApkSignatureParser {

    private const val APK_SIGNING_BLOCK_MAGIC_HI = 0x3234206b636f6c42L // "Block 42"
    private const val APK_SIGNING_BLOCK_MAGIC_LO = 0x20676953204b5041L // "APK Sig "

    private const val APK_SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871a
    private const val APK_SIGNATURE_SCHEME_V3_BLOCK_ID = 0xf05368c0.toInt()
    private const val APK_SIGNATURE_SCHEME_V31_BLOCK_ID = 0x1b93ad61
    private const val APK_SIGNATURE_SCHEME_V4_BLOCK_ID = 0x42726577

    fun parseApk(file: File, logCallback: ((String) -> Unit)? = null): ApkDiagnosis {
        val fileName = file.name
        val fileSize = file.length()
        logCallback?.invoke("Analyse du fichier: $fileName (${fileSize / 1024} Ko)...")

        var manifestInfo = BinaryManifestParser.ManifestInfo()
        var hasV1 = false
        val v1Certificates = mutableListOf<X509Certificate>()

        // 1. Inspect ZIP archive entries (V1 & AndroidManifest.xml)
        try {
            ZipFile(file).use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml")
                if (manifestEntry != null) {
                    zip.getInputStream(manifestEntry).use { input ->
                        val bytes = input.readBytes()
                        manifestInfo = BinaryManifestParser.parse(bytes)
                        logCallback?.invoke("Manifest extrait: package=${manifestInfo.packageName ?: "inconnu"}, sharedUserId=${manifestInfo.sharedUserId ?: "aucun"}")
                    }
                }

                val certFactory = CertificateFactory.getInstance("X.509")
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name.uppercase(Locale.US)
                    if (name.startsWith("META-INF/") && (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))) {
                        hasV1 = true
                        try {
                            zip.getInputStream(entry).use { inStream ->
                                val certs = certFactory.generateCertificates(inStream)
                                for (c in certs) {
                                    if (c is X509Certificate) {
                                        v1Certificates.add(c)
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // Fallback if PKCS#7 envelope requires direct parsing
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logCallback?.invoke("Avertissement ZIP: ${e.message}")
        }

        // 2. Inspect APK Signing Block (V2, V3, V3.1, V4)
        var hasV2 = false
        var hasV3 = false
        var hasV31 = false
        var hasV4 = false
        val blockCertificates = mutableListOf<X509Certificate>()

        try {
            val blockResult = inspectApkSigningBlock(file)
            hasV2 = blockResult.hasV2
            hasV3 = blockResult.hasV3
            hasV31 = blockResult.hasV31
            hasV4 = blockResult.hasV4
            blockCertificates.addAll(blockResult.certificates)
            if (hasV2) logCallback?.invoke("Schéma de signature v2 détecté dans l'APK Signing Block")
            if (hasV3) logCallback?.invoke("Schéma de signature v3 (Key Rotation) détecté")
        } catch (e: Exception) {
            logCallback?.invoke("Analyse bloc v2/v3: ${e.message}")
        }

        // Check for companion V4 signature (.idsig)
        val idsigFile = File(file.parentFile, "$fileName.idsig")
        if (idsigFile.exists()) {
            hasV4 = true
            logCallback?.invoke("Fichier d'intégrité fs-verity v4 (.idsig) présent")
        }

        val allCerts = if (blockCertificates.isNotEmpty()) blockCertificates else v1Certificates
        val certDetails = allCerts.distinctBy { it.encoded.contentHashCode() }.map { cert ->
            extractCertDetail(cert)
        }

        val isPlatform = certDetails.any { detail ->
            detail.subjectDN.contains("Android", ignoreCase = true) ||
            detail.subjectDN.contains("platform", ignoreCase = true) ||
            detail.subjectDN.contains("Mountain View", ignoreCase = true)
        }

        val isPrivileged = file.absolutePath.contains("/priv-app/") ||
                manifestInfo.sharedUserId == "android.uid.system"

        logCallback?.invoke("Certificats extraits: ${certDetails.size} identité(s) cryptographique(s)")

        return ApkDiagnosis(
            apkPath = file.absolutePath,
            apkName = fileName,
            packageName = manifestInfo.packageName,
            sharedUserId = manifestInfo.sharedUserId,
            versionName = manifestInfo.versionName,
            versionCode = manifestInfo.versionCode,
            schemes = SignatureSchemeInfo(
                hasV1Jar = hasV1,
                hasV2Scheme = hasV2,
                hasV3Scheme = hasV3,
                hasV4Scheme = hasV4,
                isV31Supported = hasV31
            ),
            certificates = certDetails,
            rawFileSize = fileSize,
            isPlatformSigned = isPlatform,
            isSystemPrivileged = isPrivileged
        )
    }

    private data class SigningBlockResult(
        val hasV2: Boolean = false,
        val hasV3: Boolean = false,
        val hasV31: Boolean = false,
        val hasV4: Boolean = false,
        val certificates: List<X509Certificate> = emptyList()
    )

    private fun inspectApkSigningBlock(file: File): SigningBlockResult {
        RandomAccessFile(file, "r").use { raf ->
            val fileLength = raf.length()
            if (fileLength < 22) return SigningBlockResult()

            // Find End of Central Directory (EOCD)
            val maxEocdSearch = (fileLength - 22).coerceAtLeast(0)
            val searchStart = (fileLength - 65557).coerceAtLeast(0)

            var eocdOffset = -1L
            val searchBuffer = ByteArray((fileLength - searchStart).toInt())
            raf.seek(searchStart)
            raf.readFully(searchBuffer)

            for (i in (searchBuffer.size - 22) downTo 0) {
                if (searchBuffer[i] == 0x50.toByte() &&
                    searchBuffer[i + 1] == 0x4b.toByte() &&
                    searchBuffer[i + 2] == 0x05.toByte() &&
                    searchBuffer[i + 3] == 0x06.toByte()
                ) {
                    eocdOffset = searchStart + i
                    break
                }
            }

            if (eocdOffset == -1L) return SigningBlockResult()

            raf.seek(eocdOffset + 16)
            val cdOffset = java.lang.Integer.toUnsignedLong(readLittleEndianInt(raf))
            if (cdOffset < 32) return SigningBlockResult()

            // APK Signing Block is located immediately before Central Directory
            raf.seek(cdOffset - 24)
            val blockSizeInFooter = raf.readLong() // Little-endian int64
            val magicLo = raf.readLong()
            val magicHi = raf.readLong()

            val reversedBlockSize = java.lang.Long.reverseBytes(blockSizeInFooter)
            val reversedMagicLo = java.lang.Long.reverseBytes(magicLo)
            val reversedMagicHi = java.lang.Long.reverseBytes(magicHi)

            val isValidMagic = (magicLo == APK_SIGNING_BLOCK_MAGIC_LO && magicHi == APK_SIGNING_BLOCK_MAGIC_HI) ||
                    (reversedMagicLo == APK_SIGNING_BLOCK_MAGIC_LO && reversedMagicHi == APK_SIGNING_BLOCK_MAGIC_HI)

            if (!isValidMagic) return SigningBlockResult()

            val blockSize = if (magicLo == APK_SIGNING_BLOCK_MAGIC_LO) blockSizeInFooter else reversedBlockSize
            val blockStartOffset = cdOffset - blockSize - 8
            if (blockStartOffset < 0 || blockStartOffset > fileLength) return SigningBlockResult()

            raf.seek(blockStartOffset)
            val blockSizeInHeader = java.lang.Long.reverseBytes(raf.readLong())
            val actualBlockSize = if (blockSizeInHeader == blockSize) blockSize else java.lang.Long.reverseBytes(blockSizeInHeader)

            // Read the ID-value pairs inside the APK Signing Block
            val dataSize = (actualBlockSize - 24).toInt()
            if (dataSize <= 0 || dataSize > 10 * 1024 * 1024) return SigningBlockResult()

            val blockData = ByteArray(dataSize)
            raf.readFully(blockData)
            val byteBuffer = ByteBuffer.wrap(blockData).order(ByteOrder.LITTLE_ENDIAN)

            var hasV2 = false
            var hasV3 = false
            var hasV31 = false
            var hasV4 = false
            val certs = mutableListOf<X509Certificate>()
            val certFactory = CertificateFactory.getInstance("X.509")

            while (byteBuffer.remaining() >= 12) {
                val pairLen = byteBuffer.long
                if (pairLen < 4 || pairLen > byteBuffer.remaining()) break
                val id = byteBuffer.int
                val valueLen = (pairLen - 4).toInt()

                when (id) {
                    APK_SIGNATURE_SCHEME_V2_BLOCK_ID -> {
                        hasV2 = true
                        val pairBytes = ByteArray(valueLen)
                        byteBuffer.get(pairBytes)
                        extractCertsFromSigners(pairBytes, certFactory, certs)
                    }
                    APK_SIGNATURE_SCHEME_V3_BLOCK_ID -> {
                        hasV3 = true
                        val pairBytes = ByteArray(valueLen)
                        byteBuffer.get(pairBytes)
                        extractCertsFromSigners(pairBytes, certFactory, certs)
                    }
                    APK_SIGNATURE_SCHEME_V31_BLOCK_ID -> {
                        hasV31 = true
                        byteBuffer.position(byteBuffer.position() + valueLen)
                    }
                    APK_SIGNATURE_SCHEME_V4_BLOCK_ID -> {
                        hasV4 = true
                        byteBuffer.position(byteBuffer.position() + valueLen)
                    }
                    else -> {
                        byteBuffer.position(byteBuffer.position() + valueLen)
                    }
                }
            }

            return SigningBlockResult(
                hasV2 = hasV2,
                hasV3 = hasV3,
                hasV31 = hasV31,
                hasV4 = hasV4,
                certificates = certs
            )
        }
    }

    private fun extractCertsFromSigners(
        signerSequenceBytes: ByteArray,
        certFactory: CertificateFactory,
        outCerts: MutableList<X509Certificate>
    ) {
        try {
            val buf = ByteBuffer.wrap(signerSequenceBytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.remaining() < 4) return
            val signersLen = buf.int
            if (signersLen > buf.remaining()) return

            while (buf.remaining() >= 4) {
                val signerLen = buf.int
                if (signerLen > buf.remaining()) break
                val signerBuf = buf.slice().order(ByteOrder.LITTLE_ENDIAN)
                signerBuf.limit(signerLen)
                buf.position(buf.position() + signerLen)

                if (signerBuf.remaining() < 4) continue
                val signedDataLen = signerBuf.int
                if (signedDataLen > signerBuf.remaining()) continue

                val signedDataBuf = signerBuf.slice().order(ByteOrder.LITTLE_ENDIAN)
                signedDataBuf.limit(signedDataLen)

                // Skip digests
                if (signedDataBuf.remaining() < 4) continue
                val digestsLen = signedDataBuf.int
                if (digestsLen > signedDataBuf.remaining()) continue
                signedDataBuf.position(signedDataBuf.position() + digestsLen)

                // Certificates sequence
                if (signedDataBuf.remaining() < 4) continue
                val certsSeqLen = signedDataBuf.int
                if (certsSeqLen > signedDataBuf.remaining()) continue

                val certsBuf = signedDataBuf.slice().order(ByteOrder.LITTLE_ENDIAN)
                certsBuf.limit(certsSeqLen)

                while (certsBuf.remaining() >= 4) {
                    val certLen = certsBuf.int
                    if (certLen > certsBuf.remaining()) break
                    val certBytes = ByteArray(certLen)
                    certsBuf.get(certBytes)
                    try {
                        val cert = certFactory.generateCertificate(certBytes.inputStream())
                        if (cert is X509Certificate) {
                            outCerts.add(cert)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun readLittleEndianInt(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
    }

    private fun extractCertDetail(cert: X509Certificate): CertificateDetail {
        val encoded = cert.encoded
        val sha256 = formatFingerprint(MessageDigest.getInstance("SHA-256").digest(encoded))
        val sha1 = formatFingerprint(MessageDigest.getInstance("SHA-1").digest(encoded))

        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val validFrom = dateFormat.format(cert.notBefore)
        val validTo = dateFormat.format(cert.notAfter)

        val pubKeyAlg = "${cert.publicKey.algorithm} (${cert.sigAlgName})"
        val serialHex = cert.serialNumber.toString(16).uppercase(Locale.ROOT)

        return CertificateDetail(
            subjectDN = cert.subjectDN.name,
            issuerDN = cert.issuerDN.name,
            validFrom = validFrom,
            validTo = validTo,
            sha256Fingerprint = sha256,
            sha1Fingerprint = sha1,
            algorithm = pubKeyAlg,
            serialNumber = serialHex,
            rawEncodedHex = encoded.take(64).toByteArray().toHexString() + "..."
        )
    }

    fun formatFingerprint(bytes: ByteArray): String {
        return bytes.joinToString(":") { "%02X".format(it) }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
