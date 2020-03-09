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
 * limitations under the License.
 */

package com.android.systemui.controls.management

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.android.systemui.qs.PageIndicator

/**
 * Page indicator for management screens.
 *
 * Adds RTL support to [PageIndicator]. To be used with [ViewPager2].
 */
class ManagementPageIndicator(
    context: Context,
    attrs: AttributeSet
) : PageIndicator(context, attrs) {

    override fun setLocation(location: Float) {
        // Location doesn't know about RTL
        if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            val numPages = childCount
            super.setLocation(numPages - 1 - location)
        } else {
            super.setLocation(location)
        }
    }
}