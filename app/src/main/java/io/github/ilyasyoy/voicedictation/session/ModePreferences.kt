package io.github.ilyasyoy.voicedictation.session

import android.content.Context
import androidx.core.content.edit

/** Only an explicit main-screen choice is stored; absent/unknown legacy values mean Record. */
internal object ModePreferences {
    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("dictation_mode",Context.MODE_PRIVATE)
    fun load(context: Context): Mode = when(preferences(context).getString("explicit_mode",null)) {
        Mode.LIVE.name -> Mode.LIVE
        else -> Mode.RECORD
    }
    fun save(context: Context, mode: Mode) { preferences(context).edit { putString("explicit_mode",mode.name) } }
}
