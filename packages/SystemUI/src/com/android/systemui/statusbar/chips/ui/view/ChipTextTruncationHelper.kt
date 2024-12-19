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

import android.view.View
import android.view.View.MeasureSpec
import android.widget.TextView.resolveSize
import com.android.systemui.res.R

/**
 * Helper class to determine when a status bar chip's text should be hidden because it's too long.
 */
class ChipTextTruncationHelper(private val view: View) {
    /** A measure spec for the status bar chip text with an unlimited width. */
    val unlimitedWidthMeasureSpec =
        SysuiMeasureSpec(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))

    /** The [MeasureSpec] that the view should actually use win [onMeasure]. */
    lateinit var widthMeasureSpec: SysuiMeasureSpec

    private var maxWidth: Int = 0
        set(value) {
            field = value
            maximumWidthMeasureSpec =
                SysuiMeasureSpec(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST))
        }

    /** A measure spec for the status bar chip text with the correct maximum width. */
    private lateinit var maximumWidthMeasureSpec: SysuiMeasureSpec

    init {
        maxWidth = fetchMaxWidth()
    }

    fun onConfigurationChanged() {
        maxWidth = fetchMaxWidth()
    }

    /**
     * Returns true if this view should show the text because there's enough room for a substantial
     * amount of text, and returns false if this view should hide the text because the text is much
     * too long.
     *
     * @param desiredTextWidthPx should be calculated by having the view measure itself with
     *   [unlimitedWidthMeasureSpec] and then sending its `measuredWidth` to this method. (This
     *   class can't compute [desiredTextWidthPx] directly because [View.onMeasure] can only be
     *   called by the view itself.)
     * @param widthMeasureSpec the view's current and unmodified width spec
     */
    fun shouldShowText(desiredTextWidthPx: Int, widthMeasureSpec: SysuiMeasureSpec): Boolean {
        // Evaluate how wide the text *can* be based on:
        // #1: The maximum width encoded by [maxWidth]
        val maxWidthBasedOnDimension =
            resolveSize(desiredTextWidthPx, maximumWidthMeasureSpec.specInt)
        // #2: The width the view is allowed to take up (If there's 2 chips, the second chip likely
        // has < [maxWidth] room available)
        val maxWidthBasedOnViewSpaceAvailable =
            resolveSize(desiredTextWidthPx, widthMeasureSpec.specInt)

        val enforcedTextWidth: Int
        if (maxWidthBasedOnViewSpaceAvailable < maxWidthBasedOnDimension) {
            // View space available takes priority
            this.widthMeasureSpec = widthMeasureSpec
            enforcedTextWidth = maxWidthBasedOnViewSpaceAvailable
        } else {
            // Enforce the maximum width
            this.widthMeasureSpec = maximumWidthMeasureSpec
            enforcedTextWidth = maxWidthBasedOnDimension
        }

        // Only show the text if at least 50% of it can show. (Assume that if < 50% of the text will
        // be visible, the text will be more confusing than helpful.)
        return desiredTextWidthPx <= enforcedTextWidth * 2
    }

    private fun fetchMaxWidth() =
        view.context.resources.getDimensionPixelSize(R.dimen.ongoing_activity_chip_max_text_width)
}

/** A typed class for [MeasureSpec] ints. */
data class SysuiMeasureSpec(val specInt: Int)
