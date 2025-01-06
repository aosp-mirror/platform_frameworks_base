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
import com.android.compose.animation.scene.Scale
import com.android.compose.animation.scene.content.state.TransitionState

/**
 * Scales the draw size of an element. Note this will only scale the draw inside of an element,
 * therefore it won't impact layout of elements around it.
 */
internal class DrawScale
private constructor(
    private val scaleX: Float,
    private val scaleY: Float,
    private val pivot: Offset,
) : InterpolatedPropertyTransformation<Scale> {
    override val property = PropertyTransformation.Property.Scale

    override fun PropertyTransformationScope.transform(
        content: ContentKey,
        element: ElementKey,
        transition: TransitionState.Transition,
        idleValue: Scale,
    ): Scale {
        return Scale(scaleX, scaleY, pivot)
    }

    class Factory(private val scaleX: Float, private val scaleY: Float, private val pivot: Offset) :
        Transformation.Factory {
        override fun create(): Transformation = DrawScale(scaleX, scaleY, pivot)
    }
}
