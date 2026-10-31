package io.github.ilyasyoy.voicedictation

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.floating.DebugFloatingConfiguration
import io.github.ilyasyoy.voicedictation.floating.FloatingFeedback
import io.github.ilyasyoy.voicedictation.session.*
import io.github.ilyasyoy.voicedictation.ui.design.SoftCurrentPalette as P
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Small real storage/retry surface. Swipe history and playback remain separate milestones. */
class SavedRecordingsActivity:Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var closing=false
    private var visibleCount=20
    private val owner get()=RecordingRecovery.processOwned
    private var scroll:ScrollView?=null
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private val dark get()=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
    private val ink get()=if(dark)P.NightText else P.Plum
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);WindowCompat.setDecorFitsSystemWindows(window,false);DictationSessions.prepare(this)
        visibleCount=state?.getInt("visible_count",20) ?: 20
        scope.launch {owner.state.collect {if(!closing)render()}}
    }
    private fun render() {
        val offset=scroll?.scrollY ?: 0
        val state=owner.state.value
        val content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(24),dp(20),dp(24))}
        fun label(parent:LinearLayout,text:String,size:Float=16f,id:Int=View.NO_ID) = TextView(this).also {
            it.id=id;it.text=text;it.textSize=size;it.setTextColor(ink);it.setPadding(0,dp(8),0,dp(8));parent.addView(it)
        }
        fun button(parent:LinearLayout,text:String,id:Int=View.NO_ID,enabled:Boolean=true,action:()->Unit) {
            parent.addView(Button(this).apply {
                this.id=id;this.text=text;isAllCaps=false;isEnabled=enabled;setTextColor(ink);minHeight=dp(52)
                background=GradientDrawable().apply {setColor(if(dark)P.NightSurface else 0xFFE7DDF2.toInt());cornerRadius=dp(24).toFloat()}
                setOnClickListener {action()}
            },LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(6)})
        }
        label(content,getString(R.string.saved_title),26f)
        label(content,getString(R.string.saved_retention))
        if(state.storageProblem)label(content,getString(R.string.recording_storage_error))
        if(state.records.isEmpty())label(content,getString(R.string.saved_empty))
        val repository=runCatching {AndroidRecordingStorage.repository(this)}.getOrNull()
        for(record in state.records.take(visibleCount)) {
            val row=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(16))
                background=GradientDrawable().apply {setColor(if(dark)P.NightSurface else P.Surface);cornerRadius=dp(24).toFloat()}
            }
            content.addView(row,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(16)})
            label(row,DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(record.createdAt)),17f)
            val status=when(record.status) {
                SavedRecordingStatus.COMPLETE -> R.string.saved_transcribed
                SavedRecordingStatus.CAPTURING -> R.string.recording
                SavedRecordingStatus.UPLOADING -> R.string.transcribing
                SavedRecordingStatus.INTERRUPTED -> R.string.saved_interrupted
                SavedRecordingStatus.UNAVAILABLE,SavedRecordingStatus.DELETING -> R.string.saved_unavailable
                else -> R.string.saved_pending
            }
            label(row,getString(status))
            if(record.durationSeconds>0)label(row,"%02d:%02d".format(record.durationSeconds/60,record.durationSeconds%60))
            if(record.kind==SavedRecordingKind.LIVE)label(row,getString(R.string.saved_live_text))
            else if(repository?.audio(record.id)?.isFile==true)label(row,getString(R.string.saved_audio_kept))
            if(record.failure!=null)label(row,getString(when(record.failure) {
                SavedRecordingFailure.KEY,SavedRecordingFailure.AUTH -> R.string.current_retry_key_error
                SavedRecordingFailure.NETWORK -> R.string.current_retry_network_error
                SavedRecordingFailure.SERVICE -> R.string.current_retry_service_error
                SavedRecordingFailure.STORAGE -> R.string.recording_storage_error
            }))
            val received=state.receivedTexts[record.id]
            val transcript=received ?: record.transcript
            if(received!=null)label(row,getString(R.string.saved_unsaved_text))
            if(transcript.isNotBlank()) {
                label(row,transcript,18f,R.id.floating_preview).setTextIsSelectable(true)
                button(row,getString(R.string.copy),R.id.floating_copy,!state.retrying) {copy(transcript)}
            }
            val canRetry=record.kind==SavedRecordingKind.RECORD && record.status in setOf(SavedRecordingStatus.PENDING,SavedRecordingStatus.FAILED,SavedRecordingStatus.INTERRUPTED) &&
                (repository?.audio(record.id)?.length() ?: 0)>0
            if(canRetry)button(row,getString(if(received!=null)R.string.saved_save_received else R.string.current_retry_recording),R.id.floating_retry_audio,!state.retrying) {
                scope.launch {
                    try {
                        val text=owner.retry(record.id) {audio -> DebugFloatingConfiguration.transcribeRetained(this@SavedRecordingsActivity,audio)
                            ?: DictationSessions.transcribeRetained(this@SavedRecordingsActivity,audio)}
                        if(text!=null) {FloatingFeedback.processOwned.retain(text);FloatingFeedback.processOwned.resetSignal()}
                    } catch(e:CancellationException) {throw e}
                    catch(_:Exception) { /* The ledger keeps the recording and exposes its typed failure. */ }
                }
            }
            button(row,getString(R.string.saved_delete),R.id.saved_delete,!state.retrying) {
                AlertDialog.Builder(this).setMessage(R.string.saved_delete_confirm)
                    .setPositiveButton(R.string.saved_delete) {_,_->owner.discard(record.id)}
                    .setNegativeButton(android.R.string.cancel,null).show()
            }
        }
        if(state.records.size>visibleCount)button(content,getString(R.string.saved_more)) {visibleCount+=20;render()}
        button(content,getString(R.string.saved_back)) {finish()}
        scroll=ScrollView(this).apply {setBackgroundColor(if(dark)P.Night else P.Paper);addView(content)}
        ViewCompat.setOnApplyWindowInsetsListener(checkNotNull(scroll)) {view,insets ->
            val safe=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left,safe.top,safe.right,safe.bottom);insets
        }
        setContentView(scroll)
        scroll?.post {scroll?.scrollTo(0,offset)}
    }
    private fun copy(text:String) {
        val clip=ClipData.newPlainText(getString(R.string.transcript),text)
        if(Build.VERSION.SDK_INT>=33)clip.description.extras=PersistableBundle().apply {putBoolean("android.content.extra.IS_SENSITIVE",true)}
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }
    override fun onSaveInstanceState(out:Bundle) {out.putInt("visible_count",visibleCount);super.onSaveInstanceState(out)}
    override fun onDestroy() {closing=true;scope.cancel();super.onDestroy()}
}
