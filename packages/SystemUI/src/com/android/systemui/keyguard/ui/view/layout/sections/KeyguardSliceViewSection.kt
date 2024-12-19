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

import android.os.Handler
import android.view.LayoutInflater
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.keyguard.KeyguardSliceView
import com.android.keyguard.KeyguardSliceViewController
import com.android.systemui.customization.R as customR
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.statusbar.policy.ConfigurationController
import javax.inject.Inject

class KeyguardSliceViewSection
@Inject
constructor(
    val smartspaceController: LockscreenSmartspaceController,
    val layoutInflater: LayoutInflater,
    @Main val handler: Handler,
    @Background val bgHandler: Handler,
    val activityStarter: ActivityStarter,
    val configurationController: ConfigurationController,
    val dumpManager: DumpManager,
    val displayTracker: DisplayTracker,
) : KeyguardSection() {
    private lateinit var sliceView: KeyguardSliceView

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (smartspaceController.isEnabled) return

        sliceView =
            layoutInflater.inflate(R.layout.keyguard_slice_view, null, false) as KeyguardSliceView
        constraintLayout.addView(sliceView)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (smartspaceController.isEnabled) return

        val controller =
            KeyguardSliceViewController(
                handler,
                bgHandler,
                sliceView,
                activityStarter,
                configurationController,
                dumpManager,
                displayTracker,
            )
        controller.init()
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (smartspaceController.isEnabled) return

        constraintSet.apply {
            connect(
                R.id.keyguard_slice_view,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
            )
            connect(
                R.id.keyguard_slice_view,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
            )
            constrainHeight(R.id.keyguard_slice_view, ConstraintSet.WRAP_CONTENT)

            connect(
                R.id.keyguard_slice_view,
                ConstraintSet.TOP,
                customR.id.lockscreen_clock_view,
                ConstraintSet.BOTTOM,
            )

            createBarrier(
                R.id.smart_space_barrier_bottom,
                Barrier.BOTTOM,
                0,
                *intArrayOf(R.id.keyguard_slice_view),
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        if (smartspaceController.isEnabled) return

        constraintLayout.removeView(R.id.keyguard_slice_view)
    }
}
