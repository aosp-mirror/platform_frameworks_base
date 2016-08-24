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

import android.graphics.Paint;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.SmallTest;

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
                if (testCase.mVariationSelectors.contains(vs)) {
                    assertTrue(signature + " is expected to be true", p.hasGlyph(testString));
                } else {
                    assertFalse(signature + " is expected to be false", p.hasGlyph(testString));
                }
            }
        }
    }
}
