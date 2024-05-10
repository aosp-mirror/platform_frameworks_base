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

import androidx.compose.ui.unit.IntSize
import com.android.compose.animation.scene.Element
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.ElementMatcher
import com.android.compose.animation.scene.Scene
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayoutImpl
import com.android.compose.animation.scene.TransitionState

/** Anchor the size of an element to the size of another element. */
internal class AnchoredSize(
    override val matcher: ElementMatcher,
    private val anchor: ElementKey,
    private val anchorWidth: Boolean,
    private val anchorHeight: Boolean,
) : PropertyTransformation<IntSize> {
    override fun transform(
        layoutImpl: SceneTransitionLayoutImpl,
        scene: Scene,
        element: Element,
        sceneValues: Element.TargetValues,
        transition: TransitionState.Transition,
        value: IntSize,
    ): IntSize {
        fun anchorSizeIn(scene: SceneKey): IntSize {
            val size = layoutImpl.elements[anchor]?.sceneValues?.get(scene)?.targetSize
            return if (size != null && size != Element.SizeUnspecified) {
                IntSize(
                    width = if (anchorWidth) size.width else value.width,
                    height = if (anchorHeight) size.height else value.height,
                )
            } else {
                value
            }
        }

        // This simple implementation assumes that the size of [element] is the same as the size of
        // the [anchor] in [scene], so simply transform to the size of the anchor in the other
        // scene.
        return if (scene.key == transition.fromScene) {
            anchorSizeIn(transition.toScene)
        } else {
            anchorSizeIn(transition.fromScene)
        }
    }
}
