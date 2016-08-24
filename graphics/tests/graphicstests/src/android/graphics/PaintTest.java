/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.graphics;

import android.test.AndroidTestCase;

public class PaintTest extends AndroidTestCase {
    public void testGetTextRunAdvances() {
        {
            // LTR
            String text = "abcdef";
            assertGetTextRunAdvances(text, 0, text.length(), 0, text.length(), false, true);
            assertGetTextRunAdvances(text, 1, text.length() - 1, 0, text.length(), false, false);
        }
        {
            // RTL
            final String text =
                    "\u0645\u0627\u0020\u0647\u064A\u0020\u0627\u0644\u0634" +
                    "\u0641\u0631\u0629\u0020\u0627\u0644\u0645\u0648\u062D" +
                    "\u062F\u0629\u0020\u064A\u0648\u0646\u064A\u0643\u0648" +
                    "\u062F\u061F";
            assertGetTextRunAdvances(text, 0, text.length(), 0, text.length(), true, true);
            assertGetTextRunAdvances(text, 1, text.length() - 1, 0, text.length(), true, false);
        }
    }

    private void assertGetTextRunAdvances(String str, int start, int end,
            int contextStart, int contextEnd, boolean isRtl, boolean compareWithOtherMethods) {
        Paint p = new Paint();

        final int count = end - start;
        final float[][] advanceArrays = new float[4][count];

        final float advance = p.getTextRunAdvances(str, start, end, contextStart, contextEnd,
                isRtl, advanceArrays[0], 0);

        char chars[] = str.toCharArray();
        final float advance_c = p.getTextRunAdvances(chars, start, count, contextStart,
                contextEnd - contextStart, isRtl, advanceArrays[1], 0);
        assertEquals(advance, advance_c, 1.0f);

        for (int c = 1; c < count; ++c) {
            final float firstPartAdvance = p.getTextRunAdvances(str, start, start + c,
                    contextStart, contextEnd, isRtl, advanceArrays[2], 0);
            final float secondPartAdvance = p.getTextRunAdvances(str, start + c, end,
                    contextStart, contextEnd, isRtl, advanceArrays[2], c);
            assertEquals(advance, firstPartAdvance + secondPartAdvance, 1.0f);


            final float firstPartAdvance_c = p.getTextRunAdvances(chars, start, c,
                    contextStart, contextEnd - contextStart, isRtl, advanceArrays[3], 0);
            final float secondPartAdvance_c = p.getTextRunAdvances(chars, start + c,
                    count - c, contextStart, contextEnd - contextStart, isRtl,
                    advanceArrays[3], c);
            assertEquals(advance, firstPartAdvance_c + secondPartAdvance_c, 1.0f);
            assertEquals(firstPartAdvance, firstPartAdvance_c, 1.0f);
            assertEquals(secondPartAdvance, secondPartAdvance_c, 1.0f);

            for (int i = 1; i < advanceArrays.length; i++) {
                for (int j = 0; j < count; j++) {
                    assertEquals(advanceArrays[0][j], advanceArrays[i][j], 1.0f);
                }
            }

            // Compare results with measureText, getRunAdvance, and getTextWidths.
            if (compareWithOtherMethods && start == contextStart && end == contextEnd) {
                assertEquals(advance, p.measureText(str, start, end), 1.0f);
                assertEquals(advance, p.getRunAdvance(
                        str, start, end, contextStart, contextEnd, isRtl, end), 1.0f);

                final float[] widths = new float[count];
                p.getTextWidths(str, start, end, widths);
                for (int i = 0; i < count; i++) {
                    assertEquals(advanceArrays[0][i], widths[i], 1.0f);
                }
            }
        }
    }

    public void testGetTextRunAdvances_invalid() {
        Paint p = new Paint();
        String text = "test";

        try {
            p.getTextRunAdvances((String)null, 0, 0, 0, 0, false, null, 0);
            fail("Should throw an IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }

        try {
            p.getTextRunAdvances((CharSequence)null, 0, 0, 0, 0, false, null, 0);
            fail("Should throw an IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }

        try {
            p.getTextRunAdvances((char[])null, 0, 0, 0, 0, false, null, 0);
            fail("Should throw an IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }

        try {
            p.getTextRunAdvances(text, 0, text.length(), 0, text.length(), false,
                    new float[text.length() - 1], 0);
            fail("Should throw an IndexOutOfBoundsException.");
        } catch (IndexOutOfBoundsException e) {
        }

        try {
            p.getTextRunAdvances(text, 0, text.length(), 0, text.length(), false,
                    new float[text.length()], 1);
            fail("Should throw an IndexOutOfBoundsException.");
        } catch (IndexOutOfBoundsException e) {
        }

        // 0 > contextStart
        try {
            p.getTextRunAdvances(text, 0, text.length(), -1, text.length(), false, null, 0);
            fail("Should throw an IndexOutOfBoundsException.");
        } catch (IndexOutOfBoundsException e) {
        }

        // contextStart > start
        try {
            p.getTextRunAdvances(text, 0, text.length(), 1, text.length(), false, null, 0);
            fail("Should throw an IndexOutOfBoundsException.");
        } catch (IndexOutOfBoundsException e) {
        }

        // start > end
        try {
            p.getTextRunAdvances(text, 1, 0, 0, text.length(), false, null, 0);
            fail("Should throw an IndexOutOfBoundsException.");
        } catch (IndexOutOfBoundsException e) {
        }

        // end > contextEnd
        try {
            p.getTextRunAdvances(text, 0, text.length(), 0, text.length() - 1, false, null, 0);
            fail("Should throw an IndexOutOfBoundsException.");
        } catch (IndexOutOfBoundsException e) {
        }

        // contextEnd > text.length
        try {
            p.getTextRunAdvances(text, 0, text.length(), 0, text.length() + 1, false, null, 0);
            fail("Should throw an IndexOutOfBoundsException.");
        } catch (IndexOutOfBoundsException e) {
        }
    }

    public void testMeasureTextBidi() {
        Paint p = new Paint();
        {
            String bidiText = "abc \u0644\u063A\u0629 def";
            p.setBidiFlags(Paint.BIDI_LTR);
            float width = p.measureText(bidiText, 0, 4);
            p.setBidiFlags(Paint.BIDI_RTL);
            width += p.measureText(bidiText, 4, 7);
            p.setBidiFlags(Paint.BIDI_LTR);
            width += p.measureText(bidiText, 7, bidiText.length());
            assertEquals(width, p.measureText(bidiText), 1.0f);
        }
        {
            String bidiText = "abc \u0644\u063A\u0629 def";
            p.setBidiFlags(Paint.BIDI_DEFAULT_LTR);
            float width = p.measureText(bidiText, 0, 4);
            width += p.measureText(bidiText, 4, 7);
            width += p.measureText(bidiText, 7, bidiText.length());
            assertEquals(width, p.measureText(bidiText), 1.0f);
        }
        {
            String bidiText = "abc \u0644\u063A\u0629 def";
            p.setBidiFlags(Paint.BIDI_FORCE_LTR);
            float width = p.measureText(bidiText, 0, 4);
            width += p.measureText(bidiText, 4, 7);
            width += p.measureText(bidiText, 7, bidiText.length());
            assertEquals(width, p.measureText(bidiText), 1.0f);
        }
        {
            String bidiText = "\u0644\u063A\u0629 abc \u0644\u063A\u0629";
            p.setBidiFlags(Paint.BIDI_RTL);
            float width = p.measureText(bidiText, 0, 4);
            p.setBidiFlags(Paint.BIDI_LTR);
            width += p.measureText(bidiText, 4, 7);
            p.setBidiFlags(Paint.BIDI_RTL);
            width += p.measureText(bidiText, 7, bidiText.length());
            assertEquals(width, p.measureText(bidiText), 1.0f);
        }
        {
            String bidiText = "\u0644\u063A\u0629 abc \u0644\u063A\u0629";
            p.setBidiFlags(Paint.BIDI_DEFAULT_RTL);
            float width = p.measureText(bidiText, 0, 4);
            width += p.measureText(bidiText, 4, 7);
            width += p.measureText(bidiText, 7, bidiText.length());
            assertEquals(width, p.measureText(bidiText), 1.0f);
        }
        {
            String bidiText = "\u0644\u063A\u0629 abc \u0644\u063A\u0629";
            p.setBidiFlags(Paint.BIDI_FORCE_RTL);
            float width = p.measureText(bidiText, 0, 4);
            width += p.measureText(bidiText, 4, 7);
            width += p.measureText(bidiText, 7, bidiText.length());
            assertEquals(width, p.measureText(bidiText), 1.0f);
        }
    }
}
