package com.example.signer

import android.content.Context
import com.example.audit.RomAuditEngine
import com.example.crypto.ApkSignatureParser
import com.example.crypto.KeyStoreManager
import com.example.model.ResignExecutionResult
import com.example.model.SigningKeyOption
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Surgical resigning engine for Android unpacked ROMs.
 * Only targets system core and trust-chain dependent APKs (e.g. sharedUserId android.uid.system).
 * Excludes symlinks strictly.
 * Purges obsolete ART caches (.odex, .vdex, .art, .oat, .fsvmeta) to prevent bootloops.
 * Updates system permission XML definitions.
 */
class SmartResignEngine(private val context: Context) {

    fun executeSmartResign(
        projectPath: String,
        selectedKey: SigningKeyOption,
        onLog: (String) -> Unit
    ): ResignExecutionResult {
        val startTime = System.currentTimeMillis()
        val logs = mutableListOf<String>()

        fun log(msg: String) {
            logs.add(msg)
            onLog(msg)
        }

        log("Initialisation de la résignature chirurgicale...")
        log("Projet cible : $projectPath")
        log("Clé sélectionnée : ${selectedKey.name} (${selectedKey.keyAlgorithm})")
        log("Empreinte SHA-256 de la clé : ${selectedKey.certFingerprintSha256}")

        val targetDir = File(projectPath)
        if (!targetDir.exists()) {
            log("ERREUR: Le répertoire '$projectPath' n'existe pas.")
            return ResignExecutionResult(
                projectPath = projectPath,
                selectedKeyName = selectedKey.name,
                resignedApksCount = 0,
                purgedCachesCount = 0,
                updatedPermissionsCount = 0,
                backupLocation = "",
                logs = logs,
                executionTimeMs = System.currentTimeMillis() - startTime,
                isSuccess = false
            )
        }

        val keyPair = KeyStoreManager.getKeyPair(selectedKey.id)
        if (keyPair == null) {
            log("ERREUR: Impossible de charger la paire de clés cryptographiques pour '${selectedKey.name}'.")
            return ResignExecutionResult(
                projectPath = projectPath,
                selectedKeyName = selectedKey.name,
                resignedApksCount = 0,
                purgedCachesCount = 0,
                updatedPermissionsCount = 0,
                backupLocation = "",
                logs = logs,
                executionTimeMs = System.currentTimeMillis() - startTime,
                isSuccess = false
            )
        }
        val (privateKey, cert) = keyPair

        // 1. Audit and Discovery (with strict symlink exclusion)
        val auditEngine = RomAuditEngine(context)
        log("Audit préalable du périmètre pour identifier la chaîne de confiance...")
        val auditResult = auditEngine.executeAudit(projectPath) { log(it) }

        val backupDir = File(auditEngine.getSignerDataDirectory(), "backup")
        val apksBackupDir = File(backupDir, "original_apks")
        apksBackupDir.mkdirs()

        // 2. Select only APKs with systemic / trust-chain impact
        // Exclude isolated APKs that have no sharedUserId and are not part of core framework
        val apksToResign = auditResult.systemCoreApks.ifEmpty {
            // If no specific sharedUid was tagged, target priv-app and framework apks
            auditResult.userSpaceApks.filter { it.isSystemPrivileged }
        }.ifEmpty {
            // Fallback: If only test apks exist, sign all discovered
            auditResult.userSpaceApks
        }

        log("--- SÉLECTION CHIRURGICALE ---")
        log("${apksToResign.size} APK(s) sélectionné(s) pour résignature (impact direct sur le boot et intégrité système).")
        val isolatedCount = auditResult.totalApksScanned - apksToResign.size
        log("$isolatedCount APK(s) isolé(s) exclu(s) pour préserver l'intégrité de la cible.")

        var resignedCount = 0

        for ((idx, apkDiag) in apksToResign.withIndex()) {
            val apkFile = File(apkDiag.apkPath)
            if (!apkFile.exists() || isSymbolicLink(apkFile)) {
                log("Ignoré (symlink ou introuvable): ${apkFile.name}")
                continue
            }

            log("[${idx + 1}/${apksToResign.size}] Résignature de ${apkFile.name} (sharedUserId=${apkDiag.sharedUserId ?: "aucun"})...")

            // Backup original APK
            try {
                val bkpFile = File(apksBackupDir, apkFile.name)
                copyFile(apkFile, bkpFile)
            } catch (e: Exception) {
                log("Avertissement backup: ${e.message}")
            }

            // Resign APK
            val success = resignApkFile(apkFile, privateKey, cert) { log(it) }
            if (success) {
                resignedCount++
                log("✓ ${apkFile.name} résigné avec succès.")
            } else {
                log("✗ Échec de la résignature pour ${apkFile.name}.")
            }
        }

        // 3. Post-processing: Purge obsolete ART caches (.odex, .vdex, .art, .oat, .fsvmeta)
        log("--- POST-TRAITEMENT ET RÉGÉNÉRATION DU SYSTÈME ---")
        log("Purge des caches d'optimisation ART obsolètes pour forcer dex2oat au premier boot...")

        var purgedCount = 0
        for (cacheItem in auditResult.invalidatedCaches) {
            val cacheFile = File(cacheItem.filePath)
            if (cacheFile.exists() && !isSymbolicLink(cacheFile)) {
                try {
                    // Backup cache item before purge
                    val cacheBackupDir = File(backupDir, "purged_caches")
                    cacheBackupDir.mkdirs()
                    val bkp = File(cacheBackupDir, cacheFile.name)
                    copyFile(cacheFile, bkp)

                    if (cacheFile.delete()) {
                        purgedCount++
                        log("Purge: ${cacheFile.name} (${cacheItem.cacheType}) supprimé.")
                    }
                } catch (e: Exception) {
                    log("Avertissement purge cache: ${e.message}")
                }
            }
        }

        // 4. Update system permission XMLs
        log("Mise à jour des fichiers de permissions système...")
        var updatedPermsCount = 0
        val targetSha256 = selectedKey.certFingerprintSha256.replace(":", "").lowercase()

        for (permInfo in auditResult.permissionFiles) {
            val permFile = File(permInfo.filePath)
            if (permFile.exists() && !isSymbolicLink(permFile) && permInfo.declaredSignatures.isNotEmpty()) {
                try {
                    var content = permFile.readText(Charsets.UTF_8)
                    var modified = false
                    for (oldSig in permInfo.declaredSignatures) {
                        if (oldSig.isNotBlank() && oldSig != targetSha256) {
                            content = content.replace(oldSig, targetSha256)
                            modified = true
                        }
                    }
                    if (modified) {
                        permFile.writeText(content, Charsets.UTF_8)
                        updatedPermsCount++
                        log("Fichier de permission actualisé avec la nouvelle signature: ${permFile.name}")
                    }
                } catch (e: Exception) {
                    log("Erreur mise à jour permission: ${e.message}")
                }
            }
        }

        val totalTime = System.currentTimeMillis() - startTime
        log("==================================================")
        log("RÉSIGNATURE TERMINÉE EN ${totalTime} ms")
        log("APKs résignés : $resignedCount / ${apksToResign.size}")
        log("Caches ART purgés : $purgedCount")
        log("Permissions actualisées : $updatedPermsCount")
        log("Dossier de sauvegarde : ${backupDir.absolutePath}")
        log("==================================================")

        return ResignExecutionResult(
            projectPath = projectPath,
            selectedKeyName = selectedKey.name,
            resignedApksCount = resignedCount,
            purgedCachesCount = purgedCount,
            updatedPermissionsCount = updatedPermsCount,
            backupLocation = backupDir.absolutePath,
            logs = logs,
            executionTimeMs = totalTime,
            isSuccess = resignedCount > 0
        )
    }

    private fun resignApkFile(
        apkFile: File,
        privateKey: PrivateKey,
        cert: X509Certificate,
        onSubLog: (String) -> Unit
    ): Boolean {
        return try {
            val tempOutputFile = File(apkFile.parentFile, "${apkFile.name}.signing_tmp")

            // Read existing entries, strip old META-INF signatures
            val entriesData = mutableMapOf<String, ByteArray>()
            val manifest = Manifest()
            manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            manifest.mainAttributes[Attributes.Name("Created-By")] = "ROMsign Surgical Engine 1.0"

            val md = MessageDigest.getInstance("SHA-256")

            ZipInputStream(FileInputStream(apkFile)).use { zis ->
                var entry: ZipEntry?
                while (zis.nextEntry.also { entry = it } != null) {
                    val entryName = entry!!.name
                    val nameUpper = entryName.uppercase()

                    // Skip previous signature entries
                    if (nameUpper.startsWith("META-INF/") && (
                        nameUpper.endsWith(".SF") ||
                        nameUpper.endsWith(".RSA") ||
                        nameUpper.endsWith(".DSA") ||
                        nameUpper.endsWith(".EC") ||
                        nameUpper == "META-INF/MANIFEST.MF"
                    )) {
                        continue
                    }

                    val bytes = zis.readBytes()
                    entriesData[entryName] = bytes

                    // Compute entry digest for new manifest
                    md.reset()
                    val digest = md.digest(bytes)
                    val digestBase64 = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)

                    val attr = Attributes()
                    attr[Attributes.Name("SHA-256-Digest")] = digestBase64
                    manifest.entries[entryName] = attr
                }
            }

            // Write new signed ZIP
            ZipOutputStream(FileOutputStream(tempOutputFile)).use { zos ->
                // 1. Write original non-signature entries
                for ((name, data) in entriesData) {
                    val ze = ZipEntry(name)
                    zos.putNextEntry(ze)
                    zos.write(data)
                    zos.closeEntry()
                }

                // 2. Write META-INF/MANIFEST.MF
                val manifestBytes = ByteArrayOutputStream().use { baos ->
                    manifest.write(baos)
                    baos.toByteArray()
                }
                zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                zos.write(manifestBytes)
                zos.closeEntry()

                // 3. Generate and write META-INF/CERT.SF
                val sfBytes = generateSignatureFile(manifest, manifestBytes)
                zos.putNextEntry(ZipEntry("META-INF/CERT.SF"))
                zos.write(sfBytes)
                zos.closeEntry()

                // 4. Generate and write META-INF/CERT.RSA (PKCS#7 signature block)
                val rsaBlock = generatePkcs7SignatureBlock(sfBytes, privateKey, cert)
                zos.putNextEntry(ZipEntry("META-INF/CERT.RSA"))
                zos.write(rsaBlock)
                zos.closeEntry()
            }

            // Replace original file with resigned file
            if (apkFile.delete() && tempOutputFile.renameTo(apkFile)) {
                true
            } else {
                tempOutputFile.copyTo(apkFile, overwrite = true)
                tempOutputFile.delete()
                true
            }
        } catch (e: Exception) {
            onSubLog("Erreur résignature ${apkFile.name}: ${e.message}")
            false
        }
    }

    private fun generateSignatureFile(manifest: Manifest, manifestBytes: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val md = MessageDigest.getInstance("SHA-256")
        val manifestDigest = md.digest(manifestBytes)
        val manifestDigestBase64 = android.util.Base64.encodeToString(manifestDigest, android.util.Base64.NO_WRAP)

        val sf = Manifest()
        sf.mainAttributes[Attributes.Name.SIGNATURE_VERSION] = "1.0"
        sf.mainAttributes[Attributes.Name("Created-By")] = "ROMsign Surgical Engine 1.0"
        sf.mainAttributes[Attributes.Name("SHA-256-Digest-Manifest")] = manifestDigestBase64

        for ((name, attr) in manifest.entries) {
            val entryDigest = attr.getValue("SHA-256-Digest") ?: continue
            val entryAttr = Attributes()
            entryAttr[Attributes.Name("SHA-256-Digest")] = entryDigest
            sf.entries[name] = entryAttr
        }

        sf.write(baos)
        return baos.toByteArray()
    }

    /**
     * Builds a standard PKCS#7 signedData structure wrapping the X.509 certificate and RSA signature.
     */
    private fun generatePkcs7SignatureBlock(
        dataToSign: ByteArray,
        privateKey: PrivateKey,
        cert: X509Certificate
    ): ByteArray {
        val sig = java.security.Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(dataToSign)
        val signatureValue = sig.sign()

        // Construct standard PKCS#7 SignedData DER container
        val certDer = cert.encoded
        val certsSet = derImplicit(0xA0.toByte(), certDer) // [0] IMPLICIT CertificateSet

        // DigestAlgorithmIdentifiers { sha-256 }
        val sha256Oid = byteArrayOf(0x06, 0x09, 0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01, 0x05, 0x00)
        val digestAlgs = derSet(derSequence(sha256Oid))

        // ContentInfo { id-data (empty for detached) }
        val dataOid = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x01)
        val contentInfo = derSequence(dataOid)

        // SignerInfo
        val signerVersion = derInteger(1)
        val issuerAndSerial = derSequence(cert.issuerX500Principal.encoded + derBigInteger(cert.serialNumber))
        val digestAlg = derSequence(sha256Oid)
        val digestEncAlg = derSequence(byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00)) // rsaEncryption
        val encryptedDigest = derOctetString(signatureValue)

        val signerInfo = derSequence(signerVersion + issuerAndSerial + digestAlg + digestEncAlg + encryptedDigest)
        val signerInfos = derSet(signerInfo)

        // SignedData
        val signedDataVersion = derInteger(1)
        val signedData = derSequence(signedDataVersion + digestAlgs + contentInfo + certsSet + signerInfos)

        // ContentInfo wrapping signedData
        val signedDataOid = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x02)
        val explicitSignedData = derExplicit(0xA0.toByte(), signedData)

        return derSequence(signedDataOid + explicitSignedData)
    }

    private fun isSymbolicLink(file: File): Boolean {
        return try {
            Files.isSymbolicLink(file.toPath()) || (file.canonicalPath != file.absolutePath)
        } catch (_: Exception) {
            false
        }
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { inStream ->
            FileOutputStream(dst).use { outStream ->
                inStream.copyTo(outStream)
            }
        }
    }

    // ASN.1 DER Encoding Helpers
    private fun derInteger(value: Int): ByteArray = byteArrayOf(0x02, 0x01, value.toByte())
    private fun derBigInteger(value: java.math.BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return byteArrayOf(0x02) + derLength(bytes.size) + bytes
    }
    private fun derOctetString(data: ByteArray): ByteArray = byteArrayOf(0x04) + derLength(data.size) + data
    private fun derSequence(data: ByteArray): ByteArray = byteArrayOf(0x30) + derLength(data.size) + data
    private fun derSet(data: ByteArray): ByteArray = byteArrayOf(0x31) + derLength(data.size) + data
    private fun derImplicit(tag: Byte, data: ByteArray): ByteArray = byteArrayOf(tag) + derLength(data.size) + data
    private fun derExplicit(tag: Byte, data: ByteArray): ByteArray = byteArrayOf(tag) + derLength(data.size) + data

    private fun derLength(length: Int): ByteArray {
        return when {
            length < 128 -> byteArrayOf(length.toByte())
            length < 256 -> byteArrayOf(0x81.toByte(), length.toByte())
            length < 65536 -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
            else -> byteArrayOf(0x83.toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte())
        }
    }
}
