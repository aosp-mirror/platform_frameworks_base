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

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import android.service.dream.dreamManager
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.Flags.FLAG_COMMUNAL_SCENE_KTF_REFACTOR
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.communal.data.repository.communalSceneRepository
import com.android.systemui.communal.domain.interactor.setCommunalAvailable
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.keyguardOcclusionRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.util.KeyguardTransitionRepositorySpySubject.Companion.assertThat
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.statusbar.domain.interactor.keyguardOcclusionInteractor
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.reset
import org.mockito.Mockito.spy
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class FromDreamingTransitionInteractorTest(flags: FlagsParameterization?) : SysuiTestCase() {
    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf(FLAG_COMMUNAL_SCENE_KTF_REFACTOR)
                .andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags!!)
    }

    private val kosmos =
        testKosmos().apply {
            this.fakeKeyguardTransitionRepository = spy(FakeKeyguardTransitionRepository())
        }

    private val testScope = kosmos.testScope
    private val underTest by lazy { kosmos.fromDreamingTransitionInteractor }

    private val powerInteractor = kosmos.powerInteractor
    private val transitionRepository = kosmos.fakeKeyguardTransitionRepository

    @Before
    fun setup() {
        runBlocking {
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.DREAMING,
                testScope,
            )
            reset(transitionRepository)
            kosmos.setCommunalAvailable(true)
        }
        underTest.start()
    }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    @Ignore("Until b/349837588 is fixed")
    fun testTransitionToOccluded_ifDreamEnds_occludingActivityOnTop() =
        testScope.runTest {
            kosmos.fakeKeyguardRepository.setDreaming(true)
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.DREAMING,
                testScope,
            )
            runCurrent()

            reset(transitionRepository)

            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(onTop = true)
            kosmos.fakeKeyguardRepository.setDreaming(false)
            runCurrent()

            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DREAMING, to = KeyguardState.OCCLUDED)
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testDoesNotTransitionToOccluded_occludingActivityOnTop_whileStillDreaming() =
        testScope.runTest {
            kosmos.fakeKeyguardRepository.setDreaming(true)
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.DREAMING,
                testScope,
            )
            runCurrent()

            reset(transitionRepository)

            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(onTop = true)
            runCurrent()

            assertThat(transitionRepository).noTransitionsStarted()
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionsToLockscreen_whenOccludingActivityEnds() =
        testScope.runTest {
            runCurrent()

            kosmos.fakeKeyguardRepository.setDreaming(true)
            kosmos.keyguardOcclusionInteractor.setWmNotifiedShowWhenLockedActivityOnTop(true)
            // Transition to DREAMING and set the power interactor awake
            powerInteractor.setAwakeForTest()

            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.DREAMING,
                testScope,
            )
            kosmos.fakeKeyguardRepository.setBiometricUnlockState(BiometricUnlockMode.NONE)

            // Get past initial setup
            advanceTimeBy(600L)
            reset(transitionRepository)

            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(onTop = false)
            kosmos.fakeKeyguardRepository.setDreaming(false)
            advanceTimeBy(60L)

            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.DREAMING, to = KeyguardState.LOCKSCREEN)
        }

    @Test
    fun testTransitionToAlternateBouncer() =
        testScope.runTest {
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.DREAMING,
                testScope,
            )
            reset(transitionRepository)

            kosmos.fakeKeyguardBouncerRepository.setAlternateVisible(true)
            runCurrent()

            assertThat(transitionRepository)
                .startedTransition(
                    from = KeyguardState.DREAMING,
                    to = KeyguardState.ALTERNATE_BOUNCER,
                )
        }

    @Test
    @EnableFlags(FLAG_COMMUNAL_SCENE_KTF_REFACTOR)
    @DisableFlags(Flags.FLAG_SCENE_CONTAINER)
    fun testTransitionToGlanceableHubOnWake() =
        testScope.runTest {
            whenever(kosmos.dreamManager.canStartDreaming(anyBoolean())).thenReturn(true)
            kosmos.setCommunalAvailable(true)
            runCurrent()

            // Device wakes up.
            powerInteractor.setAwakeForTest()
            advanceTimeBy(150L)
            runCurrent()

            // We transition to the hub when waking up.
            assertThat(kosmos.communalSceneRepository.currentScene.value)
                .isEqualTo(CommunalScenes.Communal)
            // No transitions are directly started by this interactor.
            assertThat(transitionRepository).noTransitionsStarted()
        }
}
