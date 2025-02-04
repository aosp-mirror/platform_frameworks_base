/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.compose.animation.scene

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import com.android.compose.animation.scene.content.Content
import com.android.compose.gesture.nestedDraggable

/**
 * Configures the swipeable behavior of a [SceneTransitionLayout] depending on the current state.
 */
@Stable
internal fun Modifier.swipeToScene(draggableHandler: DraggableHandler): Modifier {
    val contentForSwipes = draggableHandler.contentForSwipes()
    val enabled = draggableHandler.enabled(contentForSwipes)
    return this.nestedDraggable(
        draggable = draggableHandler,
        orientation = draggableHandler.orientation,
        overscrollEffect = draggableHandler.overscrollEffect,
        enabled = enabled,
        nestedDragsEnabled = enabled && contentForSwipes.areNestedSwipesAllowed(),
    )
}

internal fun DraggableHandler.enabled(contentForSwipes: Content = contentForSwipes()): Boolean {
    return isDrivingTransition || contentForSwipes.shouldEnableSwipes(orientation)
}

private fun DraggableHandler.contentForSwipes(): Content {
    return layoutImpl.contentForUserActions()
}

/** Whether swipe should be enabled in the given [orientation]. */
private fun Content.shouldEnableSwipes(orientation: Orientation): Boolean {
    if (userActions.isEmpty()) {
        return false
    }

    return userActions.keys.any { it is Swipe.Resolved && it.direction.orientation == orientation }
}
