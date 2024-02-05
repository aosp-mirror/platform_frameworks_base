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

package com.android.systemui.keyguard.domain.interactor

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.util.KeyguardTransitionRepositorySpySubject.Companion.assertThat as assertThatRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.data.repository.FlingInfo
import com.android.systemui.shade.data.repository.fakeShadeRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class FromLockscreenTransitionInteractorTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            fakeKeyguardTransitionRepository = spy(FakeKeyguardTransitionRepository())
        }

    private val testScope = kosmos.testScope
    private val underTest = kosmos.fromLockscreenTransitionInteractor
    private val transitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val shadeRepository = kosmos.fakeShadeRepository
    private val keyguardRepository = kosmos.fakeKeyguardRepository

    @Test
    fun testSurfaceBehindVisibility() =
        testScope.runTest {
            val values by collectValues(underTest.surfaceBehindVisibility)
            runCurrent()

            // Transition-specific surface visibility should be null ("don't care") initially.
            assertThat(values).containsExactly(null)

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                )
            )
            runCurrent()

            assertThat(values)
                .containsExactly(
                    null, // LOCKSCREEN -> AOD does not have any specific surface visibility.
                )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                )
            )
            runCurrent()

            assertThat(values)
                .containsExactly(
                    null,
                    true, // Surface is made visible immediately during LOCKSCREEN -> GONE
                )
                .inOrder()

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.FINISHED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                )
            )
            runCurrent()

            assertThat(values)
                .containsExactly(
                    null,
                    true, // Surface remains visible.
                )
                .inOrder()
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionsToGone_whenDismissFlingWhileDismissable_flagEnabled() =
        testScope.runTest {
            underTest.start()
            verify(transitionRepository, never()).startTransition(any())

            keyguardRepository.setKeyguardDismissible(true)
            runCurrent()
            shadeRepository.setCurrentFling(
                FlingInfo(expand = true) // Not a dismiss fling (expand = true).
            )
            runCurrent()

            assertThatRepository(transitionRepository)
                .startedTransition(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                )
        }

    @Test
    @DisableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testDoesNotTransitionToGone_whenDismissFlingWhileDismissable_flagDisabled() =
        testScope.runTest {
            underTest.start()
            verify(transitionRepository, never()).startTransition(any())

            keyguardRepository.setKeyguardDismissible(true)
            runCurrent()
            shadeRepository.setCurrentFling(
                FlingInfo(expand = true) // Not a dismiss fling (expand = true).
            )
            runCurrent()

            assertThatRepository(transitionRepository).noTransitionsStarted()
        }
}
