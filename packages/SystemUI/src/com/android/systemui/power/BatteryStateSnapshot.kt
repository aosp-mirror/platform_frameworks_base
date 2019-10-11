package com.android.systemui.power

import com.android.systemui.power.PowerUI.NO_ESTIMATE_AVAILABLE

/**
 * A simple data class to snapshot battery state when a particular check for the
 * low battery warning is running in the background.
 */
data class BatteryStateSnapshot(
    val batteryLevel: Int,
    val isPowerSaver: Boolean,
    val plugged: Boolean,
    val bucket: Int,
    val batteryStatus: Int,
    val severeLevelThreshold: Int,
    val lowLevelThreshold: Int,
    val timeRemainingMillis: Long,
    val averageTimeToDischargeMillis: Long,
    val severeThresholdMillis: Long,
    val lowThresholdMillis: Long,
    val isBasedOnUsage: Boolean,
    val isLowWarningEnabled: Boolean
) {
    /**
     * Returns whether hybrid warning logic/copy should be used for this snapshot
     */
    var isHybrid: Boolean = false
        private set

    init {
        this.isHybrid = true
    }

    constructor(
        batteryLevel: Int,
        isPowerSaver: Boolean,
        plugged: Boolean,
        bucket: Int,
        batteryStatus: Int,
        severeLevelThreshold: Int,
        lowLevelThreshold: Int
    ) : this(
            batteryLevel,
            isPowerSaver,
            plugged,
            bucket,
            batteryStatus,
            severeLevelThreshold,
            lowLevelThreshold,
            NO_ESTIMATE_AVAILABLE.toLong(),
            NO_ESTIMATE_AVAILABLE.toLong(),
            NO_ESTIMATE_AVAILABLE.toLong(),
            NO_ESTIMATE_AVAILABLE.toLong(),
            false,
            true
    ) {
        this.isHybrid = false
    }
}
