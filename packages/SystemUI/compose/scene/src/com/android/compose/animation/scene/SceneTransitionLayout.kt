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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalDensity

/**
 * [SceneTransitionLayout] is a container that automatically animates its content whenever its state
 * changes.
 *
 * Note: You should use [androidx.compose.animation.AnimatedContent] instead of
 * [SceneTransitionLayout] if it fits your need. Use [SceneTransitionLayout] over AnimatedContent if
 * you need support for swipe gestures, shared elements or transitions defined declaratively outside
 * UI code.
 *
 * @param state the state of this layout.
 * @param edgeDetector the edge detector used to detect which edge a swipe is started from, if any.
 * @param transitionInterceptionThreshold used during a scene transition. For the scene to be
 *   intercepted, the progress value must be above the threshold, and below (1 - threshold).
 * @param scenes the configuration of the different scenes of this layout.
 * @see updateSceneTransitionLayoutState
 */
@Composable
fun SceneTransitionLayout(
    state: SceneTransitionLayoutState,
    modifier: Modifier = Modifier,
    edgeDetector: EdgeDetector = DefaultEdgeDetector,
    @FloatRange(from = 0.0, to = 0.5) transitionInterceptionThreshold: Float = 0f,
    scenes: SceneTransitionLayoutScope.() -> Unit,
) {
    SceneTransitionLayoutForTesting(
        state,
        modifier,
        edgeDetector,
        transitionInterceptionThreshold,
        onLayoutImpl = null,
        scenes,
    )
}

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
    edgeDetector: EdgeDetector = DefaultEdgeDetector,
    @FloatRange(from = 0.0, to = 0.5) transitionInterceptionThreshold: Float = 0f,
    scenes: SceneTransitionLayoutScope.() -> Unit,
) {
    val state = updateSceneTransitionLayoutState(currentScene, onChangeScene, transitions)
    SceneTransitionLayout(
        state,
        modifier,
        edgeDetector,
        transitionInterceptionThreshold,
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

@Stable
@ElementDsl
interface BaseSceneScope {
    /** The state of the [SceneTransitionLayout] in which this scene is contained. */
    val layoutState: SceneTransitionLayoutState

    /**
     * Tag an element identified by [key].
     *
     * Tagging an element will allow you to reference that element when defining transitions, so
     * that the element can be transformed and animated when the scene transitions in or out.
     *
     * Additionally, this [key] will be used to detect elements that are shared between scenes to
     * automatically interpolate their size and offset. If you need to animate shared element values
     * (i.e. values associated to this element that change depending on which scene it is composed
     * in), use [Element] instead.
     *
     * Note that shared elements tagged using this function will be duplicated in each scene they
     * are part of, so any **internal** state (e.g. state created using `remember {
     * mutableStateOf(...) }`) will be lost. If you need to preserve internal state, you should use
     * [MovableElement] instead.
     *
     * @see Element
     * @see MovableElement
     */
    fun Modifier.element(key: ElementKey): Modifier

    /**
     * Create an element identified by [key].
     *
     * Similar to [element], this creates an element that will be automatically shared when present
     * in multiple scenes and that can be transformed during transitions, the same way that
     * [element] does.
     *
     * The only difference with [element] is that the provided [ElementScope] allows you to
     * [animate element values][ElementScope.animateElementValueAsState] or specify its
     * [movable content][Element.movableContent] that will be "moved" and composed only once during
     * transitions (as opposed to [element] that duplicates shared elements) so that any internal
     * state is preserved during and after the transition.
     *
     * @see element
     * @see MovableElement
     */
    @Composable
    fun Element(
        key: ElementKey,
        modifier: Modifier,

        // TODO(b/317026105): As discussed in http://shortn/_gJVdltF8Si, remove the @Composable
        // scope here to make sure that callers specify the content in ElementScope.content {} or
        // ElementScope.movableContent {}.
        content: @Composable ElementScope<ElementContentScope>.() -> Unit,
    )

    /**
     * Create a *movable* element identified by [key].
     *
     * Similar to [Element], this creates an element that will be automatically shared when present
     * in multiple scenes and that can be transformed during transitions, and you can also use the
     * provided [ElementScope] to [animate element values][ElementScope.animateElementValueAsState].
     *
     * The important difference with [element] and [Element] is that this element
     * [content][ElementScope.content] will be "moved" and composed only once during transitions, as
     * opposed to [element] and [Element] that duplicates shared elements, so that any internal
     * state is preserved during and after the transition.
     *
     * @see element
     * @see Element
     */
    @Composable
    fun MovableElement(
        key: ElementKey,
        modifier: Modifier,

        // TODO(b/317026105): As discussed in http://shortn/_gJVdltF8Si, remove the @Composable
        // scope here to make sure that callers specify the content in ElementScope.content {} or
        // ElementScope.movableContent {}.
        content: @Composable ElementScope<MovableElementContentScope>.() -> Unit,
    )

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
     * Don't resize during transitions. This can for instance be used to make sure that scrollable
     * lists keep a constant size during transitions even if its elements are growing/shrinking.
     */
    fun Modifier.noResizeDuringTransitions(): Modifier
}

@Stable
@ElementDsl
interface SceneScope : BaseSceneScope {
    /**
     * Animate some value at the scene level.
     *
     * @param value the value of this shared value in the current scene.
     * @param key the key of this shared value.
     * @param lerp the *linear* interpolation function that should be used to interpolate between
     *   two different values. Note that it has to be linear because the [fraction] passed to this
     *   interpolator is already interpolated.
     * @param canOverflow whether this value can overflow past the values it is interpolated
     *   between, for instance because the transition is animated using a bouncy spring.
     * @see animateSceneIntAsState
     * @see animateSceneFloatAsState
     * @see animateSceneDpAsState
     * @see animateSceneColorAsState
     */
    @Composable
    fun <T> animateSceneValueAsState(
        value: T,
        key: ValueKey,
        lerp: (start: T, stop: T, fraction: Float) -> T,
        canOverflow: Boolean,
    ): AnimatedState<T>
}

@Stable
@ElementDsl
interface ElementScope<ContentScope> {
    /**
     * Animate some value associated to this element.
     *
     * @param value the value of this shared value in the current scene.
     * @param key the key of this shared value.
     * @param lerp the *linear* interpolation function that should be used to interpolate between
     *   two different values. Note that it has to be linear because the [fraction] passed to this
     *   interpolator is already interpolated.
     * @param canOverflow whether this value can overflow past the values it is interpolated
     *   between, for instance because the transition is animated using a bouncy spring.
     * @see animateElementIntAsState
     * @see animateElementFloatAsState
     * @see animateElementDpAsState
     * @see animateElementColorAsState
     */
    @Composable
    fun <T> animateElementValueAsState(
        value: T,
        key: ValueKey,
        lerp: (start: T, stop: T, fraction: Float) -> T,
        canOverflow: Boolean,
    ): AnimatedState<T>

    /**
     * The content of this element.
     *
     * Important: This must be called exactly once, after all calls to [animateElementValueAsState].
     */
    @Composable fun content(content: @Composable ContentScope.() -> Unit)
}

/**
 * The exact same scope as [androidx.compose.foundation.layout.BoxScope].
 *
 * We can't reuse BoxScope directly because of the @LayoutScopeMarker annotation on it, which would
 * prevent us from calling Modifier.element() and other methods of [SceneScope] inside any Box {} in
 * the [content][ElementScope.content] of a [SceneScope.Element] or a [SceneScope.MovableElement].
 */
@Stable
@ElementDsl
interface ElementBoxScope {
    /** @see [androidx.compose.foundation.layout.BoxScope.align]. */
    @Stable fun Modifier.align(alignment: Alignment): Modifier

    /** @see [androidx.compose.foundation.layout.BoxScope.matchParentSize]. */
    @Stable fun Modifier.matchParentSize(): Modifier
}

/** The scope for "normal" (not movable) elements. */
@Stable @ElementDsl interface ElementContentScope : SceneScope, ElementBoxScope

/**
 * The scope for the content of movable elements.
 *
 * Note that it extends [BaseSceneScope] and not [SceneScope] because movable elements should not
 * call [SceneScope.animateSceneValueAsState], given that their content is not composed in all
 * scenes.
 */
@Stable @ElementDsl interface MovableElementContentScope : BaseSceneScope, ElementBoxScope

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
    state: SceneTransitionLayoutState,
    modifier: Modifier = Modifier,
    edgeDetector: EdgeDetector = DefaultEdgeDetector,
    transitionInterceptionThreshold: Float = 0f,
    onLayoutImpl: ((SceneTransitionLayoutImpl) -> Unit)? = null,
    scenes: SceneTransitionLayoutScope.() -> Unit,
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val layoutImpl = remember {
        SceneTransitionLayoutImpl(
                state = state as BaseSceneTransitionLayoutState,
                density = density,
                edgeDetector = edgeDetector,
                transitionInterceptionThreshold = transitionInterceptionThreshold,
                builder = scenes,
                coroutineScope = coroutineScope,
            )
            .also { onLayoutImpl?.invoke(it) }
    }

    // TODO(b/317014852): Move this into the SideEffect {} again once STLImpl.scenes is not a
    // SnapshotStateMap anymore.
    layoutImpl.updateScenes(scenes)

    SideEffect {
        if (state != layoutImpl.state) {
            error(
                "This SceneTransitionLayout was bound to a different SceneTransitionLayoutState" +
                    " that was used when creating it, which is not supported"
            )
        }

        layoutImpl.density = density
        layoutImpl.edgeDetector = edgeDetector
    }

    layoutImpl.Content(modifier)
}
