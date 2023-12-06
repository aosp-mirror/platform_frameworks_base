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

import static org.junit.Assert.assertNotEquals;

import android.test.InstrumentationTestCase;
import android.text.TextUtils;

import androidx.test.filters.SmallTest;

import java.util.Arrays;
import java.util.HashSet;

/**
 * PaintTest tests {@link Paint}.
 */
public class PaintTest extends InstrumentationTestCase {
    private static final String FONT_PATH = "fonts/HintedAdvanceWidthTest-Regular.ttf";

    static void assertEquals(String message, float[] expected, float[] actual) {
        if (expected.length != actual.length) {
            fail(message + " expected array length:<" + expected.length + "> but was:<"
                    + actual.length + ">");
        }
        for (int i = 0; i < expected.length; ++i) {
            if (expected[i] != actual[i]) {
                fail(message + " expected array element[" +i + "]:<" + expected[i] + ">but was:<"
                        + actual[i] + ">");
            }
        }
    }

    static class HintingTestCase {
        public final String mText;
        public final float mTextSize;
        public final float[] mWidthWithoutHinting;
        public final float[] mWidthWithHinting;

        public HintingTestCase(String text, float textSize, float[] widthWithoutHinting,
                               float[] widthWithHinting) {
            mText = text;
            mTextSize = textSize;
            mWidthWithoutHinting = widthWithoutHinting;
            mWidthWithHinting = widthWithHinting;
        }
    }

    // Following test cases are only valid for HintedAdvanceWidthTest-Regular.ttf in assets/fonts.
    HintingTestCase[] HINTING_TESTCASES = {
        new HintingTestCase("H", 11f, new float[] { 7f }, new float[] { 13f }),
        new HintingTestCase("O", 11f, new float[] { 7f }, new float[] { 13f }),

        new HintingTestCase("H", 13f, new float[] { 8f }, new float[] { 14f }),
        new HintingTestCase("O", 13f, new float[] { 9f }, new float[] { 15f }),

        new HintingTestCase("HO", 11f, new float[] { 7f, 7f }, new float[] { 13f, 13f }),
        new HintingTestCase("OH", 11f, new float[] { 7f, 7f }, new float[] { 13f, 13f }),

        new HintingTestCase("HO", 13f, new float[] { 8f, 9f }, new float[] { 14f, 15f }),
        new HintingTestCase("OH", 13f, new float[] { 9f, 8f }, new float[] { 15f, 14f }),
    };

    @SmallTest
    public void testHintingWidth() {
        final Typeface fontTypeface = Typeface.createFromAsset(
                getInstrumentation().getContext().getAssets(), FONT_PATH);
        Paint paint = new Paint();
        paint.setTypeface(fontTypeface);

        for (int i = 0; i < HINTING_TESTCASES.length; ++i) {
            HintingTestCase testCase = HINTING_TESTCASES[i];

            paint.setTextSize(testCase.mTextSize);

            float[] widths = new float[testCase.mText.length()];

            paint.setHinting(Paint.HINTING_OFF);
            paint.getTextWidths(String.valueOf(testCase.mText), widths);
            assertEquals("Text width of '" + testCase.mText + "' without hinting is not expected.",
                    testCase.mWidthWithoutHinting, widths);

            paint.setHinting(Paint.HINTING_ON);
            paint.getTextWidths(String.valueOf(testCase.mText), widths);
            assertEquals("Text width of '" + testCase.mText + "' with hinting is not expected.",
                    testCase.mWidthWithHinting, widths);
        }
    }

    private static class HasGlyphTestCase {
        public final int mBaseCodepoint;
        public final HashSet<Integer> mVariationSelectors;

        public HasGlyphTestCase(int baseCodepoint, Integer[] variationSelectors) {
            mBaseCodepoint = baseCodepoint;
            mVariationSelectors = new HashSet<>(Arrays.asList(variationSelectors));
        }
    }

    private static String codePointsToString(int[] codepoints) {
        StringBuilder sb = new StringBuilder();
        for (int codepoint : codepoints) {
            sb.append(Character.toChars(codepoint));
        }
        return sb.toString();
    }

    public void testHasGlyph_variationSelectors() {
        final Typeface fontTypeface = Typeface.createFromAsset(
                getInstrumentation().getContext().getAssets(), "fonts/hasGlyphTestFont.ttf");
        Paint p = new Paint();
        p.setTypeface(fontTypeface);

        // Usually latin letters U+0061..U+0064 and Mahjong Tiles U+1F000..U+1F003 don't have
        // variation selectors.  This test may fail if system pre-installed fonts have a variation
        // selector support for U+0061..U+0064 and U+1F000..U+1F003.
        HasGlyphTestCase[] HAS_GLYPH_TEST_CASES = {
            new HasGlyphTestCase(0x0061, new Integer[] {0xFE00, 0xE0100, 0xE0101, 0xE0102}),
            new HasGlyphTestCase(0x0062, new Integer[] {0xFE01, 0xE0101, 0xE0102, 0xE0103}),
            new HasGlyphTestCase(0x0063, new Integer[] {}),
            new HasGlyphTestCase(0x0064, new Integer[] {0xFE02, 0xE0102, 0xE0103}),

            new HasGlyphTestCase(0x1F000, new Integer[] {0xFE00, 0xE0100, 0xE0101, 0xE0102}),
            new HasGlyphTestCase(0x1F001, new Integer[] {0xFE01, 0xE0101, 0xE0102, 0xE0103}),
            new HasGlyphTestCase(0x1F002, new Integer[] {}),
            new HasGlyphTestCase(0x1F003, new Integer[] {0xFE02, 0xE0102, 0xE0103}),
        };

        for (HasGlyphTestCase testCase : HAS_GLYPH_TEST_CASES) {
            for (int vs = 0xFE00; vs <= 0xE01EF; ++vs) {
                // Move to variation selector supplements after variation selectors.
                if (vs == 0xFF00) {
                    vs = 0xE0100;
                }
                final String signature =
                        "hasGlyph(U+" + Integer.toHexString(testCase.mBaseCodepoint) +
                        " U+" + Integer.toHexString(vs) + ")";
                final String testString =
                        codePointsToString(new int[] {testCase.mBaseCodepoint, vs});
                if (vs == 0xFE0E // U+FE0E is the text presentation emoji. hasGlyph is expected to
                                 // return true for that variation selector if the font has the base
                                 // glyph.
                             || testCase.mVariationSelectors.contains(vs)) {
                    assertTrue(signature + " is expected to be true", p.hasGlyph(testString));
                } else {
                    assertFalse(signature + " is expected to be false", p.hasGlyph(testString));
                }
            }
        }
    }

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
        final int contextCount = contextEnd - contextStart;
        final float[][] advanceArrays = new float[2][count];
        char chars[] = str.toCharArray();
        final float advance = p.getTextRunAdvances(chars, start, count,
                contextStart, contextCount, isRtl, advanceArrays[0], 0);
        for (int c = 1; c < count; ++c) {
            final float firstPartAdvance = p.getTextRunAdvances(chars, start, c,
                    contextStart, contextCount, isRtl, advanceArrays[1], 0);
            final float secondPartAdvance = p.getTextRunAdvances(chars, start + c, count - c,
                    contextStart, contextCount, isRtl, advanceArrays[1], c);
            assertEquals(advance, firstPartAdvance + secondPartAdvance, 1.0f);

            for (int j = 0; j < count; j++) {
                assertEquals(advanceArrays[0][j], advanceArrays[1][j], 1.0f);
            }


            // Compare results with measureText, getRunAdvance, and getTextWidths.
            if (compareWithOtherMethods && start == contextStart && end == contextEnd) {
                assertEquals(advance, p.measureText(str, start, end), 1.0f);
                assertEquals(advance, p.getRunAdvance(
                        chars, start, count, contextStart, contextCount, isRtl, end), 1.0f);

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
        char[] text = "test".toCharArray();

        try {
            p.getTextRunAdvances((char[])null, 0, 0, 0, 0, false, null, 0);
            fail("Should throw an IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }

        try {
            p.getTextRunAdvances(text, 0, text.length, 0, text.length, false,
                    new float[text.length - 1], 0);
            fail("Should throw an IndexOutOfBoundsException.");
        } catch (IndexOutOfBoundsException e) {
        }

        try {
            p.getTextRunAdvances(text, 0, text.length, 0, text.length, false,
                    new float[text.length], 1);
            fail("Should throw an IndexOutOfBoundsException.");
        } catch (IndexOutOfBoundsException e) {
        }

        // 0 > contextStart
        try {
            p.getTextRunAdvances(text, 0, text.length, -1, text.length, false, null, 0);
            fail("Should throw an IndexOutOfBoundsException.");
        } catch (IndexOutOfBoundsException e) {
        }

        // contextStart > start
        try {
            p.getTextRunAdvances(text, 0, text.length, 1, text.length, false, null, 0);
            fail("Should throw an IndexOutOfBoundsException.");
        } catch (IndexOutOfBoundsException e) {
        }

        // end > contextEnd
        try {
            p.getTextRunAdvances(text, 0, text.length, 0, text.length - 1, false, null, 0);
            fail("Should throw an IndexOutOfBoundsException.");
        } catch (IndexOutOfBoundsException e) {
        }

        // contextEnd > text.length
        try {
            p.getTextRunAdvances(text, 0, text.length, 0, text.length + 1, false, null, 0);
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

    public void testSetGetWordSpacing() {
        Paint p = new Paint();
        assertEquals(0.0f, p.getWordSpacing());  // The default value should be 0.
        p.setWordSpacing(1.0f);
        assertEquals(1.0f, p.getWordSpacing());
        p.setWordSpacing(-2.0f);
        assertEquals(-2.0f, p.getWordSpacing());
    }

    public void testGetUnderlinePositionAndThickness() {
        final Typeface fontTypeface = Typeface.createFromAsset(
                getInstrumentation().getContext().getAssets(), "fonts/underlineTestFont.ttf");
        final Paint p = new Paint();
        final int textSize = 100;
        p.setTextSize(textSize);

        final float origPosition = p.getUnderlinePosition();
        final float origThickness = p.getUnderlineThickness();

        p.setTypeface(fontTypeface);
        assertNotEquals(origPosition, p.getUnderlinePosition());
        assertNotEquals(origThickness, p.getUnderlineThickness());

        //    -200 (underlinePosition in 'post' table, negative means below the baseline)
        //    ÷ 1000 (unitsPerEm in 'head' table)
        //    × 100 (text size)
        //    × -1 (negated, since we consider below the baseline positive)
        //    = 20
        assertEquals(20.0f, p.getUnderlinePosition(), 0.5f);
        //    300 (underlineThickness in 'post' table)
        //    ÷ 1000 (unitsPerEm in 'head' table)
        //    × 100 (text size)
        //    = 30
        assertEquals(30.0f, p.getUnderlineThickness(), 0.5f);
    }

    private int getClusterCount(Paint p, String text) {
        Paint.RunInfo runInfo = new Paint.RunInfo();
        p.getRunCharacterAdvance(text, 0, text.length(), 0, text.length(), false, 0, null, 0, null,
                runInfo);
        int ccByString = runInfo.getClusterCount();
        runInfo.setClusterCount(0);
        char[] buf = new char[text.length()];
        TextUtils.getChars(text, 0, text.length(), buf, 0);
        p.getRunCharacterAdvance(buf, 0, buf.length, 0, buf.length, false, 0, null, 0, null,
                runInfo);
        int ccByChars = runInfo.getClusterCount();
        assertEquals(ccByChars, ccByString);
        return ccByChars;
    }

    public void testCluster() {
        final Paint p = new Paint();
        p.setTextSize(100);

        // Regular String
        assertEquals(1, getClusterCount(p, "A"));
        assertEquals(2, getClusterCount(p, "AB"));

        // Ligature is in the same cluster
        assertEquals(1, getClusterCount(p, "fi"));  // Ligature
        p.setFontFeatureSettings("'liga' off");
        assertEquals(2, getClusterCount(p, "fi"));  // Ligature is disabled
        p.setFontFeatureSettings("");

        // Combining character
        assertEquals(1, getClusterCount(p, "\u0061\u0300"));  // A + COMBINING GRAVE ACCENT

        // BiDi
        final String rtlStr = "\u05D0\u05D1\u05D2";
        final String ltrStr = "abc";
        assertEquals(3, getClusterCount(p, rtlStr));
        assertEquals(6, getClusterCount(p, rtlStr + ltrStr));
        assertEquals(9, getClusterCount(p, ltrStr + rtlStr + ltrStr));
    }
}
