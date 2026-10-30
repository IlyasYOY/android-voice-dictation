package io.github.ilyasyoy.voicedictation.data

import android.content.Context
import android.annotation.SuppressLint
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ApiKeyStore {
    suspend fun load(): String?
    suspend fun save(key: String)
    suspend fun forget()
}
object NoKeyStore : ApiKeyStore {
    override suspend fun load(): String? = null
    override suspend fun save(key: String): Unit = throw IOException("Storage unavailable")
    override suspend fun forget() = Unit
}

/** Only AES-GCM ciphertext and a random IV reach app-private preferences; key is non-exportable. */
class AndroidApiKeyStore(context: Context, namespace: String = "") : ApiKeyStore by SerializedApiKeyStore(
    EncryptedApiKeyStore(context, namespace), ApiKeyStoreLocks.namespace(namespace))

private class EncryptedApiKeyStore(context: Context, namespace: String) : ApiKeyStore {
    private val alias = "voice-dictation.api-key.v1" + namespace
    private val preferences = context.applicationContext.getSharedPreferences("encrypted_api_key" + namespace, Context.MODE_PRIVATE)
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun secret(create: Boolean): SecretKey {
        (keystore().getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw IOException("Saved key unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override suspend fun load(): String? = withContext(Dispatchers.IO) {
        val payload = preferences.getString("payload", null) ?: return@withContext null
        val parts = payload.split(':')
        require(parts.size == 3 && parts[0] == "v1")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(false), GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), Charsets.UTF_8)
    }
    @SuppressLint("UseKtx") // Need commit() Boolean to report failed persistence; KTX edit returns Unit.
    override suspend fun save(key: String): Unit = withContext(Dispatchers.IO) {
        require(key.isNotEmpty())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret(true))
        val payload = "v1:${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:" +
            Base64.encodeToString(cipher.doFinal(key.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        if (!preferences.edit().putString("payload", payload).commit()) throw IOException("Could not save key")
    }
    @SuppressLint("UseKtx") // Need commit() Boolean: KTX edit returns Unit and would hide a failed deletion.
    override suspend fun forget(): Unit = withContext(Dispatchers.IO) {
        val removed = preferences.edit().remove("payload").commit()
        val store = keystore()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
        if (!removed) throw IOException("Could not remove saved key")
    }
}
