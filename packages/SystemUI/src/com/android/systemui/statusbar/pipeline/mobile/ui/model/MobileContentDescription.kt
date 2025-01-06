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

package com.android.systemui.statusbar.pipeline.mobile.ui.model

import android.annotation.StringRes
import android.content.Context
import com.android.systemui.res.R

sealed interface MobileContentDescription {
    fun loadContentDescription(context: Context): String

    /**
     * Content description for cellular parameterizes the [networkName] which comes from the system
     */
    data class Cellular(val networkName: String, @StringRes val levelDescriptionRes: Int) :
        MobileContentDescription {
        override fun loadContentDescription(context: Context): String =
            context.getString(
                R.string.accessibility_phone_string_format,
                networkName,
                context.getString(levelDescriptionRes),
            )
    }

    data class SatelliteContentDescription(@StringRes val resId: Int) : MobileContentDescription {
        override fun loadContentDescription(context: Context): String =
            context.getString(this.resId)
    }
}
