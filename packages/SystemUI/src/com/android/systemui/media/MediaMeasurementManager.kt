/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.media

import com.android.systemui.util.animation.BaseMeasurementCache
import com.android.systemui.util.animation.GuaranteedMeasurementCache
import com.android.systemui.util.animation.MeasurementCache
import com.android.systemui.util.animation.MeasurementInput
import com.android.systemui.util.animation.MeasurementOutput
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A class responsible creating measurement caches for media hosts which also coordinates with
 * the view manager to obtain sizes for unknown measurement inputs.
 */
@Singleton
class MediaMeasurementManager @Inject constructor(
    private val mediaViewManager: MediaViewManager
) {
    private val baseCache: MeasurementCache

    init {
        baseCache = BaseMeasurementCache()
    }

    private fun provideMeasurement(input: MediaMeasurementInput) : MeasurementOutput? {
        return mediaViewManager.obtainMeasurement(input)
    }

    /**
     * Obtain a guaranteed measurement cache for a host view. The measurement cache makes sure that
     * requesting any size from the cache will always return the correct value.
     */
    fun obtainCache(host: MediaState): GuaranteedMeasurementCache {
        val remapper = { input: MeasurementInput ->
            host.getMeasuringInput(input)
        }
        val provider = { input: MeasurementInput ->
            provideMeasurement(input as MediaMeasurementInput)
        }
        return GuaranteedMeasurementCache(baseCache, remapper, provider)
    }
}

