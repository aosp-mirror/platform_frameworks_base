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
import android.transition.TransitionListenerAdapter
import android.transition.TransitionSet
import android.transition.TransitionValues
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnPreDrawListener
import com.android.app.animation.Interpolators
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Type
import com.android.systemui.keyguard.ui.view.layout.sections.transitions.ClockSizeTransition.SmartspaceMoveTransition.Companion.STATUS_AREA_MOVE_DOWN_MILLIS
import com.android.systemui.keyguard.ui.view.layout.sections.transitions.ClockSizeTransition.SmartspaceMoveTransition.Companion.STATUS_AREA_MOVE_UP_MILLIS
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import com.google.android.material.math.MathUtils
import kotlin.math.abs

internal fun View.getRect(): Rect = Rect(this.left, this.top, this.right, this.bottom)

internal fun View.setRect(rect: Rect) =
    this.setLeftTopRightBottom(rect.left, rect.top, rect.right, rect.bottom)

class ClockSizeTransition(
    config: IntraBlueprintTransition.Config,
    clockViewModel: KeyguardClockViewModel,
) : TransitionSet() {
    init {
        ordering = ORDERING_TOGETHER
        if (config.type != Type.SmartspaceVisibility) {
            addTransition(ClockFaceOutTransition(config, clockViewModel))
            addTransition(ClockFaceInTransition(config, clockViewModel))
        }
        addTransition(SmartspaceMoveTransition(config, clockViewModel))
    }

    abstract class VisibilityBoundsTransition() : Transition() {
        abstract val captureSmartspace: Boolean
        protected val TAG = this::class.simpleName!!

        override fun captureEndValues(transition: TransitionValues) = captureValues(transition)

        override fun captureStartValues(transition: TransitionValues) = captureValues(transition)

        override fun getTransitionProperties(): Array<String> = TRANSITION_PROPERTIES

        private fun captureValues(transition: TransitionValues) {
            val view = transition.view
            transition.values[PROP_VISIBILITY] = view.visibility
            transition.values[PROP_ALPHA] = view.alpha
            transition.values[PROP_BOUNDS] = view.getRect()

            if (!captureSmartspace) return
            val parent = view.parent as View
            val targetSSView =
                parent.findViewById<View>(sharedR.id.bc_smartspace_view)
                    ?: parent.findViewById<View>(R.id.keyguard_slice_view)
            if (targetSSView == null) {
                Log.e(TAG, "Failed to find smartspace equivalent target under $parent")
                return
            }
            transition.values[SMARTSPACE_BOUNDS] = targetSSView.getRect()
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
            if (startValues == null || endValues == null) {
                Log.w(
                    TAG,
                    "Couldn't create animator: startValues=$startValues; endValues=$endValues"
                )
                return null
            }

            var fromVis = startValues.values[PROP_VISIBILITY] as Int
            var fromIsVis = fromVis == View.VISIBLE
            var fromAlpha = startValues.values[PROP_ALPHA] as Float
            val fromBounds = startValues.values[PROP_BOUNDS] as Rect
            val fromSSBounds = startValues.values[SMARTSPACE_BOUNDS] as Rect?

            val toView = endValues.view
            val toVis = endValues.values[PROP_VISIBILITY] as Int
            val toBounds = endValues.values[PROP_BOUNDS] as Rect
            val toSSBounds = endValues.values[SMARTSPACE_BOUNDS] as Rect?
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
                if (DEBUG) {
                    Log.i(
                        TAG,
                        "$src: $toView; fract=$fract; alpha=$alpha; vis=$vis; bounds=$bounds;"
                    )
                }
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

                this@VisibilityBoundsTransition.addListener(
                    object : TransitionListenerAdapter() {
                        override fun onTransitionStart(t: Transition) {
                            toView.viewTreeObserver.addOnPreDrawListener(predrawCallback)
                        }

                        override fun onTransitionEnd(t: Transition) {
                            toView.viewTreeObserver.removeOnPreDrawListener(predrawCallback)
                        }
                    }
                )

                val listener =
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(anim: Animator) {
                            assignAnimValues("start", 0f, fromVis)
                        }

                        override fun onAnimationEnd(anim: Animator) {
                            assignAnimValues("end", 1f, toVis)
                            if (sendToBack) toView.translationZ = 0f
                        }
                    }

                anim.addListener(listener)
                assignAnimValues("init", 0f, fromVis)
            }
        }

        companion object {
            private const val PROP_VISIBILITY = "ClockSizeTransition:Visibility"
            private const val PROP_ALPHA = "ClockSizeTransition:Alpha"
            private const val PROP_BOUNDS = "ClockSizeTransition:Bounds"
            private const val SMARTSPACE_BOUNDS = "ClockSizeTransition:SSBounds"
            private val TRANSITION_PROPERTIES =
                arrayOf(PROP_VISIBILITY, PROP_ALPHA, PROP_BOUNDS, SMARTSPACE_BOUNDS)
        }
    }

    abstract class ClockFaceTransition(
        config: IntraBlueprintTransition.Config,
        val viewModel: KeyguardClockViewModel,
    ) : VisibilityBoundsTransition() {
        protected abstract val isLargeClock: Boolean
        protected abstract val smallClockMoveScale: Float
        override val captureSmartspace
            get() = !isLargeClock

        protected fun addTargets() {
            if (isLargeClock) {
                viewModel.currentClock.value?.let {
                    if (DEBUG) Log.i(TAG, "Adding large clock views: ${it.largeClock.layout.views}")
                    it.largeClock.layout.views.forEach { addTarget(it) }
                }
                    ?: run {
                        Log.e(TAG, "No large clock set, falling back")
                        addTarget(R.id.lockscreen_clock_view_large)
                    }
            } else {
                if (DEBUG) Log.i(TAG, "Adding small clock")
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

            fromBounds.set(toBounds)
            if (isLargeClock) {
                // Large clock shouldn't move; fromBounds already set
            } else if (toSSBounds != null && fromSSBounds != null) {
                // Instead of moving the small clock the full distance, we compute the distance
                // smartspace will move. We then scale this to match the duration of this animation
                // so that the small clock moves at the same speed as smartspace.
                val ssTranslation =
                    abs((toSSBounds.top - fromSSBounds.top) * smallClockMoveScale).toInt()
                fromBounds.top = toBounds.top - ssTranslation
                fromBounds.bottom = toBounds.bottom - ssTranslation
            } else {
                Log.e(TAG, "mutateBounds: smallClock received no smartspace bounds")
            }
        }
    }

    class ClockFaceInTransition(
        config: IntraBlueprintTransition.Config,
        viewModel: KeyguardClockViewModel,
    ) : ClockFaceTransition(config, viewModel) {
        override val isLargeClock = viewModel.isLargeClockVisible.value
        override val smallClockMoveScale = CLOCK_IN_MILLIS / STATUS_AREA_MOVE_DOWN_MILLIS.toFloat()

        init {
            duration = CLOCK_IN_MILLIS
            startDelay = CLOCK_IN_START_DELAY_MILLIS
            interpolator = CLOCK_IN_INTERPOLATOR
            addTargets()
        }

        companion object {
            const val CLOCK_IN_MILLIS = 167L
            const val CLOCK_IN_START_DELAY_MILLIS = 133L
            val CLOCK_IN_INTERPOLATOR = Interpolators.LINEAR_OUT_SLOW_IN
        }
    }

    class ClockFaceOutTransition(
        config: IntraBlueprintTransition.Config,
        viewModel: KeyguardClockViewModel,
    ) : ClockFaceTransition(config, viewModel) {
        override val isLargeClock = !viewModel.isLargeClockVisible.value
        override val smallClockMoveScale = CLOCK_OUT_MILLIS / STATUS_AREA_MOVE_UP_MILLIS.toFloat()

        init {
            duration = CLOCK_OUT_MILLIS
            interpolator = CLOCK_OUT_INTERPOLATOR
            addTargets()
        }

        companion object {
            const val CLOCK_OUT_MILLIS = 133L
            val CLOCK_OUT_INTERPOLATOR = Interpolators.LINEAR
        }
    }

    // TODO: Might need a mechanism to update this one while in-progress
    class SmartspaceMoveTransition(
        val config: IntraBlueprintTransition.Config,
        viewModel: KeyguardClockViewModel,
    ) : VisibilityBoundsTransition() {
        private val isLargeClock = viewModel.isLargeClockVisible.value
        override val captureSmartspace = false

        init {
            duration =
                if (isLargeClock) STATUS_AREA_MOVE_UP_MILLIS else STATUS_AREA_MOVE_DOWN_MILLIS
            interpolator = Interpolators.EMPHASIZED
            addTarget(sharedR.id.date_smartspace_view)
            addTarget(sharedR.id.bc_smartspace_view)

            // Notifications normally and media on split shade needs to be moved
            addTarget(R.id.aod_notification_icon_container)
            addTarget(R.id.status_view_media_container)
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
            // If view is changing visibility, hold it in place
            if (fromIsVis == toIsVis) return
            if (DEBUG) Log.i(TAG, "Holding position of ${view.id}")
            toBounds.set(fromBounds)
        }

        companion object {
            const val STATUS_AREA_MOVE_UP_MILLIS = 967L
            const val STATUS_AREA_MOVE_DOWN_MILLIS = 467L
        }
    }

    companion object {
        val DEBUG = false
    }
}
