package io.github.ilyasyoy.voicedictation.domain

/** Final item text replaces its deltas. Committed predecessor links determine speech order. */
class LiveTranscript {
    private data class Item(var text: String = "", var final: Boolean = false, var previous: String? = null, var linked: Boolean = false)
    private val items = linkedMapOf<String, Item>()
    private val events = linkedSetOf<String>()
    fun committed(id: String, previous: String?) {
        items.getOrPut(id) { Item() }.apply { this.previous = previous; linked = true }
    }
    fun delta(id: String, value: String, eventId: String?) {
        if (eventId != null && !events.add(eventId)) return
        if (events.size > 4096) events.remove(events.first())
        val item = items.getOrPut(id) { Item() }
        if (!item.final) item.text += value
    }
    fun complete(id: String, value: String) { items.getOrPut(id) { Item() }.apply { text = value; final = true } }
    val completedCount get() = items.values.count { it.final }
    val partial get() = items.values.any { !it.final && it.text.isNotBlank() }
    val text: String get() {
        val ordered = linkedSetOf<String>()
        fun visit(id: String, visiting: MutableSet<String>) {
            if (id in ordered || !visiting.add(id)) return
            val item = items[id] ?: return
            item.previous?.let { visit(it, visiting) }
            ordered += id
        }
        items.keys.forEach { visit(it, mutableSetOf()) }
        return ordered.mapNotNull { items[it]?.text?.takeIf(String::isNotBlank) }.joinToString("\n")
    }
}
