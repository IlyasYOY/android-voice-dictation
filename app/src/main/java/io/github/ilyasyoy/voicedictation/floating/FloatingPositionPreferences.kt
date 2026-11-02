package io.github.ilyasyoy.voicedictation.floating

import android.content.Context
import android.annotation.SuppressLint

internal class FloatingPositionPreferences(context: Context) : FloatingPositionStore {
    private val preferences = context.applicationContext.getSharedPreferences("floating", Context.MODE_PRIVATE)
    override fun load(): String? = runCatching { preferences.getString(KEY, null) }.getOrNull()
    // One small synchronous commit at drag release, never on motion or visibility/layout polling.
    @SuppressLint("UseKtx") // KTX edit(commit=true) returns Unit; this port must report commit failure.
    override fun save(value: String) = runCatching { preferences.edit().putString(KEY, value).commit() }.getOrDefault(false)
    companion object { internal const val KEY = "circle_position" }
}
