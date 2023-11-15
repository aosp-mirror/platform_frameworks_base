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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractorFactory
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
@SmallTest
class AlternateBouncerViewModelTest : SysuiTestCase() {

    private lateinit var testScope: TestScope

    @Mock private lateinit var statusBarKeyguardViewManager: StatusBarKeyguardViewManager

    private lateinit var transitionRepository: FakeKeyguardTransitionRepository
    private lateinit var transitionInteractor: KeyguardTransitionInteractor
    private lateinit var underTest: AlternateBouncerViewModel

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        testScope = TestScope()

        val transitionInteractorWithDependencies =
            KeyguardTransitionInteractorFactory.create(testScope.backgroundScope)
        transitionInteractor = transitionInteractorWithDependencies.keyguardTransitionInteractor
        transitionRepository = transitionInteractorWithDependencies.repository
        underTest =
            AlternateBouncerViewModel(
                statusBarKeyguardViewManager,
                transitionInteractor,
            )
    }

    @Test
    fun transitionToAlternateBouncer_scrimAlphaUpdate() =
        runTest(UnconfinedTestDispatcher()) {
            val scrimAlphas by collectValues(underTest.scrimAlpha)

            transitionRepository.sendTransitionStep(
                stepToAlternateBouncer(0f, TransitionState.STARTED)
            )
            transitionRepository.sendTransitionStep(stepToAlternateBouncer(.4f))
            transitionRepository.sendTransitionStep(stepToAlternateBouncer(.6f))
            transitionRepository.sendTransitionStep(stepToAlternateBouncer(1f))

            assertThat(scrimAlphas.size).isEqualTo(4)
            scrimAlphas.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }
        }

    @Test
    fun transitionFromAlternateBouncer_scrimAlphaUpdate() =
        runTest(UnconfinedTestDispatcher()) {
            val scrimAlphas by collectValues(underTest.scrimAlpha)

            transitionRepository.sendTransitionStep(
                stepFromAlternateBouncer(0f, TransitionState.STARTED)
            )
            transitionRepository.sendTransitionStep(stepFromAlternateBouncer(.4f))
            transitionRepository.sendTransitionStep(stepFromAlternateBouncer(.6f))
            transitionRepository.sendTransitionStep(stepFromAlternateBouncer(1f))

            assertThat(scrimAlphas.size).isEqualTo(4)
            scrimAlphas.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }
        }

    @Test
    fun forcePluginOpen() =
        runTest(UnconfinedTestDispatcher()) {
            val forcePluginOpen by collectLastValue(underTest.forcePluginOpen)
            transitionRepository.sendTransitionStep(
                stepToAlternateBouncer(0f, TransitionState.STARTED)
            )
            transitionRepository.sendTransitionStep(stepToAlternateBouncer(.3f))
            transitionRepository.sendTransitionStep(stepToAlternateBouncer(.6f))
            transitionRepository.sendTransitionStep(stepToAlternateBouncer(1f))
            assertThat(forcePluginOpen).isTrue()

            transitionRepository.sendTransitionStep(
                stepFromAlternateBouncer(0f, TransitionState.STARTED)
            )
            transitionRepository.sendTransitionStep(stepFromAlternateBouncer(.3f))
            transitionRepository.sendTransitionStep(stepFromAlternateBouncer(.6f))
            transitionRepository.sendTransitionStep(stepFromAlternateBouncer(1f))
            assertThat(forcePluginOpen).isFalse()
        }

    @Test
    fun registerForDismissGestures() =
        runTest(UnconfinedTestDispatcher()) {
            val registerForDismissGestures by collectLastValue(underTest.registerForDismissGestures)
            transitionRepository.sendTransitionStep(
                stepToAlternateBouncer(0f, TransitionState.STARTED)
            )
            transitionRepository.sendTransitionStep(stepToAlternateBouncer(.3f))
            transitionRepository.sendTransitionStep(stepToAlternateBouncer(.6f))
            transitionRepository.sendTransitionStep(stepToAlternateBouncer(1f))
            assertThat(registerForDismissGestures).isTrue()

            transitionRepository.sendTransitionStep(
                stepFromAlternateBouncer(0f, TransitionState.STARTED)
            )
            transitionRepository.sendTransitionStep(stepFromAlternateBouncer(.3f))
            transitionRepository.sendTransitionStep(stepFromAlternateBouncer(.6f))
            transitionRepository.sendTransitionStep(stepFromAlternateBouncer(1f))
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
