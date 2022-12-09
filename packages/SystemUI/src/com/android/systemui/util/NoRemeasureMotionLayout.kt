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
 * limitations under the License.
 */

package com.android.systemui.util

import android.content.Context
import android.util.AttributeSet
import android.view.Choreographer
import androidx.constraintlayout.motion.widget.MotionLayout

/**
 * [MotionLayout] that avoids remeasuring with the same inputs in the same frame.
 *
 * This is important when this view is the child of a view that performs more than one measure pass
 * (e.g. [LinearLayout] or [ConstraintLayout]). In those cases, if this view is measured with the
 * same inputs in the same frame, we use the last result.
 */
class NoRemeasureMotionLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyle: Int = 0
) : MotionLayout(context, attrs, defStyle) {

    private var lastWidthSpec: Int? = null
    private var lastHeightSpec: Int? = null
    private var lastFrame: Long? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (
            lastWidthSpec == widthMeasureSpec &&
            lastHeightSpec == heightMeasureSpec &&
            Choreographer.getMainThreadInstance()?.frameTime == lastFrame
        ) {
            setMeasuredDimension(measuredWidth, measuredHeight)
            return
        }
        traceSection("NoRemeasureMotionLayout - measure") {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            lastWidthSpec = widthMeasureSpec
            lastHeightSpec = heightMeasureSpec
            lastFrame = Choreographer.getMainThreadInstance()?.frameTime
        }
    }
}
