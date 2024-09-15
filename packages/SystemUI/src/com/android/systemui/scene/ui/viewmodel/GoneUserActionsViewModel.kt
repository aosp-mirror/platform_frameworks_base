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

package com.android.systemui.scene.ui.viewmodel

import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.TransitionKeys.ToSplitShade
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class GoneUserActionsViewModel
@AssistedInject
constructor(private val shadeInteractor: ShadeInteractor) : UserActionsViewModel() {

    override suspend fun hydrateActions(setActions: (Map<UserAction, UserActionResult>) -> Unit) {
        shadeInteractor.shadeMode.collect { shadeMode ->
            setActions(
                when (shadeMode) {
                    ShadeMode.Single -> singleShadeActions()
                    ShadeMode.Split -> splitShadeActions()
                    ShadeMode.Dual -> dualShadeActions()
                }
            )
        }
    }

    private fun singleShadeActions(): Map<UserAction, UserActionResult> {
        return mapOf(
            Swipe.Down to Scenes.Shade,
            swipeDownFromTopWithTwoFingers() to Scenes.QuickSettings,
        )
    }

    private fun splitShadeActions(): Map<UserAction, UserActionResult> {
        return mapOf(
            Swipe.Down to UserActionResult(Scenes.Shade, ToSplitShade),
            swipeDownFromTopWithTwoFingers() to UserActionResult(Scenes.Shade, ToSplitShade),
        )
    }

    private fun dualShadeActions(): Map<UserAction, UserActionResult> {
        return mapOf(
            Swipe.Down to Overlays.NotificationsShade,
            Swipe(direction = SwipeDirection.Down, fromSource = SceneContainerEdge.TopRight) to
                Overlays.QuickSettingsShade,
        )
    }

    private fun swipeDownFromTopWithTwoFingers(): UserAction {
        return Swipe(direction = SwipeDirection.Down, pointerCount = 2, fromSource = Edge.Top)
    }

    @AssistedFactory
    interface Factory {
        fun create(): GoneUserActionsViewModel
    }
}
