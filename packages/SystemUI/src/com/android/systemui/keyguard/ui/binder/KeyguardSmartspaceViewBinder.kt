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

package com.android.systemui.keyguard.ui.binder

import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.keyguard.ui.view.layout.sections.SmartspaceSection
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.lifecycle.repeatWhenAttached

object KeyguardSmartspaceViewBinder {
    @JvmStatic
    fun bind(
        smartspaceSection: SmartspaceSection,
        keyguardRootView: ConstraintLayout,
        clockViewModel: KeyguardClockViewModel,
    ) {
        keyguardRootView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                clockViewModel.hasCustomWeatherDataDisplay.collect {
                    val constraintSet = ConstraintSet().apply { clone(keyguardRootView) }
                    smartspaceSection.applyConstraints(constraintSet)
                    constraintSet.applyTo(keyguardRootView)
                }
            }
        }
    }
}
