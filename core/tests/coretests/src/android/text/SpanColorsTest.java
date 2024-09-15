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

package android.text;

import static com.google.common.truth.Truth.assertThat;

import android.graphics.Color;
import android.graphics.drawable.ShapeDrawable;
import android.platform.test.annotations.Presubmit;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.UnderlineSpan;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SpanColorsTest {
    private final TextPaint mWorkPaint = new TextPaint();
    private SpanColors mSpanColors;
    private SpannableString mSpannedText;

    @Before
    public void setup() {
        mSpanColors = new SpanColors();
        mSpannedText = new SpannableString("Hello world! This is a test.");
        mSpannedText.setSpan(new ForegroundColorSpan(Color.RED), 0, 4,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        mSpannedText.setSpan(new ForegroundColorSpan(Color.GREEN), 6, 11,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        mSpannedText.setSpan(new UnderlineSpan(), 5, 10, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        mSpannedText.setSpan(new ImageSpan(new ShapeDrawable()), 1, 2,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        mSpannedText.setSpan(new ForegroundColorSpan(Color.BLUE), 12, 16,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
    }

    @Test
    public void testNoColorFound() {
        mSpanColors.init(mWorkPaint, mSpannedText, 25, 30); // Beyond the spans
        assertThat(mSpanColors.getColorAt(27)).isEqualTo(SpanColors.NO_COLOR_FOUND);
    }

    @Test
    public void testSingleColorSpan() {
        mSpanColors.init(mWorkPaint, mSpannedText, 1, 4);
        assertThat(mSpanColors.getColorAt(3)).isEqualTo(Color.RED);
    }

    @Test
    public void testMultipleColorSpans() {
        mSpanColors.init(mWorkPaint, mSpannedText, 0, mSpannedText.length());
        assertThat(mSpanColors.getColorAt(2)).isEqualTo(Color.RED);
        assertThat(mSpanColors.getColorAt(5)).isEqualTo(SpanColors.NO_COLOR_FOUND);
        assertThat(mSpanColors.getColorAt(8)).isEqualTo(Color.GREEN);
        assertThat(mSpanColors.getColorAt(13)).isEqualTo(Color.BLUE);
    }
}
