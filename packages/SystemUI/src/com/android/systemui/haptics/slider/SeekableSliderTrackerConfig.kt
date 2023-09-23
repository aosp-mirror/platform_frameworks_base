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
 * Configuration parameters of a seekable slider tracker.
 *
 * @property[waitTimeMillis] Wait period to determine if a touch event acquires the slider handle.
 * @property[jumpThreshold] Threshold on the slider progress to detect if a touch event is qualified
 *   as an imprecise acquisition of the slider handle.
 * @property[lowerBookendThreshold] Threshold to determine the progress on the slider that qualifies
 *   as reaching the lower bookend.
 * @property[upperBookendThreshold] Threshold to determine the progress on the slider that qualifies
 *   as reaching the upper bookend.
 */
data class SeekableSliderTrackerConfig(
    val waitTimeMillis: Long = 100,
    @FloatRange(from = 0.0, to = 1.0) val jumpThreshold: Float = 0.02f,
    @FloatRange(from = 0.0, to = 1.0) val lowerBookendThreshold: Float = 0.05f,
    @FloatRange(from = 0.0, to = 1.0) val upperBookendThreshold: Float = 0.95f,
)
