/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.notifications.ui.viewmodel

import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.shade.ui.viewmodel.OverlayShadeViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Models UI state and handles user input for the Notifications Shade scene. */
@SysUISingleton
class NotificationsShadeSceneViewModel
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    overlayShadeViewModel: OverlayShadeViewModel,
) {
    val destinationScenes: StateFlow<Map<UserAction, UserActionResult>> =
        overlayShadeViewModel.backgroundScene
            .map(::destinationScenes)
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = destinationScenes(overlayShadeViewModel.backgroundScene.value),
            )

    private fun destinationScenes(backgroundScene: SceneKey): Map<UserAction, UserActionResult> {
        return mapOf(
            Swipe.Up to backgroundScene,
            Back to backgroundScene,
        )
    }
}
