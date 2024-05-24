/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.Flags.migrateClocksToBlueprint
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.res.R
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import javax.inject.Inject

class KeyguardSliceViewSection
@Inject
constructor(
    val smartspaceController: LockscreenSmartspaceController,
) : KeyguardSection() {
    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!migrateClocksToBlueprint()) return
        if (smartspaceController.isEnabled()) return

        constraintLayout.findViewById<View?>(R.id.keyguard_slice_view)?.let {
            (it.parent as ViewGroup).removeView(it)
            constraintLayout.addView(it)
        }
    }

    override fun bindData(constraintLayout: ConstraintLayout) {}

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!migrateClocksToBlueprint()) return
        if (smartspaceController.isEnabled()) return

        constraintSet.apply {
            connect(
                R.id.keyguard_slice_view,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START
            )
            connect(
                R.id.keyguard_slice_view,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END
            )
            constrainHeight(R.id.keyguard_slice_view, ConstraintSet.WRAP_CONTENT)

            connect(
                R.id.keyguard_slice_view,
                ConstraintSet.TOP,
                R.id.lockscreen_clock_view,
                ConstraintSet.BOTTOM
            )

            createBarrier(
                R.id.smart_space_barrier_bottom,
                Barrier.BOTTOM,
                0,
                *intArrayOf(R.id.keyguard_slice_view)
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        if (!migrateClocksToBlueprint()) return
        if (smartspaceController.isEnabled()) return

        constraintLayout.removeView(R.id.keyguard_slice_view)
    }
}
