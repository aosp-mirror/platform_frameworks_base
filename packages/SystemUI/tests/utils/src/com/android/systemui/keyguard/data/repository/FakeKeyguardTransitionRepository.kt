/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.keyguard.data.repository

import android.annotation.FloatRange
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import dagger.Binds
import dagger.Module
import java.util.UUID
import javax.inject.Inject
import junit.framework.Assert.fail
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/** Fake implementation of [KeyguardTransitionRepository] */
@SysUISingleton
class FakeKeyguardTransitionRepository @Inject constructor() : KeyguardTransitionRepository {

    private val _transitions =
        MutableSharedFlow<TransitionStep>(replay = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val transitions: SharedFlow<TransitionStep> = _transitions

    init {
        _transitions.tryEmit(
            TransitionStep(
                transitionState = TransitionState.STARTED,
                from = KeyguardState.OFF,
                to = KeyguardState.LOCKSCREEN,
            )
        )

        _transitions.tryEmit(
            TransitionStep(
                transitionState = TransitionState.FINISHED,
                from = KeyguardState.OFF,
                to = KeyguardState.LOCKSCREEN,
            )
        )
    }

    /**
     * Sends STARTED, RUNNING, and FINISHED TransitionSteps between [from] and [to], calling
     * [runCurrent] after each step.
     */
    suspend fun sendTransitionSteps(
        from: KeyguardState,
        to: KeyguardState,
        testScope: TestScope,
    ) {
        sendTransitionSteps(from, to, testScope.testScheduler)
    }

    /**
     * Sends STARTED, RUNNING, and FINISHED TransitionSteps between [from] and [to], calling
     * [runCurrent] after each step.
     */
    suspend fun sendTransitionSteps(
        from: KeyguardState,
        to: KeyguardState,
        testScheduler: TestCoroutineScheduler,
    ) {
        sendTransitionStep(
            TransitionStep(
                transitionState = TransitionState.STARTED,
                from = from,
                to = to,
                value = 0f,
            )
        )
        testScheduler.runCurrent()

        sendTransitionStep(
            TransitionStep(
                transitionState = TransitionState.RUNNING,
                from = from,
                to = to,
                value = 0.5f
            )
        )
        testScheduler.runCurrent()

        sendTransitionStep(
            TransitionStep(
                transitionState = TransitionState.FINISHED,
                from = from,
                to = to,
                value = 1f,
            )
        )
        testScheduler.runCurrent()
    }

    /**
     * Directly emits the provided TransitionStep, which can be useful in tests for testing behavior
     * during specific phases of a transition (such as asserting values while a transition has
     * STARTED but not FINISHED).
     *
     * WARNING: You can get the transition repository into undefined states using this method - for
     * example, you could send a FINISHED step to LOCKSCREEN having never sent a STARTED step. This
     * can get flows that combine startedStep/finishedStep into a bad state.
     *
     * If you are just trying to get the transition repository FINISHED in a certain state, use
     * [sendTransitionSteps] - this will send STARTED, RUNNING, and FINISHED steps for you which
     * ensures that [KeyguardTransitionInteractor] flows will be in the correct state.
     *
     * If you're testing something involving transitions themselves and are sure you want to send
     * only a FINISHED step, override [validateStep].
     */
    suspend fun sendTransitionStep(step: TransitionStep, validateStep: Boolean = true) {
        _transitions.replayCache.last().let { lastStep ->
            if (
                validateStep &&
                    step.transitionState == TransitionState.FINISHED &&
                    !(lastStep.transitionState == TransitionState.STARTED ||
                        lastStep.transitionState == TransitionState.RUNNING)
            ) {
                fail(
                    "Attempted to send a FINISHED TransitionStep without a prior " +
                        "STARTED/RUNNING step. This leaves the FakeKeyguardTransitionRepository " +
                        "in an undefined state and should not be done. Pass " +
                        "allowInvalidStep=true to sendTransitionStep if you are trying to test " +
                        "this specific and" +
                        "incorrect state."
                )
            }
        }
        _transitions.emit(step)
    }

    suspend fun sendTransitionSteps(
        steps: List<TransitionStep>,
        testScope: TestScope,
        validateStep: Boolean = true
    ) {
        steps.forEach {
            sendTransitionStep(it, validateStep = validateStep)
            testScope.testScheduler.runCurrent()
        }
    }

    override fun startTransition(info: TransitionInfo): UUID? {
        return if (info.animator == null) UUID.randomUUID() else null
    }

    override fun updateTransition(
        transitionId: UUID,
        @FloatRange(from = 0.0, to = 1.0) value: Float,
        state: TransitionState
    ) = Unit
}

@Module
interface FakeKeyguardTransitionRepositoryModule {
    @Binds fun bindFake(fake: FakeKeyguardTransitionRepository): KeyguardTransitionRepository
}
