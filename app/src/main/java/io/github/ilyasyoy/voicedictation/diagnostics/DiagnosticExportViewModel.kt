package io.github.ilyasyoy.voicedictation.diagnostics

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ExportState { IDLE, CHOOSING, SAVING, SAVED, FAILED;
    val busy get() = this == CHOOSING || this == SAVING
}

/** Application ownership of an in-flight write survives a Fold/window recreation. */
internal class DiagnosticExportViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(when {
        saved.get<Boolean>("savingLogs") == true -> ExportState.FAILED
        saved.get<Boolean>("choosingLogs") == true -> ExportState.CHOOSING
        else -> ExportState.IDLE
    })
    val state = mutable.asStateFlow()
    fun choose(): Boolean {
        if (state.value.busy) return false
        saved["savingLogs"] = false; saved["choosingLogs"] = true; mutable.value = ExportState.CHOOSING
        return true
    }
    fun pickerFailed(error: Exception) {
        saved["choosingLogs"] = false; mutable.value = ExportState.FAILED
        Diagnostics.failure(DiagnosticArea.EXPORT, DiagnosticAction.START, error)
    }
    fun selected(uri: Uri?) {
        saved["choosingLogs"] = false
        if (uri == null) { saved["savingLogs"] = false; mutable.value = ExportState.IDLE; return }
        if (state.value == ExportState.SAVING) return
        saved["savingLogs"] = true
        mutable.value = ExportState.SAVING
        viewModelScope.launch {
            val app = getApplication<Application>()
            val success = withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionCode }.getOrDefault(0)
                DiagnosticExport.save(Diagnostics::snapshot, { app.contentResolver.openOutputStream(uri, "w") },
                    { DocumentsContract.deleteDocument(app.contentResolver, uri); Unit }, version, Build.VERSION.SDK_INT)
            }
            saved["savingLogs"] = false
            mutable.value = if (success) ExportState.SAVED else ExportState.FAILED
        }
    }
}
