package io.github.ilyasyoy.voicedictation.migration

import org.junit.Assert.assertThrows
import org.junit.Test

/** Bytecode regression: removing the toggle must not leave old executable entrypoints on the classpath. */
class RecordOnlyEntrypointsTest {
    @Test fun streamingExecutionAndModeSelectorClassesAreAbsent() {
        for (name in listOf(
            "session.Mode", "session.ModePreferences", "data.PcmSource", "data.AndroidPcmSource",
            "data.RealtimeTranscriber", "data.OpenAiRealtimeTranscriber", "data.LiveSession",
            "domain.LiveTranscript", "ui.design.ModeSelectorKt",
        )) assertThrows(ClassNotFoundException::class.java) {
            Class.forName("io.github.ilyasyoy.voicedictation.$name",false,javaClass.classLoader)
        }
    }
}
