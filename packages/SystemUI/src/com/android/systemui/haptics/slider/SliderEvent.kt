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

package com.android.systemui.haptics.slider

import androidx.annotation.FloatRange

/**
 * An event arising from a slider.
 *
 * @property[type] The type of event. Must be one of [SliderEventType].
 * @property[currentProgress] The current progress of the slider normalized to the range between 0F
 *   and 1F (inclusive).
 */
data class SliderEvent(
    val type: SliderEventType,
    @FloatRange(from = 0.0, to = 1.0) val currentProgress: Float
)
