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
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnPreDrawListener
import com.android.app.animation.Interpolators
import com.android.systemui.customization.R as customR
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Type
import com.android.systemui.keyguard.ui.view.layout.sections.transitions.ClockSizeTransition.SmartspaceMoveTransition.Companion.STATUS_AREA_MOVE_DOWN_MILLIS
import com.android.systemui.keyguard.ui.view.layout.sections.transitions.ClockSizeTransition.SmartspaceMoveTransition.Companion.STATUS_AREA_MOVE_UP_MILLIS
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.plugins.clocks.ClockLogger.Companion.getVisText
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
    logBuffer: LogBuffer,
) : TransitionSet() {

    init {
        ordering = ORDERING_TOGETHER
        if (config.type != Type.SmartspaceVisibility) {
            addTransition(ClockFaceOutTransition(config, clockViewModel, logBuffer))
            addTransition(ClockFaceInTransition(config, clockViewModel, logBuffer))
        }
        addTransition(SmartspaceMoveTransition(config, clockViewModel, logBuffer))
    }

    abstract class VisibilityBoundsTransition(logBuffer: LogBuffer) : Transition() {
        protected val logger = Logger(logBuffer, this::class.simpleName!!)
        abstract val captureSmartspace: Boolean

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
                logger.e({ "Failed to find smartspace equivalent target under $str1" }) {
                    str1 = "$parent"
                }
                return
            }
            transition.values[SMARTSPACE_BOUNDS] = targetSSView.getRect()
        }

        open fun initTargets(from: Target, to: Target) {}

        open fun mutateTargets(from: Target, to: Target) {}

        data class Target(
            var view: View,
            var visibility: Int,
            var isVisible: Boolean,
            var alpha: Float,
            var bounds: Rect,
            var ssBounds: Rect?,
        ) {
            companion object {
                fun fromStart(startValues: TransitionValues): Target {
                    var fromVis = startValues.values[PROP_VISIBILITY] as Int
                    var fromIsVis = fromVis == View.VISIBLE
                    var fromAlpha = startValues.values[PROP_ALPHA] as Float

                    // Align starting visibility and alpha
                    if (!fromIsVis) fromAlpha = 0f
                    else if (fromAlpha <= 0f) {
                        fromIsVis = false
                        fromVis = View.INVISIBLE
                    }

                    return Target(
                        view = startValues.view,
                        visibility = fromVis,
                        isVisible = fromIsVis,
                        alpha = fromAlpha,
                        bounds = startValues.values[PROP_BOUNDS] as Rect,
                        ssBounds = startValues.values[SMARTSPACE_BOUNDS] as Rect?,
                    )
                }

                fun fromEnd(endValues: TransitionValues): Target {
                    val toVis = endValues.values[PROP_VISIBILITY] as Int
                    val toIsVis = toVis == View.VISIBLE

                    return Target(
                        view = endValues.view,
                        visibility = toVis,
                        isVisible = toIsVis,
                        alpha = if (toIsVis) 1f else 0f,
                        bounds = endValues.values[PROP_BOUNDS] as Rect,
                        ssBounds = endValues.values[SMARTSPACE_BOUNDS] as Rect?,
                    )
                }
            }
        }

        override fun createAnimator(
            sceenRoot: ViewGroup,
            startValues: TransitionValues?,
            endValues: TransitionValues?,
        ): Animator? {
            if (startValues == null || endValues == null) {
                logger.w({ "Couldn't create animator: startValues=$str1; endValues=$str2" }) {
                    str1 = "$startValues"
                    str2 = "$endValues"
                }
                return null
            }

            val from = Target.fromStart(startValues)
            val to = Target.fromEnd(endValues)
            initTargets(from, to)
            mutateTargets(from, to)

            if (from.isVisible == to.isVisible && from.bounds.equals(to.bounds)) {
                logger.w({
                    "Skipping no-op transition: $str1; " +
                        "vis: ${getVisText(int1)} -> ${getVisText(int2)}; " +
                        "alpha: $str2; bounds: $str3; "
                }) {
                    str1 = "${to.view}"
                    int1 = from.visibility
                    int2 = to.visibility
                    str2 = "${from.alpha} -> ${to.alpha}"
                    str3 = "${from.bounds} -> ${to.bounds}"
                }

                return null
            }

            val sendToBack = from.isVisible && !to.isVisible
            fun lerp(start: Int, end: Int, fract: Float): Int =
                MathUtils.lerp(start.toFloat(), end.toFloat(), fract).toInt()
            fun computeBounds(fract: Float): Rect =
                Rect(
                    lerp(from.bounds.left, to.bounds.left, fract),
                    lerp(from.bounds.top, to.bounds.top, fract),
                    lerp(from.bounds.right, to.bounds.right, fract),
                    lerp(from.bounds.bottom, to.bounds.bottom, fract),
                )

            fun assignAnimValues(
                src: String,
                fract: Float,
                vis: Int? = null,
                log: Boolean = false,
            ) {
                mutateTargets(from, to)
                val bounds = computeBounds(fract)
                val alpha = MathUtils.lerp(from.alpha, to.alpha, fract)
                if (log) {
                    logger.i({
                        "$str1: $str2; fract=$int1%; alpha=$double1; " +
                            "vis=${getVisText(int2)}; bounds=$str3;"
                    }) {
                        str1 = src
                        str2 = "${to.view}"
                        int1 = (fract * 100).toInt()
                        double1 = alpha.toDouble()
                        int2 = vis ?: View.VISIBLE
                        str3 = "$bounds"
                    }
                }

                to.view.setVisibility(vis ?: View.VISIBLE)
                to.view.setAlpha(alpha)
                to.view.setRect(bounds)
            }

            logger.i({
                "transitioning: $str1; vis: ${getVisText(int1)} -> ${getVisText(int2)}; " +
                    "alpha: $str2; bounds: $str3;"
            }) {
                str1 = "${to.view}"
                int1 = from.visibility
                int2 = to.visibility
                str2 = "${from.alpha} -> ${to.alpha}"
                str3 = "${from.bounds} -> ${to.bounds}"
            }

            return ValueAnimator.ofFloat(0f, 1f).also { anim ->
                // We enforce the animation parameters on the target view every frame using a
                // predraw listener. This is suboptimal but prevents issues with layout passes
                // overwriting the animation for individual frames.
                val predrawCallback = OnPreDrawListener {
                    assignAnimValues("predraw", anim.animatedFraction, log = false)
                    return@OnPreDrawListener true
                }

                this@VisibilityBoundsTransition.addListener(
                    object : TransitionListenerAdapter() {
                        override fun onTransitionStart(t: Transition) {
                            to.view.viewTreeObserver.addOnPreDrawListener(predrawCallback)
                        }

                        override fun onTransitionEnd(t: Transition) {
                            to.view.viewTreeObserver.removeOnPreDrawListener(predrawCallback)
                        }
                    }
                )

                val listener =
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(anim: Animator) {
                            assignAnimValues("start", 0f, from.visibility, log = true)
                        }

                        override fun onAnimationEnd(anim: Animator) {
                            assignAnimValues("end", 1f, to.visibility, log = true)
                            if (sendToBack) to.view.translationZ = 0f
                        }
                    }

                anim.addListener(listener)
                assignAnimValues("init", 0f, from.visibility, log = true)
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
        logBuffer: LogBuffer,
    ) : VisibilityBoundsTransition(logBuffer) {
        protected abstract val isLargeClock: Boolean
        protected abstract val smallClockMoveScale: Float
        override val captureSmartspace
            get() = !isLargeClock

        protected fun addTargets() {
            if (isLargeClock) {
                viewModel.currentClock.value?.let {
                    logger.i({ "Adding large clock views: $str1" }) {
                        str1 = "${it.largeClock.layout.views}"
                    }
                    it.largeClock.layout.views.forEach { addTarget(it) }
                }
                    ?: run {
                        logger.e("No large clock set, falling back")
                        addTarget(customR.id.lockscreen_clock_view_large)
                    }
            } else {
                logger.i("Adding small clock")
                addTarget(customR.id.lockscreen_clock_view)
            }
        }

        override fun initTargets(from: Target, to: Target) {
            // Move normally if clock is not changing visibility
            if (from.isVisible == to.isVisible) return

            from.bounds.set(to.bounds)
            if (isLargeClock) {
                // Large clock shouldn't move; fromBounds already set
            } else if (to.ssBounds != null && from.ssBounds != null) {
                // Instead of moving the small clock the full distance, we compute the distance
                // smartspace will move. We then scale this to match the duration of this animation
                // so that the small clock moves at the same speed as smartspace.
                val ssTranslation =
                    abs((to.ssBounds!!.top - from.ssBounds!!.top) * smallClockMoveScale).toInt()
                from.bounds.top = to.bounds.top - ssTranslation
                from.bounds.bottom = to.bounds.bottom - ssTranslation
            } else {
                logger.e("initTargets: smallClock received no smartspace bounds")
            }
        }
    }

    class ClockFaceInTransition(
        config: IntraBlueprintTransition.Config,
        viewModel: KeyguardClockViewModel,
        logBuffer: LogBuffer,
    ) : ClockFaceTransition(config, viewModel, logBuffer) {
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
        logBuffer: LogBuffer,
    ) : ClockFaceTransition(config, viewModel, logBuffer) {
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

    class SmartspaceMoveTransition(
        val config: IntraBlueprintTransition.Config,
        val viewModel: KeyguardClockViewModel,
        logBuffer: LogBuffer,
    ) : VisibilityBoundsTransition(logBuffer) {
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

        override fun initTargets(from: Target, to: Target) {
            // If view is changing visibility, hold it in place
            if (from.isVisible == to.isVisible) return
            logger.i({ "Holding position of $int1" }) { int1 = to.view.id }

            if (from.isVisible) {
                to.bounds.set(from.bounds)
            } else {
                from.bounds.set(to.bounds)
            }
        }

        override fun mutateTargets(from: Target, to: Target) {
            if (to.view.id == sharedR.id.date_smartspace_view) {
                to.isVisible = !viewModel.hasCustomWeatherDataDisplay.value
                to.visibility = if (to.isVisible) View.VISIBLE else View.GONE
                to.alpha = if (to.isVisible) 1f else 0f
            }
        }

        companion object {
            const val STATUS_AREA_MOVE_UP_MILLIS = 967L
            const val STATUS_AREA_MOVE_DOWN_MILLIS = 467L
        }
    }
}
