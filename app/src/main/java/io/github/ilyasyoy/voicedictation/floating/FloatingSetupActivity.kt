package io.github.ilyasyoy.voicedictation.floating

import io.github.ilyasyoy.voicedictation.session.*

import io.github.ilyasyoy.voicedictation.ui.design.SoftCurrentPalette as P
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.ilyasyoy.voicedictation.R
import io.github.ilyasyoy.voicedictation.session.DictationSessions
import kotlinx.coroutines.*

class FloatingSetupActivity : ComponentActivity() {
    companion object { internal const val SHOW_RECORDINGS="show_recordings" }
    private var closing=false
    private val retryScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retryScope.launch { RecordingRecovery.processOwned.state.collect { if(!closing)render() } }
        render()
    }
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val ink = if (dark) P.NightText else P.Plum
        val paper = if (dark) P.Night else P.Paper
        fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
        val enabled = FloatingPreferences.enabled(this)
        val connected = getSystemService(AccessibilityManager::class.java).getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName && it.resolveInfo.serviceInfo.name == FloatingDictationService::class.java.name }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(32)); setBackgroundColor(paper) }
        layout.addView(TextView(this).apply { text = getString(R.string.floating_service_name); textSize = 26f; setTextColor(ink); setPadding(0,0,0,dp(20)) })
        layout.addView(TextView(this).apply { text = getString(R.string.floating_setup_summary); textSize = 16f; setTextColor(ink); setPadding(0,dp(12),0,dp(12)) })
        layout.addView(TextView(this).apply { text = getString(if (enabled && connected) R.string.floating_enabled else R.string.floating_disabled) })
        fun button(label: Int, id: Int = 0, enabled: Boolean = true, onClick: () -> Unit) { layout.addView(Button(this).apply {
            isEnabled=enabled
            text = getString(label); if (id != 0) this.id = id; isAllCaps = false; setTextColor(ink); minHeight = dp(52)
            background = GradientDrawable().apply { setColor(if(dark)P.NightSurface else 0xFFE7DDF2.toInt()); cornerRadius=dp(24).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) }
            setOnClickListener { onClick() }
        }) }
        button(if (enabled) R.string.floating_settings else R.string.floating_enable, R.id.floating_enable) {
            if (enabled) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            else AlertDialog.Builder(this).setTitle(R.string.floating_service_name).setMessage(R.string.floating_disclosure)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.floating_agree) { _, _ ->
                    FloatingPreferences.setEnabled(this, true); startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }.show()
        }
        if (enabled || connected) button(R.string.floating_disable, R.id.floating_disable) { FloatingPreferences.setEnabled(this, false); render() }
        button(R.string.floating_grant_mic, R.id.floating_mic) { permission.launch(Manifest.permission.RECORD_AUDIO) }
        button(R.string.floating_grant_notification, R.id.floating_notifications) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) layout.addView(TextView(this).apply { text = getString(R.string.floating_need_notification) })
        layout.addView(TextView(this).apply { text=getString(R.string.current_bubble_help); textSize=14f; setTextColor(ink); setPadding(0,dp(16),0,dp(16)) })
        val recovery = FloatingFeedback.processOwned
        val recordings=RecordingRecovery.processOwned
        var recoveryAnchor: android.view.View?=null
        if(recordings.count>0) {
            layout.addView(TextView(this).apply { text=getString(R.string.current_saved_recordings,recordings.count); textSize=17f; setTextColor(ink); recoveryAnchor=this })
            recordings.state.value.failure?.let { failure ->
                layout.addView(TextView(this).apply {
                    id=R.id.floating_audio_problem
                    text=getString(when(failure) {
                        RecordingRetryFailure.KEY,RecordingRetryFailure.AUTH -> R.string.current_retry_key_error
                        RecordingRetryFailure.NETWORK -> R.string.current_retry_network_error
                        RecordingRetryFailure.SERVICE -> R.string.current_retry_service_error
                    })
                    textSize=16f;setTextColor(ink);accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
                })
            }
            button(R.string.current_retry_recording,R.id.floating_retry_audio,!recordings.retrying) {
                retryScope.launch {
                    try {
                        val text=recordings.retry { file -> DebugFloatingConfiguration.transcribeRetained(this@FloatingSetupActivity,file)
                            ?: DictationSessions.transcribeRetained(this@FloatingSetupActivity,file) }
                        if(text!=null) { recovery.retain(text); recovery.resetSignal() }
                    } catch(e: CancellationException) { throw e }
                    catch(_: Exception) { recovery.offer("",getString(R.string.floating_stream_error),error=true) }
                    finally { if(!closing && !isDestroyed)render() }
                }
                render()
            }
            button(R.string.current_discard_recordings,R.id.floating_discard_audio,!recordings.retrying) { recordings.discard(); render() }
        }
        if (recovery.text.isNotBlank()) {
            layout.addView(TextView(this).apply { text=getString(R.string.current_recover); textSize=18f; setTextColor(ink); setPadding(0,dp(16),0,dp(8)) })
            layout.addView(TextView(this).apply { id=R.id.floating_preview; text=recovery.text; textSize=17f; setTextColor(ink); setTextIsSelectable(true) })
            button(R.string.copy, R.id.floating_copy) {
                val clip=android.content.ClipData.newPlainText(getString(R.string.transcript),recovery.text)
                if (Build.VERSION.SDK_INT>=33) clip.description.extras=android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE",true) }
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
            }
            button(R.string.current_discard, R.id.floating_discard) { recovery.discard(); render() }
        }
        button(R.string.floating_back) { finish() }
        layout.addView(TextView(this).apply { text = getString(R.string.floating_disclosure); textSize = 16f; setTextColor(ink); setPadding(0,dp(12),0,dp(12)) })
        for (i in 0 until layout.childCount) (layout.getChildAt(i) as? TextView)?.setTextColor(ink)
        val scroll=ScrollView(this).apply { setBackgroundColor(paper); addView(layout) }
        setContentView(scroll)
        if(intent.getBooleanExtra(SHOW_RECORDINGS,false)) recoveryAnchor?.let { anchor -> scroll.post {scroll.scrollTo(0,anchor.top)} }
    }
    override fun onDestroy() { closing=true; retryScope.cancel(); super.onDestroy() }
}
