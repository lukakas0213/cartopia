package com.cartopia.app

import android.app.Application

/** Holds the one [AppCore], so the trip recorder outlives the Activity (see [TripService]). */
class CartopiaApp : Application() {

    lateinit var core: AppCore
        private set

    override fun onCreate() {
        super.onCreate()
        core = AppCore(this)
    }
}
