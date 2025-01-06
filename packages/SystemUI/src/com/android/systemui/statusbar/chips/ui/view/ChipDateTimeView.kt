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

package com.android.systemui.statusbar.chips.ui.view

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.widget.DateTimeView

/** A [DateTimeView] for chips in the status bar. See also: [ChipTextView]. */
class ChipDateTimeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    DateTimeView(context, attrs) {
    private val textTruncationHelper = ChipTextTruncationHelper(this)

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        textTruncationHelper.onConfigurationChanged()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Evaluate how wide the text *wants* to be if it had unlimited space. This is needed so
        // that [textTruncationHelper.shouldShowText] works correctly.
        super.onMeasure(textTruncationHelper.unlimitedWidthMeasureSpec.specInt, heightMeasureSpec)

        if (
            textTruncationHelper.shouldShowText(
                desiredTextWidthPx = measuredWidth,
                widthMeasureSpec = SysuiMeasureSpec(widthMeasureSpec),
            )
        ) {
            // Show the text with the width spec specified by the helper
            super.onMeasure(textTruncationHelper.widthMeasureSpec.specInt, heightMeasureSpec)
        } else {
            // Changing visibility ensures that the content description is not read aloud when the
            // text isn't displayed.
            visibility = GONE
            setMeasuredDimension(0, 0)
        }
    }
}
