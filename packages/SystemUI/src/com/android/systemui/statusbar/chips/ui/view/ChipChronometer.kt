/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.widget.Chronometer
import androidx.annotation.UiThread

/**
 * A [Chronometer] specifically for chips in the status bar that show ongoing duration of an
 * activity.
 *
 * This class handles:
 * 1) Setting the text width. If we used a basic WRAP_CONTENT for width, the chip width would change
 *    slightly each second because the width of each number is slightly different.
 *
 *    Instead, we save the largest number width seen so far and ensure that the chip is at least
 *    that wide. This means the chip may get larger over time (e.g. in the transition from 59:59 to
 *    1:00:00), but never smaller.
 * 2) Hiding the text if the time gets too long for the space available. Once the text has been
 *    hidden, it remains hidden for the duration of the activity (or until [resetWidthRestriction]
 *    is called).
 */
class ChipChronometer
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) :
    Chronometer(context, attrs, defStyle) {

    // Minimum width that the text view can be. Corresponds with the largest number width seen so
    // far.
    private var minimumTextWidth: Int = 0

    // True if the text is too long for the space available, so the text should be hidden.
    private var shouldHideText: Boolean = false

    override fun setBase(base: Long) {
        resetWidthRestriction()
        super.setBase(base)
    }

    /**
     * Resets any width restrictions that were placed on the chronometer.
     *
     * Should be used when the user's screen bounds changed because there may now be more room in
     * the status bar to show additional content.
     */
    @UiThread
    fun resetWidthRestriction() {
        minimumTextWidth = 0
        shouldHideText = false
        // View needs to be visible in order to be re-measured
        visibility = VISIBLE
        forceLayout()
    }

    /** Sets whether this view should hide its text or not. */
    @UiThread
    fun setShouldHideText(shouldHideText: Boolean) {
        this.shouldHideText = shouldHideText
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (shouldHideText) {
            setMeasuredDimension(0, 0)
            return
        }

        // Evaluate how wide the text *wants* to be if it had unlimited space.
        super.onMeasure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightMeasureSpec)
        val desiredTextWidth = measuredWidth

        // Evaluate how wide the text *can* be based on the enforced constraints
        val enforcedTextWidth = resolveSize(desiredTextWidth, widthMeasureSpec)

        if (desiredTextWidth > enforcedTextWidth) {
            shouldHideText = true
            // Changing visibility ensures that the content description is not read aloud when the
            // time isn't displayed.
            visibility = GONE
            setMeasuredDimension(0, 0)
        } else {
            // It's possible that the current text could fit in a smaller width, but we don't want
            // the chip to change size every second. Instead, keep it at the minimum required width.
            minimumTextWidth = desiredTextWidth.coerceAtLeast(minimumTextWidth)
            setMeasuredDimension(minimumTextWidth, MeasureSpec.getSize(heightMeasureSpec))
        }
    }
}
