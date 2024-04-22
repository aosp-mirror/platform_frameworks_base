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

package com.android.systemui.statusbar.pipeline.satellite.data.demo

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before

@SmallTest
class DemoDeviceBasedSatelliteRepositoryTest : SysuiTestCase() {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val fakeSatelliteEvents =
        MutableStateFlow(
            DemoDeviceBasedSatelliteDataSource.DemoSatelliteEvent(
                connectionState = SatelliteConnectionState.Unknown,
                signalStrength = 0,
            )
        )

    private lateinit var dataSource: DemoDeviceBasedSatelliteDataSource

    private lateinit var underTest: DemoDeviceBasedSatelliteRepository

    @Before
    fun setUp() {
        dataSource =
            mock<DemoDeviceBasedSatelliteDataSource>().also {
                whenever(it.satelliteEvents).thenReturn(fakeSatelliteEvents)
            }

        underTest = DemoDeviceBasedSatelliteRepository(dataSource, testScope.backgroundScope)
    }

    @Test
    fun startProcessing_getsNewUpdates() =
        testScope.runTest {
            val latestConnection by collectLastValue(underTest.connectionState)
            val latestSignalStrength by collectLastValue(underTest.signalStrength)

            underTest.startProcessingCommands()

            fakeSatelliteEvents.value =
                DemoDeviceBasedSatelliteDataSource.DemoSatelliteEvent(
                    connectionState = SatelliteConnectionState.On,
                    signalStrength = 3,
                )

            assertThat(latestConnection).isEqualTo(SatelliteConnectionState.On)
            assertThat(latestSignalStrength).isEqualTo(3)

            fakeSatelliteEvents.value =
                DemoDeviceBasedSatelliteDataSource.DemoSatelliteEvent(
                    connectionState = SatelliteConnectionState.Connected,
                    signalStrength = 4,
                )

            assertThat(latestConnection).isEqualTo(SatelliteConnectionState.Connected)
            assertThat(latestSignalStrength).isEqualTo(4)
        }

    @Test
    fun stopProcessing_stopsGettingUpdates() =
        testScope.runTest {
            val latestConnection by collectLastValue(underTest.connectionState)
            val latestSignalStrength by collectLastValue(underTest.signalStrength)

            underTest.startProcessingCommands()

            fakeSatelliteEvents.value =
                DemoDeviceBasedSatelliteDataSource.DemoSatelliteEvent(
                    connectionState = SatelliteConnectionState.On,
                    signalStrength = 3,
                )
            assertThat(latestConnection).isEqualTo(SatelliteConnectionState.On)
            assertThat(latestSignalStrength).isEqualTo(3)

            underTest.stopProcessingCommands()

            // WHEN new values are emitted
            fakeSatelliteEvents.value =
                DemoDeviceBasedSatelliteDataSource.DemoSatelliteEvent(
                    connectionState = SatelliteConnectionState.Connected,
                    signalStrength = 4,
                )

            // THEN they're not collected because we stopped processing commands, so the old values
            // are still present
            assertThat(latestConnection).isEqualTo(SatelliteConnectionState.On)
            assertThat(latestSignalStrength).isEqualTo(3)
        }
}
