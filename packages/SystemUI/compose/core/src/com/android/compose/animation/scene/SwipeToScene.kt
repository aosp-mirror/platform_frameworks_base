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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.android.compose.nestedscroll.PriorityPostNestedScrollConnection
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Configures the swipeable behavior of a [SceneTransitionLayout] depending on the current state.
 */
@Composable
internal fun Modifier.swipeToScene(
    layoutImpl: SceneTransitionLayoutImpl,
    orientation: Orientation,
): Modifier {
    val state = layoutImpl.state.transitionState
    val currentScene = layoutImpl.scene(state.currentScene)
    val transition = remember {
        // Note that the currentScene here does not matter, it's only used for initializing the
        // transition and will be replaced when a drag event starts.
        SwipeTransition(initialScene = currentScene)
    }

    val enabled = state == transition || currentScene.shouldEnableSwipes(orientation)

    // Immediately start the drag if this our [transition] is currently animating to a scene (i.e.
    // the user released their input pointer after swiping in this orientation) and the user can't
    // swipe in the other direction.
    val startDragImmediately =
        state == transition &&
            transition.isAnimatingOffset &&
            !currentScene.shouldEnableSwipes(orientation.opposite())

    // The velocity threshold at which the intent of the user is to swipe up or down. It is the same
    // as SwipeableV2Defaults.VelocityThreshold.
    val velocityThreshold = with(LocalDensity.current) { 125.dp.toPx() }

    // The positional threshold at which the intent of the user is to swipe to the next scene. It is
    // the same as SwipeableV2Defaults.PositionalThreshold.
    val positionalThreshold = with(LocalDensity.current) { 56.dp.toPx() }

    val draggableState = rememberDraggableState { delta ->
        onDrag(layoutImpl, transition, orientation, delta)
    }

    return nestedScroll(
            connection =
                rememberSwipeToSceneNestedScrollConnection(
                    orientation = orientation,
                    coroutineScope = rememberCoroutineScope(),
                    draggableState = draggableState,
                    transition = transition,
                    layoutImpl = layoutImpl,
                    velocityThreshold = velocityThreshold,
                    positionalThreshold = positionalThreshold
                ),
        )
        .draggable(
            state = draggableState,
            orientation = orientation,
            enabled = enabled,
            startDragImmediately = startDragImmediately,
            onDragStarted = { onDragStarted(layoutImpl, transition, orientation) },
            onDragStopped = { velocity ->
                onDragStopped(
                    layoutImpl = layoutImpl,
                    transition = transition,
                    velocity = velocity,
                    velocityThreshold = velocityThreshold,
                    positionalThreshold = positionalThreshold,
                )
            },
        )
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

    /** The current offset caused by the drag gesture. */
    var dragOffset by mutableFloatStateOf(0f)

    /**
     * Whether the offset is animated (the user lifted their finger) or if it is driven by gesture.
     */
    var isAnimatingOffset by mutableStateOf(false)

    /** The animatable used to animate the offset once the user lifted its finger. */
    val offsetAnimatable = Animatable(0f, visibilityThreshold = OffsetVisibilityThreshold)

    /**
     * The job currently animating [offsetAnimatable], if it is animating. Note that setting this to
     * a new job will automatically cancel the previous one.
     */
    var offsetAnimationJob: Job? = null
        set(value) {
            field?.cancel()
            field = value
        }

    /** The absolute distance between [fromScene] and [toScene]. */
    var absoluteDistance = 0f

    /**
     * The signed distance between [fromScene] and [toScene]. It is negative if [fromScene] is above
     * or to the left of [toScene].
     */
    var _distance by mutableFloatStateOf(0f)
    val distance: Float
        get() = _distance
}

/** The destination scene when swiping up or left from [this@upOrLeft]. */
private fun Scene.upOrLeft(orientation: Orientation): SceneKey? {
    return when (orientation) {
        Orientation.Vertical -> userActions[Swipe.Up]
        Orientation.Horizontal -> userActions[Swipe.Left]
    }
}

/** The destination scene when swiping down or right from [this@downOrRight]. */
private fun Scene.downOrRight(orientation: Orientation): SceneKey? {
    return when (orientation) {
        Orientation.Vertical -> userActions[Swipe.Down]
        Orientation.Horizontal -> userActions[Swipe.Right]
    }
}

/** Whether swipe should be enabled in the given [orientation]. */
private fun Scene.shouldEnableSwipes(orientation: Orientation): Boolean {
    return upOrLeft(orientation) != null || downOrRight(orientation) != null
}

private fun Orientation.opposite(): Orientation {
    return when (this) {
        Orientation.Vertical -> Orientation.Horizontal
        Orientation.Horizontal -> Orientation.Vertical
    }
}

private fun onDragStarted(
    layoutImpl: SceneTransitionLayoutImpl,
    transition: SwipeTransition,
    orientation: Orientation,
) {
    if (layoutImpl.state.transitionState == transition) {
        // This [transition] was already driving the animation: simply take over it.
        if (transition.isAnimatingOffset) {
            // Stop animating and start from where the current offset. Setting the animation job to
            // `null` will effectively cancel the animation.
            transition.isAnimatingOffset = false
            transition.offsetAnimationJob = null
            transition.dragOffset = transition.offsetAnimatable.value
        }

        return
    }

    // TODO(b/290184746): Better handle interruptions here if state != idle.

    val fromScene = layoutImpl.scene(layoutImpl.state.transitionState.currentScene)

    transition._currentScene = fromScene
    transition._fromScene = fromScene

    // We don't know where we are transitioning to yet given that the drag just started, so set it
    // to fromScene, which will effectively be treated the same as Idle(fromScene).
    transition._toScene = fromScene

    transition.dragOffset = 0f
    transition.isAnimatingOffset = false
    transition.offsetAnimationJob = null

    // Use the layout size in the swipe orientation for swipe distance.
    // TODO(b/290184746): Also handle custom distances for transitions. With smaller distances, we
    // will also have to make sure that we correctly handle overscroll.
    transition.absoluteDistance =
        when (orientation) {
            Orientation.Horizontal -> layoutImpl.size.width
            Orientation.Vertical -> layoutImpl.size.height
        }.toFloat()

    if (transition.absoluteDistance > 0f) {
        layoutImpl.state.transitionState = transition
    }
}

private fun onDrag(
    layoutImpl: SceneTransitionLayoutImpl,
    transition: SwipeTransition,
    orientation: Orientation,
    delta: Float,
) {
    transition.dragOffset += delta

    // First check transition.fromScene should be changed for the case where the user quickly swiped
    // twice in a row to accelerate the transition and go from A => B then B => C really fast.
    maybeHandleAcceleratedSwipe(transition, orientation)

    val offset = transition.dragOffset
    val fromScene = transition._fromScene

    // Compute the target scene depending on the current offset.
    val target = fromScene.findTargetSceneAndDistance(orientation, offset, layoutImpl)

    if (transition._toScene.key != target.sceneKey) {
        transition._toScene = layoutImpl.scenes.getValue(target.sceneKey)
    }

    if (transition._distance != target.distance) {
        transition._distance = target.distance
    }
}

/**
 * Change fromScene in the case where the user quickly swiped multiple times in the same direction
 * to accelerate the transition from A => B then B => C.
 */
private fun maybeHandleAcceleratedSwipe(
    transition: SwipeTransition,
    orientation: Orientation,
) {
    val toScene = transition._toScene
    val fromScene = transition._fromScene

    // If the swipe was not committed, don't do anything.
    if (fromScene == toScene || transition._currentScene != toScene) {
        return
    }

    // If the offset is past the distance then let's change fromScene so that the user can swipe to
    // the next screen or go back to the previous one.
    val offset = transition.dragOffset
    val absoluteDistance = transition.absoluteDistance
    if (offset <= -absoluteDistance && fromScene.upOrLeft(orientation) == toScene.key) {
        transition.dragOffset += absoluteDistance
        transition._fromScene = toScene
    } else if (offset >= absoluteDistance && fromScene.downOrRight(orientation) == toScene.key) {
        transition.dragOffset -= absoluteDistance
        transition._fromScene = toScene
    }

    // Important note: toScene and distance will be updated right after this function is called,
    // using fromScene and dragOffset.
}

private data class TargetScene(
    val sceneKey: SceneKey,
    val distance: Float,
)

private fun Scene.findTargetSceneAndDistance(
    orientation: Orientation,
    directionOffset: Float,
    layoutImpl: SceneTransitionLayoutImpl,
): TargetScene {
    val maxDistance =
        when (orientation) {
            Orientation.Horizontal -> layoutImpl.size.width
            Orientation.Vertical -> layoutImpl.size.height
        }.toFloat()

    val upOrLeft = upOrLeft(orientation)
    val downOrRight = downOrRight(orientation)

    // Compute the target scene depending on the current offset.
    return when {
        directionOffset < 0f && upOrLeft != null -> {
            TargetScene(
                sceneKey = upOrLeft,
                distance = -maxDistance,
            )
        }
        directionOffset > 0f && downOrRight != null -> {
            TargetScene(
                sceneKey = downOrRight,
                distance = maxDistance,
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

private fun CoroutineScope.onDragStopped(
    layoutImpl: SceneTransitionLayoutImpl,
    transition: SwipeTransition,
    velocity: Float,
    velocityThreshold: Float,
    positionalThreshold: Float,
    canChangeScene: Boolean = true,
) {
    // The state was changed since the drag started; don't do anything.
    if (layoutImpl.state.transitionState != transition) {
        return
    }

    // We were not animating.
    if (transition._fromScene == transition._toScene) {
        layoutImpl.state.transitionState = TransitionState.Idle(transition._fromScene.key)
        return
    }

    // Compute the destination scene (and therefore offset) to settle in.
    val targetScene: Scene
    val targetOffset: Float
    val offset = transition.dragOffset
    val distance = transition.distance
    if (
        canChangeScene &&
            shouldCommitSwipe(
                offset,
                distance,
                velocity,
                velocityThreshold,
                positionalThreshold,
                wasCommitted = transition._currentScene == transition._toScene,
            )
    ) {
        targetOffset = distance
        targetScene = transition._toScene
    } else {
        targetOffset = 0f
        targetScene = transition._fromScene
    }

    // If the effective current scene changed, it should be reflected right now in the current scene
    // state, even before the settle animation is ongoing. That way all the swipeables and back
    // handlers will be refreshed and the user can for instance quickly swipe vertically from A => B
    // then horizontally from B => C, or swipe from A => B then immediately go back B => A.
    if (targetScene != transition._currentScene) {
        transition._currentScene = targetScene
        layoutImpl.onChangeScene(targetScene.key)
    }

    animateOffset(
        transition = transition,
        layoutImpl = layoutImpl,
        initialVelocity = velocity,
        targetOffset = targetOffset,
        targetScene = targetScene.key
    )
}

/**
 * Whether the swipe to the target scene should be committed or not. This is inspired by
 * SwipeableV2.computeTarget().
 */
private fun shouldCommitSwipe(
    offset: Float,
    distance: Float,
    velocity: Float,
    velocityThreshold: Float,
    positionalThreshold: Float,
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

private fun CoroutineScope.animateOffset(
    transition: SwipeTransition,
    layoutImpl: SceneTransitionLayoutImpl,
    initialVelocity: Float,
    targetOffset: Float,
    targetScene: SceneKey,
) {
    transition.offsetAnimationJob = launch {
        if (!transition.isAnimatingOffset) {
            transition.offsetAnimatable.snapTo(transition.dragOffset)
        }
        transition.isAnimatingOffset = true

        transition.offsetAnimatable.animateTo(
            targetOffset,
            // TODO(b/290184746): Make this spring spec configurable.
            spring(
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = OffsetVisibilityThreshold
            ),
            initialVelocity = initialVelocity,
        )

        // Now that the animation is done, the state should be idle. Note that if the state was
        // changed since this animation started, some external code changed it and we shouldn't do
        // anything here. Note also that this job will be cancelled in the case where the user
        // intercepts this swipe.
        if (layoutImpl.state.transitionState == transition) {
            layoutImpl.state.transitionState = TransitionState.Idle(targetScene)
        }

        transition.offsetAnimationJob = null
    }
}

private fun CoroutineScope.animateOverscroll(
    layoutImpl: SceneTransitionLayoutImpl,
    transition: SwipeTransition,
    velocity: Velocity,
    orientation: Orientation,
): Velocity {
    val velocityAmount =
        when (orientation) {
            Orientation.Vertical -> velocity.y
            Orientation.Horizontal -> velocity.x
        }

    if (velocityAmount == 0f) {
        // There is no remaining velocity
        return Velocity.Zero
    }

    val fromScene = layoutImpl.scene(layoutImpl.state.transitionState.currentScene)
    val target = fromScene.findTargetSceneAndDistance(orientation, velocityAmount, layoutImpl)
    val isValidTarget = target.distance != 0f && target.sceneKey != fromScene.key

    if (!isValidTarget || layoutImpl.state.transitionState == transition) {
        // We have not found a valid target or we are already in a transition
        return Velocity.Zero
    }

    transition._currentScene = fromScene
    transition._fromScene = fromScene
    transition._toScene = layoutImpl.scene(target.sceneKey)
    transition._distance = target.distance
    transition.absoluteDistance = target.distance.absoluteValue
    transition.dragOffset = 0f
    transition.isAnimatingOffset = false
    transition.offsetAnimationJob = null

    layoutImpl.state.transitionState = transition

    animateOffset(
        transition = transition,
        layoutImpl = layoutImpl,
        initialVelocity = velocityAmount,
        targetOffset = 0f,
        targetScene = fromScene.key
    )

    // The animateOffset animation consumes any remaining velocity.
    return velocity
}

/**
 * The number of pixels below which there won't be a visible difference in the transition and from
 * which the animation can stop.
 */
private const val OffsetVisibilityThreshold = 0.5f

@Composable
private fun rememberSwipeToSceneNestedScrollConnection(
    orientation: Orientation,
    coroutineScope: CoroutineScope,
    draggableState: DraggableState,
    transition: SwipeTransition,
    layoutImpl: SceneTransitionLayoutImpl,
    velocityThreshold: Float,
    positionalThreshold: Float,
): PriorityPostNestedScrollConnection {
    val density = LocalDensity.current
    val scrollConnection =
        remember(
            orientation,
            coroutineScope,
            draggableState,
            transition,
            layoutImpl,
            velocityThreshold,
            positionalThreshold,
            density,
        ) {
            fun Offset.toAmount() =
                when (orientation) {
                    Orientation.Horizontal -> x
                    Orientation.Vertical -> y
                }

            fun Velocity.toAmount() =
                when (orientation) {
                    Orientation.Horizontal -> x
                    Orientation.Vertical -> y
                }

            fun Float.toOffset() =
                when (orientation) {
                    Orientation.Horizontal -> Offset(x = this, y = 0f)
                    Orientation.Vertical -> Offset(x = 0f, y = this)
                }

            // The next potential scene is calculated during the canStart
            var nextScene: SceneKey? = null

            // This is the scene on which we will have priority during the scroll gesture.
            var priorityScene: SceneKey? = null

            // If we performed a long gesture before entering priority mode, we would have to avoid
            // moving on to the next scene.
            var gestureStartedOnNestedChild = false

            PriorityPostNestedScrollConnection(
                canStart = { offsetAvailable, offsetBeforeStart ->
                    val amount = offsetAvailable.toAmount()
                    if (amount == 0f) return@PriorityPostNestedScrollConnection false

                    gestureStartedOnNestedChild = offsetBeforeStart != Offset.Zero

                    val fromScene = layoutImpl.scene(layoutImpl.state.transitionState.currentScene)
                    nextScene =
                        when {
                            amount < 0f -> fromScene.upOrLeft(orientation)
                            amount > 0f -> fromScene.downOrRight(orientation)
                            else -> null
                        }

                    nextScene != null
                },
                canContinueScroll = { priorityScene == transition._toScene.key },
                onStart = {
                    priorityScene = nextScene
                    onDragStarted(layoutImpl, transition, orientation)
                },
                onScroll = { offsetAvailable ->
                    val amount = offsetAvailable.toAmount()

                    // TODO(b/297842071) We should handle the overscroll or slow drag if the gesture
                    // is initiated in a nested child.

                    // Appends a new coroutine to attempt to drag by [amount] px. In this case we
                    // are assuming that the [coroutineScope] is tied to the main thread and that
                    // calls to [launch] are therefore queued.
                    coroutineScope.launch { draggableState.drag { dragBy(amount) } }

                    amount.toOffset()
                },
                onStop = { velocityAvailable ->
                    priorityScene = null

                    coroutineScope.onDragStopped(
                        layoutImpl = layoutImpl,
                        transition = transition,
                        velocity = velocityAvailable.toAmount(),
                        velocityThreshold = velocityThreshold,
                        positionalThreshold = positionalThreshold,
                        canChangeScene = !gestureStartedOnNestedChild
                    )

                    // The onDragStopped animation consumes any remaining velocity.
                    velocityAvailable
                },
                onPostFling = { velocityAvailable ->
                    // If there is any velocity left, we can try running an overscroll animation
                    // between scenes.
                    coroutineScope.animateOverscroll(
                        layoutImpl = layoutImpl,
                        transition = transition,
                        velocity = velocityAvailable,
                        orientation = orientation
                    )
                },
            )
        }
    DisposableEffect(scrollConnection) {
        onDispose {
            coroutineScope.launch {
                // This should ensure that the draggableState is in a consistent state and that it
                // does not cause any unexpected behavior.
                scrollConnection.reset()
            }
        }
    }
    return scrollConnection
}
