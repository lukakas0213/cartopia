package com.cartopia.app.core

import com.cartopia.app.car.Gear
import com.cartopia.app.geo.MapPoint

/**
 * What the 3D page shows. The gear decides it: D drives, P saves the trip and opens decorating.
 * There is no other screen; the same map is used for both.
 */
enum class Mode(val key: String) { EDIT("edit"), DRIVE("drive"), SAVING("saving") }

/** The drive being recorded, as the drive card shows it. */
data class TripLive(
    val distanceKm: Double,
    val elapsedSec: Long,
    val score: Int,
    /** Ground this drive opened, driven along or nearby. */
    val areaM2: Double,
    val estimatedMiles: Int
)

/** Everything the Activity and [com.cartopia.app.service.TripService] observe; [AppCore.state] holds the current one. */
data class UiState(
    val loaded: Boolean = false,
    val mode: Mode = Mode.EDIT,
    val demo: Boolean = false,
    val vehicleConnected: Boolean = false,
    val gear: Gear = Gear.UNKNOWN,
    val miles: Int = 0,
    val revealed: Set<Int> = emptySet(),
    val car: MapPoint? = null,
    val trip: TripLive? = null
) {
    val driving: Boolean get() = mode == Mode.DRIVE
}
