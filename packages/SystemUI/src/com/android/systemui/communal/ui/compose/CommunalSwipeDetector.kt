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

package com.android.systemui.communal.ui.compose

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.SwipeDetector
import com.android.compose.animation.scene.SwipeSource
import com.android.compose.animation.scene.SwipeSourceDetector
import kotlin.math.abs

/**
 * {@link CommunalSwipeDetector} provides an implementation of {@link SwipeDetector} and {@link
 * SwipeSourceDetector} to enable fullscreen swipe handling to transition to and from the glanceable
 * hub.
 */
class CommunalSwipeDetector(private var lastDirection: SwipeSource.Resolved? = null) :
    SwipeSourceDetector, SwipeDetector {
    companion object {
        private const val TRAVEL_RATIO_THRESHOLD = .5f
    }

    override fun source(
        layoutSize: IntSize,
        position: IntOffset,
        density: Density,
        orientation: Orientation,
    ): SwipeSource.Resolved? {
        return lastDirection
    }

    override fun detectSwipe(change: PointerInputChange): Boolean {
        if (change.positionChange().x > 0) {
            lastDirection = Edge.Resolved.Left
        } else {
            lastDirection = Edge.Resolved.Right
        }

        // Determine whether the ratio of the distance traveled horizontally to the distance
        // traveled vertically exceeds the threshold.
        return abs(change.positionChange().x / change.positionChange().y) > TRAVEL_RATIO_THRESHOLD
    }
}
