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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.IntermediateMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.intermediateLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import com.android.compose.animation.scene.transformation.PropertyTransformation
import com.android.compose.animation.scene.transformation.SharedElementTransformation
import com.android.compose.modifiers.thenIf
import com.android.compose.ui.util.lerp

/** An element on screen, that can be composed in one or more scenes. */
internal class Element(val key: ElementKey) {
    /**
     * The last values of this element, coming from any scene. Note that this value will be unstable
     * if this element is present in multiple scenes but the shared element animation is disabled,
     * given that multiple instances of the element with different states will write to these
     * values. You should prefer using [TargetValues.lastValues] in the current scene if it is
     * defined.
     */
    val lastSharedValues = Values()

    /** The mapping between a scene and the values/state this element has in that scene, if any. */
    val sceneValues = SnapshotStateMap<SceneKey, TargetValues>()

    /**
     * The movable content of this element, if this element is composed using
     * [SceneScope.MovableElement].
     */
    val movableContent by
        // This is only accessed from the composition (main) thread, so no need to use the default
        // lock of lazy {} to synchronize.
        lazy(mode = LazyThreadSafetyMode.NONE) {
            movableContentOf { content: @Composable () -> Unit -> content() }
        }

    override fun toString(): String {
        return "Element(key=$key)"
    }

    /** The current values of this element, either in a specific scene or in a shared context. */
    class Values {
        /** The offset of the element, relative to the SceneTransitionLayout containing it. */
        var offset = Offset.Unspecified

        /** The size of this element. */
        var size = SizeUnspecified

        /** The draw scale of this element. */
        var drawScale = Scale.Default

        /** The alpha of this element. */
        var alpha = AlphaUnspecified
    }

    /** The target values of this element in a given scene. */
    class TargetValues {
        val lastValues = Values()

        var targetSize by mutableStateOf(SizeUnspecified)
        var targetOffset by mutableStateOf(Offset.Unspecified)

        val sharedValues = SnapshotStateMap<ValueKey, SharedValue<*>>()
    }

    /** A shared value of this element. */
    class SharedValue<T>(val key: ValueKey, initialValue: T) {
        var value by mutableStateOf(initialValue)
    }

    companion object {
        val SizeUnspecified = IntSize(Int.MAX_VALUE, Int.MAX_VALUE)
        val AlphaUnspecified = Float.MIN_VALUE
    }
}

data class Scale(val scaleX: Float, val scaleY: Float, val pivot: Offset = Offset.Unspecified) {

    companion object {
        val Default = Scale(1f, 1f, Offset.Unspecified)
    }
}

/** The implementation of [SceneScope.element]. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.element(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    key: ElementKey,
): Modifier = composed {
    val sceneValues = remember(scene, key) { Element.TargetValues() }
    val element =
        // Get the element associated to [key] if it was already composed in another scene,
        // otherwise create it and add it to our Map<ElementKey, Element>. This is done inside a
        // withoutReadObservation() because there is no need to recompose when that map is mutated.
        Snapshot.withoutReadObservation {
            val element =
                layoutImpl.elements[key] ?: Element(key).also { layoutImpl.elements[key] = it }
            val previousValues = element.sceneValues[scene.key]
            if (previousValues == null) {
                element.sceneValues[scene.key] = sceneValues
            } else if (previousValues != sceneValues) {
                error("$key was composed multiple times in $scene")
            }

            element
        }
    val lastSharedValues = element.lastSharedValues
    val lastSceneValues = sceneValues.lastValues

    DisposableEffect(scene, sceneValues, element) {
        onDispose {
            element.sceneValues.remove(scene.key)

            // This was the last scene this element was in, so remove it from the map.
            if (element.sceneValues.isEmpty()) {
                layoutImpl.elements.remove(element.key)
            }
        }
    }

    val alpha =
        remember(layoutImpl, element, scene, sceneValues) {
            derivedStateOf { elementAlpha(layoutImpl, element, scene, sceneValues) }
        }
    val isOpaque by remember(alpha) { derivedStateOf { alpha.value == 1f } }
    SideEffect {
        if (isOpaque) {
            lastSharedValues.alpha = 1f
            lastSceneValues.alpha = 1f
        }
    }

    val drawScale by
        remember(layoutImpl, element, scene, sceneValues) {
            derivedStateOf { getDrawScale(layoutImpl, element, scene, sceneValues) }
        }

    drawWithContent {
            if (shouldDrawElement(layoutImpl, scene, element)) {
                if (drawScale == Scale.Default) {
                    this@drawWithContent.drawContent()
                } else {
                    scale(
                        drawScale.scaleX,
                        drawScale.scaleY,
                        if (drawScale.pivot.isUnspecified) center else drawScale.pivot
                    ) {
                        this@drawWithContent.drawContent()
                    }
                }
            }
        }
        .modifierTransformations(layoutImpl, scene, element, sceneValues)
        .intermediateLayout { measurable, constraints ->
            val placeable =
                measure(layoutImpl, scene, element, sceneValues, measurable, constraints)
            layout(placeable.width, placeable.height) {
                place(layoutImpl, scene, element, sceneValues, placeable, placementScope = this)
            }
        }
        .thenIf(!isOpaque) {
            Modifier.graphicsLayer {
                val alpha = alpha.value
                this.alpha = alpha
                lastSharedValues.alpha = alpha
                lastSceneValues.alpha = alpha
            }
        }
        .testTag(key.testTag)
}

private fun shouldDrawElement(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    element: Element,
): Boolean {
    val state = layoutImpl.state.transitionState

    // Always draw the element if there is no ongoing transition or if the element is not shared.
    if (
        state !is TransitionState.Transition ||
            state.fromScene == state.toScene ||
            !layoutImpl.isTransitionReady(state) ||
            state.fromScene !in element.sceneValues ||
            state.toScene !in element.sceneValues
    ) {
        return true
    }

    val sharedTransformation = sharedElementTransformation(layoutImpl, state, element.key)
    if (sharedTransformation?.enabled == false) {
        return true
    }

    return shouldDrawOrComposeSharedElement(
        layoutImpl,
        state,
        scene.key,
        element.key,
        sharedTransformation,
    )
}

internal fun shouldDrawOrComposeSharedElement(
    layoutImpl: SceneTransitionLayoutImpl,
    transition: TransitionState.Transition,
    scene: SceneKey,
    element: ElementKey,
    sharedTransformation: SharedElementTransformation?
): Boolean {
    val scenePicker = sharedTransformation?.scenePicker ?: DefaultSharedElementScenePicker
    val fromScene = transition.fromScene
    val toScene = transition.toScene

    return scenePicker.sceneDuringTransition(
        element = element,
        fromScene = fromScene,
        toScene = toScene,
        progress = transition::progress,
        fromSceneZIndex = layoutImpl.scenes.getValue(fromScene).zIndex,
        toSceneZIndex = layoutImpl.scenes.getValue(toScene).zIndex,
    ) == scene
}

private fun isSharedElementEnabled(
    layoutImpl: SceneTransitionLayoutImpl,
    transition: TransitionState.Transition,
    element: ElementKey,
): Boolean {
    return sharedElementTransformation(layoutImpl, transition, element)?.enabled ?: true
}

internal fun sharedElementTransformation(
    layoutImpl: SceneTransitionLayoutImpl,
    transition: TransitionState.Transition,
    element: ElementKey,
): SharedElementTransformation? {
    val spec = layoutImpl.transitions.transitionSpec(transition.fromScene, transition.toScene)
    val sharedInFromScene = spec.transformations(element, transition.fromScene).shared
    val sharedInToScene = spec.transformations(element, transition.toScene).shared

    // The sharedElement() transformation must either be null or be the same in both scenes.
    if (sharedInFromScene != sharedInToScene) {
        error(
            "Different sharedElement() transformations matched $element (from=$sharedInFromScene " +
                "to=$sharedInToScene)"
        )
    }

    return sharedInFromScene
}

/**
 * Chain the [com.android.compose.animation.scene.transformation.ModifierTransformation] applied
 * throughout the current transition, if any.
 */
private fun Modifier.modifierTransformations(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    element: Element,
    sceneValues: Element.TargetValues,
): Modifier {
    when (val state = layoutImpl.state.transitionState) {
        is TransitionState.Idle -> return this
        is TransitionState.Transition -> {
            val fromScene = state.fromScene
            val toScene = state.toScene
            if (fromScene == toScene) {
                // Same as idle.
                return this
            }

            return layoutImpl.transitions
                .transitionSpec(fromScene, state.toScene)
                .transformations(element.key, scene.key)
                .modifier
                .fold(this) { modifier, transformation ->
                    with(transformation) {
                        modifier.transform(layoutImpl, scene, element, sceneValues)
                    }
                }
        }
    }
}

private fun elementAlpha(
    layoutImpl: SceneTransitionLayoutImpl,
    element: Element,
    scene: Scene,
    sceneValues: Element.TargetValues,
): Float {
    return computeValue(
            layoutImpl,
            scene,
            element,
            sceneValue = { 1f },
            transformation = { it.alpha },
            idleValue = 1f,
            currentValue = { 1f },
            lastValue = {
                sceneValues.lastValues.alpha.takeIf { it != Element.AlphaUnspecified }
                    ?: element.lastSharedValues.alpha.takeIf { it != Element.AlphaUnspecified }
                        ?: 1f
            },
            ::lerp,
        )
        .coerceIn(0f, 1f)
}

@OptIn(ExperimentalComposeUiApi::class)
private fun IntermediateMeasureScope.measure(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    element: Element,
    sceneValues: Element.TargetValues,
    measurable: Measurable,
    constraints: Constraints,
): Placeable {
    // Update the size this element has in this scene when idle.
    val targetSizeInScene = lookaheadSize
    if (targetSizeInScene != sceneValues.targetSize) {
        // TODO(b/290930950): Better handle when this changes to avoid instant size jumps.
        sceneValues.targetSize = targetSizeInScene
    }

    // Some lambdas called (max once) by computeValue() will need to measure [measurable], in which
    // case we store the resulting placeable here to make sure the element is not measured more than
    // once.
    var maybePlaceable: Placeable? = null

    fun Placeable.size() = IntSize(width, height)

    val targetSize =
        computeValue(
            layoutImpl,
            scene,
            element,
            sceneValue = { it.targetSize },
            transformation = { it.size },
            idleValue = lookaheadSize,
            currentValue = { measurable.measure(constraints).also { maybePlaceable = it }.size() },
            lastValue = {
                sceneValues.lastValues.size.takeIf { it != Element.SizeUnspecified }
                    ?: element.lastSharedValues.size.takeIf { it != Element.SizeUnspecified }
                        ?: measurable.measure(constraints).also { maybePlaceable = it }.size()
            },
            ::lerp,
        )

    val placeable =
        maybePlaceable
            ?: measurable.measure(
                Constraints.fixed(
                    targetSize.width.coerceAtLeast(0),
                    targetSize.height.coerceAtLeast(0),
                )
            )

    val size = placeable.size()
    element.lastSharedValues.size = size
    sceneValues.lastValues.size = size
    return placeable
}

private fun getDrawScale(
    layoutImpl: SceneTransitionLayoutImpl,
    element: Element,
    scene: Scene,
    sceneValues: Element.TargetValues
): Scale {
    return computeValue(
        layoutImpl,
        scene,
        element,
        sceneValue = { Scale.Default },
        transformation = { it.drawScale },
        idleValue = Scale.Default,
        currentValue = { Scale.Default },
        lastValue = {
            sceneValues.lastValues.drawScale.takeIf { it != Scale.Default }
                ?: element.lastSharedValues.drawScale
        },
        ::lerp,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun IntermediateMeasureScope.place(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    element: Element,
    sceneValues: Element.TargetValues,
    placeable: Placeable,
    placementScope: Placeable.PlacementScope,
) {
    with(placementScope) {
        // Update the offset (relative to the SceneTransitionLayout) this element has in this scene
        // when idle.
        val coords = coordinates!!
        val targetOffsetInScene = lookaheadScopeCoordinates.localLookaheadPositionOf(coords)
        if (targetOffsetInScene != sceneValues.targetOffset) {
            // TODO(b/290930950): Better handle when this changes to avoid instant offset jumps.
            sceneValues.targetOffset = targetOffsetInScene
        }

        val currentOffset = lookaheadScopeCoordinates.localPositionOf(coords, Offset.Zero)
        val targetOffset =
            computeValue(
                layoutImpl,
                scene,
                element,
                sceneValue = { it.targetOffset },
                transformation = { it.offset },
                idleValue = targetOffsetInScene,
                currentValue = { currentOffset },
                lastValue = {
                    sceneValues.lastValues.offset.takeIf { it.isSpecified }
                        ?: element.lastSharedValues.offset.takeIf { it.isSpecified }
                            ?: currentOffset
                },
                ::lerp,
            )

        element.lastSharedValues.offset = targetOffset
        sceneValues.lastValues.offset = targetOffset

        // TODO(b/291071158): Call placeWithLayer() if offset != IntOffset.Zero and size is not
        // animated once b/305195729 is fixed. Test that drawing is not invalidated in that case.
        placeable.place((targetOffset - currentOffset).round())
    }
}

/**
 * Return the value that should be used depending on the current layout state and transition.
 *
 * Important: This function must remain inline because of all the lambda parameters. These lambdas
 * are necessary because getting some of them might require some computation, like measuring a
 * Measurable.
 *
 * @param layoutImpl the [SceneTransitionLayoutImpl] associated to [element].
 * @param scene the scene containing [element].
 * @param element the element being animated.
 * @param sceneValue the value being animated.
 * @param transformation the transformation associated to the value being animated.
 * @param idleValue the value when idle, i.e. when there is no transition happening.
 * @param currentValue the value that would be used if it is not transformed. Note that this is
 *   different than [idleValue] even if the value is not transformed directly because it could be
 *   impacted by the transformations on other elements, like a parent that is being translated or
 *   resized.
 * @param lastValue the last value that was used. This should be equal to [currentValue] if this is
 *   the first time the value is set.
 * @param lerp the linear interpolation function used to interpolate between two values of this
 *   value type.
 */
private inline fun <T> computeValue(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    element: Element,
    sceneValue: (Element.TargetValues) -> T,
    transformation: (ElementTransformations) -> PropertyTransformation<T>?,
    idleValue: T,
    currentValue: () -> T,
    lastValue: () -> T,
    lerp: (T, T, Float) -> T,
): T {
    val state = layoutImpl.state.transitionState

    // There is no ongoing transition.
    if (state !is TransitionState.Transition || state.fromScene == state.toScene) {
        // Even if this element SceneTransitionLayout is not animated, the layout itself might be
        // animated (e.g. by another parent SceneTransitionLayout), in which case this element still
        // need to participate in the layout phase.
        return currentValue()
    }

    // A transition was started but it's not ready yet (not all elements have been composed/laid
    // out yet). Use the last value that was set, to make sure elements don't unexpectedly jump.
    if (!layoutImpl.isTransitionReady(state)) {
        return lastValue()
    }

    val fromScene = state.fromScene
    val toScene = state.toScene
    val fromValues = element.sceneValues[fromScene]
    val toValues = element.sceneValues[toScene]

    if (fromValues == null && toValues == null) {
        error("This should not happen, element $element is neither in $fromScene or $toScene")
    }

    // The element is shared: interpolate between the value in fromScene and the value in toScene.
    // TODO(b/290184746): Support non linear shared paths as well as a way to make sure that shared
    // elements follow the finger direction.
    val isSharedElement = fromValues != null && toValues != null
    if (isSharedElement && isSharedElementEnabled(layoutImpl, state, element.key)) {
        val start = sceneValue(fromValues!!)
        val end = sceneValue(toValues!!)

        // Make sure we don't read progress if values are the same and we don't need to interpolate,
        // so we don't invalidate the phase where this is read.
        return if (start == end) start else lerp(start, end, state.progress)
    }

    val transformation =
        transformation(
            layoutImpl.transitions
                .transitionSpec(fromScene, toScene)
                .transformations(element.key, scene.key)
        )
        // If there is no transformation explicitly associated to this element value, let's use
        // the value given by the system (like the current position and size given by the layout
        // pass).
        ?: return currentValue()

    // Get the transformed value, i.e. the target value at the beginning (for entering elements) or
    // end (for leaving elements) of the transition.
    val sceneValues =
        checkNotNull(
            when {
                isSharedElement && scene.key == fromScene -> fromValues
                isSharedElement -> toValues
                else -> fromValues ?: toValues
            }
        )

    val targetValue =
        transformation.transform(
            layoutImpl,
            scene,
            element,
            sceneValues,
            state,
            idleValue,
        )

    // Make sure we don't read progress if values are the same and we don't need to interpolate, so
    // we don't invalidate the phase where this is read.
    if (targetValue == idleValue) {
        return targetValue
    }

    val progress = state.progress
    // TODO(b/290184746): Make sure that we don't overflow transformations associated to a range.
    val rangeProgress = transformation.range?.progress(progress) ?: progress

    // Interpolate between the value at rest and the value before entering/after leaving.
    val isEntering = scene.key == toScene
    return if (isEntering) {
        lerp(targetValue, idleValue, rangeProgress)
    } else {
        lerp(idleValue, targetValue, rangeProgress)
    }
}
