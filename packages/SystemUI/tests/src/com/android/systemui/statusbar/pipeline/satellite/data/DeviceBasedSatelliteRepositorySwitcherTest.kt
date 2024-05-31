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

package com.android.systemui.statusbar.pipeline.satellite.data

import android.telephony.TelephonyManager
import android.telephony.satellite.SatelliteManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.demomode.DemoMode
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.log.core.FakeLogBuffer
import com.android.systemui.statusbar.pipeline.satellite.data.demo.DemoDeviceBasedSatelliteDataSource
import com.android.systemui.statusbar.pipeline.satellite.data.demo.DemoDeviceBasedSatelliteRepository
import com.android.systemui.statusbar.pipeline.satellite.data.prod.DeviceBasedSatelliteRepositoryImpl
import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito.verify

@SmallTest
class DeviceBasedSatelliteRepositorySwitcherTest : SysuiTestCase() {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val demoModeController =
        mock<DemoModeController>().apply { whenever(this.isInDemoMode).thenReturn(false) }
    private val satelliteManager = mock<SatelliteManager>()
    private val telephonyManager = mock<TelephonyManager>()
    private val systemClock = FakeSystemClock()

    private val realImpl =
        DeviceBasedSatelliteRepositoryImpl(
            Optional.of(satelliteManager),
            telephonyManager,
            testDispatcher,
            testScope.backgroundScope,
            logBuffer = FakeLogBuffer.Factory.create(),
            verboseLogBuffer = FakeLogBuffer.Factory.create(),
            systemClock,
        )
    private val demoDataSource =
        mock<DemoDeviceBasedSatelliteDataSource>().also {
            whenever(it.satelliteEvents)
                .thenReturn(
                    MutableStateFlow(
                        DemoDeviceBasedSatelliteDataSource.DemoSatelliteEvent(
                            connectionState = SatelliteConnectionState.Unknown,
                            signalStrength = 0,
                        )
                    )
                )
        }
    private val demoImpl =
        DemoDeviceBasedSatelliteRepository(demoDataSource, testScope.backgroundScope)

    private val underTest =
        DeviceBasedSatelliteRepositorySwitcher(
            realImpl,
            demoImpl,
            demoModeController,
            testScope.backgroundScope,
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun switcherActiveRepo_updatesWhenDemoModeChanges() =
        testScope.runTest {
            assertThat(underTest.activeRepo.value).isSameInstanceAs(realImpl)

            val latest by collectLastValue(underTest.activeRepo)
            runCurrent()

            startDemoMode()

            assertThat(latest).isSameInstanceAs(demoImpl)

            finishDemoMode()

            assertThat(latest).isSameInstanceAs(realImpl)
        }

    private fun startDemoMode() {
        whenever(demoModeController.isInDemoMode).thenReturn(true)
        getDemoModeCallback().onDemoModeStarted()
    }

    private fun finishDemoMode() {
        whenever(demoModeController.isInDemoMode).thenReturn(false)
        getDemoModeCallback().onDemoModeFinished()
    }

    private fun getDemoModeCallback(): DemoMode {
        val captor = kotlinArgumentCaptor<DemoMode>()
        verify(demoModeController).addCallback(captor.capture())
        return captor.value
    }
}
