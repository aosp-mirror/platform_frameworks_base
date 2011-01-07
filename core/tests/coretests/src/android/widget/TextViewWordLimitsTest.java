/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.text.InputType;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableString;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * TextViewPatchTest tests {@link TextView}'s definition of word. Finds and
 * verifies word limits to be in strings containing different kinds of
 * characters.
 */
public class TextViewWordLimitsTest extends AndroidTestCase {

    TextView mTv = null;
    Method mGetWordLimits, mSelectCurrentWord;
    Field mContextMenuTriggeredByKey, mSelectionControllerEnabled;


    /**
     * Sets up common fields used in all test cases.
     * @throws NoSuchFieldException
     * @throws SecurityException
     */
    @Override
    protected void setUp() throws NoSuchMethodException, SecurityException, NoSuchFieldException {
        mTv = new TextView(getContext());
        mTv.setInputType(InputType.TYPE_CLASS_TEXT);

        mGetWordLimits = mTv.getClass().getDeclaredMethod("getWordLimitsAt",
                new Class[] {int.class});
        mGetWordLimits.setAccessible(true);

        mSelectCurrentWord = mTv.getClass().getDeclaredMethod("selectCurrentWord", new Class[] {});
        mSelectCurrentWord.setAccessible(true);

        mContextMenuTriggeredByKey = mTv.getClass().getDeclaredField("mContextMenuTriggeredByKey");
        mContextMenuTriggeredByKey.setAccessible(true);
        mSelectionControllerEnabled = mTv.getClass().getDeclaredField("mSelectionControllerEnabled");
        mSelectionControllerEnabled.setAccessible(true);
    }

    /**
     * Calculate and verify word limits. Depends on the TextView implementation.
     * Uses a private method and internal data representation.
     *
     * @param text         Text to select a word from
     * @param pos          Position to expand word around
     * @param correctStart Correct start position for the word
     * @param correctEnd   Correct end position for the word
     * @throws InvocationTargetException
     * @throws IllegalAccessException
     * @throws IllegalArgumentException
     * @throws InvocationTargetException
     * @throws IllegalAccessException
     */
    private void verifyWordLimits(String text, int pos, int correctStart, int correctEnd)
    throws IllegalArgumentException, IllegalAccessException, InvocationTargetException {
        mTv.setText(text, TextView.BufferType.SPANNABLE);

        long limits = (Long)mGetWordLimits.invoke(mTv, new Object[] {new Integer(pos)});
        int actualStart = (int)(limits >>> 32);
        int actualEnd = (int)(limits & 0x00000000FFFFFFFFL);
        assertEquals(correctStart, actualStart);
        assertEquals(correctEnd, actualEnd);
    }


    private void verifySelectCurrentWord(Spannable text, int selectionStart, int selectionEnd, int correctStart,
            int correctEnd) throws InvocationTargetException, IllegalAccessException {
        mTv.setText(text, TextView.BufferType.SPANNABLE);

        Selection.setSelection((Spannable)mTv.getText(), selectionStart, selectionEnd);
        mContextMenuTriggeredByKey.setBoolean(mTv, true);
        mSelectionControllerEnabled.setBoolean(mTv, true);
        mSelectCurrentWord.invoke(mTv);

        assertEquals(correctStart, mTv.getSelectionStart());
        assertEquals(correctEnd, mTv.getSelectionEnd());
    }


    /**
     * Corner cases for string length.
     */
    @LargeTest
    public void testLengths() throws Exception {
        final String ONE_TWO = "one two";
        final String EMPTY   = "";
        final String TOOLONG = "ThisWordIsTooLongToBeDefinedAsAWordInTheSenseUsedInTextView";

        // Select first word
        verifyWordLimits(ONE_TWO, 0, 0, 3);
        verifyWordLimits(ONE_TWO, 3, 0, 3);

        // Select last word
        verifyWordLimits(ONE_TWO, 4, 4, 7);
        verifyWordLimits(ONE_TWO, 7, 4, 7);

        // Empty string
        verifyWordLimits(EMPTY, 0, -1, -1);

        // Too long word
        verifyWordLimits(TOOLONG, 0, -1, -1);
    }

    /**
     * Unicode classes included.
     */
    @LargeTest
    public void testIncludedClasses() throws Exception {
        final String LOWER          = "njlj";
        final String UPPER          = "NJLJ";
        final String TITLECASE      = "\u01C8\u01CB\u01F2"; // Lj Nj Dz
        final String OTHER          = "\u3042\u3044\u3046"; // Hiragana AIU
        final String MODIFIER       = "\u02C6\u02CA\u02CB"; // Circumflex Acute Grave

        // Each string contains a single valid word
        verifyWordLimits(LOWER, 1, 0, 4);
        verifyWordLimits(UPPER, 1, 0, 4);
        verifyWordLimits(TITLECASE, 1, 0, 3);
        verifyWordLimits(OTHER, 1, 0, 3);
        verifyWordLimits(MODIFIER, 1, 0, 3);
    }

    /**
     * Unicode classes included if combined with a letter.
     */
    @LargeTest
    public void testPartlyIncluded() throws Exception {
        final String NUMBER           = "123";
        final String NUMBER_LOWER     = "1st";
        final String APOSTROPHE       = "''";
        final String APOSTROPHE_LOWER = "'Android's'";

        // Pure decimal number is ignored
        verifyWordLimits(NUMBER, 1, -1, -1);

        // Number with letter is valid
        verifyWordLimits(NUMBER_LOWER, 1, 0, 3);

        // Stand apostrophes are ignore
        verifyWordLimits(APOSTROPHE, 1, -1, -1);

        // Apostrophes are accepted if they are a part of a word
        verifyWordLimits(APOSTROPHE_LOWER, 1, 0, 11);
    }

    /**
     * Unicode classes included if combined with a letter.
     */
    @LargeTest
    public void testFinalSeparator() throws Exception {
        final String PUNCTUATION = "abc, def.";

        // Starting from the comma
        verifyWordLimits(PUNCTUATION, 3, 0, 3);
        verifyWordLimits(PUNCTUATION, 4, 0, 4);

        // Starting from the final period
        verifyWordLimits(PUNCTUATION, 8, 5, 8);
        verifyWordLimits(PUNCTUATION, 9, 5, 9);
    }

    /**
     * Unicode classes other than listed in testIncludedClasses and
     * testPartlyIncluded act as word separators.
     */
    @LargeTest
    public void testNotIncluded() throws Exception {
        // Selection of character classes excluded
        final String MARK_NONSPACING        = "a\u030A";       // a Combining ring above
        final String PUNCTUATION_OPEN_CLOSE = "(c)";           // Parenthesis
        final String PUNCTUATION_DASH       = "non-fiction";   // Hyphen
        final String PUNCTUATION_OTHER      = "b&b";           // Ampersand
        final String SYMBOL_OTHER           = "Android\u00AE"; // Registered
        final String SEPARATOR_SPACE        = "one two";       // Space

        // "a"
        verifyWordLimits(MARK_NONSPACING, 1, 0, 1);

        // "c"
        verifyWordLimits(PUNCTUATION_OPEN_CLOSE, 1, 1, 2);

        // "non-"
        verifyWordLimits(PUNCTUATION_DASH, 3, 0, 3);
        verifyWordLimits(PUNCTUATION_DASH, 4, 4, 11);

        // "b"
        verifyWordLimits(PUNCTUATION_OTHER, 0, 0, 1);
        verifyWordLimits(PUNCTUATION_OTHER, 1, 0, 1);
        verifyWordLimits(PUNCTUATION_OTHER, 2, 0, 3); // & is considered a punctuation sign.
        verifyWordLimits(PUNCTUATION_OTHER, 3, 2, 3);

        // "Android"
        verifyWordLimits(SYMBOL_OTHER, 7, 0, 7);
        verifyWordLimits(SYMBOL_OTHER, 8, -1, -1);

        // "one"
        verifyWordLimits(SEPARATOR_SPACE, 1, 0, 3);
    }

    /**
     * Surrogate characters are treated as their code points.
     */
    @LargeTest
    public void testSurrogate() throws Exception {
        final String SURROGATE_LETTER   = "\uD800\uDC00\uD800\uDC01\uD800\uDC02"; // Linear B AEI
        final String SURROGATE_SYMBOL   = "\uD83D\uDE01\uD83D\uDE02\uD83D\uDE03"; // Three smileys

        // Letter Other is included even when coded as surrogate pairs
        verifyWordLimits(SURROGATE_LETTER, 0, 0, 6);
        verifyWordLimits(SURROGATE_LETTER, 1, 0, 6);
        verifyWordLimits(SURROGATE_LETTER, 2, 0, 6);
        verifyWordLimits(SURROGATE_LETTER, 3, 0, 6);
        verifyWordLimits(SURROGATE_LETTER, 4, 0, 6);
        verifyWordLimits(SURROGATE_LETTER, 5, 0, 6);
        verifyWordLimits(SURROGATE_LETTER, 6, 0, 6);

        // Not included classes are ignored even when coded as surrogate pairs
        verifyWordLimits(SURROGATE_SYMBOL, 0, -1, -1);
        verifyWordLimits(SURROGATE_SYMBOL, 1, -1, -1);
        verifyWordLimits(SURROGATE_SYMBOL, 2, -1, -1);
        verifyWordLimits(SURROGATE_SYMBOL, 3, -1, -1);
        verifyWordLimits(SURROGATE_SYMBOL, 4, -1, -1);
        verifyWordLimits(SURROGATE_SYMBOL, 5, -1, -1);
        verifyWordLimits(SURROGATE_SYMBOL, 6, -1, -1);
    }

    /**
     * Selection is used if present and valid word.
     */
    @LargeTest
    public void testSelectCurrentWord() throws Exception {
        SpannableString textLower       = new SpannableString("first second");
        SpannableString textOther       = new SpannableString("\u3042\3044\3046"); // Hiragana AIU
        SpannableString textDash        = new SpannableString("non-fiction");      // Hyphen
        SpannableString textPunctOther  = new SpannableString("b&b");              // Ampersand
        SpannableString textSymbolOther = new SpannableString("Android\u00AE");    // Registered

        // Valid selection - Letter, Lower
        verifySelectCurrentWord(textLower, 2, 5, 0, 5);

        // Adding the space spreads to the second word
        verifySelectCurrentWord(textLower, 2, 6, 0, 12);

        // Valid selection -- Letter, Other
        verifySelectCurrentWord(textOther, 1, 2, 0, 5);

        // Zero-width selection is interpreted as a cursor and the selection is ignored
        verifySelectCurrentWord(textLower, 2, 2, 0, 5);

        // Hyphen is part of selection
        verifySelectCurrentWord(textDash, 2, 5, 0, 11);

        // Ampersand part of selection or not
        verifySelectCurrentWord(textPunctOther, 1, 2, 0, 3);
        verifySelectCurrentWord(textPunctOther, 1, 3, 0, 3);

        // (R) part of the selection
        verifySelectCurrentWord(textSymbolOther, 2, 7, 0, 7);
        verifySelectCurrentWord(textSymbolOther, 2, 8, 0, 8);
    }
}
