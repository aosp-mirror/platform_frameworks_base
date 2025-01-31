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

import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.sensors.AsyncSensorManager
import java.util.Optional
import javax.inject.Provider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class AmbientLightModeMonitorTest : SysuiTestCase() {
    @Mock private lateinit var sensorManager: AsyncSensorManager
    @Mock private lateinit var sensor: Sensor
    @Mock private lateinit var algorithm: AmbientLightModeMonitor.DebounceAlgorithm

    private lateinit var ambientLightModeMonitor: AmbientLightModeMonitor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        ambientLightModeMonitor =
            AmbientLightModeMonitor(
                Optional.of(algorithm),
                sensorManager,
                Optional.of(Provider { sensor }),
            )
    }

    @Test
    fun shouldRegisterSensorEventListenerOnStart() {
        val callback = mock(AmbientLightModeMonitor.Callback::class.java)
        ambientLightModeMonitor.start(callback)

        verify(sensorManager).registerListener(any(), eq(sensor), anyInt())
    }

    @Test
    fun shouldUnregisterSensorEventListenerOnStop() {
        val callback = mock(AmbientLightModeMonitor.Callback::class.java)
        ambientLightModeMonitor.start(callback)

        val sensorEventListener = captureSensorEventListener()

        ambientLightModeMonitor.stop()

        verify(sensorManager).unregisterListener(eq(sensorEventListener))
    }

    @Test
    fun shouldStartDebounceAlgorithmOnStart() {
        val callback = mock(AmbientLightModeMonitor.Callback::class.java)
        ambientLightModeMonitor.start(callback)

        verify(algorithm).start(eq(callback))
    }

    @Test
    fun shouldStopDebounceAlgorithmOnStop() {
        val callback = mock(AmbientLightModeMonitor.Callback::class.java)
        ambientLightModeMonitor.start(callback)
        ambientLightModeMonitor.stop()

        verify(algorithm).stop()
    }

    @Test
    fun shouldNotRegisterForSensorUpdatesIfSensorNotAvailable() {
        val ambientLightModeMonitor =
            AmbientLightModeMonitor(Optional.of(algorithm), sensorManager, Optional.empty())

        val callback = mock(AmbientLightModeMonitor.Callback::class.java)
        ambientLightModeMonitor.start(callback)

        verify(sensorManager, never()).registerListener(any(), any(Sensor::class.java), anyInt())
    }

    // Captures [SensorEventListener], assuming it has been registered with [sensorManager].
    private fun captureSensorEventListener(): SensorEventListener {
        val captor = ArgumentCaptor.forClass(SensorEventListener::class.java)
        verify(sensorManager).registerListener(captor.capture(), any(), anyInt())
        return captor.value
    }
}
