package io.github.ilyasyoy.voicedictation.session

import android.content.Context
import androidx.core.content.edit

/** The old preference cannot select an execution path. Cleanup failure never blocks offline Record. */
internal object LegacyModeMigration {
    fun migrate(context: Context, namespace: String = "") {
        runCatching {
            context.applicationContext.getSharedPreferences("dictation_mode$namespace", Context.MODE_PRIVATE)
                .edit { remove("explicit_mode") }
        }
    }
}
