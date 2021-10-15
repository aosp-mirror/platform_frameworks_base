/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.idle

import android.annotation.IntDef
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.android.systemui.util.sensors.AsyncSensorManager
import javax.inject.Inject
import kotlin.properties.Delegates

/**
 * Monitors ambient light signals, applies a debouncing algorithm, and produces the current
 * [AmbientLightMode].
 *
 * For debouncer behavior, refer to go/titan-light-sensor-debouncer.
 *
 * @property sensorManager the sensor manager used to register sensor event updates.
 */
class AmbientLightModeMonitor @Inject constructor(
    private val sensorManager: AsyncSensorManager
) {
    companion object {
        private const val TAG = "AmbientLightModeMonitor"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        const val AMBIENT_LIGHT_MODE_LIGHT = 0
        const val AMBIENT_LIGHT_MODE_DARK = 1
        const val AMBIENT_LIGHT_MODE_UNDECIDED = 2
    }

    // Light sensor used to detect ambient lighting conditions.
    private val lightSensor: Sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    // Registered callback, which gets triggered when the ambient light mode changes.
    private var callback: Callback? = null

    // Represents all ambient light modes.
    @Retention(AnnotationRetention.SOURCE)
    @IntDef(AMBIENT_LIGHT_MODE_LIGHT, AMBIENT_LIGHT_MODE_DARK, AMBIENT_LIGHT_MODE_UNDECIDED)
    annotation class AmbientLightMode

    // The current ambient light mode.
    @AmbientLightMode private var mode: Int by Delegates.observable(AMBIENT_LIGHT_MODE_UNDECIDED
    ) { _, old, new ->
        if (old != new) {
            callback?.onChange(new)
        }
    }

    /**
     * Start monitoring the current ambient light mode.
     *
     * @param callback callback that gets triggered when the ambient light mode changes. It also
     * gets triggered immediately to update the current value when this function is called.
     */
    fun start(callback: Callback) {
        if (DEBUG) Log.d(TAG, "start monitoring ambient light mode")

        if (this.callback != null) {
            if (DEBUG) Log.w(TAG, "already started")
            return
        }

        this.callback = callback
        callback.onChange(mode)

        sensorManager.registerListener(mSensorEventListener, lightSensor,
                SensorManager.SENSOR_DELAY_NORMAL)
    }

    /**
     * Stop monitoring the current ambient light mode.
     */
    fun stop() {
        if (DEBUG) Log.d(TAG, "stop monitoring ambient light mode")

        if (callback == null) {
            if (DEBUG) Log.w(TAG, "haven't started")
            return
        }

        callback = null
        sensorManager.unregisterListener(mSensorEventListener)
    }

    private val mSensorEventListener: SensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.isEmpty()) {
                if (DEBUG) Log.w(TAG, "SensorEvent doesn't have any value")
                return
            }

            // TODO(b/201657509): add debouncing logic.
            val shouldBeLowLight = event.values[0] < 10
            mode = if (shouldBeLowLight) AMBIENT_LIGHT_MODE_DARK else AMBIENT_LIGHT_MODE_LIGHT
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            // Do nothing.
        }
    }

    /**
     * Interface of the ambient light mode callback, which gets triggered when the mode changes.
     */
    interface Callback {
        fun onChange(@AmbientLightMode mode: Int)
    }
}