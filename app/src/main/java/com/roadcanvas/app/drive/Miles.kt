package com.roadcanvas.app.drive

import kotlin.math.abs
import kotlin.math.roundToInt

/** How a drive turns into 마일: 1 per km driven, times the safety multiplier, plus 2 per newly opened block. */
data class MileBreakdown(
    val km: Double,
    val multiplier: Double,
    val newBlocks: Int,
    val base: Int,
    val bonus: Int,
    val total: Int,
    val withheld: Boolean
)

object Miles {
    const val PER_NEW_BLOCK = 2

    /**
     * [odometerKm] is the vehicle's own distance for the trip when available. If it disagrees with the
     * GPS distance by more than [MAX_MISMATCH] the drive earns nothing, so a spoofed location cannot farm 마일.
     */
    fun forTrip(gpsKm: Double, odometerKm: Double?, multiplier: Double, newBlocks: Int): MileBreakdown {
        val km = odometerKm ?: gpsKm
        val withheld = odometerKm != null && gpsKm > 0.3 &&
            abs(gpsKm - odometerKm) / maxOf(gpsKm, odometerKm) > MAX_MISMATCH
        val base = km.roundToInt()
        val bonus = newBlocks * PER_NEW_BLOCK
        val total = if (withheld) 0 else (base * multiplier).roundToInt() + bonus
        return MileBreakdown(km, multiplier, newBlocks, base, bonus, total, withheld)
    }

    private const val MAX_MISMATCH = 0.3
}
