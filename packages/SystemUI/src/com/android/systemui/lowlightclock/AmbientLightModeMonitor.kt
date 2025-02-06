/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.lowlightclock

import android.annotation.IntDef
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.android.systemui.Dumpable
import com.android.systemui.lowlightclock.dagger.LowLightModule.LIGHT_SENSOR
import com.android.systemui.util.sensors.AsyncSensorManager
import java.io.PrintWriter
import java.util.Optional
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

/**
 * Monitors ambient light signals, applies a debouncing algorithm, and produces the current ambient
 * light mode.
 *
 * @property algorithm the debounce algorithm which transforms light sensor events into an ambient
 *   light mode.
 * @property sensorManager the sensor manager used to register sensor event updates.
 */
class AmbientLightModeMonitor
@Inject
constructor(
    private val algorithm: Optional<DebounceAlgorithm>,
    private val sensorManager: AsyncSensorManager,
    @Named(LIGHT_SENSOR) private val lightSensor: Optional<Provider<Sensor>>,
) : Dumpable {
    companion object {
        private const val TAG = "AmbientLightModeMonitor"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        const val AMBIENT_LIGHT_MODE_LIGHT = 0
        const val AMBIENT_LIGHT_MODE_DARK = 1
        const val AMBIENT_LIGHT_MODE_UNDECIDED = 2
    }

    // Represents all ambient light modes.
    @Retention(AnnotationRetention.SOURCE)
    @IntDef(AMBIENT_LIGHT_MODE_LIGHT, AMBIENT_LIGHT_MODE_DARK, AMBIENT_LIGHT_MODE_UNDECIDED)
    annotation class AmbientLightMode

    /**
     * Start monitoring the current ambient light mode.
     *
     * @param callback callback that gets triggered when the ambient light mode changes.
     */
    fun start(callback: Callback) {
        if (DEBUG) Log.d(TAG, "start monitoring ambient light mode")

        if (lightSensor.isEmpty || lightSensor.get().get() == null) {
            if (DEBUG) Log.w(TAG, "light sensor not available")
            return
        }

        if (algorithm.isEmpty) {
            if (DEBUG) Log.w(TAG, "debounce algorithm not available")
            return
        }

        algorithm.get().start(callback)
        sensorManager.registerListener(
            mSensorEventListener,
            lightSensor.get().get(),
            SensorManager.SENSOR_DELAY_NORMAL,
        )
    }

    /** Stop monitoring the current ambient light mode. */
    fun stop() {
        if (DEBUG) Log.d(TAG, "stop monitoring ambient light mode")

        if (algorithm.isPresent) {
            algorithm.get().stop()
        }
        sensorManager.unregisterListener(mSensorEventListener)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println()
        pw.println("Ambient light mode monitor:")
        pw.println("  lightSensor=$lightSensor")
        pw.println()
    }

    private val mSensorEventListener: SensorEventListener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isEmpty()) {
                    if (DEBUG) Log.w(TAG, "SensorEvent doesn't have any value")
                    return
                }

                if (algorithm.isPresent) {
                    algorithm.get().onUpdateLightSensorEvent(event.values[0])
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                // Do nothing.
            }
        }

    /** Interface of the ambient light mode callback, which gets triggered when the mode changes. */
    interface Callback {
        fun onChange(@AmbientLightMode mode: Int)
    }

    /** Interface of the algorithm that transforms light sensor events to an ambient light mode. */
    interface DebounceAlgorithm {
        // Setting Callback to nullable so mockito can verify without throwing NullPointerException.
        fun start(callback: Callback?)

        fun stop()

        fun onUpdateLightSensorEvent(value: Float)
    }
}
