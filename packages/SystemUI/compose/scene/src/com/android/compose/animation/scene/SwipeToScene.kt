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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScrollModifierNode
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.unit.IntSize

/**
 * Configures the swipeable behavior of a [SceneTransitionLayout] depending on the current state.
 */
@Stable
internal fun Modifier.swipeToScene(
    draggableHandler: DraggableHandlerImpl,
    swipeDetector: SwipeDetector
): Modifier {
    return this.then(SwipeToSceneElement(draggableHandler, swipeDetector))
}

private data class SwipeToSceneElement(
    val draggableHandler: DraggableHandlerImpl,
    val swipeDetector: SwipeDetector
) : ModifierNodeElement<SwipeToSceneNode>() {
    override fun create(): SwipeToSceneNode = SwipeToSceneNode(draggableHandler, swipeDetector)

    override fun update(node: SwipeToSceneNode) {
        node.draggableHandler = draggableHandler
    }
}

private class SwipeToSceneNode(
    draggableHandler: DraggableHandlerImpl,
    swipeDetector: SwipeDetector,
) : DelegatingNode(), PointerInputModifierNode {
    private val multiPointerDraggableNode =
        delegate(
            MultiPointerDraggableNode(
                orientation = draggableHandler.orientation,
                enabled = ::enabled,
                startDragImmediately = ::startDragImmediately,
                onDragStarted = draggableHandler::onDragStarted,
                swipeDetector = swipeDetector,
            )
        )

    private var _draggableHandler = draggableHandler
    var draggableHandler: DraggableHandlerImpl
        get() = _draggableHandler
        set(value) {
            if (_draggableHandler != value) {
                _draggableHandler = value

                // Make sure to update the delegate orientation. Note that this will automatically
                // reset the underlying pointer input handler, so previous gestures will be
                // cancelled.
                multiPointerDraggableNode.orientation = value.orientation
            }
        }

    private val nestedScrollHandlerImpl =
        NestedScrollHandlerImpl(
            layoutImpl = draggableHandler.layoutImpl,
            orientation = draggableHandler.orientation,
            topOrLeftBehavior = NestedScrollBehavior.Default,
            bottomOrRightBehavior = NestedScrollBehavior.Default,
            isExternalOverscrollGesture = { false },
            pointersInfoOwner = { multiPointerDraggableNode.pointersInfo() },
        )

    init {
        delegate(nestedScrollModifierNode(nestedScrollHandlerImpl.connection, dispatcher = null))
        delegate(ScrollBehaviorOwnerNode(draggableHandler.nestedScrollKey, nestedScrollHandlerImpl))
    }

    override fun onDetach() {
        // Make sure we reset the scroll connection when this modifier is removed from composition
        nestedScrollHandlerImpl.connection.reset()
    }

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) = multiPointerDraggableNode.onPointerEvent(pointerEvent, pass, bounds)

    override fun onCancelPointerInput() = multiPointerDraggableNode.onCancelPointerInput()

    private fun enabled(): Boolean {
        return draggableHandler.isDrivingTransition ||
            currentScene().shouldEnableSwipes(multiPointerDraggableNode.orientation)
    }

    private fun currentScene(): Scene {
        val layoutImpl = draggableHandler.layoutImpl
        return layoutImpl.scene(layoutImpl.state.transitionState.currentScene)
    }

    /** Whether swipe should be enabled in the given [orientation]. */
    private fun Scene.shouldEnableSwipes(orientation: Orientation): Boolean {
        return userActions.keys.any {
            it is Swipe.Resolved && it.direction.orientation == orientation
        }
    }

    private fun startDragImmediately(startedPosition: Offset): Boolean {
        // Immediately start the drag if the user can't swipe in the other direction and the gesture
        // handler can intercept it.
        return !canOppositeSwipe() && draggableHandler.shouldImmediatelyIntercept(startedPosition)
    }

    private fun canOppositeSwipe(): Boolean {
        val oppositeOrientation =
            when (draggableHandler.orientation) {
                Orientation.Vertical -> Orientation.Horizontal
                Orientation.Horizontal -> Orientation.Vertical
            }
        return currentScene().shouldEnableSwipes(oppositeOrientation)
    }
}

/** Find the [ScrollBehaviorOwner] for the current orientation. */
internal fun DelegatableNode.requireScrollBehaviorOwner(
    draggableHandler: DraggableHandlerImpl
): ScrollBehaviorOwner {
    val ancestorNode =
        checkNotNull(findNearestAncestor(draggableHandler.nestedScrollKey)) {
            "This should never happen! Couldn't find a ScrollBehaviorOwner. " +
                "Are we inside an SceneTransitionLayout?"
        }
    return ancestorNode as ScrollBehaviorOwner
}

internal fun interface ScrollBehaviorOwner {
    fun updateScrollBehaviors(
        topOrLeftBehavior: NestedScrollBehavior,
        bottomOrRightBehavior: NestedScrollBehavior,
        isExternalOverscrollGesture: () -> Boolean,
    )
}

/**
 * We need a node that receives the desired behavior.
 *
 * TODO(b/353234530) move this logic into [SwipeToSceneNode]
 */
private class ScrollBehaviorOwnerNode(
    override val traverseKey: Any,
    val nestedScrollHandlerImpl: NestedScrollHandlerImpl
) : Modifier.Node(), TraversableNode, ScrollBehaviorOwner {
    override fun updateScrollBehaviors(
        topOrLeftBehavior: NestedScrollBehavior,
        bottomOrRightBehavior: NestedScrollBehavior,
        isExternalOverscrollGesture: () -> Boolean
    ) {
        nestedScrollHandlerImpl.topOrLeftBehavior = topOrLeftBehavior
        nestedScrollHandlerImpl.bottomOrRightBehavior = bottomOrRightBehavior
        nestedScrollHandlerImpl.isExternalOverscrollGesture = isExternalOverscrollGesture
    }
}
