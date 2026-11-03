package io.github.ilyasyoy.voicedictation.diagnostics

import io.github.ilyasyoy.voicedictation.data.ApiFailure
import java.io.*
import java.util.concurrent.*
import java.util.zip.ZipInputStream
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class DiagnosticStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val event = DiagnosticEvent(DiagnosticArea.RECORDER, DiagnosticAction.RECORD, DiagnosticCode.IO)
    private var now = 2_000_000_000_000L
    private fun store(dir: File = temporary.newFolder()) = DiagnosticStore(dir) { now }
    private fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) { val entry=zip.nextEntry ?: break; put(entry.name, zip.readBytes().toString(Charsets.UTF_8)) }
        }
    }
    private fun archive(events: ByteArray): Map<String,String> {
        val bytes=ByteArrayOutputStream()
        DiagnosticArchive.write(bytes,events,29,28,0,0)
        return unzip(bytes.toByteArray())
    }
    @Test fun ageBoundaryAndRestartPruneWithoutNewEvents() {
        val dir=temporary.newFolder();val logs=store(dir)
        logs.append(event);now+=DiagnosticStore.MAX_AGE_MILLIS-1
        assertTrue(store(dir).snapshot().isNotEmpty())
        now++
        assertEquals(0,store(dir).snapshot().size)
        assertTrue(dir.listFiles()!!.isEmpty())
    }
    @Test fun oldSegmentIsRemovedEvenIfItContainsSomeNewerEvents() {
        val dir=temporary.newFolder();val logs=store(dir)
        logs.append(event);now+=100;logs.append(event)
        now+=DiagnosticStore.MAX_AGE_MILLIS-100
        logs.prune()
        assertEquals(0,logs.snapshot().size)
        logs.append(event);assertEquals(1,logs.snapshot().toString(Charsets.UTF_8).lines().count { it.isNotEmpty() })
    }
    @Test fun sizeRotationIsBoundedBeforeAndAfterRestartAndKeepsNewest() {
        val dir=temporary.newFolder();val logs=store(dir)
        repeat(16000) { logs.append(event) }
        logs.append(DiagnosticEvent(DiagnosticArea.EXPORT,DiagnosticAction.SAVE,DiagnosticCode.OK))
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= DiagnosticStore.MAX_BYTES)
        assertTrue(dir.listFiles()!!.all { it.length() <= DiagnosticStore.SEGMENT_BYTES })
        assertTrue(dir.listFiles()!!.size <= DiagnosticStore.MAX_SEGMENTS)
        val snapshot=store(dir).snapshot().toString(Charsets.UTF_8)
        assertTrue(snapshot.trimEnd().endsWith("\"code\":\"OK\"}"))
        assertTrue(snapshot.lines().count { it.isNotEmpty() } < 16001)
    }
    @Test fun oversizeCorruptFilesFutureSegmentsAndPartialLinesCannotEnterArchive() {
        val dir=temporary.newFolder();val logs=store(dir);logs.append(event)
        val valid=dir.listFiles()!!.single()
        valid.appendText("{\"time_ms\":$now,\"area\":\"APP\",\"action\":\"START\",\"code\":\"SECRET_SENTINEL\"}\n{partial")
        File(dir,"events-${now+1}-0.jsonl").writeText("FUTURE_SENTINEL")
        File(dir,"events-$now-123.jsonl").writeBytes(ByteArray(65_537) { 65 })
        File(dir,"not-a-log.txt").writeText("PRIVATE_SENTINEL")
        val snapshot=logs.snapshot().toString(Charsets.UTF_8)
        assertEquals(1,snapshot.lines().count { it.isNotEmpty() })
        assertFalse(archive(snapshot.toByteArray()).values.joinToString().contains("SENTINEL"))
        assertEquals(1,dir.listFiles()!!.size)
    }
    @Test fun clockRollbackDropsFutureLogsAndContinuesRecording() {
        val dir=temporary.newFolder();val logs=store(dir);logs.append(event);now-=1000
        logs.append(event)
        assertEquals(1,logs.snapshot().toString(Charsets.UTF_8).lines().count { it.isNotEmpty() })
    }
    @Test fun concurrentAppendSnapshotAndRotationProduceWholeRows() {
        val logs=store();val pool=Executors.newFixedThreadPool(5)
        try {
            val writes=(1..4).map { pool.submit { repeat(1000) { logs.append(event) } } }
            val reads=pool.submit { repeat(20) { archive(logs.snapshot()) } }
            (writes+reads).forEach { it.get(30,TimeUnit.SECONDS) }
            val rows=logs.snapshot().toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
            assertEquals(4000,rows.size)
            assertTrue(rows.all { it.startsWith("{\"time_ms\":") && it.endsWith("}") })
        } finally { pool.shutdownNow() }
    }
    @Test fun exceptionMessagesCausesToStringAndUnboundedStatusesAreExcluded() {
        val logs=store()
        val poison=object: IOException("KEY_SENTINEL AUDIO_SENTINEL DICTATION_SENTINEL WORDS_SENTINEL CLIPBOARD_SENTINEL SERVER_SENTINEL APP_SENTINEL") {
            override val message: String get() = error("Must not read exception.message")
            override fun toString(): String = error("Must not call Throwable.toString")
        }
        logs.append(DiagnosticEvent.failure(DiagnosticArea.NETWORK,DiagnosticAction.PROCESS,poison))
        logs.append(DiagnosticEvent.failure(DiagnosticArea.NETWORK,DiagnosticAction.PROCESS,ApiFailure(401)))
        logs.append(DiagnosticEvent.failure(DiagnosticArea.NETWORK,DiagnosticAction.PROCESS,ApiFailure(Int.MAX_VALUE)))
        val result=archive(logs.snapshot()).values.joinToString()
        assertFalse(result.contains("SENTINEL"));assertFalse(result.contains(Int.MAX_VALUE.toString()))
        assertTrue(result.contains("\"http_status\":401"));assertTrue(result.contains("\"code\":\"AUTH\""))
        assertTrue(DiagnosticEvent::class.java.declaredFields.none { it.type == String::class.java || Throwable::class.java.isAssignableFrom(it.type) })
    }
    @Test fun emptyZipHasMetadataAndNoInventedEvents() {
        val result=archive(store().snapshot())
        assertEquals(setOf("manifest.json","events.jsonl","README.txt"),result.keys)
        assertEquals("",result["events.jsonl"])
        assertTrue(result.getValue("manifest.json").contains("\"empty\":true"))
    }
    @Test fun exportDoesNotHoldStorageLockOrCreateTemporaryFiles() {
        val dir=temporary.newFolder();val logs=store(dir);logs.append(event)
        val snapshot=logs.snapshot();val before=dir.listFiles()!!.map { it.name }.toSet()
        val output=object: ByteArrayOutputStream() {
            var once=false
            override fun write(bytes: ByteArray, offset: Int, size: Int) {
                if(!once) { once=true; val task=FutureTask { logs.append(event) };Thread(task).start();task.get(3,TimeUnit.SECONDS) }
                super.write(bytes,offset,size)
            }
        }
        DiagnosticArchive.write(output,snapshot,29,28,0,0)
        assertEquals(before,dir.listFiles()!!.map { it.name }.toSet())
        assertEquals(1,unzip(output.toByteArray()).getValue("events.jsonl").lines().count { it.isNotEmpty() })
        assertEquals(2,logs.snapshot().toString(Charsets.UTF_8).lines().count { it.isNotEmpty() })
    }
    @Test fun failedOpenWriteAndCloseDiscardPartialDocumentAndNeverClaimSuccess() {
        val factories=listOf<()->OutputStream?>(
            {null}, {throw SecurityException("PRIVATE_SENTINEL")},
            {object: OutputStream() { override fun write(value:Int) { throw IOException("PRIVATE_SENTINEL") } }},
            {object: ByteArrayOutputStream() { override fun close() { throw IOException("PRIVATE_SENTINEL") } }})
        for(open in factories) {
            var deleted=0
            assertFalse(DiagnosticExport.save({ByteArray(0)},open,{deleted++},29,28))
            assertEquals(1,deleted)
        }
    }
    @Test fun failedSnapshotAlsoDiscardsSelectedEmptyFileAndCleanupFailureIsContained() {
        assertFalse(DiagnosticExport.save({throw IOException("PRIVATE_SENTINEL")},{error("Must not open")},{throw SecurityException()},29,28))
    }
    @Test fun successfulExportClosesDestinationAndLeavesUserCopyAlone() {
        var closed=false;var deleted=false
        val output=object: ByteArrayOutputStream() { override fun close() { closed=true;super.close() } }
        assertTrue(DiagnosticExport.save({ByteArray(0)},{output},{deleted=true},29,28))
        assertTrue(closed);assertFalse(deleted);assertEquals(3,unzip(output.toByteArray()).size)
    }
    @Test fun storageFailureIsExplicitAndDoesNotWriteOutsideItsDirectory() {
        val file=temporary.newFile()
        assertThrows(IOException::class.java) { DiagnosticStore(file).append(event) }
    }
}
