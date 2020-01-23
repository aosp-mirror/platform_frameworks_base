/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.UserHandle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Supplemental tests that cannot be covered by CTS (e.g. due to hidden API dependencies).
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class EditorInfoTest {
    private static final int TEST_USER_ID = 42;

    /**
     * Makes sure that {@code null} {@link EditorInfo#targetInputMethodUser} can be copied via
     * {@link Parcel}.
     */
    @Test
    public void testNullTargetInputMethodUserParcelable() throws Exception {
        final EditorInfo editorInfo = new EditorInfo();
        editorInfo.targetInputMethodUser = null;
        assertNull(cloneViaParcel(editorInfo).targetInputMethodUser);
    }

    /**
     * Makes sure that non-{@code null} {@link EditorInfo#targetInputMethodUser} can be copied via
     * {@link Parcel}.
     */
    @Test
    public void testNonNullTargetInputMethodUserParcelable() throws Exception {
        final EditorInfo editorInfo = new EditorInfo();
        editorInfo.targetInputMethodUser = UserHandle.of(TEST_USER_ID);
        assertEquals(UserHandle.of(TEST_USER_ID), cloneViaParcel(editorInfo).targetInputMethodUser);
    }

    private static EditorInfo cloneViaParcel(EditorInfo original) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return EditorInfo.CREATOR.createFromParcel(parcel);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }

    @Test
    public void testNullTextInputComposeInitialSurroundingText() {
        final Spannable testText = null;
        final EditorInfo editorInfo = new EditorInfo();

        try {
            editorInfo.setInitialSurroundingText(testText);
            fail("Shall not take null input");
        } catch (NullPointerException expected) {
            // Expected behavior, nothing to do.
        }
    }

    @Test
    public void testNonNullTextInputComposeInitialSurroundingText() {
        final Spannable testText = createTestText(/* prependLength= */ 0,
                EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH);
        final EditorInfo editorInfo = new EditorInfo();

        // Cursor at position 0.
        int selectionLength = 0;
        editorInfo.initialSelStart = 0;
        editorInfo.initialSelEnd = editorInfo.initialSelStart + selectionLength;
        int expectedTextBeforeCursorLength = 0;
        int expectedTextAfterCursorLength = testText.length();

        editorInfo.setInitialSurroundingText(testText);

        assertExpectedTextLength(editorInfo, expectedTextBeforeCursorLength, selectionLength,
                expectedTextAfterCursorLength);

        // Cursor at the end.
        editorInfo.initialSelStart = testText.length() - selectionLength;
        editorInfo.initialSelEnd = testText.length();
        expectedTextBeforeCursorLength = testText.length();
        expectedTextAfterCursorLength = 0;

        editorInfo.setInitialSurroundingText(testText);

        assertExpectedTextLength(editorInfo, expectedTextBeforeCursorLength, selectionLength,
                expectedTextAfterCursorLength);

        // Cursor at the middle.
        selectionLength = 2;
        editorInfo.initialSelStart = testText.length() / 2;
        editorInfo.initialSelEnd = editorInfo.initialSelStart + selectionLength;
        expectedTextBeforeCursorLength = editorInfo.initialSelStart;
        expectedTextAfterCursorLength = testText.length() - editorInfo.initialSelEnd;

        editorInfo.setInitialSurroundingText(testText);

        assertExpectedTextLength(editorInfo, expectedTextBeforeCursorLength, selectionLength,
                expectedTextAfterCursorLength);

        // Accidentally swap selection start and end.
        editorInfo.initialSelEnd = testText.length() / 2;
        editorInfo.initialSelStart = editorInfo.initialSelEnd + selectionLength;

        editorInfo.setInitialSurroundingText(testText);

        assertExpectedTextLength(editorInfo, expectedTextBeforeCursorLength, selectionLength,
                expectedTextAfterCursorLength);

        // Invalid cursor position.
        editorInfo.initialSelStart = -1;

        editorInfo.setInitialSurroundingText(testText);

        assertExpectedTextLength(editorInfo,
                /* expectBeforeCursorLength= */null,
                /* expectSelectionLength= */null,
                /* expectAfterCursorLength= */null);
    }

    @Test
    public void testTooLongTextInputComposeInitialSurroundingText() {
        final Spannable testText = createTestText(/* prependLength= */ 0,
                EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH + 2);
        final EditorInfo editorInfo = new EditorInfo();

        // Cursor at position 0.
        int selectionLength = 0;
        editorInfo.initialSelStart = 0;
        editorInfo.initialSelEnd = 0 + selectionLength;
        int expectedTextBeforeCursorLength = 0;
        int expectedTextAfterCursorLength = editorInfo.MEMORY_EFFICIENT_TEXT_LENGTH;

        editorInfo.setInitialSurroundingText(testText);

        assertExpectedTextLength(editorInfo, expectedTextBeforeCursorLength, selectionLength,
                expectedTextAfterCursorLength);

        // Cursor at the end.
        editorInfo.initialSelStart = testText.length() - selectionLength;
        editorInfo.initialSelEnd = testText.length();
        expectedTextBeforeCursorLength = editorInfo.MEMORY_EFFICIENT_TEXT_LENGTH;
        expectedTextAfterCursorLength = 0;

        editorInfo.setInitialSurroundingText(testText);

        assertExpectedTextLength(editorInfo, expectedTextBeforeCursorLength, selectionLength,
                expectedTextAfterCursorLength);

        // Cursor at the middle.
        selectionLength = 2;
        editorInfo.initialSelStart = testText.length() / 2;
        editorInfo.initialSelEnd = editorInfo.initialSelStart + selectionLength;
        expectedTextBeforeCursorLength = Math.min(editorInfo.initialSelStart,
                (int) (0.8 * (EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH - selectionLength)));
        expectedTextAfterCursorLength = EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH
                - expectedTextBeforeCursorLength - selectionLength;

        editorInfo.setInitialSurroundingText(testText);

        assertExpectedTextLength(editorInfo, expectedTextBeforeCursorLength, selectionLength,
                expectedTextAfterCursorLength);

        // Accidentally swap selection start and end.
        editorInfo.initialSelEnd = testText.length() / 2;
        editorInfo.initialSelStart = editorInfo.initialSelEnd + selectionLength;

        editorInfo.setInitialSurroundingText(testText);

        assertExpectedTextLength(editorInfo, expectedTextBeforeCursorLength, selectionLength,
                expectedTextAfterCursorLength);

        // Selection too long, selected text should be dropped.
        selectionLength = EditorInfo.MAX_INITIAL_SELECTION_LENGTH + 1;
        editorInfo.initialSelStart = testText.length() / 2;
        editorInfo.initialSelEnd = editorInfo.initialSelStart + selectionLength;
        expectedTextBeforeCursorLength = Math.min(editorInfo.initialSelStart,
                (int) (0.8 * EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH));
        expectedTextAfterCursorLength = testText.length() - editorInfo.initialSelEnd;

        editorInfo.setInitialSurroundingText(testText);

        assertExpectedTextLength(editorInfo, expectedTextBeforeCursorLength,
                /* expectSelectionLength= */null, expectedTextAfterCursorLength);
    }

    @Test
    public void testTooLongSubTextInputComposeInitialSurroundingText() {
        final int prependLength = 5;
        final int subTextLength = EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH;
        final Spannable fullText = createTestText(prependLength, subTextLength);
        final EditorInfo editorInfo = new EditorInfo();
        // Cursor at the middle.
        final int selectionLength = 2;
        editorInfo.initialSelStart = fullText.length() / 2;
        editorInfo.initialSelEnd = editorInfo.initialSelStart + selectionLength;
        // #prependLength characters will be trimmed out.
        final Spannable expectedTextBeforeCursor = createExpectedText(/* startNumber= */0,
                editorInfo.initialSelStart - prependLength);
        final Spannable expectedSelectedText = createExpectedText(
                editorInfo.initialSelStart - prependLength, selectionLength);
        final Spannable expectedTextAfterCursor = createExpectedText(
                editorInfo.initialSelEnd - prependLength,
                fullText.length() - editorInfo.initialSelEnd);

        editorInfo.setInitialSurroundingSubText(fullText.subSequence(prependLength,
                fullText.length()), prependLength);

        assertTrue(TextUtils.equals(expectedTextBeforeCursor,
                editorInfo.getInitialTextBeforeCursor(editorInfo.MEMORY_EFFICIENT_TEXT_LENGTH,
                        InputConnection.GET_TEXT_WITH_STYLES)));
        assertTrue(TextUtils.equals(expectedSelectedText,
                editorInfo.getInitialSelectedText(InputConnection.GET_TEXT_WITH_STYLES)));
        assertTrue(TextUtils.equals(expectedTextAfterCursor,
                editorInfo.getInitialTextAfterCursor(editorInfo.MEMORY_EFFICIENT_TEXT_LENGTH,
                        InputConnection.GET_TEXT_WITH_STYLES)));
    }

    private static void assertExpectedTextLength(EditorInfo editorInfo,
            @Nullable Integer expectBeforeCursorLength, @Nullable Integer expectSelectionLength,
            @Nullable Integer expectAfterCursorLength) {
        final CharSequence textBeforeCursor =
                editorInfo.getInitialTextBeforeCursor(editorInfo.MEMORY_EFFICIENT_TEXT_LENGTH,
                        InputConnection.GET_TEXT_WITH_STYLES);
        final CharSequence selectedText =
                editorInfo.getInitialSelectedText(InputConnection.GET_TEXT_WITH_STYLES);
        final CharSequence textAfterCursor =
                editorInfo.getInitialTextAfterCursor(editorInfo.MEMORY_EFFICIENT_TEXT_LENGTH,
                        InputConnection.GET_TEXT_WITH_STYLES);

        if (expectBeforeCursorLength == null) {
            assertNull(textBeforeCursor);
        } else {
            assertEquals(expectBeforeCursorLength.intValue(), textBeforeCursor.length());
        }

        if (expectSelectionLength == null) {
            assertNull(selectedText);
        } else {
            assertEquals(expectSelectionLength.intValue(), selectedText.length());
        }

        if (expectAfterCursorLength == null) {
            assertNull(textAfterCursor);
        } else {
            assertEquals(expectAfterCursorLength.intValue(), textAfterCursor.length());
        }
    }

    private static Spannable createTestText(int prependLength, int surroundingLength) {
        final SpannableStringBuilder builder = new SpannableStringBuilder();
        for (int i = 0; i < prependLength; i++) {
            builder.append("a");
        }

        for (int i = 0; i < surroundingLength; i++) {
            builder.append(Integer.toString(i % 10));
        }
        return builder;
    }

    private static Spannable createExpectedText(int startNumber, int length) {
        final SpannableStringBuilder builder = new SpannableStringBuilder();
        for (int i = startNumber; i < startNumber + length; i++) {
            builder.append(Integer.toString(i % 10));
        }
        return builder;
    }
}
