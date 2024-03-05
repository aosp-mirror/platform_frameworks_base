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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeCommandQueue
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.CameraLaunchSourceModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.flag.fakeSceneContainerFlags
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.data.repository.FakeShadeRepository
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
    private val repository by lazy { kosmos.fakeKeyguardRepository }
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val commandQueue by lazy { FakeCommandQueue() }
    private val bouncerRepository = FakeKeyguardBouncerRepository()
    private val shadeRepository = FakeShadeRepository()
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val transitionState: MutableStateFlow<ObservableTransitionState> =
        MutableStateFlow(ObservableTransitionState.Idle(Scenes.Gone))

    private val underTest by lazy {
        KeyguardInteractor(
            repository = repository,
            commandQueue = commandQueue,
            powerInteractor = PowerInteractorFactory.create().powerInteractor,
            sceneContainerFlags = kosmos.fakeSceneContainerFlags,
            bouncerRepository = bouncerRepository,
            configurationInteractor = ConfigurationInteractor(FakeConfigurationRepository()),
            shadeRepository = shadeRepository,
            keyguardTransitionInteractor = kosmos.keyguardTransitionInteractor,
            sceneInteractorProvider = { sceneInteractor },
        )
    }

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
            shadeRepository.setLegacyShadeExpansion(1f)

            // When not dismissable, no alpha value (null) should emit
            repository.setKeyguardDismissible(false)
            assertThat(dismissAlpha).isNull()

            repository.setKeyguardDismissible(true)
            assertThat(dismissAlpha).isGreaterThan(0.95f)
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
    fun animationDozingTransitions() =
        testScope.runTest {
            kosmos.fakeSceneContainerFlags.enabled = true
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
                    progress = flowOf(0f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            runCurrent()
            assertThat(isAnimate).isFalse()
        }
}
