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
import android.view.ViewPropertyAnimator
import kotlin.coroutines.resume
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
                continuation.resume(Unit)
                animationListener?.onAnimationEnd(animation)
            }

            override fun onAnimationCancel(animation: Animator) {
                animationListener?.onAnimationCancel(animation)
            }

            override fun onAnimationRepeat(animation: Animator) {
                animationListener?.onAnimationRepeat(animation)
            }
        }
    )
    continuation.invokeOnCancellation { this.cancel() }
}
