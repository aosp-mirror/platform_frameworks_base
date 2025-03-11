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

import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.content.state.TransitionState

/** Fade an element in or out. */
internal object Fade : InterpolatedPropertyTransformation<Float> {
    override val property = PropertyTransformation.Property.Alpha

    override fun PropertyTransformationScope.transform(
        content: ContentKey,
        element: ElementKey,
        transition: TransitionState.Transition,
        idleValue: Float,
    ): Float {
        // Return the alpha value of [element] either when it starts fading in or when it finished
        // fading out, which is `0` in both cases.
        return 0f
    }

    object Factory : Transformation.Factory {
        override fun create(): Transformation = Fade
    }
}
