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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.policy.IKeyguardDismissCallback
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.domain.interactor.primaryBouncerInteractor
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.dismissCallbackRegistry
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.phone.statusBarKeyguardViewManager
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@SmallTest
class AlternateBouncerViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val transitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val statusBarKeyguardViewManager = kosmos.statusBarKeyguardViewManager
    private val underTest = kosmos.alternateBouncerViewModel

    @Test
    fun onTapped() =
        testScope.runTest {
            underTest.onTapped()
            verify(statusBarKeyguardViewManager).showPrimaryBouncer(any())
        }

    @Test
    fun onRemovedFromWindow() =
        testScope.runTest {
            underTest.onRemovedFromWindow()
            verify(statusBarKeyguardViewManager).hideAlternateBouncer(any())
        }

    @Test
    fun onBackRequested() =
        testScope.runTest {
            kosmos.primaryBouncerInteractor.setDismissAction(
                mock(ActivityStarter.OnDismissAction::class.java),
                {},
            )
            assertThat(kosmos.primaryBouncerInteractor.bouncerDismissAction).isNotNull()

            val dismissCallback = mock(IKeyguardDismissCallback::class.java)
            kosmos.dismissCallbackRegistry.addCallback(dismissCallback)

            underTest.onBackRequested()
            kosmos.fakeExecutor.runAllReady()
            verify(statusBarKeyguardViewManager).hideAlternateBouncer(any())
            verify(dismissCallback).onDismissCancelled()
            assertThat(kosmos.primaryBouncerInteractor.bouncerDismissAction).isNull()
        }

    @Test
    fun transitionToAlternateBouncer_scrimAlphaUpdate() =
        testScope.runTest {
            val scrimAlphas by collectValues(underTest.scrimAlpha)
            assertThat(scrimAlphas.size).isEqualTo(1) // initial value is 0f

            transitionRepository.sendTransitionSteps(
                listOf(
                    stepToAlternateBouncer(0f, TransitionState.STARTED),
                    stepToAlternateBouncer(.4f),
                    stepToAlternateBouncer(.6f),
                    stepToAlternateBouncer(1f),
                ),
                testScope,
            )

            assertThat(scrimAlphas.size).isEqualTo(5)
            scrimAlphas.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }
        }

    @Test
    fun transitionFromAlternateBouncer_scrimAlphaUpdate() =
        testScope.runTest {
            val scrimAlphas by collectValues(underTest.scrimAlpha)
            assertThat(scrimAlphas.size).isEqualTo(1) // initial value is 0f

            transitionRepository.sendTransitionSteps(
                listOf(
                    stepFromAlternateBouncer(0f, TransitionState.STARTED),
                    stepFromAlternateBouncer(.4f),
                    stepFromAlternateBouncer(.6f),
                    stepFromAlternateBouncer(1f),
                ),
                testScope,
            )
            assertThat(scrimAlphas.size).isEqualTo(5)
            scrimAlphas.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }
        }

    @Test
    fun registerForDismissGestures() =
        testScope.runTest {
            val registerForDismissGestures by collectLastValue(underTest.registerForDismissGestures)

            transitionRepository.sendTransitionSteps(
                listOf(
                    stepToAlternateBouncer(0f, TransitionState.STARTED),
                    stepToAlternateBouncer(.4f),
                    stepToAlternateBouncer(.6f),
                    stepToAlternateBouncer(1f),
                ),
                testScope,
            )
            assertThat(registerForDismissGestures).isTrue()

            transitionRepository.sendTransitionSteps(
                listOf(
                    stepFromAlternateBouncer(0f, TransitionState.STARTED),
                    stepFromAlternateBouncer(.3f),
                    stepFromAlternateBouncer(.6f),
                    stepFromAlternateBouncer(1f),
                ),
                testScope,
            )
            assertThat(registerForDismissGestures).isFalse()
        }

    private fun stepToAlternateBouncer(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return step(
            from = KeyguardState.LOCKSCREEN,
            to = KeyguardState.ALTERNATE_BOUNCER,
            value = value,
            transitionState = state,
        )
    }

    private fun stepFromAlternateBouncer(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return step(
            from = KeyguardState.ALTERNATE_BOUNCER,
            to = KeyguardState.LOCKSCREEN,
            value = value,
            transitionState = state,
        )
    }

    private fun step(
        from: KeyguardState,
        to: KeyguardState,
        value: Float,
        transitionState: TransitionState
    ): TransitionStep {
        return TransitionStep(
            from = from,
            to = to,
            value = value,
            transitionState = transitionState,
            ownerName = "AlternateBouncerViewModelTest"
        )
    }
}
