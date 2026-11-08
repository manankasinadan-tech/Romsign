package com.example.shell

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Handles shell command execution with optional root (su) privileges
 * for accessing unpacker work directories like /data/local/UnpackerSystem/.
 */
object RootShellExecutor {

    private var isRootChecked = false
    private var isRootAvailable = false

    fun checkRootAccess(): Boolean {
        if (isRootChecked) return isRootAvailable
        isRootAvailable = try {
            val paths = arrayOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/vendor/bin/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/system/sd/xbin/su"
            )
            val fileExists = paths.any { File(it).exists() }
            if (fileExists) {
                true
            } else {
                val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
                val exitCode = process.waitFor()
                exitCode == 0
            }
        } catch (_: Exception) {
            false
        }
        isRootChecked = true
        return isRootAvailable
    }

    data class CommandResult(
        val exitCode: Int,
        val stdout: List<String>,
        val stderr: List<String>
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    fun execute(command: String, asRoot: Boolean = false): CommandResult {
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()

        return try {
            val cmd = if (asRoot && checkRootAccess()) {
                arrayOf("su", "-c", command)
            } else {
                arrayOf("sh", "-c", command)
            }

            val process = Runtime.getRuntime().exec(cmd)

            val outReader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))

            var line: String?
            while (outReader.readLine().also { line = it } != null) {
                line?.let { stdout.add(it) }
            }
            while (errReader.readLine().also { line = it } != null) {
                line?.let { stderr.add(it) }
            }

            val exit = process.waitFor()
            CommandResult(exit, stdout, stderr)
        } catch (e: Exception) {
            CommandResult(-1, emptyList(), listOf("Erreur shell: ${e.message}"))
        }
    }
}
