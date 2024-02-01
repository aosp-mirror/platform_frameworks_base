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

import android.transition.TransitionManager
import android.transition.TransitionSet
import android.util.Log
import android.view.View.INVISIBLE
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.helper.widget.Layer
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.keyguard.KeyguardClockSwitch.LARGE
import com.android.keyguard.KeyguardClockSwitch.SMALL
import com.android.systemui.Flags.migrateClocksToBlueprint
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransitionType
import com.android.systemui.keyguard.ui.view.layout.sections.ClockSection
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.res.R
import com.android.systemui.shared.clocks.DEFAULT_CLOCK_ID
import kotlinx.coroutines.launch

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
                        applyConstraints(clockSection, keyguardRootView, true)
                    }
                }
                launch {
                    if (!migrateClocksToBlueprint()) return@launch
                    viewModel.clockSize.collect {
                        updateBurnInLayer(keyguardRootView, viewModel)
                        blueprintInteractor.refreshBlueprintWithTransition(
                            IntraBlueprintTransitionType.ClockSize
                        )
                    }
                }
                launch {
                    if (!migrateClocksToBlueprint()) return@launch
                    viewModel.clockShouldBeCentered.collect { clockShouldBeCentered ->
                        Log.d(
                            "ClockViewBinder",
                            "Sherry clockShouldBeCentered $clockShouldBeCentered"
                        )
                        viewModel.clock?.let {
                            // Weather clock also has hasCustomPositionUpdatedAnimation as true
                            // TODO(b/323020908): remove ID check
                            if (
                                it.largeClock.config.hasCustomPositionUpdatedAnimation &&
                                    it.config.id == DEFAULT_CLOCK_ID
                            ) {
                                blueprintInteractor.refreshBlueprintWithTransition(
                                    IntraBlueprintTransitionType.DefaultClockStepping
                                )
                            } else {
                                blueprintInteractor.refreshBlueprintWithTransition(
                                    IntraBlueprintTransitionType.DefaultTransition
                                )
                            }
                        }
                    }
                }
                launch {
                    if (!migrateClocksToBlueprint()) return@launch
                    viewModel.isAodIconsVisible.collect { isAodIconsVisible ->
                        viewModel.clock?.let {
                            // Weather clock also has hasCustomPositionUpdatedAnimation as true
                            if (
                                viewModel.useLargeClock && it.config.id == "DIGITAL_CLOCK_WEATHER"
                            ) {
                                blueprintInteractor.refreshBlueprintWithTransition(
                                    IntraBlueprintTransitionType.DefaultTransition
                                )
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
            clock.smallClock.layout.views.forEach {
                rootView.addView(it).apply { it.visibility = INVISIBLE }
            }
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
}
