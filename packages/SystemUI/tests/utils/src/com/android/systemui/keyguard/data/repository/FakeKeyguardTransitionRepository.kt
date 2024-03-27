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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/** Fake implementation of [KeyguardTransitionRepository] */
@SysUISingleton
class FakeKeyguardTransitionRepository @Inject constructor() : KeyguardTransitionRepository {
    private val _transitions =
        MutableSharedFlow<TransitionStep>(replay = 3, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val transitions: SharedFlow<TransitionStep> = _transitions

    private val _currentTransitionInfo: MutableStateFlow<TransitionInfo> =
        MutableStateFlow(
            TransitionInfo(
                ownerName = "",
                from = KeyguardState.OFF,
                to = KeyguardState.LOCKSCREEN,
                animator = null
            )
        )
    override var currentTransitionInfoInternal = _currentTransitionInfo.asStateFlow()

    init {
        // Seed the fake repository with the same initial steps the actual repository uses.
        KeyguardTransitionRepositoryImpl.initialTransitionSteps.forEach { _transitions.tryEmit(it) }
    }

    /**
     * Sends TransitionSteps between [from] and [to], calling [runCurrent] after each step.
     *
     * By default, sends steps through FINISHED (STARTED, RUNNING, FINISHED) but can be halted part
     * way using [throughTransitionState].
     */
    suspend fun sendTransitionSteps(
        from: KeyguardState,
        to: KeyguardState,
        testScope: TestScope,
        throughTransitionState: TransitionState = TransitionState.FINISHED,
    ) {
        sendTransitionSteps(from, to, testScope.testScheduler, throughTransitionState)
    }

    /**
     * Sends TransitionSteps between [from] and [to], calling [runCurrent] after each step.
     *
     * By default, sends steps through FINISHED (STARTED, RUNNING, FINISHED) but can be halted part
     * way using [throughTransitionState].
     */
    suspend fun sendTransitionSteps(
        from: KeyguardState,
        to: KeyguardState,
        testScheduler: TestCoroutineScheduler,
        throughTransitionState: TransitionState = TransitionState.FINISHED,
    ) {
        sendTransitionStep(
            step =
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = from,
                    to = to,
                    value = 0f,
                )
        )
        testScheduler.runCurrent()

        if (
            throughTransitionState == TransitionState.RUNNING ||
                throughTransitionState == TransitionState.FINISHED
        ) {
            sendTransitionStep(
                step =
                    TransitionStep(
                        transitionState = TransitionState.RUNNING,
                        from = from,
                        to = to,
                        value = 0.5f
                    )
            )
            testScheduler.runCurrent()
        }

        if (throughTransitionState == TransitionState.FINISHED) {
            sendTransitionStep(
                step =
                    TransitionStep(
                        transitionState = TransitionState.FINISHED,
                        from = from,
                        to = to,
                        value = 1f,
                    )
            )
            testScheduler.runCurrent()
        }
    }

    suspend fun sendTransitionStep(step: TransitionStep, validateStep: Boolean = true) {
        this.sendTransitionStep(
            step = step,
            validateStep = validateStep,
            ownerName = step.ownerName
        )
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
    suspend fun sendTransitionStep(
        from: KeyguardState = KeyguardState.OFF,
        to: KeyguardState = KeyguardState.OFF,
        value: Float = 0f,
        transitionState: TransitionState = TransitionState.FINISHED,
        ownerName: String = "",
        step: TransitionStep =
            TransitionStep(
                from = from,
                to = to,
                value = value,
                transitionState = transitionState,
                ownerName = ownerName
            ),
        validateStep: Boolean = true
    ) {
        if (step.transitionState == TransitionState.STARTED) {
            _currentTransitionInfo.value =
                TransitionInfo(from = step.from, to = step.to, animator = null, ownerName = "")
        }

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

    /** Version of [sendTransitionStep] that's usable from Java tests. */
    fun sendTransitionStepJava(
        coroutineScope: CoroutineScope,
        step: TransitionStep,
        validateStep: Boolean = true
    ): Job {
        return coroutineScope.launch {
            sendTransitionStep(step = step, validateStep = validateStep)
        }
    }

    suspend fun sendTransitionSteps(
        steps: List<TransitionStep>,
        testScope: TestScope,
        validateSteps: Boolean = true
    ) {
        steps.forEach {
            sendTransitionStep(step = it, validateStep = validateSteps)
            testScope.testScheduler.runCurrent()
        }
    }

    override suspend fun startTransition(info: TransitionInfo): UUID? {
        _currentTransitionInfo.value = info
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
