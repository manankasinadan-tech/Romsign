package com.example.audit

import android.content.Context
import android.os.Environment
import com.example.crypto.ApkSignatureParser
import com.example.model.ApkDiagnosis
import com.example.model.AuditResult
import com.example.model.InvalidatedCacheItem
import com.example.model.PermissionFileInfo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files

/**
 * Global audit engine scanning unpacked ROM directories.
 * Strictly excludes symbolic links (symlinks) to prevent infinite loops and corruption.
 * Generates audit reports and backs up permission files to sdcard/SIGNER/Data/.
 */
class RomAuditEngine(private val context: Context) {

    fun executeAudit(
        targetDirectoryPath: String,
        onLog: (String) -> Unit
    ): AuditResult {
        val startTime = System.currentTimeMillis()
        onLog("Démarrage de l'audit pour le répertoire cible: $targetDirectoryPath")

        val targetDir = File(targetDirectoryPath)
        if (!targetDir.exists()) {
            onLog("ERREUR: Le répertoire '$targetDirectoryPath' est introuvable sur le système de fichiers.")
            return AuditResult(
                projectPath = targetDirectoryPath,
                totalApksScanned = 0,
                symlinksSkipped = 0,
                systemCoreApks = emptyList(),
                userSpaceApks = emptyList(),
                sharedUserIdGroups = emptyMap(),
                permissionFiles = emptyList(),
                invalidatedCaches = emptyList(),
                reportFilesGenerated = emptyList(),
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }

        onLog("Parcours récursif en cours (règle stricte: filtrage et exclusion totale des liens symboliques)...")

        val discoveredApks = mutableListOf<File>()
        val permissionFilesFound = mutableListOf<File>()
        val candidateCacheFiles = mutableListOf<File>()
        var symlinksSkippedCount = 0

        // Strict recursive traversal with symlink exclusion
        fun scanDirectory(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                // Check if child is a symbolic link
                if (isSymbolicLink(child)) {
                    symlinksSkippedCount++
                    continue
                }

                if (child.isDirectory) {
                    scanDirectory(child)
                } else if (child.isFile) {
                    val nameLower = child.name.lowercase()
                    when {
                        nameLower.endsWith(".apk") -> {
                            discoveredApks.add(child)
                        }
                        nameLower.endsWith(".xml") && (child.parent?.contains("permissions") == true || child.parent?.contains("sysconfig") == true || nameLower.contains("privapp-permissions") || nameLower == "platform.xml") -> {
                            permissionFilesFound.add(child)
                        }
                        nameLower.endsWith(".odex") || nameLower.endsWith(".vdex") || nameLower.endsWith(".art") || nameLower.endsWith(".oat") || nameLower.endsWith(".fsvmeta") -> {
                            candidateCacheFiles.add(child)
                        }
                    }
                }
            }
        }

        scanDirectory(targetDir)
        onLog("Exploration terminée : ${discoveredApks.size} APK(s) trouvés, $symlinksSkippedCount lien(s) symbolique(s) exclus.")

        // Analyze APK signatures and sharedUserIds
        val diagnosedApks = mutableListOf<ApkDiagnosis>()
        for ((index, apk) in discoveredApks.withIndex()) {
            onLog("[${index + 1}/${discoveredApks.size}] Analyse de ${apk.name}...")
            val diag = ApkSignatureParser.parseApk(apk)
            diagnosedApks.add(diag)
        }

        // Group by sharedUserId
        val sharedUidGroups = diagnosedApks
            .filter { !it.sharedUserId.isNullOrBlank() }
            .groupBy({ it.sharedUserId!! }, { it.apkName })

        // Partition into system core APKs and user-space APKs
        val systemCoreApks = diagnosedApks.filter {
            it.isSystemPrivileged || it.hasCriticalSharedUid || it.isPlatformSigned
        }
        val userSpaceApks = diagnosedApks.filter { !systemCoreApks.contains(it) }

        onLog("Répartition: ${systemCoreApks.size} APK(s) cœur système / partagés, ${userSpaceApks.size} APK(s) isolés.")

        // Analyze system permission XML files
        onLog("Analyse des fichiers de permissions XML...")
        val permissionInfos = mutableListOf<PermissionFileInfo>()
        for (permFile in permissionFilesFound) {
            val parsed = parsePermissionFile(permFile)
            if (parsed != null) {
                permissionInfos.add(parsed)
            }
        }

        // Detect invalidated ART & integrity caches
        onLog("Détection des artefacts et caches d'optimisation dépendants...")
        val invalidatedCaches = mutableListOf<InvalidatedCacheItem>()
        for (cacheFile in candidateCacheFiles) {
            val name = cacheFile.name
            val parentName = cacheFile.parentFile?.name ?: ""
            val grandParentName = cacheFile.parentFile?.parentFile?.name ?: ""

            val type = when {
                name.endsWith(".odex") -> "ODEX (Optimized DEX)"
                name.endsWith(".vdex") -> "VDEX (Verified DEX)"
                name.endsWith(".art") -> "ART (Runtime Image Cache)"
                name.endsWith(".oat") -> "OAT (Ahead-of-Time Binary)"
                name.endsWith(".fsvmeta") -> "FSVMETA (fs-verity Integrity Metadata)"
                else -> "Cache ART"
            }

            // Determine matching APK
            val baseName = name.substringBeforeLast(".")
            val matchedApk = diagnosedApks.find {
                it.apkName.startsWith(baseName, ignoreCase = true) ||
                parentName.equals(it.apkName.substringBeforeLast(".apk"), ignoreCase = true) ||
                grandParentName.equals(it.apkName.substringBeforeLast(".apk"), ignoreCase = true)
            }?.apkName ?: "Système / Multiple"

            invalidatedCaches.add(
                InvalidatedCacheItem(
                    filePath = cacheFile.absolutePath,
                    fileName = name,
                    cacheType = type,
                    associatedApk = matchedApk,
                    fileSizeBytes = cacheFile.length()
                )
            )
        }

        onLog("${invalidatedCaches.size} fichier(s) dépendant(s) invalidé(s) en cas de résignature identifié(s).")

        // Prepare output directory: sdcard/SIGNER/Data
        val outputDirectory = getSignerDataDirectory()
        val backupDirectory = File(outputDirectory, "backup")
        outputDirectory.mkdirs()
        backupDirectory.mkdirs()

        // 1. Deliverable: Signatures Audit Report
        val signaturesReportFile = File(outputDirectory, "signatures_audit_report.txt")
        val signaturesReportJson = File(outputDirectory, "signatures_audit_report.json")
        val permissionsMapFile = File(outputDirectory, "permissions_signatures_map.txt")
        val invalidatedListFile = File(outputDirectory, "invalidated_dependents_list.txt")

        writeSignaturesReport(signaturesReportFile, targetDirectoryPath, diagnosedApks, sharedUidGroups)
        writeSignaturesJson(signaturesReportJson, diagnosedApks)
        writePermissionsMap(permissionsMapFile, permissionInfos)
        writeInvalidatedList(invalidatedListFile, invalidatedCaches)

        // Backup original permission XMLs
        var backedUpCount = 0
        for (permFile in permissionFilesFound) {
            try {
                val backupDest = File(backupDirectory, permFile.name)
                copyFile(permFile, backupDest)
                backedUpCount++
            } catch (_: Exception) {}
        }
        onLog("Sauvegarde des fichiers de permissions originaux effectuée: $backedUpCount fichier(s) copiés dans ${backupDirectory.path}.")

        val generatedReports = listOf(
            signaturesReportFile.absolutePath,
            signaturesReportJson.absolutePath,
            permissionsMapFile.absolutePath,
            invalidatedListFile.absolutePath,
            backupDirectory.absolutePath
        )

        onLog("Livrables enregistrés avec succès dans: ${outputDirectory.absolutePath}")
        val totalDuration = System.currentTimeMillis() - startTime
        onLog("Audit terminé avec succès en ${totalDuration} ms.")

        return AuditResult(
            projectPath = targetDirectoryPath,
            totalApksScanned = diagnosedApks.size,
            symlinksSkipped = symlinksSkippedCount,
            systemCoreApks = systemCoreApks,
            userSpaceApks = userSpaceApks,
            sharedUserIdGroups = sharedUidGroups,
            permissionFiles = permissionInfos,
            invalidatedCaches = invalidatedCaches,
            reportFilesGenerated = generatedReports,
            executionTimeMs = totalDuration
        )
    }

    private fun isSymbolicLink(file: File): Boolean {
        return try {
            Files.isSymbolicLink(file.toPath()) || (file.canonicalPath != file.absolutePath)
        } catch (_: Exception) {
            false
        }
    }

    private fun parsePermissionFile(file: File): PermissionFileInfo? {
        return try {
            val content = file.readText(Charsets.UTF_8)
            val signatures = mutableListOf<String>()
            val packages = mutableListOf<String>()

            // Extract <signer-cert> or signature attribute tags
            val signerCertRegex = Regex("""<signer-cert[^>]*signature=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            signerCertRegex.findAll(content).forEach { match ->
                signatures.add(match.groupValues[1])
            }

            // Extract <privapp-permissions package="...">
            val pkgRegex = Regex("""package=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            pkgRegex.findAll(content).forEach { match ->
                packages.add(match.groupValues[1])
            }

            PermissionFileInfo(
                filePath = file.absolutePath,
                fileName = file.name,
                declaredSignatures = signatures.distinct(),
                packagesReferenced = packages.distinct()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeSignaturesReport(
        file: File,
        projectPath: String,
        apks: List<ApkDiagnosis>,
        sharedUidGroups: Map<String, List<String>>
    ) {
        val sb = StringBuilder()
        sb.appendLine("================================================================================")
        sb.appendLine("                 ROMSIGN - RAPPORT D'AUDIT DE SIGNATURES ET CHAÎNE DE CONFIANCE")
        sb.appendLine("================================================================================")
        sb.appendLine("Projet audité : $projectPath")
        sb.appendLine("Date d'exécution : ${java.util.Date()}")
        sb.appendLine("Nombre total d'APKs inspectés : ${apks.size}")
        sb.appendLine("Groupes sharedUserId détectés : ${sharedUidGroups.size}")
        sb.appendLine("================================================================================\n")

        sb.appendLine("--- GROUPES CRITIQUES SHARED USER ID ---")
        if (sharedUidGroups.isEmpty()) {
            sb.appendLine("Aucun sharedUserId détecté.")
        } else {
            for ((uid, members) in sharedUidGroups) {
                sb.appendLine("• $uid (${members.size} membres) :")
                members.forEach { sb.appendLine("    - $it") }
            }
        }
        sb.appendLine("\n--- DÉTAIL DES APPLICATIONS DU SYSTÈME ---")

        for (apk in apks) {
            sb.appendLine("--------------------------------------------------------------------------------")
            sb.appendLine("Fichier       : ${apk.apkName}")
            sb.appendLine("Chemin        : ${apk.apkPath}")
            sb.appendLine("Package       : ${apk.packageName ?: "Non défini"}")
            sb.appendLine("sharedUserId  : ${apk.sharedUserId ?: "Aucun"}")
            sb.appendLine("Version       : ${apk.versionName ?: ""} (${apk.versionCode ?: 0})")
            sb.appendLine("Schémas       : ${apk.schemes.summaryText}")
            sb.appendLine("Impact Système: ${if (apk.isSystemPrivileged || apk.hasCriticalSharedUid) "CRITIQUE (Boot & Système)" else "Isolé / Standard"}")
            if (apk.certificates.isNotEmpty()) {
                sb.appendLine("Certificats   :")
                for (cert in apk.certificates) {
                    sb.appendLine("  * Sujet     : ${cert.subjectDN}")
                    sb.appendLine("    Validité  : Du ${cert.validFrom} au ${cert.validTo}")
                    sb.appendLine("    Algorithme: ${cert.algorithm}")
                    sb.appendLine("    SHA-256   : ${cert.sha256Fingerprint}")
                    sb.appendLine("    SHA-1     : ${cert.sha1Fingerprint}")
                }
            } else {
                sb.appendLine("Certificats   : Aucun certificat détecté (APK non signé ou corrompu)")
            }
        }

        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun writeSignaturesJson(file: File, apks: List<ApkDiagnosis>) {
        val sb = StringBuilder()
        sb.append("[\n")
        apks.forEachIndexed { i, apk ->
            sb.append("  {\n")
            sb.append("    \"apkName\": \"${escapeJson(apk.apkName)}\",\n")
            sb.append("    \"apkPath\": \"${escapeJson(apk.apkPath)}\",\n")
            sb.append("    \"packageName\": \"${escapeJson(apk.packageName ?: "")}\",\n")
            sb.append("    \"sharedUserId\": \"${escapeJson(apk.sharedUserId ?: "")}\",\n")
            sb.append("    \"versionName\": \"${escapeJson(apk.versionName ?: "")}\",\n")
            sb.append("    \"versionCode\": ${apk.versionCode ?: 0},\n")
            sb.append("    \"hasV1\": ${apk.schemes.hasV1Jar},\n")
            sb.append("    \"hasV2\": ${apk.schemes.hasV2Scheme},\n")
            sb.append("    \"hasV3\": ${apk.schemes.hasV3Scheme},\n")
            sb.append("    \"hasV4\": ${apk.schemes.hasV4Scheme},\n")
            sb.append("    \"isPlatformSigned\": ${apk.isPlatformSigned},\n")
            sb.append("    \"certificatesCount\": ${apk.certificates.size}\n")
            sb.append("  }${if (i < apks.size - 1) "," else ""}\n")
        }
        sb.append("]\n")
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun writePermissionsMap(file: File, permissions: List<PermissionFileInfo>) {
        val sb = StringBuilder()
        sb.appendLine("# ROMSIGN - CARTOGRAPHIE DES FICHIERS DE PERMISSIONS AVEC DONNÉES DE SIGNATURE")
        sb.appendLine("# Date: ${java.util.Date()}\n")
        for (info in permissions) {
            sb.appendLine("Fichier: ${info.filePath}")
            sb.appendLine("Signatures / Certificats référencés: ${if (info.declaredSignatures.isEmpty()) "Aucun" else info.declaredSignatures.joinToString(", ")}")
            sb.appendLine("Packages privilégiés ciblés: ${info.packagesReferenced.take(5).joinToString(", ")}")
            sb.appendLine("--------------------------------------------------------------------------------")
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun writeInvalidatedList(file: File, caches: List<InvalidatedCacheItem>) {
        val sb = StringBuilder()
        sb.appendLine("# ROMSIGN - LISTE DES FICHIERS DÉPENDANTS INVALIDÉS EN CAS DE RÉSIGNATURE")
        sb.appendLine("# Ces artefacts (.odex, .vdex, .art, .oat, .fsvmeta) doivent impérativement être purgés pour éviter les bootloops ART.")
        sb.appendLine("# Total détecté: ${caches.size}\n")
        for (cache in caches) {
            sb.appendLine("Chemin: ${cache.filePath}")
            sb.appendLine("  Type       : ${cache.cacheType}")
            sb.appendLine("  APK source : ${cache.associatedApk}")
            sb.appendLine("  Taille     : ${cache.fileSizeBytes} octets")
            sb.appendLine("")
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { inStream ->
            FileOutputStream(dst).use { outStream ->
                inStream.copyTo(outStream)
            }
        }
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    }

    fun getSignerDataDirectory(): File {
        val externalStorage = Environment.getExternalStorageDirectory()
        val signerDir = File(externalStorage, "SIGNER/Data")
        if (!signerDir.exists()) {
            signerDir.mkdirs()
        }
        if (signerDir.canWrite()) {
            return signerDir
        }
        // Fallback to app external files dir if root / external storage is scoped
        val fallback = File(context.getExternalFilesDir(null), "SIGNER/Data")
        fallback.mkdirs()
        return fallback
    }
}
