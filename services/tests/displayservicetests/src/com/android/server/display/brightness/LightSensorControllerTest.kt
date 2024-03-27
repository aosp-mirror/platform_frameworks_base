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

package com.android.server.display.brightness

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Handler
import androidx.test.filters.SmallTest
import com.android.internal.os.Clock
import com.android.server.display.TestUtils
import com.android.server.display.brightness.LightSensorController.Injector
import com.android.server.display.brightness.LightSensorController.LightSensorControllerConfig
import com.android.server.testutils.OffsettableClock
import com.android.server.testutils.TestHandler
import com.google.common.truth.Truth.assertThat
import kotlin.math.ceil
import kotlin.test.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FLOAT_TOLERANCE = 0.001f

@SmallTest
class LightSensorControllerTest {

    private val testHandler = TestHandler(null)
    private val testInjector = TestInjector()

    @Test
    fun `test ambient light horizon`() {
        val lightSensorController = LightSensorController(
            createLightSensorControllerConfig(
                lightSensorWarmUpTimeConfig = 0, // no warmUp time, can use first event
                brighteningLightDebounceConfig = 0,
                darkeningLightDebounceConfig = 0,
                ambientLightHorizonShort = 1000,
                ambientLightHorizonLong = 2000
            ), testInjector, testHandler)

        var reportedAmbientLux = 0f
        lightSensorController.setListener { lux ->
            reportedAmbientLux = lux
        }
        lightSensorController.enableLightSensorIfNeeded()

        assertThat(testInjector.sensorEventListener).isNotNull()

        val timeIncrement = 500L
        // set ambient lux to low
        // t = 0
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(0f))

        // t = 500
        //
        testInjector.clock.fastForward(timeIncrement)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(0f))

        // t = 1000
        testInjector.clock.fastForward(timeIncrement)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(0f))
        assertEquals(0f, reportedAmbientLux, FLOAT_TOLERANCE)

        // t = 1500
        testInjector.clock.fastForward(timeIncrement)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(0f))
        assertEquals(0f, reportedAmbientLux, FLOAT_TOLERANCE)

        // t = 2000
        // ensure that our reading is at 0.
        testInjector.clock.fastForward(timeIncrement)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(0f))
        assertEquals(0f, reportedAmbientLux, FLOAT_TOLERANCE)

        // t = 2500
        // first 10000 lux sensor event reading
        testInjector.clock.fastForward(timeIncrement)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(10_000f))
        assertTrue(reportedAmbientLux > 0f)
        assertTrue(reportedAmbientLux < 10_000f)

        // t = 3000
        // lux reading should still not yet be 10000.
        testInjector.clock.fastForward(timeIncrement)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(10_000f))
        assertTrue(reportedAmbientLux > 0)
        assertTrue(reportedAmbientLux < 10_000f)

        // t = 3500
        testInjector.clock.fastForward(timeIncrement)
        // at short horizon,  first value outside will be used in calculation (t = 2000)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(10_000f))
        assertTrue(reportedAmbientLux > 0f)
        assertTrue(reportedAmbientLux < 10_000f)

        // t = 4000
        // lux has been high (10000) for more than 1000ms.
        testInjector.clock.fastForward(timeIncrement)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(10_000f))
        assertEquals(10_000f, reportedAmbientLux, FLOAT_TOLERANCE)

        // t = 4500
        testInjector.clock.fastForward(timeIncrement)
        // short horizon is high, long horizon is high too
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(10_000f))
        assertEquals(10_000f, reportedAmbientLux, FLOAT_TOLERANCE)

        // t = 5000
        testInjector.clock.fastForward(timeIncrement)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(0f))
        assertTrue(reportedAmbientLux > 0f)
        assertTrue(reportedAmbientLux < 10_000f)

        // t = 5500
        testInjector.clock.fastForward(timeIncrement)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(0f))
        assertTrue(reportedAmbientLux > 0f)
        assertTrue(reportedAmbientLux < 10_000f)

        // t = 6000
        testInjector.clock.fastForward(timeIncrement)
        // at short horizon, first value outside will be used in calculation (t = 4500)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(0f))
        assertTrue(reportedAmbientLux > 0f)
        assertTrue(reportedAmbientLux < 10_000f)

        // t = 6500
        testInjector.clock.fastForward(timeIncrement)
        // ambient lux goes to 0
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(0f))
        assertEquals(0f, reportedAmbientLux, FLOAT_TOLERANCE)

        // only the values within the horizon should be kept
        assertArrayEquals(floatArrayOf(10_000f, 0f, 0f, 0f, 0f),
            lightSensorController.lastSensorValues, FLOAT_TOLERANCE)
        assertArrayEquals(longArrayOf(4_500, 5_000, 5_500, 6_000, 6_500),
            lightSensorController.lastSensorTimestamps)
    }

    @Test
    fun `test brightening debounce`() {
        val lightSensorController = LightSensorController(
            createLightSensorControllerConfig(
                lightSensorWarmUpTimeConfig = 0, // no warmUp time, can use first event
                brighteningLightDebounceConfig = 1500,
                ambientLightHorizonShort = 0, // only last value will be used for lux calculation
                ambientLightHorizonLong = 10_000,
                // brightening threshold is set to previous lux value
                ambientBrightnessThresholds = createHysteresisLevels(
                    brighteningThresholdLevels = floatArrayOf(),
                    brighteningThresholdsPercentages = floatArrayOf(),
                    minBrighteningThreshold = 0f
                )
            ), testInjector, testHandler)
        lightSensorController.setIdleMode(false)

        var reportedAmbientLux = 0f
        lightSensorController.setListener { lux ->
            reportedAmbientLux = lux
        }
        lightSensorController.enableLightSensorIfNeeded()

        assertThat(testInjector.sensorEventListener).isNotNull()

        // t0 (0)
        // Initial lux, initial brightening threshold
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(1200f))
        assertEquals(1200f, reportedAmbientLux, FLOAT_TOLERANCE)

        // t1 (1000)
        // Lux increase, first brightening event
        testInjector.clock.fastForward(1000)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(1800f))
        assertEquals(1200f, reportedAmbientLux, FLOAT_TOLERANCE)

        // t2 (2000) (t2 - t1 < brighteningLightDebounceConfig)
        // Lux increase, but isn't steady yet
        testInjector.clock.fastForward(1000)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(2000f))
        assertEquals(1200f, reportedAmbientLux, FLOAT_TOLERANCE)

        // t3 (3000) (t3 - t1 < brighteningLightDebounceConfig)
        // Lux increase, but isn't steady yet
        testInjector.clock.fastForward(1000)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(2200f))
        assertEquals(2200f, reportedAmbientLux, FLOAT_TOLERANCE)
    }

    @Test
    fun `test sensor readings`() {
        val ambientLightHorizonLong = 2_500
        val lightSensorController = LightSensorController(
            createLightSensorControllerConfig(
                ambientLightHorizonLong = ambientLightHorizonLong
            ), testInjector, testHandler)
        lightSensorController.setListener { }
        lightSensorController.setIdleMode(false)
        lightSensorController.enableLightSensorIfNeeded()

        // Choose values such that the ring buffer's capacity is extended and the buffer is pruned
        val increment = 11
        var lux = 5000
        for (i in 0 until 1000) {
            lux += increment
            testInjector.clock.fastForward(increment.toLong())
            testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(lux.toFloat()))
        }

        val valuesCount = ceil(ambientLightHorizonLong.toDouble() / increment + 1).toInt()
        val sensorValues = lightSensorController.lastSensorValues
        val sensorTimestamps = lightSensorController.lastSensorTimestamps

        // Only the values within the horizon should be kept
        assertEquals(valuesCount, sensorValues.size)
        assertEquals(valuesCount, sensorTimestamps.size)

        var sensorTimestamp = testInjector.clock.now()
        for (i in valuesCount - 1 downTo 1) {
            assertEquals(lux.toFloat(), sensorValues[i], FLOAT_TOLERANCE)
            assertEquals(sensorTimestamp, sensorTimestamps[i])
            lux -= increment
            sensorTimestamp -= increment
        }
        assertEquals(lux.toFloat(), sensorValues[0], FLOAT_TOLERANCE)
        assertEquals(testInjector.clock.now() - ambientLightHorizonLong, sensorTimestamps[0])
    }

    @Test
    fun `test darkening debounce`() {
        val lightSensorController = LightSensorController(
            createLightSensorControllerConfig(
                lightSensorWarmUpTimeConfig = 0, // no warmUp time, can use first event
                darkeningLightDebounceConfig = 1500,
                ambientLightHorizonShort = 0, // only last value will be used for lux calculation
                ambientLightHorizonLong = 10_000,
                // darkening threshold is set to previous lux value
                ambientBrightnessThresholds = createHysteresisLevels(
                    darkeningThresholdLevels = floatArrayOf(),
                    darkeningThresholdsPercentages = floatArrayOf(),
                    minDarkeningThreshold = 0f
                )
            ), testInjector, testHandler)

        lightSensorController.setIdleMode(false)

        var reportedAmbientLux = 0f
        lightSensorController.setListener { lux ->
            reportedAmbientLux = lux
        }
        lightSensorController.enableLightSensorIfNeeded()

        assertThat(testInjector.sensorEventListener).isNotNull()

        // t0 (0)
        // Initial lux, initial darkening threshold
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(1200f))
        assertEquals(1200f, reportedAmbientLux, FLOAT_TOLERANCE)

        // t1 (1000)
        // Lux decreased, first darkening event
        testInjector.clock.fastForward(1000)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(800f))
        assertEquals(1200f, reportedAmbientLux, FLOAT_TOLERANCE)

        // t2 (2000) (t2 - t1 < darkeningLightDebounceConfig)
        // Lux decreased, but isn't steady yet
        testInjector.clock.fastForward(1000)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(500f))
        assertEquals(1200f, reportedAmbientLux, FLOAT_TOLERANCE)

        // t3 (3000) (t3 - t1 < darkeningLightDebounceConfig)
        // Lux decreased, but isn't steady yet
        testInjector.clock.fastForward(1000)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(400f))
        assertEquals(400f, reportedAmbientLux, FLOAT_TOLERANCE)
    }

    @Test
    fun `test brightening debounce in idle mode`() {
        val lightSensorController = LightSensorController(
            createLightSensorControllerConfig(
                lightSensorWarmUpTimeConfig = 0, // no warmUp time, can use first event
                brighteningLightDebounceConfigIdle = 1500,
                ambientLightHorizonShort = 0, // only last value will be used for lux calculation
                ambientLightHorizonLong = 10_000,
                // brightening threshold is set to previous lux value
                ambientBrightnessThresholdsIdle = createHysteresisLevels(
                    brighteningThresholdLevels = floatArrayOf(),
                    brighteningThresholdsPercentages = floatArrayOf(),
                    minBrighteningThreshold = 0f
                )
            ), testInjector, testHandler)
        lightSensorController.setIdleMode(true)

        var reportedAmbientLux = 0f
        lightSensorController.setListener { lux ->
            reportedAmbientLux = lux
        }
        lightSensorController.enableLightSensorIfNeeded()

        assertThat(testInjector.sensorEventListener).isNotNull()

        // t0 (0)
        // Initial lux, initial brightening threshold
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(1200f))
        assertEquals(1200f, reportedAmbientLux, FLOAT_TOLERANCE)

        // t1 (1000)
        // Lux increase, first brightening event
        testInjector.clock.fastForward(1000)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(1800f))
        assertEquals(1200f, reportedAmbientLux, FLOAT_TOLERANCE)

        // t2 (2000) (t2 - t1 < brighteningLightDebounceConfigIdle)
        // Lux increase, but isn't steady yet
        testInjector.clock.fastForward(1000)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(2000f))
        assertEquals(1200f, reportedAmbientLux, FLOAT_TOLERANCE)

        // t3 (3000) (t3 - t1 < brighteningLightDebounceConfigIdle)
        // Lux increase, but isn't steady yet
        testInjector.clock.fastForward(1000)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(2200f))
        assertEquals(2200f, reportedAmbientLux, FLOAT_TOLERANCE)
    }

    @Test
    fun `test darkening debounce in idle mode`() {
        val lightSensorController = LightSensorController(
            createLightSensorControllerConfig(
                lightSensorWarmUpTimeConfig = 0, // no warmUp time, can use first event
                darkeningLightDebounceConfigIdle = 1500,
                ambientLightHorizonShort = 0, // only last value will be used for lux calculation
                ambientLightHorizonLong = 10_000,
                // darkening threshold is set to previous lux value
                ambientBrightnessThresholdsIdle = createHysteresisLevels(
                    darkeningThresholdLevels = floatArrayOf(),
                    darkeningThresholdsPercentages = floatArrayOf(),
                    minDarkeningThreshold = 0f
                )
            ), testInjector, testHandler)

        lightSensorController.setIdleMode(true)

        var reportedAmbientLux = 0f
        lightSensorController.setListener { lux ->
            reportedAmbientLux = lux
        }
        lightSensorController.enableLightSensorIfNeeded()

        assertThat(testInjector.sensorEventListener).isNotNull()

        // t0 (0)
        // Initial lux, initial darkening threshold
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(1200f))
        assertEquals(1200f, reportedAmbientLux, FLOAT_TOLERANCE)

        // t1 (1000)
        // Lux decreased, first darkening event
        testInjector.clock.fastForward(1000)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(800f))
        assertEquals(1200f, reportedAmbientLux, FLOAT_TOLERANCE)

        // t2 (2000) (t2 - t1 < darkeningLightDebounceConfigIdle)
        // Lux decreased, but isn't steady yet
        testInjector.clock.fastForward(1000)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(500f))
        assertEquals(1200f, reportedAmbientLux, FLOAT_TOLERANCE)

        // t3 (3000) (t3 - t1 < darkeningLightDebounceConfigIdle)
        // Lux decreased, but isn't steady yet
        testInjector.clock.fastForward(1000)
        testInjector.sensorEventListener!!.onSensorChanged(sensorEvent(400f))
        assertEquals(400f, reportedAmbientLux, FLOAT_TOLERANCE)
    }


    private fun sensorEvent(value: Float) = SensorEvent(
        testInjector.testSensor, 0, 0, floatArrayOf(value)
    )

    private class TestInjector : Injector {
        val testSensor: Sensor = TestUtils.createSensor(Sensor.TYPE_LIGHT, "Light Sensor")
        val clock: OffsettableClock = OffsettableClock.Stopped()

        var sensorEventListener: SensorEventListener? = null
        override fun getClock(): Clock {
            return object : Clock() {
                override fun uptimeMillis(): Long {
                    return clock.now()
                }
            }
        }

        override fun getLightSensor(config: LightSensorControllerConfig): Sensor {
            return testSensor
        }

        override fun registerLightSensorListener(
            listener: SensorEventListener,
            sensor: Sensor,
            rate: Int,
            handler: Handler
        ): Boolean {
            sensorEventListener = listener
            return true
        }

        override fun unregisterLightSensorListener(listener: SensorEventListener) {
            sensorEventListener = null
        }

        override fun getTag(): String {
            return "LightSensorControllerTest"
        }
    }
}