package com.cartopia.app

import android.app.Application
import com.cartopia.app.core.AppCore

/** Holds the one [AppCore], so the trip recorder outlives the Activity (see [com.cartopia.app.service.TripService]). */
class CartopiaApp : Application() {

    lateinit var core: AppCore
        private set

    override fun onCreate() {
        super.onCreate()
        core = AppCore(this)
    }
}
