package com.cartopia.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts [TripService] when the car's system boots (with the ignition) or the app is updated, so a drive is
 * recorded even if the driver only opens the navigation app. Android allows a foreground service from here.
 * A car that wakes from suspend instead of booting keeps the service from before, so that case needs nothing.
 */
class StartAtBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            TripService.start(context)
        }
    }
}
