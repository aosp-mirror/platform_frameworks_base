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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBlueprintViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.launch

/**
 * Binds the existing blueprint to the constraint layout that previews keyguard.
 *
 * This view binder should only inflate and add relevant views and apply the constraints. Actual
 * data binding should be done in {@link KeyguardPreviewRenderer}
 */
class PreviewKeyguardBlueprintViewBinder {
    companion object {

        /**
         * Binds the existing blueprint to the constraint layout that previews keyguard.
         *
         * @param constraintLayout The root view to bind to
         * @param viewModel The instance of the view model that contains flows we collect on.
         * @param finishedAddViewCallback Called when we have finished inflating the views.
         */
        fun bind(
            constraintLayout: ConstraintLayout,
            viewModel: KeyguardBlueprintViewModel,
            finishedAddViewCallback: () -> Unit
        ): DisposableHandle {
            return constraintLayout.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    launch {
                        viewModel.blueprint.collect { blueprint ->
                            val prevBluePrint = viewModel.currentBluePrint
                            Trace.beginSection("PreviewKeyguardBlueprint#applyBlueprint")

                            ConstraintSet().apply {
                                clone(constraintLayout)
                                val emptyLayout = ConstraintSet.Layout()
                                knownIds.forEach { getConstraint(it).layout.copyFrom(emptyLayout) }
                                blueprint.applyConstraints(this)
                                // Add and remove views of sections that are not contained by the
                                // other.
                                blueprint.replaceViews(
                                    prevBluePrint,
                                    constraintLayout,
                                    bindData = false
                                )
                                applyTo(constraintLayout)
                            }

                            viewModel.currentBluePrint = blueprint
                            finishedAddViewCallback.invoke()
                            Trace.endSection()
                        }
                    }
                }
            }
        }
    }
}
