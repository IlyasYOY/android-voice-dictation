package io.github.ilyasyoy.voicedictation.session

import io.github.ilyasyoy.voicedictation.diagnostics.*

import java.io.File
import java.io.IOException
import io.github.ilyasyoy.voicedictation.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class RecordingRetryFailure { KEY, AUTH, NETWORK, SERVICE, STORAGE;
    companion object {
        fun from(problem: Problem?): RecordingRetryFailure?=when(problem) {
            Problem.KEY,Problem.STORAGE -> KEY
            Problem.LOCAL_STORAGE -> STORAGE
            Problem.AUTH -> AUTH
            Problem.NETWORK -> NETWORK
            Problem.SERVER,Problem.QUOTA,Problem.RESPONSE -> SERVICE
            else -> null
        }
    }
}
internal data class RecordingRecoveryState(
    val count: Int=0, val retrying: Boolean=false, val failure: RecordingRetryFailure?=null,
    val records: List<SavedRecording> = emptyList(), val storageProblem: Boolean=false,
    val receivedTexts:Map<String,String> = emptyMap(),
    val ownedIds:Set<String> = emptySet(),
    val capacity: HistoryCapacity = HistoryCapacity(),
) {
    val savedCount get()=if(records.isEmpty())count else records.size
    override fun toString()="RecordingRecoveryState(count=$count, retrying=$retrying, savedCount=$savedCount, storageProblem=$storageProblem)"
}

/** Main-dispatcher manual actions. The repository owns durable bytes; no credentials or editors. */
internal class RecordingRecovery {
    companion object { val processOwned=RecordingRecovery() }
    private val files=mutableListOf<File>() // Also supports isolated ephemeral unit-test owners.
    private var repository:RecordingRepository?=null
    private var unsubscribe:(()->Unit)?=null
    private var activeAudio:File?=null
    private var storageProblem=false
    private val mutable=MutableStateFlow(RecordingRecoveryState())
    val state=mutable.asStateFlow()
    var retrying=false
        private set
    private fun available():List<File> = (repository?.pending()?.map {repository!!.audio(it.id)}.orEmpty()+files)
        .distinct().filter {it!=activeAudio}
    val count get()=available().size + if(retrying)1 else 0
    fun attach(store:RecordingRepository) {
        if(repository===store)return
        check(!retrying)
        unsubscribe?.invoke();repository=store
        unsubscribe=store.observe {publish()}
        storageProblem=store.migrationPending
        publish()
    }
    fun storageUnavailable() {Diagnostics.record(DiagnosticArea.RECOVERY, DiagnosticAction.STORAGE, DiagnosticCode.LOCAL_STORAGE);storageProblem=true;publish(RecordingRetryFailure.STORAGE)}
    private fun publish(failure: RecordingRetryFailure?=mutable.value.failure) {
        mutable.value=RecordingRecoveryState(count,retrying,failure,repository?.records().orEmpty(),storageProblem,repository?.receivedTexts().orEmpty(),repository?.ownedIds().orEmpty(), repository?.capacity() ?: HistoryCapacity(readable = false))
    }
    fun retain(file: File,failure: RecordingRetryFailure?=null):File {
        val store=repository
        if(store!=null) {
            try {
                val retained=store.retain(file,failure?.let {SavedRecordingFailure.valueOf(it.name)})
                files.remove(file);publish(failure ?: mutable.value.failure);return retained
            } catch(error:Exception) {Diagnostics.failure(DiagnosticArea.RECOVERY, DiagnosticAction.STORAGE, error);storageProblem=true}
        }
        if(file.exists() && file !in files)files+=file
        publish(if(storageProblem)RecordingRetryFailure.STORAGE else failure ?: mutable.value.failure)
        return file
    }
    suspend fun retry(id:String?=null,upload: suspend (File)->String): String? {
        if(retrying)return null
        val store=repository
        val source=available().firstOrNull {id==null || store?.record(it)?.id==id} ?: return null
        val previousFailure=mutable.value.failure
        var audio=source
        var token:RecordingUpload?=null
        Diagnostics.record(DiagnosticArea.RECOVERY, DiagnosticAction.RETRY)
        retrying=true;activeAudio=source;files.remove(source);publish(null)
        var failure:RecordingRetryFailure?=null
        try {
            if(store!=null) {
                try {
                    audio=if(store.record(source)!=null)source else store.retain(source)
                    activeAudio=audio
                    token=checkNotNull(store.beginUpload(audio))
                } catch(e:Exception) {throw RecordingStorageFailure(e)}
            }
            val text=store?.record(audio)?.let {store.receivedText(it.id)} ?: upload(audio)
            currentCoroutineContext().ensureActive()
            require(text.isNotBlank())
            if(store!=null)try {check(store.complete(checkNotNull(token),text))} catch(e:Exception) {throw RecordingStorageFailure(e)} else audio.delete()
            storageProblem=store?.migrationPending==true
            Diagnostics.record(DiagnosticArea.RECOVERY, DiagnosticAction.PROCESS)
            return text
        } catch(e: Exception) {
            if (e is CancellationException) Diagnostics.record(DiagnosticArea.RECOVERY, DiagnosticAction.CANCEL, DiagnosticCode.CANCELLED)
            else Diagnostics.failure(DiagnosticArea.RECOVERY, DiagnosticAction.RETRY, e)
            failure=when(e) {
                is CancellationException -> previousFailure
                is MissingSavedKey -> RecordingRetryFailure.KEY
                is RecordingStorageFailure, is VocabularyFailure -> RecordingRetryFailure.STORAGE
                is ApiFailure -> if(e.status in setOf(401,403))RecordingRetryFailure.AUTH else RecordingRetryFailure.SERVICE
                is IOException -> RecordingRetryFailure.NETWORK
                else -> RecordingRetryFailure.SERVICE
            }
            if(store!=null && token!=null) {
                try {store.failed(token,failure?.let {SavedRecordingFailure.valueOf(it.name)})}
                catch(error:Exception) {Diagnostics.failure(DiagnosticArea.RECOVERY, DiagnosticAction.STORAGE, error);storageProblem=true;failure=RecordingRetryFailure.STORAGE}
            } else if(audio.exists())files.add(0,audio)
            throw e
        } finally {retrying=false;activeAudio=null;publish(failure)}
    }
    fun discard() {
        if(retrying)return
        val store=repository
        if(store!=null)store.pending().forEach {runCatching {store.discard(it.id)}.onFailure {Diagnostics.failure(DiagnosticArea.RECOVERY, DiagnosticAction.STORAGE, it);storageProblem=true}}
        files.forEach {it.delete()};files.clear();publish(null)
    }
    fun discard(id:String):Boolean {
        if(retrying)return false
        return try {repository?.discard(id)==true} catch(error:Exception) {Diagnostics.failure(DiagnosticArea.RECOVERY, DiagnosticAction.STORAGE, error);storageProblem=true;publish(RecordingRetryFailure.STORAGE);false}
    }
}
