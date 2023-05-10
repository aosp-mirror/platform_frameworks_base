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

package com.android.systemui.util.wrapper

import android.util.DisplayUtils
import android.view.Display
import javax.inject.Inject

/** Injectable wrapper around `DisplayUtils` functions */
class DisplayUtilsWrapper @Inject constructor() {
    fun getPhysicalPixelDisplaySizeRatio(
        physicalWidth: Int,
        physicalHeight: Int,
        currentWidth: Int,
        currentHeight: Int
    ): Float {
        return DisplayUtils.getPhysicalPixelDisplaySizeRatio(
            physicalWidth,
            physicalHeight,
            currentWidth,
            currentHeight
        )
    }

    fun getMaximumResolutionDisplayMode(modes: Array<Display.Mode>?): Display.Mode? {
        return DisplayUtils.getMaximumResolutionDisplayMode(modes)
    }
}
