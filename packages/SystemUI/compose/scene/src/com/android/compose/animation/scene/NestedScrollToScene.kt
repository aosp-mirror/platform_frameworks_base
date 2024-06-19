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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScrollModifierNode
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastReduce
import com.android.compose.nestedscroll.PriorityNestedScrollConnection
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive

/**
 * Defines the behavior of the [SceneTransitionLayout] when a scrollable component is scrolled.
 *
 * By default, scrollable elements within the scene have priority during the user's gesture and are
 * not consumed by the [SceneTransitionLayout] unless specifically requested via
 * [nestedScrollToScene].
 */
enum class NestedScrollBehavior(val canStartOnPostFling: Boolean) {
    /**
     * During scene transitions, if we are within
     * [SceneTransitionLayoutImpl.transitionInterceptionThreshold], the [SceneTransitionLayout]
     * consumes scroll events instead of the scrollable component.
     */
    DuringTransitionBetweenScenes(canStartOnPostFling = false),

    /**
     * Overscroll will only be used by the [SceneTransitionLayout] to move to the next scene if the
     * gesture begins at the edge of the scrollable component (so that a scroll in that direction
     * can no longer be consumed). If the gesture is partially consumed by the scrollable component,
     * there will be NO preview of the next scene.
     *
     * In addition, during scene transitions, scroll events are consumed by the
     * [SceneTransitionLayout] instead of the scrollable component.
     */
    EdgeNoPreview(canStartOnPostFling = false),

    /**
     * Overscroll will only be used by the [SceneTransitionLayout] to move to the next scene if the
     * gesture begins at the edge of the scrollable component. If the gesture is partially consumed
     * by the scrollable component, there will be a preview of the next scene.
     *
     * In addition, during scene transitions, scroll events are consumed by the
     * [SceneTransitionLayout] instead of the scrollable component.
     */
    EdgeWithPreview(canStartOnPostFling = true),

    /**
     * Any overscroll will be used by the [SceneTransitionLayout] to move to the next scene.
     *
     * In addition, during scene transitions, scroll events are consumed by the
     * [SceneTransitionLayout] instead of the scrollable component.
     */
    EdgeAlways(canStartOnPostFling = true),
}

internal fun Modifier.nestedScrollToScene(
    layoutImpl: SceneTransitionLayoutImpl,
    orientation: Orientation,
    topOrLeftBehavior: NestedScrollBehavior,
    bottomOrRightBehavior: NestedScrollBehavior,
    isExternalOverscrollGesture: () -> Boolean,
) =
    this then
        NestedScrollToSceneElement(
            layoutImpl = layoutImpl,
            orientation = orientation,
            topOrLeftBehavior = topOrLeftBehavior,
            bottomOrRightBehavior = bottomOrRightBehavior,
            isExternalOverscrollGesture = isExternalOverscrollGesture,
        )

private data class NestedScrollToSceneElement(
    private val layoutImpl: SceneTransitionLayoutImpl,
    private val orientation: Orientation,
    private val topOrLeftBehavior: NestedScrollBehavior,
    private val bottomOrRightBehavior: NestedScrollBehavior,
    private val isExternalOverscrollGesture: () -> Boolean,
) : ModifierNodeElement<NestedScrollToSceneNode>() {
    override fun create() =
        NestedScrollToSceneNode(
            layoutImpl = layoutImpl,
            orientation = orientation,
            topOrLeftBehavior = topOrLeftBehavior,
            bottomOrRightBehavior = bottomOrRightBehavior,
            isExternalOverscrollGesture = isExternalOverscrollGesture,
        )

    override fun update(node: NestedScrollToSceneNode) {
        node.update(
            layoutImpl = layoutImpl,
            orientation = orientation,
            topOrLeftBehavior = topOrLeftBehavior,
            bottomOrRightBehavior = bottomOrRightBehavior,
            isExternalOverscrollGesture = isExternalOverscrollGesture,
        )
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "nestedScrollToScene"
        properties["layoutImpl"] = layoutImpl
        properties["orientation"] = orientation
        properties["topOrLeftBehavior"] = topOrLeftBehavior
        properties["bottomOrRightBehavior"] = bottomOrRightBehavior
    }
}

internal data class PointersInfo(
    val pointersDown: Int,
    val startedPosition: Offset,
)

private class NestedScrollToSceneNode(
    layoutImpl: SceneTransitionLayoutImpl,
    orientation: Orientation,
    topOrLeftBehavior: NestedScrollBehavior,
    bottomOrRightBehavior: NestedScrollBehavior,
    isExternalOverscrollGesture: () -> Boolean,
) : DelegatingNode() {
    private var priorityNestedScrollConnection: PriorityNestedScrollConnection =
        scenePriorityNestedScrollConnection(
            layoutImpl = layoutImpl,
            orientation = orientation,
            topOrLeftBehavior = topOrLeftBehavior,
            bottomOrRightBehavior = bottomOrRightBehavior,
            isExternalOverscrollGesture = isExternalOverscrollGesture,
            pointersInfo = pointerInfo()
        )

    private var lastPointers: List<PointerInputChange>? = null

    private fun pointerInfo(): () -> PointersInfo = {
        val pointers =
            requireNotNull(lastPointers) { "NestedScroll API was called before PointerInput API" }
        PointersInfo(
            pointersDown = pointers.size,
            startedPosition = pointers.fastMap { it.position }.fastReduce { a, b -> (a + b) / 2f },
        )
    }

    private val pointerInputHandler: suspend PointerInputScope.() -> Unit = {
        coroutineScope {
            awaitPointerEventScope {
                // Await this scope to guarantee that the PointerInput API receives touch events
                // before the NestedScroll API.
                delegate(nestedScrollNode)

                try {
                    while (isActive) {
                        // During the initial phase, we receive the event after our ancestors.
                        lastPointers = awaitPointerEvent(PointerEventPass.Initial).changes
                    }
                } finally {
                    // Clean up the nested scroll connection
                    priorityNestedScrollConnection.reset()
                    undelegate(nestedScrollNode)
                }
            }
        }
    }

    private val pointerInputNode = delegate(SuspendingPointerInputModifierNode(pointerInputHandler))

    private var nestedScrollNode: DelegatableNode =
        nestedScrollModifierNode(
            connection = priorityNestedScrollConnection,
            dispatcher = null,
        )

    fun update(
        layoutImpl: SceneTransitionLayoutImpl,
        orientation: Orientation,
        topOrLeftBehavior: NestedScrollBehavior,
        bottomOrRightBehavior: NestedScrollBehavior,
        isExternalOverscrollGesture: () -> Boolean,
    ) {
        // Clean up the old nested scroll connection
        priorityNestedScrollConnection.reset()
        pointerInputNode.resetPointerInputHandler()

        // Create a new nested scroll connection
        priorityNestedScrollConnection =
            scenePriorityNestedScrollConnection(
                layoutImpl = layoutImpl,
                orientation = orientation,
                topOrLeftBehavior = topOrLeftBehavior,
                bottomOrRightBehavior = bottomOrRightBehavior,
                isExternalOverscrollGesture = isExternalOverscrollGesture,
                pointersInfo = pointerInfo(),
            )
        nestedScrollNode =
            nestedScrollModifierNode(
                connection = priorityNestedScrollConnection,
                dispatcher = null,
            )
    }
}

private fun scenePriorityNestedScrollConnection(
    layoutImpl: SceneTransitionLayoutImpl,
    orientation: Orientation,
    topOrLeftBehavior: NestedScrollBehavior,
    bottomOrRightBehavior: NestedScrollBehavior,
    isExternalOverscrollGesture: () -> Boolean,
    pointersInfo: () -> PointersInfo,
) =
    NestedScrollHandlerImpl(
            layoutImpl = layoutImpl,
            orientation = orientation,
            topOrLeftBehavior = topOrLeftBehavior,
            bottomOrRightBehavior = bottomOrRightBehavior,
            isExternalOverscrollGesture = isExternalOverscrollGesture,
            pointersInfo = pointersInfo,
        )
        .connection
