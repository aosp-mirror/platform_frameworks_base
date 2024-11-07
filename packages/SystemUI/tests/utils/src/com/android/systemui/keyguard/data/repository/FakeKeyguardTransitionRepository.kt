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
import com.android.systemui.Flags.transitionRaceCondition
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

/**
 * Fake implementation of [KeyguardTransitionRepository].
 *
 * By default, will be seeded with a transition from OFF -> LOCKSCREEN, which is the most common
 * case. If the lockscreen is disabled, or we're in setup wizard, the repository will initialize
 * with OFF -> GONE. Construct with initInLockscreen = false if your test requires this behavior.
 */
@SysUISingleton
class FakeKeyguardTransitionRepository(
    private val initInLockscreen: Boolean = true,

    /**
     * Initial value for [FakeKeyguardTransitionRepository.sendTransitionStepsOnStartTransition].
     * This needs to be configurable in the constructor since some transitions are triggered on
     * init, before a test has the chance to set sendTransitionStepsOnStartTransition to false.
     */
    private val initiallySendTransitionStepsOnStartTransition: Boolean = true,
    private val testScope: TestScope,
) : KeyguardTransitionRepository {

    /**
     * If true, calls to [startTransition] will automatically emit STARTED, RUNNING, and FINISHED
     * transition steps from/to the given states.
     *
     * [startTransition] is what the From*TransitionInteractors call, so this more closely emulates
     * the behavior of the real KeyguardTransitionRepository, and reduces the work needed to
     * manually set up the repository state in each test. For example, setting dreaming=true will
     * automatically cause FromDreamingTransitionInteractor to call startTransition(DREAMING), and
     * then we'll send STARTED/RUNNING/FINISHED DREAMING TransitionSteps.
     *
     * If your test needs to make assertions at specific points between STARTED/FINISHED, or if it's
     * difficult to set up all of the conditions to make the transition interactors actually call
     * startTransition, set this value to false.
     */
    var sendTransitionStepsOnStartTransition = initiallySendTransitionStepsOnStartTransition

    private val _transitions =
        MutableSharedFlow<TransitionStep>(replay = 3, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val transitions: SharedFlow<TransitionStep> = _transitions

    @Inject
    constructor(
        testScope: TestScope
    ) : this(
        initInLockscreen = true,
        initiallySendTransitionStepsOnStartTransition = true,
        testScope,
    )

    private val _currentTransitionInfo: MutableStateFlow<TransitionInfo> =
        MutableStateFlow(
            TransitionInfo(
                ownerName = "",
                from = KeyguardState.OFF,
                to = KeyguardState.LOCKSCREEN,
                animator = null,
            )
        )
    override var currentTransitionInfoInternal = _currentTransitionInfo.asStateFlow()
    override var currentTransitionInfo =
        TransitionInfo(
            ownerName = "",
            from = KeyguardState.OFF,
            to = KeyguardState.LOCKSCREEN,
            animator = null,
        )

    init {
        // Seed with a FINISHED transition in OFF, same as the real repository.
        _transitions.tryEmit(
            TransitionStep(KeyguardState.OFF, KeyguardState.OFF, 1f, TransitionState.FINISHED)
        )

        if (initInLockscreen) {
            tryEmitInitialStepsFromOff(KeyguardState.LOCKSCREEN)
        } else {
            tryEmitInitialStepsFromOff(KeyguardState.OFF)
        }
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
     * Sends the provided [step] and makes sure that all previous [TransitionState]'s are sent when
     * [fillInSteps] is true. e.g. when a step FINISHED is provided, a step with STARTED and RUNNING
     * is also sent.
     */
    suspend fun sendTransitionSteps(
        step: TransitionStep,
        testScope: TestScope,
        fillInSteps: Boolean = true,
    ) {
        if (fillInSteps && step.transitionState != TransitionState.STARTED) {
            sendTransitionStep(
                step =
                    TransitionStep(
                        transitionState = TransitionState.STARTED,
                        from = step.from,
                        to = step.to,
                        value = 0f,
                    )
            )
            testScope.testScheduler.runCurrent()

            if (step.transitionState != TransitionState.RUNNING) {
                sendTransitionStep(
                    step =
                        TransitionStep(
                            transitionState = TransitionState.RUNNING,
                            from = step.from,
                            to = step.to,
                            value = 0.6f,
                        )
                )
                testScope.testScheduler.runCurrent()
            }
        }
        sendTransitionStep(step = step)
        testScope.testScheduler.runCurrent()
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
        val lastStep = _transitions.replayCache.lastOrNull()
        if (lastStep != null && lastStep.transitionState != TransitionState.FINISHED) {
            sendTransitionStep(
                step =
                    TransitionStep(
                        transitionState = TransitionState.CANCELED,
                        from = lastStep.from,
                        to = lastStep.to,
                        value = 0f,
                    )
            )
            testScheduler.runCurrent()
        }

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
                        value = 0.5f,
                    )
            )
            testScheduler.runCurrent()

            sendTransitionStep(
                step =
                    TransitionStep(
                        transitionState = TransitionState.RUNNING,
                        from = from,
                        to = to,
                        value = 1f,
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
            ownerName = step.ownerName,
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
                ownerName = ownerName,
            ),
        validateStep: Boolean = true,
    ) {
        if (step.transitionState == TransitionState.STARTED) {
            if (transitionRaceCondition()) {
                currentTransitionInfo =
                    TransitionInfo(from = step.from, to = step.to, animator = null, ownerName = "")
            } else {
                _currentTransitionInfo.value =
                    TransitionInfo(from = step.from, to = step.to, animator = null, ownerName = "")
            }
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
        validateStep: Boolean = true,
    ): Job {
        return coroutineScope.launch {
            sendTransitionStep(step = step, validateStep = validateStep)
        }
    }

    suspend fun sendTransitionSteps(
        steps: List<TransitionStep>,
        testScope: TestScope,
        validateSteps: Boolean = true,
    ) {
        steps.forEach {
            sendTransitionStep(step = it, validateStep = validateSteps)
            testScope.testScheduler.runCurrent()
        }
    }

    override suspend fun startTransition(info: TransitionInfo): UUID? {
        if (transitionRaceCondition()) {
            currentTransitionInfo = info
        } else {
            _currentTransitionInfo.value = info
        }

        if (sendTransitionStepsOnStartTransition) {
            sendTransitionSteps(from = info.from, to = info.to, testScope = testScope)
        }

        return if (info.animator == null) UUID.randomUUID() else null
    }

    override suspend fun emitInitialStepsFromOff(to: KeyguardState, testSetup: Boolean) {
        tryEmitInitialStepsFromOff(to)
    }

    private fun tryEmitInitialStepsFromOff(to: KeyguardState) {
        _transitions.tryEmit(
            TransitionStep(
                KeyguardState.OFF,
                to,
                0f,
                TransitionState.STARTED,
                ownerName = "KeyguardTransitionRepository(boot)",
            )
        )

        _transitions.tryEmit(
            TransitionStep(
                KeyguardState.OFF,
                to,
                1f,
                TransitionState.FINISHED,
                ownerName = "KeyguardTransitionRepository(boot)",
            )
        )
    }

    override suspend fun updateTransition(
        transitionId: UUID,
        @FloatRange(from = 0.0, to = 1.0) value: Float,
        state: TransitionState,
    ) = Unit

    override suspend fun forceFinishCurrentTransition() {
        _transitions.tryEmit(
            TransitionStep(
                _currentTransitionInfo.value.from,
                _currentTransitionInfo.value.to,
                1f,
                TransitionState.FINISHED,
                ownerName = _currentTransitionInfo.value.ownerName,
            )
        )
    }
}

@Module
interface FakeKeyguardTransitionRepositoryModule {
    @Binds fun bindFake(fake: FakeKeyguardTransitionRepository): KeyguardTransitionRepository
}
