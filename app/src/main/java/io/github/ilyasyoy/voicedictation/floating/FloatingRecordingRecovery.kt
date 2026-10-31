package io.github.ilyasyoy.voicedictation.floating

import java.io.File

/** Process-only ownership of failed Stop uploads. No credential, capture, editor or automatic work. */
internal class FloatingRecordingRecovery {
    companion object { val processOwned=FloatingRecordingRecovery() }
    private val files=mutableListOf<File>()
    var retrying=false
        private set
    val count get()=files.size + if(retrying)1 else 0
    fun retain(file: File) { if(file.exists() && file !in files)files+=file }
    suspend fun retry(upload: suspend (File)->String): String? {
        if(retrying || files.isEmpty())return null
        val audio=files.removeAt(0)
        retrying=true
        try {
            val text=upload(audio)
            require(text.isNotBlank())
            audio.delete()
            return text
        } catch(e: Exception) {
            if(audio.exists())files.add(0,audio)
            throw e
        } finally { retrying=false }
    }
    fun discard() {
        if(retrying)return
        files.forEach { it.delete() }; files.clear()
    }
}
