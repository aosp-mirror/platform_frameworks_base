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

package com.android.systemui.scene.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.notifications.ui.composable.HeadsUpNotificationSpace
import com.android.systemui.notifications.ui.composable.Notifications
import com.android.systemui.scene.shared.model.Direction
import com.android.systemui.scene.shared.model.Edge
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Gone" is not a real scene but rather the absence of scenes when we want to skip showing any
 * content from the scene framework.
 */
@SysUISingleton
class GoneScene
@Inject
constructor(
    private val notificationsViewModel: NotificationsPlaceholderViewModel,
) : ComposableScene {
    override val key = SceneKey.Gone

    override val destinationScenes: StateFlow<Map<UserAction, SceneModel>> =
        MutableStateFlow<Map<UserAction, SceneModel>>(
                mapOf(
                    UserAction.Swipe(
                        pointerCount = 2,
                        fromEdge = Edge.TOP,
                        direction = Direction.DOWN,
                    ) to SceneModel(SceneKey.QuickSettings),
                    UserAction.Swipe(direction = Direction.DOWN) to SceneModel(SceneKey.Shade),
                )
            )
            .asStateFlow()

    @Composable
    override fun SceneScope.Content(
        modifier: Modifier,
    ) {
        Box(modifier = modifier) {
            Box(modifier = Modifier.fillMaxSize().element(Notifications.Elements.NotificationScrim))
            HeadsUpNotificationSpace(
                viewModel = notificationsViewModel,
                modifier = Modifier.padding(16.dp).fillMaxSize(),
            )
        }
    }
}
