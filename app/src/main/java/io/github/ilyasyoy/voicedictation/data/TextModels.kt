package io.github.ilyasyoy.voicedictation.data

import io.github.ilyasyoy.voicedictation.diagnostics.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Documented text/Responses models. /models does not return endpoint capabilities. */
object TextModels {
    const val DEFAULT = "gpt-6-luna"
    private val names = linkedMapOf(
        DEFAULT to "GPT-6 Luna", "gpt-6.1-sol" to "GPT-6.1 Sol", "gpt-6.1-terra" to "GPT-6.1 Terra",
        "gpt-6-sol" to "GPT-6 Sol", "gpt-6-astra" to "GPT-6 Astra",
        "gpt-4.1-mini" to "GPT-4.1 mini", "gpt-4.1" to "GPT-4.1", "gpt-4.1-nano" to "GPT-4.1 nano",
    )
    // Keep the old nano label for a saved choice, but never offer the shut-down model as a new choice.
    val predefined: List<String> get() = names.keys.filter { it != "gpt-4.1-nano" }
    fun label(id: String): String = names[id] ?: id
    fun supported(ids: List<String>): List<String> = predefined.filter { it in ids }
}

data class TextModelListState(val models: List<String> = TextModels.predefined,
    val loading: Boolean = false, val updated: Boolean = false, val failed: Boolean = false)

/** One explicit refresh; failures/cancel keep the last list. Selection belongs to Settings, never this owner. */
internal class TextModelList(private val scope: CoroutineScope, private val load: suspend () -> List<String>) {
    private val mutable = MutableStateFlow(TextModelListState())
    val state = mutable.asStateFlow()
    fun refresh() {
        if (state.value.loading) return
        mutable.value = state.value.copy(loading = true, failed = false)
        scope.launch {
            try {
                val models = TextModels.supported(load())
                currentCoroutineContext().ensureActive()
                mutable.value = TextModelListState(models, updated = true)
            } catch (cancel: CancellationException) {
                mutable.value = state.value.copy(loading = false)
                throw cancel
            } catch (_: Exception) { mutable.value = state.value.copy(loading = false, failed = true) }
        }
    }
}

/** Metadata-only GET using the existing saved key. Never called on opening Settings or selecting a model. */
internal class OpenAiTextModels(
    client: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
    private val endpoint: HttpUrl = "https://api.openai.com/v1/models".toHttpUrl(),
) {
    private val transport = client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // Stop status-directed follow-ups too, including 503 Retry-After: 0 on GET.
            if (!response.isSuccessful) { val status = response.code; response.close(); throw ApiFailure(status) }
            response
        }.build()
    suspend fun list(key: String): List<String> {
        require(key.isNotBlank() && key.all { it.code in 33..126 })
        Diagnostics.record(DiagnosticArea.MODELS, DiagnosticAction.START)
        try { return request(key).also { Diagnostics.record(DiagnosticArea.MODELS, DiagnosticAction.STOP) } }
        catch (cancel: CancellationException) { Diagnostics.record(DiagnosticArea.MODELS, DiagnosticAction.CANCEL, DiagnosticCode.CANCELLED); throw cancel }
        catch (error: Exception) { Diagnostics.failure(DiagnosticArea.MODELS, DiagnosticAction.PROCESS, error); throw error }
    }
    private suspend fun request(key: String): List<String> {
        val call = transport.newCall(Request.Builder().url(endpoint).header("Authorization", "Bearer $key").get().build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val source = it.body?.source() ?: throw InvalidModelList()
                            if (source.request(1_000_001)) throw InvalidModelList()
                            val json = JSONObject(source.readUtf8())
                            if (json.optString("object") != "list") throw InvalidModelList()
                            val data = json.optJSONArray("data") ?: throw InvalidModelList()
                            if (data.length() > 10_000) throw InvalidModelList()
                            val ids = (0 until data.length()).map { index ->
                                val row = data.getJSONObject(index)
                                (row.opt("id") as? String)?.takeIf { id ->
                                    id.length in 1..100 && id.all { c -> c.code in 33..126 }
                                } ?: throw InvalidModelList()
                            }
                            TextModels.supported(ids)
                        }
                    }
                    if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
    }
}
internal class InvalidModelList : IOException()
