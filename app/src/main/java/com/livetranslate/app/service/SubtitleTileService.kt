package com.livetranslate.app.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.livetranslate.app.R
import com.livetranslate.app.ui.session.SessionStartActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile mirroring the subtitle session. Tap toggles start/stop;
 * starting goes through SessionStartActivity so projection consent and missing
 * permissions are handled over the current app.
 */
class SubtitleTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onStartListening() {
        stateJob = scope.launch {
            SessionBus.state.collect { updateTile(it) }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
    }

    override fun onClick() {
        when (SessionBus.state.value.status) {
            SessionBus.Status.Starting, SessionBus.Status.Running -> stopSession()
            else -> {
                if (isSecure) {
                    unlockAndRun { launchStartActivity() }
                } else {
                    launchStartActivity()
                }
            }
        }
    }

    private fun stopSession() {
        scope.launch { runCatching { SessionBus.stop() } }
        runCatching { SubtitleSessionService.stop(this) }
    }

    private fun launchStartActivity() {
        val intent = Intent(this, SessionStartActivity::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(state: SessionBus.UiState) {
        val tile = qsTile ?: return
        tile.state = when (state.status) {
            SessionBus.Status.Starting,
            SessionBus.Status.Running,
            -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = state.message.ifBlank { null }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
