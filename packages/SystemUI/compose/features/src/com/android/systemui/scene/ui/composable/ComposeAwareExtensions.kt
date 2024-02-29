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

package com.android.systemui.scene.ui.composable

import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.Edge as ComposeAwareEdge
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction as ComposeAwareUserAction
import com.android.compose.animation.scene.UserActionResult as ComposeAwareUserActionResult
import com.android.systemui.scene.shared.model.Direction
import com.android.systemui.scene.shared.model.Edge
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.scene.shared.model.UserActionResult

// TODO(b/293899074): remove this file once we can use the types from SceneTransitionLayout.

fun UserAction.asComposeAware(): ComposeAwareUserAction {
    return when (this) {
        is UserAction.Swipe ->
            Swipe(
                pointerCount = pointerCount,
                fromSource =
                    when (this.fromEdge) {
                        null -> null
                        Edge.LEFT -> ComposeAwareEdge.Left
                        Edge.TOP -> ComposeAwareEdge.Top
                        Edge.RIGHT -> ComposeAwareEdge.Right
                        Edge.BOTTOM -> ComposeAwareEdge.Bottom
                    },
                direction =
                    when (this.direction) {
                        Direction.LEFT -> SwipeDirection.Left
                        Direction.UP -> SwipeDirection.Up
                        Direction.RIGHT -> SwipeDirection.Right
                        Direction.DOWN -> SwipeDirection.Down
                    }
            )
        is UserAction.Back -> Back
    }
}

fun UserActionResult.asComposeAware(): ComposeAwareUserActionResult {
    val composeUnaware = this
    return ComposeAwareUserActionResult(
        toScene = composeUnaware.toScene,
        transitionKey = composeUnaware.transitionKey,
    )
}
