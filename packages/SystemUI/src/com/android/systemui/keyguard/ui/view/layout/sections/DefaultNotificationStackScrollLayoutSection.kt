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

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import com.android.systemui.res.R
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.shade.NotificationPanelView
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.SharedNotificationContainerBinder
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import javax.inject.Inject

class DefaultNotificationStackScrollLayoutSection
@Inject
constructor(
    private val context: Context,
    private val featureFlags: FeatureFlags,
    private val notificationPanelView: NotificationPanelView,
    private val sharedNotificationContainer: SharedNotificationContainer,
    private val sharedNotificationContainerViewModel: SharedNotificationContainerViewModel,
    private val controller: NotificationStackScrollLayoutController,
) : KeyguardSection() {
    private val placeHolderId = R.id.nssl_placeholder

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!featureFlags.isEnabled(Flags.MIGRATE_NSSL)) {
            return
        }
        // This moves the existing NSSL view to a different parent, as the controller is a
        // singleton and recreating it has other bad side effects
        notificationPanelView.findViewById<View?>(R.id.notification_stack_scroller)?.let {
            (it.parent as ViewGroup).removeView(it)
            sharedNotificationContainer.addNotificationStackScrollLayout(it)
        }

        val view = View(context, null).apply { id = placeHolderId }
        constraintLayout.addView(view)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!featureFlags.isEnabled(Flags.MIGRATE_NSSL)) {
            return
        }
        SharedNotificationContainerBinder.bind(
            sharedNotificationContainer,
            sharedNotificationContainerViewModel,
            controller,
        )
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!featureFlags.isEnabled(Flags.MIGRATE_NSSL)) {
            return
        }
        constraintSet.apply {
            val bottomMargin =
                context.resources.getDimensionPixelSize(R.dimen.keyguard_status_view_bottom_margin)
            val useSplitShade =
                context.resources.getBoolean(R.bool.config_use_split_notification_shade)

            val topAlignment =
                if (useSplitShade) {
                    TOP
                } else {
                    BOTTOM
                }
            connect(
                R.id.nssl_placeholder,
                TOP,
                R.id.keyguard_status_view,
                topAlignment,
                bottomMargin
            )
            connect(R.id.nssl_placeholder, START, PARENT_ID, START)
            connect(R.id.nssl_placeholder, END, PARENT_ID, END)
            connect(R.id.nssl_placeholder, BOTTOM, R.id.lock_icon_view, TOP)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        constraintLayout.removeView(placeHolderId)
    }
}
