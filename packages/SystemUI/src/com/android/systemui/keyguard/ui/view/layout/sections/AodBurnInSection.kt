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

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import com.android.systemui.keyguard.MigrateClocksToBlueprint
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.res.R
import javax.inject.Inject

/** Adds a layer to group elements for translation for burn-in preventation */
class AodBurnInSection
@Inject
constructor(
    private val context: Context,
    private val rootView: KeyguardRootView,
    private val clockViewModel: KeyguardClockViewModel,
) : KeyguardSection() {
    private lateinit var burnInLayer: AodBurnInLayer
    // The burn-in layer requires at least 1 view at all times
    private val emptyView: View by lazy {
        View(context, null).apply {
            id = R.id.burn_in_layer_empty_view
            visibility = View.GONE
        }
    }
    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!MigrateClocksToBlueprint.isEnabled) {
            return
        }

        constraintLayout.addView(emptyView)
        burnInLayer =
            AodBurnInLayer(context).apply {
                id = R.id.burn_in_layer
                registerListener(rootView)
                addView(emptyView)
            }
        constraintLayout.addView(burnInLayer)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!MigrateClocksToBlueprint.isEnabled) {
            return
        }
        clockViewModel.burnInLayer = burnInLayer
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!MigrateClocksToBlueprint.isEnabled) {
            return
        }

        constraintSet.apply {
            // The empty view should not occupy any space
            constrainHeight(R.id.burn_in_layer_empty_view, 1)
            constrainWidth(R.id.burn_in_layer_empty_view, 0)
            connect(R.id.burn_in_layer_empty_view, BOTTOM, PARENT_ID, BOTTOM)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        burnInLayer.unregisterListener(rootView)
        constraintLayout.removeView(R.id.burn_in_layer)
    }
}
