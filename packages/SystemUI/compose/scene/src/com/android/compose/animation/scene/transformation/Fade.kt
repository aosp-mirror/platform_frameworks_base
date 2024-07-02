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

import com.android.compose.animation.scene.Element
import com.android.compose.animation.scene.ElementMatcher
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayoutImpl
import com.android.compose.animation.scene.TransitionState

/** Fade an element in or out. */
internal class Fade(
    override val matcher: ElementMatcher,
) : PropertyTransformation<Float> {
    override fun transform(
        layoutImpl: SceneTransitionLayoutImpl,
        scene: SceneKey,
        element: Element,
        sceneState: Element.SceneState,
        transition: TransitionState.Transition,
        value: Float
    ): Float {
        // Return the alpha value of [element] either when it starts fading in or when it finished
        // fading out, which is `0` in both cases.
        return 0f
    }
}
