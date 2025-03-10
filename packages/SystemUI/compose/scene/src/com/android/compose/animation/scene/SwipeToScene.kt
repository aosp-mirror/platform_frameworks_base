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
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
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
import com.android.compose.animation.scene.content.Content

/**
 * Configures the swipeable behavior of a [SceneTransitionLayout] depending on the current state.
 */
@Stable
internal fun Modifier.swipeToScene(
    draggableHandler: DraggableHandlerImpl,
    swipeDetector: SwipeDetector,
): Modifier {
    return if (draggableHandler.enabled()) {
        this.then(SwipeToSceneElement(draggableHandler, swipeDetector))
    } else {
        this
    }
}

private fun DraggableHandlerImpl.enabled(): Boolean {
    return isDrivingTransition || contentForSwipes().shouldEnableSwipes(orientation)
}

private fun DraggableHandlerImpl.contentForSwipes(): Content {
    return layoutImpl.contentForUserActions()
}

/** Whether swipe should be enabled in the given [orientation]. */
internal fun Content.shouldEnableSwipes(orientation: Orientation): Boolean {
    if (userActions.isEmpty()) {
        return false
    }

    return userActions.keys.any { it is Swipe.Resolved && it.direction.orientation == orientation }
}

/**
 * Finds the best matching [UserActionResult] for the given [swipe] within this [Content].
 * Prioritizes actions with matching [Swipe.Resolved.fromSource].
 *
 * @param swipe The swipe to match against.
 * @return The best matching [UserActionResult], or `null` if no match is found.
 */
internal fun Content.findActionResultBestMatch(swipe: Swipe.Resolved): UserActionResult? {
    var bestPoints = Int.MIN_VALUE
    var bestMatch: UserActionResult? = null
    userActions.forEach { (actionSwipe, actionResult) ->
        if (
            actionSwipe !is Swipe.Resolved ||
                // The direction must match.
                actionSwipe.direction != swipe.direction ||
                // The number of pointers down must match.
                actionSwipe.pointerCount != swipe.pointerCount ||
                // The action requires a specific fromSource.
                (actionSwipe.fromSource != null && actionSwipe.fromSource != swipe.fromSource) ||
                // The action requires a specific pointerType.
                (actionSwipe.pointersType != null && actionSwipe.pointersType != swipe.pointersType)
        ) {
            // This action is not eligible.
            return@forEach
        }

        val sameFromSource = actionSwipe.fromSource == swipe.fromSource
        val samePointerType = actionSwipe.pointersType == swipe.pointersType
        // Prioritize actions with a perfect match.
        if (sameFromSource && samePointerType) {
            return actionResult
        }

        var points = 0
        if (sameFromSource) points++
        if (samePointerType) points++

        // Otherwise, keep track of the best eligible action.
        if (points > bestPoints) {
            bestPoints = points
            bestMatch = actionResult
        }
    }
    return bestMatch
}

private data class SwipeToSceneElement(
    val draggableHandler: DraggableHandlerImpl,
    val swipeDetector: SwipeDetector,
) : ModifierNodeElement<SwipeToSceneRootNode>() {
    override fun create(): SwipeToSceneRootNode =
        SwipeToSceneRootNode(draggableHandler, swipeDetector)

    override fun update(node: SwipeToSceneRootNode) {
        node.update(draggableHandler, swipeDetector)
    }
}

private class SwipeToSceneRootNode(
    draggableHandler: DraggableHandlerImpl,
    swipeDetector: SwipeDetector,
) : DelegatingNode() {
    private var delegateNode = delegate(SwipeToSceneNode(draggableHandler, swipeDetector))

    fun update(draggableHandler: DraggableHandlerImpl, swipeDetector: SwipeDetector) {
        if (draggableHandler == delegateNode.draggableHandler) {
            // Simple update, just update the swipe detector directly and keep the node.
            delegateNode.swipeDetector = swipeDetector
        } else {
            // The draggableHandler changed, force recreate the underlying SwipeToSceneNode.
            undelegate(delegateNode)
            delegateNode = delegate(SwipeToSceneNode(draggableHandler, swipeDetector))
        }
    }
}

private class SwipeToSceneNode(
    val draggableHandler: DraggableHandlerImpl,
    swipeDetector: SwipeDetector,
) : DelegatingNode(), PointerInputModifierNode {
    private val dispatcher = NestedScrollDispatcher()
    private val multiPointerDraggableNode =
        delegate(
            MultiPointerDraggableNode(
                orientation = draggableHandler.orientation,
                onDragStarted = draggableHandler::onDragStarted,
                onFirstPointerDown = ::onFirstPointerDown,
                swipeDetector = swipeDetector,
                dispatcher = dispatcher,
            )
        )

    var swipeDetector: SwipeDetector
        get() = multiPointerDraggableNode.swipeDetector
        set(value) {
            multiPointerDraggableNode.swipeDetector = value
        }

    private val nestedScrollHandlerImpl =
        NestedScrollHandlerImpl(
            draggableHandler = draggableHandler,
            topOrLeftBehavior = NestedScrollBehavior.Default,
            bottomOrRightBehavior = NestedScrollBehavior.Default,
            isExternalOverscrollGesture = { false },
            pointersInfoOwner = { multiPointerDraggableNode.pointersInfo() },
        )

    init {
        delegate(nestedScrollModifierNode(nestedScrollHandlerImpl.connection, dispatcher))
        delegate(ScrollBehaviorOwnerNode(draggableHandler.nestedScrollKey, nestedScrollHandlerImpl))
    }

    private fun onFirstPointerDown() {
        // When we drag our finger across the screen, the NestedScrollConnection keeps track of all
        // the scroll events until we lift our finger. However, in some cases, the connection might
        // not receive the "up" event. This can lead to an incorrect initial state for the gesture.
        // To prevent this issue, we can call the reset() method when the first finger touches the
        // screen. This ensures that the NestedScrollConnection starts from a correct state.
        nestedScrollHandlerImpl.connection.reset()
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
}

/** Find the [ScrollBehaviorOwner] for the current orientation. */
internal fun DelegatableNode.findScrollBehaviorOwner(
    draggableHandler: DraggableHandlerImpl
): ScrollBehaviorOwner? {
    // If there are no scenes in a particular orientation, the corresponding ScrollBehaviorOwnerNode
    // is removed from the composition.
    return findNearestAncestor(draggableHandler.nestedScrollKey) as? ScrollBehaviorOwner
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
    val nestedScrollHandlerImpl: NestedScrollHandlerImpl,
) : Modifier.Node(), TraversableNode, ScrollBehaviorOwner {
    override fun updateScrollBehaviors(
        topOrLeftBehavior: NestedScrollBehavior,
        bottomOrRightBehavior: NestedScrollBehavior,
        isExternalOverscrollGesture: () -> Boolean,
    ) {
        nestedScrollHandlerImpl.topOrLeftBehavior = topOrLeftBehavior
        nestedScrollHandlerImpl.bottomOrRightBehavior = bottomOrRightBehavior
        nestedScrollHandlerImpl.isExternalOverscrollGesture = isExternalOverscrollGesture
    }
}
