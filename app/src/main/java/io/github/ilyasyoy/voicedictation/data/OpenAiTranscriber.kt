package io.github.ilyasyoy.voicedictation.data

import io.github.ilyasyoy.voicedictation.domain.AudioPolicy
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject

fun interface Transcriber { suspend fun transcribe(audio: File, key: String): String }
class ApiFailure(val status: Int) : IOException()
class InvalidTranscript : IOException()

/** Endpoint injection is for tests only; the shipped application always uses HTTPS OpenAI. */
class OpenAiTranscriber(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.MINUTES).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES).writeTimeout(2, TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build(),
    private val endpoint: HttpUrl = "https://api.openai.com/v1/audio/transcriptions".toHttpUrl(),
) : Transcriber {
    override suspend fun transcribe(audio: File, key: String): String {
        require(audio.length() in 1..AudioPolicy.UPLOAD_BYTES)
        require(key.isNotBlank() && key.none { it.isWhitespace() || it.code < 32 })
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "gpt-transcribe")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "dictation.m4a", audio.asRequestBody("audio/mp4".toMediaType()))
            .build()
        val call = client.newCall(Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $key").post(body).build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw ApiFailure(it.code)
                            // Bound memory without exposing server messages, audio or credentials in logs/UI.
                            val source = it.body?.source() ?: throw InvalidTranscript()
                            if (source.request(1_000_001)) throw InvalidTranscript()
                            val json = JSONObject(source.readUtf8())
                            val text = json.opt("text") as? String ?: throw InvalidTranscript()
                            text.trim().ifEmpty { throw InvalidTranscript() }
                        }
                    }
                    if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
    }
}
