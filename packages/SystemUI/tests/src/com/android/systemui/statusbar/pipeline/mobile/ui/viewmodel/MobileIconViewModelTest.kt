/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH
import com.android.settingslib.AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH_NONE
import com.android.settingslib.mobile.MobileMappings
import com.android.settingslib.mobile.TelephonyIcons.G
import com.android.settingslib.mobile.TelephonyIcons.THREE_G
import com.android.settingslib.mobile.TelephonyIcons.UNKNOWN
import com.android.systemui.Flags.FLAG_STATUS_BAR_STATIC_INOUT_INDICATORS
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.flags.Flags.NEW_NETWORK_SLICE_UI
import com.android.systemui.log.table.logcatTableLogBuffer
import com.android.systemui.res.R
import com.android.systemui.statusbar.connectivity.MobileIconCarrierIdOverridesFake
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconInteractorImpl
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorImpl
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.statusbar.policy.data.repository.FakeUserSetupRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.CarrierConfigTracker
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class MobileIconViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private var connectivityRepository = FakeConnectivityRepository()

    private lateinit var underTest: MobileIconViewModel
    private lateinit var interactor: MobileIconInteractorImpl
    private lateinit var iconsInteractor: MobileIconsInteractorImpl
    private lateinit var repository: FakeMobileConnectionRepository
    private lateinit var connectionsRepository: FakeMobileConnectionsRepository
    private lateinit var airplaneModeRepository: FakeAirplaneModeRepository
    private lateinit var airplaneModeInteractor: AirplaneModeInteractor
    @Mock private lateinit var constants: ConnectivityConstants
    private val tableLogBuffer = logcatTableLogBuffer(kosmos, "MobileIconViewModelTest")
    @Mock private lateinit var carrierConfigTracker: CarrierConfigTracker

    private val flags =
        FakeFeatureFlagsClassic().also {
            it.set(Flags.NEW_NETWORK_SLICE_UI, false)
            it.set(Flags.FILTER_PROVISIONING_NETWORK_SUBSCRIPTIONS, true)
        }
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(constants.hasDataCapabilities).thenReturn(true)

        connectionsRepository =
            FakeMobileConnectionsRepository(FakeMobileMappingsProxy(), tableLogBuffer)

        repository =
            FakeMobileConnectionRepository(SUB_1_ID, tableLogBuffer).apply {
                setNetworkTypeKey(connectionsRepository.GSM_KEY)
                isInService.value = true
                dataConnectionState.value = DataConnectionState.Connected
                dataEnabled.value = true
            }
        connectionsRepository.activeMobileDataRepository.value = repository
        connectionsRepository.mobileIsDefault.value = true

        airplaneModeRepository = FakeAirplaneModeRepository()
        airplaneModeInteractor =
            AirplaneModeInteractor(
                airplaneModeRepository,
                connectivityRepository,
                kosmos.fakeMobileConnectionsRepository,
            )

        iconsInteractor =
            MobileIconsInteractorImpl(
                connectionsRepository,
                carrierConfigTracker,
                tableLogBuffer,
                connectivityRepository,
                FakeUserSetupRepository(),
                testScope.backgroundScope,
                context,
                flags,
            )

        interactor =
            MobileIconInteractorImpl(
                testScope.backgroundScope,
                iconsInteractor.activeDataConnectionHasDataEnabled,
                iconsInteractor.alwaysShowDataRatIcon,
                iconsInteractor.alwaysUseCdmaLevel,
                iconsInteractor.isSingleCarrier,
                iconsInteractor.mobileIsDefault,
                iconsInteractor.defaultMobileIconMapping,
                iconsInteractor.defaultMobileIconGroup,
                iconsInteractor.isDefaultConnectionFailed,
                iconsInteractor.isForceHidden,
                repository,
                context,
                MobileIconCarrierIdOverridesFake()
            )
        createAndSetViewModel()
    }

    @Test
    fun isVisible_notDataCapable_alwaysFalse() =
        testScope.runTest {
            // Create a new view model here so the constants are properly read
            whenever(constants.hasDataCapabilities).thenReturn(false)
            createAndSetViewModel()

            var latest: Boolean? = null
            val job = underTest.isVisible.onEach { latest = it }.launchIn(this)

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isVisible_notAirplane_notForceHidden_true() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isVisible.onEach { latest = it }.launchIn(this)

            airplaneModeRepository.setIsAirplaneMode(false)

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun isVisible_airplaneAndNotAllowed_false() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isVisible.onEach { latest = it }.launchIn(this)

            airplaneModeRepository.setIsAirplaneMode(true)
            repository.isAllowedDuringAirplaneMode.value = false
            connectivityRepository.setForceHiddenIcons(setOf())

            assertThat(latest).isFalse()

            job.cancel()
        }

    /** Regression test for b/291993542. */
    @Test
    fun isVisible_airplaneButAllowed_true() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isVisible.onEach { latest = it }.launchIn(this)

            airplaneModeRepository.setIsAirplaneMode(true)
            repository.isAllowedDuringAirplaneMode.value = true
            connectivityRepository.setForceHiddenIcons(setOf())

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun isVisible_forceHidden_false() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isVisible.onEach { latest = it }.launchIn(this)

            airplaneModeRepository.setIsAirplaneMode(false)
            connectivityRepository.setForceHiddenIcons(setOf(ConnectivitySlot.MOBILE))

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isVisible_respondsToUpdates() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isVisible.onEach { latest = it }.launchIn(this)

            airplaneModeRepository.setIsAirplaneMode(false)
            connectivityRepository.setForceHiddenIcons(setOf())

            assertThat(latest).isTrue()

            airplaneModeRepository.setIsAirplaneMode(true)
            assertThat(latest).isFalse()

            repository.isAllowedDuringAirplaneMode.value = true
            assertThat(latest).isTrue()

            connectivityRepository.setForceHiddenIcons(setOf(ConnectivitySlot.MOBILE))
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun contentDescription_notInService_usesNoPhone() =
        testScope.runTest {
            var latest: ContentDescription? = null
            val job = underTest.contentDescription.onEach { latest = it }.launchIn(this)

            repository.isInService.value = false

            assertThat((latest as ContentDescription.Resource).res)
                .isEqualTo(PHONE_SIGNAL_STRENGTH_NONE)

            job.cancel()
        }

    @Test
    fun contentDescription_inService_usesLevel() =
        testScope.runTest {
            var latest: ContentDescription? = null
            val job = underTest.contentDescription.onEach { latest = it }.launchIn(this)

            repository.setAllLevels(2)
            assertThat((latest as ContentDescription.Resource).res)
                .isEqualTo(PHONE_SIGNAL_STRENGTH[2])

            repository.setAllLevels(0)
            assertThat((latest as ContentDescription.Resource).res)
                .isEqualTo(PHONE_SIGNAL_STRENGTH[0])

            job.cancel()
        }

    @Test
    fun contentDescription_nonInflated_invalidLevelIsNull() =
        testScope.runTest {
            val latest by collectLastValue(underTest.contentDescription)

            repository.inflateSignalStrength.value = false
            repository.setAllLevels(-1)
            assertThat(latest).isNull()

            repository.setAllLevels(100)
            assertThat(latest).isNull()
        }

    @Test
    fun contentDescription_inflated_invalidLevelIsNull() =
        testScope.runTest {
            val latest by collectLastValue(underTest.contentDescription)

            repository.inflateSignalStrength.value = true
            repository.numberOfLevels.value = 6
            repository.setAllLevels(-2)
            assertThat(latest).isNull()

            repository.setAllLevels(100)
            assertThat(latest).isNull()
        }

    @Test
    fun contentDescription_nonInflated_testABunchOfLevelsForNull() =
        testScope.runTest {
            val latest by collectLastValue(underTest.contentDescription)

            repository.inflateSignalStrength.value = false
            repository.numberOfLevels.value = 5

            // -1 and 5 are out of the bounds for non-inflated content descriptions
            for (i in -1..5) {
                repository.setAllLevels(i)
                when (i) {
                    -1,
                    5 -> assertWithMessage("Level $i is expected to be null").that(latest).isNull()
                    else ->
                        assertWithMessage("Level $i is expected not to be null")
                            .that(latest)
                            .isNotNull()
                }
            }
        }

    @Test
    fun contentDescription_inflated_testABunchOfLevelsForNull() =
        testScope.runTest {
            val latest by collectLastValue(underTest.contentDescription)
            repository.inflateSignalStrength.value = true
            repository.numberOfLevels.value = 6
            // -1 and 6 are out of the bounds for inflated content descriptions
            // Note that the interactor adds 1 to the reported level, hence the -2 to 5 range
            for (i in -2..5) {
                repository.setAllLevels(i)
                when (i) {
                    -2,
                    5 -> assertWithMessage("Level $i is expected to be null").that(latest).isNull()
                    else ->
                        assertWithMessage("Level $i is not expected to be null")
                            .that(latest)
                            .isNotNull()
                }
            }
        }

    @Test
    fun networkType_dataEnabled_groupIsRepresented() =
        testScope.runTest {
            val expected =
                Icon.Resource(
                    THREE_G.dataType,
                    ContentDescription.Resource(THREE_G.dataContentDescription)
                )
            connectionsRepository.mobileIsDefault.value = true
            repository.setNetworkTypeKey(connectionsRepository.GSM_KEY)

            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun networkType_null_whenDisabled() =
        testScope.runTest {
            repository.setNetworkTypeKey(connectionsRepository.GSM_KEY)
            repository.setDataEnabled(false)
            connectionsRepository.mobileIsDefault.value = true
            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun networkType_null_whenCarrierNetworkChangeActive() =
        testScope.runTest {
            repository.setNetworkTypeKey(connectionsRepository.GSM_KEY)
            repository.carrierNetworkChangeActive.value = true
            connectionsRepository.mobileIsDefault.value = true
            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun networkTypeIcon_notNull_whenEnabled() =
        testScope.runTest {
            val expected =
                Icon.Resource(
                    THREE_G.dataType,
                    ContentDescription.Resource(THREE_G.dataContentDescription)
                )
            repository.setNetworkTypeKey(connectionsRepository.GSM_KEY)
            repository.setDataEnabled(true)
            repository.dataConnectionState.value = DataConnectionState.Connected
            connectionsRepository.mobileIsDefault.value = true
            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun networkType_nullWhenDataDisconnects() =
        testScope.runTest {
            val initial =
                Icon.Resource(
                    THREE_G.dataType,
                    ContentDescription.Resource(THREE_G.dataContentDescription)
                )

            repository.setNetworkTypeKey(connectionsRepository.GSM_KEY)
            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(initial)

            repository.dataConnectionState.value = DataConnectionState.Disconnected

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun networkType_null_changeToDisabled() =
        testScope.runTest {
            val expected =
                Icon.Resource(
                    THREE_G.dataType,
                    ContentDescription.Resource(THREE_G.dataContentDescription)
                )
            repository.dataEnabled.value = true
            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(expected)

            repository.dataEnabled.value = false

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun networkType_alwaysShow_shownEvenWhenDisabled() =
        testScope.runTest {
            repository.dataEnabled.value = false

            connectionsRepository.defaultDataSubRatConfig.value =
                MobileMappings.Config().also { it.alwaysShowDataRatIcon = true }

            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            val expected =
                Icon.Resource(
                    THREE_G.dataType,
                    ContentDescription.Resource(THREE_G.dataContentDescription)
                )
            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun networkType_alwaysShow_shownEvenWhenDisconnected() =
        testScope.runTest {
            repository.setNetworkTypeKey(connectionsRepository.GSM_KEY)
            repository.dataConnectionState.value = DataConnectionState.Disconnected

            connectionsRepository.defaultDataSubRatConfig.value =
                MobileMappings.Config().also { it.alwaysShowDataRatIcon = true }

            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            val expected =
                Icon.Resource(
                    THREE_G.dataType,
                    ContentDescription.Resource(THREE_G.dataContentDescription)
                )
            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun networkType_alwaysShow_shownEvenWhenFailedConnection() =
        testScope.runTest {
            repository.setNetworkTypeKey(connectionsRepository.GSM_KEY)
            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.defaultDataSubRatConfig.value =
                MobileMappings.Config().also { it.alwaysShowDataRatIcon = true }

            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            val expected =
                Icon.Resource(
                    THREE_G.dataType,
                    ContentDescription.Resource(THREE_G.dataContentDescription)
                )
            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun networkType_alwaysShow_usesDefaultIconWhenInvalid() =
        testScope.runTest {
            // The UNKNOWN icon group doesn't have a valid data type icon ID, and the logic from the
            // old pipeline was to use the default icon group if the map doesn't exist
            repository.setNetworkTypeKey(UNKNOWN.name)
            connectionsRepository.defaultDataSubRatConfig.value =
                MobileMappings.Config().also { it.alwaysShowDataRatIcon = true }

            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            val expected =
                Icon.Resource(
                    connectionsRepository.defaultMobileIconGroup.value.dataType,
                    ContentDescription.Resource(G.dataContentDescription)
                )

            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun networkType_alwaysShow_shownWhenNotDefault() =
        testScope.runTest {
            repository.setNetworkTypeKey(connectionsRepository.GSM_KEY)
            connectionsRepository.mobileIsDefault.value = false
            connectionsRepository.defaultDataSubRatConfig.value =
                MobileMappings.Config().also { it.alwaysShowDataRatIcon = true }

            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            val expected =
                Icon.Resource(
                    THREE_G.dataType,
                    ContentDescription.Resource(THREE_G.dataContentDescription)
                )
            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun networkType_notShownWhenNotDefault() =
        testScope.runTest {
            repository.setNetworkTypeKey(connectionsRepository.GSM_KEY)
            repository.dataConnectionState.value = DataConnectionState.Connected
            connectionsRepository.mobileIsDefault.value = false

            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun roaming() =
        testScope.runTest {
            repository.setAllRoaming(true)

            var latest: Boolean? = null
            val job = underTest.roaming.onEach { latest = it }.launchIn(this)

            assertThat(latest).isTrue()

            repository.setAllRoaming(false)

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun dataActivity_nullWhenConfigIsOff() =
        testScope.runTest {
            // Create a new view model here so the constants are properly read
            whenever(constants.shouldShowActivityConfig).thenReturn(false)
            createAndSetViewModel()

            var inVisible: Boolean? = null
            val inJob = underTest.activityInVisible.onEach { inVisible = it }.launchIn(this)

            var outVisible: Boolean? = null
            val outJob = underTest.activityInVisible.onEach { outVisible = it }.launchIn(this)

            var containerVisible: Boolean? = null
            val containerJob =
                underTest.activityInVisible.onEach { containerVisible = it }.launchIn(this)

            repository.dataActivityDirection.value =
                DataActivityModel(
                    hasActivityIn = true,
                    hasActivityOut = true,
                )

            assertThat(inVisible).isFalse()
            assertThat(outVisible).isFalse()
            assertThat(containerVisible).isFalse()

            inJob.cancel()
            outJob.cancel()
            containerJob.cancel()
        }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_STATIC_INOUT_INDICATORS)
    fun dataActivity_configOn_testIndicators_staticFlagOff() =
        testScope.runTest {
            // Create a new view model here so the constants are properly read
            whenever(constants.shouldShowActivityConfig).thenReturn(true)
            createAndSetViewModel()

            var inVisible: Boolean? = null
            val inJob = underTest.activityInVisible.onEach { inVisible = it }.launchIn(this)

            var outVisible: Boolean? = null
            val outJob = underTest.activityOutVisible.onEach { outVisible = it }.launchIn(this)

            var containerVisible: Boolean? = null
            val containerJob =
                underTest.activityContainerVisible.onEach { containerVisible = it }.launchIn(this)

            repository.dataActivityDirection.value =
                DataActivityModel(
                    hasActivityIn = true,
                    hasActivityOut = false,
                )

            yield()

            assertThat(inVisible).isTrue()
            assertThat(outVisible).isFalse()
            assertThat(containerVisible).isTrue()

            repository.dataActivityDirection.value =
                DataActivityModel(
                    hasActivityIn = false,
                    hasActivityOut = true,
                )

            assertThat(inVisible).isFalse()
            assertThat(outVisible).isTrue()
            assertThat(containerVisible).isTrue()

            repository.dataActivityDirection.value =
                DataActivityModel(
                    hasActivityIn = false,
                    hasActivityOut = false,
                )

            assertThat(inVisible).isFalse()
            assertThat(outVisible).isFalse()
            assertThat(containerVisible).isFalse()

            inJob.cancel()
            outJob.cancel()
            containerJob.cancel()
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_STATIC_INOUT_INDICATORS)
    fun dataActivity_configOn_testIndicators_staticFlagOn() =
        testScope.runTest {
            // Create a new view model here so the constants are properly read
            whenever(constants.shouldShowActivityConfig).thenReturn(true)
            createAndSetViewModel()

            var inVisible: Boolean? = null
            val inJob = underTest.activityInVisible.onEach { inVisible = it }.launchIn(this)

            var outVisible: Boolean? = null
            val outJob = underTest.activityOutVisible.onEach { outVisible = it }.launchIn(this)

            var containerVisible: Boolean? = null
            val containerJob =
                underTest.activityContainerVisible.onEach { containerVisible = it }.launchIn(this)

            repository.dataActivityDirection.value =
                DataActivityModel(
                    hasActivityIn = true,
                    hasActivityOut = false,
                )

            yield()

            assertThat(inVisible).isTrue()
            assertThat(outVisible).isFalse()
            assertThat(containerVisible).isTrue()

            repository.dataActivityDirection.value =
                DataActivityModel(
                    hasActivityIn = false,
                    hasActivityOut = true,
                )

            assertThat(inVisible).isFalse()
            assertThat(outVisible).isTrue()
            assertThat(containerVisible).isTrue()

            repository.dataActivityDirection.value =
                DataActivityModel(
                    hasActivityIn = false,
                    hasActivityOut = false,
                )

            assertThat(inVisible).isFalse()
            assertThat(outVisible).isFalse()
            assertThat(containerVisible).isTrue()

            inJob.cancel()
            outJob.cancel()
            containerJob.cancel()
        }

    @Test
    fun netTypeBackground_flagOff_isNull() =
        testScope.runTest {
            flags.set(NEW_NETWORK_SLICE_UI, false)
            createAndSetViewModel()

            val latest by collectLastValue(underTest.networkTypeBackground)

            repository.hasPrioritizedNetworkCapabilities.value = true

            assertThat(latest).isNull()
        }

    @Test
    fun netTypeBackground_flagOn_nullWhenNoPrioritizedCapabilities() =
        testScope.runTest {
            flags.set(NEW_NETWORK_SLICE_UI, true)
            createAndSetViewModel()

            val latest by collectLastValue(underTest.networkTypeBackground)

            repository.hasPrioritizedNetworkCapabilities.value = false

            assertThat(latest).isNull()
        }

    @Test
    fun netTypeBackground_flagOn_notNullWhenPrioritizedCapabilities() =
        testScope.runTest {
            flags.set(NEW_NETWORK_SLICE_UI, true)
            createAndSetViewModel()

            val latest by collectLastValue(underTest.networkTypeBackground)

            repository.hasPrioritizedNetworkCapabilities.value = true

            assertThat(latest)
                .isEqualTo(Icon.Resource(R.drawable.mobile_network_type_background, null))
        }

    @EnableFlags(com.android.internal.telephony.flags.Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    @Test
    fun nonTerrestrial_defaultProperties() =
        testScope.runTest {
            repository.isNonTerrestrial.value = true

            val roaming by collectLastValue(underTest.roaming)
            val networkTypeIcon by collectLastValue(underTest.networkTypeIcon)
            val networkTypeBackground by collectLastValue(underTest.networkTypeBackground)
            val activityInVisible by collectLastValue(underTest.activityInVisible)
            val activityOutVisible by collectLastValue(underTest.activityOutVisible)
            val activityContainerVisible by collectLastValue(underTest.activityContainerVisible)

            assertThat(roaming).isFalse()
            assertThat(networkTypeIcon).isNull()
            assertThat(networkTypeBackground).isNull()
            assertThat(activityInVisible).isFalse()
            assertThat(activityOutVisible).isFalse()
            assertThat(activityContainerVisible).isFalse()
        }

    @EnableFlags(com.android.internal.telephony.flags.Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    @Test
    fun nonTerrestrial_ignoresDefaultProperties() =
        testScope.runTest {
            repository.isNonTerrestrial.value = true

            val roaming by collectLastValue(underTest.roaming)
            val networkTypeIcon by collectLastValue(underTest.networkTypeIcon)
            val networkTypeBackground by collectLastValue(underTest.networkTypeBackground)
            val activityInVisible by collectLastValue(underTest.activityInVisible)
            val activityOutVisible by collectLastValue(underTest.activityOutVisible)
            val activityContainerVisible by collectLastValue(underTest.activityContainerVisible)

            repository.setAllRoaming(true)
            repository.setNetworkTypeKey(connectionsRepository.LTE_KEY)
            // sets the background on cellular
            repository.hasPrioritizedNetworkCapabilities.value = true
            repository.dataActivityDirection.value =
                DataActivityModel(
                    hasActivityIn = true,
                    hasActivityOut = true,
                )

            assertThat(roaming).isFalse()
            assertThat(networkTypeIcon).isNull()
            assertThat(networkTypeBackground).isNull()
            assertThat(activityInVisible).isFalse()
            assertThat(activityOutVisible).isFalse()
            assertThat(activityContainerVisible).isFalse()
        }

    @EnableFlags(com.android.internal.telephony.flags.Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    @Test
    fun nonTerrestrial_usesSatelliteIcon() =
        testScope.runTest {
            repository.isNonTerrestrial.value = true
            repository.setAllLevels(0)

            val latest by
                collectLastValue(underTest.icon.filterIsInstance(SignalIconModel.Satellite::class))

            // Level 0 -> no connection
            assertThat(latest).isNotNull()
            assertThat(latest!!.icon.res).isEqualTo(R.drawable.ic_satellite_connected_0)

            // 1-2 -> 1 bar
            repository.setAllLevels(1)
            assertThat(latest!!.icon.res).isEqualTo(R.drawable.ic_satellite_connected_1)

            repository.setAllLevels(2)
            assertThat(latest!!.icon.res).isEqualTo(R.drawable.ic_satellite_connected_1)

            // 3-4 -> 2 bars
            repository.setAllLevels(3)
            assertThat(latest!!.icon.res).isEqualTo(R.drawable.ic_satellite_connected_2)

            repository.setAllLevels(4)
            assertThat(latest!!.icon.res).isEqualTo(R.drawable.ic_satellite_connected_2)
        }

    @EnableFlags(com.android.internal.telephony.flags.Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    @Test
    fun satelliteIcon_ignoresInflateSignalStrength() =
        testScope.runTest {
            // Note that this is the exact same test as above, but with inflateSignalStrength set to
            // true we note that the level is unaffected by inflation
            repository.inflateSignalStrength.value = true
            repository.isNonTerrestrial.value = true
            repository.setAllLevels(0)

            val latest by
                collectLastValue(underTest.icon.filterIsInstance(SignalIconModel.Satellite::class))

            // Level 0 -> no connection
            assertThat(latest).isNotNull()
            assertThat(latest!!.icon.res).isEqualTo(R.drawable.ic_satellite_connected_0)

            // 1-2 -> 1 bar
            repository.setAllLevels(1)
            assertThat(latest!!.icon.res).isEqualTo(R.drawable.ic_satellite_connected_1)

            repository.setAllLevels(2)
            assertThat(latest!!.icon.res).isEqualTo(R.drawable.ic_satellite_connected_1)

            // 3-4 -> 2 bars
            repository.setAllLevels(3)
            assertThat(latest!!.icon.res).isEqualTo(R.drawable.ic_satellite_connected_2)

            repository.setAllLevels(4)
            assertThat(latest!!.icon.res).isEqualTo(R.drawable.ic_satellite_connected_2)
        }

    private fun createAndSetViewModel() {
        underTest =
            MobileIconViewModel(
                SUB_1_ID,
                interactor,
                airplaneModeInteractor,
                constants,
                flags,
                testScope.backgroundScope,
            )
    }

    companion object {
        private const val SUB_1_ID = 1
    }
}
