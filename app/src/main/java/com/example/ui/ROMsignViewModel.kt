package com.example.ui

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audit.RomAuditEngine
import com.example.audit.SampleUnpackerHelper
import com.example.crypto.ApkSignatureParser
import com.example.crypto.KeyStoreManager
import com.example.model.ApkDiagnosis
import com.example.model.AuditResult
import com.example.model.ResignExecutionResult
import com.example.model.SigningKeyOption
import com.example.shell.RootShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class AppFeatureTab {
    APP_INFO,
    AUDIT_PROJECT,
    SMART_RESIGN
}

data class ROMsignUiState(
    val currentTab: AppFeatureTab = AppFeatureTab.APP_INFO,
    val isRootDetected: Boolean = false,

    // Feature 1: App Info
    val appInfoSelectedPath: String = "",
    val appInfoIsRunning: Boolean = false,
    val appInfoLogs: List<String> = emptyList(),
    val appInfoDiagnosis: ApkDiagnosis? = null,

    // Feature 2: Audit Project
    val auditTargetDir: String = "/data/local/UnpackerSystem/",
    val auditIsRunning: Boolean = false,
    val auditLogs: List<String> = emptyList(),
    val auditResult: AuditResult? = null,
    val viewingReportContent: Pair<String, String>? = null, // (title, content)

    // Feature 3: Smart Resign
    val resignTargetDir: String = "/data/local/UnpackerSystem/",
    val resignIsRunning: Boolean = false,
    val resignLogs: List<String> = emptyList(),
    val resignResult: ResignExecutionResult? = null,
    val showKeySelectionDialog: Boolean = false,
    val availableSigningKeys: List<SigningKeyOption> = emptyList(),
    val selectedSigningKey: SigningKeyOption? = null,
    val showCustomKeyDialog: Boolean = false,

    val statusMessage: String? = null
)

class ROMsignViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ROMsignUiState())
    val uiState: StateFlow<ROMsignUiState> = _uiState.asStateFlow()

    private val auditEngine = RomAuditEngine(application)
    private val resignEngine = com.example.signer.SmartResignEngine(application)

    init {
        checkRoot()
        loadAvailableKeys()
        prepareDefaultDirectory()
    }

    private fun checkRoot() {
        viewModelScope.launch(Dispatchers.IO) {
            val rootAvailable = RootShellExecutor.checkRootAccess()
            _uiState.update { it.copy(isRootDetected = rootAvailable) }
        }
    }

    private fun loadAvailableKeys() {
        val keys = KeyStoreManager.getAvailableKeys()
        _uiState.update {
            it.copy(
                availableSigningKeys = keys,
                selectedSigningKey = keys.firstOrNull()
            )
        }
    }

    private fun prepareDefaultDirectory() {
        val defaultPath = "/data/local/UnpackerSystem/"
        _uiState.update {
            it.copy(
                auditTargetDir = defaultPath,
                resignTargetDir = defaultPath
            )
        }
    }

    fun selectTab(tab: AppFeatureTab) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun setAppInfoPath(path: String) {
        _uiState.update { it.copy(appInfoSelectedPath = path) }
    }

    fun setAuditTargetDir(path: String) {
        _uiState.update { it.copy(auditTargetDir = path) }
    }

    fun setResignTargetDir(path: String) {
        _uiState.update { it.copy(resignTargetDir = path) }
    }

    // --- Feature 1: App Info ---

    fun onApkSelectedFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val contentResolver = getApplication<Application>().contentResolver
                val tempFile = File(getApplication<Application>().cacheDir, "inspect_target.apk")
                contentResolver.openInputStream(uri)?.use { inStream ->
                    FileOutputStream(tempFile).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            appInfoSelectedPath = tempFile.absolutePath,
                            appInfoDiagnosis = null,
                            appInfoLogs = emptyList()
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(statusMessage = "Erreur ouverture APK: ${e.message}") }
                }
            }
        }
    }

    fun launchAppInfoDiagnosis() {
        val path = _uiState.value.appInfoSelectedPath
        if (path.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Veuillez sélectionner un fichier .apk") }
            return
        }

        val apkFile = File(path)
        if (!apkFile.exists()) {
            _uiState.update { it.copy(statusMessage = "Fichier introuvable: $path") }
            return
        }

        _uiState.update {
            it.copy(
                appInfoIsRunning = true,
                appInfoLogs = emptyList(),
                appInfoDiagnosis = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val logs = mutableListOf<String>()
            val diag = ApkSignatureParser.parseApk(apkFile) { logMsg ->
                logs.add(logMsg)
                _uiState.update { it.copy(appInfoLogs = logs.toList()) }
            }

            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        appInfoIsRunning = false,
                        appInfoDiagnosis = diag
                    )
                }
            }
        }
    }

    fun resetAppInfo() {
        _uiState.update {
            it.copy(
                appInfoIsRunning = false,
                appInfoDiagnosis = null,
                appInfoLogs = emptyList()
            )
        }
    }

    // --- Feature 2: Audit Project ---

    fun launchProjectAudit() {
        val targetPath = _uiState.value.auditTargetDir.trim()
        if (targetPath.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Veuillez indiquer le chemin du projet") }
            return
        }

        _uiState.update {
            it.copy(
                auditIsRunning = true,
                auditLogs = emptyList(),
                auditResult = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val logs = mutableListOf<String>()
            val result = auditEngine.executeAudit(targetPath) { logMsg ->
                logs.add(logMsg)
                _uiState.update { it.copy(auditLogs = logs.toList()) }
            }

            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        auditIsRunning = false,
                        auditResult = result
                    )
                }
            }
        }
    }

    fun viewReportFile(filePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(filePath)
            val content = if (file.exists() && file.isFile) {
                file.readText(Charsets.UTF_8)
            } else {
                "Fichier non accessible ou répertoire : $filePath"
            }
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(viewingReportContent = Pair(file.name, content))
                }
            }
        }
    }

    fun dismissReportViewer() {
        _uiState.update { it.copy(viewingReportContent = null) }
    }

    // --- Feature 3: Smart Resign ---

    fun requestSmartResign() {
        val path = _uiState.value.resignTargetDir.trim()
        if (path.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Veuillez renseigner le répertoire concerné") }
            return
        }
        _uiState.update { it.copy(showKeySelectionDialog = true) }
    }

    fun dismissKeySelection() {
        _uiState.update { it.copy(showKeySelectionDialog = false) }
    }

    fun selectSigningKey(key: SigningKeyOption) {
        _uiState.update { it.copy(selectedSigningKey = key) }
    }

    fun openCustomKeyCreator() {
        _uiState.update { it.copy(showCustomKeyDialog = true) }
    }

    fun dismissCustomKeyCreator() {
        _uiState.update { it.copy(showCustomKeyDialog = false) }
    }

    fun createCustomKey(alias: String, cn: String, org: String) {
        val newKey = KeyStoreManager.generateCustomKey(
            aliasName = alias.ifBlank { "Clé Personnalisée" },
            commonName = cn.ifBlank { "Custom Platform Signer" },
            organization = org.ifBlank { "ROM Kitchen" }
        )
        loadAvailableKeys()
        _uiState.update {
            it.copy(
                selectedSigningKey = newKey,
                showCustomKeyDialog = false
            )
        }
    }

    fun confirmAndLaunchSmartResign() {
        val key = _uiState.value.selectedSigningKey ?: _uiState.value.availableSigningKeys.firstOrNull()
        if (key == null) {
            _uiState.update { it.copy(statusMessage = "Aucune clé de signature disponible") }
            return
        }

        val targetPath = _uiState.value.resignTargetDir.trim()
        _uiState.update {
            it.copy(
                showKeySelectionDialog = false,
                resignIsRunning = true,
                resignLogs = emptyList(),
                resignResult = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val logs = mutableListOf<String>()
            val result = resignEngine.executeSmartResign(targetPath, key) { logMsg ->
                logs.add(logMsg)
                _uiState.update { it.copy(resignLogs = logs.toList()) }
            }

            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        resignIsRunning = false,
                        resignResult = result
                    )
                }
            }
        }
    }

    // --- Demo environment generator ---

    fun generateSampleEnvironment() {
        viewModelScope.launch(Dispatchers.IO) {
            val targetDir = File(getApplication<Application>().filesDir, "UnpackerSystem")
            SampleUnpackerHelper.ensureSampleRomExists(getApplication(), targetDir)
            val samplePath = targetDir.absolutePath

            // Also check an APK from the sample for App Info
            val sampleApk = File(targetDir, "system/priv-app/SystemUI/SystemUI.apk")

            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        auditTargetDir = samplePath,
                        resignTargetDir = samplePath,
                        appInfoSelectedPath = if (sampleApk.exists()) sampleApk.absolutePath else it.appInfoSelectedPath,
                        statusMessage = "Environnement de test UKA généré : $samplePath"
                    )
                }
            }
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}
