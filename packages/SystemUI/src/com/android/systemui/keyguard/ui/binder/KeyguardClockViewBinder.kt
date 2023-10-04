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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.ui.view.layout.items.ClockSection
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.ClockController
import com.android.systemui.res.R
import kotlinx.coroutines.launch

private val TAG = KeyguardClockViewBinder::class.simpleName

object KeyguardClockViewBinder {
    @JvmStatic
    fun bind(
        clockSection: ClockSection,
        keyguardRootView: ConstraintLayout,
        viewModel: KeyguardClockViewModel,
        keyguardBlueprintInteractor: KeyguardBlueprintInteractor,
        keyguardClockInteractor: KeyguardClockInteractor,
        featureFlags: FeatureFlagsClassic,
    ) {
        keyguardRootView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                keyguardClockInteractor.eventController.registerListeners(keyguardRootView)
            }
        }
        keyguardRootView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    if (!featureFlags.isEnabled(Flags.MIGRATE_CLOCKS_TO_BLUEPRINT)) return@launch
                    viewModel.currentClock.collect { currentClock ->
                        viewModel.clock?.let { clock -> cleanupClockViews(clock, keyguardRootView) }
                        viewModel.clock = currentClock
                        addClockViews(currentClock, keyguardRootView)
                        keyguardBlueprintInteractor.refreshBlueprint()
                    }
                }
                // TODO: Weather clock dozing animation
                // will trigger both shouldBeCentered and clockSize change
                // we should avoid this
                launch {
                    if (!featureFlags.isEnabled(Flags.MIGRATE_CLOCKS_TO_BLUEPRINT)) return@launch
                    viewModel.clockSize.collect {
                        applyConstraints(clockSection, keyguardRootView, true)
                    }
                }
                launch {
                    if (!featureFlags.isEnabled(Flags.MIGRATE_CLOCKS_TO_BLUEPRINT)) return@launch
                    viewModel.clockShouldBeCentered.collect { shouldBeCentered ->
                        clockSection.setClockShouldBeCentered(
                            viewModel.useLargeClock && shouldBeCentered
                        )
                        applyConstraints(clockSection, keyguardRootView, true)
                    }
                }
            }
        }
    }

    fun applyConstraints(
        clockSection: ClockSection,
        rootView: ConstraintLayout,
        animated: Boolean
    ) {
        val constraintSet = ConstraintSet().apply { clone(rootView) }
        clockSection.applyConstraints(constraintSet)
        if (animated) {
            TransitionManager.beginDelayedTransition(rootView)
        }

        constraintSet.applyTo(rootView)
    }

    private fun cleanupClockViews(clock: ClockController, rootView: ConstraintLayout) {
        clock.smallClock.layout.views.forEach { rootView.removeView(it) }
        clock.largeClock.layout.views.forEach { rootView.removeView(it) }
    }

    private fun addClockViews(clock: ClockController, rootView: ConstraintLayout) {
        clock.smallClock.layout.views[0].id = R.id.lockscreen_clock_view
        if (clock.largeClock.layout.views.size == 1) {
            clock.largeClock.layout.views[0].id = R.id.lockscreen_clock_view_large
        }
        // small clock should either be a single view or container with id `lockscreen_clock_view`
        clock.smallClock.layout.views.forEach { rootView.addView(it) }
        clock.largeClock.layout.views.forEach { rootView.addView(it) }
    }
}
