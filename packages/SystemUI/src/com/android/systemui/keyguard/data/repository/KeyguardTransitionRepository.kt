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
 * limitations under the License
 */
package com.android.systemui.keyguard.data.repository

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.annotation.FloatRange
import android.os.Trace
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * The source of truth for all keyguard transitions.
 *
 * While the keyguard component is visible, it can undergo a number of transitions between different
 * UI screens, such as AOD (Always-on Display), Bouncer, and others mentioned in [KeyguardState].
 * These UI elements should listen to events emitted by [transitions], to ensure a centrally
 * coordinated experience.
 *
 * To create or modify logic that controls when and how transitions get created, look at
 * [TransitionInteractor]. These interactors will call [startTransition] and [updateTransition] on
 * this repository.
 *
 * To print all transitions to logcat to help with debugging, run this command:
 * ```
 * adb shell cmd statusbar echo -b KeyguardLog:VERBOSE
 * ```
 *
 * This will print all keyguard transitions to logcat with the KeyguardTransitionAuditLogger tag.
 */
interface KeyguardTransitionRepository {
    /**
     * All events regarding transitions, as they start, run, and complete. [TransitionStep#value] is
     * a float between [0, 1] representing progress towards completion. If this is a user driven
     * transition, that value may not be a monotonic progression, as the user may swipe in any
     * direction.
     */
    val transitions: Flow<TransitionStep>

    /**
     * Interactors that require information about changes between [KeyguardState]s will call this to
     * register themselves for flowable [TransitionStep]s when that transition occurs.
     */
    fun transition(from: KeyguardState, to: KeyguardState): Flow<TransitionStep> {
        return transitions.filter { step -> step.from == from && step.to == to }
    }

    /**
     * Begin a transition from one state to another. Transitions are interruptible, and will issue a
     * [TransitionStep] with state = [TransitionState.CANCELED] before beginning the next one.
     */
    fun startTransition(info: TransitionInfo): UUID?

    /**
     * Allows manual control of a transition. When calling [startTransition], the consumer must pass
     * in a null animator. In return, it will get a unique [UUID] that will be validated to allow
     * further updates.
     *
     * When the transition is over, TransitionState.FINISHED must be passed into the [state]
     * parameter.
     */
    fun updateTransition(
        transitionId: UUID,
        @FloatRange(from = 0.0, to = 1.0) value: Float,
        state: TransitionState
    )
}

@SysUISingleton
class KeyguardTransitionRepositoryImpl @Inject constructor() : KeyguardTransitionRepository {
    /*
     * Each transition between [KeyguardState]s will have an associated Flow.
     * In order to collect these events, clients should call [transition].
     */
    private val _transitions =
        MutableSharedFlow<TransitionStep>(
            replay = 2,
            extraBufferCapacity = 10,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val transitions = _transitions.asSharedFlow().distinctUntilChanged()
    private var lastStep: TransitionStep = TransitionStep()
    private var lastAnimator: ValueAnimator? = null

    /*
     * When manual control of the transition is requested, a unique [UUID] is used as the handle
     * to permit calls to [updateTransition]
     */
    private var updateTransitionId: UUID? = null

    init {
        // Seed with transitions signaling a boot into lockscreen state. If updating this, please
        // also update FakeKeyguardTransitionRepository.
        emitTransition(
            TransitionStep(
                KeyguardState.OFF,
                KeyguardState.LOCKSCREEN,
                0f,
                TransitionState.STARTED,
                KeyguardTransitionRepositoryImpl::class.simpleName!!,
            )
        )
        emitTransition(
            TransitionStep(
                KeyguardState.OFF,
                KeyguardState.LOCKSCREEN,
                1f,
                TransitionState.FINISHED,
                KeyguardTransitionRepositoryImpl::class.simpleName!!,
            )
        )
    }

    override fun startTransition(info: TransitionInfo): UUID? {
        if (lastStep.from == info.from && lastStep.to == info.to) {
            Log.i(TAG, "Duplicate call to start the transition, rejecting: $info")
            return null
        }
        val startingValue =
            if (lastStep.transitionState != TransitionState.FINISHED) {
                Log.i(TAG, "Transition still active: $lastStep, canceling")
                when (info.modeOnCanceled) {
                    TransitionModeOnCanceled.LAST_VALUE -> lastStep.value
                    TransitionModeOnCanceled.RESET -> 0f
                    TransitionModeOnCanceled.REVERSE -> 1f - lastStep.value
                }
            } else {
                0f
            }

        lastAnimator?.cancel()
        lastAnimator = info.animator

        info.animator?.let { animator ->
            // An animator was provided, so use it to run the transition
            animator.setFloatValues(startingValue, 1f)
            animator.duration = ((1f - startingValue) * animator.duration).toLong()
            val updateListener = AnimatorUpdateListener { animation ->
                emitTransition(
                    TransitionStep(
                        info,
                        (animation.animatedValue as Float),
                        TransitionState.RUNNING
                    )
                )
            }
            val adapter =
                object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        emitTransition(TransitionStep(info, startingValue, TransitionState.STARTED))
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        endAnimation(lastStep.value, TransitionState.CANCELED)
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        endAnimation(1f, TransitionState.FINISHED)
                    }

                    private fun endAnimation(value: Float, state: TransitionState) {
                        emitTransition(TransitionStep(info, value, state))
                        animator.removeListener(this)
                        animator.removeUpdateListener(updateListener)
                        lastAnimator = null
                    }
                }
            animator.addListener(adapter)
            animator.addUpdateListener(updateListener)
            animator.start()
            return@startTransition null
        }
            ?: run {
                emitTransition(TransitionStep(info, startingValue, TransitionState.STARTED))

                // No animator, so it's manual. Provide a mechanism to callback
                updateTransitionId = UUID.randomUUID()
                return@startTransition updateTransitionId
            }
    }

    override fun updateTransition(
        transitionId: UUID,
        @FloatRange(from = 0.0, to = 1.0) value: Float,
        state: TransitionState
    ) {
        if (updateTransitionId != transitionId) {
            Log.wtf(TAG, "Attempting to update with old/invalid transitionId: $transitionId")
            return
        }

        if (state == TransitionState.FINISHED || state == TransitionState.CANCELED) {
            updateTransitionId = null
        }

        val nextStep = lastStep.copy(value = value, transitionState = state)
        emitTransition(nextStep, isManual = true)
    }

    private fun emitTransition(nextStep: TransitionStep, isManual: Boolean = false) {
        logAndTrace(nextStep, isManual)
        val emitted = _transitions.tryEmit(nextStep)
        if (!emitted) {
            Log.w(TAG, "Failed to emit next value without suspending")
        }
        lastStep = nextStep
    }

    private fun logAndTrace(step: TransitionStep, isManual: Boolean) {
        if (step.transitionState == TransitionState.RUNNING) {
            return
        }
        val manualStr = if (isManual) " (manual)" else ""
        val traceName = "Transition: ${step.from} -> ${step.to}$manualStr"

        val traceCookie = traceName.hashCode()
        when (step.transitionState) {
            TransitionState.STARTED -> Trace.beginAsyncSection(traceName, traceCookie)
            TransitionState.FINISHED -> Trace.endAsyncSection(traceName, traceCookie)
            TransitionState.CANCELED -> Trace.endAsyncSection(traceName, traceCookie)
            else -> {}
        }

        Log.i(TAG, "${step.transitionState.name} transition: $step$manualStr")
    }

    companion object {
        private const val TAG = "KeyguardTransitionRepository"
    }
}
