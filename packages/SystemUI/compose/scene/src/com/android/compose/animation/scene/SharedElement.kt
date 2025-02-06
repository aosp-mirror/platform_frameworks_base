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

internal fun shouldPlaceSharedElement(
    layoutImpl: SceneTransitionLayoutImpl,
    content: ContentKey,
    elementKey: ElementKey,
    transition: TransitionState.Transition,
): Boolean {
    val contentPicker = elementKey.contentPicker
    val pickedContent =
        contentPicker.contentDuringTransition(
            element = elementKey,
            transition = transition,
            fromContentZIndex = layoutImpl.content(transition.fromContent).globalZIndex,
            toContentZIndex = layoutImpl.content(transition.toContent).globalZIndex,
        )

    return pickedContent == content || layoutImpl.isAncestorContent(pickedContent)
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
        transformationSpec.transformations(element, transition.fromContent)?.shared
    val sharedInToContent =
        transformationSpec.transformations(element, transition.toContent)?.shared

    // The sharedElement() transformation must either be null or be the same in both contents.
    if (sharedInFromContent != sharedInToContent) {
        error(
            "Different sharedElement() transformations matched $element " +
                "(from=$sharedInFromContent to=$sharedInToContent)"
        )
    }

    return sharedInFromContent
}
