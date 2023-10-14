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
import com.android.compose.animation.scene.Element
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.ElementMatcher
import com.android.compose.animation.scene.Scene
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayoutImpl
import com.android.compose.animation.scene.TransitionState

/** Anchor the translation of an element to another element. */
internal class AnchoredTranslate(
    override val matcher: ElementMatcher,
    private val anchor: ElementKey,
) : PropertyTransformation<Offset> {
    override fun transform(
        layoutImpl: SceneTransitionLayoutImpl,
        scene: Scene,
        element: Element,
        sceneValues: Element.TargetValues,
        transition: TransitionState.Transition,
        value: Offset,
    ): Offset {
        val anchor = layoutImpl.elements[anchor] ?: return value
        fun anchorOffsetIn(scene: SceneKey): Offset? {
            return anchor.sceneValues[scene]?.targetOffset?.takeIf { it.isSpecified }
        }

        // [element] will move the same amount as [anchor] does.
        // TODO(b/290184746): Also support anchors that are not shared but translated because of
        // other transformations, like an edge translation.
        val anchorFromOffset = anchorOffsetIn(transition.fromScene) ?: return value
        val anchorToOffset = anchorOffsetIn(transition.toScene) ?: return value
        val offset = anchorToOffset - anchorFromOffset

        return if (scene.key == transition.toScene) {
            Offset(
                value.x - offset.x,
                value.y - offset.y,
            )
        } else {
            Offset(
                value.x + offset.x,
                value.y + offset.y,
            )
        }
    }
}
