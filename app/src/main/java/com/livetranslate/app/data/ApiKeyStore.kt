package com.livetranslate.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the API key in EncryptedSharedPreferences (Android Keystore-backed).
 * Never log the raw key.
 */
class ApiKeyStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getApiKey(): String = prefs.getString(KEY_API, "").orEmpty()

    fun setApiKey(value: String) {
        prefs.edit().putString(KEY_API, value.trim()).apply()
    }

    fun hasApiKey(): Boolean = getApiKey().isNotBlank()

    companion object {
        private const val FILE_NAME = "secure_api_prefs"
        private const val KEY_API = "api_key"
    }
}
