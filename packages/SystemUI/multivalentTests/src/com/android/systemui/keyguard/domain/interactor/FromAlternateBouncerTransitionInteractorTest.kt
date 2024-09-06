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

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.util.KeyguardTransitionRepositorySpySubject.Companion.assertThat
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.testKosmos
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.reset

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class FromAlternateBouncerTransitionInteractorTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            this.fakeKeyguardTransitionRepository = Mockito.spy(FakeKeyguardTransitionRepository())
        }
    private val testScope = kosmos.testScope
    private lateinit var underTest: FromAlternateBouncerTransitionInteractor
    private lateinit var transitionRepository: FakeKeyguardTransitionRepository

    @Before
    fun setup() {
        transitionRepository = kosmos.fakeKeyguardTransitionRepository
        underTest = kosmos.fromAlternateBouncerTransitionInteractor
        underTest.start()
    }

    @Test
    @DisableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun transitionToGone_keyguardOccluded_biometricAuthenticated() =
        testScope.runTest {
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.OCCLUDED,
                to = KeyguardState.ALTERNATE_BOUNCER,
                testScope
            )
            reset(transitionRepository)

            kosmos.fakeKeyguardBouncerRepository.setKeyguardAuthenticatedBiometrics(null)
            kosmos.fakeKeyguardRepository.setKeyguardOccluded(true)
            runCurrent()
            assertThat(transitionRepository).noTransitionsStarted()

            kosmos.fakeKeyguardBouncerRepository.setKeyguardAuthenticatedBiometrics(true)
            runCurrent()
            kosmos.fakeKeyguardBouncerRepository.setKeyguardAuthenticatedBiometrics(null)
            runCurrent()

            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.ALTERNATE_BOUNCER, to = KeyguardState.GONE)
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun transitionToGone_keyguardOccludedThenAltBouncer_authed_wmStateRefactor() =
        testScope.runTest {
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.OCCLUDED,
                to = KeyguardState.ALTERNATE_BOUNCER,
                testScope
            )
            reset(transitionRepository)

            // Authentication results in calling startDismissKeyguardTransition.
            kosmos.keyguardTransitionInteractor.startDismissKeyguardTransition()
            runCurrent()

            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.ALTERNATE_BOUNCER, to = KeyguardState.GONE)
        }

    @Test
    fun noTransition_keyguardNotOccluded_biometricAuthenticated() =
        testScope.runTest {
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.OCCLUDED,
                to = KeyguardState.ALTERNATE_BOUNCER,
                testScope
            )
            reset(transitionRepository)

            kosmos.fakeKeyguardRepository.setKeyguardOccluded(false)
            kosmos.fakeKeyguardBouncerRepository.setKeyguardAuthenticatedBiometrics(true)
            runCurrent()
            kosmos.fakeKeyguardBouncerRepository.setKeyguardAuthenticatedBiometrics(null)
            runCurrent()

            assertThat(transitionRepository).noTransitionsStarted()
        }

    @Test
    fun transitionToOccluded() =
        testScope.runTest {
            kosmos.fakePowerRepository.updateWakefulness(
                WakefulnessState.AWAKE,
                WakeSleepReason.POWER_BUTTON,
                WakeSleepReason.POWER_BUTTON,
                false,
            )
            kosmos.fakeKeyguardRepository.setKeyguardOccluded(true)
            kosmos.fakeKeyguardBouncerRepository.setAlternateVisible(true)
            runCurrent()

            transitionRepository.sendTransitionSteps(
                from = KeyguardState.OCCLUDED,
                to = KeyguardState.ALTERNATE_BOUNCER,
                testScope
            )
            reset(transitionRepository)

            kosmos.fakeKeyguardBouncerRepository.setAlternateVisible(false)
            advanceTimeBy(200) // advance past delay

            assertThat(transitionRepository)
                .startedTransition(
                    from = KeyguardState.ALTERNATE_BOUNCER,
                    to = KeyguardState.OCCLUDED
                )
        }

    @Test
    fun transitionToGone_whenOpeningGlanceableHubEditMode() =
        testScope.runTest {
            kosmos.fakeKeyguardBouncerRepository.setAlternateVisible(true)
            runCurrent()

            // On Glanceable hub and edit mode activity is started
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.ALTERNATE_BOUNCER,
                testScope
            )
            reset(transitionRepository)

            kosmos.communalInteractor.setEditModeOpen(true)
            runCurrent()

            // Auth and alternate bouncer is hidden
            kosmos.fakeKeyguardBouncerRepository.setAlternateVisible(false)
            advanceTimeBy(200) // advance past delay

            // Then no transition should occur yet
            assertThat(transitionRepository).noTransitionsStarted()

            // When keyguard is going away
            kosmos.fakeKeyguardRepository.setKeyguardGoingAway(true)
            runCurrent()

            // Then transition to GONE should occur
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.ALTERNATE_BOUNCER, to = KeyguardState.GONE)
        }
}
