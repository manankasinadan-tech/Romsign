package com.example.audit

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Creates a realistic unpacked ROM directory structure for testing:
 * - system/priv-app/SystemUI/SystemUI.apk (with sharedUserId android.uid.system)
 * - system/framework/framework-res.apk
 * - system/app/Calculator/Calculator.apk (isolated user-space)
 * - system/etc/permissions/privapp-permissions-system.xml
 * - oat/arm64/ caches (.odex, .vdex, .art)
 * - metadata (.fsvmeta)
 */
object SampleUnpackerHelper {

    fun ensureSampleRomExists(context: Context, targetDir: File): String {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val systemPrivApp = File(targetDir, "system/priv-app/SystemUI/oat/arm64")
        val systemFramework = File(targetDir, "system/framework/oat/arm64")
        val systemApp = File(targetDir, "system/app/Calculator")
        val systemPermissions = File(targetDir, "system/etc/permissions")

        systemPrivApp.mkdirs()
        systemFramework.mkdirs()
        systemApp.mkdirs()
        systemPermissions.mkdirs()

        // 1. Create SystemUI.apk with sharedUserId android.uid.system
        val systemUiApk = File(targetDir, "system/priv-app/SystemUI/SystemUI.apk")
        if (!systemUiApk.exists()) {
            createMinimalApk(systemUiApk, "com.android.systemui", "android.uid.system")
        }

        // 2. Create framework-res.apk
        val frameworkApk = File(targetDir, "system/framework/framework-res.apk")
        if (!frameworkApk.exists()) {
            createMinimalApk(frameworkApk, "android", null)
        }

        // 3. Create isolated Calculator.apk
        val calcApk = File(targetDir, "system/app/Calculator/Calculator.apk")
        if (!calcApk.exists()) {
            createMinimalApk(calcApk, "com.android.calculator2", null)
        }

        // 4. Create ART caches (.odex, .vdex, .art, .fsvmeta)
        val odexFile = File(systemPrivApp, "SystemUI.odex")
        if (!odexFile.exists()) odexFile.writeBytes(ByteArray(1024) { 0x7F.toByte() })

        val vdexFile = File(systemPrivApp, "SystemUI.vdex")
        if (!vdexFile.exists()) vdexFile.writeBytes(ByteArray(2048) { 0x56.toByte() })

        val fsvmetaFile = File(targetDir, "system/priv-app/SystemUI/SystemUI.apk.fsvmeta")
        if (!fsvmetaFile.exists()) fsvmetaFile.writeBytes(ByteArray(256) { 0x46.toByte() })

        val frameworkOdex = File(systemFramework, "framework-res.odex")
        if (!frameworkOdex.exists()) frameworkOdex.writeBytes(ByteArray(4096) { 0x66.toByte() })

        // 5. Create permission XML with signer-cert tag
        val permXml = File(systemPermissions, "privapp-permissions-system.xml")
        if (!permXml.exists()) {
            permXml.writeText(
                """<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="com.android.systemui">
        <permission name="android.permission.STATUS_BAR_SERVICE"/>
        <permission name="android.permission.MANAGE_USERS"/>
        <signer-cert signature="2718446f52c3861801180818d22328e6"/>
    </privapp-permissions>
</permissions>
""",
                Charsets.UTF_8
            )
        }

        return targetDir.absolutePath
    }

    private fun createMinimalApk(destination: File, packageName: String, sharedUserId: String?) {
        val manifestBytes = buildMinimalBinaryManifest(packageName, sharedUserId)
        ZipOutputStream(FileOutputStream(destination)).use { zos ->
            // AndroidManifest.xml
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(manifestBytes)
            zos.closeEntry()

            // classes.dex dummy
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write("dex\n035\u0000".toByteArray())
            zos.closeEntry()

            // resources.arsc dummy
            zos.putNextEntry(ZipEntry("resources.arsc"))
            zos.write(ByteArray(64))
            zos.closeEntry()

            // META-INF/MANIFEST.MF
            val mf = Manifest()
            mf.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            val baos = ByteArrayOutputStream()
            mf.write(baos)
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zos.write(baos.toByteArray())
            zos.closeEntry()

            // META-INF/CERT.SF
            zos.putNextEntry(ZipEntry("META-INF/CERT.SF"))
            zos.write("Signature-Version: 1.0\r\nCreated-By: AOSP\r\n".toByteArray())
            zos.closeEntry()

            // META-INF/CERT.RSA
            zos.putNextEntry(ZipEntry("META-INF/CERT.RSA"))
            zos.write(ByteArray(256) { 0x30.toByte() })
            zos.closeEntry()
        }
    }

    /**
     * Minimal binary AXML builder with string pool containing package and sharedUserId
     */
    private fun buildMinimalBinaryManifest(packageName: String, sharedUserId: String?): ByteArray {
        val strings = mutableListOf(
            "manifest",
            "package",
            packageName,
            "versionCode",
            "versionName",
            "1.0"
        )
        if (sharedUserId != null) {
            strings.add("sharedUserId")
            strings.add(sharedUserId)
        }

        // Build string pool chunk
        val strOffsets = mutableListOf<Int>()
        val strBytesOut = ByteArrayOutputStream()
        for (str in strings) {
            strOffsets.add(strBytesOut.size())
            val utf8 = str.toByteArray(Charsets.UTF_8)
            strBytesOut.write(utf8.size) // len
            strBytesOut.write(utf8.size) // byte len
            strBytesOut.write(utf8)
            strBytesOut.write(0) // null terminator
        }

        val poolHeaderSize = 28 + (strings.size * 4)
        val poolTotalSize = poolHeaderSize + strBytesOut.size()

        val poolBaos = ByteArrayOutputStream()
        val poolBuf = java.nio.ByteBuffer.allocate(poolHeaderSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        poolBuf.putInt(0x001C0001) // CHUNK_STRING_POOL
        poolBuf.putInt(poolTotalSize)
        poolBuf.putInt(strings.size)
        poolBuf.putInt(0) // styleCount
        poolBuf.putInt(1 shl 8) // UTF-8 flag
        poolBuf.putInt(poolHeaderSize) // stringsStart
        poolBuf.putInt(0) // stylesStart
        for (offset in strOffsets) {
            poolBuf.putInt(offset)
        }
        poolBaos.write(poolBuf.array())
        poolBaos.write(strBytesOut.toByteArray())

        // Build manifest start tag
        val attrCount = if (sharedUserId != null) 3 else 2
        val startTagSize = 36 + (attrCount * 20)
        val startTagBuf = java.nio.ByteBuffer.allocate(startTagSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        startTagBuf.putInt(0x00100102) // CHUNK_START_TAG
        startTagBuf.putInt(startTagSize)
        startTagBuf.putInt(1) // line
        startTagBuf.putInt(-1) // comment
        startTagBuf.putInt(-1) // uri
        startTagBuf.putInt(0) // name "manifest"
        startTagBuf.putShort(0x14) // flags
        startTagBuf.putShort(attrCount.toShort())
        startTagBuf.putShort(0)
        startTagBuf.putShort(0)
        startTagBuf.putShort(0)

        // Attribute 1: package
        startTagBuf.putInt(-1) // uri
        startTagBuf.putInt(1)  // name "package"
        startTagBuf.putInt(2)  // val str packageName
        startTagBuf.putInt(0x03000008) // type string
        startTagBuf.putInt(2)

        // Attribute 2: versionCode
        startTagBuf.putInt(-1)
        startTagBuf.putInt(3) // "versionCode"
        startTagBuf.putInt(-1)
        startTagBuf.putInt(0x10000008) // type int
        startTagBuf.putInt(100)

        // Attribute 3: sharedUserId
        if (sharedUserId != null) {
            startTagBuf.putInt(-1)
            startTagBuf.putInt(6) // "sharedUserId"
            startTagBuf.putInt(7) // sharedUserId value
            startTagBuf.putInt(0x03000008)
            startTagBuf.putInt(7)
        }

        // Header chunk
        val totalFileSize = 8 + poolBaos.size() + startTagSize
        val headerBuf = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        headerBuf.putInt(0x00080003) // CHUNK_AXML_HEADER
        headerBuf.putInt(totalFileSize)

        val fullBaos = ByteArrayOutputStream()
        fullBaos.write(headerBuf.array())
        fullBaos.write(poolBaos.toByteArray())
        fullBaos.write(startTagBuf.array())

        return fullBaos.toByteArray()
    }
}
