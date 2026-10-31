package io.github.ilyasyoy.voicedictation.floating

/** UI feedback is retained until explicit discard; a ready transcript alone is never insertion success. */
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
    val available get() = text.isNotBlank() || message != null || needsSetup
    fun offer(text: String, message: String, needsSetup: Boolean = false) {
        this.text=text; this.message=message; this.needsSetup=needsSetup; expanded=false
    }
    fun dismiss() { expanded=false }
    fun discard() { text=""; message=null; needsSetup=false; expanded=false }
    /** Returns true only when insertion was actually confirmed. */
    fun complete(result: InsertionResult, text: String, fallback: String, unconfirmed: String): Boolean {
        return when (result) {
            InsertionResult.INSERTED -> { discard(); true }
            InsertionResult.COPY -> { offer(text,fallback); false }
            InsertionResult.UNCONFIRMED -> { offer(text,unconfirmed); false }
            InsertionResult.CANCELLED -> false
        }
    }
}
