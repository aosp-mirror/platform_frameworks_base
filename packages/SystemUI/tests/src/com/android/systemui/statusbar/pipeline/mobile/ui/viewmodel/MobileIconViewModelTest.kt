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
import com.android.settingslib.AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH
import com.android.settingslib.AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH_NONE
import com.android.settingslib.mobile.TelephonyIcons.THREE_G
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class MobileIconViewModelTest : SysuiTestCase() {
    private lateinit var underTest: MobileIconViewModel
    private lateinit var interactor: FakeMobileIconInteractor
    private lateinit var airplaneModeRepository: FakeAirplaneModeRepository
    private lateinit var airplaneModeInteractor: AirplaneModeInteractor
    @Mock private lateinit var constants: ConnectivityConstants
    @Mock private lateinit var tableLogBuffer: TableLogBuffer

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(constants.hasDataCapabilities).thenReturn(true)

        airplaneModeRepository = FakeAirplaneModeRepository()
        airplaneModeInteractor =
            AirplaneModeInteractor(
                airplaneModeRepository,
                FakeConnectivityRepository(),
            )

        interactor = FakeMobileIconInteractor(tableLogBuffer)
        interactor.apply {
            setLevel(1)
            setIsDefaultDataEnabled(true)
            setIsFailedConnection(false)
            setIconGroup(THREE_G)
            setIsEmergencyOnly(false)
            setNumberOfLevels(4)
            isDataConnected.value = true
        }
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
            interactor.isForceHidden.value = false

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun isVisible_airplane_false() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isVisible.onEach { latest = it }.launchIn(this)

            airplaneModeRepository.setIsAirplaneMode(true)
            interactor.isForceHidden.value = false

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isVisible_forceHidden_false() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isVisible.onEach { latest = it }.launchIn(this)

            airplaneModeRepository.setIsAirplaneMode(false)
            interactor.isForceHidden.value = true

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isVisible_respondsToUpdates() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isVisible.onEach { latest = it }.launchIn(this)

            airplaneModeRepository.setIsAirplaneMode(false)
            interactor.isForceHidden.value = false

            assertThat(latest).isTrue()

            airplaneModeRepository.setIsAirplaneMode(true)
            assertThat(latest).isFalse()

            airplaneModeRepository.setIsAirplaneMode(false)
            assertThat(latest).isTrue()

            interactor.isForceHidden.value = true
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun iconId_correctLevel_notCutout() =
        testScope.runTest {
            var latest: SignalIconModel? = null
            val job = underTest.icon.onEach { latest = it }.launchIn(this)
            val expected = defaultSignal()

            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun iconId_cutout_whenDefaultDataDisabled() =
        testScope.runTest {
            interactor.setIsDefaultDataEnabled(false)

            var latest: SignalIconModel? = null
            val job = underTest.icon.onEach { latest = it }.launchIn(this)
            val expected = defaultSignal(level = 1, connected = false)

            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun `icon - uses empty state - when not in service`() =
        testScope.runTest {
            var latest: SignalIconModel? = null
            val job = underTest.icon.onEach { latest = it }.launchIn(this)

            interactor.isInService.value = false

            var expected = emptySignal()

            assertThat(latest).isEqualTo(expected)

            // Changing the level doesn't overwrite the disabled state
            interactor.level.value = 2
            assertThat(latest).isEqualTo(expected)

            // Once back in service, the regular icon appears
            interactor.isInService.value = true
            expected = defaultSignal(level = 2)
            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun contentDescription_notInService_usesNoPhone() =
        testScope.runTest {
            var latest: ContentDescription? = null
            val job = underTest.contentDescription.onEach { latest = it }.launchIn(this)

            interactor.isInService.value = false

            assertThat((latest as ContentDescription.Resource).res)
                .isEqualTo(PHONE_SIGNAL_STRENGTH_NONE)

            job.cancel()
        }

    @Test
    fun contentDescription_inService_usesLevel() =
        testScope.runTest {
            var latest: ContentDescription? = null
            val job = underTest.contentDescription.onEach { latest = it }.launchIn(this)

            interactor.isInService.value = true

            interactor.level.value = 2
            assertThat((latest as ContentDescription.Resource).res)
                .isEqualTo(PHONE_SIGNAL_STRENGTH[2])

            interactor.level.value = 0
            assertThat((latest as ContentDescription.Resource).res)
                .isEqualTo(PHONE_SIGNAL_STRENGTH[0])

            job.cancel()
        }

    @Test
    fun networkType_dataEnabled_groupIsRepresented() =
        testScope.runTest {
            val expected =
                Icon.Resource(
                    THREE_G.dataType,
                    ContentDescription.Resource(THREE_G.dataContentDescription)
                )
            interactor.setIconGroup(THREE_G)

            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun networkType_nullWhenDisabled() =
        testScope.runTest {
            interactor.setIconGroup(THREE_G)
            interactor.setIsDataEnabled(false)
            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun networkType_nullWhenFailedConnection() =
        testScope.runTest {
            interactor.setIconGroup(THREE_G)
            interactor.setIsDataEnabled(true)
            interactor.setIsFailedConnection(true)
            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest).isNull()

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

            interactor.setIconGroup(THREE_G)
            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            interactor.setIconGroup(THREE_G)
            assertThat(latest).isEqualTo(initial)

            interactor.isDataConnected.value = false
            yield()

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
            interactor.setIconGroup(THREE_G)
            interactor.setIsDataEnabled(true)
            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(expected)

            interactor.setIsDataEnabled(false)
            yield()

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun networkType_alwaysShow_shownEvenWhenDisabled() =
        testScope.runTest {
            interactor.setIconGroup(THREE_G)
            interactor.setIsDataEnabled(true)
            interactor.alwaysShowDataRatIcon.value = true

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
            interactor.setIconGroup(THREE_G)
            interactor.isDataConnected.value = false
            interactor.alwaysShowDataRatIcon.value = true

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
            interactor.setIconGroup(THREE_G)
            interactor.setIsFailedConnection(true)
            interactor.alwaysShowDataRatIcon.value = true

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
    fun `network type - alwaysShow - shown when not connected`() =
        testScope.runTest {
            interactor.setIconGroup(THREE_G)
            interactor.isConnected.value = false
            interactor.alwaysShowDataRatIcon.value = true

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
    fun `network type - not shown when not connected`() =
        testScope.runTest {
            interactor.setIconGroup(THREE_G)
            interactor.isDataConnected.value = true
            interactor.isConnected.value = false

            var latest: Icon? = null
            val job = underTest.networkTypeIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun roaming() =
        testScope.runTest {
            interactor.isRoaming.value = true
            var latest: Boolean? = null
            val job = underTest.roaming.onEach { latest = it }.launchIn(this)

            assertThat(latest).isTrue()

            interactor.isRoaming.value = false

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun `data activity - null when config is off`() =
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

            interactor.activity.value =
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
    fun `data activity - config on - test indicators`() =
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

            interactor.activity.value =
                DataActivityModel(
                    hasActivityIn = true,
                    hasActivityOut = false,
                )

            yield()

            assertThat(inVisible).isTrue()
            assertThat(outVisible).isFalse()
            assertThat(containerVisible).isTrue()

            interactor.activity.value =
                DataActivityModel(
                    hasActivityIn = false,
                    hasActivityOut = true,
                )

            assertThat(inVisible).isFalse()
            assertThat(outVisible).isTrue()
            assertThat(containerVisible).isTrue()

            interactor.activity.value =
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

    private fun createAndSetViewModel() {
        underTest = MobileIconViewModel(
            SUB_1_ID,
            interactor,
            airplaneModeInteractor,
            constants,
            testScope.backgroundScope,
        )
    }

    companion object {
        private const val SUB_1_ID = 1

        /** Convenience constructor for these tests */
        fun defaultSignal(
            level: Int = 1,
            connected: Boolean = true,
        ): SignalIconModel {
            return SignalIconModel(level, numberOfLevels = 4, showExclamationMark = !connected)
        }

        fun emptySignal(): SignalIconModel =
            SignalIconModel(level = 0, numberOfLevels = 4, showExclamationMark = true)
    }
}
