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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;

import android.annotation.Nullable;
import android.graphics.BlurMaskFilter;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.MaskFilterSpan;
import android.text.style.UnderlineSpan;
import android.util.StringBuilderPrinter;
import android.view.MotionEvent;
import android.view.autofill.AutofillId;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Supplemental tests that cannot be covered by CTS (e.g. due to hidden API dependencies).
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class EditorInfoTest {
    private static final int TEST_USER_ID = 42;
    private static final int LONG_EXP_TEXT_LENGTH = EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH * 2;

    private static final EditorInfo TEST_EDITOR_INFO = new EditorInfo();

    static {
        TEST_EDITOR_INFO.inputType = InputType.TYPE_CLASS_TEXT; // 0x1
        TEST_EDITOR_INFO.imeOptions = EditorInfo.IME_ACTION_GO; // 0x2
        TEST_EDITOR_INFO.privateImeOptions = "testOptions";
        TEST_EDITOR_INFO.initialSelStart = 0;
        TEST_EDITOR_INFO.initialSelEnd = 1;
        TEST_EDITOR_INFO.initialCapsMode = TextUtils.CAP_MODE_CHARACTERS; // 0x1000
        TEST_EDITOR_INFO.hintText = "testHintText";
        TEST_EDITOR_INFO.label = "testLabel";
        TEST_EDITOR_INFO.packageName = "android.view.inputmethod";
        TEST_EDITOR_INFO.fieldId = 0;
        TEST_EDITOR_INFO.autofillId = AutofillId.NO_AUTOFILL_ID;
        TEST_EDITOR_INFO.fieldName = "testField";
        TEST_EDITOR_INFO.extras = new Bundle();
        TEST_EDITOR_INFO.extras.putString("testKey", "testValue");
        TEST_EDITOR_INFO.hintLocales = LocaleList.forLanguageTags("en,de,ua");
        TEST_EDITOR_INFO.contentMimeTypes = new String[] {"image/png"};
        TEST_EDITOR_INFO.targetInputMethodUser = UserHandle.of(TEST_USER_ID);
    }

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
    public void setInitialText_nullInputText_throwsException() {
        final CharSequence testText = null;
        final EditorInfo editorInfo = new EditorInfo();

        try {
            editorInfo.setInitialSurroundingText(testText);
            fail("Shall not take null input");
        } catch (NullPointerException expected) {
            // Expected behavior, nothing to do.
        }
    }

    @Test
    public void setInitialText_cursorAtHead_dividesByCursorPosition() {
        final CharSequence testText = createTestText(EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH);

        final EditorInfo editorInfo = new EditorInfo();
        final int selectionLength = 0;
        editorInfo.initialSelStart = 0;
        editorInfo.initialSelEnd = editorInfo.initialSelStart + selectionLength;
        final int expectedTextBeforeCursorLength = 0;
        final int expectedTextAfterCursorLength = testText.length();
        final SurroundingText expectedSurroundingText =
                new SurroundingText(testText, editorInfo.initialSelStart,
                        editorInfo.initialSelEnd, 0);


        editorInfo.setInitialSurroundingText(testText);

        assertExpectedTextLength(editorInfo, expectedTextBeforeCursorLength, selectionLength,
                expectedTextAfterCursorLength, expectedSurroundingText);
    }

    @Test
    public void setInitialText_cursorAtTail_dividesByCursorPosition() {
        final CharSequence testText = createTestText(EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH);
        final EditorInfo editorInfo = new EditorInfo();
        final int selectionLength = 0;
        editorInfo.initialSelStart = testText.length() - selectionLength;
        editorInfo.initialSelEnd = testText.length();
        final int expectedTextBeforeCursorLength = testText.length();
        final int expectedTextAfterCursorLength = 0;
        final SurroundingText expectedSurroundingText =
                new SurroundingText(testText, editorInfo.initialSelStart,
                        editorInfo.initialSelEnd, 0);

        editorInfo.setInitialSurroundingText(testText);

        assertExpectedTextLength(editorInfo, expectedTextBeforeCursorLength, selectionLength,
                expectedTextAfterCursorLength, expectedSurroundingText);
    }

    @Test
    public void setInitialText_cursorAtMiddle_dividesByCursorPosition() {
        final CharSequence testText = createTestText(EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH);
        final EditorInfo editorInfo = new EditorInfo();
        final int selectionLength = 2;
        editorInfo.initialSelStart = testText.length() / 2;
        editorInfo.initialSelEnd = editorInfo.initialSelStart + selectionLength;
        final int expectedTextBeforeCursorLength = editorInfo.initialSelStart;
        final int expectedTextAfterCursorLength = testText.length() - editorInfo.initialSelEnd;
        final SurroundingText expectedSurroundingText =
                new SurroundingText(testText, editorInfo.initialSelStart,
                        editorInfo.initialSelEnd, 0);

        editorInfo.setInitialSurroundingText(testText);

        assertExpectedTextLength(editorInfo, expectedTextBeforeCursorLength, selectionLength,
                expectedTextAfterCursorLength, expectedSurroundingText);
    }

    @Test
    public void setInitialText_incorrectCursorOrder_correctsThenDivide() {
        final CharSequence testText = createTestText(EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH);
        final EditorInfo editorInfo = new EditorInfo();
        final int selectionLength = 2;
        editorInfo.initialSelEnd = testText.length() / 2;
        editorInfo.initialSelStart = editorInfo.initialSelEnd + selectionLength;
        final int expectedTextBeforeCursorLength = testText.length() / 2;
        final int expectedTextAfterCursorLength = testText.length() - testText.length() / 2
                - selectionLength;
        final SurroundingText expectedSurroundingText =
                new SurroundingText(testText,
                        editorInfo.initialSelEnd,
                        editorInfo.initialSelStart , 0);
        editorInfo.setInitialSurroundingText(testText);

        assertExpectedTextLength(editorInfo, expectedTextBeforeCursorLength, selectionLength,
                expectedTextAfterCursorLength, expectedSurroundingText);
    }

    @Test
    public void setInitialText_invalidCursorPosition_returnsNull() {
        final CharSequence testText = createTestText(EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH);
        final EditorInfo editorInfo = new EditorInfo();
        editorInfo.initialSelStart = -1;

        editorInfo.setInitialSurroundingText(testText);

        assertExpectedTextLength(editorInfo,
                /* expectBeforeCursorLength= */ null,
                /* expectSelectionLength= */ null,
                /* expectAfterCursorLength= */   null,
                /* expectSurroundingText= */ null);
    }

    @Test
    public void setOverSizeInitialText_cursorAtMiddle_dividesProportionately() {
        final CharSequence testText = createTestText(EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH + 2);
        final EditorInfo editorInfo = new EditorInfo();
        final int selectionLength = 2;
        editorInfo.initialSelStart = testText.length() / 2;
        editorInfo.initialSelEnd = editorInfo.initialSelStart + selectionLength;
        final int expectedTextBeforeCursorLength = Math.min(editorInfo.initialSelStart,
                (int) (0.8 * (EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH - selectionLength)));
        final int expectedTextAfterCursorLength = EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH
                - expectedTextBeforeCursorLength - selectionLength;
        final int offset = editorInfo.initialSelStart - expectedTextBeforeCursorLength;
        final CharSequence beforeCursor = testText.subSequence(
                offset, offset + expectedTextBeforeCursorLength);
        final CharSequence afterCursor = testText.subSequence(editorInfo.initialSelEnd,
                editorInfo.initialSelEnd + expectedTextAfterCursorLength);
        final CharSequence selectedText = testText.subSequence(editorInfo.initialSelStart,
                editorInfo.initialSelEnd);

        final SurroundingText expectedSurroundingText =
                new SurroundingText(TextUtils.concat(beforeCursor, selectedText, afterCursor),
                        expectedTextBeforeCursorLength,
                        expectedTextBeforeCursorLength + selectionLength, offset);

        editorInfo.setInitialSurroundingText(testText);

        assertExpectedTextLength(editorInfo, expectedTextBeforeCursorLength, selectionLength,
                expectedTextAfterCursorLength, expectedSurroundingText);
    }

    @Test
    public void setOverSizeInitialText_overSizeSelection_dropsSelection() {
        final CharSequence testText = createTestText(EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH + 2);
        final EditorInfo editorInfo = new EditorInfo();
        final int selectionLength = EditorInfo.MAX_INITIAL_SELECTION_LENGTH + 1;
        editorInfo.initialSelStart = testText.length() / 2;
        editorInfo.initialSelEnd = editorInfo.initialSelStart + selectionLength;
        final int expectedTextBeforeCursorLength = Math.min(editorInfo.initialSelStart,
                (int) (0.8 * EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH));
        final int expectedTextAfterCursorLength = testText.length() - editorInfo.initialSelEnd;
        final CharSequence before = testText.subSequence(
                editorInfo.initialSelStart  - expectedTextBeforeCursorLength,
                expectedTextBeforeCursorLength);
        final CharSequence after = testText.subSequence(editorInfo.initialSelEnd,
                editorInfo.initialSelEnd + expectedTextAfterCursorLength);
        final SurroundingText expectedSurroundingText =
                new SurroundingText(TextUtils.concat(before, after),
                        expectedTextBeforeCursorLength,
                        expectedTextBeforeCursorLength,
                        editorInfo.initialSelStart - expectedTextBeforeCursorLength);

        editorInfo.setInitialSurroundingText(testText);

        assertExpectedTextLength(editorInfo, expectedTextBeforeCursorLength,
                /* expectSelectionLength= */null, expectedTextAfterCursorLength,
                expectedSurroundingText);
    }

    @Test
    public void setInitialSubText_trimmedSubText_dividesByOriginalCursorPosition() {
        final String prefixString = "prefix";
        final CharSequence subText = createTestText(EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH);
        final CharSequence originalText = TextUtils.concat(prefixString, subText);
        final EditorInfo editorInfo = new EditorInfo();
        final int selLength = 2;
        editorInfo.initialSelStart = originalText.length() / 2;
        editorInfo.initialSelEnd = editorInfo.initialSelStart + selLength;
        final CharSequence expectedTextBeforeCursor = createExpectedText(/* startNumber= */0,
                editorInfo.initialSelStart - prefixString.length());
        final CharSequence expectedSelectedText = createExpectedText(
                editorInfo.initialSelStart - prefixString.length(), selLength);
        final CharSequence expectedTextAfterCursor = createExpectedText(
                editorInfo.initialSelEnd - prefixString.length(),
                originalText.length() - editorInfo.initialSelEnd);

        editorInfo.setInitialSurroundingSubText(subText, prefixString.length());

        assertTrue(TextUtils.equals(expectedTextBeforeCursor,
                editorInfo.getInitialTextBeforeCursor(LONG_EXP_TEXT_LENGTH, anyInt())));
        assertTrue(TextUtils.equals(expectedSelectedText,
                editorInfo.getInitialSelectedText(anyInt())));
        assertTrue(TextUtils.equals(expectedTextAfterCursor,
                editorInfo.getInitialTextAfterCursor(LONG_EXP_TEXT_LENGTH, anyInt())));
    }

    @Test
    public void initialSurroundingText_wrapIntoParcel_staysIntact() {
        // EditorInfo.InitialSurroundingText is not visible to test class. But all its key elements
        // must stay intact for its getter methods to return correct value and it will be wrapped
        // into its outer class for parcel transfer, therefore we can verify its parcel
        // wrapping/unwrapping logic through its outer class.
        final CharSequence testText = createTestText(EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH);
        final EditorInfo sourceEditorInfo = new EditorInfo();
        final int selectionLength = 2;
        sourceEditorInfo.initialSelStart = testText.length() / 2;
        sourceEditorInfo.initialSelEnd = sourceEditorInfo.initialSelStart + selectionLength;
        sourceEditorInfo.setInitialSurroundingText(testText);

        final EditorInfo targetEditorInfo = cloneViaParcel(sourceEditorInfo);

        assertTrue(TextUtils.equals(
                sourceEditorInfo.getInitialTextBeforeCursor(LONG_EXP_TEXT_LENGTH,
                        InputConnection.GET_TEXT_WITH_STYLES),
                targetEditorInfo.getInitialTextBeforeCursor(LONG_EXP_TEXT_LENGTH,
                        InputConnection.GET_TEXT_WITH_STYLES)));
        assertTrue(TextUtils.equals(
                sourceEditorInfo.getInitialSelectedText(InputConnection.GET_TEXT_WITH_STYLES),
                targetEditorInfo.getInitialSelectedText(InputConnection.GET_TEXT_WITH_STYLES)));
        assertTrue(TextUtils.equals(
                sourceEditorInfo.getInitialTextAfterCursor(LONG_EXP_TEXT_LENGTH,
                        InputConnection.GET_TEXT_WITH_STYLES),
                targetEditorInfo.getInitialTextAfterCursor(LONG_EXP_TEXT_LENGTH,
                        InputConnection.GET_TEXT_WITH_STYLES)));

        final SurroundingText sourceSurroundingText = sourceEditorInfo.getInitialSurroundingText(
                LONG_EXP_TEXT_LENGTH, LONG_EXP_TEXT_LENGTH, InputConnection.GET_TEXT_WITH_STYLES);
        final SurroundingText targetSurroundingText = targetEditorInfo.getInitialSurroundingText(
                LONG_EXP_TEXT_LENGTH, LONG_EXP_TEXT_LENGTH, InputConnection.GET_TEXT_WITH_STYLES);

        assertTrue(TextUtils.equals(sourceSurroundingText.getText(),
                targetSurroundingText.getText()));
        assertEquals(sourceSurroundingText.getSelectionStart(),
                targetSurroundingText.getSelectionStart());
        assertEquals(sourceSurroundingText.getSelectionEnd(),
                targetSurroundingText.getSelectionEnd());
        assertEquals(sourceSurroundingText.getOffset(), targetSurroundingText.getOffset());
    }

    @Test
    public void surroundingTextRetrieval_writeToParcel_noException() {
        StringBuilder sb = new StringBuilder("abcdefg");
        Parcel parcel = Parcel.obtain();
        EditorInfo editorInfo = new EditorInfo();
        editorInfo.initialSelStart = 2;
        editorInfo.initialSelEnd = 5;
        editorInfo.inputType = EditorInfo.TYPE_CLASS_TEXT;

        editorInfo.setInitialSurroundingText(sb);
        sb.setLength(/* newLength= */ 0);
        editorInfo.writeToParcel(parcel, /* flags= */ 0);

        editorInfo.getInitialTextBeforeCursor(/* length= */ 60, /* flags= */ 1);
    }

    @Test
    public void testSpanAfterSurroundingTextRetrieval() {
        final int flags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE;
        final SpannableStringBuilder sb =
                new SpannableStringBuilder("ParcelableSpan and non-ParcelableSpan test");
        final int parcelableStart = 0;
        final int parcelableEnd = 14;
        final int nonParcelableStart = 19;
        final int nonParcelableEnd = 37;
        final UnderlineSpan parcelableSpan = new UnderlineSpan();
        final MaskFilterSpan nonParcelableSpan =
                new MaskFilterSpan(new BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL));

        // Set spans
        sb.setSpan(parcelableSpan, parcelableStart, parcelableEnd, flags);
        sb.setSpan(nonParcelableSpan, nonParcelableStart, nonParcelableEnd, flags);

        Object[] spansBefore = sb.getSpans(/* queryStart= */ 0, sb.length(), Object.class);
        Object[] parcelableSpanBefore = sb.getSpans(parcelableStart, parcelableEnd, Object.class);

        // Verify the original spans length is 2, include ParcelableSpan and non-ParcelableSpan.
        assertNotNull(spansBefore);
        assertEquals(2, spansBefore.length);

        // Set initial surrounding text then retrieve the text.
        EditorInfo editorInfo = new EditorInfo();
        editorInfo.initialSelStart = sb.length();
        editorInfo.initialSelEnd = sb.length();
        editorInfo.inputType = EditorInfo.TYPE_CLASS_TEXT;
        editorInfo.setInitialSurroundingText(sb);
        SpannableString textBeforeCursor =
                (SpannableString) editorInfo.getInitialTextBeforeCursor(
                        /* length= */ 60, /* flags= */ 1);

        Object[] spansAfter =
                textBeforeCursor.getSpans(/* queryStart= */ 0, sb.length(), Object.class);
        Object[] parcelableSpanAfter =
                textBeforeCursor.getSpans(parcelableStart, parcelableEnd, Object.class);
        Object[] nonParcelableSpanAfter =
                textBeforeCursor.getSpans(nonParcelableStart, nonParcelableEnd, Object.class);

        // Verify only remain ParcelableSpan and it's different from the original Span instance.
        assertNotNull(spansAfter);
        assertEquals(1, spansAfter.length);
        assertEquals(1, parcelableSpanAfter.length);
        assertEquals(0, nonParcelableSpanAfter.length);
        assertNotEquals(parcelableSpanBefore, parcelableSpanAfter);
    }

    private static void assertExpectedTextLength(EditorInfo editorInfo,
            @Nullable Integer expectBeforeCursorLength, @Nullable Integer expectSelectionLength,
            @Nullable Integer expectAfterCursorLength,
            @Nullable SurroundingText expectSurroundingText) {
        final CharSequence textBeforeCursor =
                editorInfo.getInitialTextBeforeCursor(LONG_EXP_TEXT_LENGTH,
                        InputConnection.GET_TEXT_WITH_STYLES);
        final CharSequence selectedText =
                editorInfo.getInitialSelectedText(InputConnection.GET_TEXT_WITH_STYLES);
        final CharSequence textAfterCursor =
                editorInfo.getInitialTextAfterCursor(LONG_EXP_TEXT_LENGTH,
                        InputConnection.GET_TEXT_WITH_STYLES);
        final SurroundingText surroundingText = editorInfo.getInitialSurroundingText(
                LONG_EXP_TEXT_LENGTH,
                LONG_EXP_TEXT_LENGTH,
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

        if (expectSurroundingText == null) {
            assertNull(surroundingText);
        } else {
            assertTrue(TextUtils.equals(
                    expectSurroundingText.getText(), surroundingText.getText()));
            assertEquals(expectSurroundingText.getSelectionStart(),
                    surroundingText.getSelectionStart());
            assertEquals(expectSurroundingText.getSelectionEnd(),
                    surroundingText.getSelectionEnd());
            assertEquals(expectSurroundingText.getOffset(), surroundingText.getOffset());
        }
    }

    private static CharSequence createTestText(int surroundingLength) {
        final SpannableStringBuilder builder = new SpannableStringBuilder();
        for (int i = 0; i < surroundingLength; i++) {
            builder.append(Integer.toString(i % 10));
        }
        return builder;
    }

    private static CharSequence createExpectedText(int startNumber, int length) {
        final SpannableStringBuilder builder = new SpannableStringBuilder();
        for (int i = startNumber; i < startNumber + length; i++) {
            builder.append(Integer.toString(i % 10));
        }
        return builder;
    }

    @Test
    public void testDump_empty() {
        final EditorInfo info = new EditorInfo();
        final StringBuilder sb = new StringBuilder();
        info.dump(new StringBuilderPrinter(sb), "prefix: ");
        assertThat(sb.toString()).isEqualTo(
                "prefix: inputType=0x0 imeOptions=0x0 privateImeOptions=null\n"
                + "prefix: actionLabel=null actionId=0\n"
                + "prefix: initialSelStart=-1 initialSelEnd=-1 initialToolType=0"
                        + " initialCapsMode=0x0\n"
                + "prefix: hintText=null label=null\n"
                + "prefix: packageName=null autofillId=null fieldId=0 fieldName=null\n"
                + "prefix: extras=null\n"
                + "prefix: hintLocales=null\n"
                + "prefix: supportedHandwritingGestureTypes=(none)\n"
                + "prefix: supportedHandwritingGesturePreviewTypes=(none)\n"
                + "prefix: isStylusHandwritingEnabled=false\n"
                + "prefix: contentMimeTypes=null\n");
    }

    @Test
    public void testDump_filled() {
        final EditorInfo info = new EditorInfo();
        info.inputType = InputType.TYPE_CLASS_TEXT; // 0x1
        info.imeOptions = EditorInfo.IME_ACTION_GO; // 0x2
        info.privateImeOptions = "testOptions";
        info.initialSelStart = 0;
        info.initialSelEnd = 1;
        info.initialCapsMode = TextUtils.CAP_MODE_CHARACTERS; // 0x1000
        info.hintText = "testHintText";
        info.label = "testLabel";
        info.setInitialToolType(MotionEvent.TOOL_TYPE_STYLUS);
        info.setSupportedHandwritingGestures(Arrays.asList(SelectGesture.class));
        info.setSupportedHandwritingGesturePreviews(
                Stream.of(SelectGesture.class).collect(Collectors.toSet()));
        if (Flags.editorinfoHandwritingEnabled()) {
            info.setStylusHandwritingEnabled(true);
        }
        info.packageName = "android.view.inputmethod";
        info.autofillId = new AutofillId(123);
        info.fieldId = 456;
        info.fieldName = "testField";
        info.extras = new Bundle();
        info.extras.putString("testKey", "testValue");
        info.hintLocales = LocaleList.forLanguageTags("en,es,zh");
        info.contentMimeTypes = new String[] {"image/png"};
        info.targetInputMethodUser = UserHandle.of(10);
        final StringBuilder sb = new StringBuilder();
        info.dump(new StringBuilderPrinter(sb), "prefix2: ");
        assertThat(sb.toString()).isEqualTo(
                "prefix2: inputType=0x1 imeOptions=0x2 privateImeOptions=testOptions\n"
                        + "prefix2: actionLabel=null actionId=0\n"
                        + "prefix2: initialSelStart=0 initialSelEnd=1 initialToolType=2"
                                + " initialCapsMode=0x1000\n"
                        + "prefix2: hintText=testHintText label=testLabel\n"
                        + "prefix2: packageName=android.view.inputmethod autofillId=123"
                        + " fieldId=456 fieldName=testField\n"
                        + "prefix2: extras=Bundle[{testKey=testValue}]\n"
                        + "prefix2: hintLocales=[en,es,zh]\n"
                        + "prefix2: supportedHandwritingGestureTypes=SELECT\n"
                        + "prefix2: supportedHandwritingGesturePreviewTypes=SELECT\n"
                        + "prefix2: isStylusHandwritingEnabled="
                                + Flags.editorinfoHandwritingEnabled() + "\n"
                        + "prefix2: contentMimeTypes=[image/png]\n"
                        + "prefix2: targetInputMethodUserId=10\n");
    }

    @Test
    public void testDump_noDumpExtras() {
        final EditorInfo info = new EditorInfo();
        info.extras = new Bundle();
        info.extras.putString("testKey", "testValue");
        final StringBuilder sb = new StringBuilder();
        info.dump(new StringBuilderPrinter(sb), "prefix: ", false /* dumpExtras */);
        assertThat(sb.toString()).isEqualTo(
                "prefix: inputType=0x0 imeOptions=0x0 privateImeOptions=null\n"
                        + "prefix: actionLabel=null actionId=0\n"
                        + "prefix: initialSelStart=-1 initialSelEnd=-1 initialToolType=0"
                                + " initialCapsMode=0x0\n"
                        + "prefix: hintText=null label=null\n"
                        + "prefix: packageName=null autofillId=null fieldId=0 fieldName=null\n"
                        + "prefix: hintLocales=null\n"
                        + "prefix: supportedHandwritingGestureTypes=(none)\n"
                        + "prefix: supportedHandwritingGesturePreviewTypes=(none)\n"
                        + "prefix: isStylusHandwritingEnabled=false\n"
                        + "prefix: contentMimeTypes=null\n");
    }

    @Test
    public void testKindofEqualsAfterCopyInternal() {
        final EditorInfo infoCopy = TEST_EDITOR_INFO.createCopyInternal();
        assertTrue(TEST_EDITOR_INFO.kindofEquals(infoCopy));
    }

    @Test
    public void testKindofEqualsAfterCloneViaParcel() {
        // This test demonstrates a false negative case when an EditorInfo is
        // created from a Parcel and its extras are still parcelled, which in turn
        // runs into the edge case in Bundle.kindofEquals
        final EditorInfo infoCopy = cloneViaParcel(TEST_EDITOR_INFO);
        assertFalse(TEST_EDITOR_INFO.kindofEquals(infoCopy));
    }

    @Test
    public void testKindofEqualsComparesAutofillId() {
        final EditorInfo infoCopy = TEST_EDITOR_INFO.createCopyInternal();
        infoCopy.autofillId = new AutofillId(42);
        assertFalse(TEST_EDITOR_INFO.kindofEquals(infoCopy));
    }

    @Test
    public void testKindofEqualsComparesFieldId() {
        final EditorInfo infoCopy = TEST_EDITOR_INFO.createCopyInternal();
        infoCopy.fieldId = 42;
        assertFalse(TEST_EDITOR_INFO.kindofEquals(infoCopy));
    }

    @Test
    public void testKindofEqualsComparesMimeTypes() {
        final EditorInfo infoCopy = TEST_EDITOR_INFO.createCopyInternal();
        infoCopy.contentMimeTypes = new String[] {"image/png", "image/gif"};
        assertFalse(TEST_EDITOR_INFO.kindofEquals(infoCopy));
    }

    @Test
    public void testKindofEqualsComparesExtras() {
        final EditorInfo infoCopy = TEST_EDITOR_INFO.createCopyInternal();
        infoCopy.extras.putString("testKey2", "testValue");
        assertFalse(TEST_EDITOR_INFO.kindofEquals(infoCopy));
    }
}
