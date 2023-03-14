/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.systemui.shade

import android.annotation.FloatRange

data class ShadeExpansionChangeEvent(
    /** 0 when collapsed, 1 when fully expanded. */
    @FloatRange(from = 0.0, to = 1.0) val fraction: Float,
    /** Whether the panel should be considered expanded */
    val expanded: Boolean,
    /** Whether the user is actively dragging the panel. */
    val tracking: Boolean,
    /** The amount of pixels that the user has dragged during the expansion. */
    val dragDownPxAmount: Float
)
