package com.cartopia.app.car

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.util.Log
import com.cartopia.app.geo.GeoPoint

/** Live vehicle position from the Pleos FusedLocation SDK. */
class PleosLocationFeed(context: Context) : LocationFeed {

    private val fused = FusedLocation(context.applicationContext)
    private var listener: FusedLocationListener? = null

    fun isAvailable(): Boolean = runCatching { fused.isSupported() }.getOrDefault(false)

    override fun start(onFix: (GeoPoint) -> Unit, onEnd: () -> Unit) {
        val l = object : FusedLocationListener {
            override fun onChanged(data: LocationData?) {
                if (data == null) return
                onFix(GeoPoint(data.latitude, data.longitude, data.timestampMillis))
            }

            override fun onError(e: Exception) {
                Log.w(TAG, "FusedLocation error", e)
            }
        }
        listener = l
        runCatching {
            fused.initialize()
            fused.registerFusedLocationCallback(l)
        }.onFailure { Log.w(TAG, "FusedLocation unavailable", it) }
    }

    override fun stop() {
        listener?.let { l -> runCatching { fused.unregisterFusedLocationCallback(l) } }
        listener = null
        runCatching { fused.release() }
    }

    private companion object {
        const val TAG = "PleosLocationFeed"
    }
}
