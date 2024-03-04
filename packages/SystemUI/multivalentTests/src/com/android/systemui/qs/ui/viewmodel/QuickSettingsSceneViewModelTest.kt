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

package com.android.systemui.qs.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.FooterActionsController
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.qs.ui.adapter.FakeQSSceneAdapter
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.privacyChipInteractor
import com.android.systemui.shade.domain.interactor.shadeHeaderClockInteractor
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.notificationsPlaceholderViewModel
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class QuickSettingsSceneViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val mobileIconsInteractor = FakeMobileIconsInteractor(FakeMobileMappingsProxy(), mock())
    private val flags = FakeFeatureFlagsClassic().also { it.set(Flags.NEW_NETWORK_SLICE_UI, false) }
    private val qsFlexiglassAdapter = FakeQSSceneAdapter({ mock() })
    private val footerActionsViewModel = mock<FooterActionsViewModel>()
    private val footerActionsViewModelFactory =
        mock<FooterActionsViewModel.Factory> {
            whenever(create(any())).thenReturn(footerActionsViewModel)
        }
    private val footerActionsController = mock<FooterActionsController>()

    private var mobileIconsViewModel: MobileIconsViewModel =
        MobileIconsViewModel(
            logger = mock(),
            verboseLogger = mock(),
            interactor = mobileIconsInteractor,
            airplaneModeInteractor =
                AirplaneModeInteractor(
                    FakeAirplaneModeRepository(),
                    FakeConnectivityRepository(),
                    FakeMobileConnectionsRepository(),
                ),
            constants = mock(),
            flags,
            scope = testScope.backgroundScope,
        )

    private lateinit var shadeHeaderViewModel: ShadeHeaderViewModel

    private lateinit var underTest: QuickSettingsSceneViewModel

    @Before
    fun setUp() {
        shadeHeaderViewModel =
            ShadeHeaderViewModel(
                applicationScope = testScope.backgroundScope,
                context = context,
                mobileIconsInteractor = mobileIconsInteractor,
                mobileIconsViewModel = mobileIconsViewModel,
                privacyChipInteractor = kosmos.privacyChipInteractor,
                clockInteractor = kosmos.shadeHeaderClockInteractor,
                broadcastDispatcher = fakeBroadcastDispatcher,
            )

        underTest =
            QuickSettingsSceneViewModel(
                shadeHeaderViewModel = shadeHeaderViewModel,
                qsSceneAdapter = qsFlexiglassAdapter,
                notifications = kosmos.notificationsPlaceholderViewModel,
                footerActionsViewModelFactory,
                footerActionsController,
            )
    }

    @Test
    fun destinationsNotCustomizing() =
        testScope.runTest {
            val destinations by collectLastValue(underTest.destinationScenes)
            qsFlexiglassAdapter.setCustomizing(false)

            assertThat(destinations)
                .isEqualTo(
                    mapOf(
                        Back to UserActionResult(Scenes.Shade),
                        Swipe(SwipeDirection.Up) to UserActionResult(Scenes.Shade),
                    )
                )
        }

    @Test
    fun destinationsCustomizing() =
        testScope.runTest {
            val destinations by collectLastValue(underTest.destinationScenes)
            qsFlexiglassAdapter.setCustomizing(true)

            assertThat(destinations)
                .isEqualTo(
                    mapOf(
                        Back to UserActionResult(Scenes.QuickSettings),
                    )
                )
        }

    @Test
    fun gettingViewModelInitializesControllerOnlyOnce() {
        underTest.getFooterActionsViewModel(mock())
        underTest.getFooterActionsViewModel(mock())

        verify(footerActionsController, times(1)).init()
    }
}
