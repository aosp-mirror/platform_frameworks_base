/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.compose.ui.util

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity

interface SpaceVectorConverter {
    fun Offset.toFloat(): Float
    fun Velocity.toFloat(): Float
    fun Float.toOffset(): Offset
    fun Float.toVelocity(): Velocity
}

fun SpaceVectorConverter(orientation: Orientation) =
    when (orientation) {
        Orientation.Horizontal -> HorizontalConverter
        Orientation.Vertical -> VerticalConverter
    }

private val HorizontalConverter =
    object : SpaceVectorConverter {
        override fun Offset.toFloat() = x
        override fun Velocity.toFloat() = x
        override fun Float.toOffset() = Offset(this, 0f)
        override fun Float.toVelocity() = Velocity(this, 0f)
    }

private val VerticalConverter =
    object : SpaceVectorConverter {
        override fun Offset.toFloat() = y
        override fun Velocity.toFloat() = y
        override fun Float.toOffset() = Offset(0f, this)
        override fun Float.toVelocity() = Velocity(0f, this)
    }
