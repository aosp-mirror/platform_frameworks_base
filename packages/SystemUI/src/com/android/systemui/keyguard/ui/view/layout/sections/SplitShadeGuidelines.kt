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
import com.android.systemui.keyguard.data.repository.KeyguardSection
import javax.inject.Inject
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.VERTICAL

class SplitShadeGuidelines @Inject constructor(private val context: Context) :
    KeyguardSection {

    override fun apply(constraintSet: ConstraintSet) {
        constraintSet.apply {
            // For use on large screens, it will provide a guideline vertically in the center to
            // enable items to be aligned on the left or right sides
            create(R.id.split_shade_guideline, VERTICAL)
            setGuidelinePercent(R.id.split_shade_guideline, 0.5f)
        }
    }
}
