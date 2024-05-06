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
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.app.StatusBarManager
import android.platform.test.annotations.DisableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.keyguardBouncerRepository
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeCommandQueue
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.CameraLaunchSourceModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val repository = kosmos.fakeKeyguardRepository
    private val sceneInteractor = kosmos.sceneInteractor
    private val fromGoneTransitionInteractor = kosmos.fromGoneTransitionInteractor
    private val commandQueue = kosmos.fakeCommandQueue
    private val configRepository = kosmos.fakeConfigurationRepository
    private val bouncerRepository = kosmos.keyguardBouncerRepository
    private val shadeRepository = kosmos.shadeRepository
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val transitionState: MutableStateFlow<ObservableTransitionState> =
        MutableStateFlow(ObservableTransitionState.Idle(Scenes.Gone))
    private val underTest = kosmos.keyguardInteractor

    @Before
    fun setUp() {
        sceneInteractor.setTransitionState(transitionState)
    }

    @Test
    fun onCameraLaunchDetected() =
        testScope.runTest {
            val flow = underTest.onCameraLaunchDetected
            val cameraLaunchSource = collectLastValue(flow)
            runCurrent()

            commandQueue.doForEachCallback {
                it.onCameraLaunchGestureDetected(StatusBarManager.CAMERA_LAUNCH_SOURCE_WIGGLE)
            }
            assertThat(cameraLaunchSource()).isEqualTo(CameraLaunchSourceModel.WIGGLE)

            commandQueue.doForEachCallback {
                it.onCameraLaunchGestureDetected(
                    StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP
                )
            }
            assertThat(cameraLaunchSource()).isEqualTo(CameraLaunchSourceModel.POWER_DOUBLE_TAP)

            commandQueue.doForEachCallback {
                it.onCameraLaunchGestureDetected(StatusBarManager.CAMERA_LAUNCH_SOURCE_LIFT_TRIGGER)
            }
            assertThat(cameraLaunchSource()).isEqualTo(CameraLaunchSourceModel.LIFT_TRIGGER)

            commandQueue.doForEachCallback {
                it.onCameraLaunchGestureDetected(
                    StatusBarManager.CAMERA_LAUNCH_SOURCE_QUICK_AFFORDANCE
                )
            }
            assertThat(cameraLaunchSource()).isEqualTo(CameraLaunchSourceModel.QUICK_AFFORDANCE)

            flow.onCompletion { assertThat(commandQueue.callbackCount()).isEqualTo(0) }
        }

    @Test
    @DisableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testKeyguardGuardVisibilityStopsSecureCamera() =
        testScope.runTest {
            val secureCameraActive = collectLastValue(underTest.isSecureCameraActive)
            runCurrent()

            commandQueue.doForEachCallback {
                it.onCameraLaunchGestureDetected(
                    StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP
                )
            }

            assertThat(secureCameraActive()).isTrue()

            // Keyguard is showing but occluded
            repository.setKeyguardShowing(true)
            repository.setKeyguardOccluded(true)
            assertThat(secureCameraActive()).isTrue()

            // Keyguard is showing and not occluded
            repository.setKeyguardOccluded(false)
            assertThat(secureCameraActive()).isFalse()
        }

    @Test
    @DisableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testBouncerShowingResetsSecureCameraState() =
        testScope.runTest {
            val secureCameraActive = collectLastValue(underTest.isSecureCameraActive)
            runCurrent()

            commandQueue.doForEachCallback {
                it.onCameraLaunchGestureDetected(
                    StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP
                )
            }
            assertThat(secureCameraActive()).isTrue()

            // Keyguard is showing and not occluded
            repository.setKeyguardShowing(true)
            repository.setKeyguardOccluded(true)
            assertThat(secureCameraActive()).isTrue()

            bouncerRepository.setPrimaryShow(true)
            assertThat(secureCameraActive()).isFalse()
        }

    @Test
    @DisableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun keyguardVisibilityIsDefinedAsKeyguardShowingButNotOccluded() = runTest {
        val isVisible = collectLastValue(underTest.isKeyguardVisible)
        repository.setKeyguardShowing(true)
        repository.setKeyguardOccluded(false)

        assertThat(isVisible()).isTrue()

        repository.setKeyguardOccluded(true)
        assertThat(isVisible()).isFalse()

        repository.setKeyguardShowing(false)
        repository.setKeyguardOccluded(true)
        assertThat(isVisible()).isFalse()
    }

    @Test
    fun secureCameraIsNotActiveWhenNoCameraLaunchEventHasBeenFiredYet() =
        testScope.runTest {
            val secureCameraActive = collectLastValue(underTest.isSecureCameraActive)
            runCurrent()

            assertThat(secureCameraActive()).isFalse()
        }

    @Test
    fun dismissAlpha() =
        testScope.runTest {
            val dismissAlpha by collectLastValue(underTest.dismissAlpha)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )

            repository.setStatusBarState(StatusBarState.KEYGUARD)
            // User begins to swipe up
            shadeRepository.setLegacyShadeExpansion(0.99f)

            // When not dismissable, no alpha value (null) should emit
            repository.setKeyguardDismissible(false)
            assertThat(dismissAlpha).isNull()

            repository.setKeyguardDismissible(true)
            shadeRepository.setLegacyShadeExpansion(0.98f)
            assertThat(dismissAlpha).isGreaterThan(0.5f)
        }

    @Test
    fun dismissAlpha_whenShadeIsExpandedEmitsNull() =
        testScope.runTest {
            val dismissAlpha by collectLastValue(underTest.dismissAlpha)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )

            repository.setStatusBarState(StatusBarState.SHADE_LOCKED)
            shadeRepository.setQsExpansion(1f)

            repository.setKeyguardDismissible(false)
            assertThat(dismissAlpha).isNull()

            repository.setKeyguardDismissible(true)
            assertThat(dismissAlpha).isNull()
        }

    @Test
    fun keyguardTranslationY_whenGoneEmitsZero() =
        testScope.runTest {
            val keyguardTranslationY by collectLastValue(underTest.keyguardTranslationY)

            configRepository.setDimensionPixelSize(
                R.dimen.keyguard_translate_distance_on_swipe_up,
                100
            )
            configRepository.onAnyConfigurationChange()

            shadeRepository.setLegacyShadeExpansion(0f)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.GONE,
                testScope,
            )

            assertThat(keyguardTranslationY).isEqualTo(0f)
        }

    @Test
    fun keyguardTranslationY_whenNotGoneAndShadeIsFullyCollapsedEmitsZero() =
        testScope.runTest {
            val keyguardTranslationY by collectLastValue(underTest.keyguardTranslationY)

            configRepository.setDimensionPixelSize(
                R.dimen.keyguard_translate_distance_on_swipe_up,
                100
            )
            configRepository.onAnyConfigurationChange()

            shadeRepository.setLegacyShadeExpansion(0f)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )

            assertThat(keyguardTranslationY).isEqualTo(0f)
        }

    @Test
    fun keyguardTranslationY_whenTransitioningToGoneAndShadeIsExpandingEmitsNonZero() =
        testScope.runTest {
            val keyguardTranslationY by collectLastValue(underTest.keyguardTranslationY)

            configRepository.setDimensionPixelSize(
                R.dimen.keyguard_translate_distance_on_swipe_up,
                100
            )
            configRepository.onAnyConfigurationChange()

            shadeRepository.setLegacyShadeExpansion(0.5f)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    TransitionStep(
                        from = KeyguardState.AOD,
                        to = KeyguardState.GONE,
                        value = 0f,
                        transitionState = TransitionState.STARTED,
                    ),
                    TransitionStep(
                        from = KeyguardState.AOD,
                        to = KeyguardState.GONE,
                        value = 0.1f,
                        transitionState = TransitionState.RUNNING,
                    ),
                ),
                testScope,
            )

            assertThat(keyguardTranslationY).isGreaterThan(0f)
        }

    @Test
    @EnableSceneContainer
    fun animationDozingTransitions() =
        testScope.runTest {
            val isAnimate by collectLastValue(underTest.animateDozingTransitions)

            underTest.setAnimateDozingTransitions(true)
            runCurrent()
            assertThat(isAnimate).isTrue()

            underTest.setAnimateDozingTransitions(false)
            runCurrent()
            assertThat(isAnimate).isFalse()

            underTest.setAnimateDozingTransitions(true)
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Gone,
                    toScene = Scenes.Lockscreen,
                    currentScene = flowOf(Scenes.Lockscreen),
                    progress = flowOf(0f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            runCurrent()
            assertThat(isAnimate).isFalse()
        }
}
