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

package com.android.compose.animation.scene.effect

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/** An overscroll effect that ensures only a single fling animation is triggered. */
internal class GestureEffect(private val delegate: OverscrollEffect) :
    OverscrollEffect by delegate {
    private var shouldFling = false

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        shouldFling = true
        return delegate.applyToScroll(delta, source, performScroll)
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        if (!shouldFling) {
            performFling(velocity)
            return
        }
        shouldFling = false
        delegate.applyToFling(velocity, performFling)
    }

    suspend fun ensureApplyToFlingIsCalled() {
        applyToFling(Velocity.Zero) { Velocity.Zero }
    }
}
