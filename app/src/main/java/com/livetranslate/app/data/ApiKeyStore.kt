package com.livetranslate.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stores up to [MAX_KEYS] API keys (encrypted when possible) and supports rotation.
 */
class ApiKeyStore(context: Context) {
    private val prefs: SharedPreferences = createPrefs(context.applicationContext)
    private val rotateIndex = AtomicInteger(0)

    fun getApiKeys(): List<String> {
        val raw = prefs.getString(KEY_API_LIST, null)
        if (!raw.isNullOrBlank()) {
            return runCatching {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i).trim()
                        if (s.isNotEmpty()) add(s)
                    }
                }
            }.getOrDefault(emptyList()).ifEmpty {
                // migrate legacy single key
                val legacy = prefs.getString(KEY_API, "").orEmpty().trim()
                if (legacy.isNotEmpty()) listOf(legacy) else emptyList()
            }
        }
        val legacy = prefs.getString(KEY_API, "").orEmpty().trim()
        return if (legacy.isNotEmpty()) listOf(legacy) else emptyList()
    }

    fun setApiKeys(keys: List<String>) {
        val cleaned = keys.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_KEYS)
        val arr = JSONArray()
        cleaned.forEach { arr.put(it) }
        prefs.edit()
            .putString(KEY_API_LIST, arr.toString())
            .putString(KEY_API, cleaned.firstOrNull().orEmpty()) // keep legacy field in sync
            .apply()
        if (cleaned.isEmpty()) {
            rotateIndex.set(0)
        } else {
            rotateIndex.updateAndGet { it.coerceIn(0, cleaned.lastIndex) }
        }
    }

    /** Legacy helpers used by older call sites. */
    fun getApiKey(): String = getApiKeys().firstOrNull().orEmpty()

    fun setApiKey(value: String) {
        val rest = getApiKeys().drop(1)
        setApiKeys(listOf(value.trim()).filter { it.isNotEmpty() } + rest)
    }

    fun hasApiKey(): Boolean = getApiKeys().isNotEmpty()

    /**
     * Round-robin pick for a new session. Empty string if none.
     */
    fun nextRotatedKey(): String {
        val keys = getApiKeys()
        if (keys.isEmpty()) return ""
        val i = rotateIndex.getAndUpdate { (it + 1) % keys.size }
        return keys[i % keys.size]
    }

    /**
     * Try keys starting from [startIndex] (inclusive) for connection test / failover.
     */
    fun keysFrom(startIndex: Int = 0): List<String> {
        val keys = getApiKeys()
        if (keys.isEmpty()) return emptyList()
        val start = startIndex.coerceIn(0, keys.lastIndex)
        return keys.drop(start) + keys.take(start)
    }

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
        private const val KEY_API_LIST = "api_key_list"
        const val MAX_KEYS = 10
    }
}
