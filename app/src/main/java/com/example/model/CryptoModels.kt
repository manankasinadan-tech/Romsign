package com.example.model

/**
 * Information on APK signature schemes supported by the analyzed package.
 */
data class SignatureSchemeInfo(
    val hasV1Jar: Boolean = false,
    val hasV2Scheme: Boolean = false,
    val hasV3Scheme: Boolean = false,
    val hasV4Scheme: Boolean = false,
    val isV31Supported: Boolean = false
) {
    val summaryText: String
        get() {
            val schemes = mutableListOf<String>()
            if (hasV1Jar) schemes.add("v1 (JAR)")
            if (hasV2Scheme) schemes.add("v2 (APK Block)")
            if (hasV3Scheme) schemes.add("v3 (Rotation)")
            if (hasV4Scheme) schemes.add("v4 (fs-verity)")
            return if (schemes.isEmpty()) "Non signé" else schemes.joinToString(" • ")
        }
}

/**
 * Detailed cryptographic certificate data extracted from the APK.
 */
data class CertificateDetail(
    val subjectDN: String,
    val issuerDN: String,
    val validFrom: String,
    val validTo: String,
    val sha256Fingerprint: String,
    val sha1Fingerprint: String,
    val algorithm: String,
    val serialNumber: String,
    val rawEncodedHex: String = ""
)

/**
 * Diagnostic analysis result of a single APK package.
 */
data class ApkDiagnosis(
    val apkPath: String,
    val apkName: String,
    val packageName: String?,
    val sharedUserId: String?,
    val versionName: String?,
    val versionCode: Long?,
    val schemes: SignatureSchemeInfo,
    val certificates: List<CertificateDetail>,
    val rawFileSize: Long,
    val isPlatformSigned: Boolean = false,
    val isSystemPrivileged: Boolean = false
) {
    val hasCriticalSharedUid: Boolean
        get() = !sharedUserId.isNullOrBlank() && (
            sharedUserId.contains("android.uid.system", ignoreCase = true) ||
            sharedUserId.contains("android.uid.phone", ignoreCase = true) ||
            sharedUserId.contains("android.uid.shared", ignoreCase = true)
        )
}

/**
 * Information about a system permission XML referencing signatures or permissions.
 */
data class PermissionFileInfo(
    val filePath: String,
    val fileName: String,
    val declaredSignatures: List<String>,
    val packagesReferenced: List<String>
)

/**
 * ART or integrity cache file that is invalidated when an APK is resigned.
 */
data class InvalidatedCacheItem(
    val filePath: String,
    val fileName: String,
    val cacheType: String, // ODEX, VDEX, ART, OAT, FSVMETA
    val associatedApk: String,
    val fileSizeBytes: Long
)

/**
 * Global audit result of an unpacked ROM directory.
 */
data class AuditResult(
    val projectPath: String,
    val totalApksScanned: Int,
    val symlinksSkipped: Int,
    val systemCoreApks: List<ApkDiagnosis>,
    val userSpaceApks: List<ApkDiagnosis>,
    val sharedUserIdGroups: Map<String, List<String>>, // sharedUserId -> list of APK names
    val permissionFiles: List<PermissionFileInfo>,
    val invalidatedCaches: List<InvalidatedCacheItem>,
    val reportFilesGenerated: List<String>,
    val executionTimeMs: Long
)

/**
 * Signing key options for surgical resign.
 */
data class SigningKeyOption(
    val id: String,
    val name: String,
    val description: String,
    val isAospBuiltIn: Boolean,
    val certFingerprintSha256: String,
    val keyAlgorithm: String = "RSA 2048"
)

/**
 * Result of a smart resign operation.
 */
data class ResignExecutionResult(
    val projectPath: String,
    val selectedKeyName: String,
    val resignedApksCount: Int,
    val purgedCachesCount: Int,
    val updatedPermissionsCount: Int,
    val backupLocation: String,
    val logs: List<String>,
    val executionTimeMs: Long,
    val isSuccess: Boolean
)
