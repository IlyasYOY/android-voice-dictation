package io.github.ilyasyoy.voicedictation.fixture

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.Base64
import java.util.regex.Pattern
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.security.MessageDigest
import java.util.Properties
import org.junit.runners.MethodSorters

/** Instrumentation belongs to the fixture UID, not the product UID. No product instrumentation exemptions. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CrossAppTest {
    @get:Rule val testName=org.junit.rules.TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private fun checkpoint(value: String) { instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\nCHECKPOINT: $value\n") }) }
    private fun launch(component: String, port: Int? = null) {
        val intent = Intent().setComponent(ComponentName(if (component.startsWith("$FIXTURE.")) FIXTURE else APP, component))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (port != null) intent.putExtra("port", port)
        if (component == "$FIXTURE.FixtureActivity") intent.putExtra("field", "body")
        instrumentation.targetContext.startActivity(intent)
    }
    private fun awaitObject(selector: BySelector, timeout: Long): UiObject2? =
        device.wait(Until.findObject(selector), timeout)
    private fun has(selector: BySelector, timeout: Long) = awaitObject(selector, timeout) != null
    private fun find(selector: BySelector, timeout: Long = 30_000): UiObject2 =
        awaitObject(selector, timeout) ?: run {
            device.dumpWindowHierarchy(java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "failure-window.xml"))
            throw AssertionError("Missing UI selector: $selector")
        }
    private fun scroll(direction: Direction, pkg: String = APP) {
        try { device.findObject(By.pkg(pkg).scrollable(true))?.scroll(direction, 0.7f) }
        catch (_: StaleObjectException) { /* Permission return may rebuild the setup view. Re-query next time. */ }
        SystemClock.sleep(250)
    }
    private fun scrollFind(selector: BySelector): UiObject2 {
        val pkg = when {
            selector.toString().contains(FIXTURE) -> FIXTURE
            selector.toString().contains(APP) -> APP
            else -> "com.android.settings"
        }
        repeat(5) { device.findObject(selector)?.let { return it }; scroll(Direction.UP, pkg) }
        repeat(9) { device.findObject(selector)?.let { return it }; scroll(Direction.DOWN, pkg) }
        return find(selector)
    }
    private fun label(value: String) = By.text(Pattern.compile("(?i)" + Pattern.quote(value)))
    private fun focusField(name: String) {
        instrumentation.targetContext.startActivity(Intent().setComponent(ComponentName(FIXTURE, "$FIXTURE.FixtureActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("field", name))
    }
    private fun showPicker() {
        instrumentation.targetContext.startActivity(Intent().setComponent(ComponentName(FIXTURE, "$FIXTURE.FixtureActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("picker", true))
    }
    private fun appId(name: String) = By.res(APP, name)
    private fun fixtureId(name: String) = By.res(FIXTURE, name)
    private fun dismissSystemAnr() {
        device.findObject(By.res("android", "aerr_close"))?.click()
    }
    private fun configureFake() {
        checkpoint("Configuring isolated synthetic key and localhost server")
        dismissSystemAnr()
        device.pressHome()
        launch("$APP.floating.DebugFloatingSetupActivity", server.port)
        find(label("Enable isolated fixture mode"), 40_000).click()
        assertTrue(device.wait(Until.gone(label("Enable isolated fixture mode")), 60_000))
        launch("$APP.floating.DebugFloatingSetupActivity",server.port)
        find(label("Use Live fixture mode")).click()
    }
    private fun specialAccessEnabled() = instrumentation.targetContext.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == APP }
    private fun setupUserOptIn() {
        val pm = instrumentation.targetContext.packageManager
        if (specialAccessEnabled() && pm.checkPermission(android.Manifest.permission.RECORD_AUDIO, APP) == 0 &&
            (Build.VERSION.SDK_INT < 33 || pm.checkPermission(android.Manifest.permission.POST_NOTIFICATIONS, APP) == 0)) {
            checkpoint("Existing explicitly granted OS access retained; no permission changes needed")
            return
        }
        checkpoint("Opening explicit special-access onboarding")
        launch("$APP.MainActivity")
        find(label("Set up floating dictation"), 60_000).click()
        find(By.clazz("android.widget.ScrollView"), 40_000)
        screenshot("00-floating-setup")
        if (instrumentation.targetContext.packageManager.checkPermission(android.Manifest.permission.RECORD_AUDIO, APP) != 0) {
            checkpoint("Requesting microphone through OS UI")
            scrollFind(appId("floating_mic")).click()
            val allow = device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_foreground_only_button")), 40_000)
                ?: find(By.res("com.android.packageinstaller", "permission_allow_button"), 40_000)
            allow.click()
            device.wait(Until.gone(By.res("com.android.permissioncontroller", "permission_allow_foreground_only_button")), 40_000)
        }
        if (Build.VERSION.SDK_INT >= 33 && instrumentation.targetContext.packageManager.checkPermission(android.Manifest.permission.POST_NOTIFICATIONS, APP) != 0) {
            scrollFind(appId("floating_notifications")).click()
            find(By.res("com.android.permissioncontroller", "permission_allow_button"), 40_000).click()
            device.wait(Until.gone(By.res("com.android.permissioncontroller", "permission_allow_button")), 40_000)
        }
        if (specialAccessEnabled()) return
        // Scroll back to the explicit disclosure/opt-in button.
        repeat(3) { scroll(Direction.UP) }
        checkpoint("Opening accessibility disclosure/settings")
        scrollFind(appId("floating_enable")).click()
        val agree=By.res("android","button1").pkg(APP)
        val consentDeadline=SystemClock.elapsedRealtime()+40_000
        while (!device.hasObject(agree) && !device.hasObject(By.pkg("com.android.settings")) && SystemClock.elapsedRealtime()<consentDeadline) SystemClock.sleep(100)
        device.findObject(agree)?.click()
        find(By.pkg("com.android.settings"), 40_000)
        val serviceLabel = "Voice Dictation floating button"
        if (!device.hasObject(label(serviceLabel))) {
            device.wait(Until.findObject(label("Downloaded apps")), 10_000)?.click()
            device.wait(Until.findObject(label("Installed apps")), 10_000)?.click()
            device.wait(Until.findObject(label("Downloaded services")), 1000)?.click()
        }
        checkpoint("Selecting service in Android Accessibility settings")
        val row = By.res("android", "title").text(Pattern.compile(Pattern.quote(serviceLabel)))
        scrollFind(row).click()
        SystemClock.sleep(1000)
        val switch = device.wait(Until.findObject(By.res("com.android.settings", "switch_widget")), 15_000)
            ?: device.wait(Until.findObject(By.clazz("android.widget.Switch")), 3000)
            ?: find(By.checkable(true))
        if (switch.isChecked && !specialAccessEnabled()) {
            // Force-stop/install may leave the dedicated emulator's setting On but its service unbound.
            // Recover through the same visible Off/On controls and OS confirmation, never a shell grant.
            switch.click()
            val offDeadline=SystemClock.elapsedRealtime()+10_000
            while (!device.hasObject(By.checkable(true).checked(false)) && SystemClock.elapsedRealtime()<offDeadline) {
                device.findObject(By.res("android","button1").pkg("com.android.settings"))?.click()
                SystemClock.sleep(100)
            }
            find(By.checkable(true).checked(false),10_000).click()
        } else if (!switch.isChecked) switch.click()
        val enableDeadline=SystemClock.elapsedRealtime()+30_000
        while (!specialAccessEnabled() && SystemClock.elapsedRealtime()<enableDeadline) {
            device.findObject(By.res("android","button1").pkg("com.android.settings"))?.click()
            device.findObject(label("Allow"))?.click()
            SystemClock.sleep(200)
        }
        assertTrue("OS accessibility consent must actually enable the chosen service",specialAccessEnabled())
    }
    @Before fun prepare() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        val passiveRecovery=testName.methodName in setOf("vDurablePhase2UpgradeRestoresLegacyAudioWithoutAnAutomaticUpload",
            "wDurablePhase3RebootManualRetryKeepsSuccessThenExplicitDeleteSurvivesRestart")
        if (!configured) {
            configureFake(); if(!passiveRecovery)setupUserOptIn(); configured = true
        }
        if(passiveRecovery) {checkpoint("Main saved-recording recovery requires no microphone or Accessibility grant");return}
        checkpoint("Opening harmless fixture editor")
        launch("$FIXTURE.FixtureActivity", server.port)
        // Show on-screen keyboards in this isolated emulator even when a virtual hardware keyboard is attached.
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        find(fixtureId("body"), 40_000)
        // Test isolation uses the same explicit discard action as a user, never teardown data loss.
        find(appId("floating_start"),30_000)
        device.findObject(By.desc("Review text or recording problem"))?.let {
            it.click(); scrollFind(appId("floating_discard")).click()
            find(By.desc("Start dictation"),10_000)
        }
    }
    @After fun cleanup() { device.pressBack(); SystemClock.sleep(300) }
    private fun screenshot(name: String) {
        val directory = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "crossapp-evidence").apply { mkdirs() }
        assertTrue("Real emulator screenshot must be captured", device.takeScreenshot(java.io.File(directory, "$name.png")))
    }
    @Test fun aKeyboardBubbleLiveStopInsertsWithoutReplacingExistingText() {
        val count=connections.get()
        startPartial(); assertFieldUnchanged()
        screenshot("02-live-partial-original-field-unchanged")
        find(appId("floating_stop")).click()
        find(appId("floating_start"),60_000)
        assertFalse(device.hasObject(appId("floating_status")))
        screenshot("03-final-no-report")
        if(Build.VERSION.SDK_INT>=33) assertTrue(has(fixtureId("body").text("Before voice test after"),60_000))
        else {
            assertFieldUnchanged(); openRecovery()
            assertEquals("voice test",scrollFind(appId("floating_preview")).text)
            screenshot("03-optional-legacy-copy-in-setup")
            focusField("body"); assertFieldUnchanged()
        }
        assertEquals(count+1,connections.get())
        assertFalse(device.executeShellCommand("dumpsys activity services $APP").contains("isForeground=true"))
        screenshot("03b-unobscured-field")
    }
    private fun openRecovery() {
        assertFalse("No automatic error/result panel",device.hasObject(appId("floating_status")))
        find(appId("floating_start"),60_000).longClick()
        // Recovery intentionally opens scrolled to retained audio/text, below the permission controls.
        find(By.clazz("android.widget.ScrollView").pkg(APP),30_000)
    }
    private fun waitForAudio(previous: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (bytes.get() <= previous && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        return bytes.get() > previous && has(appId("floating_stop"), 20_000)
    }
    private fun startPartial() {
        val previous = bytes.get()
        find(appId("floating_start"), 60_000).click()
        assertTrue("Actual PCM must reach the fixture; no live transcript panel is shown", waitForAudio(previous))
    }
    private fun restartPartial() {
        if(device.hasObject(By.pkg(APP))) {
            if(has(appId("floating_discard"),2000)) scrollFind(appId("floating_discard")).click()
            focusField("body")
        }
        startPartial()
    }
    private fun cancelFloating() {
        find(appId("floating_stop")).longClick()
        assertTrue("Opening the non-focusable controls must not stop recording", has(appId("floating_stop"), 5000))
        find(label("Cancel")).click()
    }
    private fun assertFieldUnchanged() = assertEquals("Before  after", find(fixtureId("body")).text)
    @Test fun bCancelAndRestartNeverInsertPartialOrCreateDuplicateConnection() {
        startPartial(); val count=connections.get(); cancelFloating()
        find(appId("floating_start"),30_000); assertFieldUnchanged()
        assertFalse(device.hasObject(appId("floating_status")))
        SystemClock.sleep(1000); assertEquals(count,connections.get())
        startPartial(); assertEquals(count+1,connections.get())
        find(appId("floating_stop")).click(); find(appId("floating_start"),60_000)
        assertFalse(device.hasObject(appId("floating_status")))
    }
    @Test fun cFocusChangeCancelsAndNeverRetargets() {
        startPartial(); focusField("second")
        find(appId("floating_start"),30_000)
        assertFalse(device.hasObject(appId("floating_status")))
        assertFieldUnchanged(); assertEquals("Other field",find(fixtureId("second_body")).text)
        openRecovery(); assertTrue(scrollFind(appId("floating_preview")).text.contains("Bubble words"))
        screenshot("06-optional-cancelled-text-in-setup")
    }
    @Test fun dUnsupportedEditorOffersCopyWithoutWriting() {
        focusField("unsupported"); startPartial(); find(appId("floating_stop")).click()
        find(appId("floating_start"),60_000)
        assertEquals("Unsupported stays unchanged",find(fixtureId("unsupported_body")).text)
        assertFalse(device.hasObject(appId("floating_status")))
        openRecovery(); assertTrue(scrollFind(appId("floating_preview")).text.contains("voice test"))
        assertTrue(scrollFind(appId("floating_copy")).isEnabled)
        screenshot("07-explicit-unsent-copy-in-setup")
    }
    @Test fun ePasswordAndNumericFieldsNeverShowBubble() {
        focusField("password")
        assertTrue(device.wait(Until.gone(appId("floating_start")), 10_000))
        screenshot("08-password-excluded")
        focusField("numeric")
        assertTrue(device.wait(Until.gone(appId("floating_start")), 10_000))
        screenshot("09-numeric-excluded")
    }
    @Test fun fMovingBubbleDoesNotStartMicrophone() {
        val bubble = find(appId("floating_start")); val bounds = bubble.visibleBounds
        val count = connections.get()
        device.swipe(bounds.centerX(), bounds.centerY(), 45, 90, 25)
        SystemClock.sleep(700); assertEquals(count, connections.get())
        val moved = find(appId("floating_start")).visibleBounds
        assertTrue(moved.centerX() != bounds.centerX() || moved.centerY() != bounds.centerY())
        screenshot("10-moved-bubble-no-recording")
        startPartial(); cancelFloating(); assertFieldUnchanged()
    }
    @Test fun gStreamErrorStopsAndRequiresExplicitRestart() {
        failNext.set(true); val count=connections.get()
        find(appId("floating_start")).click()
        assertTrue(has(By.desc("Start dictation"),40_000))
        SystemClock.sleep(1800)
        assertEquals(count+1,connections.get()); assertFalse(device.hasObject(appId("floating_status")))
        assertFieldUnchanged(); screenshot("11-network-error-red-no-popup")
        val previous=bytes.get(); find(appId("floating_start")).click()
        assertTrue(waitForAudio(previous)); assertEquals(count+2,connections.get())
        screenshot("11b-explicit-retry-purple"); cancelFloating()
    }
    @Test fun hMissingSavedKeyStartsNoAudioOrRequest() {
        launch("$APP.floating.DebugFloatingSetupActivity",server.port)
        find(label("Forget fixture key")).click()
        assertTrue(device.wait(Until.gone(label("Forget fixture key")),60_000))
        focusField("body")
        val count=connections.get(); val previous=bytes.get()
        find(appId("floating_start")).click(); SystemClock.sleep(1600)
        assertEquals(count,connections.get()); assertEquals(previous,bytes.get())
        assertFalse(device.hasObject(appId("floating_status"))); assertFieldUnchanged()
    }
    @Test fun iIndependentSecondKeyboardUsesSameBubbleAndInsertion() {
        // Dedicated synthetic emulator: configure the independent test IME deterministically.
        // The product never calls these shell commands or replaces the user's keyboard.
        device.executeShellCommand("ime enable $FIXTURE/.FixtureKeyboard")
        device.executeShellCommand("ime set $FIXTURE/.FixtureKeyboard")
        assertEquals("$FIXTURE/.FixtureKeyboard", device.executeShellCommand("settings get secure default_input_method").trim())
        launch("$FIXTURE.FixtureActivity", server.port)
        find(fixtureId("body"), 40_000)
        focusField("body")
        find(label("Fixture keyboard · TEST ONLY"), 40_000)
        checkpoint("Second IME selected by dedicated-emulator fixture setup and its actual input window is visible")
        SystemClock.sleep(11_000) // Expire recent visible-product-Activity grace and allow real video capture.
        val initialConnections = connections.get(); val initialBytes = bytes.get()
        startPartial(); screenshot("14-independent-test-keyboard-live")
        find(appId("floating_stop")).click()
        if (Build.VERSION.SDK_INT >= 33) {
            assertTrue(has(fixtureId("body").text("Before voice test after"),60_000))
            assertTrue(has(By.desc("Start dictation"),10_000))
            assertFalse(device.hasObject(appId("floating_status")))
        } else {
            openRecovery()
            assertTrue(has(By.res(APP,"floating_status").textContains("Nothing inserted. Copy"),30_000))
            assertFieldUnchanged()
        }
        assertEquals(initialConnections + 1, connections.get()); assertTrue(bytes.get() > initialBytes)
        assertFalse(device.executeShellCommand("dumpsys activity services $APP").contains("isForeground=true"))
        checkpoint("Independent keyboard insertion preserved surrounding text; ${bytes.get() - initialBytes} PCM bytes; microphone foreground ended")
        screenshot("15-independent-test-keyboard-inserted")
        if (Build.VERSION.SDK_INT < 33) find(appId("floating_close")).click()
        assertTrue(device.wait(Until.gone(appId("floating_close")), 15_000))
        SystemClock.sleep(700)
        screenshot("18-second-ime-final-field-unobscured")
        device.pressBack()
        device.executeShellCommand("ime set com.android.inputmethod.latin/.LatinIME")
    }
    @Test fun jScreenOffStopsAndHidesWithoutRestart() {
        startPartial(); val count = connections.get()
        device.sleep(); assertTrue(device.wait(Until.gone(appId("floating_start")), 10_000))
        SystemClock.sleep(700); device.wakeUp(); device.pressMenu(); SystemClock.sleep(1000)
        assertEquals(count, connections.get())
        screenshot("13-screen-off-cleanup")
    }
    @Test fun kRevokedMicrophoneStopsAndNextTapIsPermissionGated() {
        startPartial(); val count=connections.get()
        device.executeShellCommand("pm revoke $APP android.permission.RECORD_AUDIO")
        SystemClock.sleep(1200); find(appId("floating_start"),30_000).click()
        SystemClock.sleep(1600)
        assertEquals(count,connections.get()); assertFieldUnchanged()
        assertFalse(device.hasObject(appId("floating_status")))
        setupUserOptIn()
    }
    @Test fun qDefaultRecordUploadsOnlyAfterStopAndNextTapStartsNormally() {
        launch("$APP.floating.DebugFloatingSetupActivity",server.port)
        find(label("Use default Record fixture mode")).click(); focusField("body")
        // The returning Activity may receive onNewIntent before its window regains input focus.
        // A real tap establishes the IME connection; then restore the fixture's insertion caret.
        find(fixtureId("body")).click(); focusField("body")
        val streams=connections.get(); val uploaded=fileRequests.get()
        failFileNext.set(true)
        repeat(2) { turn ->
            find(appId("floating_start"),30_000).click()
            find(appId("floating_stop"),30_000)
            SystemClock.sleep(1800)
            assertEquals("Record must not start a streaming connection",streams,connections.get())
            assertEquals("No upload before Stop",uploaded+turn,fileRequests.get())
            screenshot("23-record-local-$turn")
            find(appId("floating_stop")).click()
            val end=SystemClock.elapsedRealtime()+30_000
            while(fileRequests.get()<uploaded+turn+1 && SystemClock.elapsedRealtime()<end)SystemClock.sleep(100)
            assertEquals(uploaded+turn+1,fileRequests.get()); assertTrue(fileBytes.get()>0)
            find(appId("floating_start"),30_000)
            assertFalse("No result/recovery popup",device.hasObject(appId("floating_status")))
        }
        screenshot("24-record-repeat-no-popup")
        openRecovery()
        scrollFind(appId("floating_retry_audio")).click()
        // Setup rebuilds its view while retry starts; a transiently absent button is not completion.
        val retryDeadline=SystemClock.elapsedRealtime()+30_000
        while(fileRequests.get()<uploaded+3 && SystemClock.elapsedRealtime()<retryDeadline)SystemClock.sleep(100)
        assertEquals(uploaded+3,fileRequests.get())
        assertTrue(device.wait(Until.gone(appId("floating_retry_audio")),30_000))
        assertTrue(scrollFind(appId("floating_preview")).text.contains("voice test"))
        screenshot("25-explicit-saved-audio-retry-no-editor-replay")
        assertFalse(device.executeShellCommand("dumpsys activity services $APP").contains("isForeground=true"))
        checkpoint("Default floating Record used real MediaRecorder; two explicit Stop uploads plus one explicit saved-audio retry, zero WebSocket connections, no intercepting recovery popup")
    }
    @Test fun rRecoverySurvivesKeyboardHideSetupAndLargeFontWithoutReplay() {
        val originalScale=device.executeShellCommand("settings get system font_scale").trim()
        try {
            focusField("unsupported"); val count=connections.get()
            startPartial(); find(appId("floating_stop")).click(); find(appId("floating_start"),60_000)
            assertFalse(device.hasObject(appId("floating_status")))
            device.pressBack(); focusField("body"); assertFieldUnchanged()
            device.executeShellCommand("settings put system font_scale 2")
            SystemClock.sleep(1200); focusField("body"); openRecovery()
            assertTrue(scrollFind(appId("floating_preview")).text.contains("voice test"))
            scrollFind(appId("floating_copy")).click()
            assertTrue(instrumentation.targetContext.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString().contains("voice test"))
            screenshot("22-large-font-optional-recovery-in-setup")
            assertEquals(count+1,connections.get())
            scrollFind(appId("floating_discard")).click()
            focusField("body"); assertFieldUnchanged()
            assertEquals(count+1,connections.get())
            checkpoint("Optional unsent recovery survives visibility/setup changes; next tap is never a recovery gate")
        } finally {
            device.executeShellCommand(if(originalScale=="null")"settings delete system font_scale" else "settings put system font_scale $originalScale")
        }
    }
    private fun durableStateFile()=java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"vd004-upgrade.properties")
    private fun digest(audio:ByteArray)=MessageDigest.getInstance("SHA-256").digest(audio).joinToString("") {"%02x".format(it)}
    private fun openSavedFromMain() {
        launch("$APP.MainActivity")
        find(By.text(Pattern.compile("Saved recordings \\(.*\\)")),60_000).click()
        find(By.text("Saved recordings"),30_000)
    }
    @Test fun uDurablePhase1CreateFailedRecordingInAcceptedVersionBeforeUpgrade() {
        launch("$APP.floating.DebugFloatingSetupActivity",server.port)
        find(label("Use default Record fixture mode")).click();focusField("body")
        find(fixtureId("body")).click();focusField("body")
        failFileNext.set(true)
        find(appId("floating_start")).click();find(appId("floating_stop"),40_000)
        SystemClock.sleep(2200);assertEquals(0,fileRequests.get());assertEquals(0,connections.get())
        find(appId("floating_stop")).click()
        val end=SystemClock.elapsedRealtime()+40_000
        while(fileRequests.get()<1 && SystemClock.elapsedRealtime()<end)SystemClock.sleep(100)
        assertEquals(1,fileRequests.get());assertTrue(lastFileAudio.get().size>1000)
        find(appId("floating_start"),40_000)
        assertFalse(device.hasObject(appId("floating_status")))
        val state=Properties().apply {setProperty("audio_sha256",digest(lastFileAudio.get()));setProperty("audio_bytes",lastFileAudio.get().size.toString())}
        java.io.FileOutputStream(durableStateFile()).use {state.store(it,null);it.fd.sync()}
        screenshot("40-before-upgrade-failed-recording")
        checkpoint("Upgrade phase1: accepted old APK captured ${lastFileAudio.get().size} bytes; HTTP503; stopped audio remains; host must install candidate without clearing product data")
    }
    @Test fun vDurablePhase2UpgradeRestoresLegacyAudioWithoutAnAutomaticUpload() {
        assertTrue(durableStateFile().isFile)
        openSavedFromMain()
        find(appId("floating_retry_audio"),30_000)
        assertTrue(has(By.text("Audio saved"),10_000))
        SystemClock.sleep(1500);assertEquals(0,fileRequests.get());assertEquals(0,connections.get())
        screenshot("41-after-upgrade-pending-before-reboot")
        checkpoint("Upgrade phase2: candidate restored legacy cache into durable storage; manual Retry visible; zero startup HTTP/WS; host must reboot before phase3")
    }
    @Test fun wDurablePhase3RebootManualRetryKeepsSuccessThenExplicitDeleteSurvivesRestart() {
        val expected=Properties().apply {durableStateFile().inputStream().use {load(it)}}
        openSavedFromMain();find(appId("floating_retry_audio"),30_000)
        SystemClock.sleep(1200);assertEquals(0,fileRequests.get())
        find(appId("floating_retry_audio")).click()
        val end=SystemClock.elapsedRealtime()+40_000
        while(fileRequests.get()<1 && SystemClock.elapsedRealtime()<end)SystemClock.sleep(100)
        assertEquals(1,fileRequests.get());assertEquals(expected.getProperty("audio_sha256"),digest(lastFileAudio.get()))
        assertEquals(expected.getProperty("audio_bytes").toInt(),lastFileAudio.get().size)
        assertTrue(scrollFind(appId("floating_preview")).text.contains("voice test"))
        assertTrue(has(By.text("Audio saved"),10_000))
        device.executeShellCommand("am force-stop $APP")
        openSavedFromMain()
        assertTrue(scrollFind(appId("floating_preview")).text.contains("voice test"))
        assertTrue(has(By.text("Audio saved"),10_000))
        assertFalse(device.hasObject(appId("floating_retry_audio")));assertEquals(1,fileRequests.get())
        screenshot("42-success-audio-and-text-after-process-death")
        scrollFind(appId("saved_delete")).click();find(By.res("android","button1")).click()
        find(By.text("No saved recordings yet."),30_000)
        screenshot("43-explicit-delete-complete")
        device.executeShellCommand("am force-stop $APP");launch("$APP.MainActivity")
        SystemClock.sleep(1800)
        assertFalse(device.hasObject(By.text(Pattern.compile("Saved recordings \\(.*\\)"))))
        assertEquals(1,fileRequests.get())
        checkpoint("Upgrade + real reboot + manual retry passed: every audio byte matched; successful audio/text survived process death; explicit delete survived restart; exactly one explicit post-reboot HTTP and zero WS")
    }
    @Test fun zDisableRevokesAccessAndCleansTestConfiguration() {
        launch("$APP.MainActivity")
        find(label("Set up floating dictation"), 60_000).click()
        find(By.clazz("android.widget.ScrollView"), 40_000)
        screenshot("00-floating-setup")
        scrollFind(appId("floating_disable")).click()
        launch("$FIXTURE.FixtureActivity", server.port); find(fixtureId("body"), 40_000)
        assertTrue(device.wait(Until.gone(appId("floating_start")), 15_000))
        screenshot("17-disabled-no-bubble")
        launch("$APP.floating.DebugFloatingSetupActivity", server.port)
        find(label("Disable fixture mode"), 30_000).click()
        checkpoint("Explicit app disable removed special access; synthetic key/endpoint cleared")
    }
    companion object {
        private const val APP = "io.github.ilyasyoy.voicedictation"
        private const val FIXTURE = "io.github.ilyasyoy.voicedictation.fixture"
        private lateinit var server: MockWebServer
        private val connections = AtomicInteger()
        private val fileRequests = AtomicInteger()
        private val failFileNext=AtomicBoolean()
        private val fileBytes = AtomicInteger()
        private val lastFileAudio=AtomicReference(ByteArray(0))
        private val bytes = AtomicInteger()
        private val failNext = AtomicBoolean()
        private var configured = false
        private val scheduler = Executors.newSingleThreadScheduledExecutor()
        @JvmStatic @BeforeClass fun start() {
            Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
            InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            Configurator.getInstance().waitForIdleTimeout = 0
            server = MockWebServer().apply {
                dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if(request.path?.startsWith("/v1/audio/transcriptions")==true) {
                            val raw=request.body.clone().readByteArray()
                            val boundary=checkNotNull(request.getHeader("Content-Type")).substringAfter("boundary=").trim('"')
                            fun locate(needle:ByteArray,from:Int=0):Int=(from..raw.size-needle.size).firstOrNull {start->needle.indices.all {raw[start+it]==needle[it]}} ?: -1
                            val fileHeader=locate("filename=\"dictation.m4a\"".toByteArray())
                            val begin=locate("\r\n\r\n".toByteArray(),fileHeader)+4
                            val finish=locate(("\r\n--"+boundary).toByteArray(),begin)
                            check(fileHeader>=0 && begin>=4 && finish>begin)
                            lastFileAudio.set(raw.copyOfRange(begin,finish))
                            fileBytes.addAndGet(request.bodySize.toInt());fileRequests.incrementAndGet()
                            if(failFileNext.compareAndSet(true,false))return MockResponse().setResponseCode(503)
                            return MockResponse().setBody("{\"text\":\"voice test\"}")
                        }
                        return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        private var sent = false; private var commits = 0
                        override fun onOpen(webSocket: WebSocket, response: Response) { connections.incrementAndGet() }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val event = JSONObject(text)
                            when (event.getString("type")) {
                                "session.update" -> webSocket.send("{\"type\":\"session.updated\",\"session\":{\"type\":\"transcription\"}}")
                                "input_audio_buffer.append" -> {
                                    bytes.addAndGet(Base64.getDecoder().decode(event.getString("audio")).size)
                                    if (failNext.compareAndSet(true, false)) { webSocket.close(1011, "fixture failure"); return }
                                    if (!sent) { sent = true; webSocket.send("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"turn-1\",\"event_id\":\"delta-1\",\"delta\":\"Bubble words\"}") }
                                }
                                "input_audio_buffer.commit" -> {
                                    commits++; val id = "turn-$commits"
                                    webSocket.send(JSONObject().put("type", "input_audio_buffer.committed").put("item_id", id)
                                        .put("previous_item_id", if (commits == 1) JSONObject.NULL else "turn-${commits - 1}").toString())
                                    val final = if (commits == 1) "voice test" else ""
                                    scheduler.schedule({ webSocket.send(JSONObject().put("type", "conversation.item.input_audio_transcription.completed")
                                        .put("item_id", id).put("transcript", final).toString()) }, 1800, TimeUnit.MILLISECONDS)
                                }
                            }
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                    })
                    }
                }
                start()
            }
        }
        @JvmStatic @AfterClass fun finish() { scheduler.shutdownNow(); server.shutdown() }
    }
}
