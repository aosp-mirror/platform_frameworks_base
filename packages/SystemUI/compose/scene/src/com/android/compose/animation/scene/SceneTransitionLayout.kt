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

import androidx.annotation.FloatRange
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.channels.Channel

/**
 * [SceneTransitionLayout] is a container that automatically animates its content whenever
 * [currentScene] changes, using the transitions defined in [transitions].
 *
 * Note: You should use [androidx.compose.animation.AnimatedContent] instead of
 * [SceneTransitionLayout] if it fits your need. Use [SceneTransitionLayout] over AnimatedContent if
 * you need support for swipe gestures, shared elements or transitions defined declaratively outside
 * UI code.
 *
 * @param currentScene the current scene
 * @param onChangeScene a mutator that should set [currentScene] to the given scene when called.
 *   This is called when the user commits a transition to a new scene because of a [UserAction], for
 *   instance by triggering back navigation or by swiping to a new scene.
 * @param transitions the definition of the transitions used to animate a change of scene.
 * @param state the observable state of this layout.
 * @param edgeDetector the edge detector used to detect which edge a swipe is started from, if any.
 * @param transitionInterceptionThreshold used during a scene transition. For the scene to be
 *   intercepted, the progress value must be above the threshold, and below (1 - threshold).
 * @param scenes the configuration of the different scenes of this layout.
 */
@Composable
fun SceneTransitionLayout(
    currentScene: SceneKey,
    onChangeScene: (SceneKey) -> Unit,
    transitions: SceneTransitions,
    modifier: Modifier = Modifier,
    state: SceneTransitionLayoutState = remember { SceneTransitionLayoutState(currentScene) },
    edgeDetector: EdgeDetector = DefaultEdgeDetector,
    @FloatRange(from = 0.0, to = 0.5) transitionInterceptionThreshold: Float = 0f,
    scenes: SceneTransitionLayoutScope.() -> Unit,
) {
    SceneTransitionLayoutForTesting(
        currentScene,
        onChangeScene,
        transitions,
        state,
        edgeDetector,
        transitionInterceptionThreshold,
        modifier,
        onLayoutImpl = null,
        scenes,
    )
}

interface SceneTransitionLayoutScope {
    /**
     * Add a scene to this layout, identified by [key].
     *
     * You can configure [userActions] so that swiping on this layout or navigating back will
     * transition to a different scene.
     *
     * Important: scene order along the z-axis follows call order. Calling scene(A) followed by
     * scene(B) will mean that scene B renders after/above scene A.
     */
    fun scene(
        key: SceneKey,
        userActions: Map<UserAction, SceneKey> = emptyMap(),
        content: @Composable SceneScope.() -> Unit,
    )
}

/**
 * A DSL marker to prevent people from nesting calls to Modifier.element() inside a MovableElement,
 * which is not supported.
 */
@DslMarker annotation class ElementDsl

@ElementDsl
@Stable
interface SceneScope {
    /** The state of the [SceneTransitionLayout] in which this scene is contained. */
    val layoutState: SceneTransitionLayoutState

    /**
     * Tag an element identified by [key].
     *
     * Tagging an element will allow you to reference that element when defining transitions, so
     * that the element can be transformed and animated when the scene transitions in or out.
     *
     * Additionally, this [key] will be used to detect elements that are shared between scenes to
     * automatically interpolate their size, offset and [shared values][animateSharedValueAsState].
     *
     * Note that shared elements tagged using this function will be duplicated in each scene they
     * are part of, so any **internal** state (e.g. state created using `remember {
     * mutableStateOf(...) }`) will be lost. If you need to preserve internal state, you should use
     * [MovableElement] instead.
     *
     * @see MovableElement
     *
     * TODO(b/291566282): Migrate this to the new Modifier Node API and remove the @Composable
     *   constraint.
     */
    fun Modifier.element(key: ElementKey): Modifier

    /**
     * Adds a [NestedScrollConnection] to intercept scroll events not handled by the scrollable
     * component.
     *
     * @param leftBehavior when we should perform the overscroll animation at the left.
     * @param rightBehavior when we should perform the overscroll animation at the right.
     */
    fun Modifier.horizontalNestedScrollToScene(
        leftBehavior: NestedScrollBehavior = NestedScrollBehavior.EdgeNoPreview,
        rightBehavior: NestedScrollBehavior = NestedScrollBehavior.EdgeNoPreview,
    ): Modifier

    /**
     * Adds a [NestedScrollConnection] to intercept scroll events not handled by the scrollable
     * component.
     *
     * @param topBehavior when we should perform the overscroll animation at the top.
     * @param bottomBehavior when we should perform the overscroll animation at the bottom.
     */
    fun Modifier.verticalNestedScrollToScene(
        topBehavior: NestedScrollBehavior = NestedScrollBehavior.EdgeNoPreview,
        bottomBehavior: NestedScrollBehavior = NestedScrollBehavior.EdgeNoPreview,
    ): Modifier

    /**
     * Create a *movable* element identified by [key].
     *
     * This creates an element that will be automatically shared when present in multiple scenes and
     * that can be transformed during transitions, the same way that [element] does. The major
     * difference with [element] is that elements created with [MovableElement] will be "moved" and
     * composed only once during transitions (as opposed to [element] that duplicates shared
     * elements) so that any internal state is preserved during and after the transition.
     *
     * @see element
     */
    @Composable
    fun MovableElement(
        key: ElementKey,
        modifier: Modifier,
        content: @Composable MovableElementScope.() -> Unit,
    )

    /**
     * Animate some value of a shared element.
     *
     * @param value the value of this shared value in the current scene.
     * @param key the key of this shared value.
     * @param element the element associated with this value. If `null`, this value will be
     *   associated at the scene level, which means that [key] should be used maximum once in the
     *   same scene.
     * @param lerp the *linear* interpolation function that should be used to interpolate between
     *   two different values. Note that it has to be linear because the [fraction] passed to this
     *   interpolator is already interpolated.
     * @param canOverflow whether this value can overflow past the values it is interpolated
     *   between, for instance because the transition is animated using a bouncy spring.
     * @see animateSharedIntAsState
     * @see animateSharedFloatAsState
     * @see animateSharedDpAsState
     * @see animateSharedColorAsState
     */
    @Composable
    fun <T> animateSharedValueAsState(
        value: T,
        key: ValueKey,
        element: ElementKey?,
        lerp: (start: T, stop: T, fraction: Float) -> T,
        canOverflow: Boolean,
    ): State<T>

    /**
     * Punch a hole in this [element] using the bounds of [bounds] in [scene] and the given [shape].
     *
     * Punching a hole in an element will "remove" any pixel drawn by that element in the hole area.
     * This can be used to make content drawn below an opaque element visible. For example, if we
     * have [this lockscreen scene](http://shortn/_VYySFnJDhN) drawn below
     * [this shade scene](http://shortn/_fpxGUk0Rg7) and punch a hole in the latter using the big
     * clock time bounds and a RoundedCornerShape(10dp), [this](http://shortn/_qt80IvORFj) would be
     * the result.
     */
    fun Modifier.punchHole(element: ElementKey, bounds: ElementKey, shape: Shape): Modifier
}

// TODO(b/291053742): Add animateSharedValueAsState(targetValue) without any ValueKey and ElementKey
// arguments to allow sharing values inside a movable element.
@ElementDsl
interface MovableElementScope {
    @Composable
    fun <T> animateSharedValueAsState(
        value: T,
        debugName: String,
        lerp: (start: T, stop: T, fraction: Float) -> T,
        canOverflow: Boolean,
    ): State<T>
}

/** An action performed by the user. */
sealed interface UserAction

/** The user navigated back, either using a gesture or by triggering a KEYCODE_BACK event. */
data object Back : UserAction

/** The user swiped on the container. */
data class Swipe(
    val direction: SwipeDirection,
    val pointerCount: Int = 1,
    val fromEdge: Edge? = null,
) : UserAction {
    companion object {
        val Left = Swipe(SwipeDirection.Left)
        val Up = Swipe(SwipeDirection.Up)
        val Right = Swipe(SwipeDirection.Right)
        val Down = Swipe(SwipeDirection.Down)
    }
}

enum class SwipeDirection(val orientation: Orientation) {
    Up(Orientation.Vertical),
    Down(Orientation.Vertical),
    Left(Orientation.Horizontal),
    Right(Orientation.Horizontal),
}

/**
 * An internal version of [SceneTransitionLayout] to be used for tests.
 *
 * Important: You should use this only in tests and if you need to access the underlying
 * [SceneTransitionLayoutImpl]. In other cases, you should use [SceneTransitionLayout].
 */
@Composable
internal fun SceneTransitionLayoutForTesting(
    currentScene: SceneKey,
    onChangeScene: (SceneKey) -> Unit,
    transitions: SceneTransitions,
    state: SceneTransitionLayoutState,
    edgeDetector: EdgeDetector,
    transitionInterceptionThreshold: Float,
    modifier: Modifier,
    onLayoutImpl: ((SceneTransitionLayoutImpl) -> Unit)?,
    scenes: SceneTransitionLayoutScope.() -> Unit,
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val layoutImpl = remember {
        SceneTransitionLayoutImpl(
                state = state as SceneTransitionLayoutStateImpl,
                onChangeScene = onChangeScene,
                density = density,
                edgeDetector = edgeDetector,
                transitionInterceptionThreshold = transitionInterceptionThreshold,
                builder = scenes,
                coroutineScope = coroutineScope,
            )
            .also { onLayoutImpl?.invoke(it) }
    }

    val targetSceneChannel = remember { Channel<SceneKey>(Channel.CONFLATED) }
    SideEffect {
        if (state != layoutImpl.state) {
            error(
                "This SceneTransitionLayout was bound to a different SceneTransitionLayoutState" +
                    " that was used when creating it, which is not supported"
            )
        }

        layoutImpl.onChangeScene = onChangeScene
        (state as SceneTransitionLayoutStateImpl).transitions = transitions
        layoutImpl.density = density
        layoutImpl.edgeDetector = edgeDetector
        layoutImpl.updateScenes(scenes)

        state.transitions = transitions

        targetSceneChannel.trySend(currentScene)
    }

    LaunchedEffect(targetSceneChannel) {
        for (newKey in targetSceneChannel) {
            // Inspired by AnimateAsState.kt: let's poll the last value to avoid being one frame
            // late.
            val newKey = targetSceneChannel.tryReceive().getOrNull() ?: newKey
            animateToScene(layoutImpl.state, newKey)
        }
    }

    layoutImpl.Content(modifier)
}
