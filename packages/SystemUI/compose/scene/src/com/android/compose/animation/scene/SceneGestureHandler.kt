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

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.android.compose.nestedscroll.PriorityNestedScrollConnection
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@VisibleForTesting
class SceneGestureHandler(
    private val layoutImpl: SceneTransitionLayoutImpl,
    internal val orientation: Orientation,
    private val coroutineScope: CoroutineScope,
) {
    val draggable: DraggableHandler = SceneDraggableHandler(this)

    private var transitionState
        get() = layoutImpl.state.transitionState
        set(value) {
            layoutImpl.state.transitionState = value
        }

    /**
     * The transition controlled by this gesture handler. It will be set as the [transitionState] in
     * the [SceneTransitionLayoutImpl] whenever this handler is driving the current transition.
     *
     * Note: the initialScene here does not matter, it's only used for initializing the transition
     * and will be replaced when a drag event starts.
     */
    private val swipeTransition = SwipeTransition(initialScene = currentScene)

    internal val currentScene: Scene
        get() = layoutImpl.scene(transitionState.currentScene)

    @VisibleForTesting
    val isDrivingTransition
        get() = transitionState == swipeTransition

    @VisibleForTesting
    var isAnimatingOffset
        get() = swipeTransition.isAnimatingOffset
        private set(value) {
            swipeTransition.isAnimatingOffset = value
        }

    internal val swipeTransitionToScene
        get() = swipeTransition._toScene

    /**
     * The velocity threshold at which the intent of the user is to swipe up or down. It is the same
     * as SwipeableV2Defaults.VelocityThreshold.
     */
    @VisibleForTesting val velocityThreshold = with(layoutImpl.density) { 125.dp.toPx() }

    /**
     * The positional threshold at which the intent of the user is to swipe to the next scene. It is
     * the same as SwipeableV2Defaults.PositionalThreshold.
     */
    private val positionalThreshold = with(layoutImpl.density) { 56.dp.toPx() }

    internal var gestureWithPriority: Any? = null

    internal fun onDragStarted(pointersDown: Int, layoutSize: IntSize, startedPosition: Offset?) {
        if (isDrivingTransition) {
            // This [transition] was already driving the animation: simply take over it.
            // Stop animating and start from where the current offset.
            swipeTransition.stopOffsetAnimation()
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

        swipeTransition._currentScene = fromScene
        swipeTransition._fromScene = fromScene

        // We don't know where we are transitioning to yet given that the drag just started, so set
        // it to fromScene, which will effectively be treated the same as Idle(fromScene).
        swipeTransition._toScene = fromScene

        swipeTransition.stopOffsetAnimation()
        swipeTransition.dragOffset = 0f

        // Use the layout size in the swipe orientation for swipe distance.
        // TODO(b/290184746): Also handle custom distances for transitions. With smaller distances,
        // we will also have to make sure that we correctly handle overscroll.
        swipeTransition.absoluteDistance =
            when (orientation) {
                Orientation.Horizontal -> layoutSize.width
                Orientation.Vertical -> layoutSize.height
            }.toFloat()

        val fromEdge =
            startedPosition?.let { position ->
                layoutImpl.edgeDetector.edge(
                    layoutSize,
                    position.round(),
                    layoutImpl.density,
                    orientation,
                )
            }

        swipeTransition.actionUpOrLeft =
            Swipe(
                direction =
                    when (orientation) {
                        Orientation.Horizontal -> SwipeDirection.Left
                        Orientation.Vertical -> SwipeDirection.Up
                    },
                pointerCount = pointersDown,
                fromEdge = fromEdge,
            )

        swipeTransition.actionDownOrRight =
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
            swipeTransition.actionUpOrLeftNoEdge = null
            swipeTransition.actionDownOrRightNoEdge = null
        } else {
            swipeTransition.actionUpOrLeftNoEdge =
                (swipeTransition.actionUpOrLeft as Swipe).copy(fromEdge = null)
            swipeTransition.actionDownOrRightNoEdge =
                (swipeTransition.actionDownOrRight as Swipe).copy(fromEdge = null)
        }

        if (swipeTransition.absoluteDistance > 0f) {
            transitionState = swipeTransition
        }
    }

    internal fun onDrag(delta: Float) {
        if (delta == 0f) return

        swipeTransition.dragOffset += delta

        // First check transition.fromScene should be changed for the case where the user quickly
        // swiped twice in a row to accelerate the transition and go from A => B then B => C really
        // fast.
        maybeHandleAcceleratedSwipe()

        val offset = swipeTransition.dragOffset
        val fromScene = swipeTransition._fromScene

        // Compute the target scene depending on the current offset.
        val target = fromScene.findTargetSceneAndDistance(offset)

        if (swipeTransition._toScene.key != target.sceneKey) {
            swipeTransition._toScene = layoutImpl.scenes.getValue(target.sceneKey)
        }

        if (swipeTransition._distance != target.distance) {
            swipeTransition._distance = target.distance
        }
    }

    /**
     * Change fromScene in the case where the user quickly swiped multiple times in the same
     * direction to accelerate the transition from A => B then B => C.
     */
    private fun maybeHandleAcceleratedSwipe() {
        val toScene = swipeTransition._toScene
        val fromScene = swipeTransition._fromScene

        // If the swipe was not committed, don't do anything.
        if (fromScene == toScene || swipeTransition._currentScene != toScene) {
            return
        }

        // If the offset is past the distance then let's change fromScene so that the user can swipe
        // to the next screen or go back to the previous one.
        val offset = swipeTransition.dragOffset
        val absoluteDistance = swipeTransition.absoluteDistance
        if (offset <= -absoluteDistance && swipeTransition.upOrLeft(fromScene) == toScene.key) {
            swipeTransition.dragOffset += absoluteDistance
            swipeTransition._fromScene = toScene
        } else if (
            offset >= absoluteDistance && swipeTransition.downOrRight(fromScene) == toScene.key
        ) {
            swipeTransition.dragOffset -= absoluteDistance
            swipeTransition._fromScene = toScene
        }

        // Important note: toScene and distance will be updated right after this function is called,
        // using fromScene and dragOffset.
    }

    private class TargetScene(
        val sceneKey: SceneKey,
        val distance: Float,
    )

    private fun Scene.findTargetSceneAndDistance(directionOffset: Float): TargetScene {
        val upOrLeft = swipeTransition.upOrLeft(this)
        val downOrRight = swipeTransition.downOrRight(this)

        // Compute the target scene depending on the current offset.
        return when {
            directionOffset < 0f && upOrLeft != null -> {
                TargetScene(
                    sceneKey = upOrLeft,
                    distance = -swipeTransition.absoluteDistance,
                )
            }
            directionOffset > 0f && downOrRight != null -> {
                TargetScene(
                    sceneKey = downOrRight,
                    distance = swipeTransition.absoluteDistance,
                )
            }
            else -> {
                TargetScene(
                    sceneKey = key,
                    distance = 0f,
                )
            }
        }
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
            if (fromScene == toScene) {
                // We were not animating.
                transitionState = TransitionState.Idle(fromScene.key)
                return
            }

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
                val target = fromScene.findTargetSceneAndDistance(velocity)
                val isValidTarget = target.distance != 0f && target.sceneKey != fromScene.key
                if (isValidTarget) {
                    swipeTransition._toScene = layoutImpl.scene(target.sceneKey)
                    swipeTransition._distance = target.distance

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
                if (!isAnimatingOffset) {
                    swipeTransition.offsetAnimatable.snapTo(swipeTransition.dragOffset)
                }
                isAnimatingOffset = true

                swipeTransition.offsetAnimatable.animateTo(
                    targetOffset,
                    // TODO(b/290184746): Make this spring spec configurable.
                    spring(
                        stiffness = Spring.StiffnessMediumLow,
                        visibilityThreshold = OffsetVisibilityThreshold
                    ),
                    initialVelocity = initialVelocity,
                )

                isAnimatingOffset = false

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

    private class SwipeTransition(initialScene: Scene) : TransitionState.Transition {
        var _currentScene by mutableStateOf(initialScene)
        override val currentScene: SceneKey
            get() = _currentScene.key

        var _fromScene by mutableStateOf(initialScene)
        override val fromScene: SceneKey
            get() = _fromScene.key

        var _toScene by mutableStateOf(initialScene)
        override val toScene: SceneKey
            get() = _toScene.key

        override val progress: Float
            get() {
                val offset = if (isAnimatingOffset) offsetAnimatable.value else dragOffset
                if (distance == 0f) {
                    // This can happen only if fromScene == toScene.
                    error(
                        "Transition.progress should be called only when Transition.fromScene != " +
                            "Transition.toScene"
                    )
                }
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
            stopOffsetAnimation()
            offsetAnimationJob = job()
        }

        /** Stops any ongoing offset animation. */
        fun stopOffsetAnimation() {
            offsetAnimationJob?.cancel()

            if (isAnimatingOffset) {
                isAnimatingOffset = false
                dragOffset = offsetAnimatable.value
            }
        }

        /** The absolute distance between [fromScene] and [toScene]. */
        var absoluteDistance = 0f

        /**
         * The signed distance between [fromScene] and [toScene]. It is negative if [fromScene] is
         * above or to the left of [toScene].
         */
        var _distance by mutableFloatStateOf(0f)
        val distance: Float
            get() = _distance

        /** The [UserAction]s associated to this swipe. */
        var actionUpOrLeft: UserAction = Back
        var actionDownOrRight: UserAction = Back
        var actionUpOrLeftNoEdge: UserAction? = null
        var actionDownOrRightNoEdge: UserAction? = null

        fun upOrLeft(scene: Scene): SceneKey? {
            return scene.userActions[actionUpOrLeft]
                ?: actionUpOrLeftNoEdge?.let { scene.userActions[it] }
        }

        fun downOrRight(scene: Scene): SceneKey? {
            return scene.userActions[actionDownOrRight]
                ?: actionDownOrRightNoEdge?.let { scene.userActions[it] }
        }
    }

    companion object {
        private const val TAG = "SceneGestureHandler"
    }
}

private class SceneDraggableHandler(
    private val gestureHandler: SceneGestureHandler,
) : DraggableHandler {
    override fun onDragStarted(layoutSize: IntSize, startedPosition: Offset, pointersDown: Int) {
        gestureHandler.gestureWithPriority = this
        gestureHandler.onDragStarted(pointersDown, layoutSize, startedPosition)
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

@VisibleForTesting
class SceneNestedScrollHandler(
    private val gestureHandler: SceneGestureHandler,
    private val startBehavior: NestedScrollBehavior,
    private val endBehavior: NestedScrollBehavior,
) : NestedScrollHandler {
    override val connection: PriorityNestedScrollConnection = nestedScrollConnection()

    private fun Offset.toAmount() =
        when (gestureHandler.orientation) {
            Orientation.Horizontal -> x
            Orientation.Vertical -> y
        }

    private fun Velocity.toAmount() =
        when (gestureHandler.orientation) {
            Orientation.Horizontal -> x
            Orientation.Vertical -> y
        }

    private fun Float.toOffset() =
        when (gestureHandler.orientation) {
            Orientation.Horizontal -> Offset(x = this, y = 0f)
            Orientation.Vertical -> Offset(x = 0f, y = this)
        }

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
            canStartPreScroll = { offsetAvailable, offsetBeforeStart ->
                canChangeScene = offsetBeforeStart == Offset.Zero
                gestureHandler.isDrivingTransition &&
                    canChangeScene &&
                    offsetAvailable.toAmount() != 0f
            },
            canStartPostScroll = { offsetAvailable, offsetBeforeStart ->
                val amount = offsetAvailable.toAmount()
                val behavior: NestedScrollBehavior =
                    when {
                        amount > 0 -> startBehavior
                        amount < 0 -> endBehavior
                        else -> return@PriorityNestedScrollConnection false
                    }

                val isZeroOffset = offsetBeforeStart == Offset.Zero

                when (behavior) {
                    NestedScrollBehavior.DuringTransitionBetweenScenes -> {
                        canChangeScene = false // unused: added for consistency
                        false
                    }
                    NestedScrollBehavior.EdgeNoOverscroll -> {
                        canChangeScene = isZeroOffset
                        isZeroOffset && hasNextScene(amount)
                    }
                    NestedScrollBehavior.EdgeWithOverscroll -> {
                        canChangeScene = isZeroOffset
                        hasNextScene(amount)
                    }
                    NestedScrollBehavior.Always -> {
                        canChangeScene = true
                        hasNextScene(amount)
                    }
                }
            },
            canStartPostFling = { velocityAvailable ->
                val amount = velocityAvailable.toAmount()
                val behavior: NestedScrollBehavior =
                    when {
                        amount > 0 -> startBehavior
                        amount < 0 -> endBehavior
                        else -> return@PriorityNestedScrollConnection false
                    }

                // We could start an overscroll animation
                canChangeScene = false
                behavior.canStartOnPostFling && hasNextScene(amount)
            },
            canContinueScroll = { true },
            onStart = {
                gestureHandler.gestureWithPriority = this
                gestureHandler.onDragStarted(
                    pointersDown = 1,
                    layoutSize = gestureHandler.currentScene.targetSize,
                    startedPosition = null,
                )
            },
            onScroll = { offsetAvailable ->
                if (gestureHandler.gestureWithPriority != this) {
                    return@PriorityNestedScrollConnection Offset.Zero
                }

                val amount = offsetAvailable.toAmount()

                // TODO(b/297842071) We should handle the overscroll or slow drag if the gesture is
                // initiated in a nested child.
                gestureHandler.onDrag(amount)

                amount.toOffset()
            },
            onStop = { velocityAvailable ->
                if (gestureHandler.gestureWithPriority != this) {
                    return@PriorityNestedScrollConnection Velocity.Zero
                }

                gestureHandler.onDragStopped(
                    velocity = velocityAvailable.toAmount(),
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
