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
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter

@SysUISingleton
class KeyguardTransitionRepository @Inject constructor() {
    /*
     * Each transition between [KeyguardState]s will have an associated Flow.
     * In order to collect these events, clients should call [transition].
     */
    private val _transitions = MutableStateFlow(TransitionStep())
    val transitions = _transitions.asStateFlow()

    /* Information about the active transition. */
    private var currentTransitionInfo: TransitionInfo? = null
    /*
     * When manual control of the transition is requested, a unique [UUID] is used as the handle
     * to permit calls to [updateTransition]
     */
    private var updateTransitionId: UUID? = null

    /**
     * Interactors that require information about changes between [KeyguardState]s will call this to
     * register themselves for flowable [TransitionStep]s when that transition occurs.
     */
    fun transition(from: KeyguardState, to: KeyguardState): Flow<TransitionStep> {
        return transitions.filter { step -> step.from == from && step.to == to }
    }

    /**
     * Begin a transition from one state to another. The [info.from] must match
     * [currentTransitionInfo.to], or the request will be denied. This is enforced to avoid
     * unplanned transitions.
     */
    fun startTransition(info: TransitionInfo): UUID? {
        if (currentTransitionInfo != null) {
            // Open questions:
            // * Queue of transitions? buffer of 1?
            // * Are transitions cancellable if a new one is triggered?
            // * What validation does this need to do?
            Log.wtf(TAG, "Transition still active: $currentTransitionInfo")
            return null
        }
        currentTransitionInfo?.animator?.cancel()

        currentTransitionInfo = info
        info.animator?.let { animator ->
            // An animator was provided, so use it to run the transition
            animator.setFloatValues(0f, 1f)
            val updateListener =
                object : AnimatorUpdateListener {
                    override fun onAnimationUpdate(animation: ValueAnimator) {
                        emitTransition(
                            info,
                            (animation.getAnimatedValue() as Float),
                            TransitionState.RUNNING
                        )
                    }
                }
            val adapter =
                object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        Log.i(TAG, "Starting transition: $info")
                        emitTransition(info, 0f, TransitionState.STARTED)
                    }
                    override fun onAnimationCancel(animation: Animator) {
                        Log.i(TAG, "Cancelling transition: $info")
                    }
                    override fun onAnimationEnd(animation: Animator) {
                        Log.i(TAG, "Ending transition: $info")
                        emitTransition(info, 1f, TransitionState.FINISHED)
                        animator.removeListener(this)
                        animator.removeUpdateListener(updateListener)
                    }
                }
            animator.addListener(adapter)
            animator.addUpdateListener(updateListener)
            animator.start()
            return@startTransition null
        }
            ?: run {
                Log.i(TAG, "Starting transition (manual): $info")
                emitTransition(info, 0f, TransitionState.STARTED)

                // No animator, so it's manual. Provide a mechanism to callback
                updateTransitionId = UUID.randomUUID()
                return@startTransition updateTransitionId
            }
    }

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
    ) {
        if (updateTransitionId != transitionId) {
            Log.wtf(TAG, "Attempting to update with old/invalid transitionId: $transitionId")
            return
        }

        if (currentTransitionInfo == null) {
            Log.wtf(TAG, "Attempting to update with null 'currentTransitionInfo'")
            return
        }

        currentTransitionInfo?.let { info ->
            if (state == TransitionState.FINISHED) {
                updateTransitionId = null
                Log.i(TAG, "Ending transition: $info")
            }

            emitTransition(info, value, state)
        }
    }

    private fun emitTransition(
        info: TransitionInfo,
        value: Float,
        transitionState: TransitionState
    ) {
        if (transitionState == TransitionState.FINISHED) {
            currentTransitionInfo = null
        }
        _transitions.value = TransitionStep(info.from, info.to, value, transitionState)
    }

    companion object {
        private const val TAG = "KeyguardTransitionRepository"
    }
}
