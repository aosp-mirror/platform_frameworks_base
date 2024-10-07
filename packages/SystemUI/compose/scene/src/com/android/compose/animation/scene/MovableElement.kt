/*
 * Copyright (C) 2023 The Android Open Source Project
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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.IntSize
import com.android.compose.animation.scene.content.Content
import com.android.compose.animation.scene.content.state.TransitionState

@Composable
internal fun Element(
    layoutImpl: SceneTransitionLayoutImpl,
    sceneOrOverlay: Content,
    key: ElementKey,
    modifier: Modifier,
    content: @Composable ElementScope<ElementContentScope>.() -> Unit,
) {
    Box(modifier.element(layoutImpl, sceneOrOverlay, key), propagateMinConstraints = true) {
        val contentScope = sceneOrOverlay.scope
        val boxScope = this
        val elementScope =
            remember(layoutImpl, key, sceneOrOverlay, contentScope, boxScope) {
                ElementScopeImpl(layoutImpl, key, sceneOrOverlay, contentScope, boxScope)
            }

        content(elementScope)
    }
}

@Composable
internal fun MovableElement(
    layoutImpl: SceneTransitionLayoutImpl,
    sceneOrOverlay: Content,
    key: MovableElementKey,
    modifier: Modifier,
    content: @Composable ElementScope<MovableElementContentScope>.() -> Unit,
) {
    check(key.contentPicker.contents.contains(sceneOrOverlay.key)) {
        val elementName = key.debugName
        val contentName = sceneOrOverlay.key.debugName
        "MovableElement $elementName was composed in content $contentName but the " +
            "MovableElementKey($elementName).contentPicker.contents does not contain $contentName"
    }

    Box(modifier.element(layoutImpl, sceneOrOverlay, key), propagateMinConstraints = true) {
        val contentScope = sceneOrOverlay.scope
        val boxScope = this
        val elementScope =
            remember(layoutImpl, key, sceneOrOverlay, contentScope, boxScope) {
                MovableElementScopeImpl(layoutImpl, key, sceneOrOverlay, contentScope, boxScope)
            }

        content(elementScope)
    }
}

private abstract class BaseElementScope<ContentScope>(
    private val layoutImpl: SceneTransitionLayoutImpl,
    private val element: ElementKey,
    private val sceneOrOverlay: Content,
) : ElementScope<ContentScope> {
    @Composable
    override fun <T> animateElementValueAsState(
        value: T,
        key: ValueKey,
        type: SharedValueType<T, *>,
        canOverflow: Boolean,
    ): AnimatedState<T> {
        return animateSharedValueAsState(
            layoutImpl,
            sceneOrOverlay.key,
            element,
            key,
            value,
            type,
            canOverflow,
        )
    }
}

private class ElementScopeImpl(
    layoutImpl: SceneTransitionLayoutImpl,
    element: ElementKey,
    content: Content,
    private val delegateContentScope: ContentScope,
    private val boxScope: BoxScope,
) : BaseElementScope<ElementContentScope>(layoutImpl, element, content) {
    private val contentScope =
        object : ElementContentScope, ContentScope by delegateContentScope, BoxScope by boxScope {}

    @Composable
    override fun content(content: @Composable ElementContentScope.() -> Unit) {
        contentScope.content()
    }
}

private class MovableElementScopeImpl(
    private val layoutImpl: SceneTransitionLayoutImpl,
    private val element: MovableElementKey,
    private val content: Content,
    private val baseContentScope: BaseContentScope,
    private val boxScope: BoxScope,
) : BaseElementScope<MovableElementContentScope>(layoutImpl, element, content) {
    private val contentScope =
        object :
            MovableElementContentScope,
            BaseContentScope by baseContentScope,
            BoxScope by boxScope {}

    @Composable
    override fun content(content: @Composable MovableElementContentScope.() -> Unit) {
        // Whether we should compose the movable element here. The scene picker logic to know in
        // which scene we should compose/draw a movable element might depend on the current
        // transition progress, so we put this in a derivedStateOf to prevent many recompositions
        // during the transition.
        // TODO(b/317026105): Use derivedStateOf only if the scene picker reads the progress in its
        // logic.
        val contentKey = this@MovableElementScopeImpl.content.key
        val shouldComposeMovableElement by
            remember(layoutImpl, contentKey, element) {
                derivedStateOf { shouldComposeMovableElement(layoutImpl, contentKey, element) }
            }

        if (shouldComposeMovableElement) {
            val movableContent: MovableElementContent =
                layoutImpl.movableContents[element]
                    ?: movableContentOf { content: @Composable () -> Unit -> content() }
                        .also { layoutImpl.movableContents[element] = it }

            // Important: Don't introduce any parent Box or other layout here, because contentScope
            // delegates its BoxScope implementation to the Box where this content() function is
            // called, so it's important that this movableContent is composed directly under that
            // Box.
            movableContent { contentScope.content() }
        } else {
            // If we are not composed, we still need to lay out an empty space with the same *target
            // size* as its movable content, i.e. the same *size when idle*. During transitions,
            // this size will be used to interpolate the transition size, during the intermediate
            // layout pass.
            //
            // Important: Like in Modifier.element(), we read the transition states during
            // composition then pass them to Layout to make sure that composition sees new states
            // before layout and drawing.
            val transitionStates = layoutImpl.state.transitionStates
            Layout { _, _ ->
                // No need to measure or place anything.
                val size =
                    placeholderContentSize(
                        layoutImpl = layoutImpl,
                        content = contentKey,
                        element = layoutImpl.elements.getValue(element),
                        elementKey = element,
                        transitionStates = transitionStates,
                    )
                layout(size.width, size.height) {}
            }
        }
    }
}

private fun shouldComposeMovableElement(
    layoutImpl: SceneTransitionLayoutImpl,
    content: ContentKey,
    element: MovableElementKey,
): Boolean {
    return when (
        val elementState = movableElementState(element, layoutImpl.state.transitionStates)
    ) {
        null -> false
        is TransitionState.Idle ->
            movableElementContentWhenIdle(layoutImpl, element, elementState) == content
        is TransitionState.Transition -> {
            // During transitions, always compose movable elements in the scene picked by their
            // content picker.
            val contents = element.contentPicker.contents
            shouldPlaceOrComposeSharedElement(
                layoutImpl,
                content,
                element,
                elementState,
                isInContent = { contents.contains(it) },
            )
        }
    }
}

private fun movableElementState(
    element: MovableElementKey,
    transitionStates: List<TransitionState>,
): TransitionState? {
    val contents = element.contentPicker.contents
    return elementState(transitionStates, isInContent = { contents.contains(it) })
}

private fun movableElementContentWhenIdle(
    layoutImpl: SceneTransitionLayoutImpl,
    element: MovableElementKey,
    elementState: TransitionState.Idle,
): ContentKey {
    val contents = element.contentPicker.contents
    return elementContentWhenIdle(layoutImpl, elementState, isInContent = { contents.contains(it) })
}

/**
 * Return the size of the placeholder/space that is composed when the movable content is not
 * composed in a scene.
 */
private fun placeholderContentSize(
    layoutImpl: SceneTransitionLayoutImpl,
    content: ContentKey,
    element: Element,
    elementKey: MovableElementKey,
    transitionStates: List<TransitionState>,
): IntSize {
    // If the content of the movable element was already composed in this scene before, use that
    // target size.
    val targetValueInScene = element.stateByContent.getValue(content).targetSize
    if (targetValueInScene != Element.SizeUnspecified) {
        return targetValueInScene
    }

    // If the element content was already composed in the other overlay/scene, we use that
    // target size assuming it doesn't change between scenes.
    // TODO(b/317026105): Provide a way to give a hint size/content for cases where this is
    // not true.
    val otherContent =
        when (val state = movableElementState(elementKey, transitionStates)) {
            null -> return IntSize.Zero
            is TransitionState.Idle -> movableElementContentWhenIdle(layoutImpl, elementKey, state)
            is TransitionState.Transition ->
                if (state.fromContent == content) state.toContent else state.fromContent
        }

    val targetValueInOtherContent = element.stateByContent[otherContent]?.targetSize
    if (targetValueInOtherContent != null && targetValueInOtherContent != Element.SizeUnspecified) {
        return targetValueInOtherContent
    }

    return IntSize.Zero
}
