/*
 * Copyright 2023 The Android Open Source Project
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

import androidx.annotation.IdRes
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.R
import com.android.systemui.keyguard.shared.model.KeyguardSection
import javax.inject.Inject

/** Positions the long-press handling view in the keyguard. */
class DefaultLongPressHandlingSection @Inject constructor() : KeyguardSection {
    override fun apply(constraintSet: ConstraintSet) {
        constraintSet.fillMaxSize(R.id.keyguard_long_press)
    }

    private fun ConstraintSet.fillMaxSize(@IdRes viewId: Int) {
        listOf(
                ConstraintSet.START,
                ConstraintSet.TOP,
                ConstraintSet.END,
                ConstraintSet.BOTTOM,
            )
            .forEach { side ->
                connect(
                    viewId,
                    side,
                    ConstraintSet.PARENT_ID,
                    side,
                )
            }
    }
}
