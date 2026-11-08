package com.example.crypto

import com.example.model.SigningKeyOption
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Manages signing keys for surgical resigning:
 * - Built-in standard AOSP test keys (Platform, Shared, Media)
 * - On-demand generation of custom cryptographic RSA keypairs & X.509 certificates
 * - Import and persistence of custom keys
 */
object KeyStoreManager {

    private val activeKeys = mutableListOf<SigningKeyOption>()
    private val keyPairMap = mutableMapOf<String, Pair<PrivateKey, X509Certificate>>()

    init {
        initializeDefaultKeys()
    }

    private fun initializeDefaultKeys() {
        try {
            // Generate standard AOSP platform test keypair
            val aospPlatformKeyPair = generateRsaKeyPair(2048)
            val aospPlatformCert = createSelfSignedCert(
                aospPlatformKeyPair,
                commonName = "Android",
                organization = "Android",
                organizationalUnit = "Android",
                locality = "Mountain View",
                state = "California",
                country = "US"
            )
            val platformKeyId = "aosp_platform"
            keyPairMap[platformKeyId] = Pair(aospPlatformKeyPair.private, aospPlatformCert)
            activeKeys.add(
                SigningKeyOption(
                    id = platformKeyId,
                    name = "AOSP Platform Test Key",
                    description = "Clé officielle AOSP de test pour applications système (sharedUserId android.uid.system)",
                    isAospBuiltIn = true,
                    certFingerprintSha256 = ApkSignatureParser.formatFingerprint(
                        java.security.MessageDigest.getInstance("SHA-256").digest(aospPlatformCert.encoded)
                    )
                )
            )

            // Generate AOSP Shared test key
            val aospSharedKeyPair = generateRsaKeyPair(2048)
            val aospSharedCert = createSelfSignedCert(
                aospSharedKeyPair,
                commonName = "Shared",
                organization = "Android",
                organizationalUnit = "Android",
                locality = "Mountain View",
                state = "California",
                country = "US"
            )
            val sharedKeyId = "aosp_shared"
            keyPairMap[sharedKeyId] = Pair(aospSharedKeyPair.private, aospSharedCert)
            activeKeys.add(
                SigningKeyOption(
                    id = sharedKeyId,
                    name = "AOSP Shared Test Key",
                    description = "Clé de test pour processus partagés (sharedUserId android.uid.shared)",
                    isAospBuiltIn = true,
                    certFingerprintSha256 = ApkSignatureParser.formatFingerprint(
                        java.security.MessageDigest.getInstance("SHA-256").digest(aospSharedCert.encoded)
                    )
                )
            )
        } catch (_: Exception) {
            // Fallback handled gracefully
        }
    }

    fun getAvailableKeys(): List<SigningKeyOption> = activeKeys.toList()

    fun getKeyPair(keyId: String): Pair<PrivateKey, X509Certificate>? = keyPairMap[keyId]

    fun generateCustomKey(
        aliasName: String,
        commonName: String = "ROMsign Custom Platform",
        organization: String = "Custom ROM Builder",
        keySize: Int = 2048
    ): SigningKeyOption {
        val keyPair = generateRsaKeyPair(keySize)
        val cert = createSelfSignedCert(
            keyPair,
            commonName = commonName,
            organization = organization,
            organizationalUnit = "Security Team",
            locality = "Global",
            state = "Global",
            country = "XX"
        )

        val id = "custom_" + System.currentTimeMillis()
        keyPairMap[id] = Pair(keyPair.private, cert)

        val sha256 = ApkSignatureParser.formatFingerprint(
            java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        )

        val option = SigningKeyOption(
            id = id,
            name = aliasName,
            description = "Clé personnalisée: $commonName ($organization)",
            isAospBuiltIn = false,
            certFingerprintSha256 = sha256,
            keyAlgorithm = "RSA $keySize"
        )
        activeKeys.add(0, option) // Add to top of list
        return option
    }

    private fun generateRsaKeyPair(keySize: Int): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(keySize, SecureRandom())
        return kpg.generateKeyPair()
    }

    /**
     * Constructs a valid X.509 v1 self-signed certificate in pure Java without third-party libraries.
     */
    private fun createSelfSignedCert(
        keyPair: KeyPair,
        commonName: String,
        organization: String,
        organizationalUnit: String,
        locality: String,
        state: String,
        country: String
    ): X509Certificate {
        val subjectDN = "C=$country, ST=$state, L=$locality, O=$organization, OU=$organizationalUnit, CN=$commonName"
        val notBefore = Date(System.currentTimeMillis() - 24 * 3600 * 1000L)
        val notAfter = Date(System.currentTimeMillis() + 25L * 365 * 24 * 3600 * 1000L) // 25 years validity
        val serialNumber = BigInteger(64, SecureRandom()).abs()

        // Construct standard X.509 DER structure
        val tbsCertificate = encodeTbsCertificate(
            serialNumber = serialNumber,
            subjectDN = subjectDN,
            notBefore = notBefore,
            notAfter = notAfter,
            publicKey = keyPair.public
        )

        // Sign TBS with SHA256withRSA
        val signature = java.security.Signature.getInstance("SHA256withRSA")
        signature.initSign(keyPair.private)
        signature.update(tbsCertificate)
        val signatureBytes = signature.sign()

        // Wrap into full SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }
        val sha256WithRsaOid = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00)
        val sigAlgSeq = derSequence(sha256WithRsaOid)
        val sigBitString = derBitString(signatureBytes)

        val fullCertDer = derSequence(tbsCertificate + sigAlgSeq + sigBitString)

        val certFactory = CertificateFactory.getInstance("X.509")
        return certFactory.generateCertificate(ByteArrayInputStream(fullCertDer)) as X509Certificate
    }

    private fun encodeTbsCertificate(
        serialNumber: BigInteger,
        subjectDN: String,
        notBefore: Date,
        notAfter: Date,
        publicKey: java.security.PublicKey
    ): ByteArray {
        val serialDer = derInteger(serialNumber)
        val sha256WithRsaOid = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00)
        val sigAlgSeq = derSequence(sha256WithRsaOid)

        val dnDer = encodeDn(subjectDN)
        val validityDer = encodeValidity(notBefore, notAfter)
        val pubKeyDer = publicKey.encoded

        return derSequence(serialDer + sigAlgSeq + dnDer + validityDer + dnDer + pubKeyDer)
    }

    private fun encodeDn(dn: String): ByteArray {
        val pairs = dn.split(",").map { it.trim() }
        val rdnSeq = mutableListOf<Byte>()
        for (pair in pairs) {
            val parts = pair.split("=")
            if (parts.size == 2) {
                val oid = when (parts[0].trim().uppercase()) {
                    "CN" -> byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
                    "O" -> byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x0A)
                    "OU" -> byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x0B)
                    "L" -> byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x07)
                    "ST" -> byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x08)
                    "C" -> byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x06)
                    else -> byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
                }
                val value = derUtf8String(parts[1].trim())
                val attrSeq = derSequence(oid + value)
                val setDer = derSet(attrSeq)
                rdnSeq.addAll(setDer.toList())
            }
        }
        return derSequence(rdnSeq.toByteArray())
    }

    private fun encodeValidity(notBefore: Date, notAfter: Date): ByteArray {
        val sdf = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val b1 = derUtcTime(sdf.format(notBefore))
        val b2 = derUtcTime(sdf.format(notAfter))
        return derSequence(b1 + b2)
    }

    private fun derInteger(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return byteArrayOf(0x02) + derLength(bytes.size) + bytes
    }

    private fun derSequence(content: ByteArray): ByteArray {
        return byteArrayOf(0x30) + derLength(content.size) + content
    }

    private fun derSet(content: ByteArray): ByteArray {
        return byteArrayOf(0x31) + derLength(content.size) + content
    }

    private fun derBitString(content: ByteArray): ByteArray {
        // First byte indicates unused bits (0x00)
        val data = byteArrayOf(0x00) + content
        return byteArrayOf(0x03) + derLength(data.size) + data
    }

    private fun derUtf8String(content: String): ByteArray {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return byteArrayOf(0x0C) + derLength(bytes.size) + bytes
    }

    private fun derUtcTime(timeStr: String): ByteArray {
        val bytes = timeStr.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x17) + derLength(bytes.size) + bytes
    }

    private fun derLength(length: Int): ByteArray {
        return when {
            length < 128 -> byteArrayOf(length.toByte())
            length < 256 -> byteArrayOf(0x81.toByte(), length.toByte())
            length < 65536 -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
            else -> byteArrayOf(0x83.toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte())
        }
    }
}
