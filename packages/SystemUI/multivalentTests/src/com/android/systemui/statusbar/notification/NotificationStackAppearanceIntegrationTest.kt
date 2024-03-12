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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.flag.fakeSceneContainerFlags
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.fakeSceneDataSource
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.notificationStackAppearanceViewModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.notificationsPlaceholderViewModel
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationStackAppearanceIntegrationTest : SysuiTestCase() {

    private val kosmos =
        testKosmos().apply {
            fakeSceneContainerFlags.enabled = true
            fakeFeatureFlagsClassic.apply {
                set(Flags.FULL_SCREEN_USER_SWITCHER, false)
                set(Flags.NSSL_DEBUG_LINES, false)
            }
        }
    private val testScope = kosmos.testScope
    private val placeholderViewModel by lazy { kosmos.notificationsPlaceholderViewModel }
    private val appearanceViewModel by lazy { kosmos.notificationStackAppearanceViewModel }
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val fakeSceneDataSource by lazy { kosmos.fakeSceneDataSource }

    @Test
    fun updateBounds() =
        testScope.runTest {
            val bounds by collectLastValue(appearanceViewModel.stackBounds)

            val top = 200f
            val left = 0f
            val bottom = 550f
            val right = 100f
            placeholderViewModel.onBoundsChanged(
                left = left,
                top = top,
                right = right,
                bottom = bottom
            )
            assertThat(bounds)
                .isEqualTo(
                    NotificationContainerBounds(
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom
                    )
                )
        }

    @Test
    fun shadeExpansion_goneToShade() =
        testScope.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(scene = Scenes.Gone)
                )
            sceneInteractor.setTransitionState(transitionState)
            val expandFraction by collectLastValue(appearanceViewModel.expandFraction)
            assertThat(expandFraction).isEqualTo(0f)

            fakeSceneDataSource.pause()
            sceneInteractor.changeScene(Scenes.Shade, "reason")
            val transitionProgress = MutableStateFlow(0f)
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Gone,
                    toScene = Scenes.Shade,
                    progress = transitionProgress,
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            val steps = 10
            repeat(steps) { repetition ->
                val progress = (1f / steps) * (repetition + 1)
                transitionProgress.value = progress
                runCurrent()
                assertThat(expandFraction).isWithin(0.01f).of(progress)
            }

            fakeSceneDataSource.unpause(expectedScene = Scenes.Shade)
            assertThat(expandFraction).isWithin(0.01f).of(1f)
        }

    @Test
    fun shadeExpansion_idleOnLockscreen() =
        testScope.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(scene = Scenes.Lockscreen)
                )
            sceneInteractor.setTransitionState(transitionState)
            val expandFraction by collectLastValue(appearanceViewModel.expandFraction)
            assertThat(expandFraction).isEqualTo(1f)
        }

    @Test
    fun shadeExpansion_shadeToQs() =
        testScope.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(scene = Scenes.Shade)
                )
            sceneInteractor.setTransitionState(transitionState)
            val expandFraction by collectLastValue(appearanceViewModel.expandFraction)
            assertThat(expandFraction).isEqualTo(1f)

            fakeSceneDataSource.pause()
            sceneInteractor.changeScene(Scenes.QuickSettings, "reason")
            val transitionProgress = MutableStateFlow(0f)
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Shade,
                    toScene = Scenes.QuickSettings,
                    progress = transitionProgress,
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            val steps = 10
            repeat(steps) { repetition ->
                val progress = (1f / steps) * (repetition + 1)
                transitionProgress.value = progress
                runCurrent()
                assertThat(expandFraction).isEqualTo(1f)
            }

            fakeSceneDataSource.unpause(expectedScene = Scenes.QuickSettings)
            assertThat(expandFraction).isEqualTo(1f)
        }
}
