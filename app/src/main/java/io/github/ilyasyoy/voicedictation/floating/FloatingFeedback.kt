package io.github.ilyasyoy.voicedictation.floating

/** Unsent text is optional recovery, never a gate on the next recording. */
internal class FloatingFeedback {
    companion object {
        // One pending recovery in this process, containing no Context, editor token or audio resource.
        // Rebinding the service must not erase an uninserted result; process death still clears it.
        val processOwned = FloatingFeedback()
    }
    var text = ""
    var message: String? = null
    var needsSetup = false
    var expanded = false
    var error = false
    val available get() = text.isNotBlank() || message != null || needsSetup
    fun offer(text: String, message: String, needsSetup: Boolean = false, error: Boolean = false) {
        if (text.isNotBlank() && text != this.text) this.text = listOf(this.text,text).filter(String::isNotBlank).joinToString("\n\n")
        this.message=message; this.needsSetup=needsSetup; this.error=error; expanded=false
    }
    fun dismiss() { expanded=false }
    fun resetSignal() { message=null; needsSetup=false; error=false; expanded=false }
    fun discard() { text=""; resetSignal() }
    /** Confirmed and unverifiable writes both settle silently; neither is ever retried. */
    @Suppress("UNUSED_PARAMETER")
    fun complete(result: InsertionResult, text: String, fallback: String, unconfirmed: String): Boolean {
        return when (result) {
            InsertionResult.INSERTED, InsertionResult.UNCONFIRMED -> { resetSignal(); true }
            InsertionResult.COPY -> { offer(text,fallback); false }
            InsertionResult.CANCELLED -> false
        }
    }
}
