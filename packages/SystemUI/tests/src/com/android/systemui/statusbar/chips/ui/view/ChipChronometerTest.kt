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

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.LayoutInflater
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val TEXT_VIEW_MAX_WIDTH = 400

// When a [Chronometer] is created, it starts off with "00:00" as its text.
private const val INITIAL_TEXT = "00:00"
private const val LARGE_TEXT = "00:000"
private const val XL_TEXT = "00:0000"

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class ChipChronometerTest : SysuiTestCase() {

    private lateinit var textView: ChipChronometer
    private lateinit var doesNotFitText: String

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        TestableLooper.get(this).runWithLooper {
            val chipView =
                LayoutInflater.from(mContext).inflate(R.layout.ongoing_activity_chip, null)
            textView = chipView.findViewById(R.id.ongoing_activity_chip_time)!!
            measureTextView()
            calculateDoesNotFixText()
        }
    }

    @Test
    fun verifyTextSizes() {
        val initialTextLength = textView.paint.measureText(INITIAL_TEXT)
        val largeTextLength = textView.paint.measureText(LARGE_TEXT)
        val xlTextLength = textView.paint.measureText(XL_TEXT)

        // Assert that our test text sizes do what we expect them to do in the rest of the tests.
        assertThat(initialTextLength).isLessThan(TEXT_VIEW_MAX_WIDTH)
        assertThat(largeTextLength).isLessThan(TEXT_VIEW_MAX_WIDTH)
        assertThat(xlTextLength).isLessThan(TEXT_VIEW_MAX_WIDTH)
        assertThat(textView.paint.measureText(doesNotFitText)).isGreaterThan(TEXT_VIEW_MAX_WIDTH)

        assertThat(largeTextLength).isGreaterThan(initialTextLength)
        assertThat(xlTextLength).isGreaterThan(largeTextLength)
    }

    @Test
    fun onMeasure_initialTextFitsInSpace_textDisplayed() {
        assertThat(textView.measuredWidth).isGreaterThan(0)
    }

    @Test
    fun onMeasure_newTextLargerThanPreviousText_widthGetsLarger() {
        val initialTextLength = textView.measuredWidth

        setTextAndMeasure(LARGE_TEXT)

        assertThat(textView.measuredWidth).isGreaterThan(initialTextLength)
    }

    @Test
    fun onMeasure_newTextSmallerThanPreviousText_widthDoesNotGetSmaller() {
        setTextAndMeasure(XL_TEXT)
        val xlWidth = textView.measuredWidth

        setTextAndMeasure(LARGE_TEXT)

        assertThat(textView.measuredWidth).isEqualTo(xlWidth)
    }

    @Test
    fun onMeasure_textDoesNotFit_textHidden() {
        setTextAndMeasure(doesNotFitText)

        assertThat(textView.measuredWidth).isEqualTo(0)
    }

    @Test
    fun onMeasure_newTextFitsButPreviousTextDidNot_textHidden() {
        setTextAndMeasure(doesNotFitText)

        setTextAndMeasure(LARGE_TEXT)

        assertThat(textView.measuredWidth).isEqualTo(0)
    }

    @Test
    fun resetBase_hadLongerTextThenSetBaseThenShorterText_widthIsShort() {
        setTextAndMeasure(XL_TEXT)
        val xlWidth = textView.measuredWidth

        textView.base = 0L
        setTextAndMeasure(INITIAL_TEXT)

        assertThat(textView.measuredWidth).isLessThan(xlWidth)
        assertThat(textView.measuredWidth).isGreaterThan(0)
    }

    @Test
    fun setBase_wasHidingTextThenSetBaseThenShorterText_textShown() {
        setTextAndMeasure(doesNotFitText)

        textView.base = 0L
        setTextAndMeasure(INITIAL_TEXT)

        assertThat(textView.measuredWidth).isGreaterThan(0)
    }

    @Test
    fun setShouldHideText_true_textHidden() {
        textView.setShouldHideText(true)
        measureTextView()

        assertThat(textView.measuredWidth).isEqualTo(0)
    }

    @Test
    fun setShouldHideText_false_textShown() {
        // First, set to true so that setting it to false will definitely have an effect.
        textView.setShouldHideText(true)
        measureTextView()

        textView.setShouldHideText(false)
        measureTextView()

        assertThat(textView.measuredWidth).isGreaterThan(0)
    }

    private fun setTextAndMeasure(text: String) {
        textView.text = text
        measureTextView()
    }

    private fun measureTextView() {
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(TEXT_VIEW_MAX_WIDTH, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
    }

    /**
     * Calculates what [doesNotFitText] should be. Needs to be done dynamically because different
     * devices have different densities, which means the textView can fit different amounts of
     * characters.
     */
    private fun calculateDoesNotFixText() {
        var currentText = XL_TEXT + "0"
        while (textView.paint.measureText(currentText) <= TEXT_VIEW_MAX_WIDTH) {
            currentText += "0"
        }
        doesNotFitText = currentText
    }
}
