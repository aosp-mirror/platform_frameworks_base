/*
 * Copyright (C) 2024 The Android Open Source Project
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

/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.keyguard.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.deviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.keyguardOcclusionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.data.repository.Transition
import com.android.systemui.scene.data.repository.setSceneTransition
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.domain.interactor.keyguardOcclusionInteractor
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardOcclusionInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var underTest: KeyguardOcclusionInteractor
    private lateinit var powerInteractor: PowerInteractor
    private lateinit var transitionRepository: FakeKeyguardTransitionRepository

    @Before
    fun setUp() {
        powerInteractor = kosmos.powerInteractor
        transitionRepository = kosmos.fakeKeyguardTransitionRepository
        underTest = kosmos.keyguardOcclusionInteractor
    }

    @Test
    fun transitionFromPowerGesture_whileGoingToSleep_isTrue() =
        testScope.runTest {
            powerInteractor.setAwakeForTest()
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                testScope = testScope,
                throughTransitionState = TransitionState.RUNNING
            )

            powerInteractor.onCameraLaunchGestureDetected()
            runCurrent()

            assertTrue(underTest.shouldTransitionFromPowerButtonGesture())
        }

    @Test
    fun transitionFromPowerGesture_whileAsleep_isTrue() =
        testScope.runTest {
            powerInteractor.setAwakeForTest()
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                testScope = testScope,
            )

            powerInteractor.onCameraLaunchGestureDetected()
            runCurrent()

            assertTrue(underTest.shouldTransitionFromPowerButtonGesture())
        }

    @Test
    fun transitionFromPowerGesture_whileWaking_isFalse() =
        testScope.runTest {
            powerInteractor.setAwakeForTest()
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                testScope = testScope,
            )
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                testScope = testScope,
                throughTransitionState = TransitionState.RUNNING
            )

            powerInteractor.onCameraLaunchGestureDetected()
            runCurrent()

            assertFalse(underTest.shouldTransitionFromPowerButtonGesture())
        }

    @Test
    fun transitionFromPowerGesture_whileAwake_isFalse() =
        testScope.runTest {
            powerInteractor.setAwakeForTest()
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                testScope = testScope,
            )
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                testScope = testScope,
            )

            powerInteractor.onCameraLaunchGestureDetected()
            runCurrent()

            assertFalse(underTest.shouldTransitionFromPowerButtonGesture())
        }

    @Test
    fun showWhenLockedActivityLaunchedFromPowerGesture_notTrueSecondTime() =
        testScope.runTest {
            val values by collectValues(underTest.showWhenLockedActivityLaunchedFromPowerGesture)
            powerInteractor.setAsleepForTest()
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                testScope = testScope,
            )

            powerInteractor.onCameraLaunchGestureDetected()
            powerInteractor.setAwakeForTest()
            runCurrent()

            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(true)
            runCurrent()

            assertThat(values)
                .containsExactly(
                    false,
                    true,
                )

            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(false)
            runCurrent()

            assertThat(values)
                .containsExactly(
                    false,
                    true,
                    false,
                )

            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(true)
            runCurrent()

            assertThat(values)
                .containsExactly(
                    false,
                    true,
                    // Power button gesture was not triggered a second time, so this should remain
                    // false.
                    false,
                )
        }

    @Test
    @DisableSceneContainer
    fun showWhenLockedActivityLaunchedFromPowerGesture_falseIfReturningToGone() =
        testScope.runTest {
            val values by collectValues(underTest.showWhenLockedActivityLaunchedFromPowerGesture)
            powerInteractor.setAwakeForTest()
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope = testScope,
            )

            powerInteractor.setAsleepForTest()
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.AOD,
                testScope = testScope,
                throughTransitionState = TransitionState.RUNNING
            )

            powerInteractor.onCameraLaunchGestureDetected()
            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(true)
            powerInteractor.setAwakeForTest()
            runCurrent()

            transitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.GONE,
                testScope = testScope,
            )

            assertThat(values)
                .containsExactly(
                    false,
                )
        }

    @Test
    @EnableSceneContainer
    fun showWhenLockedActivityLaunchedFromPowerGesture_falseIfReturningToGone_scene_container() =
        testScope.runTest {
            val values by collectValues(underTest.showWhenLockedActivityLaunchedFromPowerGesture)
            powerInteractor.setAwakeForTest()
            kosmos.setSceneTransition(Transition(Scenes.Lockscreen, Scenes.Gone))

            powerInteractor.setAsleepForTest()

            kosmos.setSceneTransition(Transition(Scenes.Gone, Scenes.Lockscreen))
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.UNDEFINED,
                to = KeyguardState.AOD,
                testScope = testScope,
                throughTransitionState = TransitionState.RUNNING
            )

            powerInteractor.onCameraLaunchGestureDetected()
            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(true)
            powerInteractor.setAwakeForTest()
            runCurrent()

            kosmos.setSceneTransition(Transition(Scenes.Lockscreen, Scenes.Gone))
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.UNDEFINED,
                testScope = testScope,
            )

            assertThat(values)
                .containsExactly(
                    false,
                )
        }

    @Test
    @EnableSceneContainer
    fun occludingActivityWillDismissKeyguard() =
        testScope.runTest {
            val occludingActivityWillDismissKeyguard by
                collectLastValue(underTest.occludingActivityWillDismissKeyguard)
            assertThat(occludingActivityWillDismissKeyguard).isFalse()
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            runCurrent()

            // Unlock device:
            kosmos.deviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
            assertThat(occludingActivityWillDismissKeyguard).isTrue()

            // Re-lock device:
            kosmos.powerInteractor.setAsleepForTest()
            runCurrent()
            assertThat(occludingActivityWillDismissKeyguard).isFalse()
        }
}
