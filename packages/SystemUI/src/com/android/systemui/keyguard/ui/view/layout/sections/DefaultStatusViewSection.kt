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
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import com.android.systemui.R
import com.android.systemui.keyguard.data.repository.KeyguardSection
import com.android.systemui.util.LargeScreenUtils
import com.android.systemui.util.Utils
import javax.inject.Inject

class DefaultStatusViewSection @Inject constructor(private val context: Context) : KeyguardSection {
    private val statusViewId = R.id.keyguard_status_view

    override fun apply(constraintSet: ConstraintSet) {
        constraintSet.apply {
            constrainWidth(statusViewId, MATCH_CONSTRAINT)
            constrainHeight(statusViewId, WRAP_CONTENT)
            connect(statusViewId, TOP, PARENT_ID, TOP)
            connect(statusViewId, START, PARENT_ID, START)
            connect(statusViewId, END, PARENT_ID, END)

            val margin =
                if (LargeScreenUtils.shouldUseSplitNotificationShade(context.resources)) {
                    context.resources.getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin)
                } else {
                    context.resources.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin) +
                        Utils.getStatusBarHeaderHeightKeyguard(context)
                }
            setMargin(statusViewId, TOP, margin)
        }
    }
}
