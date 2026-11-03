package io.github.ilyasyoy.voicedictation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ilyasyoy.voicedictation.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class VocabularyViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(VocabularyState())
    val state = mutable.asStateFlow()
    private val mutableBusy = MutableStateFlow(true)
    val busy = mutableBusy.asStateFlow()
    private val mutableIssue = MutableStateFlow<VocabularyIssue?>(null)
    val issue = mutableIssue.asStateFlow()
    private var repository: VocabularyRepository? = null
    init { viewModelScope.launch {
        val store = try { withContext(Dispatchers.IO) { AndroidVocabularyStorage.repository(application) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableIssue.value = VocabularyIssue.STORAGE; null }
        mutableBusy.value = false
        if (store == null) return@launch
        repository = store
        store.state.collect { mutable.value = it }
    } }
    fun clearIssue() { mutableIssue.value = null }
    fun save(original: String?, input: String, onSuccess: () -> Unit) = mutate(onSuccess) {
        if (original == null) add(input) else edit(original, input)
    }
    fun delete(word: String) = mutate({}) { delete(word) }
    fun reload() = mutate({}) { refresh(); snapshot() }
    private fun mutate(onSuccess: () -> Unit, action: VocabularyRepository.() -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { (repository ?: throw VocabularyFailure(VocabularyIssue.STORAGE)).action() }
                mutableIssue.value = null; onSuccess()
            } catch (e: CancellationException) { throw e }
            catch (e: VocabularyFailure) { mutableIssue.value = e.issue }
            catch (_: Exception) { mutableIssue.value = VocabularyIssue.STORAGE }
            finally { mutableBusy.value = false }
        }
    }
}
