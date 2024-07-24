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
import com.android.compose.animation.scene.transformation.AnchoredSize
import com.android.compose.animation.scene.transformation.AnchoredTranslate
import com.android.compose.animation.scene.transformation.DrawScale
import com.android.compose.animation.scene.transformation.EdgeTranslate
import com.android.compose.animation.scene.transformation.Fade
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
) {
    private val transitionCache =
        mutableMapOf<
            SceneKey, MutableMap<SceneKey, MutableMap<TransitionKey?, TransitionSpecImpl>>
        >()

    private val overscrollCache =
        mutableMapOf<SceneKey, MutableMap<Orientation, OverscrollSpecImpl?>>()

    internal fun transitionSpec(
        from: SceneKey,
        to: SceneKey,
        key: TransitionKey?,
    ): TransitionSpecImpl {
        return transitionCache
            .getOrPut(from) { mutableMapOf() }
            .getOrPut(to) { mutableMapOf() }
            .getOrPut(key) { findSpec(from, to, key) }
    }

    private fun findSpec(from: SceneKey, to: SceneKey, key: TransitionKey?): TransitionSpecImpl {
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

        return transition(from, to, key) {
                (it.from == to && it.to == null) || (it.to == from && it.from == null)
            }
            ?.reversed()
            ?: defaultTransition(from, to)
    }

    private fun transition(
        from: SceneKey,
        to: SceneKey,
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

    private fun defaultTransition(from: SceneKey, to: SceneKey) =
        TransitionSpecImpl(key = null, from, to, TransformationSpec.EmptyProvider)

    internal fun overscrollSpec(scene: SceneKey, orientation: Orientation): OverscrollSpecImpl? =
        overscrollCache
            .getOrPut(scene) { mutableMapOf() }
            .getOrPut(orientation) { overscroll(scene, orientation) { it.scene == scene } }

    private fun overscroll(
        scene: SceneKey,
        orientation: Orientation,
        filter: (OverscrollSpecImpl) -> Boolean,
    ): OverscrollSpecImpl? {
        var match: OverscrollSpecImpl? = null
        overscrollSpecs.fastForEach { spec ->
            if (spec.orientation == orientation && filter(spec)) {
                if (match != null) {
                    error("Found multiple transition specs for transition $scene")
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
                visibilityThreshold = OffsetVisibilityThreshold,
            )

        val Empty =
            SceneTransitions(
                defaultSwipeSpec = DefaultSwipeSpec,
                transitionSpecs = emptyList(),
                overscrollSpecs = emptyList(),
            )
    }
}

/** The definition of a transition between [from] and [to]. */
interface TransitionSpec {
    /** The key of this [TransitionSpec]. */
    val key: TransitionKey?

    /**
     * The scene we are transitioning from. If `null`, this spec can be used to animate from any
     * scene.
     */
    val from: SceneKey?

    /**
     * The scene we are transitioning to. If `null`, this spec can be used to animate from any
     * scene.
     */
    val to: SceneKey?

    /**
     * Return a reversed version of this [TransitionSpec] for a transition going from [to] to
     * [from].
     */
    fun reversed(): TransitionSpec

    /**
     * The [TransformationSpec] associated to this [TransitionSpec].
     *
     * Note that this is called once every a transition associated to this [TransitionSpec] is
     * started.
     */
    fun transformationSpec(): TransformationSpec
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
        internal val EmptyProvider = { Empty }
    }
}

internal class TransitionSpecImpl(
    override val key: TransitionKey?,
    override val from: SceneKey?,
    override val to: SceneKey?,
    private val transformationSpec: () -> TransformationSpecImpl,
) : TransitionSpec {
    override fun reversed(): TransitionSpecImpl {
        return TransitionSpecImpl(
            key = key,
            from = to,
            to = from,
            transformationSpec = {
                val reverse = transformationSpec.invoke()
                TransformationSpecImpl(
                    progressSpec = reverse.progressSpec,
                    swipeSpec = reverse.swipeSpec,
                    distance = reverse.distance,
                    transformations = reverse.transformations.map { it.reversed() }
                )
            }
        )
    }

    override fun transformationSpec(): TransformationSpecImpl = this.transformationSpec.invoke()
}

/** The definition of the overscroll behavior of the [scene]. */
interface OverscrollSpec {
    /** The scene we are over scrolling. */
    val scene: SceneKey

    /** The orientation of this [OverscrollSpec]. */
    val orientation: Orientation

    /** The [TransformationSpec] associated to this [OverscrollSpec]. */
    val transformationSpec: TransformationSpec
}

internal class OverscrollSpecImpl(
    override val scene: SceneKey,
    override val orientation: Orientation,
    override val transformationSpec: TransformationSpecImpl,
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
    private val cache = mutableMapOf<ElementKey, MutableMap<SceneKey, ElementTransformations>>()

    internal fun transformations(element: ElementKey, scene: SceneKey): ElementTransformations {
        return cache
            .getOrPut(element) { mutableMapOf() }
            .getOrPut(scene) { computeTransformations(element, scene) }
    }

    /** Filter [transformations] to compute the [ElementTransformations] of [element]. */
    private fun computeTransformations(
        element: ElementKey,
        scene: SceneKey,
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
            if (!transformation.matcher.matches(element, scene)) {
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

    private fun throwIfNotNull(
        previous: Transformation?,
        element: ElementKey,
        name: String,
    ) {
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
