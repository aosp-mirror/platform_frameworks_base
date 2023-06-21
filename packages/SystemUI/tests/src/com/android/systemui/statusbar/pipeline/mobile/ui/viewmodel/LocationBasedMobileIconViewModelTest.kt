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

import androidx.test.filters.SmallTest
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconViewModelTest.Companion.defaultSignal
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
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
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class LocationBasedMobileIconViewModelTest : SysuiTestCase() {
    private lateinit var commonImpl: MobileIconViewModelCommon
    private lateinit var homeIcon: HomeMobileIconViewModel
    private lateinit var qsIcon: QsMobileIconViewModel
    private lateinit var keyguardIcon: KeyguardMobileIconViewModel
    private lateinit var interactor: FakeMobileIconInteractor
    private lateinit var airplaneModeInteractor: AirplaneModeInteractor
    @Mock private lateinit var statusBarPipelineFlags: StatusBarPipelineFlags
    @Mock private lateinit var constants: ConnectivityConstants
    @Mock private lateinit var tableLogBuffer: TableLogBuffer

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        airplaneModeInteractor =
            AirplaneModeInteractor(
                FakeAirplaneModeRepository(),
                FakeConnectivityRepository(),
            )
        interactor = FakeMobileIconInteractor(tableLogBuffer)
        interactor.apply {
            setLevel(1)
            setIsDefaultDataEnabled(true)
            setIsFailedConnection(false)
            setIconGroup(TelephonyIcons.THREE_G)
            setIsEmergencyOnly(false)
            setNumberOfLevels(4)
            isDataConnected.value = true
        }
        commonImpl =
            MobileIconViewModel(
                SUB_1_ID,
                interactor,
                airplaneModeInteractor,
                constants,
                testScope.backgroundScope,
            )

        homeIcon = HomeMobileIconViewModel(commonImpl, statusBarPipelineFlags, mock())
        qsIcon = QsMobileIconViewModel(commonImpl, statusBarPipelineFlags)
        keyguardIcon = KeyguardMobileIconViewModel(commonImpl, statusBarPipelineFlags)
    }

    @Test
    fun `location based view models receive same icon id when common impl updates`() =
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

            interactor.setLevel(2)
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
    }
}
