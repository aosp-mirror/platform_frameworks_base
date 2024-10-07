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
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.Element
import com.android.compose.animation.scene.ElementMatcher
import com.android.compose.animation.scene.SceneTransitionLayoutImpl
import com.android.compose.animation.scene.content.state.TransitionState

/** Translate an element from an edge of the layout. */
internal class EdgeTranslate(
    override val matcher: ElementMatcher,
    private val edge: Edge,
    private val startsOutsideLayoutBounds: Boolean = true,
) : PropertyTransformation<Offset> {
    override fun transform(
        layoutImpl: SceneTransitionLayoutImpl,
        content: ContentKey,
        element: Element,
        stateInContent: Element.State,
        transition: TransitionState.Transition,
        value: Offset,
    ): Offset {
        val sceneSize = layoutImpl.content(content).targetSize
        val elementSize = stateInContent.targetSize
        if (elementSize == Element.SizeUnspecified) {
            return value
        }

        return when (edge.resolve(layoutImpl.layoutDirection)) {
            Edge.Resolved.Top ->
                if (startsOutsideLayoutBounds) {
                    Offset(value.x, -elementSize.height.toFloat())
                } else {
                    Offset(value.x, 0f)
                }
            Edge.Resolved.Left ->
                if (startsOutsideLayoutBounds) {
                    Offset(-elementSize.width.toFloat(), value.y)
                } else {
                    Offset(0f, value.y)
                }
            Edge.Resolved.Bottom ->
                if (startsOutsideLayoutBounds) {
                    Offset(value.x, sceneSize.height.toFloat())
                } else {
                    Offset(value.x, (sceneSize.height - elementSize.height).toFloat())
                }
            Edge.Resolved.Right ->
                if (startsOutsideLayoutBounds) {
                    Offset(sceneSize.width.toFloat(), value.y)
                } else {
                    Offset((sceneSize.width - elementSize.width).toFloat(), value.y)
                }
        }
    }
}
