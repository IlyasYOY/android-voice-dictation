package io.github.ilyasyoy.voicedictation.onboarding

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.ilyasyoy.voicedictation.floating.FloatingDictationService
import io.github.ilyasyoy.voicedictation.floating.FloatingPreferences

internal object SetupPlatform {
    fun grants(context: Context): SetupGrants {
        val accessible = context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                it.resolveInfo.serviceInfo.packageName == context.packageName &&
                    it.resolveInfo.serviceInfo.name == FloatingDictationService::class.java.name
            }
        val runtimeNotification = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(FloatingDictationService.CHANNEL)
        return SetupGrants(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            FloatingPreferences.enabled(context), accessible, runtimeNotification && NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                channel?.importance != NotificationManager.IMPORTANCE_NONE)
    }
    fun intent(context: Context, action: SetupAction): Intent = when (action) {
        SetupAction.KEY_PAGE -> Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.openai.com/api-keys"))
        SetupAction.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        SetupAction.NOTIFICATIONS -> {
            val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(FloatingDictationService.CHANNEL)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE)
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, FloatingDictationService.CHANNEL)
            else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        SetupAction.MICROPHONE, SetupAction.APP_SETTINGS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null))
    }
    /** Called only inside the foreground explicit-paste gate; never coerce URIs or intents. */
    fun plainClipboard(context: Context): String? {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return null
        if (clip.itemCount != 1 || !clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) return null
        val item = clip.getItemAt(0)
        if (item.uri != null || item.intent != null) return null
        return item.text?.toString()
    }
}
