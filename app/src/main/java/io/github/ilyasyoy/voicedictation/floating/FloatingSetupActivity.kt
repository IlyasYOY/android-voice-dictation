package io.github.ilyasyoy.voicedictation.floating

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

class FloatingSetupActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val enabled = FloatingPreferences.enabled(this)
        val connected = getSystemService(AccessibilityManager::class.java).getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName && it.resolveInfo.serviceInfo.name == FloatingDictationService::class.java.name }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 32) }
        layout.addView(TextView(this).apply { text = getString(R.string.floating_service_name); textSize = 24f })
        layout.addView(TextView(this).apply { text = getString(R.string.floating_setup_summary); textSize = 16f })
        layout.addView(TextView(this).apply { text = getString(if (enabled && connected) R.string.floating_enabled else R.string.floating_disabled) })
        fun button(label: Int, id: Int = 0, onClick: () -> Unit) { layout.addView(Button(this).apply {
            text = getString(label); if (id != 0) this.id = id; setOnClickListener { onClick() }
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
        button(R.string.floating_back) { finish() }
        layout.addView(TextView(this).apply { text = getString(R.string.floating_disclosure); textSize = 16f })
        setContentView(ScrollView(this).apply { addView(layout) })
    }
}
