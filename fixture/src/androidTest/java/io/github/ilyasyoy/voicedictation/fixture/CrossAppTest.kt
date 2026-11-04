package io.github.ilyasyoy.voicedictation.fixture

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.regex.Pattern
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
    private fun launch(component: String, port: Int? = null, deferSetup: Boolean = true) {
        val intent = Intent().setComponent(ComponentName(if (component.startsWith("$FIXTURE.")) FIXTURE else APP, component))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (port != null) intent.putExtra("port", port)
        if (component == "$FIXTURE.FixtureActivity") intent.putExtra("field", "body")
        instrumentation.targetContext.startActivity(intent)
        if (component == "$APP.MainActivity" && deferSetup) {
            val deadline = SystemClock.elapsedRealtime() + 40_000
            while (!device.hasObject(By.pkg(APP).desc("Settings")) && SystemClock.elapsedRealtime() < deadline) {
                val notNow = device.findObject(By.pkg(APP).text("Not now"))
                if (notNow != null) { notNow.click(); checkpoint("Explicitly deferred first-run setup for this existing-feature scenario"); break }
                SystemClock.sleep(100)
            }
        }
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
    }
    private fun specialAccessEnabled() = instrumentation.targetContext.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == APP }
    private fun openFloatingSetupFromMain() {
        launch("$APP.MainActivity")
        find(By.pkg(APP).desc("Settings"), 60_000).click()
        scrollFind(By.pkg(APP).text("Set up floating dictation")).click()
        find(appId("floating_setup_title"), 40_000)
    }
    private fun setupUserOptIn() {
        val pm = instrumentation.targetContext.packageManager
        if (specialAccessEnabled() && pm.checkPermission(android.Manifest.permission.RECORD_AUDIO, APP) == 0 &&
            (Build.VERSION.SDK_INT < 33 || pm.checkPermission(android.Manifest.permission.POST_NOTIFICATIONS, APP) == 0)) {
            checkpoint("Existing explicitly granted OS access retained; no permission changes needed")
            return
        }
        checkpoint("Opening explicit special-access onboarding")
        openFloatingSetupFromMain()
        screenshot("00-floating-setup")
        if (device.hasObject(appId("floating_setup_status").text("Off"))) {
            scrollFind(appId("floating_enable")).click()
            scrollFind(appId("floating_consent")).click()
        }
        if (instrumentation.targetContext.packageManager.checkPermission(android.Manifest.permission.RECORD_AUDIO, APP) != 0) {
            checkpoint("Requesting microphone through OS UI")
            scrollFind(appId("floating_mic")).click()
            val allow = if (Build.VERSION.SDK_INT < 29) find(By.res("com.android.packageinstaller", "permission_allow_button"), 40_000)
                else device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_foreground_only_button")), 40_000)
                    ?: find(By.res("com.android.permissioncontroller", "permission_allow_button"), 10_000)
            allow.click()
            device.wait(Until.gone(By.res("com.android.permissioncontroller", "permission_allow_foreground_only_button")), 40_000)
        }
        if (specialAccessEnabled()) {
            grantFloatingNotificationsIfNeeded()
            return
        }
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
        grantFloatingNotificationsIfNeeded()
    }
    private fun grantFloatingNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && instrumentation.targetContext.packageManager.checkPermission(android.Manifest.permission.POST_NOTIFICATIONS, APP) != 0) {
            openFloatingSetupFromMain()
            scrollFind(appId("floating_notifications")).click()
            find(By.res("com.android.permissioncontroller", "permission_allow_button"), 40_000).click()
            device.wait(Until.gone(By.res("com.android.permissioncontroller", "permission_allow_button")), 40_000)
        }
    }
    @Before fun prepare() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        val passiveRecovery=testName.methodName in setOf("vDurablePhase2UpgradeRestoresLegacyAudioWithoutAnAutomaticUpload",
            "wDurablePhase3RebootManualRetryKeepsSuccessThenExplicitDeleteSurvivesRestart",
            "xDurableMainSuccessSurvivesProcessDeathAndExplicitDelete")
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
    private fun openRecovery() {
        assertFalse("No automatic error/result panel",device.hasObject(appId("floating_status")))
        find(appId("floating_start"),60_000).longClick()
        // Recovery intentionally opens scrolled to retained audio/text, below the permission controls.
        find(historyPlay(),30_000)
    }
    private fun startRecording() {
        val requests=fileRequests.get()
        find(appId("floating_start"),30_000).click()
        find(appId("floating_stop"),30_000)
        SystemClock.sleep(1000)
        assertEquals("No upload before Stop",requests,fileRequests.get())
    }
    private fun cancelFloating() {
        find(appId("floating_stop")).longClick()
        assertTrue("Opening the non-focusable controls must not stop recording", has(appId("floating_stop"), 5000))
        find(label("Cancel")).click()
    }
    private fun assertFieldUnchanged() = assertEquals("Before  after", find(fixtureId("body")).text)
    /** Real accessibility overlay above the real fixture IME; localhost failure only. */
    @Test fun vividCurrentStatesAboveKeyboardInBothThemes() {
        fun evidence(name: String) {
            SystemClock.sleep(700)
            screenshot(name)
            val path=java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "crossapp-evidence/$name.png")
            device.executeShellCommand("mkdir -p /sdcard/Download/vivid-current")
            device.executeShellCommand("cp ${path.absolutePath} /sdcard/Download/vivid-current/$name.png")
        }
        try {
            for (theme in listOf("no", "yes")) {
                device.executeShellCommand("cmd uimode night $theme")
                focusField("body"); find(fixtureId("body")).click(); focusField("body")
                find(appId("floating_start"),40_000)
                evidence("overlay-idle-$theme")
                startRecording()
                evidence("overlay-recording-$theme") // real MIC, emulator silence
                val release=java.util.concurrent.CountDownLatch(1)
                heldResponse.set(release); failFileNext.set(true)
                try {
                    val before=fileRequests.get()
                    find(appId("floating_stop")).click()
                    val deadline=SystemClock.elapsedRealtime()+20_000
                    while(fileRequests.get()==before && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50)
                    assertEquals(before+1,fileRequests.get())
                    evidence("overlay-processing-$theme")
                } finally { release.countDown(); heldResponse.set(null) }
                val error=By.pkg(APP).desc("Review text or recording problem")
                find(error,40_000)
                evidence("overlay-error-$theme")
                assertFieldUnchanged()
                device.executeShellCommand("settings put global animator_duration_scale 0")
                evidence("overlay-error-reduced-$theme")
                device.executeShellCommand("settings put global animator_duration_scale 1")
                find(error).click(); scrollFind(appId("floating_discard")).click()
                find(appId("floating_start"),30_000)
            }
        } finally {
            heldResponse.getAndSet(null)?.countDown()
            device.executeShellCommand("settings put global animator_duration_scale 1")
            device.executeShellCommand("cmd uimode night no")
        }
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
        val count = unexpectedRequests.get()
        device.swipe(bounds.centerX(), bounds.centerY(), 45, 90, 25)
        SystemClock.sleep(700); assertEquals(count, unexpectedRequests.get())
        val moved = find(appId("floating_start")).visibleBounds
        assertTrue(moved.centerX() != bounds.centerX() || moved.centerY() != bounds.centerY())
        screenshot("10-moved-bubble-no-recording")
        startRecording(); cancelFloating(); assertFieldUnchanged()
    }
    @Test fun qDefaultRecordUploadsOnlyAfterStopAndNextTapStartsNormally() {
        launch("$APP.floating.DebugFloatingSetupActivity",server.port)
        focusField("body")
        // The returning Activity may receive onNewIntent before its window regains input focus.
        // A real tap establishes the IME connection; then restore the fixture's insertion caret.
        find(fixtureId("body")).click(); focusField("body")
        val streams=unexpectedRequests.get(); val uploaded=fileRequests.get()
        failFileNext.set(true)
        repeat(2) { turn ->
            find(appId("floating_start"),30_000).click()
            find(appId("floating_stop"),30_000)
            SystemClock.sleep(1800)
            assertEquals("Record must not start a non-transcription request",streams,unexpectedRequests.get())
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
        scrollFind(historyUploadAction()).click()
        // Setup rebuilds its view while retry starts; a transiently absent button is not completion.
        val retryDeadline=SystemClock.elapsedRealtime()+30_000
        while(fileRequests.get()<uploaded+3 && SystemClock.elapsedRealtime()<retryDeadline)SystemClock.sleep(100)
        assertEquals(uploaded+3,fileRequests.get())
        val completed=By.pkg(APP).desc("Show transcript")
        scrollFind(completed).click()
        assertTrue(scrollFind(savedTranscript()).text.contains("voice test"))
        screenshot("25-explicit-saved-audio-retry-no-editor-replay")
        assertFalse(device.executeShellCommand("dumpsys activity services $APP").contains("isForeground=true"))
        checkpoint("Default floating Record used real MediaRecorder; two explicit Stop uploads plus one explicit saved-audio retry, zero non-transcription requests, no intercepting recovery popup")
    }
    @Test fun sHistoryCapacityBlocksNewMicrophoneAndResolvesWithoutEditingTheTarget() {
        launch("$APP.floating.DebugFloatingSetupActivity", server.port)
        launch("$APP.MainActivity")
        val entrance = find(By.pkg(APP).descContains("Open recording history"), 60_000)
        val existing = checkNotNull(Regex("\\((\\d+)\\)").find(entrance.contentDescription)).groupValues[1].toInt()
        find(By.pkg(APP).desc("Settings")).click()
        val field = find(By.pkg(APP).clazz("android.widget.EditText"))
        val originalLimit = field.text.toInt()
        fun saveVisibleLimit(value: Int) {
            find(By.pkg(APP).clazz("android.widget.EditText")).apply { click(); text = value.toString() }
            scrollFind(By.pkg(APP).text("Save limit")).click()
        }
        try {
            saveVisibleLimit(existing + 1)
            focusField("body"); find(fixtureId("body")).click(); focusField("body")
            val before = fileRequests.get()
            find(appId("floating_start"), 40_000).click()
            find(appId("floating_stop"), 40_000); SystemClock.sleep(1500)
            find(appId("floating_stop")).click()
            val deadline = SystemClock.elapsedRealtime() + 45_000
            while (fileRequests.get() < before + 1 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertEquals(before + 1, fileRequests.get())
            find(By.pkg(APP).desc("History full. Manage history"), 40_000)
            assertFalse(device.hasObject(appId("floating_stop")))
            assertFieldUnchanged(); screenshot("vd009-01-full-floating-affordance")
            find(By.pkg(APP).desc("History full. Manage history")).click()
            find(By.pkg(APP).text("History full"), 40_000)
            assertFalse(device.executeShellCommand("dumpsys activity services $APP").contains("isForeground=true"))
            SystemClock.sleep(1000); assertEquals(before + 1, fileRequests.get()); assertEquals(0, unexpectedRequests.get())
            screenshot("vd009-02-deliberate-shared-history")
            find(By.pkg(APP).text("Limit")).click()
            saveVisibleLimit(existing + 2)
            device.pressBack(); focusField("body"); assertFieldUnchanged()
            find(appId("floating_start"), 40_000).click()
            find(appId("floating_stop"), 40_000)
            cancelFloating(); find(appId("floating_start"), 40_000)
            assertFieldUnchanged(); assertEquals(before + 1, fileRequests.get()); assertEquals(0, unexpectedRequests.get())
            screenshot("vd009-03-returned-editor-no-stale-insertion")
            checkpoint("Capacity: one admitted actual recording reached limit; full tap opened history without a second mic/HTTP; explicit increase allowed a fresh capture; Cancel kept target text and prior result")
        } finally {
            launch("$APP.MainActivity"); find(By.pkg(APP).desc("Settings"), 60_000).click()
            val current = find(By.pkg(APP).clazz("android.widget.EditText")).text.toInt()
            if (current != originalLimit) saveVisibleLimit(originalLimit)
        }
    }
    @Test fun tFloatingCircleRemembersDragAcrossKeyboardHomeRotationAndProcessRestart() {
        val filesBefore = fileRequests.get()
        val streamsBefore = unexpectedRequests.get()
        fun circle() = find(appId("floating_start"), 40_000)
        fun reopenEditor() { focusField("body"); find(fixtureId("body"), 40_000).click(); circle() }
        fun near(expected: android.graphics.Rect) {
            val tolerance = (6 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var actual = circle().visibleBounds
            while ((kotlin.math.abs(expected.centerX()-actual.centerX()) > tolerance ||
                    kotlin.math.abs(expected.centerY()-actual.centerY()) > tolerance) && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100); actual = circle().visibleBounds
            }
            assertTrue("Remembered x: $expected / $actual", kotlin.math.abs(expected.centerX()-actual.centerX()) <= tolerance)
            assertTrue("Remembered y: $expected / $actual", kotlin.math.abs(expected.centerY()-actual.centerY()) <= tolerance)
        }
        fun evidence(name: String) {
            screenshot(name)
            // Preserve this scenario's evidence before Gradle removes the fixture package.
            val source = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "crossapp-evidence/$name.png")
            device.executeShellCommand("mkdir -p /sdcard/Download/vd007")
            device.executeShellCommand("cp ${source.absolutePath} /sdcard/Download/vd007/$name.png")
        }
        val initial = circle().visibleBounds
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val x = (device.displayWidth * .28).toInt()
        val y = (initial.centerY() - 96 * density).toInt().coerceAtLeast((72 * density).toInt())
        assertTrue(device.drag(initial.centerX(), initial.centerY(), x, y, 30))
        SystemClock.sleep(500)
        val chosen = android.graphics.Rect(circle().visibleBounds)
        assertTrue(kotlin.math.abs(chosen.centerX()-initial.centerX()) > 32 * density)
        assertTrue(kotlin.math.abs(chosen.centerY()-initial.centerY()) > 32 * density)
        assertFieldUnchanged(); evidence("01-dragged-circle")
        device.pressBack()
        assertTrue(device.wait(Until.gone(appId("floating_start")), 15_000))
        reopenEditor(); near(chosen); evidence("02-keyboard-reopened")
        device.pressHome(); reopenEditor(); near(chosen)
        try {
            device.setOrientationLeft()
            val rotationDeadline = SystemClock.elapsedRealtime() + 15_000
            while (device.displayWidth < device.displayHeight && SystemClock.elapsedRealtime() < rotationDeadline) SystemClock.sleep(100)
            assertTrue(device.displayWidth > device.displayHeight)
            reopenEditor()
            var landscape = circle().visibleBounds
            val placementDeadline = SystemClock.elapsedRealtime() + 15_000
            while ((landscape.left < 0 || landscape.top < 0 || landscape.right > device.displayWidth ||
                    landscape.bottom > device.displayHeight) && SystemClock.elapsedRealtime() < placementDeadline) {
                SystemClock.sleep(100); landscape = circle().visibleBounds
            }
            assertTrue(landscape.left >= 0 && landscape.top >= 0)
            assertTrue(landscape.right <= device.displayWidth && landscape.bottom <= device.displayHeight)
            evidence("03-landscape-clamped")
        } finally { device.setOrientationNatural(); device.unfreezeRotation() }
        reopenEditor(); near(chosen); evidence("04-portrait-restored")
        openFloatingSetupFromMain()
        scrollFind(appId("floating_disable")).click()
        val disabledDeadline = SystemClock.elapsedRealtime() + 20_000
        while (specialAccessEnabled() && SystemClock.elapsedRealtime() < disabledDeadline) SystemClock.sleep(100)
        assertFalse(specialAccessEnabled())
        device.executeShellCommand("am force-stop $APP")
        setupUserOptIn() // Re-enable via explicit disclosure and Android UI, never a shell grant.
        reopenEditor(); near(chosen); assertFieldUnchanged()
        assertEquals(filesBefore, fileRequests.get()); assertEquals(streamsBefore, unexpectedRequests.get())
        assertFalse(device.executeShellCommand("dumpsys activity services $APP").contains("isForeground=true"))
        evidence("05-new-process-restored")
        checkpoint("VD007: real circle drag survived keyboard reopen, Home, landscape/portrait and explicit disable/process restart/re-enable; unchanged editor and zero microphone HTTP/WS")
    }
    private fun durableStateFile()=java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"vd004-upgrade.properties")
    private fun digest(audio:ByteArray)=MessageDigest.getInstance("SHA-256").digest(audio).joinToString("") {"%02x".format(it)}
    private fun historyPlay() = By.pkg(APP).descContains("Play, ")
    private fun historyUploadAction() = By.pkg(APP).desc(Pattern.compile("Transcribe|Retry transcription"))
    private fun savedTranscript() = By.pkg(APP).text(Pattern.compile(".*(?:voice test|Recorded offline).*"))
    private fun openSavedFromMain() {
        launch("$APP.MainActivity")
        find(By.pkg(APP).descContains("Open recording history"), 60_000).click()
        // The sheet has no visible title; wait for an actual audio control.
        find(historyPlay(), 30_000)
    }
    private fun expandSavedTranscript() {
        scrollFind(By.pkg(APP).desc("Show transcript")).click()
        scrollFind(savedTranscript())
    }
    private fun deleteSavedMessage() {
        scrollFind(By.pkg(APP).descContains("Message options")).click()
        find(By.pkg(APP).text("Delete")).click()
        find(By.pkg(APP).text("Delete recording")).click()
    }
    @Test fun uDurablePhase1CreateFailedRecordingInAcceptedVersionBeforeUpgrade() {
        launch("$APP.floating.DebugFloatingSetupActivity",server.port)
        focusField("body")
        find(fixtureId("body")).click();focusField("body")
        failFileNext.set(true)
        find(appId("floating_start")).click();find(appId("floating_stop"),40_000)
        SystemClock.sleep(2200);assertEquals(0,fileRequests.get());assertEquals(0,unexpectedRequests.get())
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
        find(historyUploadAction(),30_000)
        assertTrue(has(historyPlay(),10_000))
        SystemClock.sleep(1500);assertEquals(0,fileRequests.get());assertEquals(0,unexpectedRequests.get())
        screenshot("41-after-upgrade-pending-before-reboot")
        checkpoint("Upgrade phase2: candidate restored legacy cache into durable storage; manual Retry visible; zero startup HTTP/WS; host must reboot before phase3")
    }
    @Test fun wDurablePhase3RebootManualRetryKeepsSuccessThenExplicitDeleteSurvivesRestart() {
        val expected=Properties().apply {durableStateFile().inputStream().use {load(it)}}
        openSavedFromMain();find(historyUploadAction(),30_000)
        SystemClock.sleep(1200);assertEquals(0,fileRequests.get())
        find(historyUploadAction()).click()
        val end=SystemClock.elapsedRealtime()+40_000
        while(fileRequests.get()<1 && SystemClock.elapsedRealtime()<end)SystemClock.sleep(100)
        assertEquals(1,fileRequests.get());assertEquals(expected.getProperty("audio_sha256"),digest(lastFileAudio.get()))
        assertEquals(expected.getProperty("audio_bytes").toInt(),lastFileAudio.get().size)
        expandSavedTranscript()
        assertTrue(scrollFind(savedTranscript()).text.contains("voice test"))
        assertTrue(has(historyPlay(),10_000))
        device.executeShellCommand("am force-stop $APP")
        openSavedFromMain()
        expandSavedTranscript()
        assertTrue(scrollFind(savedTranscript()).text.contains("voice test"))
        assertTrue(has(historyPlay(),10_000))
        assertFalse(device.hasObject(historyUploadAction()));assertEquals(1,fileRequests.get())
        screenshot("42-success-audio-and-text-after-process-death")
        deleteSavedMessage()
        find(By.text("No saved recordings yet."),30_000)
        screenshot("43-explicit-delete-complete")
        device.executeShellCommand("am force-stop $APP");launch("$APP.MainActivity")
        // Wait for the cold main window, then require its actual persisted empty count.
        find(By.pkg(APP).descContains("Saved recordings (0)"), 60_000)
        assertEquals(1,fileRequests.get())
        checkpoint("Upgrade + real reboot + manual retry passed: every audio byte matched; successful audio/text survived process death; explicit delete survived restart; exactly one explicit post-reboot HTTP and zero WS")
    }
    /** Separate fresh-guest proof, after OfflineRecordRuntimeTest; does not claim legacy/reboot continuity. */
    @Test fun xDurableMainSuccessSurvivesProcessDeathAndExplicitDelete() {
        val expected="Recorded offline. Uploaded by explicit retry."
        openSavedFromMain()
        expandSavedTranscript()
        assertTrue(scrollFind(savedTranscript()).text.contains(expected))
        assertTrue(has(historyPlay(),10_000))
        device.executeShellCommand("am force-stop $APP")
        openSavedFromMain()
        expandSavedTranscript()
        assertTrue(scrollFind(savedTranscript()).text.contains(expected))
        assertTrue(has(historyPlay(),10_000))
        assertFalse(device.hasObject(historyUploadAction()))
        assertEquals(0,fileRequests.get());assertEquals(0,unexpectedRequests.get())
        screenshot("44-main-success-audio-text-after-process-death")
        deleteSavedMessage()
        find(By.text("No saved recordings yet."),30_000)
        screenshot("45-main-explicit-delete-complete")
        device.executeShellCommand("am force-stop $APP");launch("$APP.MainActivity")
        // Wait for the cold main window, then require its actual persisted empty count.
        find(By.pkg(APP).descContains("Saved recordings (0)"), 60_000)
        assertEquals(0,fileRequests.get());assertEquals(0,unexpectedRequests.get())
        checkpoint("Fresh guest: main offline/manual-retry success audio and text survived process death; explicit delete survived restart; zero recovery HTTP/WS; separate from interrupted legacy reboot UI run")
    }
    @Test fun zDisableRevokesAccessAndCleansTestConfiguration() {
        openFloatingSetupFromMain()
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
        private val unexpectedRequests = AtomicInteger()
        private val fileRequests = AtomicInteger()
        private val failFileNext=AtomicBoolean()
        private val heldResponse=AtomicReference<java.util.concurrent.CountDownLatch?>()
        private val fileBytes = AtomicInteger()
        private val lastFileAudio=AtomicReference(ByteArray(0))
        private var configured = false
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
                            heldResponse.get()?.let { check(it.await(30,TimeUnit.SECONDS)) }
                            if(failFileNext.compareAndSet(true,false))return MockResponse().setResponseCode(503)
                            return MockResponse().setBody("{\"text\":\"voice test\"}")
                        }
                        unexpectedRequests.incrementAndGet()
                        return MockResponse().setResponseCode(404)
                    }
                }
                start()
            }
        }
        @JvmStatic @AfterClass fun finish() { server.shutdown() }
    }
}
