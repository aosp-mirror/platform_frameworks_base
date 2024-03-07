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
package com.android.systemui.statusbar.notification.stack.ui.viewbinder

import androidx.core.view.doOnDetach
import androidx.lifecycle.lifecycleScope
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationListViewModel
import kotlinx.coroutines.launch

/**
 * Binds a [NotificationStackScrollLayoutController] to its [view model][NotificationListViewModel].
 */
object HideNotificationsBinder {
    fun bindHideList(
        viewController: NotificationStackScrollLayoutController,
        viewModel: NotificationListViewModel
    ) {
        viewController.view.repeatWhenAttached {
            lifecycleScope.launch {
                viewModel.hideListViewModel.shouldHideListForPerformance.collect { shouldHide ->
                    viewController.bindHideState(shouldHide)
                }
            }
        }

        viewController.view.doOnDetach { viewController.bindHideState(shouldHide = false) }
    }

    private fun NotificationStackScrollLayoutController.bindHideState(shouldHide: Boolean) {
        if (shouldHide) {
            updateNotificationsContainerVisibility(/* visible= */ false, /* animate=*/ false)
            setSuppressChildrenMeasureAndLayout(true)
        } else {
            setSuppressChildrenMeasureAndLayout(false)

            // Show notifications back only after layout has finished because we need
            // to wait until they have resized to the new display size
            addOneShotPreDrawListener {
                updateNotificationsContainerVisibility(/* visible= */ true, /* animate=*/ true)
            }
        }
    }
}
