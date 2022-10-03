/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.quickaffordance

import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameter
import org.junit.runners.Parameterized.Parameters
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(Parameterized::class)
class HomeControlsKeyguardQuickAffordanceConfigParameterizedStateTest : SysuiTestCase() {

    companion object {
        @Parameters(
            name =
                "feature enabled = {0}, has favorites = {1}, has service infos = {2}, can show" +
                    " while locked = {3}, visibility is AVAILABLE {4} - expected visible = {5}"
        )
        @JvmStatic
        fun data() =
            (0 until 32)
                .map { combination ->
                    arrayOf(
                        /* isFeatureEnabled= */ combination and 0b10000 != 0,
                        /* hasFavorites= */ combination and 0b01000 != 0,
                        /* hasServiceInfos= */ combination and 0b00100 != 0,
                        /* canShowWhileLocked= */ combination and 0b00010 != 0,
                        /* visibilityAvailable= */ combination and 0b00001 != 0,
                        /* isVisible= */ combination == 0b11111,
                    )
                }
                .toList()
    }

    @Mock private lateinit var component: ControlsComponent
    @Mock private lateinit var controlsController: ControlsController
    @Mock private lateinit var controlsListingController: ControlsListingController
    @Captor
    private lateinit var callbackCaptor:
        ArgumentCaptor<ControlsListingController.ControlsListingCallback>

    private lateinit var underTest: HomeControlsKeyguardQuickAffordanceConfig

    @JvmField @Parameter(0) var isFeatureEnabled: Boolean = false
    @JvmField @Parameter(1) var hasFavorites: Boolean = false
    @JvmField @Parameter(2) var hasServiceInfos: Boolean = false
    @JvmField @Parameter(3) var canShowWhileLocked: Boolean = false
    @JvmField @Parameter(4) var isVisibilityAvailable: Boolean = false
    @JvmField @Parameter(5) var isVisibleExpected: Boolean = false

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(component.getTileImageId()).thenReturn(R.drawable.controls_icon)
        whenever(component.getTileTitleId()).thenReturn(R.string.quick_controls_title)
        whenever(component.getControlsController()).thenReturn(Optional.of(controlsController))
        whenever(component.getControlsListingController())
            .thenReturn(Optional.of(controlsListingController))
        whenever(component.canShowWhileLockedSetting)
            .thenReturn(MutableStateFlow(canShowWhileLocked))
        whenever(component.getVisibility())
            .thenReturn(
                if (isVisibilityAvailable) {
                    ControlsComponent.Visibility.AVAILABLE
                } else {
                    ControlsComponent.Visibility.UNAVAILABLE
                }
            )

        underTest =
            HomeControlsKeyguardQuickAffordanceConfig(
                context = context,
                component = component,
            )
    }

    @Test
    fun state() = runBlockingTest {
        whenever(component.isEnabled()).thenReturn(isFeatureEnabled)
        whenever(controlsController.getFavorites())
            .thenReturn(
                if (hasFavorites) {
                    listOf(mock())
                } else {
                    emptyList()
                }
            )
        val values = mutableListOf<KeyguardQuickAffordanceConfig.State>()
        val job = underTest.state.onEach(values::add).launchIn(this)

        if (canShowWhileLocked) {
            verify(controlsListingController).addCallback(callbackCaptor.capture())
            callbackCaptor.value.onServicesUpdated(
                if (hasServiceInfos) {
                    listOf(mock())
                } else {
                    emptyList()
                }
            )
        }

        assertThat(values.last())
            .isInstanceOf(
                if (isVisibleExpected) {
                    KeyguardQuickAffordanceConfig.State.Visible::class.java
                } else {
                    KeyguardQuickAffordanceConfig.State.Hidden::class.java
                }
            )
        job.cancel()
    }
}
