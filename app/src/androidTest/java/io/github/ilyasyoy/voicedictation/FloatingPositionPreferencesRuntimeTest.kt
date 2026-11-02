package io.github.ilyasyoy.voicedictation

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.floating.*
import java.io.File
import org.junit.*
import org.junit.Assert.*

/** Real private preferences/commit, with no microphone, editor, accessibility grant or HTTP. */
class FloatingPositionPreferencesRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("floating", 0)
    private var original: Any? = null
    @Before fun before() { original = preferences.all[FloatingPositionPreferences.KEY] }
    @After fun after() {
        val editor = preferences.edit()
        when (val value = original) {
            null -> editor.remove(FloatingPositionPreferences.KEY)
            is String -> editor.putString(FloatingPositionPreferences.KEY, value)
            is Int -> editor.putInt(FloatingPositionPreferences.KEY, value)
            is Long -> editor.putLong(FloatingPositionPreferences.KEY, value)
            is Float -> editor.putFloat(FloatingPositionPreferences.KEY, value)
            is Boolean -> editor.putBoolean(FloatingPositionPreferences.KEY, value)
            is Set<*> -> editor.putStringSet(FloatingPositionPreferences.KEY, value.filterIsInstance<String>().toSet())
            else -> error("Unexpected test preference type")
        }
        assertTrue(editor.commit())
    }
    private fun area(ime: Int = 600) = checkNotNull(FloatingArea.create(360, 800, ime, 1f))
    @Test fun completedDragCommitsToDiskAndFreshAdapterRestoresIt() {
        val state = FloatingPositionState(FloatingPositionPreferences(context))
        val point = FloatingPoint(100, 300)
        assertTrue(state.dragged(point, area()))
        val encoded = checkNotNull(preferences.getString(FloatingPositionPreferences.KEY, null))
        val file = File(context.applicationInfo.dataDir, "shared_prefs/floating.xml")
        assertTrue(file.readText().contains(encoded))
        assertEquals(point, FloatingPositionState(FloatingPositionPreferences(context)).place(area()))
    }
    @Test fun wrongLegacyTypeFallsBackAndOnlyExplicitDragReplacesIt() {
        val enabled = preferences.getBoolean("enabled", false)
        assertTrue(preferences.edit().putInt(FloatingPositionPreferences.KEY, 999).commit())
        val state = FloatingPositionState(FloatingPositionPreferences(context))
        assertEquals(FloatingPoint(288, 528), state.place(area()))
        assertEquals(999, preferences.getInt(FloatingPositionPreferences.KEY, 0))
        assertTrue(state.dragged(FloatingPoint(100, 300), area()))
        assertEquals(enabled, preferences.getBoolean("enabled", false))
        assertEquals(FloatingPoint(100, 300), FloatingPositionState(FloatingPositionPreferences(context)).place(area()))
    }
    @Test fun geometryChangesDoNotWriteOverUserPreference() {
        val state = FloatingPositionState(FloatingPositionPreferences(context))
        assertTrue(state.dragged(FloatingPoint(100, 100), area()))
        val file = File(context.applicationInfo.dataDir, "shared_prefs/floating.xml")
        val before = file.readBytes()
        state.place(area(ime = 200)); state.place(area(ime = 700))
        assertArrayEquals(before, file.readBytes())
        assertEquals(FloatingPoint(100, 100), FloatingPositionState(FloatingPositionPreferences(context)).place(area()))
    }
}
