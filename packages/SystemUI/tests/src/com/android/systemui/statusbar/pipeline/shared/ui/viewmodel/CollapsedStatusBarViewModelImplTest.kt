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
 */

package com.android.systemui.statusbar.pipeline.shared.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class CollapsedStatusBarViewModelImplTest : SysuiTestCase() {

    private lateinit var underTest: CollapsedStatusBarViewModel

    private lateinit var keyguardTransitionRepository: FakeKeyguardTransitionRepository
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        testScope = TestScope(UnconfinedTestDispatcher())

        keyguardTransitionRepository = FakeKeyguardTransitionRepository()
        val interactor = KeyguardTransitionInteractor(keyguardTransitionRepository)
        underTest = CollapsedStatusBarViewModelImpl(interactor, testScope.backgroundScope)
    }

    @Test
    fun isTransitioningFromLockscreenToOccluded_started_isTrue() =
        testScope.runTest {
            val job = underTest.isTransitioningFromLockscreenToOccluded.launchIn(this)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.OCCLUDED,
                    value = 0f,
                    TransitionState.STARTED,
                )
            )

            assertThat(underTest.isTransitioningFromLockscreenToOccluded.value).isTrue()

            job.cancel()
        }

    @Test
    fun isTransitioningFromLockscreenToOccluded_running_isTrue() =
        testScope.runTest {
            val job = underTest.isTransitioningFromLockscreenToOccluded.launchIn(this)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.OCCLUDED,
                    value = 0f,
                    TransitionState.RUNNING,
                )
            )

            assertThat(underTest.isTransitioningFromLockscreenToOccluded.value).isTrue()

            job.cancel()
        }

    @Test
    fun isTransitioningFromLockscreenToOccluded_finished_isFalse() =
        testScope.runTest {
            val job = underTest.isTransitioningFromLockscreenToOccluded.launchIn(this)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.OCCLUDED,
                    value = 0f,
                    TransitionState.FINISHED,
                )
            )

            assertThat(underTest.isTransitioningFromLockscreenToOccluded.value).isFalse()

            job.cancel()
        }

    @Test
    fun isTransitioningFromLockscreenToOccluded_canceled_isFalse() =
        testScope.runTest {
            val job = underTest.isTransitioningFromLockscreenToOccluded.launchIn(this)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.OCCLUDED,
                    value = 0f,
                    TransitionState.CANCELED,
                )
            )

            assertThat(underTest.isTransitioningFromLockscreenToOccluded.value).isFalse()

            job.cancel()
        }

    @Test
    fun isTransitioningFromLockscreenToOccluded_irrelevantTransition_isFalse() =
        testScope.runTest {
            val job = underTest.isTransitioningFromLockscreenToOccluded.launchIn(this)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.AOD,
                    KeyguardState.LOCKSCREEN,
                    value = 0f,
                    TransitionState.RUNNING,
                )
            )

            assertThat(underTest.isTransitioningFromLockscreenToOccluded.value).isFalse()

            job.cancel()
        }

    @Test
    fun isTransitioningFromLockscreenToOccluded_followsRepoUpdates() =
        testScope.runTest {
            val job = underTest.isTransitioningFromLockscreenToOccluded.launchIn(this)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.OCCLUDED,
                    value = 0f,
                    TransitionState.RUNNING,
                )
            )

            assertThat(underTest.isTransitioningFromLockscreenToOccluded.value).isTrue()

            // WHEN the repo updates the transition to finished
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.OCCLUDED,
                    value = 0f,
                    TransitionState.FINISHED,
                )
            )

            // THEN our manager also updates
            assertThat(underTest.isTransitioningFromLockscreenToOccluded.value).isFalse()

            job.cancel()
        }
}
