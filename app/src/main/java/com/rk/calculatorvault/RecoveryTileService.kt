package com.rk.calculatorvault

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Optional Quick Settings entry point for restoring/opening the calculator after hiding its launcher icon. */
class RecoveryTileService : TileService() {
    override fun onClick() {
        super.onClick()
        try {
            val launcher = ComponentName(this, "$packageName.LauncherAlias")
            packageManager.setComponentEnabledSetting(
                launcher,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            qsTile?.state = Tile.STATE_ACTIVE
            qsTile?.updateTile()
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                val pending = PendingIntent.getActivity(
                    this,
                    913,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
            // Recovery URI rkcalc://restore is a second route if a launcher does not support this tile.
        }
    }
}
