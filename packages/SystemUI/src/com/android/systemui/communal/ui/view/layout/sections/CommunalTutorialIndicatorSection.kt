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

package com.android.systemui.communal.ui.view.layout.sections

import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.Typeface.NORMAL
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.res.ResourcesCompat
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.ui.binder.CommunalTutorialIndicatorViewBinder
import com.android.systemui.communal.ui.viewmodel.CommunalTutorialIndicatorViewModel
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.layout.sections.removeView
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

class CommunalTutorialIndicatorSection
@Inject
constructor(
    @Main private val resources: Resources,
    private val communalTutorialIndicatorViewModel: CommunalTutorialIndicatorViewModel,
    private val communalInteractor: CommunalInteractor,
) : KeyguardSection() {
    private var communalTutorialIndicatorHandle: DisposableHandle? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!communalInteractor.isCommunalEnabled) {
            return
        }
        val padding =
            constraintLayout.resources.getDimensionPixelSize(
                R.dimen.communal_tutorial_indicator_padding
            )
        val view =
            TextView(constraintLayout.context).apply {
                id = R.id.communal_tutorial_indicator
                visibility = View.GONE
                background =
                    ResourcesCompat.getDrawable(
                        context.resources,
                        R.drawable.keyguard_bottom_affordance_bg,
                        context.theme
                    )
                foreground =
                    ResourcesCompat.getDrawable(
                        context.resources,
                        R.drawable.keyguard_bottom_affordance_selected_border,
                        context.theme
                    )
                gravity = Gravity.CENTER_VERTICAL
                typeface = Typeface.create("google-sans", NORMAL)
                text = constraintLayout.context.getString(R.string.communal_tutorial_indicator_text)
                setPadding(padding, padding, padding, padding)
            }
        constraintLayout.addView(view)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!communalInteractor.isCommunalEnabled) {
            return
        }
        communalTutorialIndicatorHandle =
            CommunalTutorialIndicatorViewBinder.bind(
                constraintLayout.requireViewById(R.id.communal_tutorial_indicator),
                communalTutorialIndicatorViewModel,
            )
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!communalInteractor.isCommunalEnabled) {
            return
        }
        val tutorialIndicatorId = R.id.communal_tutorial_indicator
        val width = resources.getDimensionPixelSize(R.dimen.communal_tutorial_indicator_fixed_width)
        val horizontalOffsetMargin =
            resources.getDimensionPixelSize(R.dimen.communal_tutorial_indicator_horizontal_offset)

        constraintSet.apply {
            constrainWidth(tutorialIndicatorId, width)
            constrainHeight(tutorialIndicatorId, WRAP_CONTENT)
            connect(
                tutorialIndicatorId,
                ConstraintSet.RIGHT,
                ConstraintSet.PARENT_ID,
                ConstraintSet.RIGHT,
                horizontalOffsetMargin
            )
            connect(
                tutorialIndicatorId,
                ConstraintSet.TOP,
                ConstraintSet.PARENT_ID,
                ConstraintSet.TOP
            )
            connect(
                tutorialIndicatorId,
                ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID,
                ConstraintSet.BOTTOM
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        communalTutorialIndicatorHandle?.dispose()
        constraintLayout.removeView(R.id.communal_tutorial_indicator)
    }
}
