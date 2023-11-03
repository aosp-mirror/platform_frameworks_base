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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Define the [transitions][SceneTransitions] to be used with a [SceneTransitionLayout]. */
fun transitions(builder: SceneTransitionsBuilder.() -> Unit): SceneTransitions {
    return transitionsImpl(builder)
}

@DslMarker annotation class TransitionDsl

@TransitionDsl
interface SceneTransitionsBuilder {
    /**
     * Define the default animation to be played when transitioning [to] the specified scene, from
     * any scene. For the animation specification to apply only when transitioning between two
     * specific scenes, use [from] instead.
     *
     * @see from
     */
    fun to(
        to: SceneKey,
        builder: TransitionBuilder.() -> Unit = {},
    ): TransitionSpec

    /**
     * Define the animation to be played when transitioning [from] the specified scene. For the
     * animation specification to apply only when transitioning between two specific scenes, pass
     * the destination scene via the [to] argument.
     *
     * When looking up which transition should be used when animating from scene A to scene B, we
     * pick the single transition matching one of these predicates (in order of importance):
     * 1. from == A && to == B
     * 2. to == A && from == B, which is then treated in reverse.
     * 3. (from == A && to == null) || (from == null && to == B)
     * 4. (from == B && to == null) || (from == null && to == A), which is then treated in reverse.
     */
    fun from(
        from: SceneKey,
        to: SceneKey? = null,
        builder: TransitionBuilder.() -> Unit = {},
    ): TransitionSpec
}

@TransitionDsl
interface TransitionBuilder : PropertyTransformationBuilder {
    /**
     * The [AnimationSpec] used to animate the progress of this transition from `0` to `1` when
     * performing programmatic (not input pointer tracking) animations.
     */
    var spec: AnimationSpec<Float>

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
     * @param scenePicker the [SharedElementScenePicker] to use when deciding in which scene we
     *   should draw or compose this shared element.
     */
    fun sharedElement(
        matcher: ElementMatcher,
        enabled: Boolean = true,
        scenePicker: SharedElementScenePicker = DefaultSharedElementScenePicker,
    )

    /**
     * Punch a hole in the element(s) matching [matcher] that has the same bounds as [bounds] and
     * using the given [shape].
     *
     * Punching a hole in an element will "remove" any pixel drawn by that element in the hole area.
     * This can be used to make content drawn below an opaque element visible. For example, if we
     * have [this lockscreen scene](http://shortn/_VYySFnJDhN) drawn below
     * [this shade scene](http://shortn/_fpxGUk0Rg7) and punch a hole in the latter using the big
     * clock time bounds and a RoundedCornerShape(10dp), [this](http://shortn/_qt80IvORFj) would be
     * the result.
     */
    fun punchHole(matcher: ElementMatcher, bounds: ElementKey, shape: Shape = RectangleShape)

    /**
     * Adds the transformations in [builder] but in reversed order. This allows you to partially
     * reuse the definition of the transition from scene `Foo` to scene `Bar` inside the definition
     * of the transition from scene `Bar` to scene `Foo`.
     */
    fun reversed(builder: TransitionBuilder.() -> Unit)
}

interface SharedElementScenePicker {
    /**
     * Return the scene in which [element] should be drawn (when using `Modifier.element(key)`) or
     * composed (when using `MovableElement(key)`) during the transition from [fromScene] to
     * [toScene].
     */
    fun sceneDuringTransition(
        element: ElementKey,
        fromScene: SceneKey,
        toScene: SceneKey,
        progress: () -> Float,
        fromSceneZIndex: Float,
        toSceneZIndex: Float,
    ): SceneKey
}

object DefaultSharedElementScenePicker : SharedElementScenePicker {
    override fun sceneDuringTransition(
        element: ElementKey,
        fromScene: SceneKey,
        toScene: SceneKey,
        progress: () -> Float,
        fromSceneZIndex: Float,
        toSceneZIndex: Float
    ): SceneKey {
        // By default shared elements are drawn in the highest scene possible, unless it is a
        // background.
        return if (
            (fromSceneZIndex > toSceneZIndex && !element.isBackground) ||
                (fromSceneZIndex < toSceneZIndex && element.isBackground)
        ) {
            fromScene
        } else {
            toScene
        }
    }
}

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
     * Scale the element(s) matching [matcher] so that it grows/shrinks to the same size as [anchor]
     * .
     *
     * Note: This currently only works if [anchor] is a shared element of this transition.
     */
    fun anchoredSize(matcher: ElementMatcher, anchor: ElementKey)
}

/** The edge of a [SceneTransitionLayout]. */
enum class Edge {
    Left,
    Right,
    Top,
    Bottom,
}
