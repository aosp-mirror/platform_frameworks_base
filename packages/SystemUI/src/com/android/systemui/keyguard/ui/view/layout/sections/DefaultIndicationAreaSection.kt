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
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.R
import com.android.systemui.keyguard.shared.model.KeyguardSection
import javax.inject.Inject

class DefaultIndicationAreaSection @Inject constructor(private val context: Context) :
    KeyguardSection {
    private val indicationAreaViewId = R.id.keyguard_indication_area

    override fun apply(constraintSet: ConstraintSet) {
        constraintSet.apply {
            constrainWidth(indicationAreaViewId, ViewGroup.LayoutParams.MATCH_PARENT)
            constrainHeight(indicationAreaViewId, ViewGroup.LayoutParams.WRAP_CONTENT)
            connect(
                indicationAreaViewId,
                ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID,
                ConstraintSet.BOTTOM,
                context.resources.getDimensionPixelSize(R.dimen.keyguard_indication_margin_bottom)
            )
            connect(
                indicationAreaViewId,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START
            )
            connect(
                indicationAreaViewId,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END
            )
        }
    }
}
