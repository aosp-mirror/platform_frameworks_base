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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScrollModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/**
 * Defines the behavior of the [SceneTransitionLayout] when a scrollable component is scrolled.
 *
 * By default, scrollable elements within the scene have priority during the user's gesture and are
 * not consumed by the [SceneTransitionLayout] unless specifically requested via
 * [nestedScrollToScene].
 */
enum class NestedScrollBehavior(val canStartOnPostFling: Boolean) {
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
    EdgeAlways(canStartOnPostFling = true);

    companion object {
        val Default = EdgeNoPreview
    }
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

private class NestedScrollToSceneNode(
    private var layoutImpl: SceneTransitionLayoutImpl,
    private var orientation: Orientation,
    private var topOrLeftBehavior: NestedScrollBehavior,
    private var bottomOrRightBehavior: NestedScrollBehavior,
    private var isExternalOverscrollGesture: () -> Boolean,
) : DelegatingNode() {
    private var scrollBehaviorOwner: ScrollBehaviorOwner? = null

    private fun requireScrollBehaviorOwner(): ScrollBehaviorOwner {
        var behaviorOwner = scrollBehaviorOwner
        if (behaviorOwner == null) {
            behaviorOwner = requireScrollBehaviorOwner(layoutImpl.draggableHandler(orientation))
            scrollBehaviorOwner = behaviorOwner
        }
        return behaviorOwner
    }

    private val updateScrollBehaviorsConnection =
        object : NestedScrollConnection {
            /**
             * When using [NestedScrollConnection.onPostScroll], we can specify the desired behavior
             * before our parent components. This gives them the option to override our behavior if
             * they choose.
             *
             * The behavior can be communicated at every scroll gesture to ensure that the hierarchy
             * is respected, even if one of our descendant nodes changes behavior after we set it.
             */
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // If we have some remaining scroll, that scroll can be used to initiate a
                // transition between scenes. We can assume that the behavior is only needed if
                // there is some remaining amount.
                if (available != Offset.Zero) {
                    requireScrollBehaviorOwner()
                        .updateScrollBehaviors(
                            topOrLeftBehavior = topOrLeftBehavior,
                            bottomOrRightBehavior = bottomOrRightBehavior,
                            isExternalOverscrollGesture = isExternalOverscrollGesture,
                        )
                }

                return Offset.Zero
            }
        }

    init {
        delegate(nestedScrollModifierNode(updateScrollBehaviorsConnection, dispatcher = null))
    }

    override fun onDetach() {
        scrollBehaviorOwner = null
    }

    fun update(
        layoutImpl: SceneTransitionLayoutImpl,
        orientation: Orientation,
        topOrLeftBehavior: NestedScrollBehavior,
        bottomOrRightBehavior: NestedScrollBehavior,
        isExternalOverscrollGesture: () -> Boolean,
    ) {
        this.layoutImpl = layoutImpl
        this.orientation = orientation
        this.topOrLeftBehavior = topOrLeftBehavior
        this.bottomOrRightBehavior = bottomOrRightBehavior
        this.isExternalOverscrollGesture = isExternalOverscrollGesture
    }
}
