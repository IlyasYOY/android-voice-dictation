package io.github.ilyasyoy.voicedictation.session

import io.github.ilyasyoy.voicedictation.data.HistoryCapacityReached
import io.github.ilyasyoy.voicedictation.data.RecordingRepository
import io.github.ilyasyoy.voicedictation.data.RecordingUpload
import io.github.ilyasyoy.voicedictation.data.SavedRecordingFailure
import java.io.File
import java.io.IOException

/** Session file-ownership boundary. Implementations never receive credentials or editor state. */
interface RecordingRetention {
    fun checkCapacity() = Unit
    fun beginSession() = Unit
    fun stopped(audio:File,seconds:Int) = Unit
    fun receivedText(audio:File):String? = null
    fun beforeUpload(audio: File)
    fun completed(audio: File, transcript: String)
    fun failed(audio: File, problem: Problem)
    fun release(audio: File)
    fun discard(audio: File)
    fun restoreDraft():String? = null
    fun saveDraft(text:String) = Unit
    fun clearDraft() = saveDraft("")
    fun draftSourceId():String? = null
    fun draftSourceAvailable():Boolean = true
}

internal object TemporaryRecordingRetention : RecordingRetention {
    override fun beforeUpload(audio: File) = Unit
    override fun completed(audio: File, transcript: String) { audio.delete() }
    override fun failed(audio: File, problem: Problem) = Unit
    override fun release(audio: File) = Unit
    override fun discard(audio: File) { audio.delete() }
}

internal class RecordingStorageFailure(cause: Exception) : IOException("Recording storage unavailable", cause)

/** A fresh adapter per session; upload claims remain separate from the process-owned repository. */
internal class StoredRecordingRetention(private val open: () -> RecordingRepository,private val mainDraft:Boolean=false) : RecordingRetention {
    constructor(repository:RecordingRepository,mainDraft:Boolean=false):this({repository},mainDraft)
    private val repository get()=open()
    private val attempts = mutableMapOf<File, RecordingUpload>()
    private var sourceId:String?=null
    override fun draftSourceId():String? = sourceId
    override fun draftSourceAvailable():Boolean = stored {
        sourceId?.let { repository.record(it)?.status !in setOf(null, io.github.ilyasyoy.voicedictation.data.SavedRecordingStatus.DELETING) } ?: true
    }
    override fun clearDraft() {
        if (mainDraft) stored { repository.saveText(io.github.ilyasyoy.voicedictation.data.RecordingTextSlot.MAIN, "") }
        sourceId = null
    }
    private fun <T> stored(action: () -> T): T = try { action() } catch (e: HistoryCapacityReached) { throw e } catch (e: Exception) { throw RecordingStorageFailure(e) }
    override fun checkCapacity() = stored { repository.requireCapacity() }
    override fun beginSession() = checkCapacity()
    override fun stopped(audio:File,seconds:Int) = stored {repository.duration(audio,seconds)}
    override fun receivedText(audio:File):String? = stored {repository.record(audio)?.let {repository.receivedText(it.id)}}
    override fun restoreDraft():String? = if(mainDraft)stored {
        val restored=repository.savedDraft(io.github.ilyasyoy.voicedictation.data.RecordingTextSlot.MAIN)
        sourceId=restored.sourceId;restored.text
    } else null
    override fun saveDraft(text:String) {
        // A still-open owner can hold old text after explicit deletion elsewhere. Keep
        // its deleted source identity until restore/new dictation, so repeated lifecycle
        // checkpoints cannot turn that stale text into an unlinked durable draft.
        val source=sourceId
        if(source!=null && repository.record(source)?.status in setOf(null,io.github.ilyasyoy.voicedictation.data.SavedRecordingStatus.DELETING))return
        if(mainDraft)stored {repository.saveText(io.github.ilyasyoy.voicedictation.data.RecordingTextSlot.MAIN,text,sourceId)}
    }
    override fun beforeUpload(audio: File) = stored {
        check(audio !in attempts)
        attempts[audio] = checkNotNull(repository.beginUpload(audio))
    }
    override fun completed(audio: File, transcript: String) = stored {
        val token = checkNotNull(attempts[audio])
        check(repository.complete(token, transcript))
        sourceId=token.id
        attempts.remove(audio)
        Unit
    }
    override fun failed(audio: File, problem: Problem) = stored {
        val failure = when (problem) {
            Problem.KEY -> SavedRecordingFailure.KEY
            Problem.LOCAL_STORAGE -> SavedRecordingFailure.STORAGE
            Problem.AUTH -> SavedRecordingFailure.AUTH
            Problem.NETWORK -> SavedRecordingFailure.NETWORK
            else -> SavedRecordingFailure.SERVICE
        }
        val token = attempts[audio]
        if (token != null) { try {repository.failed(token, failure)} finally {attempts.remove(audio)} }
        else repository.markFailed(audio, failure)
        Unit
    }
    override fun release(audio: File) = stored {
        attempts[audio]?.let {token -> try {repository.failed(token, null)} finally {attempts.remove(audio)} }
        Unit
    }
    override fun discard(audio: File) = stored {
        release(audio)
        repository.record(audio)?.let { check(repository.discardOwned(audio)) }
        Unit
    }
}
