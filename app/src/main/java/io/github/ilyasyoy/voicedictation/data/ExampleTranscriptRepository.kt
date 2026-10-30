package io.github.ilyasyoy.voicedictation.data

/** A replaceable data boundary for the preview only; not a transcription provider. */
fun interface ExampleTranscriptRepository {
    fun loadExample(): String
}

object LocalExampleTranscriptRepository : ExampleTranscriptRepository {
    override fun loadExample(): String =
        "A small idea for today: make it easy to capture a thought, then return to what matters. " +
            "This is example text, not a voice transcription."
}
