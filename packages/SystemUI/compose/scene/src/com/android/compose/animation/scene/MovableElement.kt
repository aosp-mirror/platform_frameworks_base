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
import androidx.compose.ui.util.fastLastOrNull

@Composable
internal fun Element(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    key: ElementKey,
    modifier: Modifier,
    content: @Composable ElementScope<ElementContentScope>.() -> Unit,
) {
    Box(modifier.element(layoutImpl, scene, key)) {
        val sceneScope = scene.scope
        val boxScope = this
        val elementScope =
            remember(layoutImpl, key, scene, sceneScope, boxScope) {
                ElementScopeImpl(layoutImpl, key, scene, sceneScope, boxScope)
            }

        content(elementScope)
    }
}

@Composable
internal fun MovableElement(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    key: ElementKey,
    modifier: Modifier,
    content: @Composable ElementScope<MovableElementContentScope>.() -> Unit,
) {
    Box(modifier.element(layoutImpl, scene, key)) {
        val sceneScope = scene.scope
        val boxScope = this
        val elementScope =
            remember(layoutImpl, key, scene, sceneScope, boxScope) {
                MovableElementScopeImpl(layoutImpl, key, scene, sceneScope, boxScope)
            }

        content(elementScope)
    }
}

private abstract class BaseElementScope<ContentScope>(
    private val layoutImpl: SceneTransitionLayoutImpl,
    private val element: ElementKey,
    private val scene: Scene,
) : ElementScope<ContentScope> {
    @Composable
    override fun <T> animateElementValueAsState(
        value: T,
        key: ValueKey,
        lerp: (start: T, stop: T, fraction: Float) -> T,
        canOverflow: Boolean
    ): AnimatedState<T> {
        return animateSharedValueAsState(
            layoutImpl,
            scene.key,
            element,
            key,
            value,
            lerp,
            canOverflow,
        )
    }
}

private class ElementScopeImpl(
    layoutImpl: SceneTransitionLayoutImpl,
    element: ElementKey,
    scene: Scene,
    private val sceneScope: SceneScope,
    private val boxScope: BoxScope,
) : BaseElementScope<ElementContentScope>(layoutImpl, element, scene) {
    private val contentScope =
        object : ElementContentScope, SceneScope by sceneScope, BoxScope by boxScope {}

    @Composable
    override fun content(content: @Composable ElementContentScope.() -> Unit) {
        contentScope.content()
    }
}

private class MovableElementScopeImpl(
    private val layoutImpl: SceneTransitionLayoutImpl,
    private val element: ElementKey,
    private val scene: Scene,
    private val sceneScope: BaseSceneScope,
    private val boxScope: BoxScope,
) : BaseElementScope<MovableElementContentScope>(layoutImpl, element, scene) {
    private val contentScope =
        object : MovableElementContentScope, BaseSceneScope by sceneScope, BoxScope by boxScope {}

    @Composable
    override fun content(content: @Composable MovableElementContentScope.() -> Unit) {
        // Whether we should compose the movable element here. The scene picker logic to know in
        // which scene we should compose/draw a movable element might depend on the current
        // transition progress, so we put this in a derivedStateOf to prevent many recompositions
        // during the transition.
        // TODO(b/317026105): Use derivedStateOf only if the scene picker reads the progress in its
        // logic.
        val shouldComposeMovableElement by
            remember(layoutImpl, scene.key, element) {
                derivedStateOf { shouldComposeMovableElement(layoutImpl, scene.key, element) }
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
            Layout { _, _ ->
                // No need to measure or place anything.
                val size =
                    placeholderContentSize(
                        layoutImpl,
                        scene.key,
                        layoutImpl.elements.getValue(element),
                    )
                layout(size.width, size.height) {}
            }
        }
    }
}

private fun shouldComposeMovableElement(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: SceneKey,
    element: ElementKey,
): Boolean {
    val transitions = layoutImpl.state.currentTransitions
    if (transitions.isEmpty()) {
        // If we are idle, there is only one [scene] that is composed so we can compose our
        // movable content here. We still check that [scene] is equal to the current idle scene, to
        // make sure we only compose it there.
        return layoutImpl.state.transitionState.currentScene == scene
    }

    // The current transition for this element is the last transition in which either fromScene or
    // toScene contains the element.
    val transition =
        transitions.fastLastOrNull { transition ->
            element.scenePicker.sceneDuringTransition(
                element = element,
                transition = transition,
                fromSceneZIndex = layoutImpl.scenes.getValue(transition.fromScene).zIndex,
                toSceneZIndex = layoutImpl.scenes.getValue(transition.toScene).zIndex,
            ) != null
        }
            ?: return false

    // Always compose movable elements in the scene picked by their scene picker.
    return shouldDrawOrComposeSharedElement(
        layoutImpl,
        scene,
        element,
        transition,
    )
}

/**
 * Return the size of the placeholder/space that is composed when the movable content is not
 * composed in a scene.
 */
private fun placeholderContentSize(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: SceneKey,
    element: Element,
): IntSize {
    // If the content of the movable element was already composed in this scene before, use that
    // target size.
    val targetValueInScene = element.sceneStates.getValue(scene).targetSize
    if (targetValueInScene != Element.SizeUnspecified) {
        return targetValueInScene
    }

    // This code is only run during transitions (otherwise the content would be composed and the
    // placeholder would not), so it's ok to cast the state into a Transition directly.
    val transition = layoutImpl.state.transitionState as TransitionState.Transition

    // If the content was already composed in the other scene, we use that target size assuming it
    // doesn't change between scenes.
    // TODO(b/317026105): Provide a way to give a hint size/content for cases where this is not
    // true.
    val otherScene = if (transition.fromScene == scene) transition.toScene else transition.fromScene
    val targetValueInOtherScene = element.sceneStates[otherScene]?.targetSize
    if (targetValueInOtherScene != null && targetValueInOtherScene != Element.SizeUnspecified) {
        return targetValueInOtherScene
    }

    return IntSize.Zero
}
