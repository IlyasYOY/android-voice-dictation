package io.github.ilyasyoy.voicedictation.session

enum class Mode { RECORD, LIVE }
enum class Phase { IDLE, CONNECTING, RECORDING, TRANSCRIBING, FINALIZING, RESULT, ERROR }
enum class Problem { KEY, STORAGE, LOCAL_STORAGE, PERMISSION, RECORDING, NETWORK, AUTH, QUOTA, SERVER, RESPONSE }
data class DictationState(
    val mode: Mode = Mode.RECORD,
    val phase: Phase = Phase.IDLE,
    val key: String = "",
    val keySaved: Boolean = false,
    val keyBusy: Boolean = false,
    val keyLoaded: Boolean = true,
    val hasSavedKey: Boolean = false,
    val text: String = "",
    val seconds: Int = 0,
    val problem: Problem? = null,
    val canRetry: Boolean = false,
    val livePartial: Boolean = false,
    val completedSegments: Int = 0,
    val safetyStopped: Boolean = false,
    val capacityBlocked: Boolean = false,
) {
    val audioBusy get() = phase in setOf(Phase.CONNECTING, Phase.RECORDING, Phase.TRANSCRIBING, Phase.FINALIZING)
    val busy get() = audioBusy || keyBusy
    val validKey get() = key.isNotEmpty() && key.all { it.code in 33..126 }
    override fun toString() = "DictationState(mode=$mode, phase=$phase, key=<redacted>, textLength=${text.length}, problem=$problem)"
    val elapsed get() = "%02d:%02d".format(seconds / 60, seconds % 60)
}

