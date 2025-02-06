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

import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.customization.R as customR
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Config
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBlueprintViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.KeyguardBlueprintLog
import com.android.systemui.plugins.clocks.ClockLogger.Companion.getVisText
import com.android.systemui.shared.R as sharedR
import com.android.systemui.util.kotlin.pairwise

object KeyguardBlueprintViewBinder {
    @JvmStatic
    fun bind(
        constraintLayout: ConstraintLayout,
        viewModel: KeyguardBlueprintViewModel,
        clockViewModel: KeyguardClockViewModel,
        smartspaceViewModel: KeyguardSmartspaceViewModel,
        @KeyguardBlueprintLog log: LogBuffer,
    ) {
        val logger = Logger(log, TAG)
        constraintLayout.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch("$TAG#viewModel.blueprint") {
                    viewModel.blueprint.pairwise(null as KeyguardBlueprint?).collect {
                        (prevBlueprint, blueprint) ->
                        val config = Config.DEFAULT
                        val transition =
                            IntraBlueprintTransition(
                                config,
                                clockViewModel,
                                smartspaceViewModel,
                                log,
                            )

                        viewModel.runTransition(constraintLayout, transition, config) {
                            // Replace sections from the previous blueprint with the new ones
                            blueprint.replaceViews(
                                constraintLayout,
                                prevBlueprint,
                                config.rebuildSections,
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

                            logger.logConstraintSet(cs, clockViewModel)
                            cs.applyTo(constraintLayout)
                        }
                    }
                }

                launch("$TAG#viewModel.refreshTransition") {
                    viewModel.refreshTransition.collect { config ->
                        val blueprint = viewModel.blueprint.value

                        viewModel.runTransition(
                            constraintLayout,
                            clockViewModel,
                            smartspaceViewModel,
                            config,
                        ) {
                            blueprint.rebuildViews(constraintLayout, config.rebuildSections)

                            val cs =
                                ConstraintSet().apply {
                                    clone(constraintLayout)
                                    blueprint.applyConstraints(this)
                                }
                            logger.logConstraintSet(cs, clockViewModel)
                            cs.applyTo(constraintLayout)
                        }
                    }
                }
            }
        }
    }

    private fun Logger.logConstraintSet(cs: ConstraintSet, viewModel: KeyguardClockViewModel) {
        val currentClock = viewModel.currentClock.value
        if (currentClock == null) return

        this.i({ "applyCsToSmallClock: vis=${getVisText(int1)}; alpha=$str1; scale=$str2" }) {
            val smallClockViewId = customR.id.lockscreen_clock_view
            int1 = cs.getVisibility(smallClockViewId)
            str1 = "${cs.getConstraint(smallClockViewId).propertySet.alpha}"
            str2 = "${cs.getConstraint(smallClockViewId).transform.scaleX}"
        }

        this.i({
            "applyCsToLargeClock: vis=${getVisText(int1)}; alpha=$str1; scale=$str2; pivotX=$str3"
        }) {
            val largeClockViewId = currentClock.largeClock.layout.views[0].id
            int1 = cs.getVisibility(largeClockViewId)
            str1 = "${cs.getConstraint(largeClockViewId).propertySet.alpha}"
            str2 = "${cs.getConstraint(largeClockViewId).transform.scaleX}"
            str3 = "${cs.getConstraint(largeClockViewId).transform.transformPivotX}"
        }

        this.i({ "applyCsToSmartspaceDate: vis=${getVisText(int1)}; alpha=$str1" }) {
            val smartspaceDateId = sharedR.id.date_smartspace_view
            int1 = cs.getVisibility(smartspaceDateId)
            str1 = "${cs.getConstraint(smartspaceDateId).propertySet.alpha}"
        }
    }

    private val TAG = "KeyguardBlueprintViewBinder"
}
