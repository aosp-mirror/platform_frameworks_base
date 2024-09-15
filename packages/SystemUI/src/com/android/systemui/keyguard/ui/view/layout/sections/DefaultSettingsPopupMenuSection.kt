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
import android.view.LayoutInflater
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.VISIBILITY_MODE_IGNORE
import androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT
import androidx.core.view.isVisible
import com.android.systemui.animation.view.LaunchableLinearLayout
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.KeyguardBottomAreaRefactor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.binder.KeyguardSettingsViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardLongPressViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSettingsMenuViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

class DefaultSettingsPopupMenuSection
@Inject
constructor(
    @Main private val resources: Resources,
    private val keyguardSettingsMenuViewModel: KeyguardSettingsMenuViewModel,
    private val keyguardLongPressViewModel: KeyguardLongPressViewModel,
    private val keyguardRootViewModel: KeyguardRootViewModel,
    private val vibratorHelper: VibratorHelper,
    private val activityStarter: ActivityStarter,
) : KeyguardSection() {
    private var settingsPopupMenuHandle: DisposableHandle? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!KeyguardBottomAreaRefactor.isEnabled) {
            return
        }
        val view =
            LayoutInflater.from(constraintLayout.context)
                .inflate(R.layout.keyguard_settings_popup_menu, constraintLayout, false)
                .apply {
                    id = R.id.keyguard_settings_button
                    isVisible = false
                    alpha = 0f
                } as LaunchableLinearLayout
        constraintLayout.addView(view)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (KeyguardBottomAreaRefactor.isEnabled) {
            settingsPopupMenuHandle =
                KeyguardSettingsViewBinder.bind(
                    constraintLayout.requireViewById<View>(R.id.keyguard_settings_button),
                    keyguardSettingsMenuViewModel,
                    keyguardLongPressViewModel,
                    keyguardRootViewModel,
                    vibratorHelper,
                    activityStarter,
                )
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
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
            // Ignore ConstrainSet's default visibility, and let the view choose
            setVisibilityMode(R.id.keyguard_settings_button, VISIBILITY_MODE_IGNORE)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        settingsPopupMenuHandle?.dispose()
        constraintLayout.removeView(R.id.keyguard_settings_button)
    }
}
