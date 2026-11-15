package com.cartopia.app.drive

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How a drive turns into 코인: [PER_KM] per km driven, times the safety multiplier, plus one per [M2_PER_COIN] of new ground
 * driven along. [newM2] is that ground as shown; [bonusM2] the same with each block capped at [MAX_BONUS_M2_PER_BLOCK].
 */
data class MileBreakdown(
    val km: Double,
    val multiplier: Double,
    val newM2: Double,
    val base: Int,
    val bonus: Int,
    val total: Int,
    val withheld: Boolean
)

object Miles {
    /** A familiar commute earns too, not only exploring: 10 km is about one item. */
    const val PER_KM = 10
    /** Blocks driven along average about 8,000 m², so one earns about 2, as it did when the bonus went by block count. */
    const val M2_PER_COIN = 4000.0
    /** A park or campus beside the road would otherwise outweigh the whole drive. */
    const val MAX_BONUS_M2_PER_BLOCK = 20000.0

    fun bonusM2(blockM2: Double) = minOf(blockM2, MAX_BONUS_M2_PER_BLOCK)


    /**
     * [odometerKm] is the vehicle's own distance for the trip when available. If it disagrees with the
     * GPS distance by more than [MAX_MISMATCH] the drive earns nothing, so a spoofed location cannot farm 코인.
     */
    fun forTrip(gpsKm: Double, odometerKm: Double?, multiplier: Double, newM2: Double, bonusM2: Double = newM2): MileBreakdown {
        val km = odometerKm ?: gpsKm
        val withheld = odometerKm != null && gpsKm > 0.3 &&
            abs(gpsKm - odometerKm) / maxOf(gpsKm, odometerKm) > MAX_MISMATCH
        val base = (km * PER_KM).roundToInt()
        val bonus = (bonusM2 / M2_PER_COIN).roundToInt()
        val total = if (withheld) 0 else (base * multiplier).roundToInt() + bonus
        return MileBreakdown(km, multiplier, newM2, base, bonus, total, withheld)
    }

    private const val MAX_MISMATCH = 0.3
}
