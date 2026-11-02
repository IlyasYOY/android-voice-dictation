package io.github.ilyasyoy.voicedictation.onboarding

import android.annotation.SuppressLint
import android.content.Context

internal class SetupPreferences(context: Context, namespace: String = "") : SetupStore {
    private val preferences = context.applicationContext.getSharedPreferences("onboarding_v1$namespace", Context.MODE_PRIVATE)
    override fun load() = SetupProgress.decode(runCatching { preferences.getString("progress", null) }.getOrNull())
    @SuppressLint("UseKtx") // The acknowledgement must observe commit failure; KTX edit returns Unit.
    override fun save(progress: SetupProgress) = runCatching {
        preferences.edit().putString("progress", progress.encode()).commit()
    }.getOrDefault(false)
}
