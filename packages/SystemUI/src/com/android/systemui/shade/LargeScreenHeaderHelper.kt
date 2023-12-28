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

package com.android.systemui.shade

import android.content.Context
import com.android.internal.policy.SystemBarUtils
import com.android.systemui.res.R
import javax.inject.Inject
import kotlin.math.max

class LargeScreenHeaderHelper @Inject constructor(private val context: Context) {

    fun getLargeScreenHeaderHeight(): Int = getLargeScreenHeaderHeight(context)

    companion object {
        @JvmStatic
        fun getLargeScreenHeaderHeight(context: Context): Int {
            val defaultHeight =
                context.resources.getDimensionPixelSize(R.dimen.large_screen_shade_header_height)
            val statusBarHeight = SystemBarUtils.getStatusBarHeight(context)
            // Height has to be at least as tall as the status bar, as the status bar height takes
            // into account display cutouts.
            return max(defaultHeight, statusBarHeight)
        }
    }
}
