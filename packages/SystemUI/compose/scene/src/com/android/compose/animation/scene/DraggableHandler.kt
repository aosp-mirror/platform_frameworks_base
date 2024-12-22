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

@file:Suppress("NOTHING_TO_INLINE")

package com.android.compose.animation.scene

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.util.fastCoerceIn
import com.android.compose.animation.scene.content.Content
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.content.state.TransitionState.HasOverscrollProperties.Companion.DistanceUnspecified
import com.android.compose.nestedscroll.PriorityNestedScrollConnection
import kotlin.math.absoluteValue

internal interface DraggableHandler {
    /**
     * Start a drag in the given [startedPosition], with the given [overSlop] and number of
     * [pointersDown].
     *
     * The returned [DragController] should be used to continue or stop the drag.
     */
    fun onDragStarted(startedPosition: Offset?, overSlop: Float, pointersDown: Int): DragController
}

/**
 * The [DragController] provides control over the transition between two scenes through the [onDrag]
 * and [onStop] methods.
 */
internal interface DragController {
    /**
     * Drag the current scene by [delta] pixels.
     *
     * @return the consumed [delta]
     */
    fun onDrag(delta: Float): Float

    /**
     * Stop the current drag with the given [velocity].
     *
     * @return the consumed [velocity]
     */
    fun onStop(velocity: Float, canChangeContent: Boolean): Float
}

internal class DraggableHandlerImpl(
    internal val layoutImpl: SceneTransitionLayoutImpl,
    internal val orientation: Orientation,
) : DraggableHandler {
    internal val nestedScrollKey = Any()
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

    /**
     * Whether we should immediately intercept a gesture.
     *
     * Note: if this returns true, then [onDragStarted] will be called with overSlop equal to 0f,
     * indicating that the transition should be intercepted.
     */
    internal fun shouldImmediatelyIntercept(startedPosition: Offset?): Boolean {
        // We don't intercept the touch if we are not currently driving the transition.
        val dragController = dragController
        if (dragController?.isDrivingTransition != true) {
            return false
        }

        val swipeAnimation = dragController.swipeAnimation

        // Only intercept the current transition if one of the 2 swipes results is also a transition
        // between the same pair of contents.
        val swipes = computeSwipes(startedPosition, pointersDown = 1)
        val fromContent = layoutImpl.content(swipeAnimation.currentContent)
        val (upOrLeft, downOrRight) = swipes.computeSwipesResults(fromContent)
        val currentScene = layoutImpl.state.currentScene
        val contentTransition = swipeAnimation.contentTransition
        return (upOrLeft != null &&
            contentTransition.isTransitioningBetween(
                fromContent.key,
                upOrLeft.toContent(currentScene),
            )) ||
            (downOrRight != null &&
                contentTransition.isTransitioningBetween(
                    fromContent.key,
                    downOrRight.toContent(currentScene),
                ))
    }

    override fun onDragStarted(
        startedPosition: Offset?,
        overSlop: Float,
        pointersDown: Int,
    ): DragController {
        if (overSlop == 0f) {
            val oldDragController = dragController
            check(oldDragController != null && oldDragController.isDrivingTransition) {
                val isActive = oldDragController?.isDrivingTransition
                "onDragStarted(overSlop=0f) requires an active dragController, but was $isActive"
            }

            // This [transition] was already driving the animation: simply take over it.
            // Stop animating and start from the current offset.
            val oldSwipeAnimation = oldDragController.swipeAnimation

            // We need to recompute the swipe results since this is a new gesture, and the
            // fromScene.userActions may have changed.
            val swipes = oldDragController.swipes
            swipes.updateSwipesResults(
                fromContent = layoutImpl.content(oldSwipeAnimation.fromContent)
            )

            // A new gesture should always create a new SwipeAnimation. This way there cannot be
            // different gestures controlling the same transition.
            val swipeAnimation = createSwipeAnimation(oldSwipeAnimation)
            return updateDragController(swipes, swipeAnimation)
        }

        val swipes = computeSwipes(startedPosition, pointersDown)
        val fromContent = layoutImpl.contentForUserActions()

        swipes.updateSwipesResults(fromContent)
        val result =
            swipes.findUserActionResult(overSlop)
                // As we were unable to locate a valid target scene, the initial SwipeAnimation
                // cannot be defined. Consequently, a simple NoOp Controller will be returned.
                ?: return NoOpDragController

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

    internal fun createSwipeAnimation(swipes: Swipes, result: UserActionResult): SwipeAnimation<*> {
        val upOrLeftResult = swipes.upOrLeftResult
        val downOrRightResult = swipes.downOrRightResult
        val isUpOrLeft =
            when (result) {
                upOrLeftResult -> true
                downOrRightResult -> false
                else -> error("Unknown result $result ($upOrLeftResult $downOrRightResult)")
            }

        return createSwipeAnimation(layoutImpl, result, isUpOrLeft, orientation)
    }

    private fun computeSwipes(startedPosition: Offset?, pointersDown: Int): Swipes {
        val fromSource =
            startedPosition?.let { position ->
                layoutImpl.swipeSourceDetector.source(
                    layoutImpl.lastSize,
                    position.round(),
                    layoutImpl.density,
                    orientation,
                )
            }

        val upOrLeft =
            Swipe.Resolved(
                direction =
                    when (orientation) {
                        Orientation.Horizontal -> SwipeDirection.Resolved.Left
                        Orientation.Vertical -> SwipeDirection.Resolved.Up
                    },
                pointerCount = pointersDown,
                fromSource = fromSource,
            )

        val downOrRight =
            Swipe.Resolved(
                direction =
                    when (orientation) {
                        Orientation.Horizontal -> SwipeDirection.Resolved.Right
                        Orientation.Vertical -> SwipeDirection.Resolved.Down
                    },
                pointerCount = pointersDown,
                fromSource = fromSource,
            )

        return if (fromSource == null) {
            Swipes(
                upOrLeft = null,
                downOrRight = null,
                upOrLeftNoSource = upOrLeft,
                downOrRightNoSource = downOrRight,
            )
        } else {
            Swipes(
                upOrLeft = upOrLeft,
                downOrRight = downOrRight,
                upOrLeftNoSource = upOrLeft.copy(fromSource = null),
                downOrRightNoSource = downOrRight.copy(fromSource = null),
            )
        }
    }

    companion object {
        private const val TAG = "DraggableHandlerImpl"
    }
}

/** @param swipes The [Swipes] associated to the current gesture. */
private class DragControllerImpl(
    private val draggableHandler: DraggableHandlerImpl,
    val swipes: Swipes,
    var swipeAnimation: SwipeAnimation<*>,
) : DragController {
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
        return onDrag(delta, swipeAnimation)
    }

    private fun <T : ContentKey> onDrag(delta: Float, swipeAnimation: SwipeAnimation<T>): Float {
        if (delta == 0f || !isDrivingTransition || swipeAnimation.isAnimatingOffset()) {
            return 0f
        }

        val toContent = swipeAnimation.toContent
        val distance = swipeAnimation.distance()
        val previousOffset = swipeAnimation.dragOffset
        val desiredOffset = previousOffset + delta

        fun hasReachedToSceneUpOrLeft() =
            distance < 0 &&
                desiredOffset <= distance &&
                swipes.upOrLeftResult?.toContent(layoutState.currentScene) == toContent

        fun hasReachedToSceneDownOrRight() =
            distance > 0 &&
                desiredOffset >= distance &&
                swipes.downOrRightResult?.toContent(layoutState.currentScene) == toContent

        // Considering accelerated swipe: Change fromContent in the case where the user quickly
        // swiped multiple times in the same direction to accelerate the transition from A => B then
        // B => C.
        //
        // TODO(b/290184746): the second drag needs to pass B to work. Add support for flinging
        //  twice before B has been reached
        val hasReachedToContent =
            swipeAnimation.currentContent == toContent &&
                (hasReachedToSceneUpOrLeft() || hasReachedToSceneDownOrRight())

        val fromContent: ContentKey
        val currentTransitionOffset: Float
        val newOffset: Float
        val consumedDelta: Float
        if (hasReachedToContent) {
            // The new transition will start from the current toContent.
            fromContent = toContent

            // The current transition is completed (we have reached the full swipe distance).
            currentTransitionOffset = distance

            // The next transition will start with the remaining offset.
            newOffset = desiredOffset - distance
            consumedDelta = delta
        } else {
            fromContent = swipeAnimation.fromContent
            val desiredProgress = swipeAnimation.computeProgress(desiredOffset)

            // Note: the distance could be negative if fromContent is above or to the left of
            // toContent.
            currentTransitionOffset =
                when {
                    distance == DistanceUnspecified ||
                        swipeAnimation.contentTransition.isWithinProgressRange(desiredProgress) ->
                        desiredOffset
                    distance > 0f -> desiredOffset.fastCoerceIn(0f, distance)
                    else -> desiredOffset.fastCoerceIn(distance, 0f)
                }

            // If there is a new transition, we will use the same offset
            newOffset = currentTransitionOffset
            consumedDelta = newOffset - previousOffset
        }

        swipeAnimation.dragOffset = currentTransitionOffset

        if (hasReachedToContent) {
            swipes.updateSwipesResults(draggableHandler.layoutImpl.content(fromContent))
        }
        val result = swipes.findUserActionResult(directionOffset = newOffset)

        if (result == null) {
            onStop(velocity = delta, canChangeContent = true)
            return 0f
        }

        val needNewTransition =
            hasReachedToContent ||
                result.toContent(layoutState.currentScene) != swipeAnimation.toContent ||
                result.transitionKey != swipeAnimation.contentTransition.key

        if (needNewTransition) {
            // Make sure the current transition will finish to the right current scene.
            swipeAnimation.currentContent = fromContent

            val newSwipeAnimation = draggableHandler.createSwipeAnimation(swipes, result)
            newSwipeAnimation.dragOffset = newOffset
            updateTransition(newSwipeAnimation)
        }

        return consumedDelta
    }

    override fun onStop(velocity: Float, canChangeContent: Boolean): Float {
        return onStop(velocity, canChangeContent, swipeAnimation)
    }

    private fun <T : ContentKey> onStop(
        velocity: Float,
        canChangeContent: Boolean,

        // Important: Make sure that this has the same name as [this.swipeAnimation] so that all the
        // code here references the current animation when [onDragStopped] is called, otherwise the
        // callbacks (like onAnimationCompleted()) might incorrectly finish a new transition that
        // replaced this one.
        swipeAnimation: SwipeAnimation<T>,
    ): Float {
        // The state was changed since the drag started; don't do anything.
        if (!isDrivingTransition || swipeAnimation.isAnimatingOffset()) {
            return 0f
        }

        val fromContent = swipeAnimation.fromContent
        val consumedVelocity: Float
        if (canChangeContent) {
            // If we are halfway between two contents, we check what the target will be based on the
            // velocity and offset of the transition, then we launch the animation.

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
            consumedVelocity = swipeAnimation.animateOffset(velocity, targetContent = targetContent)
        } else {
            // We are doing an overscroll preview animation between scenes.
            check(fromContent == swipeAnimation.currentContent) {
                "canChangeContent is false but currentContent != fromContent"
            }
            consumedVelocity = swipeAnimation.animateOffset(velocity, targetContent = fromContent)
        }

        return consumedVelocity
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
internal class Swipes(
    val upOrLeft: Swipe.Resolved?,
    val downOrRight: Swipe.Resolved?,
    val upOrLeftNoSource: Swipe.Resolved?,
    val downOrRightNoSource: Swipe.Resolved?,
) {
    /** The [UserActionResult] associated to up and down swipes. */
    var upOrLeftResult: UserActionResult? = null
    var downOrRightResult: UserActionResult? = null

    fun computeSwipesResults(fromContent: Content): Pair<UserActionResult?, UserActionResult?> {
        val userActions = fromContent.userActions
        fun result(swipe: Swipe.Resolved?): UserActionResult? {
            return userActions[swipe ?: return null]
        }

        val upOrLeftResult = result(upOrLeft) ?: result(upOrLeftNoSource)
        val downOrRightResult = result(downOrRight) ?: result(downOrRightNoSource)
        return upOrLeftResult to downOrRightResult
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

    /**
     * Returns the [UserActionResult] in the direction of [directionOffset].
     *
     * @param directionOffset signed float that indicates the direction. Positive is down or right
     *   negative is up or left.
     * @return null when there are no targets in either direction. If one direction is null and you
     *   drag into the null direction this function will return the opposite direction, assuming
     *   that the users intention is to start the drag into the other direction eventually. If
     *   [directionOffset] is 0f and both direction are available, it will default to
     *   [upOrLeftResult].
     */
    fun findUserActionResult(directionOffset: Float): UserActionResult? {
        return when {
            upOrLeftResult == null && downOrRightResult == null -> null
            (directionOffset < 0f && upOrLeftResult != null) || downOrRightResult == null ->
                upOrLeftResult
            else -> downOrRightResult
        }
    }
}

internal class NestedScrollHandlerImpl(
    private val layoutImpl: SceneTransitionLayoutImpl,
    private val orientation: Orientation,
    internal var topOrLeftBehavior: NestedScrollBehavior,
    internal var bottomOrRightBehavior: NestedScrollBehavior,
    internal var isExternalOverscrollGesture: () -> Boolean,
    private val pointersInfoOwner: PointersInfoOwner,
) {
    private val layoutState = layoutImpl.state
    private val draggableHandler = layoutImpl.draggableHandler(orientation)

    val connection: PriorityNestedScrollConnection = nestedScrollConnection()

    private fun nestedScrollConnection(): PriorityNestedScrollConnection {
        // If we performed a long gesture before entering priority mode, we would have to avoid
        // moving on to the next scene.
        var canChangeScene = false

        var _lastPointersInfo: PointersInfo? = null
        fun pointersInfo(): PointersInfo {
            return checkNotNull(_lastPointersInfo) {
                "PointersInfo should be initialized before the transition begins."
            }
        }

        fun hasNextScene(amount: Float): Boolean {
            val transitionState = layoutState.transitionState
            val scene = transitionState.currentScene
            val fromScene = layoutImpl.scene(scene)
            val nextScene =
                when {
                    amount < 0f -> {
                        val actionUpOrLeft =
                            Swipe.Resolved(
                                direction =
                                    when (orientation) {
                                        Orientation.Horizontal -> SwipeDirection.Resolved.Left
                                        Orientation.Vertical -> SwipeDirection.Resolved.Up
                                    },
                                pointerCount = pointersInfo().pointersDown,
                                fromSource = null,
                            )
                        fromScene.userActions[actionUpOrLeft]
                    }
                    amount > 0f -> {
                        val actionDownOrRight =
                            Swipe.Resolved(
                                direction =
                                    when (orientation) {
                                        Orientation.Horizontal -> SwipeDirection.Resolved.Right
                                        Orientation.Vertical -> SwipeDirection.Resolved.Down
                                    },
                                pointerCount = pointersInfo().pointersDown,
                                fromSource = null,
                            )
                        fromScene.userActions[actionDownOrRight]
                    }
                    else -> null
                }
            if (nextScene != null) return true

            if (transitionState !is TransitionState.Idle) return false

            val overscrollSpec = layoutImpl.state.transitions.overscrollSpec(scene, orientation)
            return overscrollSpec != null
        }

        var dragController: DragController? = null
        var isIntercepting = false

        return PriorityNestedScrollConnection(
            orientation = orientation,
            canStartPreScroll = { offsetAvailable, offsetBeforeStart ->
                canChangeScene =
                    if (isExternalOverscrollGesture()) false else offsetBeforeStart == 0f

                val canInterceptSwipeTransition =
                    canChangeScene &&
                        offsetAvailable != 0f &&
                        draggableHandler.shouldImmediatelyIntercept(startedPosition = null)
                if (!canInterceptSwipeTransition) return@PriorityNestedScrollConnection false

                val threshold = layoutImpl.transitionInterceptionThreshold
                val hasSnappedToIdle = layoutState.snapToIdleIfClose(threshold)
                if (hasSnappedToIdle) {
                    // If the current swipe transition is closed to 0f or 1f, then we want to
                    // interrupt the transition (snapping it to Idle) and scroll the list.
                    return@PriorityNestedScrollConnection false
                }

                _lastPointersInfo = pointersInfoOwner.pointersInfo()

                // If the current swipe transition is *not* closed to 0f or 1f, then we want the
                // scroll events to intercept the current transition to continue the scene
                // transition.
                isIntercepting = true
                true
            },
            canStartPostScroll = { offsetAvailable, offsetBeforeStart ->
                val behavior: NestedScrollBehavior =
                    when {
                        offsetAvailable > 0f -> topOrLeftBehavior
                        offsetAvailable < 0f -> bottomOrRightBehavior
                        else -> return@PriorityNestedScrollConnection false
                    }

                val isZeroOffset =
                    if (isExternalOverscrollGesture()) false else offsetBeforeStart == 0f

                _lastPointersInfo = pointersInfoOwner.pointersInfo()

                val canStart =
                    when (behavior) {
                        NestedScrollBehavior.EdgeNoPreview -> {
                            canChangeScene = isZeroOffset
                            isZeroOffset && hasNextScene(offsetAvailable)
                        }
                        NestedScrollBehavior.EdgeWithPreview -> {
                            canChangeScene = isZeroOffset
                            hasNextScene(offsetAvailable)
                        }
                        NestedScrollBehavior.EdgeAlways -> {
                            canChangeScene = true
                            hasNextScene(offsetAvailable)
                        }
                    }

                if (canStart) {
                    isIntercepting = false
                }

                canStart
            },
            canStartPostFling = { velocityAvailable ->
                val behavior: NestedScrollBehavior =
                    when {
                        velocityAvailable > 0f -> topOrLeftBehavior
                        velocityAvailable < 0f -> bottomOrRightBehavior
                        else -> return@PriorityNestedScrollConnection false
                    }

                // We could start an overscroll animation
                canChangeScene = false

                _lastPointersInfo = pointersInfoOwner.pointersInfo()

                val canStart = behavior.canStartOnPostFling && hasNextScene(velocityAvailable)
                if (canStart) {
                    isIntercepting = false
                }

                canStart
            },
            canContinueScroll = { true },
            canScrollOnFling = false,
            onStart = { offsetAvailable ->
                val pointersInfo = pointersInfo()
                dragController =
                    draggableHandler.onDragStarted(
                        pointersDown = pointersInfo.pointersDown,
                        startedPosition = pointersInfo.startedPosition,
                        overSlop = if (isIntercepting) 0f else offsetAvailable,
                    )
            },
            onScroll = { offsetAvailable ->
                val controller = dragController ?: error("Should be called after onStart")

                // TODO(b/297842071) We should handle the overscroll or slow drag if the gesture is
                // initiated in a nested child.
                controller.onDrag(delta = offsetAvailable)
            },
            onStop = { velocityAvailable ->
                val controller = dragController ?: error("Should be called after onStart")

                controller
                    .onStop(velocity = velocityAvailable, canChangeContent = canChangeScene)
                    .also { dragController = null }
            },
        )
    }
}

/**
 * The number of pixels below which there won't be a visible difference in the transition and from
 * which the animation can stop.
 */
// TODO(b/290184746): Have a better default visibility threshold which takes the swipe distance into
// account instead.
internal const val OffsetVisibilityThreshold = 0.5f

private object NoOpDragController : DragController {
    override fun onDrag(delta: Float) = 0f

    override fun onStop(velocity: Float, canChangeContent: Boolean) = 0f
}
