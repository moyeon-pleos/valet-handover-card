package dev.pleos3.car

enum class Gear { P, R, N, D, UNKNOWN }

enum class WindowPos { FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT }

data class CarState(
    val gear: Gear = Gear.UNKNOWN,
    val driverSeated: Boolean? = null,
    val passengerSeated: Boolean? = null,
    val rearSeated: Boolean? = null,
    val driverDoorOpen: Boolean? = null,
    val doorsLocked: Boolean? = null,
    val windowOpen: Map<WindowPos, Boolean> = emptyMap(),
    val lowBeamOn: Boolean? = null,
    val fuelPercent: Int? = null,
    val fuelEconomyKmPerL: Float? = null,
    val odometerKm: Float? = null,
    val brakePressed: Boolean? = null,
    val telltales: Set<String> = emptySet(),
)

data class NaviPlace(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
)

data class LocFix(
    val lat: Double,
    val lon: Double,
    val confidence: Float,
    val timeMs: Long,
)
