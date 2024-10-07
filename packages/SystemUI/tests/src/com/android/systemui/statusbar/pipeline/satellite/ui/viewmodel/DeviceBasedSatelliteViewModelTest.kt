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

package com.android.systemui.statusbar.pipeline.satellite.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.log.core.FakeLogBuffer
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.satellite.data.prod.FakeDeviceBasedSatelliteRepository
import com.android.systemui.statusbar.pipeline.satellite.domain.interactor.DeviceBasedSatelliteInteractor
import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractorImpl
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceBasedSatelliteViewModelTest : SysuiTestCase() {
    private lateinit var underTest: DeviceBasedSatelliteViewModel
    private lateinit var interactor: DeviceBasedSatelliteInteractor
    private lateinit var airplaneModeRepository: FakeAirplaneModeRepository
    private val repo = FakeDeviceBasedSatelliteRepository()
    private val testScope = TestScope()

    private val mobileIconsInteractor = FakeMobileIconsInteractor(FakeMobileMappingsProxy(), mock())

    private val connectivityRepository = FakeConnectivityRepository()
    private val wifiRepository = FakeWifiRepository()
    private val wifiInteractor =
        WifiInteractorImpl(connectivityRepository, wifiRepository, testScope.backgroundScope)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        airplaneModeRepository = FakeAirplaneModeRepository()

        interactor =
            DeviceBasedSatelliteInteractor(
                repo,
                mobileIconsInteractor,
                wifiInteractor,
                testScope.backgroundScope,
                FakeLogBuffer.Factory.create(),
                mock(),
            )

        underTest =
            DeviceBasedSatelliteViewModelImpl(
                context,
                interactor,
                testScope.backgroundScope,
                airplaneModeRepository,
                FakeLogBuffer.Factory.create(),
                mock(),
            )
    }

    @Test
    fun icon_null_satelliteNotAllowed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            // GIVEN satellite is not allowed
            repo.isSatelliteAllowedForCurrentLocation.value = false

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // THEN icon is null because we should not be showing it
            assertThat(latest).isNull()
        }

    @Test
    fun icon_null_connectedAndNotAllowed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            // GIVEN satellite is not allowed
            repo.isSatelliteAllowedForCurrentLocation.value = false

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false

            // GIVEN satellite state is Connected. (this should not ever occur, but still)
            repo.connectionState.value = SatelliteConnectionState.Connected

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // THEN icon is null despite the connected state
            assertThat(latest).isNull()
        }

    @Test
    fun icon_null_notAllOos() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            // GIVEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = true

            // GIVEN all icons are not OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = true
            i1.isEmergencyOnly.value = false

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // THEN icon is null because we have service
            assertThat(latest).isNull()
        }

    @Test
    fun icon_null_allOosAndNotAllowed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            // GIVEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = false

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // THEN icon is null because it is not allowed
            assertThat(latest).isNull()
        }

    @Test
    fun icon_null_isEmergencyOnly() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            // GIVEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = true

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // Wait for delay to be completed
            advanceTimeBy(10.seconds)

            // THEN icon is set because we don't have service
            assertThat(latest).isInstanceOf(Icon::class.java)

            // GIVEN the connection is emergency only
            i1.isEmergencyOnly.value = true

            // THEN icon is null because we have emergency connection
            assertThat(latest).isNull()
        }

    @Test
    fun icon_null_apmIsEnabled() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            // GIVEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = true

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false

            // GIVEN apm is enabled
            airplaneModeRepository.setIsAirplaneMode(true)

            // THEN icon is null because we should not be showing it
            assertThat(latest).isNull()
        }

    @Test
    fun icon_notNull_satelliteAllowedAndAllOos() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            // GIVEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = true

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // Wait for delay to be completed
            advanceTimeBy(10.seconds)

            // THEN icon is set because we don't have service
            assertThat(latest).isInstanceOf(Icon::class.java)
        }

    @Test
    fun icon_hysteresisWhenEnablingIcon() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            // GIVEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = true

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // THEN icon is null because of the hysteresis
            assertThat(latest).isNull()

            // Wait for delay to be completed
            advanceTimeBy(10.seconds)

            // THEN icon is set after the delay
            assertThat(latest).isInstanceOf(Icon::class.java)

            // GIVEN apm is enabled
            airplaneModeRepository.setIsAirplaneMode(true)

            // THEN icon is null immediately
            assertThat(latest).isNull()
        }

    @Test
    fun icon_ignoresHysteresis_whenConnected() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            // GIVEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = true

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // GIVEN satellite reports that it is Connected
            repo.connectionState.value = SatelliteConnectionState.Connected

            // THEN icon is non null because we are connected, despite the normal OOS icon waiting
            // 10 seconds for hysteresis
            assertThat(latest).isInstanceOf(Icon::class.java)
        }

    @Test
    fun icon_ignoresHysteresis_whenOn() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            // GIVEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = true

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // GIVEN satellite reports that it is Connected
            repo.connectionState.value = SatelliteConnectionState.On

            // THEN icon is non null because the connection state is On, despite the normal OOS icon
            // waiting 10 seconds for hysteresis
            assertThat(latest).isInstanceOf(Icon::class.java)
        }

    @Test
    fun icon_satelliteIsProvisioned() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            // GIVEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = true

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // GIVEN satellite is not provisioned
            repo.isSatelliteProvisioned.value = false

            // THEN icon is null because the device is not provisioned
            assertThat(latest).isNull()

            // GIVEN satellite becomes provisioned
            repo.isSatelliteProvisioned.value = true

            // Wait for delay to be completed
            advanceTimeBy(10.seconds)

            // THEN icon is null because the device is not provisioned
            assertThat(latest).isInstanceOf(Icon::class.java)
        }

    @Test
    fun icon_wifiIsActive() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            // GIVEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = true

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // GIVEN satellite is provisioned
            repo.isSatelliteProvisioned.value = true

            // GIVEN wifi network is active
            wifiRepository.setWifiNetwork(WifiNetworkModel.Active.of(level = 1))

            // THEN icon is null because the device is connected to wifi
            assertThat(latest).isNull()

            // GIVEN device loses wifi connection
            wifiRepository.setWifiNetwork(WifiNetworkModel.Invalid("test"))

            // Wait for delay to be completed
            advanceTimeBy(10.seconds)

            // THEN icon is set because the device lost wifi connection
            assertThat(latest).isInstanceOf(Icon::class.java)
        }

    @Test
    fun carrierText_nullWhenShouldNotShow_satelliteNotAllowed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.carrierText)

            // GIVEN satellite is not allowed
            repo.isSatelliteAllowedForCurrentLocation.value = false

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // THEN carrier text is null because we should not be showing it
            assertThat(latest).isNull()
        }

    @Test
    fun carrierText_null_notAllOos() =
        testScope.runTest {
            val latest by collectLastValue(underTest.carrierText)

            // GIVEN satellite is allowed + off
            repo.isSatelliteAllowedForCurrentLocation.value = true
            repo.connectionState.value = SatelliteConnectionState.Off

            // GIVEN all icons are not OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = true
            i1.isEmergencyOnly.value = false

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // THEN carrier text is null because we have service
            assertThat(latest).isNull()
        }

    @Test
    fun carrierText_notNull_notAllOos_butConnected() =
        testScope.runTest {
            val latest by collectLastValue(underTest.carrierText)

            // GIVEN satellite is allowed + connected
            repo.isSatelliteAllowedForCurrentLocation.value = true
            repo.connectionState.value = SatelliteConnectionState.Connected

            // GIVEN all icons are not OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = true
            i1.isEmergencyOnly.value = false

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // THEN carrier text is not null, because it is connected
            // This case should never happen, but let's test it anyway
            assertThat(latest).isNotNull()
        }

    @Test
    fun carrierText_nullWhenShouldNotShow_apmIsEnabled() =
        testScope.runTest {
            val latest by collectLastValue(underTest.carrierText)

            // GIVEN satellite is allowed + connected
            repo.isSatelliteAllowedForCurrentLocation.value = true
            repo.connectionState.value = SatelliteConnectionState.Connected

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false

            // GIVEN apm is enabled
            airplaneModeRepository.setIsAirplaneMode(true)

            // THEN carrier text is null because we should not be showing it
            assertThat(latest).isNull()
        }

    @Test
    fun carrierText_satelliteIsOn() =
        testScope.runTest {
            val latest by collectLastValue(underTest.carrierText)

            // GIVEN satellite is allowed + connected
            repo.isSatelliteAllowedForCurrentLocation.value = true
            repo.connectionState.value = SatelliteConnectionState.Connected

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // Wait for delay to be completed
            advanceTimeBy(10.seconds)

            // THEN carrier text is set because we don't have service
            assertThat(latest).isNotNull()
        }

    @Test
    fun carrierText_noHysteresisWhenEnablingText_connected() =
        testScope.runTest {
            val latest by collectLastValue(underTest.carrierText)

            // GIVEN satellite is allowed + connected
            repo.isSatelliteAllowedForCurrentLocation.value = true
            repo.connectionState.value = SatelliteConnectionState.Connected

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // THEN carrier text is not null because we skip hysteresis when connected
            assertThat(latest).isNotNull()
        }

    @Test
    fun carrierText_deviceIsProvisioned() =
        testScope.runTest {
            val latest by collectLastValue(underTest.carrierText)

            // GIVEN satellite is allowed + connected
            repo.isSatelliteAllowedForCurrentLocation.value = true
            repo.connectionState.value = SatelliteConnectionState.Connected

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // GIVEN satellite is not provisioned
            repo.isSatelliteProvisioned.value = false

            // THEN carrier text is null because the device is not provisioned
            assertThat(latest).isNull()

            // GIVEN satellite becomes provisioned
            repo.isSatelliteProvisioned.value = true

            // Wait for delay to be completed
            advanceTimeBy(10.seconds)

            // THEN carrier text is null because the device is not provisioned
            assertThat(latest).isNotNull()
        }

    @Test
    fun carrierText_wifiIsActive() =
        testScope.runTest {
            val latest by collectLastValue(underTest.carrierText)

            // GIVEN satellite is allowed + connected
            repo.isSatelliteAllowedForCurrentLocation.value = true
            repo.connectionState.value = SatelliteConnectionState.Connected

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false

            // GIVEN apm is disabled
            airplaneModeRepository.setIsAirplaneMode(false)

            // GIVEN satellite is provisioned
            repo.isSatelliteProvisioned.value = true

            // GIVEN wifi network is active
            wifiRepository.setWifiNetwork(WifiNetworkModel.Active.of(level = 1))

            // THEN carrier text is null because the device is connected to wifi
            assertThat(latest).isNull()

            // GIVEN device loses wifi connection
            wifiRepository.setWifiNetwork(WifiNetworkModel.Invalid("test"))

            // Wait for delay to be completed
            advanceTimeBy(10.seconds)

            // THEN carrier text is set because the device lost wifi connection
            assertThat(latest).isNotNull()
        }

    @Test
    fun carrierText_connectionStateUnknown_usesEmergencyOnlyText() =
        testScope.runTest {
            val latest by collectLastValue(underTest.carrierText)

            // Set up the conditions for satellite to be enabled
            repo.isSatelliteAllowedForCurrentLocation.value = true
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false
            airplaneModeRepository.setIsAirplaneMode(false)

            repo.connectionState.value = SatelliteConnectionState.Unknown

            // Wait for delay to be completed
            advanceTimeBy(10.seconds)

            assertThat(latest)
                .isEqualTo(context.getString(R.string.satellite_emergency_only_carrier_text))
        }

    @Test
    fun carrierText_connectionStateOff_usesEmergencyOnlyText() =
        testScope.runTest {
            val latest by collectLastValue(underTest.carrierText)

            // Set up the conditions for satellite to be enabled
            repo.isSatelliteAllowedForCurrentLocation.value = true
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false
            airplaneModeRepository.setIsAirplaneMode(false)

            repo.connectionState.value = SatelliteConnectionState.Off

            // Wait for delay to be completed
            advanceTimeBy(10.seconds)

            assertThat(latest)
                .isEqualTo(context.getString(R.string.satellite_emergency_only_carrier_text))
        }

    @Test
    fun carrierText_connectionStateOn_notConnectedString() =
        testScope.runTest {
            val latest by collectLastValue(underTest.carrierText)

            // Set up the conditions for satellite to be enabled
            repo.isSatelliteAllowedForCurrentLocation.value = true
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false
            airplaneModeRepository.setIsAirplaneMode(false)

            repo.connectionState.value = SatelliteConnectionState.On

            // Wait for delay to be completed
            advanceTimeBy(10.seconds)

            assertThat(latest)
                .isEqualTo(context.getString(R.string.satellite_connected_carrier_text))
        }

    @Test
    fun carrierText_connectionStateConnected_connectedString() =
        testScope.runTest {
            val latest by collectLastValue(underTest.carrierText)

            // Set up the conditions for satellite to be enabled
            repo.isSatelliteAllowedForCurrentLocation.value = true
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false
            i1.isEmergencyOnly.value = false
            airplaneModeRepository.setIsAirplaneMode(false)

            repo.connectionState.value = SatelliteConnectionState.Connected

            // Wait for delay to be completed
            advanceTimeBy(10.seconds)

            assertThat(latest)
                .isEqualTo(context.getString(R.string.satellite_connected_carrier_text))
        }
}
