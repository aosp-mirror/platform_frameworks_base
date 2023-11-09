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

package com.android.compose.animation.scene

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.nestedscroll.nestedScroll

/**
 * Defines the behavior of the [SceneTransitionLayout] when a scrollable component is scrolled.
 *
 * By default, scrollable elements within the scene have priority during the user's gesture and are
 * not consumed by the [SceneTransitionLayout] unless specifically requested via
 * [nestedScrollToScene].
 */
enum class NestedScrollBehavior(val canStartOnPostFling: Boolean) {
    /**
     * During scene transitions, scroll events are consumed by the [SceneTransitionLayout] instead
     * of the scrollable component.
     */
    DuringTransitionBetweenScenes(canStartOnPostFling = false),

    /**
     * Overscroll will only be used by the [SceneTransitionLayout] to move to the next scene if the
     * gesture begins at the edge of the scrollable component (so that a scroll in that direction
     * can no longer be consumed). If the gesture is partially consumed by the scrollable component,
     * there will be NO overscroll effect between scenes.
     *
     * In addition, during scene transitions, scroll events are consumed by the
     * [SceneTransitionLayout] instead of the scrollable component.
     */
    EdgeNoOverscroll(canStartOnPostFling = false),

    /**
     * Overscroll will only be used by the [SceneTransitionLayout] to move to the next scene if the
     * gesture begins at the edge of the scrollable component. If the gesture is partially consumed
     * by the scrollable component, there will be an overscroll effect between scenes.
     *
     * In addition, during scene transitions, scroll events are consumed by the
     * [SceneTransitionLayout] instead of the scrollable component.
     */
    EdgeWithOverscroll(canStartOnPostFling = true),

    /**
     * Any overscroll will be used by the [SceneTransitionLayout] to move to the next scene.
     *
     * In addition, during scene transitions, scroll events are consumed by the
     * [SceneTransitionLayout] instead of the scrollable component.
     */
    Always(canStartOnPostFling = true),
}

internal fun Modifier.nestedScrollToScene(
    layoutImpl: SceneTransitionLayoutImpl,
    orientation: Orientation,
    startBehavior: NestedScrollBehavior,
    endBehavior: NestedScrollBehavior,
): Modifier = composed {
    val connection =
        remember(layoutImpl, orientation, startBehavior, endBehavior) {
            scenePriorityNestedScrollConnection(
                layoutImpl = layoutImpl,
                orientation = orientation,
                startBehavior = startBehavior,
                endBehavior = endBehavior
            )
        }

    // Make sure we reset the scroll connection when this modifier is removed from composition
    DisposableEffect(connection) { onDispose { connection.reset() } }

    nestedScroll(connection = connection)
}

private fun scenePriorityNestedScrollConnection(
    layoutImpl: SceneTransitionLayoutImpl,
    orientation: Orientation,
    startBehavior: NestedScrollBehavior,
    endBehavior: NestedScrollBehavior,
) =
    SceneNestedScrollHandler(
            gestureHandler = layoutImpl.gestureHandler(orientation = orientation),
            startBehavior = startBehavior,
            endBehavior = endBehavior,
        )
        .connection
