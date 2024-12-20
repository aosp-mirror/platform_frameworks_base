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
import androidx.compose.ui.unit.Dp
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.content.state.TransitionState

internal class Translate private constructor(private val x: Dp, private val y: Dp) :
    InterpolatedPropertyTransformation<Offset> {
    override val property = PropertyTransformation.Property.Offset

    override fun PropertyTransformationScope.transform(
        content: ContentKey,
        element: ElementKey,
        transition: TransitionState.Transition,
        idleValue: Offset,
    ): Offset {
        return Offset(idleValue.x + x.toPx(), idleValue.y + y.toPx())
    }

    class Factory(private val x: Dp, private val y: Dp) : Transformation.Factory {
        override fun create(): Transformation = Translate(x, y)
    }
}
