package com.rk.calculatorvault

import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/** Opens only through the explicit rkcalc://restore recovery link. */
class RecoveryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val launcher = ComponentName(this, "$packageName.LauncherAlias")
            packageManager.setComponentEnabledSetting(
                launcher,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Toast.makeText(this, "Ikon Kalkulator ditampilkan kembali.", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            // The user can still restore the app from the Android app settings if supported by the device.
        }
        finish()
    }
}
