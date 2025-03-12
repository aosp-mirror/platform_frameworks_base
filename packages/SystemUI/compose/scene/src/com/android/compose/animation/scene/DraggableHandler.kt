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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.util.fastCoerceIn
import com.android.compose.animation.scene.content.Content
import com.android.compose.animation.scene.content.state.TransitionState.HasOverscrollProperties.Companion.DistanceUnspecified
import com.android.compose.nestedscroll.OnStopScope
import com.android.compose.nestedscroll.PriorityNestedScrollConnection
import com.android.compose.nestedscroll.ScrollController
import com.android.compose.ui.util.SpaceVectorConverter
import kotlin.math.absoluteValue
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface DraggableHandler {
    /**
     * Start a drag with the given [pointersDown] and [overSlop].
     *
     * The returned [DragController] should be used to continue or stop the drag.
     */
    fun onDragStarted(pointersDown: PointersInfo.PointersDown?, overSlop: Float): DragController
}

/**
 * The [DragController] provides control over the transition between two scenes through the [onDrag]
 * and [onStop] methods.
 */
internal interface DragController {
    /**
     * Drag the current scene by [delta] pixels.
     *
     * @param delta The distance to drag the scene in pixels.
     * @return the consumed [delta]
     */
    fun onDrag(delta: Float): Float

    /**
     * Stop the current drag with the given [velocity].
     *
     * @param velocity The velocity of the drag when it stopped.
     * @param canChangeContent Whether the content can be changed as a result of this drag.
     * @return the consumed [velocity] when the animation complete
     */
    suspend fun onStop(velocity: Float, canChangeContent: Boolean): Float

    /**
     * Cancels the current drag.
     *
     * @param canChangeContent Whether the content can be changed as a result of this drag.
     */
    fun onCancel(canChangeContent: Boolean)
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

    override fun onDragStarted(
        pointersDown: PointersInfo.PointersDown?,
        overSlop: Float,
    ): DragController {
        check(overSlop != 0f)
        val swipes = computeSwipes(pointersDown)
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

    private fun createSwipeAnimation(swipes: Swipes, result: UserActionResult): SwipeAnimation<*> {
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

    private fun resolveSwipeSource(startedPosition: Offset): SwipeSource.Resolved? {
        return layoutImpl.swipeSourceDetector.source(
            layoutSize = layoutImpl.lastSize,
            position = startedPosition.round(),
            density = layoutImpl.density,
            orientation = orientation,
        )
    }

    private fun computeSwipes(pointersDown: PointersInfo.PointersDown?): Swipes {
        val fromSource = pointersDown?.let { resolveSwipeSource(it.startedPosition) }
        return Swipes(
            upOrLeft = resolveSwipe(orientation, isUpOrLeft = true, pointersDown, fromSource),
            downOrRight = resolveSwipe(orientation, isUpOrLeft = false, pointersDown, fromSource),
        )
    }
}

private fun resolveSwipe(
    orientation: Orientation,
    isUpOrLeft: Boolean,
    pointersDown: PointersInfo.PointersDown?,
    fromSource: SwipeSource.Resolved?,
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
        // If the number of pointers is not specified, 1 is assumed.
        pointerCount = pointersDown?.count ?: 1,
        // Resolves the pointer type only if all pointers are of the same type.
        pointersType = pointersDown?.countByType?.keys?.singleOrNull(),
        fromSource = fromSource,
    )
}

/** @param swipes The [Swipes] associated to the current gesture. */
private class DragControllerImpl(
    private val draggableHandler: DraggableHandlerImpl,
    val swipes: Swipes,
    var swipeAnimation: SwipeAnimation<*>,
) : DragController, SpaceVectorConverter by SpaceVectorConverter(draggableHandler.orientation) {
    val layoutState = draggableHandler.layoutImpl.state

    val overscrollableContent: OverscrollableContent =
        when (draggableHandler.orientation) {
            Orientation.Vertical -> draggableHandler.layoutImpl.verticalOverscrollableContent
            Orientation.Horizontal -> draggableHandler.layoutImpl.horizontalOverscrollableContent
        }

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
        return dragWithOverscroll(delta, animation = initialAnimation)
    }

    private fun <T : ContentKey> dragWithOverscroll(
        delta: Float,
        animation: SwipeAnimation<T>,
    ): Float {
        require(delta != 0f) { "delta should not be 0" }
        var overscrollEffect = overscrollableContent.currentOverscrollEffect

        // If we're already overscrolling, continue with the current effect for a smooth finish.
        if (overscrollEffect == null || !overscrollEffect.isInProgress) {
            // Otherwise, determine the target content (toContent or fromContent) for the new
            // overscroll effect based on the gesture's direction.
            val content = animation.contentByDirection(delta)
            overscrollEffect = overscrollableContent.applyOverscrollEffectOn(content)
        }

        // TODO(b/378470603) Remove this check once NestedDraggable is used to handle drags.
        if (!overscrollEffect.node.node.isAttached) {
            return drag(delta, animation)
        }

        return overscrollEffect
            .applyToScroll(
                delta = delta.toOffset(),
                source = NestedScrollSource.UserInput,
                performScroll = {
                    val preScrollAvailable = it.toFloat()
                    drag(preScrollAvailable, animation).toOffset()
                },
            )
            .toFloat()
    }

    private fun <T : ContentKey> drag(delta: Float, animation: SwipeAnimation<T>): Float {
        if (delta == 0f) return 0f

        val distance = animation.distance()
        val previousOffset = animation.dragOffset
        val desiredOffset = previousOffset + delta
        val desiredProgress = animation.computeProgress(desiredOffset)

        // Note: the distance could be negative if fromContent is above or to the left of toContent.
        val newOffset =
            when {
                distance == DistanceUnspecified ||
                    animation.contentTransition.isWithinProgressRange(desiredProgress) ->
                    desiredOffset
                distance > 0f -> desiredOffset.fastCoerceIn(0f, distance)
                else -> desiredOffset.fastCoerceIn(distance, 0f)
            }

        animation.dragOffset = newOffset
        return newOffset - previousOffset
    }

    override suspend fun onStop(velocity: Float, canChangeContent: Boolean): Float {
        // To ensure that any ongoing animation completes gracefully and avoids an undefined state,
        // we execute the actual `onStop` logic in a non-cancellable context. This prevents the
        // coroutine from being cancelled prematurely, which could interrupt the animation.
        // TODO(b/378470603) Remove this check once NestedDraggable is used to handle drags.
        return withContext(NonCancellable) { onStop(velocity, canChangeContent, swipeAnimation) }
    }

    private suspend fun <T : ContentKey> onStop(
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
        val targetContent =
            if (canChangeContent) {
                // If we are halfway between two contents, we check what the target will be based on
                // the velocity and offset of the transition, then we launch the animation.

                val toContent = swipeAnimation.toContent

                // Compute the destination content (and therefore offset) to settle in.
                val offset = swipeAnimation.dragOffset
                val distance = swipeAnimation.distance()
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
            } else {
                // We are doing an overscroll preview animation between scenes.
                check(fromContent == swipeAnimation.currentContent) {
                    "canChangeContent is false but currentContent != fromContent"
                }
                fromContent
            }

        val overscrollEffect = overscrollableContent.applyOverscrollEffectOn(targetContent)

        // TODO(b/378470603) Remove this check once NestedDraggable is used to handle drags.
        if (!overscrollEffect.node.node.isAttached) {
            return swipeAnimation.animateOffset(velocity, targetContent)
        }

        overscrollEffect.applyToFling(
            velocity = velocity.toVelocity(),
            performFling = {
                val velocityLeft = it.toFloat()
                swipeAnimation.animateOffset(velocityLeft, targetContent).toVelocity()
            },
        )

        return velocity
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

    override fun onCancel(canChangeContent: Boolean) {
        swipeAnimation.contentTransition.coroutineScope.launch {
            onStop(velocity = 0f, canChangeContent = canChangeContent)
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
    private val draggableHandler: DraggableHandlerImpl,
    internal var topOrLeftBehavior: NestedScrollBehavior,
    internal var bottomOrRightBehavior: NestedScrollBehavior,
    internal var isExternalOverscrollGesture: () -> Boolean,
    private val pointersInfoOwner: PointersInfoOwner,
) {
    val connection: PriorityNestedScrollConnection = nestedScrollConnection()

    private fun nestedScrollConnection(): PriorityNestedScrollConnection {
        // If we performed a long gesture before entering priority mode, we would have to avoid
        // moving on to the next scene.
        var canChangeScene = false

        var lastPointersDown: PointersInfo.PointersDown? = null

        fun shouldEnableSwipes(): Boolean {
            return draggableHandler.layoutImpl
                .contentForUserActions()
                .shouldEnableSwipes(draggableHandler.orientation)
        }

        return PriorityNestedScrollConnection(
            orientation = draggableHandler.orientation,
            canStartPreScroll = { _, _, _ -> false },
            canStartPostScroll = { offsetAvailable, offsetBeforeStart, _ ->
                val behavior: NestedScrollBehavior =
                    when {
                        offsetAvailable > 0f -> topOrLeftBehavior
                        offsetAvailable < 0f -> bottomOrRightBehavior
                        else -> return@PriorityNestedScrollConnection false
                    }

                val isZeroOffset =
                    if (isExternalOverscrollGesture()) false else offsetBeforeStart == 0f

                val pointersDown: PointersInfo.PointersDown? =
                    when (val info = pointersInfoOwner.pointersInfo()) {
                        PointersInfo.MouseWheel -> {
                            // Do not support mouse wheel interactions
                            return@PriorityNestedScrollConnection false
                        }

                        is PointersInfo.PointersDown -> info
                        null -> null
                    }
                lastPointersDown = pointersDown

                when (behavior) {
                    NestedScrollBehavior.EdgeNoPreview -> {
                        canChangeScene = isZeroOffset
                        isZeroOffset && shouldEnableSwipes()
                    }

                    NestedScrollBehavior.EdgeWithPreview -> {
                        canChangeScene = isZeroOffset
                        shouldEnableSwipes()
                    }

                    NestedScrollBehavior.EdgeAlways -> {
                        canChangeScene = true
                        shouldEnableSwipes()
                    }
                }
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

                val pointersDown: PointersInfo.PointersDown? =
                    when (val info = pointersInfoOwner.pointersInfo()) {
                        PointersInfo.MouseWheel -> {
                            // Do not support mouse wheel interactions
                            return@PriorityNestedScrollConnection false
                        }

                        is PointersInfo.PointersDown -> info
                        null -> null
                    }
                lastPointersDown = pointersDown

                behavior.canStartOnPostFling && shouldEnableSwipes()
            },
            onStart = { firstScroll ->
                scrollController(
                    dragController =
                        draggableHandler.onDragStarted(
                            pointersDown = lastPointersDown,
                            overSlop = firstScroll,
                        ),
                    canChangeScene = canChangeScene,
                    pointersInfoOwner = pointersInfoOwner,
                )
            },
        )
    }
}

private fun scrollController(
    dragController: DragController,
    canChangeScene: Boolean,
    pointersInfoOwner: PointersInfoOwner,
): ScrollController {
    return object : ScrollController {
        override fun onScroll(deltaScroll: Float, source: NestedScrollSource): Float {
            if (pointersInfoOwner.pointersInfo() == PointersInfo.MouseWheel) {
                // Do not support mouse wheel interactions
                return 0f
            }

            return dragController.onDrag(delta = deltaScroll)
        }

        override suspend fun OnStopScope.onStop(initialVelocity: Float): Float {
            return dragController.onStop(
                velocity = initialVelocity,
                canChangeContent = canChangeScene,
            )
        }

        override fun onCancel() {
            dragController.onCancel(canChangeScene)
        }

        /**
         * We need to maintain scroll priority even if the scene transition can no longer consume
         * the scroll gesture to allow us to return to the previous scene.
         */
        override fun canCancelScroll(available: Float, consumed: Float) = false

        override fun canStopOnPreFling() = true
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

    override suspend fun onStop(velocity: Float, canChangeContent: Boolean) = 0f

    override fun onCancel(canChangeContent: Boolean) {
        /* do nothing */
    }
}
