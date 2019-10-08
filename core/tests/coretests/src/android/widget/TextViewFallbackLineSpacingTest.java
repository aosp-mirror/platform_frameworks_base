/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.text.DynamicLayout;
import android.text.FontFallbackSetup;
import android.text.Layout;
import android.text.StaticLayout;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView.BufferType;

import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

/**
 * Parametrized test for TextView#setFallbackLineSpacing.
 */
@MediumTest
@RunWith(Parameterized.class)
public class TextViewFallbackLineSpacingTest {

    @Parameterized.Parameters(name = "{0}")
    public static Collection layouts() {
        return Arrays.asList(new Object[][] {
                // name, enabled, BufferType
                { "Enabled - StaticLayout", true, BufferType.NORMAL},
                { "Disabled - StaticLayout", false, BufferType.NORMAL},
                { "Enabled - DynamicLayout", true, BufferType.EDITABLE},
                { "Disabled - DynamicLayout", false, BufferType.EDITABLE},
        });
    }

    @Rule
    public ActivityTestRule<TextViewActivity> mActivityRule = new ActivityTestRule<>(
            TextViewActivity.class);

    private final boolean mEnabled;
    private final BufferType mBufferType;

    public TextViewFallbackLineSpacingTest(String testName, boolean enabled,
            BufferType bufferType) {
        mEnabled = enabled;
        mBufferType = bufferType;
    }

    @Test
    public void testFallbackLineSpacing() {
        // All glyphs in the fonts are 1em wide.
        final String[] testFontFiles = {
                // ascent == 1em, descent == 2em, only supports 'a' and space
                "ascent1em-descent2em.ttf",
                // ascent == 3em, descent == 4em, only supports 'b'
                "ascent3em-descent4em.ttf"
        };
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font weight='400' style='normal'>ascent1em-descent2em.ttf</font>"
                + "  </family>"
                + "  <family>"
                + "    <font weight='400' style='normal'>ascent3em-descent4em.ttf</font>"
                + "  </family>"
                + "</familyset>";

        try (FontFallbackSetup setup =
                     new FontFallbackSetup("DynamicLayout", testFontFiles, xml)) {
            final Activity activity = mActivityRule.getActivity();
            final TextView textView = new TextView(activity);
            textView.setTypeface(setup.getTypefaceFor("sans-serif"));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 100);
            // This should result in three lines.
            textView.setText("aaaaa aabaa aaaaa", mBufferType);
            textView.setPadding(0, 0, 0, 0);
            textView.setIncludeFontPadding(false);
            textView.setFallbackLineSpacing(mEnabled);

            final int em = (int) Math.ceil(textView.getPaint().measureText("a"));
            final int width = 5 * em;
            final int height = 30 * em; // tall enough to not affect our other measurements
            textView.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            textView.layout(0, 0, width, height);

            final Layout layout = textView.getLayout();
            assertNotNull(layout);
            if (mBufferType == BufferType.NORMAL) {
                assertTrue(layout instanceof StaticLayout);
            } else {
                assertTrue(layout instanceof DynamicLayout);
            }
            assertEquals(3, layout.getLineCount());

            assertEquals(-em, layout.getLineAscent(0));
            assertEquals(2 * em, layout.getLineDescent(0));

            if (mEnabled) {
                // The second line has a 'b', so it needs more ascent and descent.
                assertEquals(-3 * em, layout.getLineAscent(1));
                assertEquals(4 * em, layout.getLineDescent(1));
            } else {
                // old behavior
                assertEquals(-em, layout.getLineAscent(1));
                assertEquals(2 * em, layout.getLineDescent(1));
            }

            assertEquals(-em, layout.getLineAscent(2));
            assertEquals(2 * em, layout.getLineDescent(2));
        }
    }
}
