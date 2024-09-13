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
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.keyguardRepository
import com.android.systemui.keyguard.shared.model.CameraLaunchType
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionState.FINISHED
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
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
    private val repository by lazy { kosmos.fakeKeyguardRepository }
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val configRepository by lazy { kosmos.fakeConfigurationRepository }
    private val bouncerRepository by lazy { kosmos.keyguardBouncerRepository }
    private val shadeRepository by lazy { kosmos.shadeRepository }
    private val powerInteractor by lazy { kosmos.powerInteractor }
    private val keyguardRepository by lazy { kosmos.keyguardRepository }
    private val keyguardTransitionRepository by lazy { kosmos.fakeKeyguardTransitionRepository }

    private val transitionState: MutableStateFlow<ObservableTransitionState> =
        MutableStateFlow(ObservableTransitionState.Idle(Scenes.Gone))

    private val underTest by lazy { kosmos.keyguardInteractor }

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

            underTest.onCameraLaunchDetected(StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP)
            assertThat(cameraLaunchSource()!!.type).isEqualTo(CameraLaunchType.POWER_DOUBLE_TAP)

            underTest.onCameraLaunchDetected(StatusBarManager.CAMERA_LAUNCH_SOURCE_WIGGLE)
            assertThat(cameraLaunchSource()!!.type).isEqualTo(CameraLaunchType.WIGGLE)

            underTest.onCameraLaunchDetected(StatusBarManager.CAMERA_LAUNCH_SOURCE_LIFT_TRIGGER)
            assertThat(cameraLaunchSource()!!.type).isEqualTo(CameraLaunchType.LIFT_TRIGGER)

            underTest.onCameraLaunchDetected(StatusBarManager.CAMERA_LAUNCH_SOURCE_QUICK_AFFORDANCE)
            assertThat(cameraLaunchSource()!!.type).isEqualTo(CameraLaunchType.QUICK_AFFORDANCE)
        }

    @Test
    @DisableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testKeyguardGuardVisibilityStopsSecureCamera() =
        testScope.runTest {
            val secureCameraActive = collectLastValue(underTest.isSecureCameraActive)
            runCurrent()

            underTest.onCameraLaunchDetected(StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP)

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

            underTest.onCameraLaunchDetected(StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP)
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
            assertThat(dismissAlpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionSteps(
                from = AOD,
                to = LOCKSCREEN,
                testScope,
            )

            repository.setStatusBarState(StatusBarState.KEYGUARD)
            // User begins to swipe up
            shadeRepository.setLegacyShadeExpansion(0.99f)

            // When not dismissable, the last alpha value should still be present
            repository.setKeyguardDismissible(false)
            assertThat(dismissAlpha).isEqualTo(1f)

            repository.setKeyguardDismissible(true)
            shadeRepository.setLegacyShadeExpansion(0.98f)
            assertThat(dismissAlpha).isGreaterThan(0.5f)
        }

    @Test
    fun dismissAlpha_whenShadeResetsEmitsOne() =
        testScope.runTest {
            val dismissAlpha by collectValues(underTest.dismissAlpha)
            assertThat(dismissAlpha[0]).isEqualTo(1f)
            assertThat(dismissAlpha.size).isEqualTo(1)

            keyguardTransitionRepository.sendTransitionSteps(
                from = AOD,
                to = LOCKSCREEN,
                testScope,
            )

            // User begins to swipe up
            repository.setStatusBarState(StatusBarState.KEYGUARD)
            repository.setKeyguardDismissible(true)
            shadeRepository.setLegacyShadeExpansion(0.98f)

            assertThat(dismissAlpha[1]).isGreaterThan(0.5f)
            assertThat(dismissAlpha[1]).isLessThan(1f)
            assertThat(dismissAlpha.size).isEqualTo(2)

            // Now reset the shade
            shadeRepository.setLegacyShadeExpansion(1f)
            assertThat(dismissAlpha[2]).isEqualTo(1f)
            assertThat(dismissAlpha.size).isEqualTo(3)
        }

    @Test
    fun dismissAlpha_doesNotEmitWhileTransitioning() =
        testScope.runTest {
            val dismissAlpha by collectLastValue(underTest.dismissAlpha)
            assertThat(dismissAlpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    TransitionStep(
                        from = AOD,
                        to = KeyguardState.GONE,
                        value = 0f,
                        transitionState = STARTED,
                    ),
                    TransitionStep(
                        from = AOD,
                        to = KeyguardState.GONE,
                        value = 0.1f,
                        transitionState = TransitionState.RUNNING,
                    ),
                ),
                testScope,
            )

            repository.setKeyguardDismissible(true)
            shadeRepository.setLegacyShadeExpansion(0.98f)

            // Should still be one
            assertThat(dismissAlpha).isEqualTo(1f)
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
                from = AOD,
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
                from = AOD,
                to = LOCKSCREEN,
                testScope,
            )

            assertThat(keyguardTranslationY).isEqualTo(0f)
        }

    @Test
    fun keyguardTranslationY_whenNotGoneAndShadeIsReesetEmitsZero() =
        testScope.runTest {
            val keyguardTranslationY by collectLastValue(underTest.keyguardTranslationY)

            configRepository.setDimensionPixelSize(
                R.dimen.keyguard_translate_distance_on_swipe_up,
                100
            )
            configRepository.onAnyConfigurationChange()

            shadeRepository.setLegacyShadeExpansion(1f)

            keyguardTransitionRepository.sendTransitionSteps(
                from = AOD,
                to = LOCKSCREEN,
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
                        from = AOD,
                        to = KeyguardState.GONE,
                        value = 0f,
                        transitionState = STARTED,
                    ),
                    TransitionStep(
                        from = AOD,
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
    fun isAbleToDream_falseWhenDozing() =
        testScope.runTest {
            val isAbleToDream by collectLastValue(underTest.isAbleToDream)

            repository.setDozeTransitionModel(
                DozeTransitionModel(from = DozeStateModel.INITIALIZED, to = DozeStateModel.DOZE_AOD)
            )

            assertThat(isAbleToDream).isEqualTo(false)
        }

    @Test
    fun isAbleToDream_falseWhenNotDozingAndNotDreaming() =
        testScope.runTest {
            val isAbleToDream by collectLastValue(underTest.isAbleToDream)

            repository.setDozeTransitionModel(
                DozeTransitionModel(from = DozeStateModel.DOZE, to = DozeStateModel.FINISH)
            )
            powerInteractor.setAwakeForTest()
            advanceTimeBy(1000L)

            assertThat(isAbleToDream).isEqualTo(false)
        }

    @Test
    fun isAbleToDream_trueWhenNotDozingAndIsDreaming_afterDelay() =
        testScope.runTest {
            val isAbleToDream by collectLastValue(underTest.isAbleToDream)
            runCurrent()

            repository.setDreaming(true)
            repository.setDozeTransitionModel(
                DozeTransitionModel(from = DozeStateModel.DOZE, to = DozeStateModel.FINISH)
            )
            powerInteractor.setAwakeForTest()
            runCurrent()

            // After some delay, still false
            advanceTimeBy(300L)
            assertThat(isAbleToDream).isEqualTo(false)

            // After more delay, is true
            advanceTimeBy(300L)
            assertThat(isAbleToDream).isEqualTo(true)

            // Also changes back after the minimal debounce
            repository.setDreaming(false)
            advanceTimeBy(55L)
            assertThat(isAbleToDream).isEqualTo(false)
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

    @Test
    @EnableSceneContainer
    fun dozeAmount_updatedByAodTransitionWhenAodEnabled() =
        testScope.runTest {
            val dozeAmount by collectLastValue(underTest.dozeAmount)

            keyguardRepository.setAodAvailable(true)

            sendTransitionStep(TransitionStep(to = AOD, value = 0f, transitionState = STARTED))
            assertThat(dozeAmount).isEqualTo(0f)

            sendTransitionStep(TransitionStep(to = AOD, value = 0.5f, transitionState = RUNNING))
            assertThat(dozeAmount).isEqualTo(0.5f)

            sendTransitionStep(TransitionStep(to = AOD, value = 1f, transitionState = FINISHED))
            assertThat(dozeAmount).isEqualTo(1f)

            sendTransitionStep(TransitionStep(AOD, LOCKSCREEN, 0f, STARTED))
            assertThat(dozeAmount).isEqualTo(1f)

            sendTransitionStep(TransitionStep(AOD, LOCKSCREEN, 0.5f, RUNNING))
            assertThat(dozeAmount).isEqualTo(0.5f)

            sendTransitionStep(TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED))
            assertThat(dozeAmount).isEqualTo(0f)
        }

    @Test
    @EnableSceneContainer
    fun dozeAmount_updatedByDozeTransitionWhenAodDisabled() =
        testScope.runTest {
            val dozeAmount by collectLastValue(underTest.dozeAmount)

            keyguardRepository.setAodAvailable(false)

            sendTransitionStep(TransitionStep(to = DOZING, value = 0f, transitionState = STARTED))
            assertThat(dozeAmount).isEqualTo(0f)

            sendTransitionStep(TransitionStep(to = DOZING, value = 0.5f, transitionState = RUNNING))
            assertThat(dozeAmount).isEqualTo(0.5f)

            sendTransitionStep(TransitionStep(to = DOZING, value = 1f, transitionState = FINISHED))
            assertThat(dozeAmount).isEqualTo(1f)

            sendTransitionStep(TransitionStep(DOZING, LOCKSCREEN, 0f, STARTED))
            assertThat(dozeAmount).isEqualTo(1f)

            sendTransitionStep(TransitionStep(DOZING, LOCKSCREEN, 0.5f, RUNNING))
            assertThat(dozeAmount).isEqualTo(0.5f)

            sendTransitionStep(TransitionStep(DOZING, LOCKSCREEN, 1f, FINISHED))
            assertThat(dozeAmount).isEqualTo(0f)
        }

    private suspend fun sendTransitionStep(step: TransitionStep) {
        keyguardTransitionRepository.sendTransitionStep(step)
        testScope.runCurrent()
    }
}
