package io.github.ilyasyoy.voicedictation.onboarding

/** Read is lazy and only user-triggered. Manual typing retains the existing key-format policy. */
internal object KeyPaste {
    fun candidate(currentKey: String, savedKey: Boolean, busy: Boolean, foreground: Boolean,
        readPlainText: () -> String?): String? {
        if (!foreground || busy || savedKey || currentKey.isNotEmpty()) return null
        val raw = readPlainText() ?: return null
        if (raw.length > 4096) return null
        val value = raw.trim()
        return value.takeIf { it.length >= 20 && it.startsWith("sk-") &&
            it.all { c -> c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' } }
    }
}
