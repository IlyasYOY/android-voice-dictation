package io.github.ilyasyoy.voicedictation.floating

import java.util.Locale

/** Metadata only: never stores field text, screen contents, credentials or an AccessibilityNodeInfo. */
data class EditorToken(
    val packageName: String, val windowId: Int, val viewId: String, val session: Long,
    val selectionStart: Int, val selectionEnd: Int, val password: Boolean, val sensitive: Boolean,
    val inputType: Int, val imeOptions: Int, val composingStart: Int, val composingEnd: Int,
) {
    fun sameEditor(other: EditorToken) = packageName == other.packageName && windowId == other.windowId &&
        viewId == other.viewId && session == other.session
}
object EditorPolicy {
    private val sensitiveNames = listOf("password", "passcode", "otp", "cvv", "cvc", "credit", "card", "iban", "bank", "security", "token", "auth", "ssn")
    fun allowed(e: EditorToken): Boolean {
        val pkg = e.packageName.lowercase(Locale.ROOT)
        val id = e.viewId.lowercase(Locale.ROOT)
        val variation = e.inputType and 0xff0
        return !e.password && !e.sensitive && e.inputType and 15 == 1 && variation in setOf(0, 0x40, 0x50) &&
            e.imeOptions and 0x1000000 == 0 && sensitiveNames.none { id.contains(it) } &&
            !Regex("(^|[._:/-])pin([._:/-]|$)").containsMatchIn(id) &&
            listOf(".bank", ".pay", "wallet", "authenticator", "password", ".vault").none { pkg.contains(it) } &&
            pkg !in setOf("android", "com.android.settings", "com.android.systemui")
    }
    fun canInsert(original: EditorToken, current: EditorToken, sdk: Int, text: String): Boolean =
        sdk >= 33 && text.isNotBlank() && text.length <= 10_000 && original.sameEditor(current) &&
            allowed(original) && allowed(current) && original.selectionStart >= 0 &&
            original.selectionStart == original.selectionEnd && current.selectionStart == original.selectionStart &&
            current.selectionEnd == original.selectionEnd && original.composingStart < 0 && current.composingStart < 0 &&
            (text.none { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' } || current.inputType and 0x20000 != 0)
    fun confirmed(start: Int, text: String, cursorStart: Int, cursorEnd: Int, suffix: CharSequence): Boolean =
        text.isNotBlank() && cursorStart == cursorEnd && cursorStart == start + text.length &&
            suffix.endsWith(text.takeLast(256))
}
