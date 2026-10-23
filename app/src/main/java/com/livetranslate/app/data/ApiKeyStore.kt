package com.livetranslate.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stores up to [MAX_KEYS] API keys (encrypted when possible). Sessions stick to
 * the active key and only fail over to the next one when it errors or runs out
 * of quota; the active index is in-memory only.
 */
class ApiKeyStore(context: Context) {
    private val prefs: SharedPreferences = createPrefs(context.applicationContext)
    private val activeIndex = AtomicInteger(0)

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
            activeIndex.set(0)
        } else {
            activeIndex.updateAndGet { it.coerceIn(0, cleaned.lastIndex) }
        }
    }

    /** Legacy helpers used by older call sites. */
    fun getApiKey(): String = getApiKeys().firstOrNull().orEmpty()

    fun setApiKey(value: String) {
        val rest = getApiKeys().drop(1)
        setApiKeys(listOf(value.trim()).filter { it.isNotEmpty() } + rest)
    }

    fun hasApiKey(): Boolean = getApiKeys().isNotEmpty()

    /** The key sessions use until it fails. Empty string if none. */
    fun activeKey(): String {
        val keys = getApiKeys()
        if (keys.isEmpty()) return ""
        return keys[activeIndex.get().coerceIn(0, keys.lastIndex)]
    }

    /**
     * [failed] errored or ran out of quota: make the key after it active and
     * return that, or null when there is no other key to fall back to.
     */
    fun failoverFrom(failed: String): String? {
        val keys = getApiKeys()
        if (keys.size < 2) return null
        val failedIndex = keys.indexOf(failed)
        // If the keys were edited and [failed] is gone, the current active key
        // (a different one) takes over.
        val next = if (failedIndex >= 0) {
            (failedIndex + 1) % keys.size
        } else {
            activeIndex.get().coerceIn(0, keys.lastIndex)
        }
        activeIndex.set(next)
        return keys[next]
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
