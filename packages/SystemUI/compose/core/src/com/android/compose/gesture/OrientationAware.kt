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

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity

/**
 * An interface to conveniently convert a [Float] to and from an [Offset] or a [Velocity] given an
 * [orientation].
 */
interface OrientationAware {
    val orientation: Orientation

    fun Float.toOffset(): Offset {
        return when (orientation) {
            Orientation.Horizontal -> Offset(x = this, y = 0f)
            Orientation.Vertical -> Offset(x = 0f, y = this)
        }
    }

    fun Float.toVelocity(): Velocity {
        return when (orientation) {
            Orientation.Horizontal -> Velocity(x = this, y = 0f)
            Orientation.Vertical -> Velocity(x = 0f, y = this)
        }
    }

    fun Offset.toFloat(): Float {
        return when (orientation) {
            Orientation.Horizontal -> this.x
            Orientation.Vertical -> this.y
        }
    }

    fun Velocity.toFloat(): Float {
        return when (orientation) {
            Orientation.Horizontal -> this.x
            Orientation.Vertical -> this.y
        }
    }
}
