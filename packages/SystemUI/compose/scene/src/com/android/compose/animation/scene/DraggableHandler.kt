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
import com.android.compose.animation.scene.TransitionState.HasOverscrollProperties.Companion.DistanceUnspecified
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
    /** Drag the current scene by [delta] pixels. */
    fun onDrag(delta: Float)

    /** Starts a transition to a target scene. */
    fun onStop(velocity: Float, canChangeScene: Boolean)
}

internal class DraggableHandlerImpl(
    internal val layoutImpl: SceneTransitionLayoutImpl,
    internal val orientation: Orientation,
    internal val coroutineScope: CoroutineScope,
) : DraggableHandler {
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

        val swipeTransition = dragController.swipeTransition

        // Don't intercept a transition that is finishing.
        if (swipeTransition.isFinishing) {
            return false
        }

        // Only intercept the current transition if one of the 2 swipes results is also a transition
        // between the same pair of scenes.
        val fromScene = swipeTransition._currentScene
        val swipes = computeSwipes(fromScene, startedPosition, pointersDown = 1)
        val (upOrLeft, downOrRight) = swipes.computeSwipesResults(fromScene)
        return (upOrLeft != null &&
            swipeTransition.isTransitioningBetween(fromScene.key, upOrLeft.toScene)) ||
            (downOrRight != null &&
                swipeTransition.isTransitioningBetween(fromScene.key, downOrRight.toScene))
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
            // Stop animating and start from where the current offset.
            oldDragController.swipeTransition.cancelOffsetAnimation()

            // We need to recompute the swipe results since this is a new gesture, and the
            // fromScene.userActions may have changed.
            val swipes = oldDragController.swipes
            swipes.updateSwipesResults(oldDragController.swipeTransition._fromScene)

            // A new gesture should always create a new SwipeTransition. This way there cannot be
            // different gestures controlling the same transition.
            val swipeTransition = SwipeTransition(oldDragController.swipeTransition)
            swipes.updateSwipesResults(fromScene = swipeTransition._fromScene)
            return updateDragController(swipes, swipeTransition)
        }

        val transitionState = layoutImpl.state.transitionState
        val fromScene = layoutImpl.scene(transitionState.currentScene)
        val swipes = computeSwipes(fromScene, startedPosition, pointersDown)
        val result =
            swipes.findUserActionResult(fromScene, overSlop, true)
                // As we were unable to locate a valid target scene, the initial SwipeTransition
                // cannot be defined. Consequently, a simple NoOp Controller will be returned.
                ?: return NoOpDragController

        return updateDragController(
            swipes = swipes,
            swipeTransition =
                SwipeTransition(
                    layoutImpl.state,
                    coroutineScope,
                    fromScene,
                    result,
                    swipes,
                    layoutImpl,
                    orientation,
                )
        )
    }

    private fun updateDragController(
        swipes: Swipes,
        swipeTransition: SwipeTransition
    ): DragController {
        val newDragController = DragControllerImpl(this, swipes, swipeTransition)
        newDragController.updateTransition(swipeTransition, force = true)
        dragController = newDragController
        return newDragController
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

    companion object {
        private const val TAG = "DraggableHandlerImpl"
    }
}

/** @param swipes The [Swipes] associated to the current gesture. */
private class DragControllerImpl(
    private val draggableHandler: DraggableHandlerImpl,
    val swipes: Swipes,
    var swipeTransition: SwipeTransition,
) : DragController {
    val layoutState = draggableHandler.layoutImpl.state

    /**
     * Whether this handle is active. If this returns false, calling [onDrag] and [onStop] will do
     * nothing. We should have only one active controller at a time
     */
    val isDrivingTransition: Boolean
        get() = layoutState.transitionState == swipeTransition

    init {
        check(!isDrivingTransition) { "Multiple controllers with the same SwipeTransition" }
    }

    fun updateTransition(newTransition: SwipeTransition, force: Boolean = false) {
        if (isDrivingTransition || force) {
            layoutState.startTransition(newTransition)
        }

        swipeTransition = newTransition
    }

    /**
     * We receive a [delta] that can be consumed to change the offset of the current
     * [SwipeTransition].
     *
     * @return the consumed delta
     */
    override fun onDrag(delta: Float) {
        if (delta == 0f || !isDrivingTransition || swipeTransition.isFinishing) return
        swipeTransition.dragOffset += delta

        val (fromScene, acceleratedOffset) =
            computeFromSceneConsideringAcceleratedSwipe(swipeTransition)

        val isNewFromScene = fromScene.key != swipeTransition.fromScene
        val result =
            swipes.findUserActionResult(
                fromScene = fromScene,
                directionOffset = swipeTransition.dragOffset,
                updateSwipesResults = isNewFromScene,
            )

        if (result == null) {
            onStop(velocity = delta, canChangeScene = true)
            return
        }

        if (
            isNewFromScene ||
                result.toScene != swipeTransition.toScene ||
                result.transitionKey != swipeTransition.key
        ) {
            // Make sure the current transition will finish to the right current scene.
            swipeTransition._currentScene = fromScene

            val swipeTransition =
                SwipeTransition(
                        layoutState = layoutState,
                        coroutineScope = draggableHandler.coroutineScope,
                        fromScene = fromScene,
                        result = result,
                        swipes = swipes,
                        layoutImpl = draggableHandler.layoutImpl,
                        orientation = draggableHandler.orientation,
                    )
                    .apply { dragOffset = swipeTransition.dragOffset + acceleratedOffset }

            updateTransition(swipeTransition)
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
        val distance = swipeTransition.distance()

        // If the swipe was not committed or if the swipe distance is not computed yet, don't do
        // anything.
        if (swipeTransition._currentScene != toScene || distance == DistanceUnspecified) {
            return fromScene to 0f
        }

        // If the offset is past the distance then let's change fromScene so that the user can swipe
        // to the next screen or go back to the previous one.
        val offset = swipeTransition.dragOffset
        val absoluteDistance = distance.absoluteValue
        return if (offset <= -absoluteDistance && swipes.upOrLeftResult?.toScene == toScene.key) {
            toScene to absoluteDistance
        } else if (offset >= absoluteDistance && swipes.downOrRightResult?.toScene == toScene.key) {
            toScene to -absoluteDistance
        } else {
            fromScene to 0f
        }
    }

    override fun onStop(velocity: Float, canChangeScene: Boolean) {
        // The state was changed since the drag started; don't do anything.
        if (!isDrivingTransition || swipeTransition.isFinishing) {
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
                with(draggableHandler.layoutImpl.state) {
                    draggableHandler.coroutineScope.onChangeScene(targetScene.key)
                }
            }

            swipeTransition.animateOffset(
                coroutineScope = draggableHandler.coroutineScope,
                initialVelocity = velocity,
                targetOffset = targetOffset,
                targetScene = targetScene.key,
            )
        }

        val fromScene = swipeTransition._fromScene
        if (canChangeScene) {
            // If we are halfway between two scenes, we check what the target will be based on the
            // velocity and offset of the transition, then we launch the animation.

            val toScene = swipeTransition._toScene

            // Compute the destination scene (and therefore offset) to settle in.
            val offset = swipeTransition.dragOffset
            val distance = swipeTransition.distance()
            var targetScene: Scene
            var targetOffset: Float
            if (
                distance != DistanceUnspecified &&
                    shouldCommitSwipe(
                        offset = offset,
                        distance = distance,
                        velocity = velocity,
                        wasCommitted = swipeTransition._currentScene == toScene,
                        requiresFullDistanceSwipe = swipeTransition.requiresFullDistanceSwipe,
                    )
            ) {
                targetScene = toScene
                targetOffset = distance
            } else {
                targetScene = fromScene
                targetOffset = 0f
            }

            if (
                targetScene != swipeTransition._currentScene &&
                    !layoutState.canChangeScene(targetScene.key)
            ) {
                // We wanted to change to a new scene but we are not allowed to, so we animate back
                // to the current scene.
                targetScene = swipeTransition._currentScene
                targetOffset =
                    if (targetScene == fromScene) {
                        0f
                    } else {
                        check(distance != DistanceUnspecified) {
                            "distance is equal to $DistanceUnspecified"
                        }
                        distance
                    }
            }

            animateTo(targetScene = targetScene, targetOffset = targetOffset)
        } else {
            // We are doing an overscroll animation between scenes. In this case, we can also start
            // from the idle position.

            val startFromIdlePosition = swipeTransition.dragOffset == 0f

            if (startFromIdlePosition) {
                // If there is a target scene, we start the overscroll animation.
                val result = swipes.findUserActionResultStrict(velocity)
                if (result == null) {
                    // We will not animate
                    swipeTransition.snapToScene(fromScene.key)
                    return
                }

                val newSwipeTransition =
                    SwipeTransition(
                            layoutState = layoutState,
                            coroutineScope = draggableHandler.coroutineScope,
                            fromScene = fromScene,
                            result = result,
                            swipes = swipes,
                            layoutImpl = draggableHandler.layoutImpl,
                            orientation = draggableHandler.orientation,
                        )
                        .apply { _currentScene = swipeTransition._currentScene }

                updateTransition(newSwipeTransition)
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

private fun SwipeTransition(
    layoutState: BaseSceneTransitionLayoutState,
    coroutineScope: CoroutineScope,
    fromScene: Scene,
    result: UserActionResult,
    swipes: Swipes,
    layoutImpl: SceneTransitionLayoutImpl,
    orientation: Orientation,
): SwipeTransition {
    val upOrLeftResult = swipes.upOrLeftResult
    val downOrRightResult = swipes.downOrRightResult
    val isUpOrLeft =
        when (result) {
            upOrLeftResult -> true
            downOrRightResult -> false
            else -> error("Unknown result $result ($upOrLeftResult $downOrRightResult)")
        }

    return SwipeTransition(
        layoutImpl = layoutImpl,
        layoutState = layoutState,
        coroutineScope = coroutineScope,
        key = result.transitionKey,
        _fromScene = fromScene,
        _toScene = layoutImpl.scene(result.toScene),
        userActionDistanceScope = layoutImpl.userActionDistanceScope,
        orientation = orientation,
        isUpOrLeft = isUpOrLeft,
        requiresFullDistanceSwipe = result.requiresFullDistanceSwipe,
        replacedTransition = null,
    )
}

private fun SwipeTransition(old: SwipeTransition): SwipeTransition {
    return SwipeTransition(
            layoutImpl = old.layoutImpl,
            layoutState = old.layoutState,
            coroutineScope = old.coroutineScope,
            key = old.key,
            _fromScene = old._fromScene,
            _toScene = old._toScene,
            userActionDistanceScope = old.userActionDistanceScope,
            orientation = old.orientation,
            isUpOrLeft = old.isUpOrLeft,
            lastDistance = old.lastDistance,
            requiresFullDistanceSwipe = old.requiresFullDistanceSwipe,
            replacedTransition = old,
        )
        .apply {
            _currentScene = old._currentScene
            dragOffset = old.dragOffset
        }
}

private class SwipeTransition(
    val layoutImpl: SceneTransitionLayoutImpl,
    val layoutState: BaseSceneTransitionLayoutState,
    val coroutineScope: CoroutineScope,
    override val key: TransitionKey?,
    val _fromScene: Scene,
    val _toScene: Scene,
    val userActionDistanceScope: UserActionDistanceScope,
    override val orientation: Orientation,
    override val isUpOrLeft: Boolean,
    val requiresFullDistanceSwipe: Boolean,
    replacedTransition: SwipeTransition?,
    var lastDistance: Float = DistanceUnspecified,
) :
    TransitionState.Transition(_fromScene.key, _toScene.key, replacedTransition),
    TransitionState.HasOverscrollProperties {
    var _currentScene by mutableStateOf(_fromScene)
    override val currentScene: SceneKey
        get() = _currentScene.key

    override val progress: Float
        get() {
            // Important: If we are going to return early because distance is equal to 0, we should
            // still make sure we read the offset before returning so that the calling code still
            // subscribes to the offset value.
            val offset = offsetAnimation?.animatable?.value ?: dragOffset

            val distance = distance()
            if (distance == DistanceUnspecified) {
                return 0f
            }

            return offset / distance
        }

    override val progressVelocity: Float
        get() {
            val animatable = offsetAnimation?.animatable ?: return 0f
            val distance = distance()
            if (distance == DistanceUnspecified) {
                return 0f
            }

            val velocityInDistanceUnit = animatable.velocity
            return velocityInDistanceUnit / distance.absoluteValue
        }

    override val isInitiatedByUserInput = true

    override var bouncingScene: SceneKey? = null

    /** The current offset caused by the drag gesture. */
    var dragOffset by mutableFloatStateOf(0f)

    /** The offset animation that animates the offset once the user lifts their finger. */
    private var offsetAnimation: OffsetAnimation? by mutableStateOf(null)

    override val isUserInputOngoing: Boolean
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

    /** Whether [TransitionState.Transition.finish] was called on this transition. */
    var isFinishing = false
        private set

    /**
     * The signed distance between [fromScene] and [toScene]. It is negative if [fromScene] is above
     * or to the left of [toScene].
     *
     * Note that this distance can be equal to [DistanceUnspecified] during the first frame of a
     * transition when the distance depends on the size or position of an element that is composed
     * in the scene we are going to.
     */
    fun distance(): Float {
        if (lastDistance != DistanceUnspecified) {
            return lastDistance
        }

        val absoluteDistance =
            with(transformationSpec.distance ?: DefaultSwipeDistance) {
                userActionDistanceScope.absoluteDistance(
                    _fromScene.targetSize,
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
        targetScene: SceneKey,
    ): OffsetAnimation {
        // Skip the animation if we have already reached the target scene and the overscroll does
        // not animate anything.
        val hasReachedTargetScene =
            (targetScene == toScene && progress >= 1f) ||
                (targetScene == fromScene && progress <= 0f)
        val skipAnimation =
            hasReachedTargetScene &&
                currentOverscrollSpec?.transformationSpec?.transformations?.isEmpty() == true

        return startOffsetAnimation {
            val animatable = Animatable(dragOffset, OffsetVisibilityThreshold)
            val isTargetGreater = targetOffset > animatable.value
            val startedWhenOvercrollingTargetScene =
                if (targetScene == fromScene) progress < 0f else progress > 1f
            val job =
                coroutineScope
                    // Important: We start atomically to make sure that we start the coroutine even
                    // if it is cancelled right after it is launched, so that snapToScene() is
                    // correctly called. Otherwise, this transition will never be stopped and we
                    // will never settle to Idle.
                    .launch(start = CoroutineStart.ATOMIC) {
                        // TODO(b/327249191): Refactor the code so that we don't even launch a
                        // coroutine if we don't need to animate.
                        if (skipAnimation) {
                            snapToScene(targetScene)
                            cancelOffsetAnimation()
                            dragOffset = targetOffset
                            return@launch
                        }

                        try {
                            val swipeSpec =
                                transformationSpec.swipeSpec
                                    ?: layoutState.transitions.defaultSwipeSpec
                            animatable.animateTo(
                                targetValue = targetOffset,
                                animationSpec = swipeSpec,
                                initialVelocity = initialVelocity,
                            ) {
                                if (bouncingScene == null) {
                                    val isBouncing =
                                        if (isTargetGreater) {
                                            if (startedWhenOvercrollingTargetScene) {
                                                value >= targetOffset
                                            } else {
                                                value > targetOffset
                                            }
                                        } else {
                                            if (startedWhenOvercrollingTargetScene) {
                                                value <= targetOffset
                                            } else {
                                                value < targetOffset
                                            }
                                        }

                                    if (isBouncing) {
                                        bouncingScene = targetScene

                                        // Immediately stop this transition if we are bouncing on a
                                        // scene that does not bounce.
                                        val overscrollSpec = currentOverscrollSpec
                                        if (
                                            overscrollSpec != null &&
                                                overscrollSpec.transformationSpec.transformations
                                                    .isEmpty()
                                        ) {
                                            snapToScene(targetScene)
                                        }
                                    }
                                }
                            }
                        } finally {
                            snapToScene(targetScene)
                        }
                    }

            OffsetAnimation(animatable, job)
        }
    }

    fun snapToScene(scene: SceneKey) {
        cancelOffsetAnimation()
        layoutState.finishTransition(this, idleScene = scene)
    }

    override fun finish(): Job {
        if (isFinishing) return requireNotNull(offsetAnimation).job
        isFinishing = true

        // If we were already animating the offset, simply return the job.
        offsetAnimation?.let {
            return it.job
        }

        // Animate to the current scene.
        val targetScene = currentScene
        val targetOffset =
            if (targetScene == fromScene) {
                0f
            } else {
                val distance = distance()
                check(distance != DistanceUnspecified) {
                    "targetScene != fromScene but distance is unspecified"
                }
                distance
            }

        val animation =
            animateOffset(
                coroutineScope = coroutineScope,
                initialVelocity = 0f,
                targetOffset = targetOffset,
                targetScene = currentScene,
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
private class Swipes(
    val upOrLeft: Swipe?,
    val downOrRight: Swipe?,
    val upOrLeftNoSource: Swipe?,
    val downOrRightNoSource: Swipe?,
) {
    /** The [UserActionResult] associated to up and down swipes. */
    var upOrLeftResult: UserActionResult? = null
    var downOrRightResult: UserActionResult? = null

    fun computeSwipesResults(fromScene: Scene): Pair<UserActionResult?, UserActionResult?> {
        val userActions = fromScene.userActions
        fun result(swipe: Swipe?): UserActionResult? {
            return userActions[swipe ?: return null]
        }

        val upOrLeftResult = result(upOrLeft) ?: result(upOrLeftNoSource)
        val downOrRightResult = result(downOrRight) ?: result(downOrRightNoSource)
        return upOrLeftResult to downOrRightResult
    }

    fun updateSwipesResults(fromScene: Scene) {
        val (upOrLeftResult, downOrRightResult) = computeSwipesResults(fromScene)

        this.upOrLeftResult = upOrLeftResult
        this.downOrRightResult = downOrRightResult
    }

    /**
     * Returns the [UserActionResult] from [fromScene] in the direction of [directionOffset].
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
    fun findUserActionResult(
        fromScene: Scene,
        directionOffset: Float,
        updateSwipesResults: Boolean,
    ): UserActionResult? {
        if (updateSwipesResults) {
            updateSwipesResults(fromScene)
        }

        return when {
            upOrLeftResult == null && downOrRightResult == null -> null
            (directionOffset < 0f && upOrLeftResult != null) || downOrRightResult == null ->
                upOrLeftResult
            else -> downOrRightResult
        }
    }

    /**
     * A strict version of [findUserActionResult] that will return null when there is no Scene in
     * [directionOffset] direction
     */
    fun findUserActionResultStrict(directionOffset: Float): UserActionResult? {
        return when {
            directionOffset > 0f -> upOrLeftResult
            directionOffset < 0f -> downOrRightResult
            else -> null
        }
    }
}

internal class NestedScrollHandlerImpl(
    private val layoutImpl: SceneTransitionLayoutImpl,
    private val orientation: Orientation,
    private val topOrLeftBehavior: NestedScrollBehavior,
    private val bottomOrRightBehavior: NestedScrollBehavior,
    private val isExternalOverscrollGesture: () -> Boolean,
    private val pointersInfo: () -> PointersInfo,
) {
    private val layoutState = layoutImpl.state
    private val draggableHandler = layoutImpl.draggableHandler(orientation)

    val connection: PriorityNestedScrollConnection = nestedScrollConnection()

    private fun nestedScrollConnection(): PriorityNestedScrollConnection {
        // If we performed a long gesture before entering priority mode, we would have to avoid
        // moving on to the next scene.
        var canChangeScene = false

        fun hasNextScene(amount: Float): Boolean {
            val transitionState = layoutState.transitionState
            val scene = transitionState.currentScene
            val fromScene = layoutImpl.scene(scene)
            val nextScene =
                when {
                    amount < 0f -> {
                        val actionUpOrLeft =
                            Swipe(
                                direction =
                                    when (orientation) {
                                        Orientation.Horizontal -> SwipeDirection.Left
                                        Orientation.Vertical -> SwipeDirection.Up
                                    },
                                pointerCount = pointersInfo().pointersDown,
                            )
                        fromScene.userActions[actionUpOrLeft]
                    }
                    amount > 0f -> {
                        val actionDownOrRight =
                            Swipe(
                                direction =
                                    when (orientation) {
                                        Orientation.Horizontal -> SwipeDirection.Right
                                        Orientation.Vertical -> SwipeDirection.Down
                                    },
                                pointerCount = pointersInfo().pointersDown,
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
                val pointers = pointersInfo()
                dragController =
                    draggableHandler.onDragStarted(
                        pointersDown = pointers.pointersDown,
                        startedPosition = pointers.startedPosition,
                        overSlop = if (isIntercepting) 0f else offsetAvailable,
                    )
            },
            onScroll = { offsetAvailable ->
                val controller = dragController ?: error("Should be called after onStart")

                // TODO(b/297842071) We should handle the overscroll or slow drag if the gesture is
                // initiated in a nested child.
                controller.onDrag(delta = offsetAvailable)

                offsetAvailable
            },
            onStop = { velocityAvailable ->
                val controller = dragController ?: error("Should be called after onStart")

                controller.onStop(velocity = velocityAvailable, canChangeScene = canChangeScene)

                dragController = null
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
// TODO(b/290184746): Have a better default visibility threshold which takes the swipe distance into
// account instead.
internal const val OffsetVisibilityThreshold = 0.5f

private object NoOpDragController : DragController {
    override fun onDrag(delta: Float) {}

    override fun onStop(velocity: Float, canChangeScene: Boolean) {}
}
