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
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.ui.adapter.FakeQSSceneAdapter
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Direction
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
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
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class QuickSettingsSceneViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sceneInteractor = kosmos.sceneInteractor
    private val mobileIconsInteractor = FakeMobileIconsInteractor(FakeMobileMappingsProxy(), mock())
    private val flags = FakeFeatureFlagsClassic().also { it.set(Flags.NEW_NETWORK_SLICE_UI, false) }
    private val qsFlexiglassAdapter = FakeQSSceneAdapter { mock() }

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
                sceneInteractor = sceneInteractor,
                mobileIconsInteractor = mobileIconsInteractor,
                mobileIconsViewModel = mobileIconsViewModel,
                broadcastDispatcher = fakeBroadcastDispatcher,
            )

        underTest =
            QuickSettingsSceneViewModel(
                shadeHeaderViewModel = shadeHeaderViewModel,
                qsSceneAdapter = qsFlexiglassAdapter,
                notifications = kosmos.notificationsPlaceholderViewModel,
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
                        UserAction.Back to SceneModel(SceneKey.Shade),
                        UserAction.Swipe(Direction.UP) to SceneModel(SceneKey.Shade),
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
                        UserAction.Back to SceneModel(SceneKey.QuickSettings),
                    )
                )
        }
}
