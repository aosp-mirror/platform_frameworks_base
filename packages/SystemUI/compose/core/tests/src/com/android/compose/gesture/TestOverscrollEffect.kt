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

package com.android.compose.gesture

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

class TestOverscrollEffect(
    override val orientation: Orientation,
    private val onPostFling: suspend (Float) -> Float = { it },
    private val onPostScroll: (Float) -> Float,
) : OverscrollEffect, OrientationAware {
    override val isInProgress: Boolean = false
    var applyToFlingDone = false
        private set

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        val consumedByScroll = performScroll(delta)
        val available = delta - consumedByScroll
        val consumedByEffect = onPostScroll(available.toFloat()).toOffset()
        return consumedByScroll + consumedByEffect
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        val consumedByFling = performFling(velocity)
        val available = velocity - consumedByFling
        onPostFling(available.toFloat())
        applyToFlingDone = true
    }
}
