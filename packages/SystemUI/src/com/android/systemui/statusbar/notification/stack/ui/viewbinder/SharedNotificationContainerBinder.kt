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

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.launch

/** Binds the shared notification container to its view-model. */
object SharedNotificationContainerBinder {

    @JvmStatic
    fun bind(
        view: SharedNotificationContainer,
        viewModel: SharedNotificationContainerViewModel,
        controller: NotificationStackScrollLayoutController,
        notificationStackSizeCalculator: NotificationStackSizeCalculator,
    ): DisposableHandle {
        val disposableHandle =
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    launch {
                        viewModel.configurationBasedDimensions.collect {
                            view.updateConstraints(
                                useSplitShade = it.useSplitShade,
                                marginStart = it.marginStart,
                                marginTop = it.marginTop,
                                marginEnd = it.marginEnd,
                                marginBottom = it.marginBottom,
                            )

                            controller.setOverExpansion(0f)
                            controller.setOverScrollAmount(0)
                            controller.updateFooter()
                        }
                    }

                    launch {
                        viewModel
                            .getMaxNotifications { space ->
                                notificationStackSizeCalculator.computeMaxKeyguardNotifications(
                                    controller.getView(),
                                    space,
                                    0f, // Vertical space for shelf is already accounted for
                                    controller.getShelfHeight().toFloat(),
                                )
                            }
                            .collect { controller.setMaxDisplayedNotifications(it) }
                    }

                    launch {
                        viewModel.position.collect {
                            val animate = it.animate || controller.isAddOrRemoveAnimationPending()
                            controller.updateTopPadding(it.top, animate)
                        }
                    }

                    launch { viewModel.translationY.collect { controller.setTranslationY(it) } }
                }
            }

        controller.setOnHeightChangedRunnable(Runnable { viewModel.notificationStackChanged() })

        return object : DisposableHandle {
            override fun dispose() {
                disposableHandle.dispose()
                controller.setOnHeightChangedRunnable(null)
            }
        }
    }
}
