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

package com.android.systemui.statusbar.chips.ui.view

import android.content.Context
import android.util.AttributeSet
import com.android.systemui.animation.view.LaunchableLinearLayout

/**
 * A container view for the ongoing activity chip background. Needed so that we can limit the height
 * of the background when the font size is very large (200%), in which case the background would go
 * past the bounds of the status bar.
 */
class ChipBackgroundContainer(context: Context, attrs: AttributeSet) :
    LaunchableLinearLayout(context, attrs) {

    /** Sets where this view should fetch its max height from. */
    var maxHeightFetcher: (() -> Int)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val maxHeight = maxHeightFetcher?.invoke()
        val chosenHeight =
            if (maxHeight != null) {
                // Give 1 extra px of space (without it, the background could still be cut off)
                measuredHeight.coerceAtMost(maxHeight - 1)
            } else {
                measuredHeight
            }
        setMeasuredDimension(measuredWidth, chosenHeight)
    }
}
