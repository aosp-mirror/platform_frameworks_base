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

package com.android.systemui.keyguard.ui.composable.section

import android.content.Context
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.notifications.ui.composable.NotificationStack
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.statusbar.notification.stack.AmbientState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.shared.flexiNotifsEnabled
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.NotificationStackAppearanceViewBinder
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationStackAppearanceViewModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import javax.inject.Inject

class NotificationSection
@Inject
constructor(
    @Application context: Context,
    private val viewModel: NotificationsPlaceholderViewModel,
    controller: NotificationStackScrollLayoutController,
    sceneContainerFlags: SceneContainerFlags,
    sharedNotificationContainer: SharedNotificationContainer,
    stackScrollLayout: NotificationStackScrollLayout,
    notificationStackAppearanceViewModel: NotificationStackAppearanceViewModel,
    ambientState: AmbientState,
) {
    init {
        if (sceneContainerFlags.flexiNotifsEnabled()) {
            (stackScrollLayout.parent as? ViewGroup)?.removeView(stackScrollLayout)
            sharedNotificationContainer.addNotificationStackScrollLayout(stackScrollLayout)

            NotificationStackAppearanceViewBinder.bind(
                context,
                sharedNotificationContainer,
                notificationStackAppearanceViewModel,
                ambientState,
                controller,
            )
        }
    }

    @Composable
    fun SceneScope.Notifications(modifier: Modifier = Modifier) {
        NotificationStack(
            viewModel = viewModel,
            modifier = modifier,
        )
    }
}
