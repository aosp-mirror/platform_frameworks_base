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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.util.fastCoerceIn
import com.android.compose.animation.scene.content.Content
import com.android.compose.animation.scene.content.Overlay
import com.android.compose.animation.scene.content.Scene
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.content.state.TransitionState.HasOverscrollProperties.Companion.DistanceUnspecified
import com.android.compose.nestedscroll.PriorityNestedScrollConnection
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    internal val coroutineScope: CoroutineScope,
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

        // Don't intercept a transition that is finishing.
        if (swipeAnimation.isFinishing) {
            return false
        }

        // Only intercept the current transition if one of the 2 swipes results is also a transition
        // between the same pair of contents.
        val swipes = computeSwipes(startedPosition, pointersDown = 1)
        val fromContent = swipeAnimation.currentContent
        val (upOrLeft, downOrRight) = swipes.computeSwipesResults(fromContent)
        val currentScene = layoutImpl.state.currentScene
        val contentTransition = swipeAnimation.contentTransition
        return (upOrLeft != null &&
            contentTransition.isTransitioningBetween(
                fromContent.key,
                upOrLeft.toContent(currentScene)
            )) ||
            (downOrRight != null &&
                contentTransition.isTransitioningBetween(
                    fromContent.key,
                    downOrRight.toContent(currentScene)
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
            oldSwipeAnimation.cancelOffsetAnimation()

            // We need to recompute the swipe results since this is a new gesture, and the
            // fromScene.userActions may have changed.
            val swipes = oldDragController.swipes
            swipes.updateSwipesResults(fromContent = oldSwipeAnimation.fromContent)

            // A new gesture should always create a new SwipeAnimation. This way there cannot be
            // different gestures controlling the same transition.
            val swipeAnimation = createSwipeAnimation(oldSwipeAnimation)
            return updateDragController(swipes, swipeAnimation)
        }

        val swipes = computeSwipes(startedPosition, pointersDown)
        val fromContent = layoutImpl.contentForUserActions()
        val result =
            swipes.findUserActionResult(fromContent, overSlop, updateSwipesResults = true)
                // As we were unable to locate a valid target scene, the initial SwipeAnimation
                // cannot be defined. Consequently, a simple NoOp Controller will be returned.
                ?: return NoOpDragController

        val swipeAnimation = createSwipeAnimation(swipes, result)
        return updateDragController(swipes, swipeAnimation)
    }

    private fun updateDragController(
        swipes: Swipes,
        swipeAnimation: SwipeAnimation<*>
    ): DragControllerImpl {
        val newDragController = DragControllerImpl(this, swipes, swipeAnimation)
        newDragController.updateTransition(swipeAnimation, force = true)
        dragController = newDragController
        return newDragController
    }

    internal fun createSwipeAnimation(
        swipes: Swipes,
        result: UserActionResult,
    ): SwipeAnimation<*> {
        val upOrLeftResult = swipes.upOrLeftResult
        val downOrRightResult = swipes.downOrRightResult
        val isUpOrLeft =
            when (result) {
                upOrLeftResult -> true
                downOrRightResult -> false
                else -> error("Unknown result $result ($upOrLeftResult $downOrRightResult)")
            }

        fun <T : Content> swipeAnimation(fromContent: T, toContent: T): SwipeAnimation<T> {
            return SwipeAnimation(
                layoutImpl = layoutImpl,
                fromContent = fromContent,
                toContent = toContent,
                userActionDistanceScope = layoutImpl.userActionDistanceScope,
                orientation = orientation,
                isUpOrLeft = isUpOrLeft,
                requiresFullDistanceSwipe = result.requiresFullDistanceSwipe,
            )
        }

        val layoutState = layoutImpl.state
        return when (result) {
            is UserActionResult.ChangeScene -> {
                val fromScene = layoutImpl.scene(layoutState.currentScene)
                val toScene = layoutImpl.scene(result.toScene)
                ChangeCurrentSceneSwipeTransition(
                        layoutState = layoutState,
                        swipeAnimation =
                            swipeAnimation(fromContent = fromScene, toContent = toScene),
                        key = result.transitionKey,
                        replacedTransition = null,
                    )
                    .swipeAnimation
            }
            is UserActionResult.ShowOverlay -> {
                val fromScene = layoutImpl.scene(layoutState.currentScene)
                val overlay = layoutImpl.overlay(result.overlay)
                ShowOrHideOverlaySwipeTransition(
                        layoutState = layoutState,
                        _fromOrToScene = fromScene,
                        _overlay = overlay,
                        swipeAnimation =
                            swipeAnimation(fromContent = fromScene, toContent = overlay),
                        key = result.transitionKey,
                        replacedTransition = null,
                    )
                    .swipeAnimation
            }
            is UserActionResult.HideOverlay -> {
                val toScene = layoutImpl.scene(layoutState.currentScene)
                val overlay = layoutImpl.overlay(result.overlay)
                ShowOrHideOverlaySwipeTransition(
                        layoutState = layoutState,
                        _fromOrToScene = toScene,
                        _overlay = overlay,
                        swipeAnimation = swipeAnimation(fromContent = overlay, toContent = toScene),
                        key = result.transitionKey,
                        replacedTransition = null,
                    )
                    .swipeAnimation
            }
            is UserActionResult.ReplaceByOverlay -> {
                val fromOverlay = layoutImpl.contentForUserActions() as Overlay
                val toOverlay = layoutImpl.overlay(result.overlay)
                ReplaceOverlaySwipeTransition(
                        layoutState = layoutState,
                        swipeAnimation =
                            swipeAnimation(fromContent = fromOverlay, toContent = toOverlay),
                        key = result.transitionKey,
                        replacedTransition = null,
                    )
                    .swipeAnimation
            }
        }
    }

    private fun createSwipeAnimation(old: SwipeAnimation<*>): SwipeAnimation<*> {
        return when (val transition = old.contentTransition) {
            is TransitionState.Transition.ChangeCurrentScene -> {
                ChangeCurrentSceneSwipeTransition(transition as ChangeCurrentSceneSwipeTransition)
                    .swipeAnimation
            }
            is TransitionState.Transition.ShowOrHideOverlay -> {
                ShowOrHideOverlaySwipeTransition(transition as ShowOrHideOverlaySwipeTransition)
                    .swipeAnimation
            }
            is TransitionState.Transition.ReplaceOverlay -> {
                ReplaceOverlaySwipeTransition(transition as ReplaceOverlaySwipeTransition)
                    .swipeAnimation
            }
        }
    }

    private fun computeSwipes(startedPosition: Offset?, pointersDown: Int): Swipes {
        val fromSource =
            startedPosition?.let { position ->
                layoutImpl.swipeSourceDetector
                    .source(
                        layoutImpl.lastSize,
                        position.round(),
                        layoutImpl.density,
                        orientation,
                    )
                    ?.resolve(layoutImpl.layoutDirection)
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
            layoutState.startTransition(newTransition.contentTransition)
        }

        val previous = swipeAnimation
        swipeAnimation = newTransition

        // Finish the previous transition.
        if (previous != newTransition) {
            layoutState.finishTransition(previous.contentTransition)
        }
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

    private fun <T : Content> onDrag(delta: Float, swipeAnimation: SwipeAnimation<T>): Float {
        if (delta == 0f || !isDrivingTransition || swipeAnimation.isFinishing) {
            return 0f
        }

        val toContent = swipeAnimation.toContent
        val distance = swipeAnimation.distance()
        val previousOffset = swipeAnimation.dragOffset
        val desiredOffset = previousOffset + delta

        fun hasReachedToSceneUpOrLeft() =
            distance < 0 &&
                desiredOffset <= distance &&
                swipes.upOrLeftResult?.toContent(layoutState.currentScene) == toContent.key

        fun hasReachedToSceneDownOrRight() =
            distance > 0 &&
                desiredOffset >= distance &&
                swipes.downOrRightResult?.toContent(layoutState.currentScene) == toContent.key

        // Considering accelerated swipe: Change fromContent in the case where the user quickly
        // swiped multiple times in the same direction to accelerate the transition from A => B then
        // B => C.
        //
        // TODO(b/290184746): the second drag needs to pass B to work. Add support for flinging
        //  twice before B has been reached
        val hasReachedToContent =
            swipeAnimation.currentContent == toContent &&
                (hasReachedToSceneUpOrLeft() || hasReachedToSceneDownOrRight())

        val fromContent: Content
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

        val result =
            swipes.findUserActionResult(
                fromContent = fromContent,
                directionOffset = newOffset,
                updateSwipesResults = hasReachedToContent
            )

        if (result == null) {
            onStop(velocity = delta, canChangeContent = true)
            return 0f
        }

        val needNewTransition =
            hasReachedToContent ||
                result.toContent(layoutState.currentScene) != swipeAnimation.toContent.key ||
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

    private fun <T : Content> onStop(
        velocity: Float,
        canChangeContent: Boolean,

        // Important: Make sure that this has the same name as [this.swipeAnimation] so that all the
        // code here references the current animation when [onDragStopped] is called, otherwise the
        // callbacks (like onAnimationCompleted()) might incorrectly finish a new transition that
        // replaced this one.
        swipeAnimation: SwipeAnimation<T>,
    ): Float {
        // The state was changed since the drag started; don't do anything.
        if (!isDrivingTransition || swipeAnimation.isFinishing) {
            return 0f
        }

        fun animateTo(targetContent: T, targetOffset: Float) {
            // If the effective current content changed, it should be reflected right now in the
            // current state, even before the settle animation is ongoing. That way all the
            // swipeables and back handlers will be refreshed and the user can for instance quickly
            // swipe vertically from A => B then horizontally from B => C, or swipe from A => B then
            // immediately go back B => A.
            if (targetContent != swipeAnimation.currentContent) {
                swipeAnimation.currentContent = targetContent
            }

            swipeAnimation.animateOffset(
                coroutineScope = draggableHandler.coroutineScope,
                initialVelocity = velocity,
                targetOffset = targetOffset,
                targetContent = targetContent,
            )
        }

        val fromContent = swipeAnimation.fromContent
        if (canChangeContent) {
            // If we are halfway between two contents, we check what the target will be based on the
            // velocity and offset of the transition, then we launch the animation.

            val toContent = swipeAnimation.toContent

            // Compute the destination content (and therefore offset) to settle in.
            val offset = swipeAnimation.dragOffset
            val distance = swipeAnimation.distance()
            var targetContent: Content
            var targetOffset: Float
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
                targetContent = toContent
                targetOffset = distance
            } else {
                targetContent = fromContent
                targetOffset = 0f
            }

            fun shouldChangeContent(): Boolean {
                return when (val transition = swipeAnimation.contentTransition) {
                    is TransitionState.Transition.ChangeCurrentScene ->
                        layoutState.canChangeScene(targetContent.key as SceneKey)
                    is TransitionState.Transition.ShowOrHideOverlay -> {
                        if (targetContent.key == transition.overlay) {
                            layoutState.canShowOverlay(transition.overlay)
                        } else {
                            layoutState.canHideOverlay(transition.overlay)
                        }
                    }
                    is TransitionState.Transition.ReplaceOverlay -> {
                        val to = targetContent.key as OverlayKey
                        val from =
                            if (to == transition.toOverlay) transition.fromOverlay
                            else transition.toOverlay
                        layoutState.canReplaceOverlay(from, to)
                    }
                }
            }

            if (targetContent != swipeAnimation.currentContent && !shouldChangeContent()) {
                // We wanted to change to a new scene but we are not allowed to, so we animate back
                // to the current scene.
                targetContent = swipeAnimation.currentContent
                targetOffset =
                    if (targetContent == fromContent) {
                        0f
                    } else {
                        check(distance != DistanceUnspecified) {
                            "distance is equal to $DistanceUnspecified"
                        }
                        distance
                    }
            }

            animateTo(targetContent = targetContent, targetOffset = targetOffset)
        } else {
            // We are doing an overscroll preview animation between scenes.
            check(fromContent == swipeAnimation.currentContent) {
                "canChangeContent is false but currentContent != fromContent"
            }
            animateTo(targetContent = fromContent, targetOffset = 0f)
        }

        // The onStop animation consumes any remaining velocity.
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
}

private class ChangeCurrentSceneSwipeTransition(
    val layoutState: MutableSceneTransitionLayoutStateImpl,
    val swipeAnimation: SwipeAnimation<Scene>,
    override val key: TransitionKey?,
    replacedTransition: ChangeCurrentSceneSwipeTransition?,
) :
    TransitionState.Transition.ChangeCurrentScene(
        swipeAnimation.fromContent.key,
        swipeAnimation.toContent.key,
        replacedTransition,
    ),
    TransitionState.HasOverscrollProperties by swipeAnimation {

    constructor(
        other: ChangeCurrentSceneSwipeTransition
    ) : this(
        layoutState = other.layoutState,
        swipeAnimation = SwipeAnimation(other.swipeAnimation),
        key = other.key,
        replacedTransition = other,
    )

    init {
        swipeAnimation.contentTransition = this
    }

    override val currentScene: SceneKey
        get() = swipeAnimation.currentContent.key

    override val progress: Float
        get() = swipeAnimation.progress

    override val progressVelocity: Float
        get() = swipeAnimation.progressVelocity

    override val isInitiatedByUserInput: Boolean = true

    override val isUserInputOngoing: Boolean
        get() = swipeAnimation.isUserInputOngoing

    override fun finish(): Job = swipeAnimation.finish()
}

private class ShowOrHideOverlaySwipeTransition(
    val layoutState: MutableSceneTransitionLayoutStateImpl,
    val swipeAnimation: SwipeAnimation<Content>,
    val _overlay: Overlay,
    val _fromOrToScene: Scene,
    override val key: TransitionKey?,
    replacedTransition: ShowOrHideOverlaySwipeTransition?,
) :
    TransitionState.Transition.ShowOrHideOverlay(
        _overlay.key,
        _fromOrToScene.key,
        swipeAnimation.fromContent.key,
        swipeAnimation.toContent.key,
        replacedTransition,
    ),
    TransitionState.HasOverscrollProperties by swipeAnimation {
    constructor(
        other: ShowOrHideOverlaySwipeTransition
    ) : this(
        layoutState = other.layoutState,
        swipeAnimation = SwipeAnimation(other.swipeAnimation),
        _overlay = other._overlay,
        _fromOrToScene = other._fromOrToScene,
        key = other.key,
        replacedTransition = other,
    )

    init {
        swipeAnimation.contentTransition = this
    }

    override val isEffectivelyShown: Boolean
        get() = swipeAnimation.currentContent == _overlay

    override val progress: Float
        get() = swipeAnimation.progress

    override val progressVelocity: Float
        get() = swipeAnimation.progressVelocity

    override val isInitiatedByUserInput: Boolean = true

    override val isUserInputOngoing: Boolean
        get() = swipeAnimation.isUserInputOngoing

    override fun finish(): Job = swipeAnimation.finish()
}

private class ReplaceOverlaySwipeTransition(
    val layoutState: MutableSceneTransitionLayoutStateImpl,
    val swipeAnimation: SwipeAnimation<Overlay>,
    override val key: TransitionKey?,
    replacedTransition: ReplaceOverlaySwipeTransition?,
) :
    TransitionState.Transition.ReplaceOverlay(
        swipeAnimation.fromContent.key,
        swipeAnimation.toContent.key,
        replacedTransition,
    ),
    TransitionState.HasOverscrollProperties by swipeAnimation {
    constructor(
        other: ReplaceOverlaySwipeTransition
    ) : this(
        layoutState = other.layoutState,
        swipeAnimation = SwipeAnimation(other.swipeAnimation),
        key = other.key,
        replacedTransition = other,
    )

    init {
        swipeAnimation.contentTransition = this
    }

    override val effectivelyShownOverlay: OverlayKey
        get() = swipeAnimation.currentContent.key

    override val progress: Float
        get() = swipeAnimation.progress

    override val progressVelocity: Float
        get() = swipeAnimation.progressVelocity

    override val isInitiatedByUserInput: Boolean = true

    override val isUserInputOngoing: Boolean
        get() = swipeAnimation.isUserInputOngoing

    override fun finish(): Job = swipeAnimation.finish()
}

/** A helper class that contains the main logic for swipe transitions. */
internal class SwipeAnimation<T : Content>(
    val layoutImpl: SceneTransitionLayoutImpl,
    val fromContent: T,
    val toContent: T,
    private val userActionDistanceScope: UserActionDistanceScope,
    override val orientation: Orientation,
    override val isUpOrLeft: Boolean,
    val requiresFullDistanceSwipe: Boolean,
    private var lastDistance: Float = DistanceUnspecified,
    currentContent: T = fromContent,
    dragOffset: Float = 0f,
) : TransitionState.HasOverscrollProperties {
    /** The [TransitionState.Transition] whose implementation delegates to this [SwipeAnimation]. */
    lateinit var contentTransition: TransitionState.Transition

    var currentContent by mutableStateOf(currentContent)

    val progress: Float
        get() {
            // Important: If we are going to return early because distance is equal to 0, we should
            // still make sure we read the offset before returning so that the calling code still
            // subscribes to the offset value.
            val offset = offsetAnimation?.animatable?.value ?: dragOffset

            return computeProgress(offset)
        }

    fun computeProgress(offset: Float): Float {
        val distance = distance()
        if (distance == DistanceUnspecified) {
            return 0f
        }
        return offset / distance
    }

    val progressVelocity: Float
        get() {
            val animatable = offsetAnimation?.animatable ?: return 0f
            val distance = distance()
            if (distance == DistanceUnspecified) {
                return 0f
            }

            val velocityInDistanceUnit = animatable.velocity
            return velocityInDistanceUnit / distance.absoluteValue
        }

    override var bouncingContent: ContentKey? = null

    /** The current offset caused by the drag gesture. */
    var dragOffset by mutableFloatStateOf(dragOffset)

    /** The offset animation that animates the offset once the user lifts their finger. */
    private var offsetAnimation: OffsetAnimation? by mutableStateOf(null)

    val isUserInputOngoing: Boolean
        get() = offsetAnimation == null

    override val overscrollScope: OverscrollScope =
        object : OverscrollScope {
            override val density: Float
                get() = layoutImpl.density.density

            override val fontScale: Float
                get() = layoutImpl.density.fontScale

            override val absoluteDistance: Float
                get() = distance().absoluteValue
        }

    /** Whether [finish] was called on this animation. */
    var isFinishing = false
        private set

    constructor(
        other: SwipeAnimation<T>
    ) : this(
        layoutImpl = other.layoutImpl,
        fromContent = other.fromContent,
        toContent = other.toContent,
        userActionDistanceScope = other.userActionDistanceScope,
        orientation = other.orientation,
        isUpOrLeft = other.isUpOrLeft,
        requiresFullDistanceSwipe = other.requiresFullDistanceSwipe,
        lastDistance = other.lastDistance,
        currentContent = other.currentContent,
        dragOffset = other.dragOffset,
    )

    /**
     * The signed distance between [fromContent] and [toContent]. It is negative if [fromContent] is
     * above or to the left of [toContent].
     *
     * Note that this distance can be equal to [DistanceUnspecified] during the first frame of a
     * transition when the distance depends on the size or position of an element that is composed
     * in the content we are going to.
     */
    fun distance(): Float {
        if (lastDistance != DistanceUnspecified) {
            return lastDistance
        }

        val absoluteDistance =
            with(contentTransition.transformationSpec.distance ?: DefaultSwipeDistance) {
                userActionDistanceScope.absoluteDistance(
                    fromContent.targetSize,
                    orientation,
                )
            }

        if (absoluteDistance <= 0f) {
            return DistanceUnspecified
        }

        val distance = if (isUpOrLeft) -absoluteDistance else absoluteDistance
        lastDistance = distance
        return distance
    }

    /** Ends any previous [offsetAnimation] and runs the new [animation]. */
    private fun startOffsetAnimation(animation: () -> OffsetAnimation): OffsetAnimation {
        cancelOffsetAnimation()
        return animation().also { offsetAnimation = it }
    }

    /** Cancel any ongoing offset animation. */
    // TODO(b/317063114) This should be a suspended function to avoid multiple jobs running at
    // the same time.
    fun cancelOffsetAnimation() {
        val animation = offsetAnimation ?: return
        offsetAnimation = null

        dragOffset = animation.animatable.value
        animation.job.cancel()
    }

    fun animateOffset(
        // TODO(b/317063114) The CoroutineScope should be removed.
        coroutineScope: CoroutineScope,
        initialVelocity: Float,
        targetOffset: Float,
        targetContent: T,
    ): OffsetAnimation {
        val initialProgress = progress
        // Skip the animation if we have already reached the target content and the overscroll does
        // not animate anything.
        val hasReachedTargetContent =
            (targetContent == toContent && initialProgress >= 1f) ||
                (targetContent == fromContent && initialProgress <= 0f)
        val skipAnimation =
            hasReachedTargetContent && !contentTransition.isWithinProgressRange(initialProgress)

        return startOffsetAnimation {
            val animatable = Animatable(dragOffset, OffsetVisibilityThreshold)
            val isTargetGreater = targetOffset > animatable.value
            val startedWhenOvercrollingTargetContent =
                if (targetContent == fromContent) initialProgress < 0f else initialProgress > 1f
            val job =
                coroutineScope
                    // Important: We start atomically to make sure that we start the coroutine even
                    // if it is cancelled right after it is launched, so that snapToContent() is
                    // correctly called. Otherwise, this transition will never be stopped and we
                    // will never settle to Idle.
                    .launch(start = CoroutineStart.ATOMIC) {
                        // TODO(b/327249191): Refactor the code so that we don't even launch a
                        // coroutine if we don't need to animate.
                        if (skipAnimation) {
                            snapToContent(targetContent)
                            dragOffset = targetOffset
                            return@launch
                        }

                        try {
                            val swipeSpec =
                                contentTransition.transformationSpec.swipeSpec
                                    ?: layoutImpl.state.transitions.defaultSwipeSpec
                            animatable.animateTo(
                                targetValue = targetOffset,
                                animationSpec = swipeSpec,
                                initialVelocity = initialVelocity,
                            ) {
                                if (bouncingContent == null) {
                                    val isBouncing =
                                        if (isTargetGreater) {
                                            if (startedWhenOvercrollingTargetContent) {
                                                value >= targetOffset
                                            } else {
                                                value > targetOffset
                                            }
                                        } else {
                                            if (startedWhenOvercrollingTargetContent) {
                                                value <= targetOffset
                                            } else {
                                                value < targetOffset
                                            }
                                        }

                                    if (isBouncing) {
                                        bouncingContent = targetContent.key

                                        // Immediately stop this transition if we are bouncing on a
                                        // content that does not bounce.
                                        if (!contentTransition.isWithinProgressRange(progress)) {
                                            snapToContent(targetContent)
                                        }
                                    }
                                }
                            }
                        } finally {
                            snapToContent(targetContent)
                        }
                    }

            OffsetAnimation(animatable, job)
        }
    }

    fun snapToContent(content: T) {
        cancelOffsetAnimation()
        check(currentContent == content)
        layoutImpl.state.finishTransition(contentTransition)
    }

    fun finish(): Job {
        if (isFinishing) return requireNotNull(offsetAnimation).job
        isFinishing = true

        // If we were already animating the offset, simply return the job.
        offsetAnimation?.let {
            return it.job
        }

        // Animate to the current content.
        val targetContent = currentContent
        val targetOffset =
            if (targetContent == fromContent) {
                0f
            } else {
                val distance = distance()
                check(distance != DistanceUnspecified) {
                    "targetContent != fromContent but distance is unspecified"
                }
                distance
            }

        val animation =
            animateOffset(
                coroutineScope = layoutImpl.coroutineScope,
                initialVelocity = 0f,
                targetOffset = targetOffset,
                targetContent = targetContent,
            )
        check(offsetAnimation == animation)
        return animation.job
    }

    internal class OffsetAnimation(
        /** The animatable used to animate the offset. */
        val animatable: Animatable<Float, AnimationVector1D>,

        /** The job in which [animatable] is animated. */
        val job: Job,
    )
}

private object DefaultSwipeDistance : UserActionDistance {
    override fun UserActionDistanceScope.absoluteDistance(
        fromSceneSize: IntSize,
        orientation: Orientation,
    ): Float {
        return when (orientation) {
            Orientation.Horizontal -> fromSceneSize.width
            Orientation.Vertical -> fromSceneSize.height
        }.toFloat()
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

    fun updateSwipesResults(fromContent: Content) {
        val (upOrLeftResult, downOrRightResult) = computeSwipesResults(fromContent)

        this.upOrLeftResult = upOrLeftResult
        this.downOrRightResult = downOrRightResult
    }

    /**
     * Returns the [UserActionResult] from [fromContent] in the direction of [directionOffset].
     *
     * @param fromContent the content from which we look for the target
     * @param directionOffset signed float that indicates the direction. Positive is down or right
     *   negative is up or left.
     * @param updateSwipesResults whether the swipe results should be updated to the current values
     *   held in the user actions map. Usually we don't want to update them while doing a drag,
     *   because this could change the target content (jump cutting) to a different content, when
     *   some system state changed the targets the background. However, an update is needed any time
     *   we calculate the targets for a new fromContent.
     * @return null when there are no targets in either direction. If one direction is null and you
     *   drag into the null direction this function will return the opposite direction, assuming
     *   that the users intention is to start the drag into the other direction eventually. If
     *   [directionOffset] is 0f and both direction are available, it will default to
     *   [upOrLeftResult].
     */
    fun findUserActionResult(
        fromContent: Content,
        directionOffset: Float,
        updateSwipesResults: Boolean,
    ): UserActionResult? {
        if (updateSwipesResults) {
            updateSwipesResults(fromContent)
        }

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
