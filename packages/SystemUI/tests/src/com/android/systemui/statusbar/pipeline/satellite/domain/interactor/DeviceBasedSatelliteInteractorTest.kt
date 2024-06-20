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

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.filters.SmallTest
import com.android.internal.telephony.flags.Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.log.core.FakeLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.satellite.data.prod.FakeDeviceBasedSatelliteRepository
import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractorImpl
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
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
    private val connectivityRepository = FakeConnectivityRepository()
    private val wifiRepository = FakeWifiRepository()
    private val wifiInteractor =
        WifiInteractorImpl(connectivityRepository, wifiRepository, testScope.backgroundScope)

    @Before
    fun setUp() {
        underTest =
            DeviceBasedSatelliteInteractor(
                repo,
                iconsInteractor,
                wifiInteractor,
                testScope.backgroundScope,
                FakeLogBuffer.Factory.create(),
            )
    }

    @Test
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun isSatelliteAllowed_falseWhenNotAllowed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isSatelliteAllowed)

            // WHEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = false

            // THEN the interactor returns false due to the flag value
            assertThat(latest).isFalse()
        }

    @Test
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun isSatelliteAllowed_trueWhenAllowed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isSatelliteAllowed)

            // WHEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = true

            // THEN the interactor returns false due to the flag value
            assertThat(latest).isTrue()
        }

    @Test
    @DisableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun isSatelliteAllowed_offWhenFlagIsOff() =
        testScope.runTest {
            // GIVEN feature is disabled

            // Remake the interactor so the flag is read
            underTest =
                DeviceBasedSatelliteInteractor(
                    repo,
                    iconsInteractor,
                    wifiInteractor,
                    testScope.backgroundScope,
                    FakeLogBuffer.Factory.create(),
                )

            val latest by collectLastValue(underTest.isSatelliteAllowed)

            // WHEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = true

            // THEN the interactor returns false due to the flag value
            assertThat(latest).isFalse()
        }

    @Test
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
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
    @DisableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun connectionState_offWhenFeatureIsDisabled() =
        testScope.runTest {
            // GIVEN the flag is disabled

            // Remake the interactor so the flag is read
            underTest =
                DeviceBasedSatelliteInteractor(
                    repo,
                    iconsInteractor,
                    wifiInteractor,
                    testScope.backgroundScope,
                    FakeLogBuffer.Factory.create(),
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
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
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
    @DisableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun signalStrength_zeroWhenDisabled() =
        testScope.runTest {
            // GIVEN the flag is enabled

            // Remake the interactor so the flag is read
            underTest =
                DeviceBasedSatelliteInteractor(
                    repo,
                    iconsInteractor,
                    wifiInteractor,
                    testScope.backgroundScope,
                    FakeLogBuffer.Factory.create(),
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
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun areAllConnectionsOutOfService_noConnections_noDeviceEmergencyCalls_yes() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 0 connections

            // GIVEN, device is not in emergency calls only mode
            iconsInteractor.isDeviceInEmergencyCallsOnlyMode.value = false

            // THEN the value is propagated to this interactor
            assertThat(latest).isTrue()
        }

    @Test
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun areAllConnectionsOutOfService_noConnections_deviceEmergencyCalls_yes() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 0 connections

            // GIVEN, device is in emergency calls only mode
            iconsInteractor.isDeviceInEmergencyCallsOnlyMode.value = true

            // THEN the value is propagated to this interactor
            assertThat(latest).isFalse()
        }

    @Test
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun areAllConnectionsOutOfService_oneConnectionInService_thenLost_noDeviceEmergencyCalls_yes() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 1 connections
            val i1 = iconsInteractor.getMobileConnectionInteractorForSubId(1)
            // GIVEN, no device-based emergency calls
            iconsInteractor.isDeviceInEmergencyCallsOnlyMode.value = false

            // WHEN connection is in service
            i1.isInService.value = true
            i1.isEmergencyOnly.value = false
            i1.isNonTerrestrial.value = false

            // THEN we are considered NOT to be OOS
            assertThat(latest).isFalse()

            // WHEN the connection disappears
            iconsInteractor.icons.value = listOf()

            // THEN we are back to OOS
            assertThat(latest).isTrue()
        }

    @Test
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun areAllConnectionsOutOfService_oneConnectionInService_thenLost_deviceEmergencyCalls_no() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 1 connections
            val i1 = iconsInteractor.getMobileConnectionInteractorForSubId(1)
            // GIVEN, device-based emergency calls
            iconsInteractor.isDeviceInEmergencyCallsOnlyMode.value = true

            // WHEN one connection is in service
            i1.isInService.value = true
            i1.isEmergencyOnly.value = false
            i1.isNonTerrestrial.value = false

            // THEN we are considered NOT to be OOS
            assertThat(latest).isFalse()

            // WHEN the connection disappears
            iconsInteractor.icons.value = listOf()

            // THEN we are still NOT in OOS, due to device-based emergency calls
            assertThat(latest).isFalse()
        }

    @Test
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun areAllConnectionsOutOfService_twoConnectionsOos_nonNtn_noDeviceEmergencyCalls_yes() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 2 connections
            val i1 = iconsInteractor.getMobileConnectionInteractorForSubId(1)
            val i2 = iconsInteractor.getMobileConnectionInteractorForSubId(2)
            // GIVEN, no device-based emergency calls
            iconsInteractor.isDeviceInEmergencyCallsOnlyMode.value = false

            // WHEN all of the connections are OOS and none are NTN
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false
            i1.isNonTerrestrial.value = false
            i2.isInService.value = false
            i2.isEmergencyOnly.value = false
            i2.isNonTerrestrial.value = false

            // THEN the value is propagated to this interactor
            assertThat(latest).isTrue()
        }

    @Test
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun areAllConnectionsOutOfService_twoConnectionsOos_nonNtn_deviceEmergencyCalls_no() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 2 connections
            val i1 = iconsInteractor.getMobileConnectionInteractorForSubId(1)
            val i2 = iconsInteractor.getMobileConnectionInteractorForSubId(2)
            // GIVEN, device-based emergency calls
            iconsInteractor.isDeviceInEmergencyCallsOnlyMode.value = true

            // WHEN all of the connections are OOS and none are NTN
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false
            i1.isNonTerrestrial.value = false
            i2.isInService.value = false
            i2.isEmergencyOnly.value = false
            i2.isNonTerrestrial.value = false

            // THEN we are not considered OOS due to device based emergency calling
            assertThat(latest).isFalse()
        }

    @Test
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun areAllConnectionsOutOfService_twoConnectionsOos_noDeviceEmergencyCalls_oneNtn_no() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 2 connections
            val i1 = iconsInteractor.getMobileConnectionInteractorForSubId(1)
            val i2 = iconsInteractor.getMobileConnectionInteractorForSubId(2)
            // GIVEN, no device-based emergency calls
            iconsInteractor.isDeviceInEmergencyCallsOnlyMode.value = false

            // WHEN all of the connections are OOS and one is NTN
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false
            i1.isNonTerrestrial.value = false
            i2.isInService.value = false
            i2.isEmergencyOnly.value = false

            // sub2 is non terrestrial, consider it connected for the sake of the iconography
            i2.isNonTerrestrial.value = true

            // THEN the value is propagated to this interactor
            assertThat(latest).isFalse()
        }

    @Test
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun areAllConnectionsOutOfService_oneConnectionOos_noDeviceEmergencyCalls_nonNtn_yes() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 1 connection
            val i1 = iconsInteractor.getMobileConnectionInteractorForSubId(1)
            // GIVEN, no device-based emergency calls
            iconsInteractor.isDeviceInEmergencyCallsOnlyMode.value = false

            // WHEN all of the connections are OOS
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false
            i1.isNonTerrestrial.value = false

            // THEN the value is propagated to this interactor
            assertThat(latest).isTrue()
        }

    @Test
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun areAllConnectionsOutOfService_oneConnectionOos_nonNtn_no() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 1 connection
            val i1 = iconsInteractor.getMobileConnectionInteractorForSubId(1)
            // GIVEN, device-based emergency calls
            iconsInteractor.isDeviceInEmergencyCallsOnlyMode.value = true

            // WHEN all of the connections are OOS
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false
            i1.isNonTerrestrial.value = false

            // THEN the value is propagated to this interactor
            assertThat(latest).isFalse()
        }

    @Test
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun areAllConnectionsOutOfService_oneConnectionOos_ntn_no() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 1 connection
            val i1 = iconsInteractor.getMobileConnectionInteractorForSubId(1)

            // WHEN all of the connections are OOS
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false
            i1.isNonTerrestrial.value = true

            // THEN the value is propagated to this interactor
            assertThat(latest).isFalse()
        }

    @Test
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun areAllConnectionsOutOfService_oneConnectionInService_nonNtn_no() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 1 connection
            val i1 = iconsInteractor.getMobileConnectionInteractorForSubId(1)

            // WHEN all of the connections are NOT OOS
            i1.isInService.value = true
            i1.isNonTerrestrial.value = false

            // THEN the value is propagated to this interactor
            assertThat(latest).isFalse()
        }

    @Test
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun areAllConnectionsOutOfService_oneConnectionInService_ntn_no() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 1 connection
            val i1 = iconsInteractor.getMobileConnectionInteractorForSubId(1)

            // WHEN all of the connections are NOT OOS
            i1.isInService.value = true
            i1.isNonTerrestrial.value = true

            // THEN the value is propagated to this interactor
            assertThat(latest).isFalse()
        }

    @Test
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun areAllConnectionsOutOfService_twoConnectionsOneInService_nonNtn_no() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areAllConnectionsOutOfService)

            // GIVEN, 2 connection
            val i1 = iconsInteractor.getMobileConnectionInteractorForSubId(1)
            val i2 = iconsInteractor.getMobileConnectionInteractorForSubId(2)

            // WHEN at least 1 connection is NOT OOS.
            i1.isInService.value = false
            i1.isNonTerrestrial.value = false
            i2.isInService.value = true
            i2.isNonTerrestrial.value = false

            // THEN the value is propagated to this interactor
            assertThat(latest).isFalse()
        }

    @Test
    @EnableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun areAllConnectionsOutOfService_twoConnectionsInService_nonNtn_no() =
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
    @DisableFlags(FLAG_OEM_ENABLED_SATELLITE_FLAG)
    fun areAllConnectionsOutOfService_falseWhenFlagIsOff() =
        testScope.runTest {
            // GIVEN the flag is disabled

            // Remake the interactor so the flag is read
            underTest =
                DeviceBasedSatelliteInteractor(
                    repo,
                    iconsInteractor,
                    wifiInteractor,
                    testScope.backgroundScope,
                    FakeLogBuffer.Factory.create(),
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

    @Test
    fun isWifiActive_falseWhenWifiNotActive() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isWifiActive)

            // WHEN wifi is not active
            wifiRepository.setWifiNetwork(WifiNetworkModel.Invalid("test"))

            // THEN the interactor returns false due to the wifi network not being active
            assertThat(latest).isFalse()
        }

    @Test
    fun isWifiActive_trueWhenWifiIsActive() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isWifiActive)

            // WHEN wifi is active
            wifiRepository.setWifiNetwork(WifiNetworkModel.Active(networkId = 0, level = 1))

            // THEN the interactor returns true due to the wifi network being active
            assertThat(latest).isTrue()
        }
}
