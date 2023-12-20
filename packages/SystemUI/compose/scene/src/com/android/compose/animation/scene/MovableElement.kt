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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.IntSize

@Composable
internal fun Element(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    key: ElementKey,
    modifier: Modifier,
    content: @Composable ElementScope<ElementContentScope>.() -> Unit,
) {
    ElementBase(
        layoutImpl,
        scene,
        key,
        modifier,
        content,
        scene.scope,
        isMovable = false,
    )
}

@Composable
internal fun MovableElement(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    key: ElementKey,
    modifier: Modifier,
    content: @Composable ElementScope<MovableElementContentScope>.() -> Unit,
) {
    ElementBase(
        layoutImpl,
        scene,
        key,
        modifier,
        content,
        scene.scope,
        isMovable = true,
    )
}

@Composable
private inline fun <ContentScope> ElementBase(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    key: ElementKey,
    modifier: Modifier,
    content: @Composable ElementScope<ContentScope>.() -> Unit,
    contentScope: ContentScope,
    isMovable: Boolean,
) {
    Box(modifier.element(layoutImpl, scene, key)) {
        // Get the Element from the map. It will always be the same and we don't want to recompose
        // every time an element is added/removed from SceneTransitionLayoutImpl.elements, so we
        // disable read observation during the look-up in that map.
        val element = Snapshot.withoutReadObservation { layoutImpl.elements.getValue(key) }
        val elementScope =
            remember(layoutImpl, element, scene, contentScope, isMovable) {
                ElementScopeImpl(layoutImpl, element, scene, contentScope, isMovable)
            }

        elementScope.content()
    }
}

private class ElementScopeImpl<ContentScope>(
    private val layoutImpl: SceneTransitionLayoutImpl,
    private val element: Element,
    private val scene: Scene,
    private val contentScope: ContentScope,
    private val isMovable: Boolean,
) : ElementScope<ContentScope> {
    @Composable
    override fun <T> animateElementValueAsState(
        value: T,
        key: ValueKey,
        lerp: (start: T, stop: T, fraction: Float) -> T,
        canOverflow: Boolean
    ): State<T> {
        return animateSharedValueAsState(layoutImpl, scene, element, key, value, lerp, canOverflow)
    }

    @Composable
    override fun content(content: @Composable ContentScope.() -> Unit) {
        if (!isMovable) {
            contentScope.content()
            return
        }

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
            element.movableContent { contentScope.content() }
        } else {
            // If we are not composed, we still need to lay out an empty space with the same *target
            // size* as its movable content, i.e. the same *size when idle*. During transitions,
            // this size will be used to interpolate the transition size, during the intermediate
            // layout pass.
            Layout { _, _ ->
                // No need to measure or place anything.
                val size = placeholderContentSize(layoutImpl, scene.key, element)
                layout(size.width, size.height) {}
            }
        }
    }
}

private fun shouldComposeMovableElement(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: SceneKey,
    element: Element,
): Boolean {
    val transition =
        layoutImpl.state.currentTransition
        // If we are idle, there is only one [scene] that is composed so we can compose our
        // movable content here.
        ?: return true
    val fromScene = transition.fromScene
    val toScene = transition.toScene

    val fromReady = layoutImpl.isSceneReady(fromScene)
    val toReady = layoutImpl.isSceneReady(toScene)

    if (!fromReady && !toReady) {
        // Neither of the scenes will be drawn, so where we compose it doesn't really matter. Note
        // that we could have slightly more complicated logic here to optimize for this case, but
        // it's not worth it given that readyScenes should disappear soon (b/316901148).
        return scene == toScene
    }

    // If one of the scenes is not ready, compose it in the other one to make sure it is drawn.
    if (!fromReady) return scene == toScene
    if (!toReady) return scene == fromScene

    // Always compose movable elements in the scene picked by their scene picker.
    return shouldDrawOrComposeSharedElement(
        layoutImpl,
        transition,
        scene,
        element.key,
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
    val targetValueInScene = element.sceneValues.getValue(scene).targetSize
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
    val targetValueInOtherScene = element.sceneValues[otherScene]?.targetSize
    if (targetValueInOtherScene != null && targetValueInOtherScene != Element.SizeUnspecified) {
        return targetValueInOtherScene
    }

    return IntSize.Zero
}
