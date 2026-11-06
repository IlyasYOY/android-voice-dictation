package io.github.ilyasyoy.voicedictation.data

import okhttp3.RequestBody
import okio.BufferedSink

/** Also blocks server-directed follow-ups (503/421), beyond connection-failure retries. */
internal fun RequestBody.singleAttempt(): RequestBody {
    val original = this
    return object : RequestBody() {
        override fun contentType() = original.contentType()
        override fun contentLength() = original.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = original.writeTo(sink)
    }
}
