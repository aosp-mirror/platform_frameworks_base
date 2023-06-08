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

package com.android.systemui.shade.transition

import android.util.MathUtils
import javax.inject.Inject

/** Interpolator responsible for the split shade. */
internal class SplitShadeInterpolator @Inject internal constructor() :
    LargeScreenShadeInterpolator {

    override fun getBehindScrimAlpha(fraction: Float): Float {
        // Start delay: 0%
        // Duration: 40%
        // End: 40%
        return mapFraction(start = 0f, end = 0.4f, fraction)
    }

    override fun getNotificationScrimAlpha(fraction: Float): Float {
        // Start delay: 39%
        // Duration: 27%
        // End: 66%
        return mapFraction(start = 0.39f, end = 0.66f, fraction)
    }

    override fun getNotificationContentAlpha(fraction: Float): Float {
        return getNotificationScrimAlpha(fraction)
    }

    override fun getNotificationFooterAlpha(fraction: Float): Float {
        // Start delay: 57.6%
        // Duration: 32.1%
        // End: 89.7%
        return mapFraction(start = 0.576f, end = 0.897f, fraction)
    }

    override fun getQsAlpha(fraction: Float): Float {
        return getNotificationScrimAlpha(fraction)
    }

    private fun mapFraction(start: Float, end: Float, fraction: Float) =
        MathUtils.constrainedMap(
            /* rangeMin= */ 0f,
            /* rangeMax= */ 1f,
            /* valueMin= */ start,
            /* valueMax= */ end,
            /* value= */ fraction
        )
}
