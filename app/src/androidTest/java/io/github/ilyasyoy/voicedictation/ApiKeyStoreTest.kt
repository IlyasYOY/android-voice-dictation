package io.github.ilyasyoy.voicedictation

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.data.AndroidApiKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ApiKeyStoreTest {
    @Test fun authenticatedCiphertextRejectsTamperingAndForgetRecovers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidApiKeyStore(context, ".test.tamper")
        try {
            store.forget(); store.save("synthetic-tamper-test")
            val preferences = context.getSharedPreferences("encrypted_api_key.test.tamper", Context.MODE_PRIVATE)
            val original = preferences.getString("payload", null)!!
            val parts = original.split(':').toMutableList()
            val ciphertext = android.util.Base64.decode(parts[2], android.util.Base64.NO_WRAP)
            ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
            parts[2] = android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP)
            val edit = preferences.edit(); edit.putString("payload", parts.joinToString(":")); assertTrue(edit.commit())
            try { store.load(); fail("Tampered ciphertext accepted") } catch (_: javax.crypto.AEADBadTagException) { }
            store.forget(); assertNull(store.load())
        } finally { store.forget() }
    }
}
