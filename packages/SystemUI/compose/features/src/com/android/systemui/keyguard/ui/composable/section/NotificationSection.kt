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

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.SharedNotificationContainerBinder
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher

class NotificationSection
@Inject
constructor(
    private val rootViewModel: KeyguardRootViewModel,
    private val sharedNotificationContainer: SharedNotificationContainer,
    private val sharedNotificationContainerViewModel: SharedNotificationContainerViewModel,
    private val controller: NotificationStackScrollLayoutController,
    private val notificationStackSizeCalculator: NotificationStackSizeCalculator,
    @Main private val dispatcher: CoroutineDispatcher,
    private val sceneContainerFlags: SceneContainerFlags,
) {
    @Composable
    fun SceneScope.Notifications(modifier: Modifier = Modifier) {
        MovableElement(
            key = NotificationsElementKey,
            modifier = modifier,
        ) {
            val (bounds, onBoundsChanged) = remember { mutableStateOf<Pair<Float, Float>?>(null) }
            LaunchedEffect(bounds) {
                bounds?.let {
                    rootViewModel.onNotificationContainerBoundsChanged(bounds.first, bounds.second)
                }
            }

            AndroidView(
                factory = { context ->
                    View(context, null).apply {
                        id = R.id.nssl_placeholder
                        SharedNotificationContainerBinder.bind(
                            view = sharedNotificationContainer,
                            viewModel = sharedNotificationContainerViewModel,
                            sceneContainerFlags = sceneContainerFlags,
                            controller = controller,
                            notificationStackSizeCalculator = notificationStackSizeCalculator,
                            mainImmediateDispatcher = dispatcher,
                        )
                    }
                },
                modifier =
                    modifier.onPlaced {
                        val positionInRoot = it.positionInWindow()
                        val size = it.size
                        onBoundsChanged(positionInRoot.y to positionInRoot.y + size.height)
                    },
            )
        }
    }
}

private val NotificationsElementKey = ElementKey("Notifications")
