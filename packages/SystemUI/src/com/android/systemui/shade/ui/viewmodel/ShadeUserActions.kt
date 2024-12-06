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
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.TransitionKeys.ToSplitShade
import com.android.systemui.scene.ui.viewmodel.SceneContainerEdge

/** Returns collection of [UserAction] to [UserActionResult] pairs for opening the single shade. */
fun singleShadeActions(
    isDownFromTopEdgeEnabled: Boolean = true,
    requireTwoPointersForTopEdgeForQs: Boolean = false,
): Array<Pair<UserAction, UserActionResult>> {
    val shadeUserActionResult = UserActionResult(Scenes.Shade)
    val qsSceneUserActionResult = UserActionResult(Scenes.QuickSettings)
    return buildList {
            // Swiping down, not from the edge, always goes to shade.
            add(Swipe.Down to shadeUserActionResult)
            add(Swipe.Down(pointerCount = 2) to shadeUserActionResult)
            if (isDownFromTopEdgeEnabled) {
                add(
                    swipeDownFromTop(pointerCount = 1) to
                        if (requireTwoPointersForTopEdgeForQs) {
                            shadeUserActionResult
                        } else {
                            qsSceneUserActionResult
                        }
                )
                add(swipeDownFromTop(pointerCount = 2) to qsSceneUserActionResult)
            }
        }
        .toTypedArray()
}

/** Returns collection of [UserAction] to [UserActionResult] pairs for opening the split shade. */
fun splitShadeActions(): Array<Pair<UserAction, UserActionResult>> {
    val shadeUserActionResult = UserActionResult(Scenes.Shade, ToSplitShade)
    return arrayOf(
        // Swiping down, not from the edge, always goes to shade.
        Swipe.Down to shadeUserActionResult,
        Swipe.Down(pointerCount = 2) to shadeUserActionResult,
        // Swiping down from the top edge goes to QS.
        swipeDownFromTop(pointerCount = 1) to shadeUserActionResult,
        swipeDownFromTop(pointerCount = 2) to shadeUserActionResult,
    )
}

/** Returns collection of [UserAction] to [UserActionResult] pairs for opening the dual shade. */
fun dualShadeActions(): Array<Pair<UserAction, UserActionResult>> {
    val notifShadeUserActionResult = UserActionResult.ShowOverlay(Overlays.NotificationsShade)
    val qsShadeuserActionResult = UserActionResult.ShowOverlay(Overlays.QuickSettingsShade)
    return arrayOf(
        Swipe.Down to notifShadeUserActionResult,
        Swipe.Down(fromSource = SceneContainerEdge.TopRight) to qsShadeuserActionResult,
    )
}

private fun swipeDownFromTop(pointerCount: Int): Swipe {
    return Swipe.Down(fromSource = Edge.Top, pointerCount = pointerCount)
}
