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

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.TransitionState.HasOverscrollProperties.Companion.DistanceUnspecified

/** Define the [transitions][SceneTransitions] to be used with a [SceneTransitionLayout]. */
fun transitions(builder: SceneTransitionsBuilder.() -> Unit): SceneTransitions {
    return transitionsImpl(builder)
}

@DslMarker annotation class TransitionDsl

@TransitionDsl
interface SceneTransitionsBuilder {
    /**
     * The default [AnimationSpec] used when after the user lifts their finger after starting a
     * swipe to transition, to animate back into one of the 2 scenes we are transitioning to.
     */
    var defaultSwipeSpec: SpringSpec<Float>

    /**
     * Define the default animation to be played when transitioning [to] the specified scene, from
     * any scene. For the animation specification to apply only when transitioning between two
     * specific scenes, use [from] instead.
     *
     * If [key] is not `null`, then this transition will only be used if the same key is specified
     * when triggering the transition.
     *
     * @see from
     */
    fun to(
        to: SceneKey,
        key: TransitionKey? = null,
        builder: TransitionBuilder.() -> Unit = {},
    ): TransitionSpec

    /**
     * Define the animation to be played when transitioning [from] the specified scene. For the
     * animation specification to apply only when transitioning between two specific scenes, pass
     * the destination scene via the [to] argument.
     *
     * When looking up which transition should be used when animating from scene A to scene B, we
     * pick the single transition with the given [key] and matching one of these predicates (in
     * order of importance):
     * 1. from == A && to == B
     * 2. to == A && from == B, which is then treated in reverse.
     * 3. (from == A && to == null) || (from == null && to == B)
     * 4. (from == B && to == null) || (from == null && to == A), which is then treated in reverse.
     */
    fun from(
        from: SceneKey,
        to: SceneKey? = null,
        key: TransitionKey? = null,
        builder: TransitionBuilder.() -> Unit = {},
    ): TransitionSpec

    /**
     * Define the animation to be played when the [scene] is overscrolled in the given
     * [orientation].
     *
     * The overscroll animation always starts from a progress of 0f, and reaches 1f when moving the
     * [distance] down/right, -1f when moving in the opposite direction.
     */
    fun overscroll(
        scene: SceneKey,
        orientation: Orientation,
        builder: OverscrollBuilder.() -> Unit = {},
    ): OverscrollSpec
}

interface BaseTransitionBuilder : PropertyTransformationBuilder {
    /**
     * The distance it takes for this transition to animate from 0% to 100% when it is driven by a
     * [UserAction].
     *
     * If `null`, a default distance will be used that depends on the [UserAction] performed.
     */
    var distance: UserActionDistance?

    /**
     * Define a progress-based range for the transformations inside [builder].
     *
     * For instance, the following will fade `Foo` during the first half of the transition then it
     * will translate it by 100.dp during the second half.
     *
     * ```
     * fractionRange(end = 0.5f) { fade(Foo) }
     * fractionRange(start = 0.5f) { translate(Foo, x = 100.dp) }
     * ```
     *
     * @param start the start of the range, in the [0; 1] range.
     * @param end the end of the range, in the [0; 1] range.
     */
    fun fractionRange(
        start: Float? = null,
        end: Float? = null,
        builder: PropertyTransformationBuilder.() -> Unit,
    )
}

@TransitionDsl
interface TransitionBuilder : BaseTransitionBuilder {
    /**
     * The [AnimationSpec] used to animate the associated transition progress from `0` to `1` when
     * the transition is triggered (i.e. it is not gesture-based).
     */
    var spec: AnimationSpec<Float>

    /**
     * The [SpringSpec] used to animate the associated transition progress when the transition was
     * started by a swipe and is now animating back to a scene because the user lifted their finger.
     *
     * If `null`, then the [SceneTransitionsBuilder.defaultSwipeSpec] will be used.
     */
    var swipeSpec: SpringSpec<Float>?

    /**
     * Define a timestamp-based range for the transformations inside [builder].
     *
     * For instance, the following will fade `Foo` during the first half of the transition then it
     * will translate it by 100.dp during the second half.
     *
     * ```
     * spec = tween(500)
     * timestampRange(end = 250) { fade(Foo) }
     * timestampRange(start = 250) { translate(Foo, x = 100.dp) }
     * ```
     *
     * Important: [spec] must be a [androidx.compose.animation.core.DurationBasedAnimationSpec] if
     * you call [timestampRange], otherwise this will throw. The spec duration will be used to
     * transform this range into a [fractionRange].
     *
     * @param startMillis the start of the range, in the [0; spec.duration] range.
     * @param endMillis the end of the range, in the [0; spec.duration] range.
     */
    fun timestampRange(
        startMillis: Int? = null,
        endMillis: Int? = null,
        builder: PropertyTransformationBuilder.() -> Unit,
    )

    /**
     * Configure the shared transition when [matcher] is shared between two scenes.
     *
     * @param enabled whether the matched element(s) should actually be shared in this transition.
     *   Defaults to true.
     */
    fun sharedElement(matcher: ElementMatcher, enabled: Boolean = true)

    /**
     * Adds the transformations in [builder] but in reversed order. This allows you to partially
     * reuse the definition of the transition from scene `Foo` to scene `Bar` inside the definition
     * of the transition from scene `Bar` to scene `Foo`.
     */
    fun reversed(builder: TransitionBuilder.() -> Unit)
}

@TransitionDsl
interface OverscrollBuilder : BaseTransitionBuilder {
    /** Translate the element(s) matching [matcher] by ([x], [y]) pixels. */
    fun translate(
        matcher: ElementMatcher,
        x: OverscrollScope.() -> Float = { 0f },
        y: OverscrollScope.() -> Float = { 0f },
    )
}

interface OverscrollScope {
    /**
     * Return the absolute distance between fromScene and toScene, if available, otherwise
     * [DistanceUnspecified].
     */
    val absoluteDistance: Float
}

/**
 * An interface to decide where we should draw shared Elements or compose MovableElements.
 *
 * @see DefaultElementScenePicker
 * @see HighestZIndexScenePicker
 * @see LowestZIndexScenePicker
 * @see MovableElementScenePicker
 */
interface ElementScenePicker {
    /**
     * Return the scene in which [element] should be drawn (when using `Modifier.element(key)`) or
     * composed (when using `MovableElement(key)`) during the given [transition]. If this element
     * should not be drawn or composed in neither [transition.fromScene] nor [transition.toScene],
     * return `null`.
     *
     * Important: For [MovableElements][SceneScope.MovableElement], this scene picker will *always*
     * be used during transitions to decide whether we should compose that element in a given scene
     * or not. Therefore, you should make sure that the returned [SceneKey] contains the movable
     * element, otherwise that element will not be composed in any scene during the transition.
     */
    fun sceneDuringTransition(
        element: ElementKey,
        transition: TransitionState.Transition,
        fromSceneZIndex: Float,
        toSceneZIndex: Float,
    ): SceneKey?

    /**
     * Return [transition.fromScene] if it is in [scenes] and [transition.toScene] is not, or return
     * [transition.toScene] if it is in [scenes] and [transition.fromScene] is not. If neither
     * [transition.fromScene] and [transition.toScene] are in [scenes], return `null`. If both
     * [transition.fromScene] and [transition.toScene] are in [scenes], throw an exception.
     *
     * This function can be useful when computing the scene in which a movable element should be
     * composed.
     */
    fun pickSingleSceneIn(
        scenes: Set<SceneKey>,
        transition: TransitionState.Transition,
        element: ElementKey,
    ): SceneKey? {
        val fromScene = transition.fromScene
        val toScene = transition.toScene
        val fromSceneInScenes = scenes.contains(fromScene)
        val toSceneInScenes = scenes.contains(toScene)

        return when {
            fromSceneInScenes && toSceneInScenes -> {
                error(
                    "Element $element can be in both $fromScene and $toScene. You should add a " +
                        "special case for this transition before calling pickSingleSceneIn()."
                )
            }
            fromSceneInScenes -> fromScene
            toSceneInScenes -> toScene
            else -> null
        }
    }
}

/** An [ElementScenePicker] that draws/composes elements in the scene with the highest z-order. */
object HighestZIndexScenePicker : ElementScenePicker {
    override fun sceneDuringTransition(
        element: ElementKey,
        transition: TransitionState.Transition,
        fromSceneZIndex: Float,
        toSceneZIndex: Float
    ): SceneKey {
        return if (fromSceneZIndex > toSceneZIndex) {
            transition.fromScene
        } else {
            transition.toScene
        }
    }
}

/** An [ElementScenePicker] that draws/composes elements in the scene with the lowest z-order. */
object LowestZIndexScenePicker : ElementScenePicker {
    override fun sceneDuringTransition(
        element: ElementKey,
        transition: TransitionState.Transition,
        fromSceneZIndex: Float,
        toSceneZIndex: Float
    ): SceneKey {
        return if (fromSceneZIndex < toSceneZIndex) {
            transition.fromScene
        } else {
            transition.toScene
        }
    }
}

/**
 * An [ElementScenePicker] that draws/composes elements in the scene we are transitioning to, iff
 * that scene is in [scenes].
 *
 * This picker can be useful for movable elements whose content size depends on its content (because
 * it wraps it) in at least one scene. That way, the target size of the MovableElement will be
 * computed in the scene we are going to and, given that this element was probably already composed
 * in the scene we are going from before starting the transition, the interpolated size of the
 * movable element during the transition should be correct.
 *
 * The downside of this picker is that the zIndex of the element when going from scene A to scene B
 * is not the same as when going from scene B to scene A, so it's not usable in situations where
 * z-ordering during the transition matters.
 */
class MovableElementScenePicker(private val scenes: Set<SceneKey>) : ElementScenePicker {
    override fun sceneDuringTransition(
        element: ElementKey,
        transition: TransitionState.Transition,
        fromSceneZIndex: Float,
        toSceneZIndex: Float,
    ): SceneKey? {
        return when {
            scenes.contains(transition.toScene) -> transition.toScene
            scenes.contains(transition.fromScene) -> transition.fromScene
            else -> null
        }
    }
}

/** The default [ElementScenePicker]. */
val DefaultElementScenePicker = HighestZIndexScenePicker

@TransitionDsl
interface PropertyTransformationBuilder {
    /**
     * Fade the element(s) matching [matcher]. This will automatically fade in or fade out if the
     * element is entering or leaving the scene, respectively.
     */
    fun fade(matcher: ElementMatcher)

    /** Translate the element(s) matching [matcher] by ([x], [y]) dp. */
    fun translate(matcher: ElementMatcher, x: Dp = 0.dp, y: Dp = 0.dp)

    /**
     * Translate the element(s) matching [matcher] from/to the [edge] of the [SceneTransitionLayout]
     * animating it.
     *
     * If [startsOutsideLayoutBounds] is `true`, then the element will start completely outside of
     * the layout bounds (i.e. none of it will be visible at progress = 0f if the layout clips its
     * content). If it is `false`, then the element will start aligned with the edge of the layout
     * (i.e. it will be completely visible at progress = 0f).
     */
    fun translate(matcher: ElementMatcher, edge: Edge, startsOutsideLayoutBounds: Boolean = true)

    /**
     * Translate the element(s) matching [matcher] by the same amount that [anchor] is translated
     * during this transition.
     *
     * Note: This currently only works if [anchor] is a shared element of this transition.
     *
     * TODO(b/290184746): Also support anchors that are not shared but translated because of other
     *   transformations, like an edge translation.
     */
    fun anchoredTranslate(matcher: ElementMatcher, anchor: ElementKey)

    /**
     * Scale the [width] and [height] of the element(s) matching [matcher]. Note that this scaling
     * is done during layout, so it will potentially impact the size and position of other elements.
     */
    fun scaleSize(matcher: ElementMatcher, width: Float = 1f, height: Float = 1f)

    /**
     * Scale the drawing with [scaleX] and [scaleY] of the element(s) matching [matcher]. Note this
     * will only scale the draw inside of an element, therefore it won't impact layout of elements
     * around it.
     */
    fun scaleDraw(
        matcher: ElementMatcher,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        pivot: Offset = Offset.Unspecified
    )

    /**
     * Scale the element(s) matching [matcher] so that it grows/shrinks to the same size as
     * [anchor].
     *
     * Note: This currently only works if [anchor] is a shared element of this transition.
     */
    fun anchoredSize(
        matcher: ElementMatcher,
        anchor: ElementKey,
        anchorWidth: Boolean = true,
        anchorHeight: Boolean = true,
    )
}
