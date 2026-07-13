package com.livetranslate.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API Key storage. Prefers EncryptedSharedPreferences; falls back to private prefs
 * if Keystore/crypto init fails (some ROMs crash otherwise).
 */
class ApiKeyStore(context: Context) {
    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    fun getApiKey(): String = prefs.getString(KEY_API, "").orEmpty()

    fun setApiKey(value: String) {
        prefs.edit().putString(KEY_API, value.trim()).apply()
    }

    fun hasApiKey(): Boolean = getApiKey().isNotBlank()

    private fun createPrefs(context: Context): SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                context,
                FILE_NAME_ENCRYPTED,
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "EncryptedSharedPreferences unavailable, using private prefs", t)
            context.getSharedPreferences(FILE_NAME_FALLBACK, Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val TAG = "ApiKeyStore"
        private const val FILE_NAME_ENCRYPTED = "secure_api_prefs"
        private const val FILE_NAME_FALLBACK = "api_prefs_fallback"
        private const val KEY_API = "api_key"
    }
}
