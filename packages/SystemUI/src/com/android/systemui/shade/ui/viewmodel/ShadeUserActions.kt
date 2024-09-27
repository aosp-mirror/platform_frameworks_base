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

package com.android.systemui.shade.ui.viewmodel

import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.TransitionKeys.ToSplitShade
import com.android.systemui.scene.ui.viewmodel.SceneContainerEdge

/** Returns collection of [UserAction] to [UserActionResult] pairs for opening the single shade. */
fun singleShadeActions(
    requireTwoPointersForTopEdgeForQs: Boolean = false
): Array<Pair<UserAction, UserActionResult>> {
    return arrayOf(
        // Swiping down, not from the edge, always goes to shade.
        Swipe.Down to Scenes.Shade,
        swipeDown(pointerCount = 2) to Scenes.Shade,

        // Swiping down from the top edge.
        swipeDownFromTop(pointerCount = 1) to
            if (requireTwoPointersForTopEdgeForQs) {
                Scenes.Shade
            } else {
                Scenes.QuickSettings
            },
        swipeDownFromTop(pointerCount = 2) to Scenes.QuickSettings,
    )
}

/** Returns collection of [UserAction] to [UserActionResult] pairs for opening the split shade. */
fun splitShadeActions(): Array<Pair<UserAction, UserActionResult>> {
    val splitShadeSceneKey = UserActionResult(Scenes.Shade, ToSplitShade)
    return arrayOf(
        // Swiping down, not from the edge, always goes to shade.
        Swipe.Down to splitShadeSceneKey,
        swipeDown(pointerCount = 2) to splitShadeSceneKey,
        // Swiping down from the top edge goes to QS.
        swipeDownFromTop(pointerCount = 1) to splitShadeSceneKey,
        swipeDownFromTop(pointerCount = 2) to splitShadeSceneKey,
    )
}

/** Returns collection of [UserAction] to [UserActionResult] pairs for opening the dual shade. */
fun dualShadeActions(): Array<Pair<UserAction, UserActionResult>> {
    return arrayOf(
        Swipe.Down to Overlays.NotificationsShade,
        Swipe(direction = SwipeDirection.Down, fromSource = SceneContainerEdge.TopRight) to
            Overlays.QuickSettingsShade,
    )
}

private fun swipeDownFromTop(pointerCount: Int): Swipe {
    return Swipe(SwipeDirection.Down, fromSource = Edge.Top, pointerCount = pointerCount)
}

private fun swipeDown(pointerCount: Int): Swipe {
    return Swipe(SwipeDirection.Down, pointerCount = pointerCount)
}
