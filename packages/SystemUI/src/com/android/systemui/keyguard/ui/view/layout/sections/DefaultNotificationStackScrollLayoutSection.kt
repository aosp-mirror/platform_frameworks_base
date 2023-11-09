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
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.shared.KeyguardShadeMigrationNssl
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.res.R
import com.android.systemui.shade.NotificationPanelView
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import javax.inject.Inject

/** Single column format for notifications (default for phones) */
class DefaultNotificationStackScrollLayoutSection
@Inject
constructor(
    context: Context,
    private val featureFlags: FeatureFlags,
    notificationPanelView: NotificationPanelView,
    sharedNotificationContainer: SharedNotificationContainer,
    sharedNotificationContainerViewModel: SharedNotificationContainerViewModel,
    controller: NotificationStackScrollLayoutController,
    notificationStackSizeCalculator: NotificationStackSizeCalculator,
    private val smartspaceViewModel: KeyguardSmartspaceViewModel,
) :
    NotificationStackScrollLayoutSection(
        context,
        notificationPanelView,
        sharedNotificationContainer,
        sharedNotificationContainerViewModel,
        controller,
        notificationStackSizeCalculator,
    ) {
    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!KeyguardShadeMigrationNssl.isEnabled) {
            return
        }
        constraintSet.apply {
            val bottomMargin =
                context.resources.getDimensionPixelSize(R.dimen.keyguard_status_view_bottom_margin)

            if (featureFlags.isEnabled(Flags.MIGRATE_CLOCKS_TO_BLUEPRINT)) {
                connect(
                    R.id.nssl_placeholder,
                    TOP,
                    smartspaceViewModel.smartspaceViewId,
                    BOTTOM,
                    bottomMargin
                )
                setGoneMargin(R.id.nssl_placeholder, TOP, bottomMargin)
            } else {
                connect(R.id.nssl_placeholder, TOP, R.id.keyguard_status_view, BOTTOM, bottomMargin)
            }
            connect(R.id.nssl_placeholder, START, PARENT_ID, START)
            connect(R.id.nssl_placeholder, END, PARENT_ID, END)

            val lockId =
                if (featureFlags.isEnabled(Flags.REFACTOR_UDFPS_KEYGUARD_VIEWS)) {
                    R.id.device_entry_icon_view
                } else {
                    R.id.lock_icon_view
                }
            connect(R.id.nssl_placeholder, BOTTOM, lockId, TOP)
        }
    }
}
