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

package com.android.systemui.scene.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.scene.domain.interactor.SystemGestureExclusionInteractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlin.math.roundToInt

/** Decides whether drag gestures should be filtered out in the scene container framework. */
class SceneContainerGestureFilter
@AssistedInject
constructor(interactor: SystemGestureExclusionInteractor, @Assisted displayId: Int) :
    ExclusiveActivatable() {

    private val hydrator = Hydrator("SceneContainerGestureFilter.hydrator")
    private val exclusionRegion by
        hydrator.hydratedStateOf(
            traceName = "exclusionRegion",
            initialValue = null,
            source = interactor.exclusionRegion(displayId),
        )

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    /**
     * Returns `true` if a drag gesture starting at [startPosition] should be filtered out (e.g.
     * ignored, `false` otherwise.
     *
     * Invoke this and pass in the position of the `ACTION_DOWN` pointer event that began the
     * gesture.
     */
    fun shouldFilterGesture(startPosition: Offset): Boolean {
        check(isActive) { "Must be activated to use!" }

        return exclusionRegion?.contains(startPosition.x.roundToInt(), startPosition.y.roundToInt())
            ?: false
    }

    @AssistedFactory
    interface Factory {
        fun create(displayId: Int): SceneContainerGestureFilter
    }
}
