/*
 * Copyright (C) 2024 The Android Open Source Project
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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

internal class UserActionDistanceScopeImpl(
    private val layoutImpl: SceneTransitionLayoutImpl,
) : UserActionDistanceScope {
    override val density: Float
        get() = layoutImpl.density.density

    override val fontScale: Float
        get() = layoutImpl.density.fontScale

    override fun ElementKey.targetSize(scene: SceneKey): IntSize? {
        return layoutImpl.elements[this]?.sceneStates?.get(scene)?.targetSize.takeIf {
            it != Element.SizeUnspecified
        }
    }

    override fun ElementKey.targetOffset(scene: SceneKey): Offset? {
        return layoutImpl.elements[this]?.sceneStates?.get(scene)?.targetOffset.takeIf {
            it != Offset.Unspecified
        }
    }

    override fun SceneKey.targetSize(): IntSize? {
        return layoutImpl.scenes[this]?.targetSize.takeIf { it != IntSize.Zero }
    }
}
