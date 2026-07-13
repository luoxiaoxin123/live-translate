package com.livetranslate.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build

object PermissionUtils {
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notificationPermissionNeeded(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
