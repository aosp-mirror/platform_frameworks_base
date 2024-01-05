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

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.IntermediateMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.intermediateLayout
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import com.android.compose.animation.scene.transformation.PropertyTransformation
import com.android.compose.animation.scene.transformation.SharedElementTransformation
import com.android.compose.ui.util.lerp
import kotlinx.coroutines.launch

/** An element on screen, that can be composed in one or more scenes. */
@Stable
internal class Element(val key: ElementKey) {
    /**
     * The last state of this element, coming from any scene. Note that this state will be unstable
     * if this element is present in multiple scenes but the shared element animation is disabled,
     * given that multiple instances of the element with different states will write to this state.
     * You should prefer using [SceneState.lastState] in the current scene when it is defined.
     */
    val lastSharedState = State()

    /** The mapping between a scene and the state this element has in that scene, if any. */
    val sceneStates = mutableMapOf<SceneKey, SceneState>()

    override fun toString(): String {
        return "Element(key=$key)"
    }

    /** The state of this element, either in a specific scene or in a shared context. */
    class State {
        /** The offset of the element, relative to the SceneTransitionLayout containing it. */
        var offset = Offset.Unspecified

        /** The size of this element. */
        var size = SizeUnspecified

        /** The draw scale of this element. */
        var drawScale = Scale.Default

        /** The alpha of this element. */
        var alpha = AlphaUnspecified
    }

    /** The last and target state of this element in a given scene. */
    @Stable
    class SceneState(val scene: SceneKey) {
        val lastState = State()

        var targetSize by mutableStateOf(SizeUnspecified)
        var targetOffset by mutableStateOf(Offset.Unspecified)

        /**
         * The attached [ElementNode] a Modifier.element() for a given element and scene. During
         * composition, this set could have 0 to 2 elements. After composition and after all
         * modifier nodes have been attached/detached, this set should contain exactly 1 element.
         */
        val nodes = mutableSetOf<ElementNode>()
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
@Stable
internal fun Modifier.element(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    key: ElementKey,
): Modifier {
    return this.then(ElementModifier(layoutImpl, scene, key))
        // TODO(b/311132415): Move this into ElementNode once we can create a delegate
        // IntermediateLayoutModifierNode.
        .intermediateLayout { measurable, constraints ->
            // TODO(b/311132415): No need to fetch the element and sceneState from the map anymore
            // once this is merged into ElementNode.
            val element = layoutImpl.elements.getValue(key)
            val sceneState = element.sceneStates.getValue(scene.key)

            val placeable = measure(layoutImpl, scene, element, sceneState, measurable, constraints)
            layout(placeable.width, placeable.height) {
                place(layoutImpl, scene, element, sceneState, placeable, placementScope = this)
            }
        }
        .testTag(key.testTag)
}

/**
 * An element associated to [ElementNode]. Note that this element does not support updates as its
 * arguments should always be the same.
 */
private data class ElementModifier(
    private val layoutImpl: SceneTransitionLayoutImpl,
    private val scene: Scene,
    private val key: ElementKey,
) : ModifierNodeElement<ElementNode>() {
    override fun create(): ElementNode = ElementNode(layoutImpl, scene, key)

    override fun update(node: ElementNode) {
        node.update(layoutImpl, scene, key)
    }
}

internal class ElementNode(
    private var layoutImpl: SceneTransitionLayoutImpl,
    private var scene: Scene,
    private var key: ElementKey,
) : Modifier.Node(), DrawModifierNode {
    private var _element: Element? = null
    private val element: Element
        get() = _element!!

    private var _sceneState: Element.SceneState? = null
    private val sceneState: Element.SceneState
        get() = _sceneState!!

    override fun onAttach() {
        super.onAttach()
        updateElementAndSceneValues()
        addNodeToSceneState()
    }

    private fun updateElementAndSceneValues() {
        val element =
            layoutImpl.elements[key] ?: Element(key).also { layoutImpl.elements[key] = it }
        _element = element
        _sceneState =
            element.sceneStates[scene.key]
                ?: Element.SceneState(scene.key).also { element.sceneStates[scene.key] = it }
    }

    private fun addNodeToSceneState() {
        sceneState.nodes.add(this)

        coroutineScope.launch {
            // At this point all [CodeLocationNode] have been attached or detached, which means that
            // [sceneState.codeLocations] should have exactly 1 element, otherwise this means that
            // this element was composed multiple times in the same scene.
            val nCodeLocations = sceneState.nodes.size
            if (nCodeLocations != 1 || !sceneState.nodes.contains(this@ElementNode)) {
                error("$key was composed $nCodeLocations times in ${sceneState.scene}")
            }
        }
    }

    override fun onDetach() {
        super.onDetach()
        removeNodeFromSceneState()
        maybePruneMaps(layoutImpl, element, sceneState)

        _element = null
        _sceneState = null
    }

    private fun removeNodeFromSceneState() {
        sceneState.nodes.remove(this)
    }

    fun update(
        layoutImpl: SceneTransitionLayoutImpl,
        scene: Scene,
        key: ElementKey,
    ) {
        check(layoutImpl == this.layoutImpl && scene == this.scene)
        removeNodeFromSceneState()

        val prevElement = this.element
        val prevSceneState = this.sceneState
        this.key = key
        updateElementAndSceneValues()

        addNodeToSceneState()
        maybePruneMaps(layoutImpl, prevElement, prevSceneState)
    }

    override fun ContentDrawScope.draw() {
        val drawScale = getDrawScale(layoutImpl, element, scene, sceneState)
        if (drawScale == Scale.Default) {
            drawContent()
        } else {
            scale(
                drawScale.scaleX,
                drawScale.scaleY,
                if (drawScale.pivot.isUnspecified) center else drawScale.pivot,
            ) {
                this@draw.drawContent()
            }
        }
    }

    companion object {
        private fun maybePruneMaps(
            layoutImpl: SceneTransitionLayoutImpl,
            element: Element,
            sceneState: Element.SceneState,
        ) {
            // If element is not composed from this scene anymore, remove the scene values. This
            // works because [onAttach] is called before [onDetach], so if an element is moved from
            // the UI tree we will first add the new code location then remove the old one.
            if (sceneState.nodes.isEmpty() && element.sceneStates[sceneState.scene] == sceneState) {
                element.sceneStates.remove(sceneState.scene)

                // If the element is not composed in any scene, remove it from the elements map.
                if (element.sceneStates.isEmpty() && layoutImpl.elements[element.key] == element) {
                    layoutImpl.elements.remove(element.key)
                }
            }
        }
    }
}

private fun shouldDrawElement(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    element: Element,
): Boolean {
    val transition = layoutImpl.state.currentTransition

    // Always draw the element if there is no ongoing transition or if the element is not shared.
    if (
        transition == null ||
            !layoutImpl.isTransitionReady(transition) ||
            transition.fromScene !in element.sceneStates ||
            transition.toScene !in element.sceneStates
    ) {
        return true
    }

    val sharedTransformation =
        sharedElementTransformation(layoutImpl.state, transition, element.key)
    if (sharedTransformation?.enabled == false) {
        return true
    }

    return shouldDrawOrComposeSharedElement(
        layoutImpl,
        transition,
        scene.key,
        element.key,
    )
}

internal fun shouldDrawOrComposeSharedElement(
    layoutImpl: SceneTransitionLayoutImpl,
    transition: TransitionState.Transition,
    scene: SceneKey,
    element: ElementKey,
): Boolean {
    val scenePicker = element.scenePicker
    val fromScene = transition.fromScene
    val toScene = transition.toScene

    return scenePicker.sceneDuringTransition(
        element = element,
        transition = transition,
        fromSceneZIndex = layoutImpl.scenes.getValue(fromScene).zIndex,
        toSceneZIndex = layoutImpl.scenes.getValue(toScene).zIndex,
    ) == scene
}

private fun isSharedElementEnabled(
    layoutState: BaseSceneTransitionLayoutState,
    transition: TransitionState.Transition,
    element: ElementKey,
): Boolean {
    return sharedElementTransformation(layoutState, transition, element)?.enabled ?: true
}

internal fun sharedElementTransformation(
    layoutState: BaseSceneTransitionLayoutState,
    transition: TransitionState.Transition,
    element: ElementKey,
): SharedElementTransformation? {
    val transformationSpec = layoutState.transformationSpec
    val sharedInFromScene = transformationSpec.transformations(element, transition.fromScene).shared
    val sharedInToScene = transformationSpec.transformations(element, transition.toScene).shared

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
 * Whether the element is opaque or not.
 *
 * Important: The logic here should closely match the logic in [elementAlpha]. Note that we don't
 * reuse [elementAlpha] and simply check if alpha == 1f because [isElementOpaque] is checked during
 * placement and we don't want to read the transition progress in that phase.
 */
private fun isElementOpaque(
    layoutImpl: SceneTransitionLayoutImpl,
    element: Element,
    scene: Scene,
    sceneState: Element.SceneState,
): Boolean {
    val transition = layoutImpl.state.currentTransition ?: return true

    if (!layoutImpl.isTransitionReady(transition)) {
        val lastValue =
            sceneState.lastState.alpha.takeIf { it != Element.AlphaUnspecified }
                ?: element.lastSharedState.alpha.takeIf { it != Element.AlphaUnspecified } ?: 1f

        return lastValue == 1f
    }

    val fromScene = transition.fromScene
    val toScene = transition.toScene
    val fromState = element.sceneStates[fromScene]
    val toState = element.sceneStates[toScene]

    if (fromState == null && toState == null) {
        error("This should not happen, element $element is neither in $fromScene or $toScene")
    }

    val isSharedElement = fromState != null && toState != null
    if (isSharedElement && isSharedElementEnabled(layoutImpl.state, transition, element.key)) {
        return true
    }

    return layoutImpl.state.transformationSpec.transformations(element.key, scene.key).alpha == null
}

/**
 * Whether the element is opaque or not.
 *
 * Important: The logic here should closely match the logic in [isElementOpaque]. Note that we don't
 * reuse [elementAlpha] in [isElementOpaque] and simply check if alpha == 1f because
 * [isElementOpaque] is checked during placement and we don't want to read the transition progress
 * in that phase.
 */
private fun elementAlpha(
    layoutImpl: SceneTransitionLayoutImpl,
    element: Element,
    scene: Scene,
    sceneState: Element.SceneState,
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
                sceneState.lastState.alpha.takeIf { it != Element.AlphaUnspecified }
                    ?: element.lastSharedState.alpha.takeIf { it != Element.AlphaUnspecified } ?: 1f
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
    sceneState: Element.SceneState,
    measurable: Measurable,
    constraints: Constraints,
): Placeable {
    // Update the size this element has in this scene when idle.
    val targetSizeInScene = lookaheadSize
    if (targetSizeInScene != sceneState.targetSize) {
        // TODO(b/290930950): Better handle when this changes to avoid instant size jumps.
        sceneState.targetSize = targetSizeInScene
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
                sceneState.lastState.size.takeIf { it != Element.SizeUnspecified }
                    ?: element.lastSharedState.size.takeIf { it != Element.SizeUnspecified }
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
    element.lastSharedState.size = size
    sceneState.lastState.size = size
    return placeable
}

private fun getDrawScale(
    layoutImpl: SceneTransitionLayoutImpl,
    element: Element,
    scene: Scene,
    sceneState: Element.SceneState
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
            sceneState.lastState.drawScale.takeIf { it != Scale.Default }
                ?: element.lastSharedState.drawScale
        },
        ::lerp,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun IntermediateMeasureScope.place(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    element: Element,
    sceneState: Element.SceneState,
    placeable: Placeable,
    placementScope: Placeable.PlacementScope,
) {
    with(placementScope) {
        // Update the offset (relative to the SceneTransitionLayout) this element has in this scene
        // when idle.
        val coords = coordinates ?: error("Element ${element.key} does not have any coordinates")
        val targetOffsetInScene = lookaheadScopeCoordinates.localLookaheadPositionOf(coords)
        if (targetOffsetInScene != sceneState.targetOffset) {
            // TODO(b/290930950): Better handle when this changes to avoid instant offset jumps.
            sceneState.targetOffset = targetOffsetInScene
        }

        val currentOffset = lookaheadScopeCoordinates.localPositionOf(coords, Offset.Zero)
        val lastSharedState = element.lastSharedState
        val lastSceneState = sceneState.lastState
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
                    lastSceneState.offset.takeIf { it.isSpecified }
                        ?: lastSharedState.offset.takeIf { it.isSpecified } ?: currentOffset
                },
                ::lerp,
            )

        lastSharedState.offset = targetOffset
        lastSceneState.offset = targetOffset

        // No need to place the element in this scene if we don't want to draw it anyways. Note that
        // it's still important to compute the target offset and update last(Shared|Scene)State,
        // otherwise they will be out of date.
        if (!shouldDrawElement(layoutImpl, scene, element)) {
            return
        }

        val offset = (targetOffset - currentOffset).round()
        if (isElementOpaque(layoutImpl, element, scene, sceneState)) {
            // TODO(b/291071158): Call placeWithLayer() if offset != IntOffset.Zero and size is not
            // animated once b/305195729 is fixed. Test that drawing is not invalidated in that
            // case.
            placeable.place(offset)
            lastSharedState.alpha = 1f
            lastSceneState.alpha = 1f
        } else {
            placeable.placeWithLayer(offset) {
                val alpha = elementAlpha(layoutImpl, element, scene, sceneState)
                this.alpha = alpha
                lastSharedState.alpha = alpha
                lastSceneState.alpha = alpha
            }
        }
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
    sceneValue: (Element.SceneState) -> T,
    transformation: (ElementTransformations) -> PropertyTransformation<T>?,
    idleValue: T,
    currentValue: () -> T,
    lastValue: () -> T,
    lerp: (T, T, Float) -> T,
): T {
    val transition =
        layoutImpl.state.currentTransition
        // There is no ongoing transition. Even if this element SceneTransitionLayout is not
        // animated, the layout itself might be animated (e.g. by another parent
        // SceneTransitionLayout), in which case this element still need to participate in the
        // layout phase.
        ?: return currentValue()

    // A transition was started but it's not ready yet (not all elements have been composed/laid
    // out yet). Use the last value that was set, to make sure elements don't unexpectedly jump.
    if (!layoutImpl.isTransitionReady(transition)) {
        return lastValue()
    }

    val fromScene = transition.fromScene
    val toScene = transition.toScene
    val fromState = element.sceneStates[fromScene]
    val toState = element.sceneStates[toScene]

    if (fromState == null && toState == null) {
        // TODO(b/311600838): Throw an exception instead once layers of disposed elements are not
        // run anymore.
        return lastValue()
    }

    // The element is shared: interpolate between the value in fromScene and the value in toScene.
    // TODO(b/290184746): Support non linear shared paths as well as a way to make sure that shared
    // elements follow the finger direction.
    val isSharedElement = fromState != null && toState != null
    if (isSharedElement && isSharedElementEnabled(layoutImpl.state, transition, element.key)) {
        val start = sceneValue(fromState!!)
        val end = sceneValue(toState!!)

        // Make sure we don't read progress if values are the same and we don't need to interpolate,
        // so we don't invalidate the phase where this is read.
        return if (start == end) start else lerp(start, end, transition.progress)
    }

    val transformation =
        transformation(layoutImpl.state.transformationSpec.transformations(element.key, scene.key))
        // If there is no transformation explicitly associated to this element value, let's use
        // the value given by the system (like the current position and size given by the layout
        // pass).
        ?: return currentValue()

    // Get the transformed value, i.e. the target value at the beginning (for entering elements) or
    // end (for leaving elements) of the transition.
    val sceneState =
        checkNotNull(
            when {
                isSharedElement && scene.key == fromScene -> fromState
                isSharedElement -> toState
                else -> fromState ?: toState
            }
        )

    val targetValue =
        transformation.transform(
            layoutImpl,
            scene,
            element,
            sceneState,
            transition,
            idleValue,
        )

    // Make sure we don't read progress if values are the same and we don't need to interpolate, so
    // we don't invalidate the phase where this is read.
    if (targetValue == idleValue) {
        return targetValue
    }

    val progress = transition.progress
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
