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

package com.android.systemui.statusbar.notification

import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.fakeSceneDataSource
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimBounds
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimShape
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationTransitionThresholds.EXPANSION_FOR_DELAYED_STACK_FADE_IN
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationTransitionThresholds.EXPANSION_FOR_MAX_SCRIM_ALPHA
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.notificationScrollViewModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.notificationsPlaceholderViewModel
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
@EnableSceneContainer
class NotificationStackAppearanceIntegrationTest : SysuiTestCase() {

    private val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply {
                set(Flags.FULL_SCREEN_USER_SWITCHER, false)
                set(Flags.NSSL_DEBUG_LINES, false)
            }
        }
    private val testScope = kosmos.testScope
    private val placeholderViewModel by lazy { kosmos.notificationsPlaceholderViewModel }
    private val scrollViewModel by lazy { kosmos.notificationScrollViewModel }
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val fakeKeyguardRepository by lazy { kosmos.fakeKeyguardRepository }
    private val fakeSceneDataSource by lazy { kosmos.fakeSceneDataSource }

    @Test
    fun updateBounds() =
        testScope.runTest {
            val radius = MutableStateFlow(32)
            val leftOffset = MutableStateFlow(0)
            val shape by
                collectLastValue(scrollViewModel.notificationScrimShape(radius, leftOffset))

            // When: receive scrim bounds
            placeholderViewModel.onScrimBoundsChanged(
                ShadeScrimBounds(left = 0f, top = 200f, right = 100f, bottom = 550f)
            )
            // Then: shape is updated
            assertThat(shape)
                .isEqualTo(
                    ShadeScrimShape(
                        bounds =
                            ShadeScrimBounds(left = 0f, top = 200f, right = 100f, bottom = 550f),
                        topRadius = 32,
                        bottomRadius = 0,
                    )
                )

            // When: receive new scrim bounds
            leftOffset.value = 200
            radius.value = 24
            placeholderViewModel.onScrimBoundsChanged(
                ShadeScrimBounds(left = 210f, top = 200f, right = 300f, bottom = 550f)
            )
            // Then: shape is updated
            assertThat(shape)
                .isEqualTo(
                    ShadeScrimShape(
                        bounds =
                            ShadeScrimBounds(left = 10f, top = 200f, right = 100f, bottom = 550f),
                        topRadius = 24,
                        bottomRadius = 0,
                    )
                )

            // When: QuickSettings shows up full screen
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(Scenes.QuickSettings)
                )
            sceneInteractor.setTransitionState(transitionState)
            // Then: shape is null
            assertThat(shape).isNull()
        }

    @Test
    fun brightnessMirrorAlpha_updatesViewModel() =
        testScope.runTest {
            val maxAlpha by collectLastValue(scrollViewModel.maxAlpha)
            assertThat(maxAlpha).isEqualTo(1f)
            placeholderViewModel.setAlphaForBrightnessMirror(0.33f)
            assertThat(maxAlpha).isEqualTo(0.33f)
            placeholderViewModel.setAlphaForBrightnessMirror(0f)
            assertThat(maxAlpha).isEqualTo(0f)
            placeholderViewModel.setAlphaForBrightnessMirror(1f)
            assertThat(maxAlpha).isEqualTo(1f)
        }

    @Test
    fun shadeExpansion_goneToShade() =
        testScope.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(currentScene = Scenes.Gone)
                )
            sceneInteractor.setTransitionState(transitionState)
            val expandFraction by collectLastValue(scrollViewModel.expandFraction)
            assertThat(expandFraction).isEqualTo(0f)

            fakeSceneDataSource.changeScene(toScene = Scenes.Gone)
            val isScrollable by collectLastValue(scrollViewModel.isScrollable)
            assertThat(isScrollable).isFalse()

            fakeSceneDataSource.pause()

            sceneInteractor.changeScene(Scenes.Shade, "reason")
            val transitionProgress = MutableStateFlow(0f)
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Gone,
                    toScene = Scenes.Shade,
                    currentScene = flowOf(Scenes.Shade),
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
            assertThat(isScrollable).isTrue()
        }

    @Test
    fun shadeExpansion_idleOnLockscreen() =
        testScope.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(currentScene = Scenes.Lockscreen)
                )
            sceneInteractor.setTransitionState(transitionState)
            val expandFraction by collectLastValue(scrollViewModel.expandFraction)
            assertThat(expandFraction).isEqualTo(1f)

            fakeSceneDataSource.changeScene(toScene = Scenes.Lockscreen)
            val isScrollable by collectLastValue(scrollViewModel.isScrollable)
            assertThat(isScrollable).isTrue()
        }

    @Test
    fun shadeExpansion_idleOnQs() =
        testScope.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(currentScene = Scenes.QuickSettings)
                )
            sceneInteractor.setTransitionState(transitionState)
            val expandFraction by collectLastValue(scrollViewModel.expandFraction)
            assertThat(expandFraction).isEqualTo(1f)

            fakeSceneDataSource.changeScene(toScene = Scenes.QuickSettings)
            val isScrollable by collectLastValue(scrollViewModel.isScrollable)
            assertThat(isScrollable).isFalse()
        }

    @Test
    fun shadeExpansion_shadeToQs() =
        testScope.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(currentScene = Scenes.Shade)
                )
            sceneInteractor.setTransitionState(transitionState)
            val expandFraction by collectLastValue(scrollViewModel.expandFraction)
            assertThat(expandFraction).isEqualTo(1f)

            fakeSceneDataSource.changeScene(toScene = Scenes.Shade)
            val isScrollable by collectLastValue(scrollViewModel.isScrollable)
            assertThat(isScrollable).isTrue()

            fakeSceneDataSource.pause()

            sceneInteractor.changeScene(Scenes.QuickSettings, "reason")
            val transitionProgress = MutableStateFlow(0f)
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Shade,
                    toScene = Scenes.QuickSettings,
                    currentScene = flowOf(Scenes.QuickSettings),
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
            assertThat(isScrollable).isFalse()
        }

    @Test
    fun shadeExpansion_goneToQs() =
        testScope.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(currentScene = Scenes.Gone)
                )
            sceneInteractor.setTransitionState(transitionState)
            val expandFraction by collectLastValue(scrollViewModel.expandFraction)
            assertThat(expandFraction).isEqualTo(0f)

            fakeSceneDataSource.changeScene(toScene = Scenes.Gone)
            val isScrollable by collectLastValue(scrollViewModel.isScrollable)
            assertThat(isScrollable).isFalse()

            fakeSceneDataSource.pause()

            sceneInteractor.changeScene(Scenes.QuickSettings, "reason")
            val transitionProgress = MutableStateFlow(0f)
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Gone,
                    toScene = Scenes.QuickSettings,
                    currentScene = flowOf(Scenes.QuickSettings),
                    progress = transitionProgress,
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            val steps = 10
            repeat(steps) { repetition ->
                val progress = (1f / steps) * (repetition + 1)
                transitionProgress.value = progress
                runCurrent()
                assertThat(expandFraction)
                    .isEqualTo(
                        (progress / EXPANSION_FOR_MAX_SCRIM_ALPHA -
                                EXPANSION_FOR_DELAYED_STACK_FADE_IN)
                            .coerceIn(0f, 1f)
                    )
            }

            fakeSceneDataSource.unpause(expectedScene = Scenes.QuickSettings)
            assertThat(expandFraction).isEqualTo(1f)
            assertThat(isScrollable).isFalse()
        }
}
