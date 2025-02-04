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
import android.provider.Settings
import android.service.dream.dreamManager
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.Flags.FLAG_COMMUNAL_SCENE_KTF_REFACTOR
import com.android.systemui.Flags.FLAG_GLANCEABLE_HUB_V2
import com.android.systemui.Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR
import com.android.systemui.Flags.FLAG_SCENE_CONTAINER
import com.android.systemui.Flags.glanceableHubV2
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.data.repository.batteryRepository
import com.android.systemui.common.data.repository.fake
import com.android.systemui.communal.data.repository.FakeCommunalSceneRepository
import com.android.systemui.communal.data.repository.communalSceneRepository
import com.android.systemui.communal.data.repository.fakeCommunalSceneRepository
import com.android.systemui.communal.domain.interactor.setCommunalAvailable
import com.android.systemui.communal.domain.interactor.setCommunalV2ConfigEnabled
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepositorySpy
import com.android.systemui.keyguard.data.repository.keyguardOcclusionRepository
import com.android.systemui.keyguard.data.repository.keyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.util.KeyguardTransitionRepositorySpySubject.Companion.assertThat
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
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

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@EnableFlags(FLAG_COMMUNAL_HUB)
class FromDozingTransitionInteractorTest(flags: FlagsParameterization?) : SysuiTestCase() {
    private val kosmos =
        testKosmos().useUnconfinedTestDispatcher().apply {
            this.keyguardTransitionRepository = fakeKeyguardTransitionRepositorySpy
            this.fakeCommunalSceneRepository =
                spy(FakeCommunalSceneRepository(applicationScope = applicationCoroutineScope))
        }

    private val Kosmos.underTest by Kosmos.Fixture { fromDozingTransitionInteractor }

    private val Kosmos.transitionRepository by
        Kosmos.Fixture { fakeKeyguardTransitionRepositorySpy }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf(
                FLAG_COMMUNAL_SCENE_KTF_REFACTOR,
                FLAG_GLANCEABLE_HUB_V2,
            )
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags!!)
    }

    @Before
    fun setup() {
        kosmos.underTest.start()

        // Transition to DOZING and set the power interactor asleep.
        kosmos.powerInteractor.setAsleepForTest()
        kosmos.setCommunalV2ConfigEnabled(true)
        runBlocking {
            kosmos.transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.DOZING,
                kosmos.testScope,
            )
            kosmos.fakeKeyguardRepository.setBiometricUnlockState(BiometricUnlockMode.NONE)
            reset(kosmos.transitionRepository)
        }
    }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToLockscreen_onWakeup() =
        kosmos.runTest {
            powerInteractor.setAwakeForTest()

            // Under default conditions, we should transition to LOCKSCREEN when waking up.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.LOCKSCREEN)
        }

    @Test
    @DisableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToGone_onWakeup_whenGoingAway() =
        kosmos.runTest {
            keyguardInteractor.setIsKeyguardGoingAway(true)

            powerInteractor.setAwakeForTest()
            testScope.advanceTimeBy(60L)

            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.GONE)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    @DisableFlags(FLAG_COMMUNAL_SCENE_KTF_REFACTOR, FLAG_GLANCEABLE_HUB_V2)
    fun testTransitionToLockscreen_onWake_canDream_glanceableHubAvailable() =
        kosmos.runTest {
            whenever(dreamManager.canStartDreaming(anyBoolean())).thenReturn(true)
            setCommunalAvailable(true)

            powerInteractor.setAwakeForTest()

            // If dreaming is possible and communal is available, then we should transition to
            // GLANCEABLE_HUB when waking up due to power button press.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.GLANCEABLE_HUB)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR, FLAG_COMMUNAL_SCENE_KTF_REFACTOR)
    fun testTransitionToLockscreen_onWake_canDream_ktfRefactor() =
        kosmos.runTest {
            setCommunalAvailable(true)
            if (glanceableHubV2()) {
                val user = fakeUserRepository.asMainUser()
                fakeSettings.putIntForUser(
                    Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP,
                    1,
                    user.id,
                )
                batteryRepository.fake.setDevicePluggedIn(true)
            } else {
                whenever(dreamManager.canStartDreaming(anyBoolean())).thenReturn(true)
            }

            clearInvocations(fakeCommunalSceneRepository)
            powerInteractor.setAwakeForTest()

            // If dreaming is possible and communal is available, then we should transition to
            // GLANCEABLE_HUB when waking up due to power button press.
            verify(fakeCommunalSceneRepository).snapToScene(CommunalScenes.Communal)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToLockscreen_onWake_canNotDream_glanceableHubAvailable() =
        kosmos.runTest {
            whenever(dreamManager.canStartDreaming(anyBoolean())).thenReturn(false)
            setCommunalAvailable(true)
            powerInteractor.setAwakeForTest()

            // If dreaming is NOT possible but communal is available, then we should transition to
            // LOCKSCREEN when waking up due to power button press.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.LOCKSCREEN)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToLockscreen_onWake_canNDream_glanceableHubNotAvailable() =
        kosmos.runTest {
            whenever(dreamManager.canStartDreaming(anyBoolean())).thenReturn(true)
            setCommunalAvailable(false)

            powerInteractor.setAwakeForTest()

            // If dreaming is possible but communal is NOT available, then we should transition to
            // LOCKSCREEN when waking up due to power button press.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.LOCKSCREEN)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    @DisableFlags(FLAG_COMMUNAL_SCENE_KTF_REFACTOR)
    fun testTransitionToGlanceableHub_onWakeup_ifIdleOnCommunal_noOccludingActivity() =
        kosmos.runTest {
            fakeCommunalSceneRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Communal))
            )

            powerInteractor.setAwakeForTest()

            // Under default conditions, we should transition to LOCKSCREEN when waking up.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.GLANCEABLE_HUB)
        }

    @Test
    @DisableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR, FLAG_SCENE_CONTAINER)
    @EnableFlags(FLAG_COMMUNAL_SCENE_KTF_REFACTOR)
    fun testTransitionToGlanceableHub_onWakeup_ifAvailable() =
        kosmos.runTest {
            setCommunalAvailable(true)
            if (glanceableHubV2()) {
                val user = fakeUserRepository.asMainUser()
                fakeSettings.putIntForUser(
                    Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP,
                    1,
                    user.id,
                )
                batteryRepository.fake.setDevicePluggedIn(true)
            } else {
                whenever(dreamManager.canStartDreaming(anyBoolean())).thenReturn(true)
            }

            // Device turns on.
            powerInteractor.setAwakeForTest()
            testScope.advanceTimeBy(51L)

            // We transition to the hub when waking up.
            Truth.assertThat(communalSceneRepository.currentScene.value)
                .isEqualTo(CommunalScenes.Communal)
            // No transitions are directly started by this interactor.
            assertThat(transitionRepository).noTransitionsStarted()
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToOccluded_onWakeup_whenOccludingActivityOnTop() =
        kosmos.runTest {
            keyguardOcclusionRepository.setShowWhenLockedActivityInfo(true)
            powerInteractor.setAwakeForTest()

            // Waking with a SHOW_WHEN_LOCKED activity on top should transition to OCCLUDED.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.OCCLUDED)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToOccluded_onWakeup_whenOccludingActivityOnTop_evenIfIdleOnCommunal() =
        kosmos.runTest {
            fakeCommunalSceneRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Communal))
            )

            keyguardOcclusionRepository.setShowWhenLockedActivityInfo(true)
            powerInteractor.setAwakeForTest()

            // Waking with a SHOW_WHEN_LOCKED activity on top should transition to OCCLUDED.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.OCCLUDED)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    @Suppress("ktlint:standard:max-line-length")
    fun testTransitionToOccluded_onWakeUp_ifPowerButtonGestureDetected_fromAod_nonDismissableKeyguard() =
        kosmos.runTest {
            powerInteractor.onCameraLaunchGestureDetected()
            powerInteractor.setAwakeForTest()

            // We should head back to GONE since we started there.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.OCCLUDED)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToGone_onWakeUp_ifPowerButtonGestureDetected_fromAod_dismissableKeyguard() =
        kosmos.runTest {
            fakeKeyguardRepository.setKeyguardDismissible(true)
            powerInteractor.onCameraLaunchGestureDetected()
            powerInteractor.setAwakeForTest()

            // We should head back to GONE since we started there.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.GONE)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToGone_onWakeUp_ifPowerButtonGestureDetected_fromGone() =
        kosmos.runTest {
            val isGone by
                collectLastValue(keyguardTransitionInteractor.isFinishedIn(Scenes.Gone, GONE))
            powerInteractor.setAwakeForTest()
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.DOZING,
                to = KeyguardState.GONE,
                testScope,
            )

            // Make sure we're GONE.
            assertEquals(true, isGone)

            // Get part way to AOD.
            powerInteractor.onStartedGoingToSleep(PowerManager.GO_TO_SLEEP_REASON_MIN)

            transitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.DOZING,
                testScope = testScope,
                throughTransitionState = TransitionState.RUNNING,
            )

            // Detect a power gesture and then wake up.
            fakeKeyguardRepository.setKeyguardDismissible(true)
            reset(transitionRepository)
            powerInteractor.onCameraLaunchGestureDetected()
            powerInteractor.setAwakeForTest()

            // We should head back to GONE since we started there.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.GONE)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    @Suppress("ktlint:standard:max-line-length")
    fun testTransitionToOccluded_onWakeUp_ifPowerButtonGestureDetectedAfterFinishedInAod_fromGone() =
        kosmos.runTest {
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

            // We should go to OCCLUDED - we came from GONE, but we finished in AOD, so this is no
            // longer an insecure camera launch and it would be bad if we unlocked now.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.OCCLUDED)
        }

    @Test
    @EnableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionToOccluded_onWakeUp_ifPowerButtonGestureDetected_fromLockscreen() =
        kosmos.runTest {
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

            // Make sure we're in LOCKSCREEN.
            assertEquals(true, isLockscreen)

            // Get part way to AOD.
            powerInteractor.onStartedGoingToSleep(PowerManager.GO_TO_SLEEP_REASON_MIN)

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

            // We should head back to GONE since we started there.
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DOZING, to = KeyguardState.OCCLUDED)
        }
}
