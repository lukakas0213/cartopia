package com.cartopia.app.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltNotBuckledWarningListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Gear { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN }

/** Vehicle SDK signals the app reacts to. Every value stays at its default when the SDK is unavailable. */
class VehicleSignals(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private var started = false

    private val _gear = MutableStateFlow(Gear.UNKNOWN)
    val gear: StateFlow<Gear> = _gear.asStateFlow()

    private val _brakePressed = MutableStateFlow(false)
    val brakePressed: StateFlow<Boolean> = _brakePressed.asStateFlow()

    private val _turnSignalOn = MutableStateFlow(false)
    val turnSignalOn: StateFlow<Boolean> = _turnSignalOn.asStateFlow()

    private val _seatBeltWarning = MutableStateFlow(false)
    val seatBeltWarning: StateFlow<Boolean> = _seatBeltWarning.asStateFlow()

    /** Odometer reading in km, or null until the vehicle reports one. */
    private val _odometerKm = MutableStateFlow<Float?>(null)
    val odometerKm: StateFlow<Float?> = _odometerKm.asStateFlow()

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            _gear.value = when (gear) {
                null -> Gear.UNKNOWN
                VehicleGear.GEAR_PARK -> Gear.PARK
                VehicleGear.GEAR_REVERSE -> Gear.REVERSE
                VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
                VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
                else -> Gear.DRIVE
            }
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "Vehicle signal failed", e)
        }
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
            _brakePressed.value = pressed == true
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "Vehicle signal failed", e)
        }
    }
    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            _turnSignalOn.value = state != null && state != TurnSignalValue.OFF
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "Vehicle signal failed", e)
        }
    }
    private val seatBeltListener = object : SeatBeltNotBuckledWarningListener {
        override fun onSeatBeltNotBuckledWarningUpdated(warning: Boolean?) {
            _seatBeltWarning.value = warning == true
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "Vehicle signal failed", e)
        }
    }
    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(km: Float?) {
            _odometerKm.value = km
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "Vehicle signal failed", e)
        }
    }

    /** Returns false when the Vehicle service is missing (for example off-car), so the app falls back to demo mode. */
    fun start(): Boolean {
        if (started) return true
        return try {
            vehicle.initialize()
            vehicle.getGear().registerGearSelection(gearListener)
            vehicle.getBrake().registerBrakePedalStatus(brakeListener)
            vehicle.getTurnSignal().registerTurnSignalState(turnListener)
            vehicle.getSafety().registerSeatBeltNotBuckledWarning(driverSeat, seatBeltListener)
            vehicle.getOdometer().registerDistanceDriven(odometerListener)
            started = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "Vehicle SDK unavailable", e)
            false
        }
    }

    fun stop() {
        if (!started) return
        runCatching {
            vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.getBrake().unregisterBrakePedalStatus(brakeListener)
            vehicle.getTurnSignal().unregisterTurnSignalState(turnListener)
            vehicle.getSafety().unregisterSeatBeltNotBuckledWarning(driverSeat, seatBeltListener)
            vehicle.getOdometer().unregisterDistanceDriven(odometerListener)
            vehicle.release()
        }
        started = false
    }

    private companion object {
        const val TAG = "VehicleSignals"
    }
}
