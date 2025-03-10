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

package com.android.systemui.volume.dialog.ui.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.ViewPropertyAnimator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Starts animation and suspends until it's finished. Cancels the animation if the running coroutine
 * is cancelled.
 *
 * Careful! This method overrides [ViewPropertyAnimator.setListener]. Use [animationListener]
 * instead.
 */
suspend fun ViewPropertyAnimator.suspendAnimate(
    animationListener: Animator.AnimatorListener? = null
) = suspendCancellableCoroutine { continuation ->
    start()
    setListener(
        object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {
                animationListener?.onAnimationStart(animation)
            }

            override fun onAnimationEnd(animation: Animator) {
                continuation.resumeIfCan(Unit)
                animationListener?.onAnimationEnd(animation)
            }

            override fun onAnimationCancel(animation: Animator) {
                continuation.resumeIfCan(Unit)
                animationListener?.onAnimationCancel(animation)
            }

            override fun onAnimationRepeat(animation: Animator) {
                animationListener?.onAnimationRepeat(animation)
            }
        }
    )
    continuation.invokeOnCancellation { this.cancel() }
}

/**
 * Starts animation and suspends until it's finished. Cancels the animation if the running coroutine
 * is cancelled.
 */
@Suppress("UNCHECKED_CAST")
suspend fun <T> ValueAnimator.awaitAnimation(onValueChanged: (T) -> Unit) {
    suspendCancellableCoroutine { continuation ->
        addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = continuation.resumeIfCan(Unit)

                override fun onAnimationCancel(animation: Animator) = continuation.resumeIfCan(Unit)
            }
        )
        addUpdateListener { onValueChanged(it.animatedValue as T) }

        start()
        continuation.invokeOnCancellation { cancel() }
    }
}

/**
 * Starts spring animation and suspends until it's finished. Cancels the animation if the running
 * coroutine is cancelled.
 */
suspend fun SpringAnimation.suspendAnimate(
    animationUpdateListener: DynamicAnimation.OnAnimationUpdateListener? = null,
    animationEndListener: DynamicAnimation.OnAnimationEndListener? = null,
) = suspendCancellableCoroutine { continuation ->
    animationUpdateListener?.let(::addUpdateListener)
    addEndListener { animation, canceled, value, velocity ->
        continuation.resumeIfCan(Unit)
        animationEndListener?.onAnimationEnd(animation, canceled, value, velocity)
    }
    animateToFinalPosition(1F)
    continuation.invokeOnCancellation {
        animationUpdateListener?.let(::removeUpdateListener)
        animationEndListener?.let(::removeEndListener)
        cancel()
    }
}

private fun <T> CancellableContinuation<T>.resumeIfCan(value: T) {
    if (!isCancelled && !isCompleted) {
        resume(value)
    }
}
