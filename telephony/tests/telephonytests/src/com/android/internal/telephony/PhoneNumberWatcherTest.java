/*
 * Copyright (C) 2008 The Android Open Source Project
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
package com.android.internal.telephony;

import android.telephony.PhoneNumberFormattingTextWatcher;
import android.test.AndroidTestCase;
import android.text.Editable;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;

public class PhoneNumberWatcherTest extends AndroidTestCase {
    public void testAppendChars() {
        final String multiChars = "65012345";
        final String formatted1 = "(650) 123-45";
        TextWatcher textWatcher = getTextWatcher();
        SpannableStringBuilder number = new SpannableStringBuilder();
        // Append more than one chars
        textWatcher.beforeTextChanged(number, 0, 0, multiChars.length());
        number.append(multiChars);
        Selection.setSelection(number, number.length());
        textWatcher.onTextChanged(number, 0, 0, number.length());
        textWatcher.afterTextChanged(number);
        assertEquals(formatted1, number.toString());
        assertEquals(formatted1.length(), Selection.getSelectionEnd(number));
        // Append one chars
        final char appendChar = '6';
        final String formatted2 = "(650) 123-456";
        int len = number.length();
        textWatcher.beforeTextChanged(number, number.length(), 0, 1);
        number.append(appendChar);
        Selection.setSelection(number, number.length());
        textWatcher.onTextChanged(number, len, 0, 1);
        textWatcher.afterTextChanged(number);
        assertEquals(formatted2, number.toString());
        assertEquals(formatted2.length(), Selection.getSelectionEnd(number));
    }

    public void testRemoveLastChars() {
        final String init = "65012345678";
        final String result1 = "(650) 123-4567";
        TextWatcher textWatcher = getTextWatcher();
        // Remove the last char.
        SpannableStringBuilder number = new SpannableStringBuilder(init);
        int len = number.length();
        textWatcher.beforeTextChanged(number, len - 1, 1, 0);
        number.delete(len - 1, len);
        Selection.setSelection(number, number.length());
        textWatcher.onTextChanged(number, number.length() - 1, 1, 0);
        textWatcher.afterTextChanged(number);
        assertEquals(result1, number.toString());
        assertEquals(result1.length(), Selection.getSelectionEnd(number));
        // Remove last 5 chars
        final String result2 = "650-123";
        textWatcher.beforeTextChanged(number, number.length() - 4, 4, 0);
        number.delete(number.length() - 5, number.length());
        Selection.setSelection(number, number.length());
        textWatcher.onTextChanged(number, number.length(), 4, 0);
        textWatcher.afterTextChanged(number);
        assertEquals(result2, number.toString());
        assertEquals(result2.length(), Selection.getSelectionEnd(number));
    }

    public void testInsertChars() {
        final String init = "650-23";
        final String expected1 = "650-123";
        TextWatcher textWatcher = getTextWatcher();

        // Insert one char
        SpannableStringBuilder number = new SpannableStringBuilder(init);
        textWatcher.beforeTextChanged(number, 3, 0, 1);
        number.insert(3, "1"); // 6501-23
        Selection.setSelection(number, 4); // make the cursor at right of 1
        textWatcher.onTextChanged(number, 3, 0, 1);
        textWatcher.afterTextChanged(number);
        assertEquals(expected1, number.toString());
        // the cursor should still at the right of '1'
        assertEquals(5, Selection.getSelectionEnd(number));

        // Insert multiple chars
        final String expected2 = "(650) 145-6723";
        textWatcher.beforeTextChanged(number, 5, 0, 4);
        number.insert(5, "4567"); // change to 650-1456723
        Selection.setSelection(number, 9); // the cursor is at the right of '7'.
        textWatcher.onTextChanged(number, 7, 0, 4);
        textWatcher.afterTextChanged(number);
        assertEquals(expected2, number.toString());
        // the cursor should be still at the right of '7'
        assertEquals(12, Selection.getSelectionEnd(number));
    }

    public void testStopFormatting() {
        final String init = "(650) 123";
        final String expected1 = "(650) 123 4";
        TextWatcher textWatcher = getTextWatcher();

        // Append space
        SpannableStringBuilder number = new SpannableStringBuilder(init);
        textWatcher.beforeTextChanged(number, 9, 0, 2);
        number.insert(9, " 4"); // (6501) 23 4
        Selection.setSelection(number, number.length()); // make the cursor at right of 4
        textWatcher.onTextChanged(number, 9, 0, 2);
        textWatcher.afterTextChanged(number);
        assertEquals(expected1, number.toString());
        // the cursor should still at the right of '1'
        assertEquals(expected1.length(), Selection.getSelectionEnd(number));

        // Delete a ')'
        final String expected2 ="(650 123";
        textWatcher = getTextWatcher();
        number = new SpannableStringBuilder(init);
        textWatcher.beforeTextChanged(number, 4, 1, 0);
        number.delete(4, 5); // (6501 23 4
        Selection.setSelection(number, 5); // make the cursor at right of 1
        textWatcher.onTextChanged(number, 4, 1, 0);
        textWatcher.afterTextChanged(number);
        assertEquals(expected2, number.toString());
        // the cursor should still at the right of '1'
        assertEquals(5, Selection.getSelectionEnd(number));

        // Insert a hyphen
        final String expected3 ="(650) 12-3";
        textWatcher = getTextWatcher();
        number = new SpannableStringBuilder(init);
        textWatcher.beforeTextChanged(number, 8, 0, 1);
        number.insert(8, "-"); // (650) 12-3
        Selection.setSelection(number, 9); // make the cursor at right of -
        textWatcher.onTextChanged(number, 8, 0, 1);
        textWatcher.afterTextChanged(number);
        assertEquals(expected3, number.toString());
        // the cursor should still at the right of '-'
        assertEquals(9, Selection.getSelectionEnd(number));
    }

    public void testRestartFormatting() {
        final String init = "(650) 123";
        final String expected1 = "(650) 123 4";
        TextWatcher textWatcher = getTextWatcher();

        // Append space
        SpannableStringBuilder number = new SpannableStringBuilder(init);
        textWatcher.beforeTextChanged(number, 9, 0, 2);
        number.insert(9, " 4"); // (650) 123 4
        Selection.setSelection(number, number.length()); // make the cursor at right of 4
        textWatcher.onTextChanged(number, 9, 0, 2);
        textWatcher.afterTextChanged(number);
        assertEquals(expected1, number.toString());
        // the cursor should still at the right of '4'
        assertEquals(expected1.length(), Selection.getSelectionEnd(number));

        // Clear the current string, and start formatting again.
        int len = number.length();
        textWatcher.beforeTextChanged(number, 0, len, 0);
        number.delete(0, len);
        textWatcher.onTextChanged(number, 0, len, 0);
        textWatcher.afterTextChanged(number);

        final String expected2 = "650-1234";
        number = new SpannableStringBuilder(init);
        textWatcher.beforeTextChanged(number, 9, 0, 1);
        number.insert(9, "4"); // (650) 1234
        Selection.setSelection(number, number.length()); // make the cursor at right of 4
        textWatcher.onTextChanged(number, 9, 0, 1);
        textWatcher.afterTextChanged(number);
        assertEquals(expected2, number.toString());
        // the cursor should still at the right of '4'
        assertEquals(expected2.length(), Selection.getSelectionEnd(number));
    }

    public void testTextChangedByOtherTextWatcher() {
        final TextWatcher cleanupTextWatcher = new TextWatcher() {
            public void afterTextChanged(Editable s) {
                s.clear();
            }

            public void beforeTextChanged(CharSequence s, int start, int count,
                    int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before,
                    int count) {
            }
        };
        final String init = "(650) 123";
        final String expected1 = "";
        TextWatcher textWatcher = getTextWatcher();

        SpannableStringBuilder number = new SpannableStringBuilder(init);
        textWatcher.beforeTextChanged(number, 5, 0, 1);
        number.insert(5, "4"); // (6504) 123
        Selection.setSelection(number, 5); // make the cursor at right of 4
        textWatcher.onTextChanged(number, 5, 0, 1);
        number.setSpan(cleanupTextWatcher, 0, number.length(), 0);
        textWatcher.afterTextChanged(number);
        assertEquals(expected1, number.toString());
    }

    private TextWatcher getTextWatcher() {
        return new PhoneNumberFormattingTextWatcher("US");
    }
}
