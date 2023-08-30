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

import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.res.ResourcesCompat
import com.android.systemui.R
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.binder.KeyguardQuickAffordanceViewBinder

/** Base class for sections that add lockscreen shortcuts. */
abstract class BaseShortcutsSection : KeyguardSection {
    protected open var leftShortcutHandle: KeyguardQuickAffordanceViewBinder.Binding? = null
    protected open var rightShortcutHandle: KeyguardQuickAffordanceViewBinder.Binding? = null

    override fun addViews(constraintLayout: ConstraintLayout) {}

    override fun applyConstraints(constraintSet: ConstraintSet) {}

    override fun onDestroy() {
        leftShortcutHandle?.destroy()
        rightShortcutHandle?.destroy()
    }

    protected open fun addLeftShortcut(constraintLayout: ConstraintLayout) {
        if (constraintLayout.findViewById<View>(R.id.start_button) != null) return

        val padding =
            constraintLayout.resources.getDimensionPixelSize(
                R.dimen.keyguard_affordance_fixed_padding
            )
        val view =
            LaunchableImageView(constraintLayout.context, null).apply {
                id = R.id.start_button
                scaleType = ImageView.ScaleType.FIT_CENTER
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
                visibility = View.INVISIBLE
                setPadding(padding, padding, padding, padding)
            }
        constraintLayout.addView(view)
    }

    protected open fun addRightShortcut(constraintLayout: ConstraintLayout) {
        if (constraintLayout.findViewById<View>(R.id.end_button) != null) return

        val padding =
            constraintLayout.resources.getDimensionPixelSize(
                R.dimen.keyguard_affordance_fixed_padding
            )
        val view =
            LaunchableImageView(constraintLayout.context, null).apply {
                id = R.id.end_button
                scaleType = ImageView.ScaleType.FIT_CENTER
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
                visibility = View.INVISIBLE
                setPadding(padding, padding, padding, padding)
            }
        constraintLayout.addView(view)
    }
}
