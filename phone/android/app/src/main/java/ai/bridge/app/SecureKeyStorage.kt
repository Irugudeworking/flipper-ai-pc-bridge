package ai.bridge.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small encrypted preference store. The AES key is non-exportable and held by Android Keystore. */
internal class SecureKeyStorage(context: Context, provider: AiBackend) {
    private val prefs = context.getSharedPreferences(
        if(provider == AiBackend.OPENAI) "secure_ai" else "secure_gemini", Context.MODE_PRIVATE)
    private val keyAlias = if(provider == AiBackend.OPENAI) "flipper_ai_api_key_v1" else "flipper_ai_gemini_api_key_v1"

    fun isConfigured(): Boolean = prefs.contains("iv") && prefs.contains("ciphertext")

    fun save(value: String) {
        require(value.isNotBlank()) { "API key cannot be empty" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()) { "Could not save encrypted API key" }
    }

    fun read(): String? {
        val ivValue = prefs.getString("iv", null) ?: return null
        val cipherValue = prefs.getString("ciphertext", null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(ivValue, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(cipherValue, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            delete()
            null
        }
    }

    fun delete() {
        prefs.edit().clear().commit()
        runCatching { (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }).deleteEntry(keyAlias) }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
