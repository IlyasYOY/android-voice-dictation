package io.github.ilyasyoy.voicedictation

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Run seed and verify in separate force-stopped processes. Never creates a recorder or real audio. */
class OrphanCleanupRestartTest {
    @Test fun processStartupRemovesCrashOrphanWithoutOpeningRecorder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "dictation")
        val orphan = File(directory, "synthetic-process-orphan.m4a")
        if (InstrumentationRegistry.getArguments().getString("orphanPhase") == "seed") {
            directory.mkdirs(); orphan.writeText("SYNTHETIC lifecycle marker, not audio")
            assertTrue(orphan.exists())
        } else {
            assertFalse("Application startup must clean the previous process's orphan before any recording session", orphan.exists())
        }
    }
}
