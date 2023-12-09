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

import android.content.res.Resources
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.shared.model.KeyguardSection
import javax.inject.Inject

class DefaultSettingsPopupMenuSection @Inject constructor(@Main private val resources: Resources) :
    KeyguardSection {
    override fun apply(constraintSet: ConstraintSet) {
        val horizontalOffsetMargin =
            resources.getDimensionPixelSize(R.dimen.keyguard_affordance_horizontal_offset)

        constraintSet.apply {
            constrainWidth(R.id.keyguard_settings_button, WRAP_CONTENT)
            constrainHeight(R.id.keyguard_settings_button, WRAP_CONTENT)
            constrainMinHeight(
                R.id.keyguard_settings_button,
                resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_height)
            )
            connect(R.id.keyguard_settings_button, START, PARENT_ID, START, horizontalOffsetMargin)
            connect(R.id.keyguard_settings_button, END, PARENT_ID, END, horizontalOffsetMargin)
            connect(
                R.id.keyguard_settings_button,
                BOTTOM,
                PARENT_ID,
                BOTTOM,
                resources.getDimensionPixelSize(R.dimen.keyguard_affordance_vertical_offset)
            )
        }
    }
}
