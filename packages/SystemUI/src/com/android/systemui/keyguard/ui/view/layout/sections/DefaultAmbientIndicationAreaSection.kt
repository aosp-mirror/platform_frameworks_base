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

import android.view.LayoutInflater
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.Flags.keyguardBottomAreaRefactor
import com.android.systemui.res.R
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.binder.KeyguardAmbientIndicationAreaViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardAmbientIndicationViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import javax.inject.Inject

class DefaultAmbientIndicationAreaSection
@Inject
constructor(
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val keyguardAmbientIndicationViewModel: KeyguardAmbientIndicationViewModel,
    private val keyguardRootViewModel: KeyguardRootViewModel,
) : KeyguardSection() {
    private var ambientIndicationAreaHandle: KeyguardAmbientIndicationAreaViewBinder.Binding? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (keyguardBottomAreaRefactor()) {
            val view =
                LayoutInflater.from(constraintLayout.context)
                    .inflate(R.layout.ambient_indication, constraintLayout, false)

            constraintLayout.addView(view)
        }
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (keyguardBottomAreaRefactor()) {
            ambientIndicationAreaHandle =
                KeyguardAmbientIndicationAreaViewBinder.bind(
                    constraintLayout,
                    keyguardAmbientIndicationViewModel,
                    keyguardRootViewModel,
                )
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            constrainWidth(R.id.ambient_indication_container, MATCH_PARENT)

            if (keyguardUpdateMonitor.isUdfpsSupported) {
                // constrain below udfps and above indication area
                constrainHeight(R.id.ambient_indication_container, MATCH_CONSTRAINT)
                connect(R.id.ambient_indication_container, TOP, R.id.lock_icon_view, BOTTOM)
                connect(
                    R.id.ambient_indication_container,
                    BOTTOM,
                    R.id.keyguard_indication_area,
                    TOP
                )
                connect(R.id.ambient_indication_container, START, PARENT_ID, START)
                connect(R.id.ambient_indication_container, END, PARENT_ID, END)
            } else {
                // constrain above lock icon
                constrainHeight(R.id.ambient_indication_container, WRAP_CONTENT)
                connect(R.id.ambient_indication_container, BOTTOM, R.id.lock_icon_view, TOP)
                connect(R.id.ambient_indication_container, START, PARENT_ID, START)
                connect(R.id.ambient_indication_container, END, PARENT_ID, END)
            }
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        ambientIndicationAreaHandle?.destroy()

        constraintLayout.removeView(R.id.ambient_indication_container)
    }
}
