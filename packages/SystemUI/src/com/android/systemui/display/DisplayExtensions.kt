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

package com.android.systemui.display

import android.graphics.Rect
import android.view.Display
import android.view.DisplayInfo

val Display.naturalBounds: Rect
    get() {
        val outDisplayInfo = DisplayInfo()
        getDisplayInfo(outDisplayInfo)
        return Rect(
            /* left = */ 0,
            /* top = */ 0,
            /* right = */ outDisplayInfo.naturalWidth,
            /* bottom = */ outDisplayInfo.naturalHeight
        )
    }

val Display.naturalWidth: Int
    get() {
        val outDisplayInfo = DisplayInfo()
        getDisplayInfo(outDisplayInfo)
        return outDisplayInfo.naturalWidth
    }

val Display.naturalHeight: Int
    get() {
        val outDisplayInfo = DisplayInfo()
        getDisplayInfo(outDisplayInfo)
        return outDisplayInfo.naturalHeight
    }
