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
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.AuthenticationFlags
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.util.KeyguardTransitionRepositorySpySubject.Companion.assertThat
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.reset
import org.mockito.Mockito.spy

@SmallTest
@RunWith(AndroidJUnit4::class)
class FromGoneTransitionInteractorTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            fakeKeyguardTransitionRepository = spy(FakeKeyguardTransitionRepository())
        }
    private val testScope = kosmos.testScope
    private val underTest = kosmos.fromGoneTransitionInteractor
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository

    @Before
    fun setUp() {
        underTest.start()
    }

    @Test
    @Ignore("Fails due to fix for b/324432820 - will re-enable once permanent fix is submitted.")
    fun testDoesNotTransitionToLockscreen_ifStartedButNotFinishedInGone() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    TransitionStep(
                        from = KeyguardState.LOCKSCREEN,
                        to = KeyguardState.GONE,
                        transitionState = TransitionState.STARTED,
                    ),
                    TransitionStep(
                        from = KeyguardState.LOCKSCREEN,
                        to = KeyguardState.GONE,
                        transitionState = TransitionState.RUNNING,
                    ),
                ),
                testScope,
            )
            reset(keyguardTransitionRepository)
            kosmos.fakeKeyguardRepository.setKeyguardShowing(true)
            runCurrent()

            // We're in the middle of a LOCKSCREEN -> GONE transition.
            assertThat(keyguardTransitionRepository).noTransitionsStarted()
        }

    @Test
    @DisableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionsToLockscreen_ifFinishedInGone() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope,
            )
            reset(keyguardTransitionRepository)
            kosmos.fakeKeyguardRepository.setKeyguardShowing(true)
            runCurrent()

            // We're in the middle of a GONE -> LOCKSCREEN transition.
            assertThat(keyguardTransitionRepository)
                .startedTransition(
                    to = KeyguardState.LOCKSCREEN,
                )
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun testTransitionsToLockscreen_ifFinishedInGone_wmRefactor() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope,
            )
            reset(keyguardTransitionRepository)

            // Trigger lockdown.
            kosmos.fakeBiometricSettingsRepository.setAuthenticationFlags(
                AuthenticationFlags(
                    0,
                    LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN
                )
            )
            runCurrent()

            // We're in the middle of a GONE -> LOCKSCREEN transition.
            assertThat(keyguardTransitionRepository)
                .startedTransition(
                    to = KeyguardState.LOCKSCREEN,
                )
        }
}
