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
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.content.state.TransitionState
import kotlin.math.roundToInt

/**
 * Scales the size of an element. Note that this makes the element resize every frame and will
 * therefore impact the layout of other elements.
 */
internal class ScaleSize private constructor(private val width: Float, private val height: Float) :
    InterpolatedPropertyTransformation<IntSize> {
    override val property = PropertyTransformation.Property.Size

    override fun PropertyTransformationScope.transform(
        content: ContentKey,
        element: ElementKey,
        transition: TransitionState.Transition,
        idleValue: IntSize,
    ): IntSize {
        return IntSize(
            width = (idleValue.width * width).roundToInt(),
            height = (idleValue.height * height).roundToInt(),
        )
    }

    class Factory(private val width: Float = 1f, private val height: Float = 1f) :
        Transformation.Factory {
        override fun create(): Transformation = ScaleSize(width, height)
    }
}
