package com.livetranslate.app.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A published GitHub release that is newer than the installed build. */
data class AvailableUpdate(
    val version: String,
    val url: String,
)

/**
 * Asks GitHub for the latest (non-draft, non-prerelease) release and reports it
 * when its tag is newer than [currentVersion]. Every failure — offline, rate
 * limited, malformed tag — is treated as "no update" so startup never breaks.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/luoxiaoxin123/live-translate/releases/latest"
    const val RELEASES_PAGE = "https://github.com/luoxiaoxin123/live-translate/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String? = null,
    )

    suspend fun check(currentVersion: String): AvailableUpdate? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val release = json.decodeFromString<Release>(response.body?.string().orEmpty())
                if (!isNewer(release.tagName, currentVersion)) return@runCatching null
                AvailableUpdate(
                    version = release.tagName.trimStart('v', 'V'),
                    url = release.htmlUrl?.takeIf { it.isNotBlank() } ?: RELEASES_PAGE,
                )
            }
        }.onFailure { Log.w(TAG, "update check failed", it) }.getOrNull()
    }

    /** True when [latest] has a higher numeric version than [current] ("v0.4.3" > "0.4.2-debug"). */
    fun isNewer(latest: String, current: String): Boolean {
        val a = numericParts(latest) ?: return false
        val b = numericParts(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (diff != 0) return diff > 0
        }
        return false
    }

    private fun numericParts(version: String): List<Int>? {
        val core = version.trim().trimStart('v', 'V').takeWhile { it.isDigit() || it == '.' }
        val parts = core.split('.').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: return null }
        return parts.ifEmpty { null }
    }
}
