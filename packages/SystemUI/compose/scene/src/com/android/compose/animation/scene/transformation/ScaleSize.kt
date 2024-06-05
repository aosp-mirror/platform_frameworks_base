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
import com.android.compose.animation.scene.ElementMatcher
import com.android.compose.animation.scene.Scene
import com.android.compose.animation.scene.SceneTransitionLayoutImpl
import com.android.compose.animation.scene.TransitionState
import kotlin.math.roundToInt

/**
 * Scales the size of an element. Note that this makes the element resize every frame and will
 * therefore impact the layout of other elements.
 */
internal class ScaleSize(
    override val matcher: ElementMatcher,
    private val width: Float = 1f,
    private val height: Float = 1f,
) : PropertyTransformation<IntSize> {
    override fun transform(
        layoutImpl: SceneTransitionLayoutImpl,
        scene: Scene,
        element: Element,
        sceneState: Element.SceneState,
        transition: TransitionState.Transition,
        value: IntSize,
    ): IntSize {
        return IntSize(
            width = (value.width * width).roundToInt(),
            height = (value.height * height).roundToInt(),
        )
    }
}
