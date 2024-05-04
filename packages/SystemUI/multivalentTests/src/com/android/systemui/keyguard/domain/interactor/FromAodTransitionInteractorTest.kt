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

package com.android.systemui.keyguard.domain.interactor

import android.os.PowerManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.keyguardOcclusionRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.util.KeyguardTransitionRepositorySpySubject.Companion.assertThat
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.testKosmos
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.reset
import org.mockito.Mockito.spy

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class FromAodTransitionInteractorTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            this.fakeKeyguardTransitionRepository = spy(FakeKeyguardTransitionRepository())
        }

    private val testScope = kosmos.testScope
    private lateinit var underTest: FromAodTransitionInteractor

    private lateinit var powerInteractor: PowerInteractor
    private lateinit var transitionRepository: FakeKeyguardTransitionRepository

    @Before
    fun setup() {
        powerInteractor = kosmos.powerInteractor
        transitionRepository = kosmos.fakeKeyguardTransitionRepository
        underTest = kosmos.fromAodTransitionInteractor

        underTest.start()

        // Transition to AOD and set the power interactor asleep.
        powerInteractor.setAsleepForTest()
        runBlocking {
            kosmos.fakeKeyguardRepository.setKeyguardShowing(false)
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                testScope
            )
            kosmos.fakeKeyguardRepository.setBiometricUnlockState(BiometricUnlockMode.NONE)
            reset(transitionRepository)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToLockscreen_onWakeup() =
        testScope.runTest {
            powerInteractor.setAwakeForTest()
            advanceTimeBy(100) // account for debouncing

            // Under default conditions, we should transition to LOCKSCREEN when waking up.
            assertThat(transitionRepository)
                .startedTransition(
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                )
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToOccluded_onWakeup_whenOccludingActivityOnTop() =
        testScope.runTest {
            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(true)
            powerInteractor.setAwakeForTest()
            advanceTimeBy(100) // account for debouncing

            // Waking with a SHOW_WHEN_LOCKED activity on top should transition to OCCLUDED.
            assertThat(transitionRepository)
                .startedTransition(
                    from = KeyguardState.AOD,
                    to = KeyguardState.OCCLUDED,
                )
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToOccluded_onWakeUp_ifPowerButtonGestureDetected_fromAod_nonDismissableKeyguard() =
        testScope.runTest {
            powerInteractor.onCameraLaunchGestureDetected()
            powerInteractor.setAwakeForTest()
            advanceTimeBy(100) // account for debouncing

            // We should head back to GONE since we started there.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.AOD, to = KeyguardState.OCCLUDED)
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToGone_onWakeUp_ifPowerButtonGestureDetected_fromAod_dismissableKeyguard() =
        testScope.runTest {
            kosmos.fakeKeyguardRepository.setKeyguardDismissible(true)
            powerInteractor.onCameraLaunchGestureDetected()
            powerInteractor.setAwakeForTest()
            advanceTimeBy(100) // account for debouncing

            // We should head back to GONE since we started there.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.AOD, to = KeyguardState.GONE)
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToGone_onWakeUp_ifPowerButtonGestureDetected_fromGone() =
        testScope.runTest {
            powerInteractor.setAwakeForTest()
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.GONE,
                testScope,
            )
            runCurrent()

            // Make sure we're GONE.
            assertEquals(KeyguardState.GONE, kosmos.keyguardTransitionInteractor.getFinishedState())

            // Get part way to AOD.
            powerInteractor.onStartedGoingToSleep(PowerManager.GO_TO_SLEEP_REASON_MIN)
            runCurrent()

            transitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.AOD,
                testScope = testScope,
                throughTransitionState = TransitionState.RUNNING,
            )

            // Detect a power gesture and then wake up.
            reset(transitionRepository)
            powerInteractor.onCameraLaunchGestureDetected()
            powerInteractor.setAwakeForTest()
            advanceTimeBy(100) // account for debouncing

            // We should head back to GONE since we started there.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.AOD, to = KeyguardState.GONE)
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToOccluded_onWakeUp_ifPowerButtonGestureDetectedAfterFinishedInAod_fromGone() =
        testScope.runTest {
            powerInteractor.setAwakeForTest()
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.GONE,
                testScope,
            )
            runCurrent()

            // Make sure we're GONE.
            assertEquals(KeyguardState.GONE, kosmos.keyguardTransitionInteractor.getFinishedState())

            // Get all the way to AOD
            powerInteractor.onStartedGoingToSleep(PowerManager.GO_TO_SLEEP_REASON_MIN)
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.AOD,
                testScope = testScope,
            )

            // Detect a power gesture and then wake up.
            reset(transitionRepository)
            powerInteractor.onCameraLaunchGestureDetected()
            powerInteractor.setAwakeForTest()
            advanceTimeBy(100) // account for debouncing

            // We should go to OCCLUDED - we came from GONE, but we finished in AOD, so this is no
            // longer an insecure camera launch and it would be bad if we unlocked now.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.AOD, to = KeyguardState.OCCLUDED)
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToOccluded_onWakeUp_ifPowerButtonGestureDetected_fromLockscreen() =
        testScope.runTest {
            powerInteractor.setAwakeForTest()
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )
            runCurrent()

            // Make sure we're in LOCKSCREEN.
            assertEquals(
                KeyguardState.LOCKSCREEN,
                kosmos.keyguardTransitionInteractor.getFinishedState()
            )

            // Get part way to AOD.
            powerInteractor.onStartedGoingToSleep(PowerManager.GO_TO_SLEEP_REASON_MIN)
            runCurrent()

            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                testScope = testScope,
                throughTransitionState = TransitionState.RUNNING,
            )

            // Detect a power gesture and then wake up.
            reset(transitionRepository)
            powerInteractor.onCameraLaunchGestureDetected()
            powerInteractor.setAwakeForTest()
            advanceTimeBy(100) // account for debouncing

            // We should head back to GONE since we started there.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.AOD, to = KeyguardState.OCCLUDED)
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testWakeAndUnlock_transitionsToGone_onlyAfterDismissCallPostWakeup() =
        testScope.runTest {
            kosmos.fakeKeyguardRepository.setBiometricUnlockState(
                BiometricUnlockMode.WAKE_AND_UNLOCK
            )
            powerInteractor.setAwakeForTest()
            runCurrent()

            // Waking up from wake and unlock should not start any transitions, we'll wait for the
            // dismiss call.
            assertThat(transitionRepository).noTransitionsStarted()

            underTest.dismissAod()
            advanceTimeBy(100) // account for debouncing

            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.AOD, to = KeyguardState.GONE)
        }

    @Test
    fun testTransitionToOccluded_onWake() =
        testScope.runTest {
            kosmos.fakeKeyguardRepository.setKeyguardOccluded(true)
            powerInteractor.setAwakeForTest()
            advanceTimeBy(100) // account for debouncing

            // Waking up from AOD while occluded should transition to OCCLUDED.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.AOD, to = KeyguardState.OCCLUDED)
        }

    @Test
    fun testTransitionToGone_onWakeUpFromAod_dismissibleKeyguard_securityNone() =
        testScope.runTest {
            kosmos.fakeKeyguardRepository.setKeyguardShowing(false)
            kosmos.fakeKeyguardRepository.setKeyguardDismissible(true)
            powerInteractor.setAwakeForTest()
            advanceTimeBy(100) // account for debouncing

            // We should head back to GONE since we started there.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.AOD, to = KeyguardState.GONE)
        }

    @Test
    @DisableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToGone_onWakeUp_ifPowerButtonGestureDetectedInAod_fromGone() =
        testScope.runTest {
            powerInteractor.setAwakeForTest()
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.GONE,
                testScope,
            )
            runCurrent()

            // Make sure we're GONE.
            assertEquals(KeyguardState.GONE, kosmos.keyguardTransitionInteractor.getFinishedState())

            // Start going to AOD on first button push
            powerInteractor.onStartedGoingToSleep(PowerManager.GO_TO_SLEEP_REASON_MIN)
            transitionRepository.sendTransitionSteps(
                listOf(
                    TransitionStep(
                        from = KeyguardState.GONE,
                        to = KeyguardState.AOD,
                        transitionState = TransitionState.STARTED,
                        value = 0f
                    ),
                    TransitionStep(
                        from = KeyguardState.GONE,
                        to = KeyguardState.AOD,
                        transitionState = TransitionState.RUNNING,
                        value = 0.1f
                    ),
                ),
                testScope = testScope,
            )

            // Detect a power gesture and then wake up.
            reset(transitionRepository)
            powerInteractor.onCameraLaunchGestureDetected()
            powerInteractor.setAwakeForTest()
            advanceTimeBy(100) // account for debouncing

            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.AOD, to = KeyguardState.GONE)
        }

    @Test
    fun testTransitionToLockscreen_onWakeUpFromAod_dismissibleKeyguard_securitySwipe() =
        testScope.runTest {
            kosmos.fakeKeyguardRepository.setKeyguardShowing(true)
            kosmos.fakeKeyguardRepository.setKeyguardDismissible(true)
            powerInteractor.setAwakeForTest()
            advanceTimeBy(100) // account for debouncing

            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.AOD, to = KeyguardState.LOCKSCREEN)
        }
}
