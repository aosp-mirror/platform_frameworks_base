/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.text;

import android.text.Layout.Alignment;
import junit.framework.TestCase;

/**
 * Tests for text measuring methods of StaticLayout.
 */
public class StaticLayoutTextMeasuringTest extends TestCase {
    private static final float SPACE_MULTI = 1.0f;
    private static final float SPACE_ADD = 0.0f;
    private static final int DEFAULT_OUTER_WIDTH = 150;
    private static final Alignment DEFAULT_ALIGN = Alignment.ALIGN_LEFT;

    private TextPaint mDefaultPaint;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (mDefaultPaint == null) {
            mDefaultPaint = new TextPaint();
        }
    }

    public void testGetPrimaryHorizontal_zwnbsp() {
        // a, ZERO WIDTH NO-BREAK SPACE
        String testString = "a\uFEFF";
        StaticLayout layout = new StaticLayout(testString, mDefaultPaint,
                DEFAULT_OUTER_WIDTH, DEFAULT_ALIGN, SPACE_MULTI, SPACE_ADD, true);

        assertEquals(0.0f, layout.getPrimaryHorizontal(0));
        assertEquals(layout.getPrimaryHorizontal(2), layout.getPrimaryHorizontal(1));
    }

    public void testGetPrimaryHorizontal_devanagari() {
        // DEVANAGARI LETTER KA, DEVANAGARI VOWEL SIGN AA
        String testString = "\u0915\u093E";
        StaticLayout layout = new StaticLayout(testString, mDefaultPaint,
                DEFAULT_OUTER_WIDTH, DEFAULT_ALIGN, SPACE_MULTI, SPACE_ADD, true);

        assertEquals(0.0f, layout.getPrimaryHorizontal(0));
        assertEquals(layout.getPrimaryHorizontal(2), layout.getPrimaryHorizontal(1));
    }

    public void testGetPrimaryHorizontal_flagEmoji() {
        // REGIONAL INDICATOR SYMBOL LETTER U, REGIONAL INDICATOR SYMBOL LETTER S, REGIONAL
        // INDICATOR SYMBOL LETTER Z
        // First two code points (U and S) forms a US flag.
        String testString = "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDFF";
        StaticLayout layout = new StaticLayout(testString, mDefaultPaint,
                DEFAULT_OUTER_WIDTH, DEFAULT_ALIGN, SPACE_MULTI, SPACE_ADD, true);

        assertEquals(0.0f, layout.getPrimaryHorizontal(0));
        assertEquals(layout.getPrimaryHorizontal(4), layout.getPrimaryHorizontal(1));
        assertEquals(layout.getPrimaryHorizontal(4), layout.getPrimaryHorizontal(2));
        assertEquals(layout.getPrimaryHorizontal(4), layout.getPrimaryHorizontal(3));

        assertTrue(layout.getPrimaryHorizontal(6) > layout.getPrimaryHorizontal(4));
        assertEquals(layout.getPrimaryHorizontal(6), layout.getPrimaryHorizontal(5));
    }
}
