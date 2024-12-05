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
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Expandable
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig.OnTriggeredResult
import com.android.systemui.res.R
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class HomeControlsKeyguardQuickAffordanceConfigTest : SysuiTestCase() {

    @Mock private lateinit var component: ControlsComponent
    @Mock private lateinit var expandable: Expandable

    private lateinit var underTest: HomeControlsKeyguardQuickAffordanceConfig

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(component.canShowWhileLockedSetting).thenReturn(MutableStateFlow(true))

        underTest =
            HomeControlsKeyguardQuickAffordanceConfig(context = context, component = component)
    }

    @Test
    fun state_whenCannotShowWhileLocked_returnsHidden() = runTest(UnconfinedTestDispatcher()) {
        whenever(component.canShowWhileLockedSetting).thenReturn(MutableStateFlow(false))
        whenever(component.isEnabled()).thenReturn(true)
        whenever(component.getTileImageId()).thenReturn(R.drawable.controls_icon)
        whenever(component.getTileTitleId()).thenReturn(R.string.quick_controls_title)
        val controlsController = mock<ControlsController>()
        whenever(component.getControlsController()).thenReturn(Optional.of(controlsController))
        whenever(component.getControlsListingController()).thenReturn(Optional.empty())
        whenever(component.getVisibility()).thenReturn(ControlsComponent.Visibility.AVAILABLE)
        whenever(controlsController.getFavorites()).thenReturn(listOf(mock()))

        val values = mutableListOf<KeyguardQuickAffordanceConfig.LockScreenState>()
        val job = underTest.lockScreenState.onEach(values::add).launchIn(this)

        assertThat(values.last())
            .isInstanceOf(KeyguardQuickAffordanceConfig.LockScreenState.Hidden::class.java)
        job.cancel()
    }

    @Test
    fun state_whenListingControllerIsMissing_returnsHidden() = runTest(UnconfinedTestDispatcher()) {
        whenever(component.isEnabled()).thenReturn(true)
        whenever(component.getTileImageId()).thenReturn(R.drawable.controls_icon)
        whenever(component.getTileTitleId()).thenReturn(R.string.quick_controls_title)
        val controlsController = mock<ControlsController>()
        whenever(component.getControlsController()).thenReturn(Optional.of(controlsController))
        whenever(component.getControlsListingController()).thenReturn(Optional.empty())
        whenever(component.getVisibility()).thenReturn(ControlsComponent.Visibility.AVAILABLE)
        whenever(controlsController.getFavorites()).thenReturn(listOf(mock()))

        val values = mutableListOf<KeyguardQuickAffordanceConfig.LockScreenState>()
        val job = underTest.lockScreenState.onEach(values::add).launchIn(this)

        assertThat(values.last())
            .isInstanceOf(KeyguardQuickAffordanceConfig.LockScreenState.Hidden::class.java)
        job.cancel()
    }

    @Test
    fun onQuickAffordanceTriggered_canShowWhileLockedSettingIsTrue() =
        runTest(UnconfinedTestDispatcher()) {
            whenever(component.canShowWhileLockedSetting).thenReturn(MutableStateFlow(true))

            val onClickedResult = underTest.onTriggered(expandable)

            assertThat(onClickedResult).isInstanceOf(OnTriggeredResult.StartActivity::class.java)
            assertThat((onClickedResult as OnTriggeredResult.StartActivity).canShowWhileLocked)
                .isTrue()
        }

    @Test
    fun onQuickAffordanceTriggered_canShowWhileLockedSettingIsFalse() =
        runTest(UnconfinedTestDispatcher()) {
            whenever(component.canShowWhileLockedSetting).thenReturn(MutableStateFlow(false))

            val onClickedResult = underTest.onTriggered(expandable)

            assertThat(onClickedResult).isInstanceOf(OnTriggeredResult.StartActivity::class.java)
            assertThat((onClickedResult as OnTriggeredResult.StartActivity).canShowWhileLocked)
                .isFalse()
        }
}
