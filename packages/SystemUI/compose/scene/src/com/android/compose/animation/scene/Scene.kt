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

package com.android.compose.animation.scene

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex

/** A scene in a [SceneTransitionLayout]. */
internal class Scene(
    val key: SceneKey,
    layoutImpl: SceneTransitionLayoutImpl,
    content: @Composable SceneScope.() -> Unit,
    actions: Map<UserAction, SceneKey>,
    zIndex: Float,
) {
    private val scope = SceneScopeImpl(layoutImpl, this)

    var content by mutableStateOf(content)
    var userActions by mutableStateOf(actions)
    var zIndex by mutableFloatStateOf(zIndex)
    var size by mutableStateOf(IntSize.Zero)

    @Composable
    fun Content(modifier: Modifier = Modifier) {
        Box(modifier.zIndex(zIndex).onPlaced { size = it.size }.testTag(key.testTag)) {
            scope.content()
        }
    }

    override fun toString(): String {
        return "Scene(key=$key)"
    }
}

private class SceneScopeImpl(
    private val layoutImpl: SceneTransitionLayoutImpl,
    private val scene: Scene,
) : SceneScope {
    override fun Modifier.element(key: ElementKey): Modifier {
        return element(layoutImpl, scene, key)
    }

    @Composable
    override fun <T> animateSharedValueAsState(
        value: T,
        key: ValueKey,
        element: ElementKey,
        lerp: (T, T, Float) -> T,
        canOverflow: Boolean
    ): State<T> {
        val element =
            layoutImpl.elements[element]
                ?: error(
                    "Element $element is not composed. Make sure to call animateSharedXAsState " +
                        "*after* Modifier.element(key)."
                )

        return animateSharedValueAsState(
            layoutImpl,
            scene,
            element,
            key,
            value,
            lerp,
            canOverflow,
        )
    }

    @Composable
    override fun MovableElement(
        key: ElementKey,
        modifier: Modifier,
        content: @Composable MovableElementScope.() -> Unit,
    ) {
        MovableElement(layoutImpl, scene, key, modifier, content)
    }
}
