/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.management

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.IdRes
import android.content.Intent
import android.transition.Transition
import android.transition.TransitionValues
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.android.systemui.R
import com.android.systemui.animation.Interpolators
import com.android.systemui.controls.ui.ControlsUiController

object ControlsAnimations {

    private const val ALPHA_EXIT_DURATION = 183L
    private const val ALPHA_ENTER_DELAY = ALPHA_EXIT_DURATION
    private const val ALPHA_ENTER_DURATION = 350L - ALPHA_ENTER_DELAY

    private const val Y_TRANSLATION_EXIT_DURATION = 183L
    private const val Y_TRANSLATION_ENTER_DELAY = Y_TRANSLATION_EXIT_DURATION - ALPHA_ENTER_DELAY
    private const val Y_TRANSLATION_ENTER_DURATION = 400L - Y_TRANSLATION_EXIT_DURATION
    private var translationY: Float = -1f

    /**
     * Setup an activity to handle enter/exit animations. [view] should be the root of the content.
     * Fade and translate together.
     */
    fun observerForAnimations(
            view: ViewGroup,
            window: Window,
            intent: Intent,
            animateY: Boolean = true
    ): LifecycleObserver {
        return object : LifecycleObserver {
            var showAnimation = intent.getBooleanExtra(ControlsUiController.EXTRA_ANIMATE, false)

            init {
                // Must flag the parent group to move it all together, and set the initial
                // transitionAlpha to 0.0f. This property is reserved for fade animations.
                view.setTransitionGroup(true)
                view.transitionAlpha = 0.0f

                if (translationY == -1f) {
                    if (animateY) {
                        translationY = view.context.resources.getDimensionPixelSize(
                                R.dimen.global_actions_controls_y_translation).toFloat()
                    } else {
                        translationY = 0f
                    }
                }
            }

            @OnLifecycleEvent(Lifecycle.Event.ON_START)
            fun setup() {
                with(window) {
                    allowEnterTransitionOverlap = true
                    enterTransition = enterWindowTransition(view.getId())
                    exitTransition = exitWindowTransition(view.getId())
                    reenterTransition = enterWindowTransition(view.getId())
                    returnTransition = exitWindowTransition(view.getId())
                }
            }

            @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
            fun enterAnimation() {
                if (showAnimation) {
                    ControlsAnimations.enterAnimation(view).start()
                    showAnimation = false
                }
            }

            @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
            fun resetAnimation() {
                view.translationY = 0f
            }
        }
    }

    fun enterAnimation(view: View): Animator {
        Log.d(ControlsUiController.TAG, "Enter animation for $view")

        view.transitionAlpha = 0.0f
        view.alpha = 1.0f

        view.translationY = translationY

        val alphaAnimator = ObjectAnimator.ofFloat(view, "transitionAlpha", 0.0f, 1.0f).apply {
            interpolator = Interpolators.DECELERATE_QUINT
            startDelay = ALPHA_ENTER_DELAY
            duration = ALPHA_ENTER_DURATION
        }

        val yAnimator = ObjectAnimator.ofFloat(view, "translationY", 0.0f).apply {
            interpolator = Interpolators.DECELERATE_QUINT
            startDelay = Y_TRANSLATION_ENTER_DURATION
            duration = Y_TRANSLATION_ENTER_DURATION
        }

        return AnimatorSet().apply {
            playTogether(alphaAnimator, yAnimator)
        }
    }

    /**
     * Properly handle animations originating from dialogs. Activity transitions require
     * transitioning between two activities, so expose this method for dialogs to animate
     * on exit.
     */
    @JvmStatic
    fun exitAnimation(view: View, onEnd: Runnable? = null): Animator {
        Log.d(ControlsUiController.TAG, "Exit animation for $view")

        val alphaAnimator = ObjectAnimator.ofFloat(view, "transitionAlpha", 0.0f).apply {
            interpolator = Interpolators.ACCELERATE
            duration = ALPHA_EXIT_DURATION
        }

        view.translationY = 0.0f
        val yAnimator = ObjectAnimator.ofFloat(view, "translationY", -translationY).apply {
            interpolator = Interpolators.ACCELERATE
            duration = Y_TRANSLATION_EXIT_DURATION
        }

        return AnimatorSet().apply {
            playTogether(alphaAnimator, yAnimator)
            onEnd?.let {
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        it.run()
                    }
                })
            }
        }
    }

    fun enterWindowTransition(@IdRes id: Int) =
        WindowTransition({ view: View -> enterAnimation(view) }).apply {
            addTarget(id)
        }

    fun exitWindowTransition(@IdRes id: Int) =
        WindowTransition({ view: View -> exitAnimation(view) }).apply {
            addTarget(id)
        }
}

/**
 * In order to animate, at least one property must be marked on each view that should move.
 * Setting "item" is just a flag to indicate that it should move by the animator.
 */
class WindowTransition(
    val animator: (view: View) -> Animator
) : Transition() {
    override fun captureStartValues(tv: TransitionValues) {
        tv.values["item"] = 0.0f
    }

    override fun captureEndValues(tv: TransitionValues) {
        tv.values["item"] = 1.0f
    }

    override fun createAnimator(
        sceneRoot: ViewGroup,
        startValues: TransitionValues?,
        endValues: TransitionValues?
    ): Animator? = animator(startValues!!.view)
}
