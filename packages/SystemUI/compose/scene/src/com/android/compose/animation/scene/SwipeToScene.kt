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
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
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
    return then(SwipeToSceneElement(draggableHandler, swipeDetector, draggableHandler.enabled()))
}

private fun DraggableHandlerImpl.enabled(): Boolean {
    return isDrivingTransition || contentForSwipes().shouldEnableSwipes(orientation)
}

private fun DraggableHandlerImpl.contentForSwipes(): Content {
    return layoutImpl.contentForUserActions()
}

/** Whether swipe should be enabled in the given [orientation]. */
internal fun Content.shouldEnableSwipes(orientation: Orientation): Boolean {
    if (userActions.isEmpty() || !areSwipesAllowed()) {
        return false
    }

    return userActions.keys.any { it is Swipe.Resolved && it.direction.orientation == orientation }
}

private data class SwipeToSceneElement(
    val draggableHandler: DraggableHandlerImpl,
    val swipeDetector: SwipeDetector,
    val enabled: Boolean,
) : ModifierNodeElement<SwipeToSceneRootNode>() {
    override fun create(): SwipeToSceneRootNode =
        SwipeToSceneRootNode(draggableHandler, swipeDetector, enabled)

    override fun update(node: SwipeToSceneRootNode) {
        node.update(draggableHandler, swipeDetector, enabled)
    }
}

private class SwipeToSceneRootNode(
    draggableHandler: DraggableHandlerImpl,
    swipeDetector: SwipeDetector,
    enabled: Boolean,
) : DelegatingNode() {
    private var delegateNode = if (enabled) create(draggableHandler, swipeDetector) else null

    fun update(
        draggableHandler: DraggableHandlerImpl,
        swipeDetector: SwipeDetector,
        enabled: Boolean,
    ) {
        // Disabled.
        if (!enabled) {
            delegateNode?.let { undelegate(it) }
            delegateNode = null
            return
        }

        // Disabled => Enabled.
        val nullableDelegate = delegateNode
        if (nullableDelegate == null) {
            delegateNode = create(draggableHandler, swipeDetector)
            return
        }

        // Enabled => Enabled (update).
        if (draggableHandler == nullableDelegate.draggableHandler) {
            // Simple update, just update the swipe detector directly and keep the node.
            nullableDelegate.swipeDetector = swipeDetector
        } else {
            // The draggableHandler changed, force recreate the underlying SwipeToSceneNode.
            undelegate(nullableDelegate)
            delegateNode = create(draggableHandler, swipeDetector)
        }
    }

    private fun create(
        draggableHandler: DraggableHandlerImpl,
        swipeDetector: SwipeDetector,
    ): SwipeToSceneNode {
        return delegate(SwipeToSceneNode(draggableHandler, swipeDetector))
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
            pointersInfoOwner = { multiPointerDraggableNode.pointersInfo() },
        )

    init {
        delegate(nestedScrollModifierNode(nestedScrollHandlerImpl.connection, dispatcher))
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
