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
 *
 */

package com.android.systemui.keyguard.ui.binder

import android.util.Log
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launch
import com.android.systemui.keyguard.KeyguardBottomAreaRefactor
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.BaseBlueprintTransition
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Config
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBlueprintViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import com.android.systemui.util.kotlin.pairwise

object KeyguardBlueprintViewBinder {
    @JvmStatic
    fun bind(
        constraintLayout: ConstraintLayout,
        viewModel: KeyguardBlueprintViewModel,
        clockViewModel: KeyguardClockViewModel,
        smartspaceViewModel: KeyguardSmartspaceViewModel,
    ) {
        constraintLayout.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch("$TAG#viewModel.blueprint") {
                    viewModel.blueprint
                        .pairwise(
                            null as KeyguardBlueprint?,
                        )
                        .collect { (prevBlueprint, blueprint) ->
                            val config = Config.DEFAULT
                            val transition =
                                if (
                                    !KeyguardBottomAreaRefactor.isEnabled &&
                                        prevBlueprint != null &&
                                        prevBlueprint != blueprint
                                ) {
                                    BaseBlueprintTransition(clockViewModel)
                                        .addTransition(
                                            IntraBlueprintTransition(
                                                config,
                                                clockViewModel,
                                                smartspaceViewModel
                                            )
                                        )
                                } else {
                                    IntraBlueprintTransition(
                                        config,
                                        clockViewModel,
                                        smartspaceViewModel
                                    )
                                }

                            viewModel.runTransition(constraintLayout, transition, config) {
                                // Replace sections from the previous blueprint with the new ones
                                blueprint.replaceViews(
                                    constraintLayout,
                                    prevBlueprint,
                                    config.rebuildSections
                                )

                                val cs =
                                    ConstraintSet().apply {
                                        clone(constraintLayout)
                                        val emptyLayout = ConstraintSet.Layout()
                                        knownIds.forEach {
                                            getConstraint(it).layout.copyFrom(emptyLayout)
                                        }
                                        blueprint.applyConstraints(this)
                                    }

                                logAlphaVisibilityOfAppliedConstraintSet(cs, clockViewModel)
                                cs.applyTo(constraintLayout)
                            }
                        }
                }

                launch("$TAG#viewModel.refreshTransition") {
                    viewModel.refreshTransition.collect { config ->
                        val blueprint = viewModel.blueprint.value

                        viewModel.runTransition(
                            constraintLayout,
                            IntraBlueprintTransition(config, clockViewModel, smartspaceViewModel),
                            config,
                        ) {
                            blueprint.rebuildViews(constraintLayout, config.rebuildSections)

                            val cs =
                                ConstraintSet().apply {
                                    clone(constraintLayout)
                                    blueprint.applyConstraints(this)
                                }
                            logAlphaVisibilityOfAppliedConstraintSet(cs, clockViewModel)
                            cs.applyTo(constraintLayout)
                        }
                    }
                }
            }
        }
    }

    private fun logAlphaVisibilityOfAppliedConstraintSet(
        cs: ConstraintSet,
        viewModel: KeyguardClockViewModel
    ) {
        val currentClock = viewModel.currentClock.value
        if (!DEBUG || currentClock == null) return
        val smallClockViewId = R.id.lockscreen_clock_view
        val largeClockViewId = currentClock.largeClock.layout.views[0].id
        val smartspaceDateId = sharedR.id.date_smartspace_view
        Log.i(
            TAG,
            "applyCsToSmallClock: vis=${cs.getVisibility(smallClockViewId)} " +
                "alpha=${cs.getConstraint(smallClockViewId).propertySet.alpha}"
        )
        Log.i(
            TAG,
            "applyCsToLargeClock: vis=${cs.getVisibility(largeClockViewId)} " +
                "alpha=${cs.getConstraint(largeClockViewId).propertySet.alpha}"
        )
        Log.i(
            TAG,
            "applyCsToSmartspaceDate: vis=${cs.getVisibility(smartspaceDateId)} " +
                "alpha=${cs.getConstraint(smartspaceDateId).propertySet.alpha}"
        )
    }

    private const val TAG = "KeyguardBlueprintViewBinder"
    private const val DEBUG = false
}
