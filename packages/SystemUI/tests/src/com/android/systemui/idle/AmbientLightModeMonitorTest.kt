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

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.sensors.AsyncSensorManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.reset
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.any
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class AmbientLightModeMonitorTest : SysuiTestCase() {
    @Mock private lateinit var sensorManager: AsyncSensorManager
    @Mock private lateinit var sensor: Sensor

    private lateinit var ambientLightModeMonitor: AmbientLightModeMonitor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)).thenReturn(sensor)
        ambientLightModeMonitor = AmbientLightModeMonitor(sensorManager)
    }

    @Test
    fun shouldTriggerCallbackImmediatelyOnStart() {
        val callback = mock(AmbientLightModeMonitor.Callback::class.java)
        ambientLightModeMonitor.start(callback)

        // Monitor just started, should receive UNDECIDED.
        verify(callback).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_UNDECIDED)

        // Receives SensorEvent, and now mode is LIGHT.
        val sensorEventListener = captureSensorEventListener()
        sensorEventListener.onSensorChanged(sensorEventWithSingleValue(15f))

        // Stop monitor.
        ambientLightModeMonitor.stop()

        // Restart monitor.
        reset(callback)
        ambientLightModeMonitor.start(callback)

        // Verify receiving current mode (LIGHT) immediately.
        verify(callback).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT)
    }

    @Test
    fun shouldReportDarkModeWhenSensorValueIsLessThanTen() {
        val callback = mock(AmbientLightModeMonitor.Callback::class.java)
        ambientLightModeMonitor.start(callback)

        val sensorEventListener = captureSensorEventListener()
        reset(callback)

        sensorEventListener.onSensorChanged(sensorEventWithSingleValue(0f))
        verify(callback).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK)

        sensorEventListener.onSensorChanged(sensorEventWithSingleValue(1f))
        verify(callback).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK)

        sensorEventListener.onSensorChanged(sensorEventWithSingleValue(5f))
        verify(callback).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK)

        sensorEventListener.onSensorChanged(sensorEventWithSingleValue(9.9f))
        verify(callback).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK)
    }

    @Test
    fun shouldReportLightModeWhenSensorValueIsGreaterThanOrEqualToTen() {
        val callback = mock(AmbientLightModeMonitor.Callback::class.java)
        ambientLightModeMonitor.start(callback)

        val sensorEventListener = captureSensorEventListener()
        reset(callback)

        sensorEventListener.onSensorChanged(sensorEventWithSingleValue(10f))
        verify(callback).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT)

        sensorEventListener.onSensorChanged(sensorEventWithSingleValue(10.1f))
        verify(callback).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT)

        sensorEventListener.onSensorChanged(sensorEventWithSingleValue(15f))
        verify(callback).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT)

        sensorEventListener.onSensorChanged(sensorEventWithSingleValue(100f))
        verify(callback).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT)
    }

    @Test
    fun shouldOnlyTriggerCallbackWhenValueChanges() {
        val callback = mock(AmbientLightModeMonitor.Callback::class.java)
        ambientLightModeMonitor.start(callback)

        val sensorEventListener = captureSensorEventListener()

        // Light mode, should trigger callback.
        reset(callback)
        sensorEventListener.onSensorChanged(sensorEventWithSingleValue(20f))
        verify(callback).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT)

        // Light mode again, should NOT trigger callback.
        reset(callback)
        sensorEventListener.onSensorChanged(sensorEventWithSingleValue(25f))
        verify(callback, never()).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT)

        // Dark mode, should trigger callback.
        reset(callback)
        sensorEventListener.onSensorChanged(sensorEventWithSingleValue(2f))
        verify(callback).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK)

        // Dark mode again, should not trigger callback.
        reset(callback)
        sensorEventListener.onSensorChanged(sensorEventWithSingleValue(3f))
        verify(callback, never()).onChange(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK)
    }

    // Captures [SensorEventListener], assuming it has been registered with [sensorManager].
    private fun captureSensorEventListener(): SensorEventListener {
        val captor = ArgumentCaptor.forClass(SensorEventListener::class.java)
        verify(sensorManager).registerListener(captor.capture(), any(), anyInt())
        return captor.value
    }

    // Returns a [SensorEvent] with a single [value].
    private fun sensorEventWithSingleValue(value: Float): SensorEvent {
        return SensorEvent(sensor, 1, 1, FloatArray(1) { value })
    }
}
