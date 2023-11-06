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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.connectivity.MobileIconCarrierIdOverridesFake
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeUserSetupRepository
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconInteractorImpl
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorImpl
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.util.CarrierConfigTracker
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class LocationBasedMobileIconViewModelTest : SysuiTestCase() {
    private lateinit var commonImpl: MobileIconViewModelCommon
    private lateinit var homeIcon: HomeMobileIconViewModel
    private lateinit var qsIcon: QsMobileIconViewModel
    private lateinit var keyguardIcon: KeyguardMobileIconViewModel
    private lateinit var iconsInteractor: MobileIconsInteractor
    private lateinit var interactor: MobileIconInteractor
    private lateinit var connectionsRepository: FakeMobileConnectionsRepository
    private lateinit var repository: FakeMobileConnectionRepository
    private lateinit var airplaneModeInteractor: AirplaneModeInteractor

    private val connectivityRepository = FakeConnectivityRepository()
    private val flags =
        FakeFeatureFlagsClassic().also {
            it.set(Flags.NEW_NETWORK_SLICE_UI, false)
            it.set(Flags.FILTER_PROVISIONING_NETWORK_SUBSCRIPTIONS, true)
        }

    @Mock private lateinit var statusBarPipelineFlags: StatusBarPipelineFlags
    @Mock private lateinit var constants: ConnectivityConstants
    @Mock private lateinit var tableLogBuffer: TableLogBuffer
    @Mock private lateinit var carrierConfigTracker: CarrierConfigTracker

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        airplaneModeInteractor =
            AirplaneModeInteractor(
                FakeAirplaneModeRepository(),
                FakeConnectivityRepository(),
                FakeMobileConnectionsRepository(),
            )
        connectionsRepository =
            FakeMobileConnectionsRepository(FakeMobileMappingsProxy(), tableLogBuffer)
        repository =
            FakeMobileConnectionRepository(SUB_1_ID, tableLogBuffer).apply {
                isInService.value = true
                cdmaLevel.value = 1
                primaryLevel.value = 1
                isEmergencyOnly.value = false
                numberOfLevels.value = 4
                resolvedNetworkType.value = ResolvedNetworkType.DefaultNetworkType(lookupKey = "3G")
                dataConnectionState.value = DataConnectionState.Connected
            }

        connectionsRepository.activeMobileDataRepository.value = repository

        connectivityRepository.apply { setMobileConnected() }

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

        commonImpl =
            MobileIconViewModel(
                SUB_1_ID,
                interactor,
                airplaneModeInteractor,
                constants,
                flags,
                testScope.backgroundScope,
            )

        homeIcon = HomeMobileIconViewModel(commonImpl, mock())
        qsIcon = QsMobileIconViewModel(commonImpl)
        keyguardIcon = KeyguardMobileIconViewModel(commonImpl)
    }

    @Test
    fun locationBasedViewModelsReceiveSameIconIdWhenCommonImplUpdates() =
        testScope.runTest {
            var latestHome: SignalIconModel? = null
            val homeJob = homeIcon.icon.onEach { latestHome = it }.launchIn(this)

            var latestQs: SignalIconModel? = null
            val qsJob = qsIcon.icon.onEach { latestQs = it }.launchIn(this)

            var latestKeyguard: SignalIconModel? = null
            val keyguardJob = keyguardIcon.icon.onEach { latestKeyguard = it }.launchIn(this)

            var expected = defaultSignal(level = 1)

            assertThat(latestHome).isEqualTo(expected)
            assertThat(latestQs).isEqualTo(expected)
            assertThat(latestKeyguard).isEqualTo(expected)

            repository.setAllLevels(2)
            expected = defaultSignal(level = 2)

            assertThat(latestHome).isEqualTo(expected)
            assertThat(latestQs).isEqualTo(expected)
            assertThat(latestKeyguard).isEqualTo(expected)

            homeJob.cancel()
            qsJob.cancel()
            keyguardJob.cancel()
        }

    companion object {
        private const val SUB_1_ID = 1
        private const val NUM_LEVELS = 4

        /** Convenience constructor for these tests */
        fun defaultSignal(level: Int = 1): SignalIconModel {
            return SignalIconModel(
                level,
                NUM_LEVELS,
                showExclamationMark = false,
                carrierNetworkChange = false,
            )
        }
    }
}
