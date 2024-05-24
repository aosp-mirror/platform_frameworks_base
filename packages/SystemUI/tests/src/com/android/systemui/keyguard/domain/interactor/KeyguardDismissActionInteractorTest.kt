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
 *
 */

package com.android.systemui.keyguard.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.DismissAction
import com.android.systemui.keyguard.shared.model.KeyguardDone
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardDismissActionInteractorTest : SysuiTestCase() {
    val kosmos = testKosmos()

    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val transitionRepository = kosmos.fakeKeyguardTransitionRepository

    private val testScope = kosmos.testScope

    private lateinit var dismissInteractorWithDependencies:
        KeyguardDismissInteractorFactory.WithDependencies
    private lateinit var underTest: KeyguardDismissActionInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        dismissInteractorWithDependencies =
            KeyguardDismissInteractorFactory.create(
                context = context,
                testScope = testScope,
                keyguardRepository = keyguardRepository,
            )

        underTest =
            KeyguardDismissActionInteractor(
                keyguardRepository,
                kosmos.keyguardTransitionInteractor,
                dismissInteractorWithDependencies.interactor,
                testScope.backgroundScope,
            )
    }

    @Test
    fun updateDismissAction_onRepoChange() =
        testScope.runTest {
            val dismissAction by collectLastValue(underTest.dismissAction)

            val newDismissAction =
                DismissAction.RunImmediately(
                    onDismissAction = { KeyguardDone.IMMEDIATE },
                    onCancelAction = {},
                    message = "",
                    willAnimateOnLockscreen = true,
                )
            keyguardRepository.setDismissAction(newDismissAction)
            assertThat(dismissAction).isEqualTo(newDismissAction)
        }

    @Test
    fun messageUpdate() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)
            keyguardRepository.setDismissAction(
                DismissAction.RunImmediately(
                    onDismissAction = { KeyguardDone.IMMEDIATE },
                    onCancelAction = {},
                    message = "message",
                    willAnimateOnLockscreen = true,
                )
            )
            assertThat(message).isEqualTo("message")
        }

    @Test
    fun runDismissAnimationOnKeyguard_defaultStateFalse() =
        testScope.runTest { assertThat(underTest.runDismissAnimationOnKeyguard()).isFalse() }

    @Test
    fun runDismissAnimationOnKeyguardUpdates() =
        testScope.runTest {
            val animate by collectLastValue(underTest.willAnimateDismissActionOnLockscreen)
            keyguardRepository.setDismissAction(
                DismissAction.RunImmediately(
                    onDismissAction = { KeyguardDone.IMMEDIATE },
                    onCancelAction = {},
                    message = "message",
                    willAnimateOnLockscreen = true,
                )
            )
            assertThat(animate).isEqualTo(true)

            keyguardRepository.setDismissAction(
                DismissAction.RunImmediately(
                    onDismissAction = { KeyguardDone.IMMEDIATE },
                    onCancelAction = {},
                    message = "message",
                    willAnimateOnLockscreen = false,
                )
            )
            assertThat(animate).isEqualTo(false)
        }

    @Test
    fun executeDismissAction_dismissKeyguardRequestWithImmediateDismissAction_biometricAuthed() =
        testScope.runTest {
            val executeDismissAction by collectLastValue(underTest.executeDismissAction)

            val onDismissAction = { KeyguardDone.IMMEDIATE }
            keyguardRepository.setDismissAction(
                DismissAction.RunImmediately(
                    onDismissAction = onDismissAction,
                    onCancelAction = {},
                    message = "message",
                    willAnimateOnLockscreen = true,
                )
            )
            dismissInteractorWithDependencies.bouncerRepository.setKeyguardAuthenticatedBiometrics(
                true
            )
            assertThat(executeDismissAction).isEqualTo(onDismissAction)
        }

    @Test
    fun executeDismissAction_dismissKeyguardRequestWithoutImmediateDismissAction() =
        testScope.runTest {
            val executeDismissAction by collectLastValue(underTest.executeDismissAction)

            // WHEN a keyguard action will run after the keyguard is gone
            val onDismissAction = {}
            keyguardRepository.setDismissAction(
                DismissAction.RunAfterKeyguardGone(
                    dismissAction = onDismissAction,
                    onCancelAction = {},
                    message = "message",
                    willAnimateOnLockscreen = true,
                )
            )
            assertThat(executeDismissAction).isNull()

            // WHEN the keyguard is GONE
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope,
            )
            assertThat(executeDismissAction).isNotNull()
        }

    @Test
    fun resetDismissAction() =
        testScope.runTest {
            val resetDismissAction by collectLastValue(underTest.resetDismissAction)

            keyguardRepository.setDismissAction(
                DismissAction.RunAfterKeyguardGone(
                    dismissAction = {},
                    onCancelAction = {},
                    message = "message",
                    willAnimateOnLockscreen = true,
                )
            )
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                testScope
            )
            assertThat(resetDismissAction).isEqualTo(Unit)
        }

    @Test
    fun setDismissAction_callsCancelRunnableOnPreviousDismissAction() =
        testScope.runTest {
            val dismissAction by collectLastValue(underTest.dismissAction)
            var previousDismissActionCancelCalled = false
            keyguardRepository.setDismissAction(
                DismissAction.RunImmediately(
                    onDismissAction = { KeyguardDone.IMMEDIATE },
                    onCancelAction = { previousDismissActionCancelCalled = true },
                    message = "",
                    willAnimateOnLockscreen = true,
                )
            )

            val newDismissAction =
                DismissAction.RunImmediately(
                    onDismissAction = { KeyguardDone.IMMEDIATE },
                    onCancelAction = {},
                    message = "",
                    willAnimateOnLockscreen = true,
                )
            underTest.setDismissAction(newDismissAction)

            // THEN previous dismiss action got its onCancel called
            assertThat(previousDismissActionCancelCalled).isTrue()

            // THEN dismiss action is updated
            assertThat(dismissAction).isEqualTo(newDismissAction)
        }

    @Test
    fun handleDismissAction() =
        testScope.runTest {
            val dismissAction by collectLastValue(underTest.dismissAction)
            underTest.handleDismissAction()
            assertThat(dismissAction).isEqualTo(DismissAction.None)
        }

    @Test
    fun setKeyguardDone() =
        testScope.runTest {
            val keyguardDoneTiming by
                collectLastValue(dismissInteractorWithDependencies.interactor.keyguardDone)
            runCurrent()

            underTest.setKeyguardDone(KeyguardDone.LATER)
            assertThat(keyguardDoneTiming).isEqualTo(KeyguardDone.LATER)

            underTest.setKeyguardDone(KeyguardDone.IMMEDIATE)
            assertThat(keyguardDoneTiming).isEqualTo(KeyguardDone.IMMEDIATE)
        }
}
