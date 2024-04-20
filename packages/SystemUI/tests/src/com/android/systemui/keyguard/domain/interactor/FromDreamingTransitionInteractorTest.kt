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

import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.keyguardOcclusionRepository
import com.android.systemui.keyguard.data.repository.keyguardRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.util.KeyguardTransitionRepositorySpySubject.Companion.assertThat
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.testKosmos
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito.reset
import org.mockito.Mockito.spy

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class FromDreamingTransitionInteractorTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            this.fakeKeyguardTransitionRepository = spy(FakeKeyguardTransitionRepository())
        }

    private val testScope = kosmos.testScope
    private val underTest = kosmos.fromDreamingTransitionInteractor

    private val powerInteractor = kosmos.powerInteractor
    private val transitionRepository = kosmos.fakeKeyguardTransitionRepository

    @Before
    fun setup() {
        underTest.start()

        // Transition to DOZING and set the power interactor asleep.
        powerInteractor.setAsleepForTest()
        runBlocking {
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.DREAMING,
                testScope
            )
            kosmos.keyguardRepository.setBiometricUnlockState(BiometricUnlockModel.NONE)
            reset(transitionRepository)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
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
                .startedTransition(
                    from = KeyguardState.DREAMING,
                    to = KeyguardState.OCCLUDED,
                )
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
            kosmos.fakeKeyguardRepository.setDreaming(true)
            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(onTop = true)
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.DREAMING,
                testScope,
            )
            runCurrent()

            reset(transitionRepository)

            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(onTop = false)
            runCurrent()

            assertThat(transitionRepository)
                .startedTransition(
                    from = KeyguardState.DREAMING,
                    to = KeyguardState.LOCKSCREEN,
                )
        }

    @Test
    fun testTransitionToAlternateBouncer() =
        testScope.runTest {
            kosmos.fakeKeyguardBouncerRepository.setAlternateVisible(true)
            runCurrent()

            assertThat(transitionRepository)
                .startedTransition(
                    from = KeyguardState.DREAMING,
                    to = KeyguardState.ALTERNATE_BOUNCER,
                )
        }
}
