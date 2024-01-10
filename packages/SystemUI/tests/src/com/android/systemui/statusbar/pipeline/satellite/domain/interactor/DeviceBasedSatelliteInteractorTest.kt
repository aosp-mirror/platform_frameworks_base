/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.satellite.domain.interactor

import androidx.test.filters.SmallTest
import com.android.internal.telephony.flags.Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.satellite.data.prod.FakeDeviceBasedSatelliteRepository
import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before

@SmallTest
class DeviceBasedSatelliteInteractorTest : SysuiTestCase() {
    private lateinit var underTest: DeviceBasedSatelliteInteractor

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private val iconsInteractor =
        FakeMobileIconsInteractor(
            FakeMobileMappingsProxy(),
            mock(),
        )

    private val repo = FakeDeviceBasedSatelliteRepository()

    @Before
    fun setUp() {
        mSetFlagsRule.enableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)

        underTest =
            DeviceBasedSatelliteInteractor(
                repo,
                iconsInteractor,
                testScope.backgroundScope,
            )
    }

    @Test
    fun isSatelliteAllowed_falseWhenNotAllowed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isSatelliteAllowed)

            // WHEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = false

            // THEN the interactor returns false due to the flag value
            assertThat(latest).isFalse()
        }

    @Test
    fun isSatelliteAllowed_trueWhenAllowed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isSatelliteAllowed)

            // WHEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = true

            // THEN the interactor returns false due to the flag value
            assertThat(latest).isTrue()
        }

    @Test
    fun isSatelliteAllowed_offWhenFlagIsOff() =
        testScope.runTest {
            // GIVEN feature is disabled
            mSetFlagsRule.disableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)

            // Remake the interactor so the flag is read
            underTest =
                DeviceBasedSatelliteInteractor(
                    repo,
                    iconsInteractor,
                    testScope.backgroundScope,
                )

            val latest by collectLastValue(underTest.isSatelliteAllowed)

            // WHEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = true

            // THEN the interactor returns false due to the flag value
            assertThat(latest).isFalse()
        }

    @Test
    fun connectionState_matchesRepositoryValue() =
        testScope.runTest {
            val latest by collectLastValue(underTest.connectionState)

            // Off
            repo.connectionState.value = SatelliteConnectionState.Off
            assertThat(latest).isEqualTo(SatelliteConnectionState.Off)

            // On
            repo.connectionState.value = SatelliteConnectionState.On
            assertThat(latest).isEqualTo(SatelliteConnectionState.On)

            // Connected
            repo.connectionState.value = SatelliteConnectionState.Connected
            assertThat(latest).isEqualTo(SatelliteConnectionState.Connected)

            // Unknown
            repo.connectionState.value = SatelliteConnectionState.Unknown
            assertThat(latest).isEqualTo(SatelliteConnectionState.Unknown)
        }

    @Test
    fun connectionState_offWhenFeatureIsDisabled() =
        testScope.runTest {
            // GIVEN the flag is disabled
            mSetFlagsRule.disableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)

            // Remake the interactor so the flag is read
            underTest =
                DeviceBasedSatelliteInteractor(
                    repo,
                    iconsInteractor,
                    testScope.backgroundScope,
                )

            val latest by collectLastValue(underTest.connectionState)

            // THEN the state is always Off, regardless of status in system_server

            // Off
            repo.connectionState.value = SatelliteConnectionState.Off
            assertThat(latest).isEqualTo(SatelliteConnectionState.Off)

            // On
            repo.connectionState.value = SatelliteConnectionState.On
            assertThat(latest).isEqualTo(SatelliteConnectionState.Off)

            // Connected
            repo.connectionState.value = SatelliteConnectionState.Connected
            assertThat(latest).isEqualTo(SatelliteConnectionState.Off)

            // Unknown
            repo.connectionState.value = SatelliteConnectionState.Unknown
            assertThat(latest).isEqualTo(SatelliteConnectionState.Off)
        }

    @Test
    fun signalStrength_matchesRepo() =
        testScope.runTest {
            val latest by collectLastValue(underTest.signalStrength)

            repo.signalStrength.value = 1
            assertThat(latest).isEqualTo(1)

            repo.signalStrength.value = 2
            assertThat(latest).isEqualTo(2)

            repo.signalStrength.value = 3
            assertThat(latest).isEqualTo(3)

            repo.signalStrength.value = 4
            assertThat(latest).isEqualTo(4)
        }

    @Test
    fun signalStrength_zeroWhenDisabled() =
        testScope.runTest {
            // GIVEN the flag is enabled
            mSetFlagsRule.disableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)

            // Remake the interactor so the flag is read
            underTest =
                DeviceBasedSatelliteInteractor(
                    repo,
                    iconsInteractor,
                    testScope.backgroundScope,
                )

            val latest by collectLastValue(underTest.signalStrength)

            // THEN the value is always 0, regardless of what the system says
            repo.signalStrength.value = 1
            assertThat(latest).isEqualTo(0)

            repo.signalStrength.value = 2
            assertThat(latest).isEqualTo(0)

            repo.signalStrength.value = 3
            assertThat(latest).isEqualTo(0)

            repo.signalStrength.value = 4
            assertThat(latest).isEqualTo(0)
        }

    @Test
    fun areAllConnectionsOutOfService_twoConnectionsOos_yes() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 2 connections
            val i1 = iconsInteractor.getMobileConnectionInteractorForSubId(1)
            val i2 = iconsInteractor.getMobileConnectionInteractorForSubId(2)

            // WHEN all of the connections are OOS
            i1.isInService.value = false
            i2.isInService.value = false

            // THEN the value is propagated to this interactor
            assertThat(latest).isTrue()
        }

    @Test
    fun areAllConnectionsOutOfService_oneConnectionOos_yes() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 1 connection
            val i1 = iconsInteractor.getMobileConnectionInteractorForSubId(1)

            // WHEN all of the connections are OOS
            i1.isInService.value = false

            // THEN the value is propagated to this interactor
            assertThat(latest).isTrue()
        }

    @Test
    fun areAllConnectionsOutOfService_oneConnectionInService_no() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 1 connection
            val i1 = iconsInteractor.getMobileConnectionInteractorForSubId(1)

            // WHEN all of the connections are NOT OOS
            i1.isInService.value = true

            // THEN the value is propagated to this interactor
            assertThat(latest).isFalse()
        }

    @Test
    fun areAllConnectionsOutOfService_twoConnectionsOneInService_no() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 2 connection
            val i1 = iconsInteractor.getMobileConnectionInteractorForSubId(1)
            val i2 = iconsInteractor.getMobileConnectionInteractorForSubId(2)

            // WHEN at least 1 connection is NOT OOS.
            i1.isInService.value = false
            i2.isInService.value = true

            // THEN the value is propagated to this interactor
            assertThat(latest).isFalse()
        }

    @Test
    fun areAllConnectionsOutOfService_twoConnectionsInService_no() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 2 connection
            val i1 = iconsInteractor.getMobileConnectionInteractorForSubId(1)
            val i2 = iconsInteractor.getMobileConnectionInteractorForSubId(1)

            // WHEN all connections are NOT OOS.
            i1.isInService.value = true
            i2.isInService.value = true

            // THEN the value is propagated to this interactor
            assertThat(latest).isFalse()
        }

    @Test
    fun areAllConnectionsOutOfService_falseWhenFlagIsOff() =
        testScope.runTest {
            // GIVEN the flag is disabled
            mSetFlagsRule.disableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)

            // Remake the interactor so the flag is read
            underTest =
                DeviceBasedSatelliteInteractor(
                    repo,
                    iconsInteractor,
                    testScope.backgroundScope,
                )

            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN a condition that should return true (all conections OOS)

            val i1 = iconsInteractor.getMobileConnectionInteractorForSubId(1)
            val i2 = iconsInteractor.getMobileConnectionInteractorForSubId(1)

            i1.isInService.value = true
            i2.isInService.value = true

            // THEN the value is still false, because the flag is off
            assertThat(latest).isFalse()
        }
}
