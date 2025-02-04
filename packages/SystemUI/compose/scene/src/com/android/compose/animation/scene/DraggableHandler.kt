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

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.util.fastCoerceIn
import com.android.compose.animation.scene.content.Content
import com.android.compose.animation.scene.content.state.TransitionState.Companion.DistanceUnspecified
import com.android.compose.animation.scene.effect.GestureEffect
import com.android.compose.gesture.NestedDraggable
import com.android.compose.ui.util.SpaceVectorConverter
import com.android.mechanics.DistanceGestureContext
import com.android.mechanics.spec.InputDirection
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

internal class DraggableHandler(
    internal val layoutImpl: SceneTransitionLayoutImpl,
    internal val orientation: Orientation,
    private val gestureEffectProvider: (ContentKey) -> GestureEffect,
) : NestedDraggable {
    /** The [DraggableHandler] can only have one active [DragController] at a time. */
    private var dragController: DragControllerImpl? = null

    internal val isDrivingTransition: Boolean
        get() = dragController?.isDrivingTransition == true

    /**
     * The velocity threshold at which the intent of the user is to swipe up or down. It is the same
     * as SwipeableV2Defaults.VelocityThreshold.
     */
    internal val velocityThreshold: Float
        get() = with(layoutImpl.density) { 125.dp.toPx() }

    /**
     * The positional threshold at which the intent of the user is to swipe to the next scene. It is
     * the same as SwipeableV2Defaults.PositionalThreshold.
     */
    internal val positionalThreshold
        get() = with(layoutImpl.density) { 56.dp.toPx() }

    /** The [OverscrollEffect] that should consume any overscroll on this draggable. */
    internal val overscrollEffect: OverscrollEffect = DelegatingOverscrollEffect()

    override fun shouldStartDrag(change: PointerInputChange): Boolean {
        return layoutImpl.swipeDetector.detectSwipe(change)
    }

    override fun shouldConsumeNestedScroll(sign: Float): Boolean {
        return this.enabled()
    }

    override fun onDragStarted(
        position: Offset,
        sign: Float,
        pointersDown: Int,
        pointerType: PointerType?,
    ): NestedDraggable.Controller {
        check(sign != 0f)
        val swipes = computeSwipes(position, pointersDown, pointerType)
        val fromContent = layoutImpl.contentForUserActions()

        swipes.updateSwipesResults(fromContent)
        val upOrLeft = swipes.upOrLeftResult
        val downOrRight = swipes.downOrRightResult
        val result =
            when {
                sign < 0 -> upOrLeft ?: downOrRight
                sign >= 0f -> downOrRight ?: upOrLeft
                else -> null
            } ?: return NoOpDragController

        if (result is UserActionResult.ShowOverlay) {
            layoutImpl.hideOverlays(result.hideCurrentOverlays)
        }

        val swipeAnimation = createSwipeAnimation(swipes, result)
        return updateDragController(swipes, swipeAnimation)
    }

    private fun updateDragController(
        swipes: Swipes,
        swipeAnimation: SwipeAnimation<*>,
    ): DragControllerImpl {
        val newDragController = DragControllerImpl(this, swipes, swipeAnimation)
        newDragController.updateTransition(swipeAnimation, force = true)
        dragController = newDragController
        return newDragController
    }

    private fun createSwipeAnimation(swipes: Swipes, result: UserActionResult): SwipeAnimation<*> {
        val upOrLeftResult = swipes.upOrLeftResult
        val downOrRightResult = swipes.downOrRightResult
        val isUpOrLeft =
            when (result) {
                upOrLeftResult -> true
                downOrRightResult -> false
                else -> error("Unknown result $result ($upOrLeftResult $downOrRightResult)")
            }

        val gestureContext =
            DistanceGestureContext(
                initialDragOffset = 0f,
                initialDirection = if (isUpOrLeft) InputDirection.Min else InputDirection.Max,
                directionChangeSlop = layoutImpl.directionChangeSlop,
            )

        return createSwipeAnimation(
            layoutImpl,
            result,
            isUpOrLeft,
            orientation,
            gestureContext,
            layoutImpl.decayAnimationSpec,
        )
    }

    private fun resolveSwipeSource(startedPosition: Offset): SwipeSource.Resolved? {
        return layoutImpl.swipeSourceDetector.source(
            layoutSize = layoutImpl.lastSize,
            position = startedPosition.round(),
            density = layoutImpl.density,
            orientation = orientation,
        )
    }

    private fun computeSwipes(
        position: Offset,
        pointersDown: Int,
        pointerType: PointerType?,
    ): Swipes {
        val fromSource = resolveSwipeSource(position)
        return Swipes(
            upOrLeft =
                resolveSwipe(orientation, isUpOrLeft = true, fromSource, pointersDown, pointerType),
            downOrRight =
                resolveSwipe(orientation, isUpOrLeft = false, fromSource, pointersDown, pointerType),
        )
    }

    /**
     * An implementation of [OverscrollEffect] that delegates to the correct content effect
     * depending on the current scene/overlays and transition.
     */
    private inner class DelegatingOverscrollEffect :
        OverscrollEffect, SpaceVectorConverter by SpaceVectorConverter(orientation) {
        private var currentContent: ContentKey? = null
        private var currentDelegate: GestureEffect? = null
            set(value) {
                field?.let { delegate ->
                    if (delegate.isInProgress) {
                        layoutImpl.animationScope.launch { delegate.ensureApplyToFlingIsCalled() }
                    }
                }

                field = value
            }

        override val isInProgress: Boolean
            get() = currentDelegate?.isInProgress ?: false

        override fun applyToScroll(
            delta: Offset,
            source: NestedScrollSource,
            performScroll: (Offset) -> Offset,
        ): Offset {
            val available = delta.toFloat()
            if (available == 0f) {
                return performScroll(delta)
            }

            ensureDelegateIsNotNull(available)
            val delegate = checkNotNull(currentDelegate)
            return if (delegate.node.node.isAttached) {
                delegate.applyToScroll(delta, source, performScroll)
            } else {
                performScroll(delta)
            }
        }

        override suspend fun applyToFling(
            velocity: Velocity,
            performFling: suspend (Velocity) -> Velocity,
        ) {
            val available = velocity.toFloat()
            if (available != 0f && isDrivingTransition) {
                ensureDelegateIsNotNull(available)
            }

            // Note: we set currentDelegate and currentContent to null before calling performFling,
            // which can suspend and take a lot of time.
            val delegate = currentDelegate
            currentDelegate = null
            currentContent = null

            if (delegate != null && delegate.node.node.isAttached) {
                delegate.applyToFling(velocity, performFling)
            } else {
                performFling(velocity)
            }
        }

        private fun ensureDelegateIsNotNull(direction: Float) {
            require(direction != 0f)
            if (isInProgress) {
                return
            }

            val content =
                if (isDrivingTransition) {
                    checkNotNull(dragController).swipeAnimation.contentByDirection(direction)
                } else {
                    layoutImpl.contentForUserActions().key
                }

            if (content != currentContent) {
                currentContent = content
                currentDelegate = gestureEffectProvider(content)
            }
        }
    }
}

private fun resolveSwipe(
    orientation: Orientation,
    isUpOrLeft: Boolean,
    fromSource: SwipeSource.Resolved?,
    pointersDown: Int,
    pointerType: PointerType?,
): Swipe.Resolved {
    return Swipe.Resolved(
        direction =
            when (orientation) {
                Orientation.Horizontal ->
                    if (isUpOrLeft) {
                        SwipeDirection.Resolved.Left
                    } else {
                        SwipeDirection.Resolved.Right
                    }

                Orientation.Vertical ->
                    if (isUpOrLeft) {
                        SwipeDirection.Resolved.Up
                    } else {
                        SwipeDirection.Resolved.Down
                    }
            },
        pointerCount = pointersDown,
        pointerType = pointerType,
        fromSource = fromSource,
    )
}

/** @param swipes The [Swipes] associated to the current gesture. */
private class DragControllerImpl(
    private val draggableHandler: DraggableHandler,
    val swipes: Swipes,
    var swipeAnimation: SwipeAnimation<*>,
) :
    NestedDraggable.Controller,
    SpaceVectorConverter by SpaceVectorConverter(draggableHandler.orientation) {
    val layoutState = draggableHandler.layoutImpl.state

    /**
     * Whether this handle is active. If this returns false, calling [onDrag] and [onStop] will do
     * nothing.
     */
    val isDrivingTransition: Boolean
        get() = layoutState.transitionState == swipeAnimation.contentTransition

    init {
        check(!isDrivingTransition) { "Multiple controllers with the same SwipeTransition" }
    }

    fun updateTransition(newTransition: SwipeAnimation<*>, force: Boolean = false) {
        if (force || isDrivingTransition) {
            layoutState.startTransitionImmediately(
                animationScope = draggableHandler.layoutImpl.animationScope,
                newTransition.contentTransition,
                true,
            )
        }

        swipeAnimation = newTransition
    }

    /**
     * We receive a [delta] that can be consumed to change the offset of the current
     * [SwipeAnimation].
     *
     * @return the consumed delta
     */
    override fun onDrag(delta: Float): Float {
        val initialAnimation = swipeAnimation
        if (delta == 0f || !isDrivingTransition || initialAnimation.isAnimatingOffset()) {
            return 0f
        }

        // swipeAnimation can change during the gesture, we want to always use the initial reference
        // during the whole drag gesture.
        return drag(delta, animation = initialAnimation)
    }

    private fun <T : ContentKey> drag(delta: Float, animation: SwipeAnimation<T>): Float {
        val distance = animation.distance()
        val previousOffset = animation.dragOffset
        val desiredOffset = previousOffset + delta

        // Note: the distance could be negative if fromContent is above or to the left of toContent.
        val newOffset =
            when {
                distance == DistanceUnspecified -> {
                    // Consume everything so that we don't overscroll, this will be coerced later
                    // when the distance is defined.
                    delta
                }

                distance > 0f -> desiredOffset.fastCoerceIn(0f, distance)
                else -> desiredOffset.fastCoerceIn(distance, 0f)
            }

        animation.dragOffset = newOffset
        return newOffset - previousOffset
    }

    override suspend fun onDragStopped(velocity: Float, awaitFling: suspend () -> Unit): Float {
        return onStop(velocity, swipeAnimation, awaitFling)
    }

    private suspend fun <T : ContentKey> onStop(
        velocity: Float,

        // Important: Make sure that this has the same name as [this.swipeAnimation] so that all the
        // code here references the current animation when [onDragStopped] is called, otherwise the
        // callbacks (like onAnimationCompleted()) might incorrectly finish a new transition that
        // replaced this one.
        swipeAnimation: SwipeAnimation<T>,
        awaitFling: suspend () -> Unit,
    ): Float {
        // The state was changed since the drag started; don't do anything.
        if (!isDrivingTransition || swipeAnimation.isAnimatingOffset()) {
            return 0f
        }

        val fromContent = swipeAnimation.fromContent
        // If we are halfway between two contents, we check what the target will be based on
        // the velocity and offset of the transition, then we launch the animation.

        val toContent = swipeAnimation.toContent

        // Compute the destination content (and therefore offset) to settle in.
        val offset = swipeAnimation.dragOffset
        val distance = swipeAnimation.distance()
        val targetContent =
            if (
                distance != DistanceUnspecified &&
                    shouldCommitSwipe(
                        offset = offset,
                        distance = distance,
                        velocity = velocity,
                        wasCommitted = swipeAnimation.currentContent == toContent,
                        requiresFullDistanceSwipe = swipeAnimation.requiresFullDistanceSwipe,
                    )
            ) {
                toContent
            } else {
                fromContent
            }

        return swipeAnimation.animateOffset(velocity, targetContent, awaitFling = awaitFling)
    }

    /**
     * Whether the swipe to the target scene should be committed or not. This is inspired by
     * SwipeableV2.computeTarget().
     */
    private fun shouldCommitSwipe(
        offset: Float,
        distance: Float,
        velocity: Float,
        wasCommitted: Boolean,
        requiresFullDistanceSwipe: Boolean,
    ): Boolean {
        if (requiresFullDistanceSwipe && !wasCommitted) {
            return offset / distance >= 1f
        }

        fun isCloserToTarget(): Boolean {
            return (offset - distance).absoluteValue < offset.absoluteValue
        }

        val velocityThreshold = draggableHandler.velocityThreshold
        val positionalThreshold = draggableHandler.positionalThreshold

        // Swiping up or left.
        if (distance < 0f) {
            return if (offset > 0f || velocity >= velocityThreshold) {
                false
            } else {
                velocity <= -velocityThreshold ||
                    (offset <= -positionalThreshold && !wasCommitted) ||
                    isCloserToTarget()
            }
        }

        // Swiping down or right.
        return if (offset < 0f || velocity <= -velocityThreshold) {
            false
        } else {
            velocity >= velocityThreshold ||
                (offset >= positionalThreshold && !wasCommitted) ||
                isCloserToTarget()
        }
    }
}

/** The [Swipe] associated to a given fromScene, startedPosition and pointersDown. */
internal class Swipes(val upOrLeft: Swipe.Resolved, val downOrRight: Swipe.Resolved) {
    /** The [UserActionResult] associated to up and down swipes. */
    var upOrLeftResult: UserActionResult? = null
    var downOrRightResult: UserActionResult? = null

    private fun computeSwipesResults(
        fromContent: Content
    ): Pair<UserActionResult?, UserActionResult?> {
        val upOrLeftResult = fromContent.findActionResultBestMatch(swipe = upOrLeft)
        val downOrRightResult = fromContent.findActionResultBestMatch(swipe = downOrRight)
        return upOrLeftResult to downOrRightResult
    }

    /**
     * Finds the best matching [UserActionResult] for the given [swipe] within this [Content].
     * Prioritizes actions with matching [Swipe.Resolved.fromSource].
     *
     * @param swipe The swipe to match against.
     * @return The best matching [UserActionResult], or `null` if no match is found.
     */
    private fun Content.findActionResultBestMatch(swipe: Swipe.Resolved): UserActionResult? {
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
                    (actionSwipe.fromSource != null &&
                        actionSwipe.fromSource != swipe.fromSource) ||
                    // The action requires a specific pointerType.
                    (actionSwipe.pointerType != null &&
                        actionSwipe.pointerType != swipe.pointerType)
            ) {
                // This action is not eligible.
                return@forEach
            }

            val sameFromSource = actionSwipe.fromSource == swipe.fromSource
            val samePointerType = actionSwipe.pointerType == swipe.pointerType
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

    /**
     * Update the swipes results.
     *
     * Usually we don't want to update them while doing a drag, because this could change the target
     * content (jump cutting) to a different content, when some system state changed the targets the
     * background. However, an update is needed any time we calculate the targets for a new
     * fromContent.
     */
    fun updateSwipesResults(fromContent: Content) {
        val (upOrLeftResult, downOrRightResult) = computeSwipesResults(fromContent)

        this.upOrLeftResult = upOrLeftResult
        this.downOrRightResult = downOrRightResult
    }
}

/**
 * The number of pixels below which there won't be a visible difference in the transition and from
 * which the animation can stop.
 */
// TODO(b/290184746): Have a better default visibility threshold which takes the swipe distance into
// account instead.
internal const val OffsetVisibilityThreshold = 0.5f

private object NoOpDragController : NestedDraggable.Controller {
    override fun onDrag(delta: Float) = 0f

    override suspend fun onDragStopped(velocity: Float, awaitFling: suspend () -> Unit): Float = 0f
}
