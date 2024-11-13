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

package com.android.compose.animation.scene.transformation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.Element
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.ElementMatcher
import com.android.compose.animation.scene.SceneTransitionLayoutImpl
import com.android.compose.animation.scene.content.state.TransitionState

/** Anchor the translation of an element to another element. */
internal class AnchoredTranslate(
    override val matcher: ElementMatcher,
    private val anchor: ElementKey,
) : PropertyTransformation<Offset> {
    override fun transform(
        layoutImpl: SceneTransitionLayoutImpl,
        content: ContentKey,
        element: Element,
        stateInContent: Element.State,
        transition: TransitionState.Transition,
        value: Offset,
    ): Offset {
        fun throwException(content: ContentKey?): Nothing {
            throwMissingAnchorException(
                transformation = "AnchoredTranslate",
                anchor = anchor,
                content = content,
            )
        }

        val anchor = layoutImpl.elements[anchor] ?: throwException(content = null)
        fun anchorOffsetIn(content: ContentKey): Offset? {
            return anchor.stateByContent[content]?.targetOffset?.takeIf { it.isSpecified }
        }

        // [element] will move the same amount as [anchor] does.
        // TODO(b/290184746): Also support anchors that are not shared but translated because of
        // other transformations, like an edge translation.
        val anchorFromOffset =
            anchorOffsetIn(transition.fromContent) ?: throwException(transition.fromContent)
        val anchorToOffset =
            anchorOffsetIn(transition.toContent) ?: throwException(transition.toContent)
        val offset = anchorToOffset - anchorFromOffset

        return if (content == transition.toContent) {
            Offset(value.x - offset.x, value.y - offset.y)
        } else {
            Offset(value.x + offset.x, value.y + offset.y)
        }
    }
}

internal fun throwMissingAnchorException(
    transformation: String,
    anchor: ElementKey,
    content: ContentKey?,
): Nothing {
    error(
        """
        Anchor ${anchor.debugName} does not have a target state in content ${content?.debugName}.
        This either means that it was not composed at all during the transition or that it was
        composed too late, for instance during layout/subcomposition. To avoid flickers in
        $transformation, you should make sure that the composition and layout of anchor is *not*
        deferred, for instance by moving it out of lazy layouts.
    """
            .trimIndent()
    )
}
