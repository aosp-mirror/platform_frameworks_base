/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.keyguard

import android.util.MathUtils

object BouncerPanelExpansionCalculator {
    /**
     *  Scale the alpha/position of the host view.
     */
    @JvmStatic
    fun getHostViewScaledExpansion(fraction: Float): Float {
        return when {
                    fraction >= 0.9f -> 1f
                    fraction < 0.6 -> 0f
                    else -> (fraction - 0.6f) / 0.3f
                }
    }

    /**
     *  Scale the alpha/tint of the back scrim.
     */
    @JvmStatic
    fun getBackScrimScaledExpansion(fraction: Float): Float {
        return MathUtils.constrain((fraction - 0.9f) / 0.1f, 0f, 1f)
    }

    /**
     *  This will scale the alpha/position of the clock.
     */
    @JvmStatic
    fun getKeyguardClockScaledExpansion(fraction: Float): Float {
        return MathUtils.constrain((fraction - 0.7f) / 0.3f, 0f, 1f)
    }

    /**
     *  Scale the position of the dream complications.
     */
    @JvmStatic
    fun getDreamYPositionScaledExpansion(fraction: Float): Float {
        return when {
            fraction >= 0.98f -> 1f
            fraction < 0.93 -> 0f
            else -> (fraction - 0.93f) / 0.05f
        }
    }

    /**
     *  Scale the alpha of the dream complications.
     */
    @JvmStatic
    fun getDreamAlphaScaledExpansion(fraction: Float): Float {
        return MathUtils.constrain((fraction - 0.94f) / 0.06f, 0f, 1f)
    }
}