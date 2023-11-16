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
import androidx.compose.animation.core.snap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import com.android.compose.animation.scene.transformation.AnchoredSize
import com.android.compose.animation.scene.transformation.AnchoredTranslate
import com.android.compose.animation.scene.transformation.DrawScale
import com.android.compose.animation.scene.transformation.EdgeTranslate
import com.android.compose.animation.scene.transformation.Fade
import com.android.compose.animation.scene.transformation.ModifierTransformation
import com.android.compose.animation.scene.transformation.PropertyTransformation
import com.android.compose.animation.scene.transformation.RangedPropertyTransformation
import com.android.compose.animation.scene.transformation.ScaleSize
import com.android.compose.animation.scene.transformation.SharedElementTransformation
import com.android.compose.animation.scene.transformation.Transformation
import com.android.compose.animation.scene.transformation.Translate

/** The transitions configuration of a [SceneTransitionLayout]. */
class SceneTransitions(
    internal val transitionSpecs: List<TransitionSpec>,
) {
    private val cache = mutableMapOf<SceneKey, MutableMap<SceneKey, TransitionSpec>>()

    internal fun transitionSpec(from: SceneKey, to: SceneKey): TransitionSpec {
        return cache.getOrPut(from) { mutableMapOf() }.getOrPut(to) { findSpec(from, to) }
    }

    private fun findSpec(from: SceneKey, to: SceneKey): TransitionSpec {
        val spec = transition(from, to) { it.from == from && it.to == to }
        if (spec != null) {
            return spec
        }

        val reversed = transition(from, to) { it.from == to && it.to == from }
        if (reversed != null) {
            return reversed.reverse()
        }

        val relaxedSpec =
            transition(from, to) {
                (it.from == from && it.to == null) || (it.to == to && it.from == null)
            }
        if (relaxedSpec != null) {
            return relaxedSpec
        }

        return transition(from, to) {
                (it.from == to && it.to == null) || (it.to == from && it.from == null)
            }
            ?.reverse()
            ?: defaultTransition(from, to)
    }

    private fun transition(
        from: SceneKey,
        to: SceneKey,
        filter: (TransitionSpec) -> Boolean,
    ): TransitionSpec? {
        var match: TransitionSpec? = null
        transitionSpecs.fastForEach { spec ->
            if (filter(spec)) {
                if (match != null) {
                    error("Found multiple transition specs for transition $from => $to")
                }
                match = spec
            }
        }
        return match
    }

    private fun defaultTransition(from: SceneKey, to: SceneKey) =
        TransitionSpec(from, to, emptyList(), snap())
}

/** The definition of a transition between [from] and [to]. */
data class TransitionSpec(
    val from: SceneKey?,
    val to: SceneKey?,
    val transformations: List<Transformation>,
    val spec: AnimationSpec<Float>,
) {
    // TODO(b/302300957): Make sure this cache does not infinitely grow.
    private val cache = mutableMapOf<ElementKey, MutableMap<SceneKey, ElementTransformations>>()

    internal fun reverse(): TransitionSpec {
        return copy(
            from = to,
            to = from,
            transformations = transformations.fastMap { it.reverse() },
        )
    }

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
        val modifier = mutableListOf<ModifierTransformation>()
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
                is ModifierTransformation -> modifier.add(transformation)
                is PropertyTransformation<*> -> onPropertyTransformation(transformation)
            }
        }

        return ElementTransformations(shared, modifier, offset, size, drawScale, alpha)
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
    val modifier: List<ModifierTransformation>,
    val offset: PropertyTransformation<Offset>?,
    val size: PropertyTransformation<IntSize>?,
    val drawScale: PropertyTransformation<Scale>?,
    val alpha: PropertyTransformation<Float>?,
)
