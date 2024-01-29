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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
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
    private val layoutState = layoutImpl.state
    val draggable: DraggableHandler = SceneDraggableHandler(this)

    private var _swipeTransition: SwipeTransition? = null
    internal var swipeTransition: SwipeTransition
        get() = _swipeTransition ?: error("SwipeTransition needs to be initialized")
        set(value) {
            _swipeTransition = value
        }

    private fun updateTransition(newTransition: SwipeTransition, force: Boolean = false) {
        if (isDrivingTransition || force) layoutState.startTransition(newTransition)
        swipeTransition = newTransition
    }

    internal val isDrivingTransition
        get() = layoutState.transitionState == _swipeTransition

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
    private val positionalThreshold
        get() = with(layoutImpl.density) { 56.dp.toPx() }

    internal var currentSource: Any? = null

    /** The [Swipes] associated to the current gesture. */
    private var swipes: Swipes? = null

    /** The [UserActionResult] associated to up and down swipes. */
    private var upOrLeftResult: UserActionResult? = null
    private var downOrRightResult: UserActionResult? = null

    /**
     * Whether we should immediately intercept a gesture.
     *
     * Note: if this returns true, then [onDragStarted] will be called with overSlop equal to 0f,
     * indicating that the transition should be intercepted.
     */
    internal fun shouldImmediatelyIntercept(startedPosition: Offset?): Boolean {
        // We don't intercept the touch if we are not currently driving the transition.
        if (!isDrivingTransition) {
            return false
        }

        // Only intercept the current transition if one of the 2 swipes results is also a transition
        // between the same pair of scenes.
        val fromScene = swipeTransition._currentScene
        val swipes = computeSwipes(fromScene, startedPosition, pointersDown = 1)
        val (upOrLeft, downOrRight) = computeSwipesResults(fromScene, swipes)
        return (upOrLeft != null &&
            swipeTransition.isTransitioningBetween(fromScene.key, upOrLeft.toScene)) ||
            (downOrRight != null &&
                swipeTransition.isTransitioningBetween(fromScene.key, downOrRight.toScene))
    }

    internal fun onDragStarted(pointersDown: Int, startedPosition: Offset?, overSlop: Float) {
        if (overSlop == 0f) {
            check(isDrivingTransition) {
                "onDragStarted() called while isDrivingTransition=false overSlop=0f"
            }

            // This [transition] was already driving the animation: simply take over it.
            // Stop animating and start from where the current offset.
            swipeTransition.cancelOffsetAnimation()
            updateSwipesResults(swipeTransition._fromScene)
            return
        }

        val transitionState = layoutState.transitionState
        if (transitionState is TransitionState.Transition) {
            // TODO(b/290184746): Better handle interruptions here if state != idle.
            Log.w(
                TAG,
                "start from TransitionState.Transition is not fully supported: from" +
                    " ${transitionState.fromScene} to ${transitionState.toScene} " +
                    "(progress ${transitionState.progress})"
            )
        }

        val fromScene = layoutImpl.scene(transitionState.currentScene)
        updateSwipes(fromScene, startedPosition, pointersDown)

        val (targetScene, distance) =
            findTargetSceneAndDistance(fromScene, overSlop, updateSwipesResults = true) ?: return
        updateTransition(SwipeTransition(fromScene, targetScene, distance), force = true)
    }

    private fun updateSwipes(fromScene: Scene, startedPosition: Offset?, pointersDown: Int) {
        this.swipes = computeSwipes(fromScene, startedPosition, pointersDown)
    }

    private fun computeSwipes(
        fromScene: Scene,
        startedPosition: Offset?,
        pointersDown: Int
    ): Swipes {
        val fromSource =
            startedPosition?.let { position ->
                layoutImpl.swipeSourceDetector.source(
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
                fromSource = fromSource,
            )

        val downOrRight =
            Swipe(
                direction =
                    when (orientation) {
                        Orientation.Horizontal -> SwipeDirection.Right
                        Orientation.Vertical -> SwipeDirection.Down
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

    private fun Scene.getAbsoluteDistance(distance: UserActionDistance?): Float {
        val targetSize = this.targetSize
        return with(distance ?: DefaultSwipeDistance) {
            layoutImpl.density.absoluteDistance(targetSize, orientation)
        }
    }

    internal fun onDrag(delta: Float) {
        if (delta == 0f || !isDrivingTransition) return
        swipeTransition.dragOffset += delta

        val (fromScene, acceleratedOffset) =
            computeFromSceneConsideringAcceleratedSwipe(swipeTransition)

        val isNewFromScene = fromScene.key != swipeTransition.fromScene
        val (targetScene, distance) =
            findTargetSceneAndDistance(
                fromScene,
                swipeTransition.dragOffset,
                updateSwipesResults = isNewFromScene,
            )
                ?: run {
                    onDragStopped(delta, true)
                    return
                }
        swipeTransition.dragOffset += acceleratedOffset

        if (isNewFromScene || targetScene.key != swipeTransition.toScene) {
            updateTransition(
                SwipeTransition(fromScene, targetScene, distance).apply {
                    this.dragOffset = swipeTransition.dragOffset
                }
            )
        }
    }

    private fun updateSwipesResults(fromScene: Scene) {
        val (upOrLeftResult, downOrRightResult) =
            computeSwipesResults(
                fromScene,
                this.swipes ?: error("updateSwipes() should be called before updateSwipesResults()")
            )

        this.upOrLeftResult = upOrLeftResult
        this.downOrRightResult = downOrRightResult
    }

    private fun computeSwipesResults(
        fromScene: Scene,
        swipes: Swipes
    ): Pair<UserActionResult?, UserActionResult?> {
        val userActions = fromScene.userActions
        fun sceneToSwipePair(swipe: Swipe?): UserActionResult? {
            return userActions[swipe ?: return null]
        }

        val upOrLeftResult =
            sceneToSwipePair(swipes.upOrLeft) ?: sceneToSwipePair(swipes.upOrLeftNoSource)
        val downOrRightResult =
            sceneToSwipePair(swipes.downOrRight) ?: sceneToSwipePair(swipes.downOrRightNoSource)
        return Pair(upOrLeftResult, downOrRightResult)
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
        if (swipeTransition._currentScene != toScene) {
            return Pair(fromScene, 0f)
        }

        // If the offset is past the distance then let's change fromScene so that the user can swipe
        // to the next screen or go back to the previous one.
        val offset = swipeTransition.dragOffset
        return if (offset <= -absoluteDistance && upOrLeftResult?.toScene == toScene.key) {
            Pair(toScene, absoluteDistance)
        } else if (offset >= absoluteDistance && downOrRightResult?.toScene == toScene.key) {
            Pair(toScene, -absoluteDistance)
        } else {
            Pair(fromScene, 0f)
        }
    }

    /**
     * Returns the target scene and distance from [fromScene] in the direction [directionOffset].
     *
     * @param fromScene the scene from which we look for the target
     * @param directionOffset signed float that indicates the direction. Positive is down or right
     *   negative is up or left.
     * @param updateSwipesResults whether the target scenes should be updated to the current values
     *   held in the Scenes map. Usually we don't want to update them while doing a drag, because
     *   this could change the target scene (jump cutting) to a different scene, when some system
     *   state changed the targets the background. However, an update is needed any time we
     *   calculate the targets for a new fromScene.
     * @return null when there are no targets in either direction. If one direction is null and you
     *   drag into the null direction this function will return the opposite direction, assuming
     *   that the users intention is to start the drag into the other direction eventually. If
     *   [directionOffset] is 0f and both direction are available, it will default to
     *   [upOrLeftResult].
     */
    private inline fun findTargetSceneAndDistance(
        fromScene: Scene,
        directionOffset: Float,
        updateSwipesResults: Boolean,
    ): Pair<Scene, Float>? {
        if (updateSwipesResults) updateSwipesResults(fromScene)

        // Compute the target scene depending on the current offset.
        return when {
            upOrLeftResult == null && downOrRightResult == null -> null
            (directionOffset < 0f && upOrLeftResult != null) || downOrRightResult == null ->
                upOrLeftResult?.let { result ->
                    Pair(
                        layoutImpl.scene(result.toScene),
                        -fromScene.getAbsoluteDistance(result.distance)
                    )
                }
            else ->
                downOrRightResult?.let { result ->
                    Pair(
                        layoutImpl.scene(result.toScene),
                        fromScene.getAbsoluteDistance(result.distance)
                    )
                }
        }
    }

    /**
     * A strict version of [findTargetSceneAndDistance] that will return null when there is no Scene
     * in [directionOffset] direction
     */
    private inline fun findTargetSceneAndDistanceStrict(
        fromScene: Scene,
        directionOffset: Float,
    ): Pair<Scene, Float>? {
        return when {
            directionOffset > 0f ->
                upOrLeftResult?.let { result ->
                    Pair(
                        layoutImpl.scene(result.toScene),
                        -fromScene.getAbsoluteDistance(result.distance),
                    )
                }
            directionOffset < 0f ->
                downOrRightResult?.let { result ->
                    Pair(
                        layoutImpl.scene(result.toScene),
                        fromScene.getAbsoluteDistance(result.distance),
                    )
                }
            else -> null
        }
    }

    internal fun onDragStopped(velocity: Float, canChangeScene: Boolean) {
        // The state was changed since the drag started; don't do anything.
        if (!isDrivingTransition) {
            return
        }

        // Important: Make sure that all the code here references the current transition when
        // [onDragStopped] is called, otherwise the callbacks (like onAnimationCompleted()) might
        // incorrectly finish a new transition that replaced this one.
        val swipeTransition = this.swipeTransition

        fun animateTo(targetScene: Scene, targetOffset: Float) {
            // If the effective current scene changed, it should be reflected right now in the
            // current scene state, even before the settle animation is ongoing. That way all the
            // swipeables and back handlers will be refreshed and the user can for instance quickly
            // swipe vertically from A => B then horizontally from B => C, or swipe from A => B then
            // immediately go back B => A.
            if (targetScene != swipeTransition._currentScene) {
                swipeTransition._currentScene = targetScene
                with(layoutImpl.state) { coroutineScope.onChangeScene(targetScene.key) }
            }

            swipeTransition.animateOffset(
                coroutineScope = coroutineScope,
                initialVelocity = velocity,
                targetOffset = targetOffset,
                onAnimationCompleted = {
                    layoutState.finishTransition(swipeTransition, idleScene = targetScene.key)
                }
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
                // If there is a target scene, we start the overscroll animation.
                val (targetScene, distance) =
                    findTargetSceneAndDistanceStrict(fromScene, velocity)
                        ?: run {
                            // We will not animate
                            layoutState.finishTransition(swipeTransition, idleScene = fromScene.key)
                            return
                        }

                updateTransition(
                    SwipeTransition(fromScene, targetScene, distance).apply {
                        _currentScene = swipeTransition._currentScene
                    }
                )
                animateTo(targetScene = fromScene, targetOffset = 0f)
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

    internal class SwipeTransition(
        val _fromScene: Scene,
        val _toScene: Scene,
        /**
         * The signed distance between [fromScene] and [toScene]. It is negative if [fromScene] is
         * above or to the left of [toScene].
         */
        val distance: Float
    ) : TransitionState.Transition(_fromScene.key, _toScene.key) {
        var _currentScene by mutableStateOf(_fromScene)
        override val currentScene: SceneKey
            get() = _currentScene.key

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
        private fun startOffsetAnimation(job: () -> Job) {
            cancelOffsetAnimation()
            offsetAnimationJob = job()
        }

        /** Cancel any ongoing offset animation. */
        // TODO(b/317063114) This should be a suspended function to avoid multiple jobs running at
        // the same time.
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

        // TODO(b/290184746): Make this spring spec configurable.
        private val animationSpec =
            spring(
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = OffsetVisibilityThreshold
            )

        fun animateOffset(
            // TODO(b/317063114) The CoroutineScope should be removed.
            coroutineScope: CoroutineScope,
            initialVelocity: Float,
            targetOffset: Float,
            onAnimationCompleted: () -> Unit,
        ) {
            startOffsetAnimation {
                coroutineScope.launch {
                    animateOffset(targetOffset, initialVelocity)
                    onAnimationCompleted()
                }
            }
        }

        private suspend fun animateOffset(targetOffset: Float, initialVelocity: Float) {
            if (!isAnimatingOffset) {
                offsetAnimatable.snapTo(dragOffset)
            }
            isAnimatingOffset = true

            offsetAnimatable.animateTo(
                targetValue = targetOffset,
                animationSpec = animationSpec,
                initialVelocity = initialVelocity,
            )

            finishOffsetAnimation()
        }
    }

    companion object {
        private const val TAG = "SceneGestureHandler"
    }

    private object DefaultSwipeDistance : UserActionDistance {
        override fun Density.absoluteDistance(
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
    private class Swipes(
        val upOrLeft: Swipe?,
        val downOrRight: Swipe?,
        val upOrLeftNoSource: Swipe?,
        val downOrRightNoSource: Swipe?,
    )
}

private class SceneDraggableHandler(
    private val gestureHandler: SceneGestureHandler,
) : DraggableHandler {
    private val source = this

    override fun onDragStarted(startedPosition: Offset, overSlop: Float, pointersDown: Int) {
        gestureHandler.currentSource = source
        gestureHandler.onDragStarted(pointersDown, startedPosition, overSlop)
    }

    override fun onDelta(pixels: Float) {
        if (gestureHandler.currentSource == source) {
            gestureHandler.onDrag(delta = pixels)
        }
    }

    override fun onDragStopped(velocity: Float) {
        if (gestureHandler.currentSource == source) {
            gestureHandler.currentSource = null
            gestureHandler.onDragStopped(velocity = velocity, canChangeScene = true)
        }
    }
}

internal class SceneNestedScrollHandler(
    private val layoutImpl: SceneTransitionLayoutImpl,
    private val orientation: Orientation,
    private val topOrLeftBehavior: NestedScrollBehavior,
    private val bottomOrRightBehavior: NestedScrollBehavior,
) : NestedScrollHandler {
    private val layoutState = layoutImpl.state
    private val gestureHandler = layoutImpl.gestureHandler(orientation)

    override val connection: PriorityNestedScrollConnection = nestedScrollConnection()

    private fun nestedScrollConnection(): PriorityNestedScrollConnection {
        // If we performed a long gesture before entering priority mode, we would have to avoid
        // moving on to the next scene.
        var canChangeScene = false

        val actionUpOrLeft =
            Swipe(
                direction =
                    when (orientation) {
                        Orientation.Horizontal -> SwipeDirection.Left
                        Orientation.Vertical -> SwipeDirection.Up
                    },
                pointerCount = 1,
            )

        val actionDownOrRight =
            Swipe(
                direction =
                    when (orientation) {
                        Orientation.Horizontal -> SwipeDirection.Right
                        Orientation.Vertical -> SwipeDirection.Down
                    },
                pointerCount = 1,
            )

        fun hasNextScene(amount: Float): Boolean {
            val fromScene = layoutImpl.scene(layoutState.transitionState.currentScene)
            val nextScene =
                when {
                    amount < 0f -> fromScene.userActions[actionUpOrLeft]
                    amount > 0f -> fromScene.userActions[actionDownOrRight]
                    else -> null
                }
            return nextScene != null
        }

        val source = this
        var isIntercepting = false

        return PriorityNestedScrollConnection(
            orientation = orientation,
            canStartPreScroll = { offsetAvailable, offsetBeforeStart ->
                canChangeScene = offsetBeforeStart == 0f

                val canInterceptSwipeTransition =
                    canChangeScene &&
                        offsetAvailable != 0f &&
                        gestureHandler.shouldImmediatelyIntercept(startedPosition = null)
                if (!canInterceptSwipeTransition) return@PriorityNestedScrollConnection false

                val swipeTransition = gestureHandler.swipeTransition
                val progress = swipeTransition.progress
                val threshold = layoutImpl.transitionInterceptionThreshold
                fun isProgressCloseTo(value: Float) = (progress - value).absoluteValue <= threshold

                // The transition is always between 0 and 1. If it is close to either of these
                // intervals, we want to go directly to the TransitionState.Idle.
                // The progress value can go beyond this range in the case of overscroll.
                val shouldSnapToIdle = isProgressCloseTo(0f) || isProgressCloseTo(1f)
                if (shouldSnapToIdle) {
                    swipeTransition.cancelOffsetAnimation()
                    layoutState.finishTransition(swipeTransition, swipeTransition.currentScene)
                }

                // Start only if we cannot consume this event
                val canStart = !shouldSnapToIdle
                if (canStart) {
                    isIntercepting = true
                }

                canStart
            },
            canStartPostScroll = { offsetAvailable, offsetBeforeStart ->
                val behavior: NestedScrollBehavior =
                    when {
                        offsetAvailable > 0f -> topOrLeftBehavior
                        offsetAvailable < 0f -> bottomOrRightBehavior
                        else -> return@PriorityNestedScrollConnection false
                    }

                val isZeroOffset = offsetBeforeStart == 0f

                val canStart =
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

                val canStart = behavior.canStartOnPostFling && hasNextScene(velocityAvailable)
                if (canStart) {
                    isIntercepting = false
                }

                canStart
            },
            canContinueScroll = { true },
            canScrollOnFling = false,
            onStart = { offsetAvailable ->
                gestureHandler.currentSource = source
                gestureHandler.onDragStarted(
                    pointersDown = 1,
                    startedPosition = null,
                    overSlop = if (isIntercepting) 0f else offsetAvailable,
                )
            },
            onScroll = { offsetAvailable ->
                if (gestureHandler.currentSource != source) {
                    return@PriorityNestedScrollConnection 0f
                }

                // TODO(b/297842071) We should handle the overscroll or slow drag if the gesture is
                // initiated in a nested child.
                gestureHandler.onDrag(offsetAvailable)

                offsetAvailable
            },
            onStop = { velocityAvailable ->
                if (gestureHandler.currentSource != source) {
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
