package io.github.ilyasyoy.voicedictation.diagnostics

import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.RecordingStorageFailure
import io.github.ilyasyoy.voicedictation.session.MissingSavedKey
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Closed vocabulary only. Never accept messages, paths, URLs, text, editor tokens or stack traces. */
internal enum class DiagnosticArea { APP, MAIN, SESSION, RECOVERY, NETWORK, RECORDER, OVERLAY, INSERTION, EXPORT }
internal enum class DiagnosticAction { START, STOP, RESUME, BACKGROUND, CLOSE, CONNECT, INTERRUPT, UNBIND, CONFIGURATION, SCREEN_OFF, RECORD, PROCESS, RETRY, CANCEL, SAFETY_STOP, STORAGE, RENDER, FOREGROUND, COPY, INSERT, SAVE, CLEANUP, CRASH }
internal enum class DiagnosticCode { OK, CANCELLED, PERMISSION, RECORDING, KEY, STORAGE, LOCAL_STORAGE, NETWORK, AUTH, QUOTA, SERVER, RESPONSE, TIMEOUT, DNS, TLS, IO, INVALID, UNKNOWN, COPY, UNCONFIRMED, INSERTED, BLOCKED, CAPACITY }
internal data class DiagnosticEvent(
    val area: DiagnosticArea, val action: DiagnosticAction, val code: DiagnosticCode = DiagnosticCode.OK,
    val httpStatus: Int? = null,
) {
    // Only the numeric HTTP status survives. Throwable messages/causes/class names are never inspected.
    companion object {
        fun failure(area: DiagnosticArea, action: DiagnosticAction, error: Throwable) = DiagnosticEvent(area, action, when (error) {
            is ApiFailure -> when (error.status) { 401,403 -> DiagnosticCode.AUTH; 429 -> DiagnosticCode.QUOTA; else -> DiagnosticCode.SERVER }
            is MissingSavedKey -> DiagnosticCode.KEY
            is InvalidTranscript -> DiagnosticCode.RESPONSE
            is SecurityException -> DiagnosticCode.PERMISSION
            is RecordingStorageFailure, is VocabularyFailure -> DiagnosticCode.LOCAL_STORAGE
            is SocketTimeoutException -> DiagnosticCode.TIMEOUT
            is UnknownHostException -> DiagnosticCode.DNS
            is SSLException -> DiagnosticCode.TLS
            is IOException -> DiagnosticCode.IO
            is IllegalArgumentException -> DiagnosticCode.INVALID
            else -> DiagnosticCode.UNKNOWN
        }, (error as? ApiFailure)?.status?.takeIf { it in 100..599 })
    }
}
