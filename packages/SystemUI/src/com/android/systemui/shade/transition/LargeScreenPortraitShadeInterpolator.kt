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
import com.android.systemui.animation.ShadeInterpolation
import javax.inject.Inject

/** Interpolator responsible for the shade when in portrait on a large screen. */
internal class LargeScreenPortraitShadeInterpolator @Inject internal constructor() :
    LargeScreenShadeInterpolator {

    override fun getBehindScrimAlpha(fraction: Float): Float {
        return MathUtils.constrainedMap(0f, 1f, 0f, 0.3f, fraction)
    }

    override fun getNotificationScrimAlpha(fraction: Float): Float {
        return MathUtils.constrainedMap(0f, 1f, 0.3f, 0.75f, fraction)
    }

    override fun getNotificationContentAlpha(fraction: Float): Float {
        return ShadeInterpolation.getContentAlpha(fraction)
    }

    override fun getNotificationFooterAlpha(fraction: Float): Float {
        return ShadeInterpolation.getContentAlpha(fraction)
    }

    override fun getQsAlpha(fraction: Float): Float {
        return ShadeInterpolation.getContentAlpha(fraction)
    }
}
