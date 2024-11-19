/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.transformation.SharedElementTransformation
import com.android.compose.animation.scene.transformation.TransformationWithRange

/**
 * Whether this element should be rendered by the given [content]. This method returns true only for
 * exactly one content at any given time.
 */
internal fun Element.shouldBeRenderedBy(content: ContentKey): Boolean {
    // The current strategy is that always the content with the lowest nestingDepth has authority.
    // This content is supposed to render the shared element because this is also the level at which
    // the transition is running. If the [renderAuthority.size] is 1 it means that that this element
    // is currently composed only in one nesting level, which means that the render authority
    // is determined by "classic" shared element code.
    return renderAuthority.size == 1 || renderAuthority.first() == content
}

/**
 * Whether this element is currently composed in multiple [SceneTransitionLayout]s.
 *
 * Note: Shared elements across [NestedSceneTransitionLayout]s side-by-side are not supported.
 */
internal fun Element.isPresentInMultipleStls(): Boolean {
    return renderAuthority.size > 1
}

internal fun shouldPlaceSharedElement(
    layoutImpl: SceneTransitionLayoutImpl,
    content: ContentKey,
    elementKey: ElementKey,
    transition: TransitionState.Transition,
): Boolean {
    val element = layoutImpl.elements.getValue(elementKey)
    if (element.isPresentInMultipleStls()) {
        // If the element is present in multiple STLs we require the highest STL to render it and
        // we don't want contentPicker to potentially return false for the highest STL.
        return element.shouldBeRenderedBy(content)
    }

    val overscrollContent = transition.currentOverscrollSpec?.content
    if (overscrollContent != null) {
        return when (transition) {
            // If we are overscrolling between scenes, only place/compose the element in the
            // overscrolling scene.
            is TransitionState.Transition.ChangeScene -> content == overscrollContent

            // If we are overscrolling an overlay, place/compose the element if [content] is the
            // overscrolling content or if [content] is the current scene and the overscrolling
            // overlay does not contain the element.
            is TransitionState.Transition.ReplaceOverlay,
            is TransitionState.Transition.ShowOrHideOverlay ->
                content == overscrollContent ||
                    (content == transition.currentScene &&
                        overscrollContent !in element.stateByContent)
        }
    }

    val scenePicker = elementKey.contentPicker
    val pickedScene =
        scenePicker.contentDuringTransition(
            element = elementKey,
            transition = transition,
            fromContentZIndex = layoutImpl.content(transition.fromContent).zIndex,
            toContentZIndex = layoutImpl.content(transition.toContent).zIndex,
        )

    return pickedScene == content
}

internal fun isSharedElementEnabled(
    element: ElementKey,
    transition: TransitionState.Transition,
): Boolean {
    return sharedElementTransformation(element, transition)?.transformation?.enabled ?: true
}

internal fun sharedElementTransformation(
    element: ElementKey,
    transition: TransitionState.Transition,
): TransformationWithRange<SharedElementTransformation>? {
    val transformationSpec = transition.transformationSpec
    val sharedInFromContent =
        transformationSpec.transformations(element, transition.fromContent).shared
    val sharedInToContent = transformationSpec.transformations(element, transition.toContent).shared

    // The sharedElement() transformation must either be null or be the same in both contents.
    if (sharedInFromContent != sharedInToContent) {
        error(
            "Different sharedElement() transformations matched $element " +
                "(from=$sharedInFromContent to=$sharedInToContent)"
        )
    }

    return sharedInFromContent
}
