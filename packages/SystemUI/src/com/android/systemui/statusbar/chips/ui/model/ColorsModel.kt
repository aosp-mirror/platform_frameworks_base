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

package com.android.systemui.statusbar.chips.ui.model

import android.content.Context
import android.content.res.ColorStateList
import androidx.annotation.ColorInt
import com.android.settingslib.Utils
import com.android.systemui.res.R

/** Model representing how the chip in the status bar should be colored. */
sealed interface ColorsModel {
    /** The color for the background of the chip. */
    fun background(context: Context): ColorStateList

    /** The color for the text (and icon) on the chip. */
    @ColorInt fun text(context: Context): Int

    /** The chip should match the theme's primary color. */
    data object Themed : ColorsModel {
        override fun background(context: Context): ColorStateList =
            Utils.getColorAttr(context, com.android.internal.R.attr.colorAccent)

        override fun text(context: Context) =
            Utils.getColorAttrDefaultColor(context, com.android.internal.R.attr.colorPrimary)
    }

    /** The chip should have a red background with white text. */
    data object Red : ColorsModel {
        override fun background(context: Context): ColorStateList =
            ColorStateList.valueOf(context.getColor(R.color.GM2_red_600))

        override fun text(context: Context) = context.getColor(android.R.color.white)
    }
}
