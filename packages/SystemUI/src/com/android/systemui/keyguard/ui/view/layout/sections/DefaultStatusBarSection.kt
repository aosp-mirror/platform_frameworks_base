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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import com.android.keyguard.dagger.KeyguardStatusBarViewComponent
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.NotificationPanelView
import com.android.systemui.shade.ShadeViewStateProvider
import com.android.systemui.statusbar.phone.KeyguardStatusBarView
import com.android.systemui.util.Utils
import javax.inject.Inject

/** A section for the status bar displayed at the top of the lockscreen. */
class DefaultStatusBarSection
@Inject
constructor(
    private val context: Context,
    private val notificationPanelView: NotificationPanelView,
    private val keyguardStatusBarViewComponentFactory: KeyguardStatusBarViewComponent.Factory,
) : KeyguardSection() {

    private val statusBarViewId = R.id.keyguard_header

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!SceneContainerFlag.isEnabled) {
            return
        }

        notificationPanelView.findViewById<View>(statusBarViewId)?.let {
            (it.parent as ViewGroup).removeView(it)
        }

        val view =
            LayoutInflater.from(constraintLayout.context)
                .inflate(R.layout.keyguard_status_bar, constraintLayout, false)
                as KeyguardStatusBarView

        constraintLayout.addView(view)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!SceneContainerFlag.isEnabled) {
            return
        }

        val statusBarView =
            constraintLayout.findViewById<KeyguardStatusBarView>(statusBarViewId) ?: return

        val provider =
            object : ShadeViewStateProvider {
                override val lockscreenShadeDragProgress: Float = 0f
                override val panelViewExpandedHeight: Float = 0f
                override fun shouldHeadsUpBeVisible(): Boolean {
                    return false
                }
            }
        val statusBarViewComponent =
            keyguardStatusBarViewComponentFactory.build(statusBarView, provider)
        val controller = statusBarViewComponent.keyguardStatusBarViewController
        controller.init()
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            constrainHeight(statusBarViewId, Utils.getStatusBarHeaderHeightKeyguard(context))
            connect(statusBarViewId, TOP, PARENT_ID, TOP)
            connect(statusBarViewId, START, PARENT_ID, START)
            connect(statusBarViewId, END, PARENT_ID, END)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        constraintLayout.removeView(statusBarViewId)
    }
}
