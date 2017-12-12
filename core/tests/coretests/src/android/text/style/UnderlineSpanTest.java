/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.text.style;


import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class UnderlineSpanTest {
    private class RedUnderlineSpan extends UnderlineSpan {
        @Override
        public void updateDrawState(TextPaint ds) {
            ds.setUnderlineText(android.graphics.Color.RED, 1.0f);
        }
    }

    // Identical to the normal UnderlineSpan test, except that a subclass of UnderlineSpan is used
    // that draws a red underline. This shouldn't affect width either.
    @Test
    public void testDoesntAffectWidth_colorUnderlineSubclass() {
        // Roboto kerns between "P" and "."
        final SpannableString text = new SpannableString("P.");
        final float origLineWidth = textWidth(text);
        // Underline just the "P".
        text.setSpan(new RedUnderlineSpan(), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        final float underlinedLineWidth = textWidth(text);
        assertEquals(origLineWidth, underlinedLineWidth, 0.0f);
    }

    // Measures the width of some potentially-spanned text, assuming it's not too wide.
    private float textWidth(CharSequence text) {
        final TextPaint tp = new TextPaint();
        tp.setTextSize(100.0f); // Large enough so that the difference in kerning is visible.
        final int largeWidth = 10000; // Enough width so the whole text fits in one line.
        final StaticLayout layout = StaticLayout.Builder.obtain(
                text, 0, text.length(), tp, largeWidth).build();
        return layout.getLineWidth(0);
    }
}
