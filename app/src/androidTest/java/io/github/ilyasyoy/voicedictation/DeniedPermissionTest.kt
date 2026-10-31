package io.github.ilyasyoy.voicedictation

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.ilyasyoy.voicedictation.data.AndroidAudioRecorder
import io.github.ilyasyoy.voicedictation.data.Transcriber
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import io.github.ilyasyoy.voicedictation.session.Phase
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Dedicated emulator fixture: reset only this app's microphone decision, then use real OS dialogs. */
class DeniedPermissionTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun deniedPermissionCanBeRequestedAgainWithoutBackgroundRecording() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        for (command in listOf("pm revoke ${context.packageName} ${Manifest.permission.RECORD_AUDIO}",
            "pm clear-permission-flags ${context.packageName} ${Manifest.permission.RECORD_AUDIO} user-set user-fixed")) {
            instrumentation.uiAutomation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        lateinit var model: DictationViewModel
        MainActivity.testNotice = "PERMISSION TEST · no OpenAI call"
        MainActivity.testFactory = { DictationViewModel(AndroidAudioRecorder(it), Transcriber { _, _ -> error("No upload expected") })
            .also { m -> model = m; m.setKey("synthetic-test-key") } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithTag("record").performScrollTo().performClick()
                val deny = device.wait(Until.findObject(By.res("com.android.packageinstaller", "permission_deny_button")), 10_000)
                    ?: device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_deny_button")), 10_000)
                assertNotNull("Microphone system permission dialog", deny); deny!!.click()
                compose.waitUntil(30_000) { model.state.value.phase == Phase.ERROR }
                compose.onNodeWithTag("problem").assertExists()
                val out = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(out, "06-permission-denied.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                for (command in listOf("mkdir -p /sdcard/Download/dictation-evidence",
                    "cp ${File(out, "06-permission-denied.png").absolutePath} /sdcard/Download/dictation-evidence/06-permission-denied.png")) {
                    instrumentation.uiAutomation.executeShellCommand(command).use {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                    }
                }
                compose.onNodeWithTag("record").performScrollTo().performClick()
                val allow = device.wait(Until.findObject(By.res("com.android.packageinstaller", "permission_allow_button")), 10_000)
                    ?: device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_foreground_only_button")), 10_000)
                assertNotNull(allow); allow!!.click()
                compose.waitUntil(30_000) { model.state.value.phase == Phase.RECORDING }
                compose.onNodeWithTag("cancel").performScrollTo().performClick()
                assertTrue(File(context.cacheDir, "dictation").listFiles().orEmpty().isEmpty())
            }
        } finally { MainActivity.testFactory = null; MainActivity.testNotice = null }
    }
}
