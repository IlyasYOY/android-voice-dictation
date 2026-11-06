package io.github.ilyasyoy.voicedictation.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri

/** Public website navigation only: no credential, account identifier, headers or query parameters. */
internal object OpenAiBilling {
    private const val URL = "https://platform.openai.com/settings/organization/billing/overview"

    fun open(startActivity: (Intent) -> Unit): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, URL.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
