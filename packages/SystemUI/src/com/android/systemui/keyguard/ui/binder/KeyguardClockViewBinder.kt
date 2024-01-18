/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.keyguard.ui.binder

import android.animation.Animator
import android.animation.ValueAnimator
import android.transition.Transition
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.transition.TransitionValues
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.helper.widget.Layer
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.animation.Interpolators
import com.android.keyguard.KeyguardClockSwitch.LARGE
import com.android.keyguard.KeyguardClockSwitch.SMALL
import com.android.systemui.Flags.migrateClocksToBlueprint
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.ui.view.layout.sections.ClockSection
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.res.R
import kotlinx.coroutines.launch

private const val KEYGUARD_STATUS_VIEW_CUSTOM_CLOCK_MOVE_DURATION_MS = 1000L

object KeyguardClockViewBinder {
    @JvmStatic
    fun bind(
        clockSection: ClockSection,
        keyguardRootView: ConstraintLayout,
        viewModel: KeyguardClockViewModel,
        keyguardClockInteractor: KeyguardClockInteractor,
        blueprintInteractor: KeyguardBlueprintInteractor,
    ) {
        keyguardRootView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                keyguardClockInteractor.clockEventController.registerListeners(keyguardRootView)
            }
        }
        keyguardRootView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    if (!migrateClocksToBlueprint()) return@launch
                    viewModel.currentClock.collect { currentClock ->
                        cleanupClockViews(viewModel.clock, keyguardRootView, viewModel.burnInLayer)
                        viewModel.clock = currentClock
                        addClockViews(currentClock, keyguardRootView)
                        updateBurnInLayer(keyguardRootView, viewModel)
                        blueprintInteractor.refreshBlueprint()
                    }
                }
                launch {
                    if (!migrateClocksToBlueprint()) return@launch
                    viewModel.clockSize.collect {
                        updateBurnInLayer(keyguardRootView, viewModel)
                        blueprintInteractor.refreshBlueprint()
                    }
                }
                launch {
                    if (!migrateClocksToBlueprint()) return@launch
                    viewModel.clockShouldBeCentered.collect {
                        viewModel.clock?.let {
                            if (it.largeClock.config.hasCustomPositionUpdatedAnimation) {
                                playClockCenteringAnimation(clockSection, keyguardRootView, it)
                            } else {
                                blueprintInteractor.refreshBlueprint()
                            }
                        }
                    }
                }
            }
        }
    }
    @VisibleForTesting
    fun updateBurnInLayer(
        keyguardRootView: ConstraintLayout,
        viewModel: KeyguardClockViewModel,
    ) {
        val burnInLayer = viewModel.burnInLayer
        val clockController = viewModel.currentClock.value
        clockController?.let { clock ->
            when (viewModel.clockSize.value) {
                LARGE -> {
                    clock.smallClock.layout.views.forEach { burnInLayer?.removeView(it) }
                    if (clock.config.useAlternateSmartspaceAODTransition) {
                        clock.largeClock.layout.views.forEach { burnInLayer?.addView(it) }
                    }
                }
                SMALL -> {
                    clock.smallClock.layout.views.forEach { burnInLayer?.addView(it) }
                    clock.largeClock.layout.views.forEach { burnInLayer?.removeView(it) }
                }
            }
        }
        viewModel.burnInLayer?.updatePostLayout(keyguardRootView)
    }

    private fun cleanupClockViews(
        clockController: ClockController?,
        rootView: ConstraintLayout,
        burnInLayer: Layer?
    ) {
        clockController?.let { clock ->
            clock.smallClock.layout.views.forEach {
                burnInLayer?.removeView(it)
                rootView.removeView(it)
            }
            // add large clock to burn in layer only when it will have same transition with other
            // components in AOD
            // otherwise, it will have a separate scale transition while other components only have
            // translate transition
            if (clock.config.useAlternateSmartspaceAODTransition) {
                clock.largeClock.layout.views.forEach { burnInLayer?.removeView(it) }
            }
            clock.largeClock.layout.views.forEach { rootView.removeView(it) }
        }
    }

    @VisibleForTesting
    fun addClockViews(
        clockController: ClockController?,
        rootView: ConstraintLayout,
    ) {
        clockController?.let { clock ->
            clock.smallClock.layout.views[0].id = R.id.lockscreen_clock_view
            if (clock.largeClock.layout.views.size == 1) {
                clock.largeClock.layout.views[0].id = R.id.lockscreen_clock_view_large
            }
            // small clock should either be a single view or container with id
            // `lockscreen_clock_view`
            clock.smallClock.layout.views.forEach { rootView.addView(it) }
            clock.largeClock.layout.views.forEach { rootView.addView(it) }
        }
    }
    fun applyConstraints(
        clockSection: ClockSection,
        rootView: ConstraintLayout,
        animated: Boolean,
        set: TransitionSet? = null,
    ) {
        val constraintSet = ConstraintSet().apply { clone(rootView) }
        clockSection.applyConstraints(constraintSet)
        if (animated) {
            set?.let { TransitionManager.beginDelayedTransition(rootView, it) }
                ?: run { TransitionManager.beginDelayedTransition(rootView) }
        }
        constraintSet.applyTo(rootView)
    }

    private fun playClockCenteringAnimation(
        clockSection: ClockSection,
        keyguardRootView: ConstraintLayout,
        clock: ClockController,
    ) {
        // Find the clock, so we can exclude it from this transition.
        val clockView = clock.largeClock.view
        val set = TransitionSet()
        val adapter = SplitShadeTransitionAdapter(clock)
        adapter.setInterpolator(Interpolators.LINEAR)
        adapter.setDuration(KEYGUARD_STATUS_VIEW_CUSTOM_CLOCK_MOVE_DURATION_MS)
        adapter.addTarget(clockView)
        set.addTransition(adapter)
        applyConstraints(clockSection, keyguardRootView, true, set)
    }

    internal class SplitShadeTransitionAdapter
    @VisibleForTesting
    constructor(private val clock: ClockController) : Transition() {
        private fun captureValues(transitionValues: TransitionValues) {
            transitionValues.values[PROP_BOUNDS_LEFT] = transitionValues.view.left
            val locationInWindowTmp = IntArray(2)
            transitionValues.view.getLocationInWindow(locationInWindowTmp)
            transitionValues.values[PROP_X_IN_WINDOW] = locationInWindowTmp[0]
        }

        override fun captureEndValues(transitionValues: TransitionValues) {
            captureValues(transitionValues)
        }

        override fun captureStartValues(transitionValues: TransitionValues) {
            captureValues(transitionValues)
        }

        override fun createAnimator(
            sceneRoot: ViewGroup,
            startValues: TransitionValues?,
            endValues: TransitionValues?
        ): Animator? {
            if (startValues == null || endValues == null) {
                return null
            }
            val anim = ValueAnimator.ofFloat(0f, 1f)
            val fromLeft = startValues.values[PROP_BOUNDS_LEFT] as Int
            val fromWindowX = startValues.values[PROP_X_IN_WINDOW] as Int
            val toWindowX = endValues.values[PROP_X_IN_WINDOW] as Int
            // Using windowX, to determine direction, instead of left, as in RTL the difference of
            // toLeft - fromLeft is always positive, even when moving left.
            val direction = if (toWindowX - fromWindowX > 0) 1 else -1
            anim.addUpdateListener { animation: ValueAnimator ->
                clock.largeClock.animations.onPositionUpdated(
                    fromLeft,
                    direction,
                    animation.animatedFraction
                )
            }
            return anim
        }

        override fun getTransitionProperties(): Array<String> {
            return TRANSITION_PROPERTIES
        }

        companion object {
            private const val PROP_BOUNDS_LEFT = "splitShadeTransitionAdapter:boundsLeft"
            private const val PROP_X_IN_WINDOW = "splitShadeTransitionAdapter:xInWindow"
            private val TRANSITION_PROPERTIES = arrayOf(PROP_BOUNDS_LEFT, PROP_X_IN_WINDOW)
        }
    }
}
