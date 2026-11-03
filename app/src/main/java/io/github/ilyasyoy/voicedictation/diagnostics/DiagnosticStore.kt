package io.github.ilyasyoy.voicedictation.diagnostics

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One process, serialized append/prune/snapshot. Call on IO, never the UI thread. */
internal class DiagnosticStore(private val directory: File, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val MAX_BYTES = 1_048_576L
        const val SEGMENT_BYTES = 65_536L
        const val MAX_AGE_MILLIS = 7 * 24 * 60 * 60 * 1000L
        const val MAX_RECORD_BYTES = 256
        const val MAX_SEGMENTS = 16
        private val filePattern = Regex("events-([0-9]{1,19})-([0-9]+)\\.jsonl")
        private val rowPattern = Regex("""\{"time_ms":([0-9]{1,19}),"area":"([A-Z_]+)","action":"([A-Z_]+)","code":"([A-Z_]+)"(?:,"http_status":([0-9]{3}))?\}""")
    }
    private var active: File? = null
    private var sequence = 0L
    @Synchronized fun append(event: DiagnosticEvent) {
        val now = clock().coerceAtLeast(0)
        pruneAt(now)
        val row = "{\"time_ms\":$now,\"area\":\"${event.area.name}\",\"action\":\"${event.action.name}\",\"code\":\"${event.code.name}\"" +
            (event.httpStatus?.takeIf { it in 100..599 }?.let { ",\"http_status\":$it" } ?: "") + "}\n"
        val bytes = row.toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_RECORD_BYTES)
        var target = active?.takeIf { it.exists() && it.length() + bytes.size <= SEGMENT_BYTES }
        if (target == null) {
            do { target = File(directory, "events-$now-${sequence++}.jsonl") } while (target!!.exists())
            active = target
        }
        if (!target!!.exists()) {
            while (files().size >= MAX_SEGMENTS) remove(files().first { it != target })
        }
        // Make room before writing: the on-disk bound holds even if the write subsequently fails.
        var total = files().sumOf { it.length() }
        for (file in files()) {
            if (total + bytes.size <= MAX_BYTES) break
            if (file != target) { val size = file.length(); remove(file); total -= size }
        }
        target!!.appendBytes(bytes)
    }
    @Synchronized fun prune() = pruneAt(clock().coerceAtLeast(0))
    private fun files(): List<File> = directory.listFiles()?.filter { it.isFile }?.sortedWith(compareBy<File> {
        filePattern.matchEntire(it.name)?.groupValues?.get(1)?.toLongOrNull() ?: Long.MIN_VALUE
    }.thenBy { filePattern.matchEntire(it.name)?.groupValues?.get(2)?.toLongOrNull() ?: Long.MIN_VALUE }) ?: emptyList()
    private fun remove(file: File) {
        if (!file.delete() && file.exists()) throw IOException("Diagnostic cleanup failed")
        if (active == file) active = null
    }
    private fun pruneAt(now: Long) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Diagnostic storage unavailable")
        for (file in files()) {
            val started = filePattern.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
            if (file.canonicalFile.parentFile != directory.canonicalFile || started == null || started > now ||
                now - started >= MAX_AGE_MILLIS || file.length() > SEGMENT_BYTES) remove(file)
        }
        while (files().size > MAX_SEGMENTS) remove(files().first())
        var total = files().sumOf { it.length() }
        for (file in files()) { if (total <= MAX_BYTES) break; val size=file.length(); remove(file); total-=size }
    }
    /** Snapshot is bounded and detached before a slow document provider receives any bytes. */
    @Synchronized fun snapshot(): ByteArray {
        val now = clock().coerceAtLeast(0)
        pruneAt(now)
        val output = java.io.ByteArrayOutputStream()
        for (file in files()) {
            // Revalidate every persisted row; corrupted/partial/unrecognized rows cannot enter an export.
            file.readLines(Charsets.UTF_8).forEach { row ->
                if (validRow(row, now)) { output.write(row.toByteArray(Charsets.UTF_8)); output.write(10) }
            }
        }
        return output.toByteArray()
    }
    private fun validRow(row: String, now: Long): Boolean {
        if (row.toByteArray(Charsets.UTF_8).size >= MAX_RECORD_BYTES) return false
        val fields = rowPattern.matchEntire(row)?.groupValues ?: return false
        val timestamp = fields[1].toLongOrNull() ?: return false
        return timestamp <= now && now - timestamp < MAX_AGE_MILLIS &&
            DiagnosticArea.entries.any { it.name == fields[2] } && DiagnosticAction.entries.any { it.name == fields[3] } &&
            DiagnosticCode.entries.any { it.name == fields[4] } && (fields[5].isEmpty() || fields[5].toInt() in 100..599)
    }
}

internal object DiagnosticArchive {
    /** No temporary ZIP or public cache files. Empty logs still produce a valid, explained archive. */
    fun write(output: OutputStream, events: ByteArray, appVersion: Int, androidApi: Int, dropped: Long, writeFailures: Long) {
        require(events.size <= DiagnosticStore.MAX_BYTES)
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            entry("manifest.json", """{"schema":1,"app_version_code":$appVersion,"android_api":$androidApi,"max_age_days":7,"max_log_bytes":1048576,"dropped_events":${dropped.coerceAtLeast(0)},"write_failures":${writeFailures.coerceAtLeast(0)},"empty":${events.isEmpty()}}""".toByteArray())
            entry("events.jsonl", events)
            entry("README.txt", "Local diagnostics, schema 1. UTC epoch milliseconds. Up to 7 days / 1 MiB; oldest segments rotate first. Fixed event codes only. No audio, dictated text, dictionary, clipboard, keys, server bodies, URLs, editor or third-party app data. No automatic upload. Empty events.jsonl means no retained diagnostic events. Queue drops and storage failures are counted in manifest.json. Saved copies are managed by you.\n".toByteArray())
        }
    }
}
