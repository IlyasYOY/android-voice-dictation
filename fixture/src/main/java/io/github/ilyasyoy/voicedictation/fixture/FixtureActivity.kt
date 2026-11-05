package io.github.ilyasyoy.voicedictation.fixture

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.*
import android.widget.*
import androidx.annotation.RequiresApi

/** No accounts, user data or real messages. The product app runs in a separate, uninstrumented UID. */
class FixtureActivity : Activity() {
    private var requestedAction: ((Intent) -> Unit)? = null
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); requestedAction?.invoke(intent) }
    override fun onCreate(savedInstanceState: Bundle?) {
        if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 32, 12, 12) }
        val viewport = ScrollView(this)
        root.addView(TextView(this).apply { setText(R.string.test_banner); textSize = 16f })
        fun field(id: Int, initial: String, type: Int, unsupported: Boolean = false): EditText {
            val editor = if (unsupported) object : EditText(this) {
                override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
                    super.onCreateInputConnection(outAttrs)?.let { original ->
                        object : InputConnectionWrapper(original, false) {
                            override fun commitText(text: CharSequence?, newCursorPosition: Int) = false
                            @RequiresApi(33) override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) = false
                            @RequiresApi(31) override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? = null
                        }
                    }
            } else EditText(this)
            editor.id = id; editor.inputType = type; editor.setText(initial); editor.setTextSize(16f)
            root.addView(editor, LinearLayout.LayoutParams(-1, 72)); return editor
        }
        val plain = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        val body = field(R.id.body, "Before  after", plain)
        val second = field(R.id.second_body, "Other field", plain)
        val secret = field(R.id.secret, "fake-only", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val numeric = field(R.id.payment_amount, "", InputType.TYPE_CLASS_NUMBER)
        val unsupported = field(R.id.unsupported_body, "Unsupported stays unchanged", plain, true)
        fun focus(editor: EditText, position: Int = editor.text.length) {
            editor.requestFocus(); editor.setSelection(position)
            editor.post {
                editor.requestRectangleOnScreen(android.graphics.Rect(0, 0, editor.width, editor.height), true)
                getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                editor.postDelayed({ editor.requestRectangleOnScreen(android.graphics.Rect(0, 0, editor.width, editor.height), true) }, 1200)
            }
        }
        fun button(label: String, id: Int = View.NO_ID, action: () -> Unit) {
            root.addView(Button(this).apply { text = label; this.id = id; setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, 52))
        }
        button("Focus body at insertion point", R.id.focus_body) { focus(body, 7) }
        button("Focus second", R.id.focus_second) { focus(second) }
        button("Focus password", R.id.focus_password) { focus(secret) }
        button("Focus numeric/payment", R.id.focus_numeric) { focus(numeric) }
        button("Focus unsupported", R.id.focus_unsupported) { focus(unsupported) }
        button("Choose keyboard", R.id.choose_keyboard) { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        button("Enable test keyboard in system settings", R.id.enable_keyboard) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        button("Open floating setup") { startActivity(Intent().setComponent(ComponentName(APP, "$APP.MainActivity"))) }
        button("Configure isolated fake backend") {
            startActivity(Intent().setComponent(ComponentName(APP, "$APP.floating.DebugFloatingSetupActivity")).putExtra("port", intent.getIntExtra("port", 0)))
        }
        val submissions = TextView(this).apply { id = R.id.submission_count; text = getString(R.string.submissions, 0) }
        root.addView(submissions)
        button("Submit TEST ONLY") { submissions.text = getString(R.string.submissions, 1) }
        viewport.addView(root)
        if (Build.VERSION.SDK_INT >= 30) viewport.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(viewport)
        requestedAction = { command ->
            val target = when (command.getStringExtra("field")) {
                "body" -> body; "second" -> second; "password" -> secret; "numeric" -> numeric; "unsupported" -> unsupported
                else -> null
            }
            target?.let { focus(it, if (it === body) 7 else it.text.length) }
            if (command.getBooleanExtra("picker", false)) getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
        root.post { requestedAction?.invoke(intent) }
    }
    companion object { private const val APP = "io.github.ilyasyoy.voicedictation" }
}
