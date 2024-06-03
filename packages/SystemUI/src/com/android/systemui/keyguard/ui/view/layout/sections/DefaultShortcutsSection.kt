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
import android.view.WindowInsets
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.LEFT
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.RIGHT
import androidx.constraintlayout.widget.ConstraintSet.VISIBILITY_MODE_IGNORE
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.KeyguardBottomAreaRefactor
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.ui.binder.KeyguardQuickAffordanceViewBinder
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordancesCombinedViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.VibratorHelper
import dagger.Lazy
import javax.inject.Inject

class DefaultShortcutsSection
@Inject
constructor(
    @Main private val resources: Resources,
    private val keyguardQuickAffordancesCombinedViewModel:
        KeyguardQuickAffordancesCombinedViewModel,
    private val keyguardRootViewModel: KeyguardRootViewModel,
    private val falsingManager: FalsingManager,
    private val indicationController: KeyguardIndicationController,
    private val vibratorHelper: VibratorHelper,
    private val keyguardBlueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
) : BaseShortcutSection() {

    // Amount to increase the bottom margin by to avoid colliding with inset
    private var safeInsetBottom = 0

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (KeyguardBottomAreaRefactor.isEnabled) {
            addLeftShortcut(constraintLayout)
            addRightShortcut(constraintLayout)

            constraintLayout
                .requireViewById<LaunchableImageView>(R.id.start_button)
                .setOnApplyWindowInsetsListener { _, windowInsets ->
                    val tempSafeInset = windowInsets?.displayCutout?.safeInsetBottom ?: 0
                    if (safeInsetBottom != tempSafeInset) {
                        safeInsetBottom = tempSafeInset
                        keyguardBlueprintInteractor
                            .get()
                            .refreshBlueprint(IntraBlueprintTransition.Type.DefaultTransition)
                    }
                    WindowInsets.CONSUMED
                }
        }
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (KeyguardBottomAreaRefactor.isEnabled) {
            leftShortcutHandle =
                KeyguardQuickAffordanceViewBinder.bind(
                    constraintLayout.requireViewById(R.id.start_button),
                    keyguardQuickAffordancesCombinedViewModel.startButton,
                    keyguardQuickAffordancesCombinedViewModel.transitionAlpha,
                    falsingManager,
                    vibratorHelper,
                ) {
                    indicationController.showTransientIndication(it)
                }
            rightShortcutHandle =
                KeyguardQuickAffordanceViewBinder.bind(
                    constraintLayout.requireViewById(R.id.end_button),
                    keyguardQuickAffordancesCombinedViewModel.endButton,
                    keyguardQuickAffordancesCombinedViewModel.transitionAlpha,
                    falsingManager,
                    vibratorHelper,
                ) {
                    indicationController.showTransientIndication(it)
                }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val width = resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_width)
        val height = resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_height)
        val horizontalOffsetMargin =
            resources.getDimensionPixelSize(R.dimen.keyguard_affordance_horizontal_offset)
        val verticalOffsetMargin =
            resources.getDimensionPixelSize(R.dimen.keyguard_affordance_vertical_offset)

        constraintSet.apply {
            constrainWidth(R.id.start_button, width)
            constrainHeight(R.id.start_button, height)
            connect(R.id.start_button, LEFT, PARENT_ID, LEFT, horizontalOffsetMargin)
            connect(
                R.id.start_button,
                BOTTOM,
                PARENT_ID,
                BOTTOM,
                verticalOffsetMargin + safeInsetBottom
            )

            constrainWidth(R.id.end_button, width)
            constrainHeight(R.id.end_button, height)
            connect(R.id.end_button, RIGHT, PARENT_ID, RIGHT, horizontalOffsetMargin)
            connect(
                R.id.end_button,
                BOTTOM,
                PARENT_ID,
                BOTTOM,
                verticalOffsetMargin + safeInsetBottom
            )

            // The constraint set visibility for start and end button are default visible, set to
            // ignore so the view's own initial visibility (invisible) is used
            setVisibilityMode(R.id.start_button, VISIBILITY_MODE_IGNORE)
            setVisibilityMode(R.id.end_button, VISIBILITY_MODE_IGNORE)
        }
    }
}
