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
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.content.state.TransitionState

/** Anchor the translation of an element to another element. */
internal class AnchoredTranslate private constructor(private val anchor: ElementKey) :
    InterpolatedPropertyTransformation<Offset> {
    override val property = PropertyTransformation.Property.Offset

    override fun PropertyTransformationScope.transform(
        content: ContentKey,
        element: ElementKey,
        transition: TransitionState.Transition,
        idleValue: Offset,
    ): Offset {
        fun throwException(content: ContentKey?): Nothing {
            throwMissingAnchorException(
                transformation = "AnchoredTranslate",
                anchor = anchor,
                content = content,
            )
        }

        // [element] will move the same amount as [anchor] does.
        // TODO(b/290184746): Also support anchors that are not shared but translated because of
        // other transformations, like an edge translation.
        val anchorFromOffset =
            anchor.targetOffset(transition.fromContent) ?: throwException(transition.fromContent)
        val anchorToOffset =
            anchor.targetOffset(transition.toContent) ?: throwException(transition.toContent)
        val offset = anchorToOffset - anchorFromOffset

        return if (content == transition.toContent) {
            Offset(idleValue.x - offset.x, idleValue.y - offset.y)
        } else {
            Offset(idleValue.x + offset.x, idleValue.y + offset.y)
        }
    }

    class Factory(private val anchor: ElementKey) : Transformation.Factory {
        override fun create(): Transformation = AnchoredTranslate(anchor)
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
