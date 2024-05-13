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

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.SceneScope
import com.android.compose.modifiers.thenIf
import com.android.systemui.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.MigrateClocksToBlueprint
import com.android.systemui.keyguard.ui.composable.modifier.burnInAware
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.BurnInParameters
import com.android.systemui.keyguard.ui.viewmodel.LockscreenContentViewModel
import com.android.systemui.notifications.ui.composable.ConstrainedNotificationStack
import com.android.systemui.res.R
import com.android.systemui.shade.LargeScreenHeaderHelper
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.SharedNotificationContainerBinder
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import javax.inject.Inject

@SysUISingleton
class NotificationSection
@Inject
constructor(
    private val viewModel: NotificationsPlaceholderViewModel,
    private val aodBurnInViewModel: AodBurnInViewModel,
    sharedNotificationContainer: SharedNotificationContainer,
    sharedNotificationContainerViewModel: SharedNotificationContainerViewModel,
    stackScrollLayout: NotificationStackScrollLayout,
    sharedNotificationContainerBinder: SharedNotificationContainerBinder,
    private val lockscreenContentViewModel: LockscreenContentViewModel,
) {

    init {
        if (!MigrateClocksToBlueprint.isEnabled) {
            throw IllegalStateException("this requires MigrateClocksToBlueprint.isEnabled")
        }
        // This scene container section moves the NSSL to the SharedNotificationContainer.
        // This also requires that SharedNotificationContainer gets moved to the
        // SceneWindowRootView by the SceneWindowRootViewBinder. Prior to Scene Container,
        // but when the KeyguardShadeMigrationNssl flag is enabled, NSSL is moved into this
        // container by the NotificationStackScrollLayoutSection.
        // Ensure stackScrollLayout is a child of sharedNotificationContainer.

        if (stackScrollLayout.parent != sharedNotificationContainer) {
            (stackScrollLayout.parent as? ViewGroup)?.removeView(stackScrollLayout)
            sharedNotificationContainer.addNotificationStackScrollLayout(stackScrollLayout)
        }

        sharedNotificationContainerBinder.bind(
            sharedNotificationContainer,
            sharedNotificationContainerViewModel,
        )
    }

    /**
     * @param burnInParams params to make this view adaptive to burn-in, `null` to disable burn-in
     *   adjustment
     */
    @Composable
    fun SceneScope.Notifications(burnInParams: BurnInParameters?, modifier: Modifier = Modifier) {
        val shouldUseSplitNotificationShade by
            lockscreenContentViewModel.shouldUseSplitNotificationShade.collectAsStateWithLifecycle()
        val areNotificationsVisible by
            lockscreenContentViewModel.areNotificationsVisible.collectAsStateWithLifecycle()
        val splitShadeTopMargin: Dp =
            if (Flags.centralizedStatusBarHeightFix()) {
                LargeScreenHeaderHelper.getLargeScreenHeaderHeight(LocalContext.current).dp
            } else {
                dimensionResource(id = R.dimen.large_screen_shade_header_height)
            }

        if (!areNotificationsVisible) {
            return
        }

        ConstrainedNotificationStack(
            viewModel = viewModel,
            modifier =
                modifier
                    .fillMaxWidth()
                    .thenIf(shouldUseSplitNotificationShade) {
                        Modifier.padding(top = splitShadeTopMargin)
                    }
                    .let {
                        if (burnInParams == null) {
                            it
                        } else {
                            it.burnInAware(
                                viewModel = aodBurnInViewModel,
                                params = burnInParams,
                            )
                        }
                    },
        )
    }
}
