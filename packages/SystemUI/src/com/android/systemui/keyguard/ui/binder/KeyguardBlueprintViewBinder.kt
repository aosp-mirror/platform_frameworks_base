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

import android.os.Trace
import android.util.Log
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBlueprintViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.launch

class KeyguardBlueprintViewBinder {
    companion object {
        private const val TAG = "KeyguardBlueprintViewBinder"

        fun bind(constraintLayout: ConstraintLayout, viewModel: KeyguardBlueprintViewModel) {
            constraintLayout.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    launch {
                        viewModel.blueprint.collect { blueprint ->
                            Trace.beginSection("KeyguardBlueprint#applyBlueprint")
                            Log.d(TAG, "applying blueprint: $blueprint")
                            if (blueprint != viewModel.currentBluePrint) {
                                viewModel.currentBluePrint?.onDestroy()
                            }
                            val constraintSet =
                                ConstraintSet().apply {
                                    clone(constraintLayout)
                                    val emptyLayout = ConstraintSet.Layout()
                                    knownIds.forEach {
                                        getConstraint(it).layout.copyFrom(emptyLayout)
                                    }
                                    blueprint.addViews(constraintLayout)
                                    blueprint.applyConstraints(this)
                                    applyTo(constraintLayout)
                                }
                            // Remove all unconstrained views.
                            constraintLayout.children
                                .filterNot { constraintSet.knownIds.contains(it.id) }
                                .forEach { constraintLayout.removeView(it) }

                            viewModel.currentBluePrint = blueprint
                            Trace.endSection()
                        }
                    }
                }
            }
        }
    }
}
