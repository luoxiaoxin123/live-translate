package com.livetranslate.app.ui.session

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.livetranslate.app.LiveTranslateApp
import com.livetranslate.app.R
import com.livetranslate.app.data.AudioSourceMode
import com.livetranslate.app.service.SessionBus
import com.livetranslate.app.service.SubtitleSessionService
import com.livetranslate.app.ui.main.MainActivity
import com.livetranslate.app.util.PermissionUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Invisible trampoline for starting a subtitle session without opening the app
 * UI (Quick Settings tile, future floating toggle). Chains the same checks as
 * MainActivity — API key, runtime permissions, overlay permission, projection
 * consent — over whatever app the user is currently in.
 *
 * Must NOT be noHistory: it has to survive being covered by the permission /
 * projection dialogs to receive their results. It stays invisible and finishes
 * itself after every path instead.
 */
class SessionStartActivity : ComponentActivity() {

    private lateinit var app: LiveTranslateApp
    private var mode: AudioSourceMode = AudioSourceMode.MEDIA

    private val runtimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) {
            checkOverlayPermission()
        } else {
            fail(R.string.msg_need_permissions)
        }
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (PermissionUtils.canDrawOverlays(this)) {
            continueToProjectionOrStart()
        } else {
            fail(R.string.msg_need_overlay)
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startSession(result.resultCode, result.data)
        } else {
            fail(R.string.msg_projection_cancelled)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as LiveTranslateApp
        lifecycleScope.launch {
            mode = app.settingsRepository.settings.first().audioSourceMode
            checkApiKey()
        }
    }

    private fun checkApiKey() {
        if (!app.apiKeyStore.hasApiKey()) {
            Toast.makeText(this, R.string.msg_need_api_key, Toast.LENGTH_LONG).show()
            SessionBus.setStatus(
                SessionBus.Status.Error,
                getString(R.string.msg_need_api_key),
            )
            // Landing on the app is the fastest path to fixing this — the user
            // is sent to Settings where the API key field lives.
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        checkRuntimePermissions()
    }

    private fun checkRuntimePermissions() {
        val needed = buildList {
            if (mode.needsMicrophone || mode.needsMediaProjection) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.distinct().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            checkOverlayPermission()
        } else {
            runtimePermissionsLauncher.launch(needed.toTypedArray())
        }
    }

    private fun checkOverlayPermission() {
        if (PermissionUtils.canDrawOverlays(this)) {
            continueToProjectionOrStart()
        } else {
            overlayLauncher.launch(PermissionUtils.overlaySettingsIntent(this))
        }
    }

    private fun continueToProjectionOrStart() {
        if (mode.needsMediaProjection) {
            SessionBus.setStatus(
                SessionBus.Status.Starting,
                getString(R.string.msg_requesting_projection),
            )
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            startSession(resultCode = null, data = null)
        }
    }

    private fun startSession(resultCode: Int?, data: Intent?) {
        runCatching {
            SubtitleSessionService.start(
                this,
                audioSource = mode,
                resultCode = resultCode,
                data = data,
            )
        }.onFailure {
            Log.e(TAG, "start service failed", it)
            fail(R.string.msg_service_start_failed, it.message.orEmpty())
            return
        }
        finish()
    }

    private fun fail(messageRes: Int, vararg args: Any) {
        val message = getString(messageRes, *args)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        SessionBus.setStatus(SessionBus.Status.Error, message)
        finish()
    }

    companion object {
        private const val TAG = "SessionStartActivity"
    }
}
