package com.volt.terminal

import android.content.Context
import android.provider.Settings

object DeviceHelper {

    /**
     * Returns a stable machine identifier.
     * Uses Android ID as fallback; replace with a hardware serial if available.
     */
    fun getMachineId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        return if (!androidId.isNullOrBlank()) "cm30-$androidId" else "volt-machine-01"
    }
}
