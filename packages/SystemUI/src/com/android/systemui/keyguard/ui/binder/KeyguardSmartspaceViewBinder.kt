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

package com.android.systemui.keyguard.ui.binder

import android.transition.TransitionManager
import android.view.View
import androidx.constraintlayout.helper.widget.Layer
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.Flags.migrateClocksToBlueprint
import com.android.systemui.keyguard.ui.view.layout.sections.SmartspaceSection
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import kotlinx.coroutines.launch

object KeyguardSmartspaceViewBinder {
    @JvmStatic
    fun bind(
        smartspaceSection: SmartspaceSection,
        keyguardRootView: ConstraintLayout,
        clockViewModel: KeyguardClockViewModel,
        smartspaceViewModel: KeyguardSmartspaceViewModel,
    ) {
        keyguardRootView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    if (!migrateClocksToBlueprint()) return@launch
                    clockViewModel.hasCustomWeatherDataDisplay.collect { hasCustomWeatherDataDisplay
                        ->
                        if (hasCustomWeatherDataDisplay) {
                            removeDateWeatherToBurnInLayer(keyguardRootView, smartspaceViewModel)
                        } else {
                            addDateWeatherToBurnInLayer(keyguardRootView, smartspaceViewModel)
                        }
                        clockViewModel.burnInLayer?.updatePostLayout(keyguardRootView)
                        val constraintSet = ConstraintSet().apply { clone(keyguardRootView) }
                        smartspaceSection.applyConstraints(constraintSet)
                        TransitionManager.beginDelayedTransition(keyguardRootView)
                        constraintSet.applyTo(keyguardRootView)
                    }
                }
            }
        }
    }

    private fun addDateWeatherToBurnInLayer(
        constraintLayout: ConstraintLayout,
        smartspaceViewModel: KeyguardSmartspaceViewModel
    ) {
        val burnInLayer = constraintLayout.requireViewById<Layer>(R.id.burn_in_layer)
        burnInLayer.apply {
            if (
                smartspaceViewModel.isSmartspaceEnabled &&
                    smartspaceViewModel.isDateWeatherDecoupled
            ) {
                val dateView =
                    constraintLayout.requireViewById<View>(sharedR.id.date_smartspace_view)
                val weatherView =
                    constraintLayout.requireViewById<View>(sharedR.id.weather_smartspace_view)
                addView(weatherView)
                addView(dateView)
            }
        }
    }

    private fun removeDateWeatherToBurnInLayer(
        constraintLayout: ConstraintLayout,
        smartspaceViewModel: KeyguardSmartspaceViewModel
    ) {
        val burnInLayer = constraintLayout.requireViewById<Layer>(R.id.burn_in_layer)
        burnInLayer.apply {
            if (
                smartspaceViewModel.isSmartspaceEnabled &&
                    smartspaceViewModel.isDateWeatherDecoupled
            ) {
                val dateView =
                    constraintLayout.requireViewById<View>(sharedR.id.date_smartspace_view)
                val weatherView =
                    constraintLayout.requireViewById<View>(sharedR.id.weather_smartspace_view)
                removeView(weatherView)
                removeView(dateView)
            }
        }
    }
}
