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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastForEach
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.transformation.AnchoredSize
import com.android.compose.animation.scene.transformation.AnchoredTranslate
import com.android.compose.animation.scene.transformation.DrawScale
import com.android.compose.animation.scene.transformation.EdgeTranslate
import com.android.compose.animation.scene.transformation.Fade
import com.android.compose.animation.scene.transformation.OverscrollTranslate
import com.android.compose.animation.scene.transformation.PropertyTransformation
import com.android.compose.animation.scene.transformation.RangedPropertyTransformation
import com.android.compose.animation.scene.transformation.ScaleSize
import com.android.compose.animation.scene.transformation.SharedElementTransformation
import com.android.compose.animation.scene.transformation.Transformation
import com.android.compose.animation.scene.transformation.Translate

/** The transitions configuration of a [SceneTransitionLayout]. */
class SceneTransitions
internal constructor(
    internal val defaultSwipeSpec: SpringSpec<Float>,
    internal val transitionSpecs: List<TransitionSpecImpl>,
    internal val overscrollSpecs: List<OverscrollSpecImpl>,
    internal val interruptionHandler: InterruptionHandler,
    internal val defaultProgressConverter: ProgressConverter,
) {
    private val transitionCache =
        mutableMapOf<
            ContentKey,
            MutableMap<ContentKey, MutableMap<TransitionKey?, TransitionSpecImpl>>,
        >()

    private val overscrollCache =
        mutableMapOf<ContentKey, MutableMap<Orientation, OverscrollSpecImpl?>>()

    internal fun transitionSpec(
        from: ContentKey,
        to: ContentKey,
        key: TransitionKey?,
    ): TransitionSpecImpl {
        return transitionCache
            .getOrPut(from) { mutableMapOf() }
            .getOrPut(to) { mutableMapOf() }
            .getOrPut(key) { findSpec(from, to, key) }
    }

    private fun findSpec(
        from: ContentKey,
        to: ContentKey,
        key: TransitionKey?,
    ): TransitionSpecImpl {
        val spec = transition(from, to, key) { it.from == from && it.to == to }
        if (spec != null) {
            return spec
        }

        val reversed = transition(from, to, key) { it.from == to && it.to == from }
        if (reversed != null) {
            return reversed.reversed()
        }

        val relaxedSpec =
            transition(from, to, key) {
                (it.from == from && it.to == null) || (it.to == to && it.from == null)
            }
        if (relaxedSpec != null) {
            return relaxedSpec
        }

        val relaxedReversed =
            transition(from, to, key) {
                (it.from == to && it.to == null) || (it.to == from && it.from == null)
            }
        if (relaxedReversed != null) {
            return relaxedReversed.reversed()
        }

        return if (key != null) {
            findSpec(from, to, null)
        } else {
            defaultTransition(from, to)
        }
    }

    private fun transition(
        from: ContentKey,
        to: ContentKey,
        key: TransitionKey?,
        filter: (TransitionSpecImpl) -> Boolean,
    ): TransitionSpecImpl? {
        var match: TransitionSpecImpl? = null
        transitionSpecs.fastForEach { spec ->
            if (spec.key == key && filter(spec)) {
                if (match != null) {
                    error("Found multiple transition specs for transition $from => $to")
                }
                match = spec
            }
        }
        return match
    }

    private fun defaultTransition(from: ContentKey, to: ContentKey) =
        TransitionSpecImpl(key = null, from, to, null, null, TransformationSpec.EmptyProvider)

    internal fun overscrollSpec(scene: ContentKey, orientation: Orientation): OverscrollSpecImpl? =
        overscrollCache
            .getOrPut(scene) { mutableMapOf() }
            .getOrPut(orientation) { overscroll(scene, orientation) { it.content == scene } }

    private fun overscroll(
        scene: ContentKey,
        orientation: Orientation,
        filter: (OverscrollSpecImpl) -> Boolean,
    ): OverscrollSpecImpl? {
        var match: OverscrollSpecImpl? = null
        overscrollSpecs.fastForEach { spec ->
            if (spec.orientation == orientation && filter(spec)) {
                if (match != null) {
                    error("Found multiple overscroll specs for overscroll $scene")
                }
                match = spec
            }
        }
        return match
    }

    companion object {
        internal val DefaultSwipeSpec =
            spring(
                stiffness = Spring.StiffnessMediumLow,
                dampingRatio = Spring.DampingRatioLowBouncy,
                visibilityThreshold = OffsetVisibilityThreshold,
            )

        val Empty =
            SceneTransitions(
                defaultSwipeSpec = DefaultSwipeSpec,
                transitionSpecs = emptyList(),
                overscrollSpecs = emptyList(),
                interruptionHandler = DefaultInterruptionHandler,
                defaultProgressConverter = ProgressConverter.Default,
            )
    }
}

/** The definition of a transition between [from] and [to]. */
interface TransitionSpec {
    /** The key of this [TransitionSpec]. */
    val key: TransitionKey?

    /**
     * The content we are transitioning from. If `null`, this spec can be used to animate from any
     * content.
     */
    val from: ContentKey?

    /**
     * The content we are transitioning to. If `null`, this spec can be used to animate from any
     * content.
     */
    val to: ContentKey?

    /**
     * Return a reversed version of this [TransitionSpec] for a transition going from [to] to
     * [from].
     */
    fun reversed(): TransitionSpec

    /**
     * The [TransformationSpec] associated to this [TransitionSpec] for the given [transition].
     *
     * Note that this is called once whenever a transition associated to this [TransitionSpec] is
     * started.
     */
    fun transformationSpec(transition: TransitionState.Transition): TransformationSpec

    /**
     * The preview [TransformationSpec] associated to this [TransitionSpec] for the given
     * [transition].
     *
     * Note that this is called once whenever a transition associated to this [TransitionSpec] is
     * started.
     */
    fun previewTransformationSpec(transition: TransitionState.Transition): TransformationSpec?
}

interface TransformationSpec {
    /**
     * The [AnimationSpec] used to animate the associated transition progress from `0` to `1` when
     * the transition is triggered (i.e. it is not gesture-based).
     */
    val progressSpec: AnimationSpec<Float>

    /**
     * The [SpringSpec] used to animate the associated transition progress when the transition was
     * started by a swipe and is now animating back to a scene because the user lifted their finger.
     *
     * If `null`, then the [SceneTransitions.defaultSwipeSpec] will be used.
     */
    val swipeSpec: SpringSpec<Float>?

    /**
     * The distance it takes for this transition to animate from 0% to 100% when it is driven by a
     * [UserAction].
     *
     * If `null`, a default distance will be used that depends on the [UserAction] performed.
     */
    val distance: UserActionDistance?

    /** The list of [Transformation] applied to elements during this transition. */
    val transformations: List<Transformation>

    companion object {
        internal val Empty =
            TransformationSpecImpl(
                progressSpec = snap(),
                swipeSpec = null,
                distance = null,
                transformations = emptyList(),
            )
        internal val EmptyProvider = { _: TransitionState.Transition -> Empty }
    }
}

internal class TransitionSpecImpl(
    override val key: TransitionKey?,
    override val from: ContentKey?,
    override val to: ContentKey?,
    private val previewTransformationSpec:
        ((TransitionState.Transition) -> TransformationSpecImpl)? =
        null,
    private val reversePreviewTransformationSpec:
        ((TransitionState.Transition) -> TransformationSpecImpl)? =
        null,
    private val transformationSpec: (TransitionState.Transition) -> TransformationSpecImpl,
) : TransitionSpec {
    override fun reversed(): TransitionSpecImpl {
        return TransitionSpecImpl(
            key = key,
            from = to,
            to = from,
            previewTransformationSpec = reversePreviewTransformationSpec,
            reversePreviewTransformationSpec = previewTransformationSpec,
            transformationSpec = { transition ->
                val reverse = transformationSpec.invoke(transition)
                TransformationSpecImpl(
                    progressSpec = reverse.progressSpec,
                    swipeSpec = reverse.swipeSpec,
                    distance = reverse.distance,
                    transformations = reverse.transformations.map { it.reversed() },
                )
            },
        )
    }

    override fun transformationSpec(
        transition: TransitionState.Transition
    ): TransformationSpecImpl = transformationSpec.invoke(transition)

    override fun previewTransformationSpec(
        transition: TransitionState.Transition
    ): TransformationSpecImpl? = previewTransformationSpec?.invoke(transition)
}

/** The definition of the overscroll behavior of the [content]. */
interface OverscrollSpec {
    /** The scene we are over scrolling. */
    val content: ContentKey

    /** The orientation of this [OverscrollSpec]. */
    val orientation: Orientation

    /** The [TransformationSpec] associated to this [OverscrollSpec]. */
    val transformationSpec: TransformationSpec

    /**
     * Function that takes a linear overscroll progress value ranging from 0 to +/- infinity and
     * outputs the desired **overscroll progress value**.
     *
     * When the progress value is:
     * - 0, the user is not overscrolling.
     * - 1, the user overscrolled by exactly the [OverscrollBuilder.distance].
     * - Greater than 1, the user overscrolled more than the [OverscrollBuilder.distance].
     */
    val progressConverter: ProgressConverter?
}

internal class OverscrollSpecImpl(
    override val content: ContentKey,
    override val orientation: Orientation,
    override val transformationSpec: TransformationSpecImpl,
    override val progressConverter: ProgressConverter?,
) : OverscrollSpec

/**
 * An implementation of [TransformationSpec] that allows the quick retrieval of an element
 * [ElementTransformations].
 */
internal class TransformationSpecImpl(
    override val progressSpec: AnimationSpec<Float>,
    override val swipeSpec: SpringSpec<Float>?,
    override val distance: UserActionDistance?,
    override val transformations: List<Transformation>,
) : TransformationSpec {
    private val cache = mutableMapOf<ElementKey, MutableMap<ContentKey, ElementTransformations>>()

    internal fun transformations(element: ElementKey, content: ContentKey): ElementTransformations {
        return cache
            .getOrPut(element) { mutableMapOf() }
            .getOrPut(content) { computeTransformations(element, content) }
    }

    /** Filter [transformations] to compute the [ElementTransformations] of [element]. */
    private fun computeTransformations(
        element: ElementKey,
        content: ContentKey,
    ): ElementTransformations {
        var shared: SharedElementTransformation? = null
        var offset: PropertyTransformation<Offset>? = null
        var size: PropertyTransformation<IntSize>? = null
        var drawScale: PropertyTransformation<Scale>? = null
        var alpha: PropertyTransformation<Float>? = null

        fun <T> onPropertyTransformation(
            root: PropertyTransformation<T>,
            current: PropertyTransformation<T> = root,
        ) {
            when (current) {
                is Translate,
                is OverscrollTranslate,
                is EdgeTranslate,
                is AnchoredTranslate -> {
                    throwIfNotNull(offset, element, name = "offset")
                    offset = root as PropertyTransformation<Offset>
                }
                is ScaleSize,
                is AnchoredSize -> {
                    throwIfNotNull(size, element, name = "size")
                    size = root as PropertyTransformation<IntSize>
                }
                is DrawScale -> {
                    throwIfNotNull(drawScale, element, name = "drawScale")
                    drawScale = root as PropertyTransformation<Scale>
                }
                is Fade -> {
                    throwIfNotNull(alpha, element, name = "alpha")
                    alpha = root as PropertyTransformation<Float>
                }
                is RangedPropertyTransformation -> onPropertyTransformation(root, current.delegate)
            }
        }

        transformations.fastForEach { transformation ->
            if (!transformation.matcher.matches(element, content)) {
                return@fastForEach
            }

            when (transformation) {
                is SharedElementTransformation -> {
                    throwIfNotNull(shared, element, name = "shared")
                    shared = transformation
                }
                is PropertyTransformation<*> -> onPropertyTransformation(transformation)
            }
        }

        return ElementTransformations(shared, offset, size, drawScale, alpha)
    }

    private fun throwIfNotNull(previous: Transformation?, element: ElementKey, name: String) {
        if (previous != null) {
            error("$element has multiple $name transformations")
        }
    }
}

/** The transformations of an element during a transition. */
internal class ElementTransformations(
    val shared: SharedElementTransformation?,
    val offset: PropertyTransformation<Offset>?,
    val size: PropertyTransformation<IntSize>?,
    val drawScale: PropertyTransformation<Scale>?,
    val alpha: PropertyTransformation<Float>?,
)
