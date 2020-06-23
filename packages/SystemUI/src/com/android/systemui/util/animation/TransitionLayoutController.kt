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
 * limitations under the License
 */

package com.android.systemui.util.animation

import android.animation.ValueAnimator
import android.graphics.PointF
import android.util.MathUtils
import com.android.systemui.Interpolators

/**
 * The fraction after which we start fading in when going from a gone widget to a visible one
 */
private const val GONE_FADE_FRACTION = 0.8f

/**
 * The amont we're scaling appearing views
 */
private const val GONE_SCALE_AMOUNT = 0.8f

/**
 * A controller for a [TransitionLayout] which handles state transitions and keeps the transition
 * layout up to date with the desired state.
 */
open class TransitionLayoutController {

    /**
     * The layout that this controller controls
     */
    private var transitionLayout: TransitionLayout? = null
    private var currentState = TransitionViewState()
    private var animationStartState: TransitionViewState? = null
    private var state = TransitionViewState()
    private var pivot = PointF()
    private var animator: ValueAnimator = ValueAnimator.ofFloat(0.0f, 1.0f)
    private var currentHeight: Int = 0
    private var currentWidth: Int = 0
    var sizeChangedListener: ((Int, Int) -> Unit)? = null

    init {
        animator.apply {
            addUpdateListener {
                updateStateFromAnimation()
            }
            interpolator = Interpolators.FAST_OUT_SLOW_IN
        }
    }

    private fun updateStateFromAnimation() {
        if (animationStartState == null || !animator.isRunning) {
            return
        }
        val view = transitionLayout ?: return
        getInterpolatedState(
                startState = animationStartState!!,
                endState = state,
                progress = animator.animatedFraction,
                pivot = pivot,
                resultState = currentState)
        applyStateToLayout(currentState)
    }

    private fun applyStateToLayout(state: TransitionViewState) {
        transitionLayout?.setState(state)
        if (currentHeight != state.height || currentWidth != state.width) {
            currentHeight = state.height
            currentWidth = state.width
            sizeChangedListener?.invoke(currentWidth, currentHeight)
        }
    }

    /**
     * Get an interpolated state between two viewstates. This interpolates all positions for all
     * widgets as well as it's bounds based on the given input.
     */
    fun getInterpolatedState(
        startState: TransitionViewState,
        endState: TransitionViewState,
        progress: Float,
        pivot: PointF,
        resultState: TransitionViewState
    ) {
        this.pivot.set(pivot)
        val view = transitionLayout ?: return
        val childCount = view.childCount
        for (i in 0 until childCount) {
            val id = view.getChildAt(i).id
            val resultWidgetState = resultState.widgetStates[id] ?: WidgetState()
            val widgetStart = startState.widgetStates[id] ?: continue
            val widgetEnd = endState.widgetStates[id] ?: continue
            var alphaProgress = progress
            var widthProgress = progress
            val resultMeasureWidth: Int
            val resultMeasureHeight: Int
            val newScale: Float
            val resultX: Float
            val resultY: Float
            if (widgetStart.gone != widgetEnd.gone) {
                // A view is appearing or disappearing. Let's not just interpolate between them as
                // this looks quite ugly
                val nowGone: Boolean
                if (widgetStart.gone) {

                    // Only fade it in at the very end
                    alphaProgress = MathUtils.map(GONE_FADE_FRACTION, 1.0f, 0.0f, 1.0f, progress)
                    nowGone = progress < GONE_FADE_FRACTION

                    // Scale it just a little, not all the way
                    val endScale = widgetEnd.scale
                    newScale = MathUtils.lerp(GONE_SCALE_AMOUNT * endScale, endScale, progress)

                    // don't clip
                    widthProgress = 1.0f

                    // Let's directly measure it with the end state
                    resultMeasureWidth = widgetEnd.measureWidth
                    resultMeasureHeight = widgetEnd.measureHeight

                    // Let's make sure we're centering the view in the gone view instead of having
                    // the left at 0
                    resultX = MathUtils.lerp(widgetStart.x - resultMeasureWidth / 2.0f,
                            widgetEnd.x,
                            progress)
                    resultY = MathUtils.lerp(widgetStart.y - resultMeasureHeight / 2.0f,
                            widgetEnd.y,
                            progress)
                } else {

                    // Fadeout in the very beginning
                    alphaProgress = MathUtils.map(0.0f, 1.0f - GONE_FADE_FRACTION, 0.0f, 1.0f,
                            progress)
                    nowGone = progress > 1.0f - GONE_FADE_FRACTION

                    // Scale it just a little, not all the way
                    val startScale = widgetStart.scale
                    newScale = MathUtils.lerp(startScale, startScale * GONE_SCALE_AMOUNT, progress)

                    // Don't clip
                    widthProgress = 0.0f

                    // Let's directly measure it with the start state
                    resultMeasureWidth = widgetStart.measureWidth
                    resultMeasureHeight = widgetStart.measureHeight

                    // Let's make sure we're centering the view in the gone view instead of having
                    // the left at 0
                    resultX = MathUtils.lerp(widgetStart.x,
                            widgetEnd.x - resultMeasureWidth / 2.0f,
                            progress)
                    resultY = MathUtils.lerp(widgetStart.y,
                            widgetEnd.y - resultMeasureHeight / 2.0f,
                            progress)
                }
                resultWidgetState.gone = nowGone
            } else {
                resultWidgetState.gone = widgetStart.gone
                // Let's directly measure it with the end state
                resultMeasureWidth = widgetEnd.measureWidth
                resultMeasureHeight = widgetEnd.measureHeight
                newScale = MathUtils.lerp(widgetStart.scale, widgetEnd.scale, progress)
                resultX = MathUtils.lerp(widgetStart.x, widgetEnd.x, progress)
                resultY = MathUtils.lerp(widgetStart.y, widgetEnd.y, progress)
            }
            resultWidgetState.apply {
                x = resultX
                y = resultY
                alpha = MathUtils.lerp(widgetStart.alpha, widgetEnd.alpha, alphaProgress)
                width = MathUtils.lerp(widgetStart.width.toFloat(), widgetEnd.width.toFloat(),
                        widthProgress).toInt()
                height = MathUtils.lerp(widgetStart.height.toFloat(), widgetEnd.height.toFloat(),
                        widthProgress).toInt()
                scale = newScale

                // Let's directly measure it with the end state
                measureWidth = resultMeasureWidth
                measureHeight = resultMeasureHeight
            }
            resultState.widgetStates[id] = resultWidgetState
        }
        resultState.apply {
            width = MathUtils.lerp(startState.width.toFloat(), endState.width.toFloat(),
                    progress).toInt()
            height = MathUtils.lerp(startState.height.toFloat(), endState.height.toFloat(),
                    progress).toInt()
            translation.x = (endState.width - width) * pivot.x
            translation.y = (endState.height - height) * pivot.y
        }
    }

    fun attach(transitionLayout: TransitionLayout) {
        this.transitionLayout = transitionLayout
    }

    /**
     * Set a new state to be applied to the dynamic view.
     *
     * @param state the state to be applied
     * @param animate should this change be animated. If [false] the we will either apply the
     * state immediately if no animation is running, and if one is running, we will update the end
     * value to match the new state.
     * @param applyImmediately should this change be applied immediately, canceling all running
     * animations
     */
    fun setState(
        state: TransitionViewState,
        applyImmediately: Boolean,
        animate: Boolean,
        duration: Long = 0,
        delay: Long = 0
    ) {
        val animated = animate && currentState.width != 0
        this.state = state.copy()
        if (applyImmediately || transitionLayout == null) {
            animator.cancel()
            applyStateToLayout(this.state)
            currentState = state.copy(reusedState = currentState)
        } else if (animated) {
            animationStartState = currentState.copy()
            animator.duration = duration
            animator.startDelay = delay
            animator.start()
        } else if (!animator.isRunning) {
            applyStateToLayout(this.state)
            currentState = state.copy(reusedState = currentState)
        }
        // otherwise the desired state was updated and the animation will go to the new target
    }

    /**
     * Set a new state that will be used to measure the view itself and is useful during
     * transitions, where the state set via [setState] may differ from how the view
     * should be measured.
     */
    fun setMeasureState(
        state: TransitionViewState
    ) {
        transitionLayout?.measureState = state
    }
}
