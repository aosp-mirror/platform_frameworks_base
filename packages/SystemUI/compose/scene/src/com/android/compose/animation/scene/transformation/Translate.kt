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
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.Element
import com.android.compose.animation.scene.ElementMatcher
import com.android.compose.animation.scene.OverscrollScope
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayoutImpl
import com.android.compose.animation.scene.TransitionState

internal class Translate(
    override val matcher: ElementMatcher,
    private val x: Dp = 0.dp,
    private val y: Dp = 0.dp,
) : PropertyTransformation<Offset> {
    override fun transform(
        layoutImpl: SceneTransitionLayoutImpl,
        scene: SceneKey,
        element: Element,
        sceneState: Element.SceneState,
        transition: TransitionState.Transition,
        value: Offset,
    ): Offset {
        return with(layoutImpl.density) {
            Offset(
                value.x + x.toPx(),
                value.y + y.toPx(),
            )
        }
    }
}

internal class OverscrollTranslate(
    override val matcher: ElementMatcher,
    val x: OverscrollScope.() -> Float = { 0f },
    val y: OverscrollScope.() -> Float = { 0f },
) : PropertyTransformation<Offset> {
    override fun transform(
        layoutImpl: SceneTransitionLayoutImpl,
        scene: SceneKey,
        element: Element,
        sceneState: Element.SceneState,
        transition: TransitionState.Transition,
        value: Offset,
    ): Offset {
        // As this object is created by OverscrollBuilderImpl and we retrieve the current
        // OverscrollSpec only when the transition implements HasOverscrollProperties, we can assume
        // that this method was invoked after performing this check.
        val overscrollProperties = transition as TransitionState.HasOverscrollProperties

        return Offset(
            x = value.x + overscrollProperties.overscrollScope.x(),
            y = value.y + overscrollProperties.overscrollScope.y(),
        )
    }
}
