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
 */
package com.android.dream.lowlight

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import com.android.dream.lowlight.util.suspendCoroutineWithTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * Helper class that allows listening and running animations before entering or exiting low light.
 */
@Singleton
class LowLightTransitionCoordinator @Inject constructor() {
    /**
     * Listener that is notified before low light entry.
     */
    interface LowLightEnterListener {
        /**
         * Callback that is notified before the device enters low light.
         *
         * @return an optional animator that will be waited upon before entering low light.
         */
        fun onBeforeEnterLowLight(): Animator?
    }

    /**
     * Listener that is notified before low light exit.
     */
    interface LowLightExitListener {
        /**
         * Callback that is notified before the device exits low light.
         *
         * @return an optional animator that will be waited upon before exiting low light.
         */
        fun onBeforeExitLowLight(): Animator?
    }

    private var mLowLightEnterListener: LowLightEnterListener? = null
    private var mLowLightExitListener: LowLightExitListener? = null

    /**
     * Sets the listener for the low light enter event.
     *
     * Only one listener can be set at a time. This method will overwrite any previously set
     * listener. Null can be used to unset the listener.
     */
    fun setLowLightEnterListener(lowLightEnterListener: LowLightEnterListener?) {
        mLowLightEnterListener = lowLightEnterListener
    }

    /**
     * Sets the listener for the low light exit event.
     *
     * Only one listener can be set at a time. This method will overwrite any previously set
     * listener. Null can be used to unset the listener.
     */
    fun setLowLightExitListener(lowLightExitListener: LowLightExitListener?) {
        mLowLightExitListener = lowLightExitListener
    }

    /**
     * Notifies listeners that the device is about to enter or exit low light, and waits for the
     * animation to complete. If this function is cancelled, the animation is also cancelled.
     *
     * @param timeout the maximum duration to wait for the transition animation. If the animation
     * does not complete within this time period, a
     * @param entering true if listeners should be notified before entering low light, false if this
     * is notifying before exiting.
     */
    suspend fun waitForLowLightTransitionAnimation(timeout: Duration, entering: Boolean) =
        suspendCoroutineWithTimeout(timeout) { continuation ->
            var animator: Animator? = null
            if (entering && mLowLightEnterListener != null) {
                animator = mLowLightEnterListener!!.onBeforeEnterLowLight()
            } else if (!entering && mLowLightExitListener != null) {
                animator = mLowLightExitListener!!.onBeforeExitLowLight()
            }

            if (animator == null) {
                continuation.resume(Unit)
                return@suspendCoroutineWithTimeout
            }

            // If the listener returned an animator to indicate it was running an animation, run the
            // callback after the animation completes, otherwise call the callback directly.
            val listener = object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animator: Animator) {
                    continuation.resume(Unit)
                }

                override fun onAnimationCancel(animation: Animator) {
                    continuation.cancel()
                }
            }
            animator.addListener(listener)
        }
}
