package io.github.ilyasyoy.voicedictation.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

internal enum class SavedRecordingStatus { CAPTURING, PENDING, UPLOADING, FAILED, INTERRUPTED, COMPLETE, DELETING, UNAVAILABLE }
internal enum class SavedRecordingFailure { KEY, AUTH, NETWORK, SERVICE, STORAGE }
internal enum class RecordingTextSlot { MAIN, FLOATING }
/** LEGACY_TEXT is read-only compatibility for already saved text; new capture creates RECORD only. */
internal enum class SavedRecordingKind(val storedValue: String) { RECORD("RECORD"), LEGACY_TEXT("LIVE");
    companion object { fun fromStored(value: String) = entries.first { it.storedValue == value } }
}
internal data class SavedRecordingDraft(val text:String,val sourceId:String?) {
    override fun toString()="SavedRecordingDraft(textLength=${text.length}, sourceId=$sourceId)"
}
internal interface RecordingDeletion {
    fun delete(file:File) {Files.deleteIfExists(file.toPath())}
    fun sync(directory:File) {AtomicRecordingCommit.syncDirectory(directory)}
}
internal object DurableRecordingDeletion:RecordingDeletion
internal data class SavedRecording(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: SavedRecordingStatus,
    val durationSeconds: Int = 0,
    val transcript: String = "",
    val failure: SavedRecordingFailure? = null,
    val attempt: Long = 0,
    val legacyName: String? = null,
    val kind: SavedRecordingKind = SavedRecordingKind.RECORD,
    val partial: Boolean = false,
) {
    override fun toString()="SavedRecording(id=$id, status=$status, transcriptLength=${transcript.length}, kind=$kind)"
}
internal data class RecordingUpload(val id: String, val attempt: Long)

/** File commit boundary, injectable for interruption/failure tests. Never truncates the last commit. */
internal fun interface RecordingCommit {
    fun write(target: File, values: Properties)
}

internal object AtomicRecordingCommit : RecordingCommit {
    override fun write(target: File, values: Properties) {
        val staging = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(staging).use { stream -> values.store(stream, null); stream.fd.sync() }
        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(checkNotNull(target.parentFile))
    }
    fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}

/**
 * Private, single-process recording ledger. Each metadata replacement is atomic and synced.
 * A restarted process only restores data: it never owns a microphone, credential, editor or upload.
 * A submission token prevents a cancelled/late request from committing over a newer attempt.
 */
internal class RecordingRepository(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val commit: RecordingCommit = AtomicRecordingCommit,
    private val deletion:RecordingDeletion=DurableRecordingDeletion,
) {
    private val rows = linkedMapOf<String, SavedRecording>()
    private val reserved = mutableSetOf<String>()
    private var countLimit = HistoryCapacity.DEFAULT_LIMIT
    private var limitReadable = true
    private val uploading = mutableSetOf<RecordingUpload>()
    private val received=mutableMapOf<String,String>() // Copy remains possible if disk commit fails.
    private val observers = CopyOnWriteArrayList<() -> Unit>()
    var migrationPending=false
        private set

    init {
        val created=root.mkdirs()
        check(created || root.isDirectory) { "Recording directory unavailable" }
        if(created)root.parentFile?.let(AtomicRecordingCommit::syncDirectory)
        readHistoryLimit()
        root.listFiles()?.filter { it.isDirectory && validId(it.name) }?.forEach { directory ->
            // A crash after the final tombstone unlink may leave an empty directory, never audio.
            if(directory.listFiles()?.isEmpty()==true) {
                runCatching {deletion.delete(directory);deletion.sync(root)}
                return@forEach
            }
            val record = try { read(directory) } catch (_: Exception) {
                SavedRecording(directory.name, directory.lastModified(), clock(), SavedRecordingStatus.UNAVAILABLE)
            }
            rows[record.id] = record
        }
        rows.values.toList().forEach { record ->
            when (record.status) {
                SavedRecordingStatus.CAPTURING, SavedRecordingStatus.UPLOADING -> {
                    val restored=record.copy(status = SavedRecordingStatus.INTERRUPTED, updatedAt = clock())
                    try {save(restored)} catch(_:Exception) {rows[record.id]=restored.copy(failure=SavedRecordingFailure.STORAGE)}
                }
                SavedRecordingStatus.DELETING -> runCatching { erase(record.id) }
                else -> Unit
            }
        }
    }

    @Synchronized fun capacity() = HistoryCapacity(countLimit, rows.size, limitReadable)
    @Synchronized fun requireCapacity() {
        if (!limitReadable) throw IOException("History limit unavailable")
        val state = capacity()
        if (state.full) throw HistoryCapacityReached(state)
    }
    @Synchronized fun setHistoryLimit(limit: Int) {
        require(limit > 0)
        val values = Properties().apply { setProperty("format", "1"); setProperty("limit", limit.toString()) }
        try { commit.write(File(root, "history-limit.properties"), values) }
        catch (e: Exception) {
            // A directory-sync failure can happen after atomic replacement. Match the
            // visible setting instead of admitting against a stale, possibly larger limit.
            readHistoryLimit(); changed(); throw e
        }
        countLimit = limit; limitReadable = true
        changed()
    }
    private fun readHistoryLimit() {
        val setting = File(root, "history-limit.properties")
        try {
            val restored = if (!setting.exists()) HistoryCapacity.DEFAULT_LIMIT else {
                val p = Properties().apply { setting.inputStream().use { load(it) } }
                require(p.getProperty("format") == "1")
                checkNotNull(HistoryCapacity.parse(p.getProperty("limit", "")))
            }
            countLimit = restored; limitReadable = true
        } catch (_: Exception) { limitReadable = false }
    }
    @Synchronized fun records(): List<SavedRecording> = rows.values.sortedByDescending { it.createdAt }
    @Synchronized fun pending(): List<SavedRecording> = rows.values.filter {
        it.id !in reserved && it.kind==SavedRecordingKind.RECORD && audio(it.id).length()>0 &&
            it.status in setOf(SavedRecordingStatus.PENDING, SavedRecordingStatus.FAILED, SavedRecordingStatus.INTERRUPTED)
    }.sortedBy { it.createdAt }
    @Synchronized fun record(file: File): SavedRecording? = id(file)?.let(rows::get)
    @Synchronized fun record(id: String): SavedRecording? = rows[id]
    @Synchronized fun receivedText(id:String):String?=received[id]
    @Synchronized fun receivedTexts():Map<String,String> = received.toMap()
    @Synchronized fun ownedIds():Set<String> = reserved.toSet()
    @Synchronized fun savedText(slot: RecordingTextSlot): String = savedDraft(slot).text
    @Synchronized fun savedDraft(slot: RecordingTextSlot): SavedRecordingDraft {
        val file = File(root, "${slot.name.lowercase()}-text.properties")
        if (!file.exists()) return SavedRecordingDraft("",null)
        val values = Properties().apply { file.inputStream().use { load(it) } }
        require(values.getProperty("format") == "1")
        val source=values.getProperty("sourceId")
        if(source!=null && rows[source]?.status in listOf(null,SavedRecordingStatus.DELETING))return SavedRecordingDraft("",null)
        return SavedRecordingDraft(values.getProperty("text") ?: throw IOException("Missing saved text"),source)
    }
    @Synchronized fun saveText(slot: RecordingTextSlot, text: String,sourceId:String?=null) {
        val file=File(root,"${slot.name.lowercase()}-text.properties")
        if(file.exists()) {
            val old=Properties().apply {file.inputStream().use {load(it)}}
            require(old.getProperty("format")=="1") {"Unknown saved text format"}
        }
        val values = Properties().apply {
            setProperty("format", "1"); setProperty("text", text); setProperty("updatedAt", clock().toString())
            sourceId?.let {require(validId(it));setProperty("sourceId",it)}
        }
        commit.write(file, values)
    }
    @Synchronized fun duration(file:File,seconds:Int) {
        val record=checkNotNull(record(file));save(record.copy(durationSeconds=seconds.coerceAtLeast(0),updatedAt=clock()))
    }
    fun observe(observer: () -> Unit): () -> Unit { observers += observer; return { observers -= observer } }
    private fun changed() { observers.forEach { runCatching(it) } }
    private fun directory(id: String): File { require(validId(id)); return File(root, id) }
    fun audio(id: String): File = File(directory(id), "audio.m4a")
    private fun metadata(id: String) = File(directory(id), "record.properties")
    private fun id(file: File): String? {
        val parent = file.parentFile ?: return null
        return parent.name.takeIf { validId(it) && file.name == "audio.m4a" && parent.parentFile?.canonicalFile == root.canonicalFile }
    }

    @Synchronized fun allocate(): File {
        requireCapacity()
        val id = UUID.randomUUID().toString()
        val now = clock()
        check(directory(id).mkdir()) { "Recording directory unavailable" }
        reserved += id
        try {
            AtomicRecordingCommit.syncDirectory(root)
            save(SavedRecording(id, now, now, SavedRecordingStatus.CAPTURING))
        }
        catch (e: Exception) {
            // Track even a partially committed allocation. No audio has started yet;
            // unknown metadata/bytes stay intact and count until explicit cleanup.
            reserved -= id
            rows[id] = SavedRecording(id, now, now, SavedRecordingStatus.UNAVAILABLE, failure = SavedRecordingFailure.STORAGE)
            changed()
            throw e
        }
        return audio(id)
    }

    @Synchronized fun finalized(file: File, seconds: Int = 0) {
        val record = checkNotNull(record(file))
        require(file.length() > 0) { "Empty recording" }
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
        save(record.copy(status = SavedRecordingStatus.PENDING, durationSeconds = seconds.coerceAtLeast(0), updatedAt = clock()))
    }

    /** Only transfers a completed source after its bytes AND metadata are committed. Safe to repeat. */
    @Synchronized fun retain(file: File, failure: SavedRecordingFailure? = null): File {
        val existing = record(file)
        val record = if (existing != null) existing else {
            require(file.isFile && file.length() > 0)
            val now = clock()
            val new = SavedRecording(UUID.randomUUID().toString(), now, now, SavedRecordingStatus.PENDING)
            check(directory(new.id).mkdir())
            AtomicRecordingCommit.syncDirectory(root)
            val target = audio(new.id)
            FileOutputStream(target).use { output -> file.inputStream().use { it.copyTo(output) }; output.fd.sync() }
            save(new)
            file.delete()
            new
        }
        check(record.status !in setOf(SavedRecordingStatus.COMPLETE, SavedRecordingStatus.DELETING, SavedRecordingStatus.UNAVAILABLE))
        uploading.removeAll { it.id == record.id }
        reserved -= record.id
        val detached=record.copy(status = if (failure == null) SavedRecordingStatus.PENDING else SavedRecordingStatus.FAILED,
            failure = failure, updatedAt = clock())
        try { save(detached) } catch (_: Exception) {
            // Already-durable bytes still have their previous disk record. Release resources even
            // when storage is full; restart conservatively restores any old UPLOADING state.
            rows[record.id]=detached.copy(failure=SavedRecordingFailure.STORAGE)
        }
        changed()
        return audio(record.id)
    }

    @Synchronized fun beginUpload(file: File): RecordingUpload? {
        val record = record(file) ?: return null
        if (record.status !in setOf(SavedRecordingStatus.PENDING, SavedRecordingStatus.FAILED, SavedRecordingStatus.INTERRUPTED) || !file.isFile) return null
        val token = RecordingUpload(record.id, record.attempt + 1)
        save(record.copy(status = SavedRecordingStatus.UPLOADING, attempt = token.attempt, failure = null, updatedAt = clock()))
        uploading += token
        return token
    }

    @Synchronized fun complete(token: RecordingUpload, transcript: String): Boolean {
        require(transcript.isNotBlank())
        val record = owned(token) ?: return false
        // User policy: keep BOTH successful audio and transcript until explicit discard.
        try {save(record.copy(status = SavedRecordingStatus.COMPLETE, transcript = transcript, failure = null, updatedAt = clock()))}
        catch(e:Exception) {received[token.id]=transcript;changed();throw e}
        received.remove(token.id)
        uploading -= token; reserved -= token.id
        changed()
        return true
    }

    @Synchronized fun failed(token: RecordingUpload, failure: SavedRecordingFailure?): Boolean {
        val record = owned(token) ?: return false
        val failed=record.copy(status = SavedRecordingStatus.FAILED, failure = failure, updatedAt = clock())
        try {save(failed)} catch(e:Exception) {
            rows[record.id]=failed.copy(failure=SavedRecordingFailure.STORAGE)
            throw e
        } finally {uploading -= token;changed()}
        changed()
        return true
    }
    @Synchronized fun markFailed(file: File, failure: SavedRecordingFailure) {
        val record = checkNotNull(record(file))
        check(record.status in setOf(SavedRecordingStatus.PENDING, SavedRecordingStatus.FAILED, SavedRecordingStatus.INTERRUPTED))
        save(record.copy(status = SavedRecordingStatus.FAILED, failure = failure, updatedAt = clock()))
    }
    private fun owned(token: RecordingUpload): SavedRecording? = rows[token.id]?.takeIf {
        token in uploading && it.attempt == token.attempt && it.status == SavedRecordingStatus.UPLOADING
    }

    @Synchronized fun discard(id: String): Boolean {
        val record = rows[id] ?: return false
        if (id in reserved || uploading.any { it.id == id }) return false
        save(record.copy(status = SavedRecordingStatus.DELETING, updatedAt = clock()))
        erase(id)
        return true
    }
    /** Only the stopped capture/session owner may release its reservation for explicit Cancel. */
    @Synchronized fun discardOwned(file:File):Boolean {
        val record=record(file) ?: return false
        if(uploading.any {it.id==record.id})return false
        reserved-=record.id
        return discard(record.id)
    }
    private fun erase(id: String) {
        RecordingTextSlot.entries.forEach {slot ->
            val file=File(root,"${slot.name.lowercase()}-text.properties")
            if(file.exists()) {
                val values=Properties().apply {file.inputStream().use {load(it)}}
                if(values.getProperty("format")=="1" && values.getProperty("sourceId")==id)saveText(slot,"")
            }
        }
        val folder = directory(id)
        val tombstone=metadata(id)
        // The deletion intent must outlive every audio/text byte and its directory sync.
        folder.listFiles()?.filter {it!=tombstone}?.forEach {deletion.delete(it)}
        deletion.sync(folder)
        deletion.delete(tombstone);deletion.sync(folder)
        deletion.delete(folder);deletion.sync(root)
        rows.remove(id); reserved -= id; received.remove(id);changed()
    }

    /** Upgrade-only import; old cache is never swept before all recognized bytes are committed. */
    @Synchronized fun migrateLegacy(cache: File) {
        cache.listFiles()?.filter { it.isFile && it.extension == "m4a" && it.length() > 0 }?.forEach { source ->
            val previous = rows.values.firstOrNull { it.legacyName == source.name }
            if (previous != null) { source.delete(); return@forEach }
            val now = clock()
            val id = UUID.nameUUIDFromBytes(("legacy:" + source.name).toByteArray(Charsets.UTF_8)).toString()
            // A failed pre-commit import may have copied the bytes but left no metadata yet.
            // Reuse that known destination; never overwrite an unknown future metadata format.
            if (metadata(id).exists()) throw IOException("Legacy destination has unrecognized metadata")
            val record = SavedRecording(id, source.lastModified().coerceAtMost(now), now,
                SavedRecordingStatus.INTERRUPTED, legacyName = source.name)
            check(directory(record.id).mkdir() || directory(record.id).isDirectory)
            AtomicRecordingCommit.syncDirectory(root)
            FileOutputStream(audio(record.id)).use { output -> source.inputStream().use { it.copyTo(output) }; output.fd.sync() }
            save(record)
            source.delete()
        }
        migrationPending=false
    }
    fun migrationFailed() {migrationPending=true;changed()}

    private fun save(record: SavedRecording) {
        val values = Properties().apply {
            setProperty("format", "1"); setProperty("id", record.id)
            setProperty("createdAt", record.createdAt.toString()); setProperty("updatedAt", record.updatedAt.toString())
            setProperty("status", record.status.name); setProperty("seconds", record.durationSeconds.toString())
            setProperty("transcript", record.transcript); setProperty("attempt", record.attempt.toString())
            setProperty("kind",record.kind.storedValue);setProperty("partial",record.partial.toString())
            record.failure?.let { setProperty("failure", it.name) }; record.legacyName?.let { setProperty("legacyName", it) }
        }
        commit.write(metadata(record.id), values)
        rows[record.id] = record
        changed()
    }
    private fun read(directory: File): SavedRecording {
        val p = Properties().apply { File(directory, "record.properties").inputStream().use { load(it) } }
        require(p.getProperty("format") == "1" && p.getProperty("id") == directory.name)
        return SavedRecording(directory.name, p.getProperty("createdAt").toLong(), p.getProperty("updatedAt").toLong(),
            SavedRecordingStatus.valueOf(p.getProperty("status")), p.getProperty("seconds").toInt(), p.getProperty("transcript"),
            p.getProperty("failure")?.let(SavedRecordingFailure::valueOf), p.getProperty("attempt").toLong(), p.getProperty("legacyName"),
            SavedRecordingKind.fromStored(p.getProperty("kind","RECORD")),p.getProperty("partial","false").toBooleanStrict())
    }
    companion object { private fun validId(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) }
}
