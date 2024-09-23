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
import android.platform.test.flag.junit.FlagsParameterization
import android.service.dream.dreamManager
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.Flags.FLAG_COMMUNAL_SCENE_KTF_REFACTOR
import com.android.systemui.Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR
import com.android.systemui.Flags.FLAG_SCENE_CONTAINER
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.FakeCommunalSceneRepository
import com.android.systemui.communal.data.repository.communalSceneRepository
import com.android.systemui.communal.data.repository.fakeCommunalSceneRepository
import com.android.systemui.communal.domain.interactor.setCommunalAvailable
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.keyguardOcclusionRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.util.KeyguardTransitionRepositorySpySubject.Companion.assertThat
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.google.common.truth.Truth
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.reset
import org.mockito.Mockito.spy
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@EnableFlags(FLAG_COMMUNAL_HUB)
class FromDozingTransitionInteractorTest(flags: FlagsParameterization?) : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            this.fakeKeyguardTransitionRepository = spy(FakeKeyguardTransitionRepository())
            this.fakeCommunalSceneRepository =
                spy(FakeCommunalSceneRepository(applicationScope = applicationCoroutineScope))
        }

    private val testScope = kosmos.testScope
    private lateinit var underTest: FromDozingTransitionInteractor

    private lateinit var powerInteractor: PowerInteractor
    private lateinit var transitionRepository: FakeKeyguardTransitionRepository

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf(FLAG_COMMUNAL_SCENE_KTF_REFACTOR)
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags!!)
    }

    @Before
    fun setup() {
        powerInteractor = kosmos.powerInteractor
        transitionRepository = kosmos.fakeKeyguardTransitionRepository
        underTest = kosmos.fromDozingTransitionInteractor

        underTest.start()

        // Transition to DOZING and set the power interactor asleep.
        powerInteractor.setAsleepForTest()
        runBlocking {
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.DOZING,
                testScope,
            )
            kosmos.fakeKeyguardRepository.setBiometricUnlockState(BiometricUnlockMode.NONE)
            reset(transitionRepository)
        }
    }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToLockscreen_onWakeup() =
        testScope.runTest {
            powerInteractor.setAwakeForTest()
            runCurrent()

            // Under default conditions, we should transition to LOCKSCREEN when waking up.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.LOCKSCREEN)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    @DisableFlags(FLAG_COMMUNAL_SCENE_KTF_REFACTOR)
    fun testTransitionToLockscreen_onWake_canDream_glanceableHubAvailable() =
        testScope.runTest {
            whenever(kosmos.dreamManager.canStartDreaming(anyBoolean())).thenReturn(true)
            kosmos.setCommunalAvailable(true)
            runCurrent()

            powerInteractor.setAwakeForTest()
            runCurrent()

            // If dreaming is possible and communal is available, then we should transition to
            // GLANCEABLE_HUB when waking up due to power button press.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.GLANCEABLE_HUB)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR, FLAG_COMMUNAL_SCENE_KTF_REFACTOR)
    fun testTransitionToLockscreen_onWake_canDream_ktfRefactor() =
        testScope.runTest {
            whenever(kosmos.dreamManager.canStartDreaming(anyBoolean())).thenReturn(true)
            kosmos.setCommunalAvailable(true)
            runCurrent()
            clearInvocations(kosmos.fakeCommunalSceneRepository)

            powerInteractor.setAwakeForTest()
            runCurrent()

            // If dreaming is possible and communal is available, then we should transition to
            // GLANCEABLE_HUB when waking up due to power button press.
            verify(kosmos.fakeCommunalSceneRepository).snapToScene(CommunalScenes.Communal)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToLockscreen_onWake_canNotDream_glanceableHubAvailable() =
        testScope.runTest {
            whenever(kosmos.dreamManager.canStartDreaming(anyBoolean())).thenReturn(false)
            kosmos.setCommunalAvailable(true)
            runCurrent()

            powerInteractor.setAwakeForTest()
            runCurrent()

            // If dreaming is NOT possible but communal is available, then we should transition to
            // LOCKSCREEN when waking up due to power button press.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.LOCKSCREEN)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToLockscreen_onWake_canNDream_glanceableHubNotAvailable() =
        testScope.runTest {
            whenever(kosmos.dreamManager.canStartDreaming(anyBoolean())).thenReturn(true)
            kosmos.setCommunalAvailable(false)
            runCurrent()

            powerInteractor.setAwakeForTest()
            runCurrent()

            // If dreaming is possible but communal is NOT available, then we should transition to
            // LOCKSCREEN when waking up due to power button press.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.LOCKSCREEN)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    @DisableFlags(FLAG_COMMUNAL_SCENE_KTF_REFACTOR)
    fun testTransitionToGlanceableHub_onWakeup_ifIdleOnCommunal_noOccludingActivity() =
        testScope.runTest {
            kosmos.fakeCommunalSceneRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Communal))
            )
            runCurrent()

            powerInteractor.setAwakeForTest()
            runCurrent()

            // Under default conditions, we should transition to LOCKSCREEN when waking up.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.GLANCEABLE_HUB)
        }

    @Test
    @DisableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR, FLAG_SCENE_CONTAINER)
    @EnableFlags(FLAG_COMMUNAL_SCENE_KTF_REFACTOR)
    fun testTransitionToGlanceableHub_onWakeup_ifAvailable() =
        testScope.runTest {
            // Hub is available.
            whenever(kosmos.dreamManager.canStartDreaming(anyBoolean())).thenReturn(true)
            kosmos.setCommunalAvailable(true)
            runCurrent()

            // Device turns on.
            powerInteractor.setAwakeForTest()
            advanceTimeBy(50L)
            runCurrent()

            // We transition to the hub when waking up.
            Truth.assertThat(kosmos.communalSceneRepository.currentScene.value)
                .isEqualTo(CommunalScenes.Communal)
            // No transitions are directly started by this interactor.
            assertThat(transitionRepository).noTransitionsStarted()
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToOccluded_onWakeup_whenOccludingActivityOnTop() =
        testScope.runTest {
            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(true)
            powerInteractor.setAwakeForTest()
            runCurrent()

            // Waking with a SHOW_WHEN_LOCKED activity on top should transition to OCCLUDED.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.OCCLUDED)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToOccluded_onWakeup_whenOccludingActivityOnTop_evenIfIdleOnCommunal() =
        testScope.runTest {
            kosmos.fakeCommunalSceneRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Communal))
            )
            runCurrent()

            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(true)
            powerInteractor.setAwakeForTest()
            runCurrent()

            // Waking with a SHOW_WHEN_LOCKED activity on top should transition to OCCLUDED.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.OCCLUDED)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    @Suppress("ktlint:standard:max-line-length")
    fun testTransitionToOccluded_onWakeUp_ifPowerButtonGestureDetected_fromAod_nonDismissableKeyguard() =
        testScope.runTest {
            powerInteractor.onCameraLaunchGestureDetected()
            powerInteractor.setAwakeForTest()
            runCurrent()

            // We should head back to GONE since we started there.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.OCCLUDED)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToGone_onWakeUp_ifPowerButtonGestureDetected_fromAod_dismissableKeyguard() =
        testScope.runTest {
            kosmos.fakeKeyguardRepository.setKeyguardDismissible(true)
            powerInteractor.onCameraLaunchGestureDetected()
            powerInteractor.setAwakeForTest()
            runCurrent()

            // We should head back to GONE since we started there.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.GONE)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToGone_onWakeUp_ifPowerButtonGestureDetected_fromGone() =
        testScope.runTest {
            val isGone by
                collectLastValue(
                    kosmos.keyguardTransitionInteractor.isFinishedIn(Scenes.Gone, GONE)
                )
            powerInteractor.setAwakeForTest()
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.DOZING,
                to = KeyguardState.GONE,
                testScope,
            )
            runCurrent()

            // Make sure we're GONE.
            assertEquals(true, isGone)

            // Get part way to AOD.
            powerInteractor.onStartedGoingToSleep(PowerManager.GO_TO_SLEEP_REASON_MIN)
            runCurrent()

            transitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.DOZING,
                testScope = testScope,
                throughTransitionState = TransitionState.RUNNING,
            )

            // Detect a power gesture and then wake up.
            kosmos.fakeKeyguardRepository.setKeyguardDismissible(true)
            reset(transitionRepository)
            powerInteractor.onCameraLaunchGestureDetected()
            powerInteractor.setAwakeForTest()
            runCurrent()

            // We should head back to GONE since we started there.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.GONE)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    @Suppress("ktlint:standard:max-line-length")
    fun testTransitionToOccluded_onWakeUp_ifPowerButtonGestureDetectedAfterFinishedInAod_fromGone() =
        testScope.runTest {
            val isGone by
                collectLastValue(
                    kosmos.keyguardTransitionInteractor.isFinishedIn(Scenes.Gone, GONE)
                )
            powerInteractor.setAwakeForTest()
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.DOZING,
                to = KeyguardState.GONE,
                testScope,
            )
            runCurrent()

            // Make sure we're GONE.
            assertEquals(true, isGone)

            // Get all the way to AOD
            powerInteractor.onStartedGoingToSleep(PowerManager.GO_TO_SLEEP_REASON_MIN)
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.DOZING,
                testScope = testScope,
            )

            // Detect a power gesture and then wake up.
            reset(transitionRepository)
            powerInteractor.onCameraLaunchGestureDetected()
            powerInteractor.setAwakeForTest()
            runCurrent()

            // We should go to OCCLUDED - we came from GONE, but we finished in AOD, so this is no
            // longer an insecure camera launch and it would be bad if we unlocked now.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.OCCLUDED)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToOccluded_onWakeUp_ifPowerButtonGestureDetected_fromLockscreen() =
        testScope.runTest {
            val isLockscreen by
                collectLastValue(
                    kosmos.keyguardTransitionInteractor.isFinishedIn(Scenes.Lockscreen, LOCKSCREEN)
                )
            powerInteractor.setAwakeForTest()
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.DOZING,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )
            runCurrent()

            // Make sure we're in LOCKSCREEN.
            assertEquals(true, isLockscreen)

            // Get part way to AOD.
            powerInteractor.onStartedGoingToSleep(PowerManager.GO_TO_SLEEP_REASON_MIN)
            runCurrent()

            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.DOZING,
                testScope = testScope,
                throughTransitionState = TransitionState.RUNNING,
            )

            // Detect a power gesture and then wake up.
            reset(transitionRepository)
            powerInteractor.onCameraLaunchGestureDetected()
            powerInteractor.setAwakeForTest()
            runCurrent()

            // We should head back to GONE since we started there.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.OCCLUDED)
        }
}
