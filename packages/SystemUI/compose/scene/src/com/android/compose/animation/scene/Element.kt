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
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ApproachLayoutModifierNode
import androidx.compose.ui.layout.ApproachMeasureScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.traverseDescendants
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastLastOrNull
import androidx.compose.ui.util.lerp
import com.android.compose.animation.scene.transformation.PropertyTransformation
import com.android.compose.animation.scene.transformation.SharedElementTransformation
import com.android.compose.ui.util.lerp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** An element on screen, that can be composed in one or more scenes. */
@Stable
internal class Element(val key: ElementKey) {
    /** The mapping between a scene and the state this element has in that scene, if any. */
    // TODO(b/316901148): Make this a normal map instead once we can make sure that new transitions
    // are first seen by composition then layout/drawing code. See b/316901148#comment2 for details.
    val sceneStates = SnapshotStateMap<SceneKey, SceneState>()

    /**
     * The last transition that was used when computing the state (size, position and alpha) of this
     * element in any scene, or `null` if it was last laid out when idle.
     */
    var lastTransition: TransitionState.Transition? = null

    /** Whether this element was ever drawn in a scene. */
    var wasDrawnInAnyScene = false

    override fun toString(): String {
        return "Element(key=$key)"
    }

    /** The last and target state of this element in a given scene. */
    @Stable
    class SceneState(val scene: SceneKey) {
        /**
         * The *target* state of this element in this scene, i.e. the state of this element when we
         * are idle on this scene.
         */
        var targetSize by mutableStateOf(SizeUnspecified)
        var targetOffset by mutableStateOf(Offset.Unspecified)

        /** The last state this element had in this scene. */
        var lastOffset = Offset.Unspecified
        var lastSize = SizeUnspecified
        var lastScale = Scale.Unspecified
        var lastAlpha = AlphaUnspecified

        /** The state of this element in this scene right before the last interruption (if any). */
        var offsetBeforeInterruption = Offset.Unspecified
        var sizeBeforeInterruption = SizeUnspecified
        var scaleBeforeInterruption = Scale.Unspecified
        var alphaBeforeInterruption = AlphaUnspecified

        /**
         * The delta values to add to this element state to have smoother interruptions. These
         * should be multiplied by the
         * [current interruption progress][TransitionState.Transition.interruptionProgress] so that
         * they nicely animate from their values down to 0.
         */
        var offsetInterruptionDelta = Offset.Zero
        var sizeInterruptionDelta = IntSize.Zero
        var scaleInterruptionDelta = Scale.Zero
        var alphaInterruptionDelta = 0f

        /**
         * The attached [ElementNode] a Modifier.element() for a given element and scene. During
         * composition, this set could have 0 to 2 elements. After composition and after all
         * modifier nodes have been attached/detached, this set should contain exactly 1 element.
         */
        val nodes = mutableSetOf<ElementNode>()
    }

    companion object {
        val SizeUnspecified = IntSize(Int.MAX_VALUE, Int.MAX_VALUE)
        val AlphaUnspecified = Float.MAX_VALUE
    }
}

data class Scale(val scaleX: Float, val scaleY: Float, val pivot: Offset = Offset.Unspecified) {
    companion object {
        val Default = Scale(1f, 1f, Offset.Unspecified)
        val Zero = Scale(0f, 0f, Offset.Zero)
        val Unspecified = Scale(Float.MAX_VALUE, Float.MAX_VALUE, Offset.Unspecified)
    }
}

/** The implementation of [SceneScope.element]. */
@Stable
internal fun Modifier.element(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    key: ElementKey,
): Modifier {
    // Make sure that we read the current transitions during composition and not during
    // layout/drawing.
    // TODO(b/341072461): Revert this and read the current transitions in ElementNode directly once
    // we can ensure that SceneTransitionLayoutImpl will compose new scenes first.
    val currentTransitions = layoutImpl.state.currentTransitions
    return then(ElementModifier(layoutImpl, currentTransitions, scene, key)).testTag(key.testTag)
}

/**
 * An element associated to [ElementNode]. Note that this element does not support updates as its
 * arguments should always be the same.
 */
private data class ElementModifier(
    private val layoutImpl: SceneTransitionLayoutImpl,
    private val currentTransitions: List<TransitionState.Transition>,
    private val scene: Scene,
    private val key: ElementKey,
) : ModifierNodeElement<ElementNode>() {
    override fun create(): ElementNode = ElementNode(layoutImpl, currentTransitions, scene, key)

    override fun update(node: ElementNode) {
        node.update(layoutImpl, currentTransitions, scene, key)
    }
}

internal class ElementNode(
    private var layoutImpl: SceneTransitionLayoutImpl,
    private var currentTransitions: List<TransitionState.Transition>,
    private var scene: Scene,
    private var key: ElementKey,
) : Modifier.Node(), DrawModifierNode, ApproachLayoutModifierNode, TraversableNode {
    private var _element: Element? = null
    private val element: Element
        get() = _element!!

    private var _sceneState: Element.SceneState? = null
    private val sceneState: Element.SceneState
        get() = _sceneState!!

    override val traverseKey: Any = ElementTraverseKey

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
        currentTransitions: List<TransitionState.Transition>,
        scene: Scene,
        key: ElementKey,
    ) {
        check(layoutImpl == this.layoutImpl && scene == this.scene)
        this.currentTransitions = currentTransitions

        removeNodeFromSceneState()

        val prevElement = this.element
        val prevSceneState = this.sceneState
        this.key = key
        updateElementAndSceneValues()

        addNodeToSceneState()
        maybePruneMaps(layoutImpl, prevElement, prevSceneState)
    }

    override fun isMeasurementApproachInProgress(lookaheadSize: IntSize): Boolean {
        // TODO(b/324191441): Investigate whether making this check more complex (checking if this
        // element is shared or transformed) would lead to better performance.
        return layoutImpl.state.isTransitioning()
    }

    override fun Placeable.PlacementScope.isPlacementApproachInProgress(
        lookaheadCoordinates: LayoutCoordinates
    ): Boolean {
        // TODO(b/324191441): Investigate whether making this check more complex (checking if this
        // element is shared or transformed) would lead to better performance.
        return layoutImpl.state.isTransitioning()
    }

    @ExperimentalComposeUiApi
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        check(isLookingAhead)

        return measurable.measure(constraints).run {
            // Update the size this element has in this scene when idle.
            sceneState.targetSize = size()

            layout(width, height) {
                // Update the offset (relative to the SceneTransitionLayout) this element has in
                // this scene when idle.
                coordinates?.let { coords ->
                    with(layoutImpl.lookaheadScope) {
                        sceneState.targetOffset =
                            lookaheadScopeCoordinates.localLookaheadPositionOf(coords)
                    }
                }
                place(0, 0)
            }
        }
    }

    override fun ApproachMeasureScope.approachMeasure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val transitions = currentTransitions
        val transition = elementTransition(layoutImpl, element, transitions)

        // If this element is not supposed to be laid out now, either because it is not part of any
        // ongoing transition or the other scene of its transition is overscrolling, then lay out
        // the element normally and don't place it.
        val overscrollScene = transition?.currentOverscrollSpec?.scene
        val isOtherSceneOverscrolling = overscrollScene != null && overscrollScene != scene.key
        val isNotPartOfAnyOngoingTransitions = transitions.isNotEmpty() && transition == null
        if (isNotPartOfAnyOngoingTransitions || isOtherSceneOverscrolling) {
            recursivelyClearPlacementValues()

            val placeable = measurable.measure(constraints)
            sceneState.lastSize = placeable.size()

            return layout(placeable.width, placeable.height) { /* Do not place */ }
        }

        val placeable =
            measure(layoutImpl, scene, element, transition, sceneState, measurable, constraints)
        sceneState.lastSize = placeable.size()
        return layout(placeable.width, placeable.height) { place(transition, placeable) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun Placeable.PlacementScope.place(
        transition: TransitionState.Transition?,
        placeable: Placeable,
    ) {
        with(layoutImpl.lookaheadScope) {
            // Update the offset (relative to the SceneTransitionLayout) this element has in this
            // scene when idle.
            val coords =
                coordinates ?: error("Element ${element.key} does not have any coordinates")
            val targetOffsetInScene = lookaheadScopeCoordinates.localLookaheadPositionOf(coords)

            // No need to place the element in this scene if we don't want to draw it anyways.
            if (!shouldPlaceElement(layoutImpl, scene.key, element, transition)) {
                recursivelyClearPlacementValues()
                return
            }

            val currentOffset = lookaheadScopeCoordinates.localPositionOf(coords, Offset.Zero)
            val targetOffset =
                computeValue(
                    layoutImpl,
                    scene,
                    element,
                    transition,
                    sceneValue = { it.targetOffset },
                    transformation = { it.offset },
                    idleValue = targetOffsetInScene,
                    currentValue = { currentOffset },
                    isSpecified = { it != Offset.Unspecified },
                    ::lerp,
                )

            val interruptedOffset =
                computeInterruptedValue(
                    layoutImpl,
                    transition,
                    value = targetOffset,
                    unspecifiedValue = Offset.Unspecified,
                    zeroValue = Offset.Zero,
                    getValueBeforeInterruption = { sceneState.offsetBeforeInterruption },
                    setValueBeforeInterruption = { sceneState.offsetBeforeInterruption = it },
                    getInterruptionDelta = { sceneState.offsetInterruptionDelta },
                    setInterruptionDelta = { delta ->
                        setPlacementInterruptionDelta(
                            element = element,
                            sceneState = sceneState,
                            transition = transition,
                            delta = delta,
                            setter = { sceneState, delta ->
                                sceneState.offsetInterruptionDelta = delta
                            },
                        )
                    },
                    diff = { a, b -> a - b },
                    add = { a, b, bProgress -> a + b * bProgress },
                )

            sceneState.lastOffset = interruptedOffset

            val offset = (interruptedOffset - currentOffset).round()
            if (
                isElementOpaque(scene, element, transition) &&
                    interruptedAlpha(layoutImpl, element, transition, sceneState, alpha = 1f) == 1f
            ) {
                sceneState.lastAlpha = 1f

                // TODO(b/291071158): Call placeWithLayer() if offset != IntOffset.Zero and size is
                // not animated once b/305195729 is fixed. Test that drawing is not invalidated in
                // that case.
                placeable.place(offset)
            } else {
                placeable.placeWithLayer(offset) {
                    // This layer might still run on its own (outside of the placement phase) even
                    // if this element is not placed or composed anymore, so we need to double check
                    // again here before calling [elementAlpha] (which will update
                    // [SceneState.lastAlpha]). We also need to recompute the current transition to
                    // make sure that we are using the current transition and not a reference to an
                    // old one. See b/343138966 for details.
                    if (_element == null) {
                        return@placeWithLayer
                    }

                    val transition = elementTransition(layoutImpl, element, currentTransitions)
                    if (!shouldPlaceElement(layoutImpl, scene.key, element, transition)) {
                        return@placeWithLayer
                    }

                    alpha = elementAlpha(layoutImpl, scene, element, transition, sceneState)
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
            }
        }
    }

    /**
     * Recursively clear the last placement values on this node and all descendants ElementNodes.
     * This should be called when this node is not placed anymore, so that we correctly clear values
     * for the descendants for which approachMeasure() won't be called.
     */
    private fun recursivelyClearPlacementValues() {
        fun Element.SceneState.clearLastPlacementValues() {
            lastOffset = Offset.Unspecified
            lastScale = Scale.Unspecified
            lastAlpha = Element.AlphaUnspecified
        }

        sceneState.clearLastPlacementValues()
        traverseDescendants(ElementTraverseKey) { node ->
            (node as ElementNode).sceneState.clearLastPlacementValues()
            TraversableNode.Companion.TraverseDescendantsAction.ContinueTraversal
        }
    }

    override fun ContentDrawScope.draw() {
        element.wasDrawnInAnyScene = true

        val transition = elementTransition(layoutImpl, element, currentTransitions)
        val drawScale = getDrawScale(layoutImpl, scene, element, transition, sceneState)
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
        private val ElementTraverseKey = Any()

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

/**
 * The transition that we should consider for [element]. This is the last transition where one of
 * its scenes contains the element.
 */
private fun elementTransition(
    layoutImpl: SceneTransitionLayoutImpl,
    element: Element,
    transitions: List<TransitionState.Transition>,
): TransitionState.Transition? {
    val transition =
        transitions.fastLastOrNull { transition ->
            transition.fromScene in element.sceneStates || transition.toScene in element.sceneStates
        }

    val previousTransition = element.lastTransition
    element.lastTransition = transition

    if (transition != previousTransition && transition != null && previousTransition != null) {
        // The previous transition was interrupted by another transition.
        prepareInterruption(layoutImpl, element, transition, previousTransition)
    } else if (transition == null && previousTransition != null) {
        // The transition was just finished.
        element.sceneStates.values.forEach {
            it.clearValuesBeforeInterruption()
            it.clearInterruptionDeltas()
        }
    }

    return transition
}

private fun prepareInterruption(
    layoutImpl: SceneTransitionLayoutImpl,
    element: Element,
    transition: TransitionState.Transition,
    previousTransition: TransitionState.Transition,
) {
    val sceneStates = element.sceneStates
    fun updatedSceneState(key: SceneKey): Element.SceneState? {
        return sceneStates[key]?.also { it.selfUpdateValuesBeforeInterruption() }
    }

    val previousFromState = updatedSceneState(previousTransition.fromScene)
    val previousToState = updatedSceneState(previousTransition.toScene)
    val fromState = updatedSceneState(transition.fromScene)
    val toState = updatedSceneState(transition.toScene)

    reconcileStates(element, previousTransition)
    reconcileStates(element, transition)

    // Remove the interruption values to all scenes but the scene(s) where the element will be
    // placed, to make sure that interruption deltas are computed only right after this interruption
    // is prepared.
    fun maybeCleanPlacementValuesBeforeInterruption(sceneState: Element.SceneState) {
        if (!shouldPlaceElement(layoutImpl, sceneState.scene, element, transition)) {
            sceneState.offsetBeforeInterruption = Offset.Unspecified
            sceneState.alphaBeforeInterruption = Element.AlphaUnspecified
            sceneState.scaleBeforeInterruption = Scale.Unspecified

            sceneState.offsetInterruptionDelta = Offset.Zero
            sceneState.alphaInterruptionDelta = 0f
            sceneState.scaleInterruptionDelta = Scale.Zero
        }
    }

    previousFromState?.let { maybeCleanPlacementValuesBeforeInterruption(it) }
    previousToState?.let { maybeCleanPlacementValuesBeforeInterruption(it) }
    fromState?.let { maybeCleanPlacementValuesBeforeInterruption(it) }
    toState?.let { maybeCleanPlacementValuesBeforeInterruption(it) }
}

/**
 * Reconcile the state of [element] in the fromScene and toScene of [transition] so that the values
 * before interruption have their expected values, taking shared transitions into account.
 */
private fun reconcileStates(
    element: Element,
    transition: TransitionState.Transition,
) {
    val fromSceneState = element.sceneStates[transition.fromScene] ?: return
    val toSceneState = element.sceneStates[transition.toScene] ?: return
    if (!isSharedElementEnabled(element.key, transition)) {
        return
    }

    if (
        fromSceneState.offsetBeforeInterruption != Offset.Unspecified &&
            toSceneState.offsetBeforeInterruption == Offset.Unspecified
    ) {
        // Element is shared and placed in fromScene only.
        toSceneState.updateValuesBeforeInterruption(fromSceneState)
    } else if (
        toSceneState.offsetBeforeInterruption != Offset.Unspecified &&
            fromSceneState.offsetBeforeInterruption == Offset.Unspecified
    ) {
        // Element is shared and placed in toScene only.
        fromSceneState.updateValuesBeforeInterruption(toSceneState)
    }
}

private fun Element.SceneState.selfUpdateValuesBeforeInterruption() {
    offsetBeforeInterruption = lastOffset
    sizeBeforeInterruption = lastSize
    scaleBeforeInterruption = lastScale
    alphaBeforeInterruption = lastAlpha
}

private fun Element.SceneState.updateValuesBeforeInterruption(lastState: Element.SceneState) {
    offsetBeforeInterruption = lastState.offsetBeforeInterruption
    sizeBeforeInterruption = lastState.sizeBeforeInterruption
    scaleBeforeInterruption = lastState.scaleBeforeInterruption
    alphaBeforeInterruption = lastState.alphaBeforeInterruption

    clearInterruptionDeltas()
}

private fun Element.SceneState.clearInterruptionDeltas() {
    offsetInterruptionDelta = Offset.Zero
    sizeInterruptionDelta = IntSize.Zero
    scaleInterruptionDelta = Scale.Zero
    alphaInterruptionDelta = 0f
}

private fun Element.SceneState.clearValuesBeforeInterruption() {
    offsetBeforeInterruption = Offset.Unspecified
    scaleBeforeInterruption = Scale.Unspecified
    alphaBeforeInterruption = Element.AlphaUnspecified
}

/**
 * Compute what [value] should be if we take the
 * [interruption progress][TransitionState.Transition.interruptionProgress] of [transition] into
 * account.
 */
private inline fun <T> computeInterruptedValue(
    layoutImpl: SceneTransitionLayoutImpl,
    transition: TransitionState.Transition?,
    value: T,
    unspecifiedValue: T,
    zeroValue: T,
    getValueBeforeInterruption: () -> T,
    setValueBeforeInterruption: (T) -> Unit,
    getInterruptionDelta: () -> T,
    setInterruptionDelta: (T) -> Unit,
    diff: (a: T, b: T) -> T, // a - b
    add: (a: T, b: T, bProgress: Float) -> T, // a + (b * bProgress)
): T {
    val valueBeforeInterruption = getValueBeforeInterruption()

    // If the value before the interruption is specified, it means that this is the first time we
    // compute [value] right after an interruption.
    if (valueBeforeInterruption != unspecifiedValue) {
        // Compute and store the delta between the value before the interruption and the current
        // value.
        setInterruptionDelta(diff(valueBeforeInterruption, value))

        // Reset the value before interruption now that we processed it.
        setValueBeforeInterruption(unspecifiedValue)
    }

    val delta = getInterruptionDelta()
    return if (delta == zeroValue || transition == null) {
        // There was no interruption or there is no transition: just return the value.
        value
    } else {
        // Add `delta * interruptionProgress` to the value so that we animate to value.
        val interruptionProgress = transition.interruptionProgress(layoutImpl)
        if (interruptionProgress == 0f) {
            value
        } else {
            add(value, delta, interruptionProgress)
        }
    }
}

/**
 * Set the interruption delta of a *placement/drawing*-related value (offset, alpha, scale). This
 * ensures that the delta is also set on the other scene in the transition for shared elements, so
 * that there is no jump cut if the scene where the element is placed has changed.
 */
private inline fun <T> setPlacementInterruptionDelta(
    element: Element,
    sceneState: Element.SceneState,
    transition: TransitionState.Transition?,
    delta: T,
    setter: (Element.SceneState, T) -> Unit,
) {
    // Set the interruption delta on the current scene.
    setter(sceneState, delta)

    if (transition == null) {
        return
    }

    // If the element is shared, also set the delta on the other scene so that it is used by that
    // scene if we start overscrolling it and change the scene where the element is placed.
    val otherScene =
        if (sceneState.scene == transition.fromScene) transition.toScene else transition.fromScene
    val otherSceneState = element.sceneStates[otherScene] ?: return
    if (isSharedElementEnabled(element.key, transition)) {
        setter(otherSceneState, delta)
    }
}

private fun shouldPlaceElement(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: SceneKey,
    element: Element,
    transition: TransitionState.Transition?,
): Boolean {
    // Always place the element if we are idle.
    if (transition == null) {
        return true
    }

    // Don't place the element in this scene if this scene is not part of the current element
    // transition.
    if (scene != transition.fromScene && scene != transition.toScene) {
        return false
    }

    // Place the element if it is not shared.
    if (
        transition.fromScene !in element.sceneStates || transition.toScene !in element.sceneStates
    ) {
        return true
    }

    val sharedTransformation = sharedElementTransformation(element.key, transition)
    if (sharedTransformation?.enabled == false) {
        return true
    }

    return shouldPlaceOrComposeSharedElement(
        layoutImpl,
        scene,
        element.key,
        transition,
    )
}

internal fun shouldPlaceOrComposeSharedElement(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: SceneKey,
    element: ElementKey,
    transition: TransitionState.Transition,
): Boolean {
    // If we are overscrolling, only place/compose the element in the overscrolling scene.
    val overscrollScene = transition.currentOverscrollSpec?.scene
    if (overscrollScene != null) {
        return scene == overscrollScene
    }

    val scenePicker = element.scenePicker
    val fromScene = transition.fromScene
    val toScene = transition.toScene

    val pickedScene =
        scenePicker.sceneDuringTransition(
            element = element,
            transition = transition,
            fromSceneZIndex = layoutImpl.scenes.getValue(fromScene).zIndex,
            toSceneZIndex = layoutImpl.scenes.getValue(toScene).zIndex,
        ) ?: return false

    return pickedScene == scene
}

private fun isSharedElementEnabled(
    element: ElementKey,
    transition: TransitionState.Transition,
): Boolean {
    return sharedElementTransformation(element, transition)?.enabled ?: true
}

internal fun sharedElementTransformation(
    element: ElementKey,
    transition: TransitionState.Transition,
): SharedElementTransformation? {
    val transformationSpec = transition.transformationSpec
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
    scene: Scene,
    element: Element,
    transition: TransitionState.Transition?,
): Boolean {
    if (transition == null) {
        return true
    }

    val fromScene = transition.fromScene
    val toScene = transition.toScene
    val fromState = element.sceneStates[fromScene]
    val toState = element.sceneStates[toScene]

    if (fromState == null && toState == null) {
        // TODO(b/311600838): Throw an exception instead once layers of disposed elements are not
        // run anymore.
        return true
    }

    val isSharedElement = fromState != null && toState != null
    if (isSharedElement && isSharedElementEnabled(element.key, transition)) {
        return true
    }

    return transition.transformationSpec.transformations(element.key, scene.key).alpha == null
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
    scene: Scene,
    element: Element,
    transition: TransitionState.Transition?,
    sceneState: Element.SceneState,
): Float {
    val alpha =
        computeValue(
                layoutImpl,
                scene,
                element,
                transition,
                sceneValue = { 1f },
                transformation = { it.alpha },
                idleValue = 1f,
                currentValue = { 1f },
                isSpecified = { true },
                ::lerp,
            )
            .fastCoerceIn(0f, 1f)

    // If the element is fading during this transition and that it is drawn for the first time, make
    // sure that it doesn't instantly appear on screen.
    if (!element.wasDrawnInAnyScene && alpha > 0f) {
        element.sceneStates.forEach { it.value.alphaBeforeInterruption = 0f }
    }

    val interruptedAlpha = interruptedAlpha(layoutImpl, element, transition, sceneState, alpha)
    sceneState.lastAlpha = interruptedAlpha
    return interruptedAlpha
}

private fun interruptedAlpha(
    layoutImpl: SceneTransitionLayoutImpl,
    element: Element,
    transition: TransitionState.Transition?,
    sceneState: Element.SceneState,
    alpha: Float,
): Float {
    return computeInterruptedValue(
        layoutImpl,
        transition,
        value = alpha,
        unspecifiedValue = Element.AlphaUnspecified,
        zeroValue = 0f,
        getValueBeforeInterruption = { sceneState.alphaBeforeInterruption },
        setValueBeforeInterruption = { sceneState.alphaBeforeInterruption = it },
        getInterruptionDelta = { sceneState.alphaInterruptionDelta },
        setInterruptionDelta = { delta ->
            setPlacementInterruptionDelta(
                element = element,
                sceneState = sceneState,
                transition = transition,
                delta = delta,
                setter = { sceneState, delta -> sceneState.alphaInterruptionDelta = delta },
            )
        },
        diff = { a, b -> a - b },
        add = { a, b, bProgress -> a + b * bProgress },
    )
}

private fun ApproachMeasureScope.measure(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    element: Element,
    transition: TransitionState.Transition?,
    sceneState: Element.SceneState,
    measurable: Measurable,
    constraints: Constraints,
): Placeable {
    // Some lambdas called (max once) by computeValue() will need to measure [measurable], in which
    // case we store the resulting placeable here to make sure the element is not measured more than
    // once.
    var maybePlaceable: Placeable? = null

    val targetSize =
        computeValue(
            layoutImpl,
            scene,
            element,
            transition,
            sceneValue = { it.targetSize },
            transformation = { it.size },
            idleValue = lookaheadSize,
            currentValue = { measurable.measure(constraints).also { maybePlaceable = it }.size() },
            isSpecified = { it != Element.SizeUnspecified },
            ::lerp,
        )

    // The measurable was already measured, so we can't take interruptions into account here given
    // that we are not allowed to measure the same measurable twice.
    maybePlaceable?.let { placeable ->
        sceneState.sizeBeforeInterruption = Element.SizeUnspecified
        sceneState.sizeInterruptionDelta = IntSize.Zero
        return placeable
    }

    val interruptedSize =
        computeInterruptedValue(
            layoutImpl,
            transition,
            value = targetSize,
            unspecifiedValue = Element.SizeUnspecified,
            zeroValue = IntSize.Zero,
            getValueBeforeInterruption = { sceneState.sizeBeforeInterruption },
            setValueBeforeInterruption = { sceneState.sizeBeforeInterruption = it },
            getInterruptionDelta = { sceneState.sizeInterruptionDelta },
            setInterruptionDelta = { sceneState.sizeInterruptionDelta = it },
            diff = { a, b -> IntSize(a.width - b.width, a.height - b.height) },
            add = { a, b, bProgress ->
                IntSize(
                    (a.width + b.width * bProgress).roundToInt(),
                    (a.height + b.height * bProgress).roundToInt(),
                )
            },
        )

    return measurable.measure(
        Constraints.fixed(
            interruptedSize.width.coerceAtLeast(0),
            interruptedSize.height.coerceAtLeast(0),
        )
    )
}

private fun Placeable.size(): IntSize = IntSize(width, height)

private fun ContentDrawScope.getDrawScale(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    element: Element,
    transition: TransitionState.Transition?,
    sceneState: Element.SceneState,
): Scale {
    val scale =
        computeValue(
            layoutImpl,
            scene,
            element,
            transition,
            sceneValue = { Scale.Default },
            transformation = { it.drawScale },
            idleValue = Scale.Default,
            currentValue = { Scale.Default },
            isSpecified = { true },
            ::lerp,
        )

    fun Offset.specifiedOrCenter(): Offset {
        return this.takeIf { isSpecified } ?: center
    }

    val interruptedScale =
        computeInterruptedValue(
            layoutImpl,
            transition,
            value = scale,
            unspecifiedValue = Scale.Unspecified,
            zeroValue = Scale.Zero,
            getValueBeforeInterruption = { sceneState.scaleBeforeInterruption },
            setValueBeforeInterruption = { sceneState.scaleBeforeInterruption = it },
            getInterruptionDelta = { sceneState.scaleInterruptionDelta },
            setInterruptionDelta = { delta ->
                setPlacementInterruptionDelta(
                    element = element,
                    sceneState = sceneState,
                    transition = transition,
                    delta = delta,
                    setter = { sceneState, delta -> sceneState.scaleInterruptionDelta = delta },
                )
            },
            diff = { a, b ->
                Scale(
                    scaleX = a.scaleX - b.scaleX,
                    scaleY = a.scaleY - b.scaleY,
                    pivot =
                        if (a.pivot.isUnspecified && b.pivot.isUnspecified) {
                            Offset.Unspecified
                        } else {
                            a.pivot.specifiedOrCenter() - b.pivot.specifiedOrCenter()
                        }
                )
            },
            add = { a, b, bProgress ->
                Scale(
                    scaleX = a.scaleX + b.scaleX * bProgress,
                    scaleY = a.scaleY + b.scaleY * bProgress,
                    pivot =
                        if (a.pivot.isUnspecified && b.pivot.isUnspecified) {
                            Offset.Unspecified
                        } else {
                            a.pivot.specifiedOrCenter() + b.pivot.specifiedOrCenter() * bProgress
                        }
                )
            }
        )

    sceneState.lastScale = interruptedScale
    return interruptedScale
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
 * @param lerp the linear interpolation function used to interpolate between two values of this
 *   value type.
 */
private inline fun <T> computeValue(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    element: Element,
    transition: TransitionState.Transition?,
    sceneValue: (Element.SceneState) -> T,
    transformation: (ElementTransformations) -> PropertyTransformation<T>?,
    idleValue: T,
    currentValue: () -> T,
    isSpecified: (T) -> Boolean,
    lerp: (T, T, Float) -> T,
): T {
    if (transition == null) {
        // There is no ongoing transition. Even if this element SceneTransitionLayout is not
        // animated, the layout itself might be animated (e.g. by another parent
        // SceneTransitionLayout), in which case this element still need to participate in the
        // layout phase.
        return currentValue()
    }

    val fromScene = transition.fromScene
    val toScene = transition.toScene

    val fromState = element.sceneStates[fromScene]
    val toState = element.sceneStates[toScene]

    if (fromState == null && toState == null) {
        // TODO(b/311600838): Throw an exception instead once layers of disposed elements are not
        // run anymore.
        return idleValue
    }

    if (transition is TransitionState.HasOverscrollProperties) {
        val overscroll = transition.currentOverscrollSpec
        if (overscroll?.scene == scene.key) {
            val elementSpec = overscroll.transformationSpec.transformations(element.key, scene.key)
            val propertySpec = transformation(elementSpec) ?: return currentValue()
            val overscrollState = checkNotNull(if (scene.key == toScene) toState else fromState)
            val targetValue =
                propertySpec.transform(
                    layoutImpl,
                    scene,
                    element,
                    overscrollState,
                    transition,
                    idleValue,
                )

            // Make sure we don't read progress if values are the same and we don't need to
            // interpolate, so we don't invalidate the phase where this is read.
            if (targetValue == idleValue) {
                return targetValue
            }

            // TODO(b/290184746): Make sure that we don't overflow transformations associated to a
            // range.
            val directionSign = if (transition.isUpOrLeft) -1 else 1
            val isToScene = overscroll.scene == transition.toScene
            val overscrollProgress = transition.progress.let { if (isToScene) it - 1f else it }
            val progress = directionSign * overscrollProgress
            val rangeProgress = propertySpec.range?.progress(progress) ?: progress

            // Interpolate between the value at rest and the over scrolled value.
            return lerp(idleValue, targetValue, rangeProgress)
        }
    }

    // The element is shared: interpolate between the value in fromScene and the value in toScene.
    // TODO(b/290184746): Support non linear shared paths as well as a way to make sure that shared
    // elements follow the finger direction.
    val isSharedElement = fromState != null && toState != null
    if (isSharedElement && isSharedElementEnabled(element.key, transition)) {
        val start = sceneValue(fromState!!)
        val end = sceneValue(toState!!)

        // TODO(b/316901148): Remove checks to isSpecified() once the lookahead pass runs for all
        // nodes before the intermediate layout pass.
        if (!isSpecified(start)) return end
        if (!isSpecified(end)) return start

        // Make sure we don't read progress if values are the same and we don't need to interpolate,
        // so we don't invalidate the phase where this is read.
        return if (start == end) start else lerp(start, end, transition.progress)
    }

    val transformation =
        transformation(transition.transformationSpec.transformations(element.key, scene.key))
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
