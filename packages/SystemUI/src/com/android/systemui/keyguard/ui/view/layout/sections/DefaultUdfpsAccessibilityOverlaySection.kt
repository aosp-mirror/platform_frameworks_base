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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.Flags
import com.android.systemui.deviceentry.ui.binder.UdfpsAccessibilityOverlayBinder
import com.android.systemui.deviceentry.ui.view.UdfpsAccessibilityOverlay
import com.android.systemui.deviceentry.ui.viewmodel.DeviceEntryUdfpsAccessibilityOverlayViewModel
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Positions the UDFPS accessibility overlay on the bottom half of the keyguard. */
@ExperimentalCoroutinesApi
class DefaultUdfpsAccessibilityOverlaySection
@Inject
constructor(
    private val context: Context,
    private val viewModel: DeviceEntryUdfpsAccessibilityOverlayViewModel,
) : KeyguardSection() {
    private val viewId = R.id.udfps_accessibility_overlay
    private var cachedConstraintLayout: ConstraintLayout? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        cachedConstraintLayout = constraintLayout
        constraintLayout.addView(UdfpsAccessibilityOverlay(context).apply { id = viewId })
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        UdfpsAccessibilityOverlayBinder.bind(
            constraintLayout.findViewById(viewId)!!,
            viewModel,
        )
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            connect(viewId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(viewId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)

            create(R.id.udfps_accessibility_overlay_top_guideline, ConstraintSet.HORIZONTAL)
            setGuidelinePercent(R.id.udfps_accessibility_overlay_top_guideline, .5f)
            connect(
                viewId,
                ConstraintSet.TOP,
                R.id.udfps_accessibility_overlay_top_guideline,
                ConstraintSet.BOTTOM,
            )

            if (Flags.keyguardBottomAreaRefactor()) {
                connect(
                    viewId,
                    ConstraintSet.BOTTOM,
                    R.id.keyguard_indication_area,
                    ConstraintSet.TOP,
                )
            } else {
                connect(viewId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            }
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        constraintLayout.removeView(viewId)
    }
}
