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
import com.android.systemui.Flags.centralizedStatusBarHeightFix
import com.android.systemui.Flags.migrateClocksToBlueprint
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.shade.LargeScreenHeaderHelper
import com.android.systemui.shade.NotificationPanelView
import com.android.systemui.statusbar.notification.stack.AmbientState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationStackAppearanceViewModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher

/** Single column format for notifications (default for phones) */
class DefaultNotificationStackScrollLayoutSection
@Inject
constructor(
    context: Context,
    sceneContainerFlags: SceneContainerFlags,
    notificationPanelView: NotificationPanelView,
    sharedNotificationContainer: SharedNotificationContainer,
    sharedNotificationContainerViewModel: SharedNotificationContainerViewModel,
    notificationStackAppearanceViewModel: NotificationStackAppearanceViewModel,
    ambientState: AmbientState,
    controller: NotificationStackScrollLayoutController,
    notificationStackSizeCalculator: NotificationStackSizeCalculator,
    private val largeScreenHeaderHelperLazy: Lazy<LargeScreenHeaderHelper>,
    @Main mainDispatcher: CoroutineDispatcher,
) :
    NotificationStackScrollLayoutSection(
        context,
        sceneContainerFlags,
        notificationPanelView,
        sharedNotificationContainer,
        sharedNotificationContainerViewModel,
        notificationStackAppearanceViewModel,
        ambientState,
        controller,
        notificationStackSizeCalculator,
        mainDispatcher,
    ) {
    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!migrateClocksToBlueprint()) {
            return
        }
        constraintSet.apply {
            val bottomMargin =
                context.resources.getDimensionPixelSize(R.dimen.keyguard_status_view_bottom_margin)
            if (migrateClocksToBlueprint()) {
                val useLargeScreenHeader =
                    context.resources.getBoolean(R.bool.config_use_large_screen_shade_header)
                val marginTopLargeScreen =
                    if (centralizedStatusBarHeightFix()) {
                        largeScreenHeaderHelperLazy.get().getLargeScreenHeaderHeight()
                    } else {
                        context.resources.getDimensionPixelSize(
                            R.dimen.large_screen_shade_header_height
                        )
                    }
                connect(
                    R.id.nssl_placeholder,
                    TOP,
                    R.id.smart_space_barrier_bottom,
                    BOTTOM,
                    bottomMargin +
                        if (useLargeScreenHeader) {
                            marginTopLargeScreen
                        } else {
                            0
                        }
                )
            } else {
                connect(R.id.nssl_placeholder, TOP, R.id.keyguard_status_view, BOTTOM, bottomMargin)
            }
            connect(R.id.nssl_placeholder, START, PARENT_ID, START)
            connect(R.id.nssl_placeholder, END, PARENT_ID, END)

            addNotificationPlaceholderBarrier(this)
        }
    }
}
