package io.github.ilyasyoy.voicedictation.floating

import java.io.File
import java.io.IOException
import io.github.ilyasyoy.voicedictation.data.ApiFailure
import io.github.ilyasyoy.voicedictation.session.MissingSavedKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class RecordingRetryFailure { KEY, AUTH, NETWORK, SERVICE }
internal data class RecordingRecoveryState(val count: Int=0,val retrying: Boolean=false,val failure: RecordingRetryFailure?=null)

/** Process-only ownership of failed Stop uploads. No credential, capture, editor or automatic work. */
internal class FloatingRecordingRecovery {
    companion object { val processOwned=FloatingRecordingRecovery() }
    private val files=mutableListOf<File>()
    private val mutable=MutableStateFlow(RecordingRecoveryState())
    val state=mutable.asStateFlow()
    var retrying=false
        private set
    val count get()=files.size + if(retrying)1 else 0
    private fun publish(failure: RecordingRetryFailure?=mutable.value.failure) { mutable.value=RecordingRecoveryState(count,retrying,failure) }
    fun retain(file: File,failure: RecordingRetryFailure?=null) {
        if(file.exists() && file !in files)files+=file
        publish(failure ?: mutable.value.failure)
    }
    suspend fun retry(upload: suspend (File)->String): String? {
        if(retrying || files.isEmpty())return null
        val previousFailure=mutable.value.failure
        val audio=files.removeAt(0)
        retrying=true;publish(null)
        var failure: RecordingRetryFailure?=null
        try {
            val text=upload(audio)
            require(text.isNotBlank())
            audio.delete()
            return text
        } catch(e: Exception) {
            if(audio.exists())files.add(0,audio)
            failure=when(e) {
                is CancellationException -> previousFailure
                is MissingSavedKey -> RecordingRetryFailure.KEY
                is ApiFailure -> if(e.status in setOf(401,403))RecordingRetryFailure.AUTH else RecordingRetryFailure.SERVICE
                is IOException -> RecordingRetryFailure.NETWORK
                else -> RecordingRetryFailure.SERVICE
            }
            throw e
        } finally { retrying=false;publish(failure) }
    }
    fun discard() {
        if(retrying)return
        files.forEach { it.delete() }; files.clear();publish(null)
    }
}
