package io.github.ilyasyoy.voicedictation

import androidx.core.content.edit
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.session.LegacyModeMigration
import org.junit.Test
import org.junit.Assert.*

class LegacyModeMigrationTest {
    @Test fun savedOldLiveRecordDefaultAndMalformedPreferencesCannotRestoreARemovedMode() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val namespace=".record-only-test"
        val prefs=context.getSharedPreferences("dictation_mode$namespace",0)
        try {
            for (old in listOf("LIVE","RECORD",null,"unknown")) {
                prefs.edit(commit=true){clear();putString("sentinel","preserve other settings");if(old!=null)putString("explicit_mode",old)}
                LegacyModeMigration.migrate(context,namespace)
                assertFalse(prefs.contains("explicit_mode"));assertEquals("preserve other settings",prefs.getString("sentinel",null))
            }
            prefs.edit(commit=true){putInt("explicit_mode",7)}
            LegacyModeMigration.migrate(context,namespace);assertFalse(prefs.contains("explicit_mode"))
        } finally {prefs.edit(commit=true){clear()}}
    }
}
