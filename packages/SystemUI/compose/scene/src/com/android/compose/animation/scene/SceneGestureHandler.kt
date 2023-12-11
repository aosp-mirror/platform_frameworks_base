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

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.android.compose.nestedscroll.PriorityNestedScrollConnection
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class SceneGestureHandler(
    internal val layoutImpl: SceneTransitionLayoutImpl,
    internal val orientation: Orientation,
    private val coroutineScope: CoroutineScope,
) {
    val draggable: DraggableHandler = SceneDraggableHandler(this)

    internal var transitionState
        get() = layoutImpl.state.transitionState
        set(value) {
            layoutImpl.state.transitionState = value
        }

    internal var swipeTransition: SwipeTransition = SwipeTransition(currentScene, currentScene, 1f)
        private set

    private fun updateTransition(newTransition: SwipeTransition, force: Boolean = false) {
        if (isDrivingTransition || force) transitionState = newTransition
        swipeTransition = newTransition
    }

    internal val currentScene: Scene
        get() = layoutImpl.scene(transitionState.currentScene)

    internal val isDrivingTransition
        get() = transitionState == swipeTransition

    /**
     * The velocity threshold at which the intent of the user is to swipe up or down. It is the same
     * as SwipeableV2Defaults.VelocityThreshold.
     */
    internal val velocityThreshold = with(layoutImpl.density) { 125.dp.toPx() }

    /**
     * The positional threshold at which the intent of the user is to swipe to the next scene. It is
     * the same as SwipeableV2Defaults.PositionalThreshold.
     */
    private val positionalThreshold = with(layoutImpl.density) { 56.dp.toPx() }

    internal var gestureWithPriority: Any? = null

    /** The [UserAction]s associated to the current swipe. */
    private var actionUpOrLeft: UserAction? = null
    private var actionDownOrRight: UserAction? = null
    private var actionUpOrLeftNoEdge: UserAction? = null
    private var actionDownOrRightNoEdge: UserAction? = null

    internal fun onDragStarted(pointersDown: Int, startedPosition: Offset?, overSlop: Float) {
        if (isDrivingTransition) {
            // This [transition] was already driving the animation: simply take over it.
            // Stop animating and start from where the current offset.
            swipeTransition.cancelOffsetAnimation()
            return
        }

        val transition = transitionState
        if (transition is TransitionState.Transition) {
            // TODO(b/290184746): Better handle interruptions here if state != idle.
            Log.w(
                TAG,
                "start from TransitionState.Transition is not fully supported: from" +
                    " ${transition.fromScene} to ${transition.toScene} " +
                    "(progress ${transition.progress})"
            )
        }

        val fromScene = currentScene
        setCurrentActions(fromScene, startedPosition, pointersDown)

        if (fromScene.upOrLeft() == null && fromScene.downOrRight() == null) {
            return
        }

        val (targetScene, distance) = fromScene.findTargetSceneAndDistance(overSlop)

        updateTransition(SwipeTransition(fromScene, targetScene, distance), force = true)
    }

    private fun setCurrentActions(fromScene: Scene, startedPosition: Offset?, pointersDown: Int) {
        val fromEdge =
            startedPosition?.let { position ->
                layoutImpl.edgeDetector.edge(
                    fromScene.targetSize,
                    position.round(),
                    layoutImpl.density,
                    orientation,
                )
            }

        val upOrLeft =
            Swipe(
                direction =
                    when (orientation) {
                        Orientation.Horizontal -> SwipeDirection.Left
                        Orientation.Vertical -> SwipeDirection.Up
                    },
                pointerCount = pointersDown,
                fromEdge = fromEdge,
            )

        val downOrRight =
            Swipe(
                direction =
                    when (orientation) {
                        Orientation.Horizontal -> SwipeDirection.Right
                        Orientation.Vertical -> SwipeDirection.Down
                    },
                pointerCount = pointersDown,
                fromEdge = fromEdge,
            )

        if (fromEdge == null) {
            actionUpOrLeft = null
            actionDownOrRight = null
            actionUpOrLeftNoEdge = upOrLeft
            actionDownOrRightNoEdge = downOrRight
        } else {
            actionUpOrLeft = upOrLeft
            actionDownOrRight = downOrRight
            actionUpOrLeftNoEdge = upOrLeft.copy(fromEdge = null)
            actionDownOrRightNoEdge = downOrRight.copy(fromEdge = null)
        }
    }

    /**
     * Use the layout size in the swipe orientation for swipe distance.
     *
     * TODO(b/290184746): Also handle custom distances for transitions. With smaller distances, we
     *   will also have to make sure that we correctly handle overscroll.
     */
    private fun Scene.getAbsoluteDistance(): Float {
        return when (orientation) {
            Orientation.Horizontal -> targetSize.width
            Orientation.Vertical -> targetSize.height
        }.toFloat()
    }

    internal fun onDrag(delta: Float) {
        if (delta == 0f || !isDrivingTransition) return
        swipeTransition.dragOffset += delta

        val (fromScene, acceleratedOffset) =
            computeFromSceneConsideringAcceleratedSwipe(swipeTransition)
        swipeTransition.dragOffset += acceleratedOffset

        // Compute the target scene depending on the current offset.
        val (targetScene, distance) =
            fromScene.findTargetSceneAndDistance(swipeTransition.dragOffset)

        // TODO(b/290184746): support long scroll A => B => C? especially for non fullscreen scenes
        if (
            fromScene.key != swipeTransition.fromScene || targetScene.key != swipeTransition.toScene
        ) {
            updateTransition(
                SwipeTransition(fromScene, targetScene, distance).apply {
                    this.dragOffset = swipeTransition.dragOffset
                }
            )
        }
    }

    /**
     * Change fromScene in the case where the user quickly swiped multiple times in the same
     * direction to accelerate the transition from A => B then B => C.
     *
     * @return the new fromScene and a dragOffset to be added in case the scene has changed
     *
     * TODO(b/290184746): the second drag needs to pass B to work. Add support for flinging twice
     *   before B has been reached
     */
    private inline fun computeFromSceneConsideringAcceleratedSwipe(
        swipeTransition: SwipeTransition,
    ): Pair<Scene, Float> {
        val toScene = swipeTransition._toScene
        val fromScene = swipeTransition._fromScene
        val absoluteDistance = swipeTransition.distance.absoluteValue

        // If the swipe was not committed, don't do anything.
        if (fromScene == toScene || swipeTransition._currentScene != toScene) {
            return Pair(fromScene, 0f)
        }

        // If the offset is past the distance then let's change fromScene so that the user can swipe
        // to the next screen or go back to the previous one.
        val offset = swipeTransition.dragOffset
        return if (offset <= -absoluteDistance && fromScene.upOrLeft() == toScene.key) {
            Pair(toScene, absoluteDistance)
        } else if (offset >= absoluteDistance && fromScene.downOrRight() == toScene.key) {
            Pair(toScene, -absoluteDistance)
        } else {
            Pair(fromScene, 0f)
        }
    }

    // TODO(b/290184746): there are two bugs here:
    // 1. if both upOrLeft and downOrRight become `null` during a transition this will crash
    // 2. if one of them changes during a transition, the transition will jump cut to the new target
    private inline fun Scene.findTargetSceneAndDistance(
        directionOffset: Float
    ): Pair<Scene, Float> {
        val upOrLeft = upOrLeft()
        val downOrRight = downOrRight()
        val absoluteDistance = getAbsoluteDistance()

        // Compute the target scene depending on the current offset.
        return if ((directionOffset < 0f && upOrLeft != null) || downOrRight == null) {
            Pair(layoutImpl.scene(upOrLeft!!), -absoluteDistance)
        } else {
            Pair(layoutImpl.scene(downOrRight), absoluteDistance)
        }
    }

    private fun Scene.upOrLeft(): SceneKey? {
        return userActions[actionUpOrLeft] ?: userActions[actionUpOrLeftNoEdge]
    }

    private fun Scene.downOrRight(): SceneKey? {
        return userActions[actionDownOrRight] ?: userActions[actionDownOrRightNoEdge]
    }

    internal fun onDragStopped(velocity: Float, canChangeScene: Boolean) {
        // The state was changed since the drag started; don't do anything.
        if (!isDrivingTransition) {
            return
        }

        fun animateTo(targetScene: Scene, targetOffset: Float) {
            // If the effective current scene changed, it should be reflected right now in the
            // current scene state, even before the settle animation is ongoing. That way all the
            // swipeables and back handlers will be refreshed and the user can for instance quickly
            // swipe vertically from A => B then horizontally from B => C, or swipe from A => B then
            // immediately go back B => A.
            if (targetScene != swipeTransition._currentScene) {
                swipeTransition._currentScene = targetScene
                layoutImpl.onChangeScene(targetScene.key)
            }

            animateOffset(
                initialVelocity = velocity,
                targetOffset = targetOffset,
                targetScene = targetScene.key
            )
        }

        val fromScene = swipeTransition._fromScene
        if (canChangeScene) {
            // If we are halfway between two scenes, we check what the target will be based on the
            // velocity and offset of the transition, then we launch the animation.

            val toScene = swipeTransition._toScene

            // Compute the destination scene (and therefore offset) to settle in.
            val offset = swipeTransition.dragOffset
            val distance = swipeTransition.distance
            if (
                shouldCommitSwipe(
                    offset,
                    distance,
                    velocity,
                    wasCommitted = swipeTransition._currentScene == toScene,
                )
            ) {
                // Animate to the next scene
                animateTo(targetScene = toScene, targetOffset = distance)
            } else {
                // Animate to the initial scene
                animateTo(targetScene = fromScene, targetOffset = 0f)
            }
        } else {
            // We are doing an overscroll animation between scenes. In this case, we can also start
            // from the idle position.

            val startFromIdlePosition = swipeTransition.dragOffset == 0f

            if (startFromIdlePosition) {
                // If there is a next scene, we start the overscroll animation.
                val (targetScene, distance) = fromScene.findTargetSceneAndDistance(velocity)
                val isValidTarget = distance != 0f && targetScene.key != fromScene.key
                if (isValidTarget) {
                    updateTransition(
                        SwipeTransition(fromScene, targetScene, distance).apply {
                            _currentScene = swipeTransition._currentScene
                        }
                    )
                    animateTo(targetScene = fromScene, targetOffset = 0f)
                } else {
                    // We will not animate
                    transitionState = TransitionState.Idle(fromScene.key)
                }
            } else {
                // We were between two scenes: animate to the initial scene.
                animateTo(targetScene = fromScene, targetOffset = 0f)
            }
        }
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
    ): Boolean {
        fun isCloserToTarget(): Boolean {
            return (offset - distance).absoluteValue < offset.absoluteValue
        }

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

    private fun animateOffset(
        initialVelocity: Float,
        targetOffset: Float,
        targetScene: SceneKey,
    ) {
        swipeTransition.startOffsetAnimation {
            coroutineScope.launch {
                if (!swipeTransition.isAnimatingOffset) {
                    swipeTransition.offsetAnimatable.snapTo(swipeTransition.dragOffset)
                }
                swipeTransition.isAnimatingOffset = true

                swipeTransition.offsetAnimatable.animateTo(
                    targetOffset,
                    // TODO(b/290184746): Make this spring spec configurable.
                    spring(
                        stiffness = Spring.StiffnessMediumLow,
                        visibilityThreshold = OffsetVisibilityThreshold
                    ),
                    initialVelocity = initialVelocity,
                )

                swipeTransition.finishOffsetAnimation()

                // Now that the animation is done, the state should be idle. Note that if the state
                // was changed since this animation started, some external code changed it and we
                // shouldn't do anything here. Note also that this job will be cancelled in the case
                // where the user intercepts this swipe.
                if (isDrivingTransition) {
                    transitionState = TransitionState.Idle(targetScene)
                }
            }
        }
    }

    internal class SwipeTransition(
        val _fromScene: Scene,
        val _toScene: Scene,
        /**
         * The signed distance between [fromScene] and [toScene]. It is negative if [fromScene] is
         * above or to the left of [toScene].
         */
        val distance: Float
    ) : TransitionState.Transition {
        var _currentScene by mutableStateOf(_fromScene)
        override val currentScene: SceneKey
            get() = _currentScene.key

        override val fromScene: SceneKey = _fromScene.key

        override val toScene: SceneKey = _toScene.key

        override val progress: Float
            get() {
                val offset = if (isAnimatingOffset) offsetAnimatable.value else dragOffset
                return offset / distance
            }

        override val isInitiatedByUserInput = true

        /** The current offset caused by the drag gesture. */
        var dragOffset by mutableFloatStateOf(0f)

        /**
         * Whether the offset is animated (the user lifted their finger) or if it is driven by
         * gesture.
         */
        var isAnimatingOffset by mutableStateOf(false)

        // If we are not animating offset, it means the offset is being driven by the user's finger.
        override val isUserInputOngoing: Boolean
            get() = !isAnimatingOffset

        /** The animatable used to animate the offset once the user lifted its finger. */
        val offsetAnimatable = Animatable(0f, OffsetVisibilityThreshold)

        /** Job to check that there is at most one offset animation in progress. */
        private var offsetAnimationJob: Job? = null

        /** Ends any previous [offsetAnimationJob] and runs the new [job]. */
        fun startOffsetAnimation(job: () -> Job) {
            cancelOffsetAnimation()
            offsetAnimationJob = job()
        }

        /** Cancel any ongoing offset animation. */
        fun cancelOffsetAnimation() {
            offsetAnimationJob?.cancel()
            finishOffsetAnimation()
        }

        fun finishOffsetAnimation() {
            if (isAnimatingOffset) {
                isAnimatingOffset = false
                dragOffset = offsetAnimatable.value
            }
        }
    }

    companion object {
        private const val TAG = "SceneGestureHandler"
    }
}

private class SceneDraggableHandler(
    private val gestureHandler: SceneGestureHandler,
) : DraggableHandler {
    override fun onDragStarted(startedPosition: Offset, overSlop: Float, pointersDown: Int) {
        gestureHandler.gestureWithPriority = this
        gestureHandler.onDragStarted(pointersDown, startedPosition, overSlop)
    }

    override fun onDelta(pixels: Float) {
        if (gestureHandler.gestureWithPriority == this) {
            gestureHandler.onDrag(delta = pixels)
        }
    }

    override fun onDragStopped(velocity: Float) {
        if (gestureHandler.gestureWithPriority == this) {
            gestureHandler.gestureWithPriority = null
            gestureHandler.onDragStopped(velocity = velocity, canChangeScene = true)
        }
    }
}

internal class SceneNestedScrollHandler(
        private val gestureHandler: SceneGestureHandler,
        private val topOrLeftBehavior: NestedScrollBehavior,
        private val bottomOrRightBehavior: NestedScrollBehavior,
) : NestedScrollHandler {
    override val connection: PriorityNestedScrollConnection = nestedScrollConnection()

    private fun nestedScrollConnection(): PriorityNestedScrollConnection {
        // If we performed a long gesture before entering priority mode, we would have to avoid
        // moving on to the next scene.
        var canChangeScene = false

        val actionUpOrLeft =
            Swipe(
                direction =
                    when (gestureHandler.orientation) {
                        Orientation.Horizontal -> SwipeDirection.Left
                        Orientation.Vertical -> SwipeDirection.Up
                    },
                pointerCount = 1,
            )

        val actionDownOrRight =
            Swipe(
                direction =
                    when (gestureHandler.orientation) {
                        Orientation.Horizontal -> SwipeDirection.Right
                        Orientation.Vertical -> SwipeDirection.Down
                    },
                pointerCount = 1,
            )

        fun hasNextScene(amount: Float): Boolean {
            val fromScene = gestureHandler.currentScene
            val nextScene =
                when {
                    amount < 0f -> fromScene.userActions[actionUpOrLeft]
                    amount > 0f -> fromScene.userActions[actionDownOrRight]
                    else -> null
                }
            return nextScene != null
        }

        return PriorityNestedScrollConnection(
            orientation = gestureHandler.orientation,
            canStartPreScroll = { offsetAvailable, offsetBeforeStart ->
                canChangeScene = offsetBeforeStart == 0f

                val canInterceptSwipeTransition =
                    canChangeScene && gestureHandler.isDrivingTransition && offsetAvailable != 0f
                if (!canInterceptSwipeTransition) return@PriorityNestedScrollConnection false

                val progress = gestureHandler.swipeTransition.progress
                val threshold = gestureHandler.layoutImpl.transitionInterceptionThreshold
                fun isProgressCloseTo(value: Float) = (progress - value).absoluteValue <= threshold

                // The transition is always between 0 and 1. If it is close to either of these
                // intervals, we want to go directly to the TransitionState.Idle.
                // The progress value can go beyond this range in the case of overscroll.
                val shouldSnapToIdle = isProgressCloseTo(0f) || isProgressCloseTo(1f)
                if (shouldSnapToIdle) {
                    gestureHandler.swipeTransition.cancelOffsetAnimation()
                    gestureHandler.transitionState =
                        TransitionState.Idle(gestureHandler.swipeTransition.currentScene)
                }

                // Start only if we cannot consume this event
                !shouldSnapToIdle
            },
            canStartPostScroll = { offsetAvailable, offsetBeforeStart ->
                val behavior: NestedScrollBehavior =
                    when {
                        offsetAvailable > 0f -> topOrLeftBehavior
                        offsetAvailable < 0f -> bottomOrRightBehavior
                        else -> return@PriorityNestedScrollConnection false
                    }

                val isZeroOffset = offsetBeforeStart == 0f

                when (behavior) {
                    NestedScrollBehavior.DuringTransitionBetweenScenes -> {
                        canChangeScene = false // unused: added for consistency
                        false
                    }
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
                behavior.canStartOnPostFling && hasNextScene(velocityAvailable)
            },
            canContinueScroll = { true },
            canScrollOnFling = false,
            onStart = { offsetAvailable ->
                gestureHandler.gestureWithPriority = this
                gestureHandler.onDragStarted(
                    pointersDown = 1,
                    startedPosition = null,
                    overSlop = offsetAvailable,
                )
            },
            onScroll = { offsetAvailable ->
                if (gestureHandler.gestureWithPriority != this) {
                    return@PriorityNestedScrollConnection 0f
                }

                // TODO(b/297842071) We should handle the overscroll or slow drag if the gesture is
                // initiated in a nested child.
                gestureHandler.onDrag(offsetAvailable)

                offsetAvailable
            },
            onStop = { velocityAvailable ->
                if (gestureHandler.gestureWithPriority != this) {
                    return@PriorityNestedScrollConnection 0f
                }

                gestureHandler.onDragStopped(
                    velocity = velocityAvailable,
                    canChangeScene = canChangeScene
                )

                // The onDragStopped animation consumes any remaining velocity.
                velocityAvailable
            },
        )
    }
}

/**
 * The number of pixels below which there won't be a visible difference in the transition and from
 * which the animation can stop.
 */
private const val OffsetVisibilityThreshold = 0.5f
