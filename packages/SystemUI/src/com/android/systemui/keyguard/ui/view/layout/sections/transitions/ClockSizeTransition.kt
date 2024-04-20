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

package com.android.systemui.keyguard.ui.view.layout.sections.transitions

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.transition.Transition
import android.transition.TransitionSet
import android.transition.TransitionValues
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnPreDrawListener
import com.android.app.animation.Interpolators
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Type
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import com.google.android.material.math.MathUtils
import kotlin.math.abs

internal fun View.setRect(rect: Rect) =
    this.setLeftTopRightBottom(rect.left, rect.top, rect.right, rect.bottom)

class ClockSizeTransition(
    config: IntraBlueprintTransition.Config,
    clockViewModel: KeyguardClockViewModel,
    smartspaceViewModel: KeyguardSmartspaceViewModel,
) : TransitionSet() {
    init {
        ordering = ORDERING_TOGETHER
        if (config.type != Type.SmartspaceVisibility) {
            addTransition(ClockFaceOutTransition(config, clockViewModel, smartspaceViewModel))
            addTransition(ClockFaceInTransition(config, clockViewModel, smartspaceViewModel))
        }
        addTransition(SmartspaceMoveTransition(config, clockViewModel))
    }

    open class VisibilityBoundsTransition() : Transition() {
        var captureSmartspace: Boolean = false

        override fun captureEndValues(transition: TransitionValues) = captureValues(transition)
        override fun captureStartValues(transition: TransitionValues) = captureValues(transition)
        override fun getTransitionProperties(): Array<String> = TRANSITION_PROPERTIES

        private fun captureValues(transition: TransitionValues) {
            val view = transition.view
            transition.values[PROP_VISIBILITY] = view.visibility
            transition.values[PROP_ALPHA] = view.alpha
            transition.values[PROP_BOUNDS] = Rect(view.left, view.top, view.right, view.bottom)

            if (!captureSmartspace) return
            val ss = (view.parent as View).findViewById<View>(sharedR.id.bc_smartspace_view)
            if (ss == null) return
            transition.values[SMARTSPACE_BOUNDS] = Rect(ss.left, ss.top, ss.right, ss.bottom)
        }

        open fun mutateBounds(
            view: View,
            fromIsVis: Boolean,
            toIsVis: Boolean,
            fromBounds: Rect,
            toBounds: Rect,
            fromSSBounds: Rect?,
            toSSBounds: Rect?
        ) {}

        override fun createAnimator(
            sceenRoot: ViewGroup,
            startValues: TransitionValues?,
            endValues: TransitionValues?
        ): Animator? {
            if (startValues == null || endValues == null) return null

            var fromVis = startValues.values[PROP_VISIBILITY] as Int
            var fromIsVis = fromVis == View.VISIBLE
            var fromAlpha = startValues.values[PROP_ALPHA] as Float
            val fromBounds = startValues.values[PROP_BOUNDS] as Rect
            val fromSSBounds =
                if (captureSmartspace) startValues.values[SMARTSPACE_BOUNDS] as Rect else null

            val toView = endValues.view
            val toVis = endValues.values[PROP_VISIBILITY] as Int
            val toBounds = endValues.values[PROP_BOUNDS] as Rect
            val toSSBounds =
                if (captureSmartspace) endValues.values[SMARTSPACE_BOUNDS] as Rect else null
            val toIsVis = toVis == View.VISIBLE
            val toAlpha = if (toIsVis) 1f else 0f

            // Align starting visibility and alpha
            if (!fromIsVis) fromAlpha = 0f
            else if (fromAlpha <= 0f) {
                fromIsVis = false
                fromVis = View.INVISIBLE
            }

            mutateBounds(toView, fromIsVis, toIsVis, fromBounds, toBounds, fromSSBounds, toSSBounds)
            if (fromIsVis == toIsVis && fromBounds.equals(toBounds)) {
                if (DEBUG) {
                    Log.w(
                        TAG,
                        "Skipping no-op transition: $toView; " +
                            "vis: $fromVis -> $toVis; " +
                            "alpha: $fromAlpha -> $toAlpha; " +
                            "bounds: $fromBounds -> $toBounds; "
                    )
                }
                return null
            }

            val sendToBack = fromIsVis && !toIsVis
            fun lerp(start: Int, end: Int, fract: Float): Int =
                MathUtils.lerp(start.toFloat(), end.toFloat(), fract).toInt()
            fun computeBounds(fract: Float): Rect =
                Rect(
                    lerp(fromBounds.left, toBounds.left, fract),
                    lerp(fromBounds.top, toBounds.top, fract),
                    lerp(fromBounds.right, toBounds.right, fract),
                    lerp(fromBounds.bottom, toBounds.bottom, fract)
                )

            fun assignAnimValues(src: String, fract: Float, vis: Int? = null) {
                val bounds = computeBounds(fract)
                val alpha = MathUtils.lerp(fromAlpha, toAlpha, fract)
                if (DEBUG)
                    Log.i(
                        TAG,
                        "$src: $toView; fract=$fract; alpha=$alpha; vis=$vis; bounds=$bounds;"
                    )
                toView.setVisibility(vis ?: View.VISIBLE)
                toView.setAlpha(alpha)
                toView.setRect(bounds)
            }

            if (DEBUG) {
                Log.i(
                    TAG,
                    "transitioning: $toView; " +
                        "vis: $fromVis -> $toVis; " +
                        "alpha: $fromAlpha -> $toAlpha; " +
                        "bounds: $fromBounds -> $toBounds; "
                )
            }

            return ValueAnimator.ofFloat(0f, 1f).also { anim ->
                // We enforce the animation parameters on the target view every frame using a
                // predraw listener. This is suboptimal but prevents issues with layout passes
                // overwriting the animation for individual frames.
                val predrawCallback = OnPreDrawListener {
                    assignAnimValues("predraw", anim.animatedFraction)
                    return@OnPreDrawListener true
                }

                val listener =
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(anim: Animator) {
                            assignAnimValues("start", 0f, fromVis)
                        }

                        override fun onAnimationEnd(anim: Animator) {
                            assignAnimValues("end", 1f, toVis)
                            if (sendToBack) toView.translationZ = 0f
                            toView.viewTreeObserver.removeOnPreDrawListener(predrawCallback)
                        }

                        override fun onAnimationPause(anim: Animator) {
                            toView.viewTreeObserver.removeOnPreDrawListener(predrawCallback)
                        }

                        override fun onAnimationResume(anim: Animator) {
                            toView.viewTreeObserver.addOnPreDrawListener(predrawCallback)
                        }
                    }

                anim.duration = duration
                anim.startDelay = startDelay
                anim.interpolator = interpolator
                anim.addListener(listener)
                anim.addPauseListener(listener)

                assignAnimValues("init", 0f, fromVis)
                toView.viewTreeObserver.addOnPreDrawListener(predrawCallback)
            }
        }

        companion object {
            private const val PROP_VISIBILITY = "ClockSizeTransition:Visibility"
            private const val PROP_ALPHA = "ClockSizeTransition:Alpha"
            private const val PROP_BOUNDS = "ClockSizeTransition:Bounds"
            private const val SMARTSPACE_BOUNDS = "ClockSizeTransition:SSBounds"
            private val TRANSITION_PROPERTIES =
                arrayOf(PROP_VISIBILITY, PROP_ALPHA, PROP_BOUNDS, SMARTSPACE_BOUNDS)

            private val DEBUG = false
            private val TAG = VisibilityBoundsTransition::class.simpleName!!
        }
    }

    class ClockFaceInTransition(
        config: IntraBlueprintTransition.Config,
        val viewModel: KeyguardClockViewModel,
        val smartspaceViewModel: KeyguardSmartspaceViewModel,
    ) : VisibilityBoundsTransition() {
        init {
            duration = CLOCK_IN_MILLIS
            startDelay = CLOCK_IN_START_DELAY_MILLIS
            interpolator = CLOCK_IN_INTERPOLATOR
            captureSmartspace =
                !viewModel.isLargeClockVisible.value && smartspaceViewModel.isSmartspaceEnabled

            if (viewModel.isLargeClockVisible.value) {
                viewModel.currentClock.value?.let {
                    it.largeClock.layout.views.forEach { addTarget(it) }
                }
            } else {
                addTarget(R.id.lockscreen_clock_view)
            }
        }

        override fun mutateBounds(
            view: View,
            fromIsVis: Boolean,
            toIsVis: Boolean,
            fromBounds: Rect,
            toBounds: Rect,
            fromSSBounds: Rect?,
            toSSBounds: Rect?
        ) {
            // Move normally if clock is not changing visibility
            if (fromIsVis == toIsVis) return

            fromBounds.left = toBounds.left
            fromBounds.right = toBounds.right
            if (viewModel.isLargeClockVisible.value) {
                // Large clock shouldn't move
                fromBounds.top = toBounds.top
                fromBounds.bottom = toBounds.bottom
            } else if (toSSBounds != null && fromSSBounds != null) {
                // Instead of moving the small clock the full distance, we compute the distance
                // smartspace will move. We then scale this to match the duration of this animation
                // so that the small clock moves at the same speed as smartspace.
                val ssTranslation =
                    abs((toSSBounds.top - fromSSBounds.top) * SMALL_CLOCK_IN_MOVE_SCALE).toInt()
                fromBounds.top = toBounds.top - ssTranslation
                fromBounds.bottom = toBounds.bottom - ssTranslation
            } else {
                Log.e(TAG, "mutateBounds: smallClock received no smartspace bounds")
            }
        }

        companion object {
            const val CLOCK_IN_MILLIS = 167L
            const val CLOCK_IN_START_DELAY_MILLIS = 133L
            val CLOCK_IN_INTERPOLATOR = Interpolators.LINEAR_OUT_SLOW_IN
            const val SMALL_CLOCK_IN_MOVE_SCALE =
                CLOCK_IN_MILLIS / SmartspaceMoveTransition.STATUS_AREA_MOVE_DOWN_MILLIS.toFloat()
            private val TAG = ClockFaceInTransition::class.simpleName!!
        }
    }

    class ClockFaceOutTransition(
        config: IntraBlueprintTransition.Config,
        val viewModel: KeyguardClockViewModel,
        val smartspaceViewModel: KeyguardSmartspaceViewModel,
    ) : VisibilityBoundsTransition() {
        init {
            duration = CLOCK_OUT_MILLIS
            interpolator = CLOCK_OUT_INTERPOLATOR
            captureSmartspace =
                viewModel.isLargeClockVisible.value && smartspaceViewModel.isSmartspaceEnabled

            if (viewModel.isLargeClockVisible.value) {
                addTarget(R.id.lockscreen_clock_view)
            } else {
                viewModel.currentClock.value?.let {
                    it.largeClock.layout.views.forEach { addTarget(it) }
                }
            }
        }

        override fun mutateBounds(
            view: View,
            fromIsVis: Boolean,
            toIsVis: Boolean,
            fromBounds: Rect,
            toBounds: Rect,
            fromSSBounds: Rect?,
            toSSBounds: Rect?
        ) {
            // Move normally if clock is not changing visibility
            if (fromIsVis == toIsVis) return

            toBounds.left = fromBounds.left
            toBounds.right = fromBounds.right
            if (!viewModel.isLargeClockVisible.value) {
                // Large clock shouldn't move
                toBounds.top = fromBounds.top
                toBounds.bottom = fromBounds.bottom
            } else if (toSSBounds != null && fromSSBounds != null) {
                // Instead of moving the small clock the full distance, we compute the distance
                // smartspace will move. We then scale this to match the duration of this animation
                // so that the small clock moves at the same speed as smartspace.
                val ssTranslation =
                    abs((toSSBounds.top - fromSSBounds.top) * SMALL_CLOCK_OUT_MOVE_SCALE).toInt()
                toBounds.top = fromBounds.top - ssTranslation
                toBounds.bottom = fromBounds.bottom - ssTranslation
            } else {
                Log.w(TAG, "mutateBounds: smallClock received no smartspace bounds")
            }
        }

        companion object {
            const val CLOCK_OUT_MILLIS = 133L
            val CLOCK_OUT_INTERPOLATOR = Interpolators.LINEAR
            const val SMALL_CLOCK_OUT_MOVE_SCALE =
                CLOCK_OUT_MILLIS / SmartspaceMoveTransition.STATUS_AREA_MOVE_UP_MILLIS.toFloat()
            private val TAG = ClockFaceOutTransition::class.simpleName!!
        }
    }

    // TODO: Might need a mechanism to update this one while in-progress
    class SmartspaceMoveTransition(
        val config: IntraBlueprintTransition.Config,
        viewModel: KeyguardClockViewModel,
    ) : VisibilityBoundsTransition() {
        init {
            duration =
                if (viewModel.isLargeClockVisible.value) STATUS_AREA_MOVE_UP_MILLIS
                else STATUS_AREA_MOVE_DOWN_MILLIS
            interpolator = Interpolators.EMPHASIZED
            addTarget(sharedR.id.date_smartspace_view)
            addTarget(sharedR.id.weather_smartspace_view)
            addTarget(sharedR.id.bc_smartspace_view)

            // Notifications normally and media on split shade needs to be moved
            addTarget(R.id.aod_notification_icon_container)
            addTarget(R.id.status_view_media_container)
        }

        companion object {
            const val STATUS_AREA_MOVE_UP_MILLIS = 967L
            const val STATUS_AREA_MOVE_DOWN_MILLIS = 467L
        }
    }
}
