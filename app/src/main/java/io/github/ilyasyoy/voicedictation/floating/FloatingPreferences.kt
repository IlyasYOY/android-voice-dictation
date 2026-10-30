package io.github.ilyasyoy.voicedictation.floating

import android.content.Context
import androidx.core.content.edit

object FloatingPreferences {
    fun enabled(context: Context) = context.getSharedPreferences("floating", Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("floating", Context.MODE_PRIVATE).edit { putBoolean("enabled", enabled) }
        if (!enabled) FloatingDictationService.instance?.disableFromUser()
    }
}
