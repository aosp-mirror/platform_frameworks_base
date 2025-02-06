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
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.compose.ui.util.fastForEachReversed
import androidx.compose.ui.util.lerp
import com.android.compose.animation.scene.content.Content
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.transformation.CustomPropertyTransformation
import com.android.compose.animation.scene.transformation.InterpolatedPropertyTransformation
import com.android.compose.animation.scene.transformation.PropertyTransformation
import com.android.compose.animation.scene.transformation.TransformationWithRange
import com.android.compose.modifiers.thenIf
import com.android.compose.ui.graphics.drawInContainer
import com.android.compose.ui.util.lerp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** An element on screen, that can be composed in one or more contents. */
@Stable
internal class Element(val key: ElementKey) {
    /** The mapping between a content and the state this element has in that content, if any. */
    // TODO(b/316901148): Make this a normal map instead once we can make sure that new transitions
    // are first seen by composition then layout/drawing code. See b/316901148#comment2 for details.
    val stateByContent = SnapshotStateMap<ContentKey, State>()

    /**
     * The last transition that was used when computing the state (size, position and alpha) of this
     * element in any content, or `null` if it was last laid out when idle.
     */
    var lastTransition: TransitionState.Transition? = null

    /** Whether this element was ever drawn in a content. */
    var wasDrawnInAnyContent = false

    override fun toString(): String {
        return "Element(key=$key)"
    }

    /** The last and target state of this element in a given content. */
    @Stable
    class State(
        /**
         * A list of contents where this element state finds itself in. The last content is the
         * content of the STL which is actually responsible to compose and place this element. The
         * other contents (if any) are the ancestors. The ancestors do not actually place this
         * element but the element is part of the ancestors scene as part of a NestedSTL. The state
         * can be accessed by ancestor transitions to read the properties of this element to compute
         * transformations.
         */
        val contents: List<ContentKey>
    ) {
        /**
         * The *target* state of this element in this content, i.e. the state of this element when
         * we are idle on this content.
         */
        var targetSize by mutableStateOf(SizeUnspecified)
        var targetOffset by mutableStateOf(Offset.Unspecified)

        /** The last state this element had in this content. */
        var lastOffset = Offset.Unspecified
        var lastSize = SizeUnspecified
        var lastScale = Scale.Unspecified
        var lastAlpha = AlphaUnspecified

        /**
         * The state of this element in this content right before the last interruption (if any).
         */
        var offsetBeforeInterruption = Offset.Unspecified
        var sizeBeforeInterruption = SizeUnspecified
        var scaleBeforeInterruption = Scale.Unspecified
        var alphaBeforeInterruption = AlphaUnspecified

        /**
         * The delta values to add to this element state to have smoother interruptions. These
         * should be multiplied by the
         * [current interruption progress][ContentState.Transition.interruptionProgress] so that
         * they nicely animate from their values down to 0.
         */
        var offsetInterruptionDelta = Offset.Zero
        var sizeInterruptionDelta = IntSize.Zero
        var scaleInterruptionDelta = Scale.Zero
        var alphaInterruptionDelta = 0f

        /**
         * The attached [ElementNode] a Modifier.element() for a given element and content. During
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

/** The implementation of [ContentScope.element]. */
@Stable
internal fun Modifier.element(
    layoutImpl: SceneTransitionLayoutImpl,
    content: Content,
    key: ElementKey,
): Modifier {
    // Make sure that we read the current transitions during composition and not during
    // layout/drawing.
    // TODO(b/341072461): Revert this and read the current transitions in ElementNode directly once
    // we can ensure that SceneTransitionLayoutImpl will compose new contents first.
    val currentTransitionStates = getAllNestedTransitionStates(layoutImpl)

    return thenIf(layoutImpl.state.isElevationPossible(content.key, key)) {
            Modifier.maybeElevateInContent(layoutImpl, content, key, currentTransitionStates)
        }
        .then(ElementModifier(layoutImpl, currentTransitionStates, content, key))
        .testTag(key.testTag)
}

/**
 * Returns the transition states of all ancestors + the transition state of the current STL. The
 * last element is the transition state of the local STL (the one with the highest nestingDepth).
 *
 * @return Each transition state of a STL is a List and this is a list of all the states.
 */
internal fun getAllNestedTransitionStates(
    layoutImpl: SceneTransitionLayoutImpl
): List<List<TransitionState>> {
    return buildList {
        layoutImpl.ancestors.fastForEach { add(it.layoutImpl.state.transitionStates) }
        add(layoutImpl.state.transitionStates)
    }
}

private fun Modifier.maybeElevateInContent(
    layoutImpl: SceneTransitionLayoutImpl,
    content: Content,
    key: ElementKey,
    transitionStates: List<List<TransitionState>>,
): Modifier {
    fun isSharedElement(
        stateByContent: Map<ContentKey, Element.State>,
        transition: TransitionState.Transition,
    ): Boolean {
        fun inFromContent() = transition.fromContent in stateByContent
        fun inToContent() = transition.toContent in stateByContent
        fun inCurrentScene() = transition.currentScene in stateByContent

        return if (transition is TransitionState.Transition.ReplaceOverlay) {
            (inFromContent() && (inToContent() || inCurrentScene())) ||
                (inToContent() && inCurrentScene())
        } else {
            inFromContent() && inToContent()
        }
    }

    return drawInContainer(
        content.containerState,
        enabled = {
            val stateByContent = layoutImpl.elements.getValue(key).stateByContent
            val state = elementState(transitionStates, key, isInContent = { it in stateByContent })

            state is TransitionState.Transition &&
                state.transformationSpec
                    .transformations(key, content.key)
                    ?.shared
                    ?.transformation
                    ?.elevateInContent == content.key &&
                isSharedElement(stateByContent, state) &&
                isSharedElementEnabled(key, state) &&
                shouldPlaceElement(
                    layoutImpl,
                    content.key,
                    layoutImpl.elements.getValue(key),
                    state,
                )
        },
    )
}

/**
 * An element associated to [ElementNode]. Note that this element does not support updates as its
 * arguments should always be the same.
 */
internal data class ElementModifier(
    internal val layoutImpl: SceneTransitionLayoutImpl,
    private val currentTransitionStates: List<List<TransitionState>>,
    internal val content: Content,
    internal val key: ElementKey,
) : ModifierNodeElement<ElementNode>() {
    override fun create(): ElementNode =
        ElementNode(layoutImpl, currentTransitionStates, content, key)

    override fun update(node: ElementNode) {
        node.update(layoutImpl, currentTransitionStates, content, key)
    }
}

internal class ElementNode(
    private var layoutImpl: SceneTransitionLayoutImpl,
    private var currentTransitionStates: List<List<TransitionState>>,
    private var content: Content,
    private var key: ElementKey,
) : Modifier.Node(), DrawModifierNode, ApproachLayoutModifierNode, TraversableNode {
    private var _element: Element? = null
    private val element: Element
        get() = _element!!

    private val stateInContent: Element.State
        get() = element.stateByContent.getValue(content.key)

    override val traverseKey: Any = ElementTraverseKey

    override fun onAttach() {
        super.onAttach()
        updateElementAndContentValues()
        addNodeToContentState()
    }

    private fun updateElementAndContentValues() {
        val element =
            layoutImpl.elements[key] ?: Element(key).also { layoutImpl.elements[key] = it }
        _element = element
        if (!element.stateByContent.contains(content.key)) {
            val contents = buildList {
                layoutImpl.ancestors.fastForEach { add(it.inContent) }
                add(content.key)
            }

            val elementState = Element.State(contents)
            element.stateByContent[content.key] = elementState

            layoutImpl.ancestors.fastForEach {
                element.stateByContent.putIfAbsent(it.inContent, elementState)
            }
        }
    }

    private fun addNodeToContentState() {
        stateInContent.nodes.add(this)

        coroutineScope.launch {
            // At this point all [CodeLocationNode] have been attached or detached, which means that
            // [elementState.codeLocations] should have exactly 1 element, otherwise this means that
            // this element was composed multiple times in the same content.
            val nCodeLocations = stateInContent.nodes.size
            if (nCodeLocations != 1 || !stateInContent.nodes.contains(this@ElementNode)) {
                error("$key was composed $nCodeLocations times in ${stateInContent.contents}")
            }
        }
    }

    override fun onDetach() {
        super.onDetach()
        removeNodeFromContentState()
        maybePruneMaps(layoutImpl, element, stateInContent)

        _element = null
    }

    private fun removeNodeFromContentState() {
        stateInContent.nodes.remove(this)
    }

    fun update(
        layoutImpl: SceneTransitionLayoutImpl,
        currentTransitionStates: List<List<TransitionState>>,
        content: Content,
        key: ElementKey,
    ) {
        check(layoutImpl == this.layoutImpl && content == this.content)
        this.currentTransitionStates = currentTransitionStates

        removeNodeFromContentState()

        val prevElement = this.element
        val prevElementState = this.stateInContent
        this.key = key
        updateElementAndContentValues()

        addNodeToContentState()
        maybePruneMaps(layoutImpl, prevElement, prevElementState)
    }

    override fun isMeasurementApproachInProgress(lookaheadSize: IntSize): Boolean {
        // TODO(b/324191441): Investigate whether making this check more complex (checking if this
        // element is shared or transformed) would lead to better performance.
        return isAnyStateTransitioning()
    }

    override fun Placeable.PlacementScope.isPlacementApproachInProgress(
        lookaheadCoordinates: LayoutCoordinates
    ): Boolean {
        // TODO(b/324191441): Investigate whether making this check more complex (checking if this
        // element is shared or transformed) would lead to better performance.
        return isAnyStateTransitioning()
    }

    private fun isAnyStateTransitioning(): Boolean {
        return layoutImpl.state.isTransitioning() ||
            layoutImpl.ancestors.fastAny { it.layoutImpl.state.isTransitioning() }
    }

    @ExperimentalComposeUiApi
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        check(isLookingAhead)

        return measurable.measure(constraints).run {
            // Update the size this element has in this content when idle.
            stateInContent.targetSize = size()

            layout(width, height) {
                // Update the offset (relative to the SceneTransitionLayout) this element has in
                // this content when idle.
                coordinates?.let { coords ->
                    with(layoutImpl.lookaheadScope) {
                        stateInContent.targetOffset =
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
        val elementState = elementState(layoutImpl, element, currentTransitionStates)
        if (elementState == null) {
            // If the element is not part of any transition, place it normally in its idle scene.
            // This is the case if for example a transition between two overlays is ongoing where
            // sharedElement isn't part of either but the element is still rendered as part of
            // the underlying scene that is currently not being transitioned.
            val currentState = currentTransitionStates.last().last()
            val shouldPlaceInThisContent =
                elementContentWhenIdle(
                    layoutImpl,
                    currentState,
                    isInContent = { it in element.stateByContent },
                ) == content.key
            return if (shouldPlaceInThisContent) {
                placeNormally(measurable, constraints)
            } else {
                doNotPlace(measurable, constraints)
            }
        }
        syncAncestorElementState()

        val transition = elementState as? TransitionState.Transition

        val placeable =
            measure(layoutImpl, element, transition, stateInContent, measurable, constraints)
        stateInContent.lastSize = placeable.size()
        return layout(placeable.width, placeable.height) { place(elementState, placeable) }
    }

    private fun ApproachMeasureScope.doNotPlace(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        recursivelyClearPlacementValues()
        stateInContent.lastSize = Element.SizeUnspecified

        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { /* Do not place */ }
    }

    private fun ApproachMeasureScope.placeNormally(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        stateInContent.lastSize = placeable.size()
        return layout(placeable.width, placeable.height) {
            coordinates?.let {
                with(layoutImpl.lookaheadScope) {
                    stateInContent.lastOffset =
                        lookaheadScopeCoordinates.localPositionOf(it, Offset.Zero)
                }
            }

            placeable.place(0, 0)
        }
    }

    private fun Placeable.PlacementScope.place(
        elementState: TransitionState,
        placeable: Placeable,
    ) {
        with(layoutImpl.lookaheadScope) {
            // Update the offset (relative to the SceneTransitionLayout) this element has in this
            // content when idle.
            val coords =
                coordinates ?: error("Element ${element.key} does not have any coordinates")

            // No need to place the element in this content if we don't want to draw it anyways.
            if (!shouldPlaceElement(layoutImpl, content.key, element, elementState)) {
                recursivelyClearPlacementValues()
                return
            }

            val transition = elementState as? TransitionState.Transition
            val currentOffset = lookaheadScopeCoordinates.localPositionOf(coords, Offset.Zero)
            val targetOffset =
                computeValue(
                    layoutImpl,
                    stateInContent,
                    element,
                    transition,
                    contentValue = { it.targetOffset },
                    transformation = { it?.offset },
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
                    getValueBeforeInterruption = { stateInContent.offsetBeforeInterruption },
                    setValueBeforeInterruption = { stateInContent.offsetBeforeInterruption = it },
                    getInterruptionDelta = { stateInContent.offsetInterruptionDelta },
                    setInterruptionDelta = { delta ->
                        setPlacementInterruptionDelta(
                            element = element,
                            stateInContent = stateInContent,
                            transition = transition,
                            delta = delta,
                            setter = { stateInContent, delta ->
                                stateInContent.offsetInterruptionDelta = delta
                            },
                        )
                    },
                    diff = { a, b -> a - b },
                    add = { a, b, bProgress -> a + b * bProgress },
                )

            stateInContent.lastOffset = interruptedOffset

            val offset = (interruptedOffset - currentOffset).round()
            if (
                isElementOpaque(content, element, transition) &&
                    interruptedAlpha(layoutImpl, element, transition, stateInContent, alpha = 1f) ==
                        1f
            ) {
                stateInContent.lastAlpha = 1f

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

                    val elementState = elementState(layoutImpl, element, currentTransitionStates)
                    if (
                        elementState == null ||
                            !shouldPlaceElement(layoutImpl, content.key, element, elementState)
                    ) {
                        return@placeWithLayer
                    }

                    val transition = elementState as? TransitionState.Transition
                    alpha = elementAlpha(layoutImpl, element, transition, stateInContent)
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
            }
        }
    }

    /**
     * This method makes sure that the ancestor element state is *roughly* in sync. Assume we have
     * the following nested scenes:
     * ```
     *       /   \
     *     P1     P2
     *   /   \
     *  C1   C2
     * ```
     *
     * We write the state of the shared element into its parent P1 even though P1 does not contain
     * the element directly but it's part of its NestedSTL instead. This value is used to
     * interpolate transitions on higher levels, e.g. between P1 and P2. Technically the best
     * solution would be to always write the fully interpolated state into P1 but because this
     * interferes with `computeValue` computations of other transitions this solution requires more
     * sophistication and additional invocations of `computeValue`. We might want to aim for such a
     * solution in the future when we allocate resources to that feature. For now, we only roughly
     * set the state of P1 to either C1 or C2 based on heuristics.
     *
     * If we assign the P1 state just on attach/detach of a scene like we do for C1 and C2, this
     * leads to problems where P1 can either become stale or is erased. This leads to situations
     * where a shared element is not animated anymore.
     *
     * With this solution we track the transition state of the local transition at all times and set
     * P1 based on the currentScene or overlay. In certain sequences of transition this may create
     * jump cuts of a shared element mainly because of two reasons:
     *
     * a) P1 state is modified during a transition of P1 and X. Due to the new value it may jump cut
     * when the interruption system is not triggered correctly. b) A dominant parent transition ends
     * (P1 - X) but a local transition is still running, resulting in a different state of the
     * element.
     *
     * Both issues can be addressed by interpolating P1 in the future.
     */
    private fun syncAncestorElementState() {
        // every nested STL syncs only the level above it
        layoutImpl.ancestors.lastOrNull()?.also { ancestor ->
            val localTransition =
                localElementState(
                    currentTransitionStates.last(),
                    isInContent = { it in element.stateByContent },
                )
            when (localTransition) {
                is TransitionState.Idle ->
                    assignState(ancestor.inContent, localTransition.currentScene)
                is TransitionState.Transition.ChangeScene ->
                    assignState(ancestor.inContent, localTransition.currentScene)
                is TransitionState.Transition.ReplaceOverlay ->
                    assignState(ancestor.inContent, localTransition.effectivelyShownOverlay)
                is TransitionState.Transition.ShowOrHideOverlay ->
                    if (localTransition.isEffectivelyShown) {
                        assignState(ancestor.inContent, localTransition.overlay)
                    } else {
                        assignState(ancestor.inContent, localTransition.fromOrToScene)
                    }
                null -> {}
            }
        }
    }

    private fun assignState(toContent: ContentKey, fromContent: ContentKey) {
        val fromState = element.stateByContent[fromContent]
        if (fromState != null) {
            element.stateByContent[toContent] = fromState
        } else {
            element.stateByContent.remove(toContent)
        }
    }

    /**
     * Recursively clear the last placement values on this node and all descendants ElementNodes.
     * This should be called when this node is not placed anymore, so that we correctly clear values
     * for the descendants for which approachMeasure() won't be called.
     */
    private fun recursivelyClearPlacementValues() {
        fun Element.State.clearLastPlacementValues() {
            lastOffset = Offset.Unspecified
            lastScale = Scale.Unspecified
            lastAlpha = Element.AlphaUnspecified
        }

        stateInContent.clearLastPlacementValues()
        traverseDescendants(ElementTraverseKey) { node ->
            if ((node as ElementNode)._element != null) {
                node.stateInContent.clearLastPlacementValues()
            }
            TraversableNode.Companion.TraverseDescendantsAction.ContinueTraversal
        }
    }

    override fun ContentDrawScope.draw() {
        element.wasDrawnInAnyContent = true

        val transition =
            elementState(layoutImpl, element, currentTransitionStates)
                as? TransitionState.Transition
        val drawScale = getDrawScale(layoutImpl, element, transition, stateInContent)
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
            stateInContent: Element.State,
        ) {
            fun pruneForContent(contentKey: ContentKey) {
                // If element is not composed in this content anymore, remove the content values.
                // This works because [onAttach] is called before [onDetach], so if an element is
                // moved from the UI tree we will first add the new code location then remove the
                // old one.
                if (
                    stateInContent.nodes.isEmpty() &&
                        element.stateByContent[contentKey] == stateInContent
                ) {
                    element.stateByContent.remove(contentKey)

                    // If the element is not composed in any content, remove it from the elements
                    // map.
                    if (
                        element.stateByContent.isEmpty() &&
                            layoutImpl.elements[element.key] == element
                    ) {
                        layoutImpl.elements.remove(element.key)
                    }
                }
            }

            stateInContent.contents.fastForEach { pruneForContent(it) }
        }
    }
}

/** The [TransitionState] that we should consider for [element]. */
private fun elementState(
    layoutImpl: SceneTransitionLayoutImpl,
    element: Element,
    transitionStates: List<List<TransitionState>>,
): TransitionState? {
    val state =
        elementState(transitionStates, element.key, isInContent = { it in element.stateByContent })

    val transition = state as? TransitionState.Transition
    val previousTransition = element.lastTransition
    element.lastTransition = transition

    if (transition != previousTransition && transition != null && previousTransition != null) {
        // The previous transition was interrupted by another transition.
        prepareInterruption(layoutImpl, element, transition, previousTransition)
    } else if (transition == null && previousTransition != null) {
        // The transition was just finished.
        element.stateByContent.values.forEach {
            it.clearValuesBeforeInterruption()
            it.clearInterruptionDeltas()
        }
    }

    return state
}

internal inline fun elementState(
    transitionStates: List<List<TransitionState>>,
    elementKey: ElementKey,
    isInContent: (ContentKey) -> Boolean,
): TransitionState? {
    // transitionStates is a list of all ancestor transition states + transitionState of the local
    // STL. By traversing the list in normal order we by default prioritize the transitionState of
    // the highest ancestor if it is running and has a transformation for this element.
    transitionStates.fastForEachIndexed { index, states ->
        if (index < transitionStates.size - 1) {
            // Check if any ancestor runs a transition that has a transformation for the element
            states.fastForEachReversed { state ->
                if (
                    isSharedElement(state, isInContent) ||
                        hasTransformationForElement(state, elementKey)
                ) {
                    return state
                }
            }
        } else {
            return localElementState(states, isInContent)
        }
    }
    return null
}

private inline fun localElementState(
    states: List<TransitionState>,
    isInContent: (ContentKey) -> Boolean,
): TransitionState? {
    // the last state of the list is the state of the local STL
    val lastState = states.last()
    if (lastState is TransitionState.Idle) {
        check(states.size == 1)
        return lastState
    }

    // Find the last transition with a content that contains the element.
    states.fastForEachReversed { state ->
        val transition = state as TransitionState.Transition
        if (isInContent(transition.fromContent) || isInContent(transition.toContent)) {
            return transition
        }
    }

    // We are running a transition where both from and to don't contain the element. The element
    // may still be rendered as e.g. it can be part of a idle scene where two overlays are currently
    // transitioning above it.
    return null
}

private inline fun isSharedElement(
    state: TransitionState,
    isInContent: (ContentKey) -> Boolean,
): Boolean {
    return state is TransitionState.Transition &&
        isInContent(state.fromContent) &&
        isInContent(state.toContent)
}

private fun hasTransformationForElement(state: TransitionState, elementKey: ElementKey): Boolean {
    return state is TransitionState.Transition &&
        (state.transformationSpec.hasTransformation(elementKey, state.fromContent) ||
            state.transformationSpec.hasTransformation(elementKey, state.toContent))
}

internal inline fun elementContentWhenIdle(
    layoutImpl: SceneTransitionLayoutImpl,
    currentState: TransitionState,
    isInContent: (ContentKey) -> Boolean,
): ContentKey {
    val currentScene = currentState.currentScene
    val overlays = currentState.currentOverlays
    if (overlays.isEmpty()) {
        return currentScene
    }

    // Find the overlay with highest zIndex that contains the element.
    // TODO(b/353679003): Should we cache enabledOverlays into a List<> to avoid a lot of
    // allocations here?
    var currentOverlay: OverlayKey? = null
    for (overlay in overlays) {
        if (
            isInContent(overlay) &&
                (currentOverlay == null ||
                    (layoutImpl.overlay(overlay).zIndex >
                        layoutImpl.overlay(currentOverlay).zIndex))
        ) {
            currentOverlay = overlay
        }
    }

    return currentOverlay ?: currentScene
}

private fun prepareInterruption(
    layoutImpl: SceneTransitionLayoutImpl,
    element: Element,
    transition: TransitionState.Transition,
    previousTransition: TransitionState.Transition,
) {
    if (transition.replacedTransition == previousTransition) {
        return
    }

    val stateByContent = element.stateByContent
    fun updateStateInContent(key: ContentKey): Element.State? {
        return stateByContent[key]?.also { it.selfUpdateValuesBeforeInterruption() }
    }

    val previousFromState = updateStateInContent(previousTransition.fromContent)
    val previousToState = updateStateInContent(previousTransition.toContent)
    val fromState = updateStateInContent(transition.fromContent)
    val toState = updateStateInContent(transition.toContent)

    val previousUniqueState = reconcileStates(element, previousTransition, previousState = null)
    reconcileStates(element, transition, previousState = previousUniqueState)

    // Remove the interruption values to all contents but the content(s) where the element will be
    // placed, to make sure that interruption deltas are computed only right after this interruption
    // is prepared.
    fun cleanInterruptionValues(stateInContent: Element.State) {
        stateInContent.sizeInterruptionDelta = IntSize.Zero
        stateInContent.offsetInterruptionDelta = Offset.Zero
        stateInContent.alphaInterruptionDelta = 0f
        stateInContent.scaleInterruptionDelta = Scale.Zero

        if (!shouldPlaceElement(layoutImpl, stateInContent.contents.last(), element, transition)) {
            stateInContent.offsetBeforeInterruption = Offset.Unspecified
            stateInContent.alphaBeforeInterruption = Element.AlphaUnspecified
            stateInContent.scaleBeforeInterruption = Scale.Unspecified
        }
    }

    previousFromState?.let { cleanInterruptionValues(it) }
    previousToState?.let { cleanInterruptionValues(it) }
    fromState?.let { cleanInterruptionValues(it) }
    toState?.let { cleanInterruptionValues(it) }
}

/**
 * Reconcile the state of [element] in the fromContent and toContent of [transition] so that the
 * values before interruption have their expected values, taking shared transitions into account.
 *
 * @return the unique state this element had during [transition], `null` if it had multiple
 *   different states (i.e. the shared animation was disabled).
 */
private fun reconcileStates(
    element: Element,
    transition: TransitionState.Transition,
    previousState: Element.State?,
): Element.State? {
    fun reconcileWithPreviousState(state: Element.State) {
        if (previousState != null && state.offsetBeforeInterruption == Offset.Unspecified) {
            state.updateValuesBeforeInterruption(previousState)
        }
    }

    val fromContentState = element.stateByContent[transition.fromContent]
    val toContentState = element.stateByContent[transition.toContent]

    if (fromContentState == null || toContentState == null) {
        return (fromContentState ?: toContentState)
            ?.also { reconcileWithPreviousState(it) }
            ?.takeIf { it.offsetBeforeInterruption != Offset.Unspecified }
    }

    if (!isSharedElementEnabled(element.key, transition)) {
        return null
    }

    if (
        fromContentState.offsetBeforeInterruption != Offset.Unspecified &&
            toContentState.offsetBeforeInterruption == Offset.Unspecified
    ) {
        // Element is shared and placed in fromContent only.
        toContentState.updateValuesBeforeInterruption(fromContentState)
        return fromContentState
    }

    if (
        toContentState.offsetBeforeInterruption != Offset.Unspecified &&
            fromContentState.offsetBeforeInterruption == Offset.Unspecified
    ) {
        // Element is shared and placed in toContent only.
        fromContentState.updateValuesBeforeInterruption(toContentState)
        return toContentState
    }

    return null
}

private fun Element.State.selfUpdateValuesBeforeInterruption() {
    sizeBeforeInterruption = lastSize

    if (lastAlpha > 0f) {
        offsetBeforeInterruption = lastOffset
        scaleBeforeInterruption = lastScale
        alphaBeforeInterruption = lastAlpha
    } else {
        // Consider the element as not placed in this content if it was fully transparent.
        // TODO(b/290930950): Look into using derived state inside place() instead to not even place
        // the element at all when alpha == 0f.
        offsetBeforeInterruption = Offset.Unspecified
        scaleBeforeInterruption = Scale.Unspecified
        alphaBeforeInterruption = Element.AlphaUnspecified
    }
}

private fun Element.State.updateValuesBeforeInterruption(lastState: Element.State) {
    offsetBeforeInterruption = lastState.offsetBeforeInterruption
    sizeBeforeInterruption = lastState.sizeBeforeInterruption
    scaleBeforeInterruption = lastState.scaleBeforeInterruption
    alphaBeforeInterruption = lastState.alphaBeforeInterruption

    clearInterruptionDeltas()
}

private fun Element.State.clearInterruptionDeltas() {
    offsetInterruptionDelta = Offset.Zero
    sizeInterruptionDelta = IntSize.Zero
    scaleInterruptionDelta = Scale.Zero
    alphaInterruptionDelta = 0f
}

private fun Element.State.clearValuesBeforeInterruption() {
    offsetBeforeInterruption = Offset.Unspecified
    scaleBeforeInterruption = Scale.Unspecified
    alphaBeforeInterruption = Element.AlphaUnspecified
}

/**
 * Compute what [value] should be if we take the
 * [interruption progress][ContentState.Transition.interruptionProgress] of [transition] into
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
 * ensures that the delta is also set on the other content in the transition for shared elements, so
 * that there is no jump cut if the content where the element is placed has changed.
 */
private inline fun <T> setPlacementInterruptionDelta(
    element: Element,
    stateInContent: Element.State,
    transition: TransitionState.Transition?,
    delta: T,
    setter: (Element.State, T) -> Unit,
) {
    // Set the interruption delta on the current content.
    setter(stateInContent, delta)

    if (transition == null) {
        return
    }

    // If the element is shared, also set the delta on the other content so that it is used by that
    // content if we start overscrolling it and change the content where the element is placed.
    val otherContent =
        if (stateInContent.contents.last() == transition.fromContent) transition.toContent
        else transition.fromContent
    val otherContentState = element.stateByContent[otherContent] ?: return
    if (isSharedElementEnabled(element.key, transition)) {
        setter(otherContentState, delta)
    }
}

private fun shouldPlaceElement(
    layoutImpl: SceneTransitionLayoutImpl,
    content: ContentKey,
    element: Element,
    elementState: TransitionState,
): Boolean {
    if (element.key.placeAllCopies) {
        return true
    }

    val transition =
        when (elementState) {
            is TransitionState.Idle -> {
                return content ==
                    elementContentWhenIdle(
                        layoutImpl,
                        elementState,
                        isInContent = { it in element.stateByContent },
                    )
            }
            is TransitionState.Transition -> elementState
        }

    // Don't place the element in this content if this content is not part of the current element
    // transition.
    val isReplacingOverlay = transition is TransitionState.Transition.ReplaceOverlay
    if (
        content != transition.fromContent &&
            content != transition.toContent &&
            (!isReplacingOverlay || content != transition.currentScene) &&
            transitionDoesNotInvolveAncestorContent(layoutImpl, transition)
    ) {
        return false
    }

    // Place the element if it is not shared.
    var copies = 0
    if (transition.fromContent in element.stateByContent) copies++
    if (transition.toContent in element.stateByContent) copies++
    if (isReplacingOverlay && transition.currentScene in element.stateByContent) copies++
    if (copies <= 1) {
        return true
    }

    val sharedTransformation = sharedElementTransformation(element.key, transition)
    if (sharedTransformation?.transformation?.enabled == false) {
        return true
    }

    return shouldPlaceSharedElement(layoutImpl, content, element.key, transition)
}

private fun transitionDoesNotInvolveAncestorContent(
    layoutImpl: SceneTransitionLayoutImpl,
    transition: TransitionState.Transition,
): Boolean {
    return layoutImpl.ancestors.fastAll {
        it.inContent != transition.fromContent && it.inContent != transition.toContent
    }
}

/**
 * Whether the element is opaque or not.
 *
 * Important: The logic here should closely match the logic in [elementAlpha]. Note that we don't
 * reuse [elementAlpha] and simply check if alpha == 1f because [isElementOpaque] is checked during
 * placement and we don't want to read the transition progress in that phase.
 */
private fun isElementOpaque(
    content: Content,
    element: Element,
    transition: TransitionState.Transition?,
): Boolean {
    if (transition == null) {
        return true
    }

    val fromState = element.stateByContent[transition.fromContent]
    val toState = element.stateByContent[transition.toContent]

    if (fromState == null && toState == null) {
        // TODO(b/311600838): Throw an exception instead once layers of disposed elements are not
        // run anymore.
        return true
    }

    val isSharedElement = fromState != null && toState != null
    if (isSharedElement && isSharedElementEnabled(element.key, transition)) {
        return true
    }

    return transition.transformationSpec.transformations(element.key, content.key)?.alpha == null
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
    transition: TransitionState.Transition?,
    stateInContent: Element.State,
): Float {
    val alpha =
        computeValue(
                layoutImpl,
                stateInContent,
                element,
                transition,
                contentValue = { 1f },
                transformation = { it?.alpha },
                currentValue = { 1f },
                isSpecified = { true },
                ::lerp,
            )
            .fastCoerceIn(0f, 1f)

    // If the element is fading during this transition and that it is drawn for the first time, make
    // sure that it doesn't instantly appear on screen.
    if (!element.wasDrawnInAnyContent && alpha > 0f) {
        element.stateByContent.forEach { it.value.alphaBeforeInterruption = 0f }
    }

    val interruptedAlpha = interruptedAlpha(layoutImpl, element, transition, stateInContent, alpha)
    stateInContent.lastAlpha = interruptedAlpha
    return interruptedAlpha
}

private fun interruptedAlpha(
    layoutImpl: SceneTransitionLayoutImpl,
    element: Element,
    transition: TransitionState.Transition?,
    stateInContent: Element.State,
    alpha: Float,
): Float {
    return computeInterruptedValue(
        layoutImpl,
        transition,
        value = alpha,
        unspecifiedValue = Element.AlphaUnspecified,
        zeroValue = 0f,
        getValueBeforeInterruption = { stateInContent.alphaBeforeInterruption },
        setValueBeforeInterruption = { stateInContent.alphaBeforeInterruption = it },
        getInterruptionDelta = { stateInContent.alphaInterruptionDelta },
        setInterruptionDelta = { delta ->
            setPlacementInterruptionDelta(
                element = element,
                stateInContent = stateInContent,
                transition = transition,
                delta = delta,
                setter = { stateInContent, delta -> stateInContent.alphaInterruptionDelta = delta },
            )
        },
        diff = { a, b -> a - b },
        add = { a, b, bProgress -> a + b * bProgress },
    )
}

private fun measure(
    layoutImpl: SceneTransitionLayoutImpl,
    element: Element,
    transition: TransitionState.Transition?,
    stateInContent: Element.State,
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
            stateInContent,
            element,
            transition,
            contentValue = { it.targetSize },
            transformation = { it?.size },
            currentValue = { measurable.measure(constraints).also { maybePlaceable = it }.size() },
            isSpecified = { it != Element.SizeUnspecified },
            ::lerp,
        )

    // The measurable was already measured, so we can't take interruptions into account here given
    // that we are not allowed to measure the same measurable twice.
    maybePlaceable?.let { placeable ->
        stateInContent.sizeBeforeInterruption = Element.SizeUnspecified
        stateInContent.sizeInterruptionDelta = IntSize.Zero
        return placeable
    }

    val interruptedSize =
        computeInterruptedValue(
            layoutImpl,
            transition,
            value = targetSize,
            unspecifiedValue = Element.SizeUnspecified,
            zeroValue = IntSize.Zero,
            getValueBeforeInterruption = { stateInContent.sizeBeforeInterruption },
            setValueBeforeInterruption = { stateInContent.sizeBeforeInterruption = it },
            getInterruptionDelta = { stateInContent.sizeInterruptionDelta },
            setInterruptionDelta = { stateInContent.sizeInterruptionDelta = it },
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
    element: Element,
    transition: TransitionState.Transition?,
    stateInContent: Element.State,
): Scale {
    val scale =
        computeValue(
            layoutImpl,
            stateInContent,
            element,
            transition,
            contentValue = { Scale.Default },
            transformation = { it?.drawScale },
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
            getValueBeforeInterruption = { stateInContent.scaleBeforeInterruption },
            setValueBeforeInterruption = { stateInContent.scaleBeforeInterruption = it },
            getInterruptionDelta = { stateInContent.scaleInterruptionDelta },
            setInterruptionDelta = { delta ->
                setPlacementInterruptionDelta(
                    element = element,
                    stateInContent = stateInContent,
                    transition = transition,
                    delta = delta,
                    setter = { stateInContent, delta ->
                        stateInContent.scaleInterruptionDelta = delta
                    },
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
                        },
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
                        },
                )
            },
        )

    stateInContent.lastScale = interruptedScale
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
 * @param currentContentState the content state of the content for which we are computing the value.
 *   Note that during interruptions, this could be the state of a content that is neither
 *   [transition.toContent] nor [transition.fromContent].
 * @param element the element being animated.
 * @param contentValue the value being animated.
 * @param transformation the transformation associated to the value being animated.
 * @param currentValue the value that would be used if it is not transformed. Note that this is
 *   different than [idleValue] even if the value is not transformed directly because it could be
 *   impacted by the transformations on other elements, like a parent that is being translated or
 *   resized.
 * @param lerp the linear interpolation function used to interpolate between two values of this
 *   value type.
 */
private inline fun <T> computeValue(
    layoutImpl: SceneTransitionLayoutImpl,
    currentContentState: Element.State,
    element: Element,
    transition: TransitionState.Transition?,
    contentValue: (Element.State) -> T,
    transformation:
        (ElementTransformations?) -> TransformationWithRange<PropertyTransformation<T>>?,
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

    val fromContent = transition.fromContent
    val toContent = transition.toContent

    val fromState = element.stateByContent[fromContent]
    val toState = element.stateByContent[toContent]

    if (fromState == null && toState == null) {
        // TODO(b/311600838): Throw an exception instead once layers of disposed elements are not
        // run anymore.
        return contentValue(currentContentState)
    }

    val currentContent = currentContentState.contents.last()

    // The element is shared: interpolate between the value in fromContent and toContent.
    // TODO(b/290184746): Support non linear shared paths as well as a way to make sure that shared
    // elements follow the finger direction.
    val isSharedElement = fromState != null && toState != null
    if (isSharedElement && isSharedElementEnabled(element.key, transition)) {
        return interpolateSharedElement(
            transition = transition,
            contentValue = contentValue,
            fromState = fromState!!,
            toState = toState!!,
            isSpecified = isSpecified,
            lerp = lerp,
        )
    }

    // If we are replacing an overlay and the element is both in a single overlay and in the current
    // scene, interpolate the state of the element using the current scene as the other scene.
    var currentSceneState: Element.State? = null
    if (!isSharedElement && transition is TransitionState.Transition.ReplaceOverlay) {
        currentSceneState = element.stateByContent[transition.currentScene]
        if (currentSceneState != null && isSharedElementEnabled(element.key, transition)) {
            return interpolateSharedElement(
                transition = transition,
                contentValue = contentValue,
                fromState = fromState ?: currentSceneState,
                toState = toState ?: currentSceneState,
                isSpecified = isSpecified,
                lerp = lerp,
            )
        }
    }

    // The content for which we compute the transformation. Note that this is not necessarily
    // [currentContent] because [currentContent] could be a different content than the transition
    // fromContent or toContent during interruptions or when a ancestor transition is running.
    val transformationContentKey: ContentKey =
        getTransformationContentKey(
            isDisabledSharedElement = isSharedElement,
            currentContent = currentContent,
            layoutImpl = layoutImpl,
            transition = transition,
            element = element,
            currentSceneState = currentSceneState,
        )
    // Get the transformed value, i.e. the target value at the beginning (for entering elements) or
    // end (for leaving elements) of the transition.
    val targetState: Element.State = element.stateByContent.getValue(transformationContentKey)
    val idleValue = contentValue(targetState)

    val transformationWithRange =
        transformation(
            transition.transformationSpec.transformations(element.key, transformationContentKey)
        )

    val isElementEntering =
        when {
            transformationContentKey == toContent -> true
            transformationContentKey == fromContent -> false
            isAncestorTransition(layoutImpl, transition) ->
                isEnteringAncestorTransition(layoutImpl, transition)
            transformationContentKey == transition.currentScene -> toState == null
            else -> transformationContentKey == toContent
        }

    val previewTransformation =
        transition.previewTransformationSpec?.let {
            transformation(it.transformations(element.key, transformationContentKey))
        }

    if (previewTransformation != null) {
        return computePreviewTransformationValue(
            transition,
            idleValue,
            transformationContentKey,
            isElementEntering,
            previewTransformation,
            element,
            layoutImpl,
            transformationWithRange,
            lerp,
        )
    }

    if (transformationWithRange == null) {
        // If there is no transformation explicitly associated to this element value, let's use
        // the value given by the system (like the current position and size given by the layout
        // pass).
        return currentValue()
    }

    val transformation = transformationWithRange.transformation
    when (transformation) {
        is CustomPropertyTransformation ->
            return with(transformation) {
                layoutImpl.propertyTransformationScope.transform(
                    transformationContentKey,
                    element.key,
                    transition,
                    transition.coroutineScope,
                )
            }
        is InterpolatedPropertyTransformation -> {
            /* continue */
        }
    }

    val targetValue =
        with(transformation) {
            layoutImpl.propertyTransformationScope.transform(
                transformationContentKey,
                element.key,
                transition,
                idleValue,
            )
        }

    // Make sure we don't read progress if values are the same and we don't need to interpolate, so
    // we don't invalidate the phase where this is read.
    if (targetValue == idleValue) {
        return targetValue
    }

    val progress = transition.progress
    // TODO(b/290184746): Make sure that we don't overflow transformations associated to a range.
    val rangeProgress = transformationWithRange.range?.progress(progress) ?: progress

    return if (isElementEntering) {
        lerp(targetValue, idleValue, rangeProgress)
    } else {
        lerp(idleValue, targetValue, rangeProgress)
    }
}

private fun getTransformationContentKey(
    isDisabledSharedElement: Boolean,
    currentContent: ContentKey,
    layoutImpl: SceneTransitionLayoutImpl,
    transition: TransitionState.Transition,
    element: Element,
    currentSceneState: Element.State?,
): ContentKey {
    return when {
        isDisabledSharedElement -> {
            currentContent
        }
        isAncestorTransition(layoutImpl, transition) -> {
            if (
                element.stateByContent[transition.fromContent] != null &&
                    transition.transformationSpec.hasTransformation(
                        element.key,
                        transition.fromContent,
                    )
            ) {
                transition.fromContent
            } else if (
                element.stateByContent[transition.toContent] != null &&
                    transition.transformationSpec.hasTransformation(
                        element.key,
                        transition.toContent,
                    )
            ) {
                transition.toContent
            } else {
                throw IllegalStateException(
                    "Ancestor transition is active but no transformation " +
                        "spec was found. The ancestor transition should have only been selected " +
                        "when a transformation for that element and content was defined."
                )
            }
        }
        currentSceneState != null && currentContent == transition.currentScene -> {
            currentContent
        }
        element.stateByContent[transition.fromContent] != null -> {
            transition.fromContent
        }
        else -> {
            transition.toContent
        }
    }
}

private inline fun <T> computePreviewTransformationValue(
    transition: TransitionState.Transition,
    idleValue: T,
    transformationContentKey: ContentKey,
    isEntering: Boolean,
    previewTransformation: TransformationWithRange<PropertyTransformation<T>>,
    element: Element,
    layoutImpl: SceneTransitionLayoutImpl,
    transformationWithRange: TransformationWithRange<PropertyTransformation<T>>?,
    lerp: (T, T, Float) -> T,
): T {
    val isInPreviewStage = transition.isInPreviewStage

    val previewTargetValue =
        with(
            previewTransformation.transformation.requireInterpolatedTransformation(
                element,
                transition,
            ) {
                "Custom transformations in preview specs should not be possible"
            }
        ) {
            layoutImpl.propertyTransformationScope.transform(
                transformationContentKey,
                element.key,
                transition,
                idleValue,
            )
        }

    val targetValueOrNull =
        transformationWithRange?.let { transformation ->
            with(
                transformation.transformation.requireInterpolatedTransformation(
                    element,
                    transition,
                ) {
                    "Custom transformations are not allowed for properties with a preview"
                }
            ) {
                layoutImpl.propertyTransformationScope.transform(
                    transformationContentKey,
                    element.key,
                    transition,
                    idleValue,
                )
            }
        }

    // Make sure we don't read progress if values are the same and we don't need to interpolate,
    // so we don't invalidate the phase where this is read.
    when {
        isInPreviewStage && isEntering && previewTargetValue == targetValueOrNull ->
            return previewTargetValue
        isInPreviewStage && !isEntering && idleValue == previewTargetValue -> return idleValue
        previewTargetValue == targetValueOrNull && idleValue == previewTargetValue ->
            return idleValue
        else -> {}
    }

    val previewProgress = transition.previewProgress
    // progress is not needed for all cases of the below when block, therefore read it lazily
    // TODO(b/290184746): Make sure that we don't overflow transformations associated to a range
    val previewRangeProgress =
        previewTransformation.range?.progress(previewProgress) ?: previewProgress

    if (isInPreviewStage) {
        // if we're in the preview stage of the transition, interpolate between start state and
        // preview target state:
        return if (isEntering) {
            // i.e. in the entering case between previewTargetValue and targetValue (or
            // idleValue if no transformation is defined in the second stage transition)...
            lerp(previewTargetValue, targetValueOrNull ?: idleValue, previewRangeProgress)
        } else {
            // ...and in the exiting case between the idleValue and the previewTargetValue.
            lerp(idleValue, previewTargetValue, previewRangeProgress)
        }
    }

    // if we're in the second stage of the transition, interpolate between the state the
    // element was left at the end of the preview-phase and the target state:
    return if (isEntering) {
        // i.e. in the entering case between preview-end-state and the idleValue...
        lerp(
            lerp(previewTargetValue, targetValueOrNull ?: idleValue, previewRangeProgress),
            idleValue,
            transformationWithRange?.range?.progress(transition.progress) ?: transition.progress,
        )
    } else {
        if (targetValueOrNull == null) {
            // ... and in the exiting case, the element should remain in the preview-end-state
            // if no further transformation is defined in the second-stage transition...
            lerp(idleValue, previewTargetValue, previewRangeProgress)
        } else {
            // ...and otherwise it should be interpolated between preview-end-state and
            // targetValue
            lerp(
                lerp(idleValue, previewTargetValue, previewRangeProgress),
                targetValueOrNull,
                transformationWithRange.range?.progress(transition.progress) ?: transition.progress,
            )
        }
    }
}

private fun isAncestorTransition(
    layoutImpl: SceneTransitionLayoutImpl,
    transition: TransitionState.Transition,
): Boolean {
    return layoutImpl.ancestors.fastAny {
        it.inContent == transition.fromContent || it.inContent == transition.toContent
    }
}

private fun isEnteringAncestorTransition(
    layoutImpl: SceneTransitionLayoutImpl,
    transition: TransitionState.Transition,
): Boolean {
    return layoutImpl.ancestors.fastAny { it.inContent == transition.toContent }
}

private inline fun <T> PropertyTransformation<T>.requireInterpolatedTransformation(
    element: Element,
    transition: TransitionState.Transition,
    errorMessage: () -> String,
): InterpolatedPropertyTransformation<T> {
    return when (this) {
        is InterpolatedPropertyTransformation -> this
        is CustomPropertyTransformation -> {
            val elem = element.key.debugName
            val fromContent = transition.fromContent
            val toContent = transition.toContent
            error("${errorMessage()} (element=$elem fromContent=$fromContent toContent=$toContent)")
        }
    }
}

private inline fun <T> interpolateSharedElement(
    transition: TransitionState.Transition,
    contentValue: (Element.State) -> T,
    fromState: Element.State,
    toState: Element.State,
    isSpecified: (T) -> Boolean,
    lerp: (T, T, Float) -> T,
): T {
    val start = contentValue(fromState)
    val end = contentValue(toState)

    // TODO(b/316901148): Remove checks to isSpecified() once the lookahead pass runs for all
    // nodes before the intermediate layout pass.
    if (!isSpecified(start)) return end
    if (!isSpecified(end)) return start

    // Make sure we don't read progress if values are the same and we don't need to interpolate,
    // so we don't invalidate the phase where this is read.
    return if (start == end) start else lerp(start, end, transition.progress)
}
