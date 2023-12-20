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
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.SplitShadeStateController
import javax.inject.Inject

class SplitShadeClockSection
@Inject
constructor(
    clockInteractor: KeyguardClockInteractor,
    keyguardClockViewModel: KeyguardClockViewModel,
    smartspaceViewModel: KeyguardSmartspaceViewModel,
    context: Context,
    splitShadeStateController: SplitShadeStateController,
) :
    ClockSection(
        clockInteractor,
        keyguardClockViewModel,
        smartspaceViewModel,
        context,
        splitShadeStateController
    ) {
    override fun applyDefaultConstraints(constraints: ConstraintSet) {
        super.applyDefaultConstraints(constraints)
        val largeClockEndGuideline =
            if (keyguardClockViewModel.clockShouldBeCentered.value) ConstraintSet.PARENT_ID
            else R.id.split_shade_guideline
        constraints.apply {
            connect(
                R.id.lockscreen_clock_view_large,
                ConstraintSet.END,
                largeClockEndGuideline,
                ConstraintSet.END
            )
        }
    }
}
