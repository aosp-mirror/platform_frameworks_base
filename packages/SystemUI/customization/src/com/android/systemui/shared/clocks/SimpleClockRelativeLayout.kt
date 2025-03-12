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

package com.android.systemui.shared.clocks

import android.content.Context
import android.view.View.MeasureSpec.EXACTLY
import android.widget.RelativeLayout
import androidx.core.view.children
import com.android.systemui.shared.clocks.view.SimpleDigitalClockView

class SimpleClockRelativeLayout(context: Context, val faceLayout: DigitalFaceLayout?) :
    RelativeLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // For migrate_clocks_to_blueprint, mode is EXACTLY
        // when the flag is turned off, we won't execute this codes
        if (MeasureSpec.getMode(heightMeasureSpec) == EXACTLY) {
            if (
                faceLayout == DigitalFaceLayout.TWO_PAIRS_VERTICAL ||
                    faceLayout == DigitalFaceLayout.FOUR_DIGITS_ALIGN_CENTER
            ) {
                val constrainedHeight = MeasureSpec.getSize(heightMeasureSpec) / 2F
                children.forEach {
                    // The assumption here is the height of text view is linear to font size
                    (it as SimpleDigitalClockView).applyTextSize(
                        constrainedHeight,
                        constrainedByHeight = true,
                    )
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
