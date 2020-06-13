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

package com.android.systemui.util

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * Basically a normal linear layout but doesn't grow its children with weight 1 even when its
 * measured with exactly.
 */
class NeverExactlyLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {

        val (widthExactly, usedWidthSpec, width) = getNonExactlyMeasureSpec(widthMeasureSpec)
        val (heightExactly, usedHeightSpec, height) = getNonExactlyMeasureSpec(heightMeasureSpec)

        super.onMeasure(usedWidthSpec, usedHeightSpec)
        if (widthExactly || heightExactly) {
            val newWidth = if (widthExactly) width else measuredWidth
            val newHeight = if (heightExactly) height else measuredHeight
            setMeasuredDimension(newWidth, newHeight)
        }
    }

    /**
     * Obtain a measurespec that's not exactly
     *
     * @return a triple, where we return 1. if this was exactly, 2. the new measurespec, 3. the size
     *         of the measurespec
     */
    private fun getNonExactlyMeasureSpec(measureSpec: Int): Triple<Boolean, Int, Int> {
        var newSpec = measureSpec
        val isExactly = MeasureSpec.getMode(measureSpec) == MeasureSpec.EXACTLY
        val size = MeasureSpec.getSize(measureSpec)
        if (isExactly) {
            newSpec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST)
        }
        return Triple(isExactly, newSpec, size)
    }
}