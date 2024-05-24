/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.platform.test.annotations.Presubmit;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.SuggestionSpan;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BaseInputConnectionTest {
    private static final int[] CURSOR_CAPS_MODES =
            new int[] {
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS,
                InputType.TYPE_TEXT_FLAG_CAP_WORDS,
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            };

    private BaseInputConnection mBaseInputConnection;
    private Editable mEditable;
    private View mMockView;

    @Before
    public void setUp() throws Exception {
        mMockView = new View(InstrumentationRegistry.getInstrumentation().getContext());
        mBaseInputConnection = new BaseInputConnection(mMockView, /*fullEditor=*/ true);
        mEditable = mBaseInputConnection.getEditable();
        verifyContent("", 0, 0, -1, -1);
    }

    @Test
    public void testCommitText_toEditorWithoutSelectionAndComposing() {
        // before commit: "|"
        // after commit: "text1|"
        assertThat(mBaseInputConnection.commitText("text1", 1)).isTrue();
        verifyContent("text1", 5, 5, -1, -1);

        // before commit: "text1|"
        // after commit: "text1text2|"
        assertThat(mBaseInputConnection.commitText("text2", "text1".length())).isTrue();
        verifyContent("text1text2", 10, 10, -1, -1);

        // before commit: "text1text2|"
        // after commit: "text1text2text3|"
        assertThat(mBaseInputConnection.commitText("text3", 100)).isTrue();
        verifyContent("text1text2text3", 15, 15, -1, -1);

        // before commit: "text1text2text3|"
        // after commit: "text1text2text3|text4"
        assertThat(mBaseInputConnection.commitText("text4", 0)).isTrue();
        verifyContent("text1text2text3text4", 15, 15, -1, -1);

        // before commit: "text1text2text3|text4"
        // after commit: "text1text2text|3text5text4"
        assertThat(mBaseInputConnection.commitText("text5", -1)).isTrue();
        verifyContent("text1text2text3text5text4", 14, 14, -1, -1);

        // before commit: "text1text2text|3text5text4"
        // after commit: "text1text2te|xttext63text5text4"
        assertThat(mBaseInputConnection.commitText("text6", -2)).isTrue();
        verifyContent("text1text2texttext63text5text4", 12, 12, -1, -1);

        // before commit: "text1text2te|xttext63text5text4"
        // after commit: "|text1text2tetext7xttext63text5text4"
        assertThat(mBaseInputConnection.commitText("text7", -100)).isTrue();
        verifyContent("text1text2tetext7xttext63text5text4", 0, 0, -1, -1);
    }

    @Test
    public void testCommitText_toEditorWithSelection() {
        // before commit: "123|456|789"
        // before commit: "123text|789"
        prepareContent("123456789", 3, 6, -1, -1);
        assertThat(mBaseInputConnection.commitText("text", 1)).isTrue();
        verifyContent("123text789", 7, 7, -1, -1);

        // before commit: "|123|"
        // before commit: "|text"
        prepareContent("123", 0, 3, -1, -1);
        assertThat(mBaseInputConnection.commitText("text", 0)).isTrue();
        verifyContent("text", 0, 0, -1, -1);
    }

    @Test
    public void testCommitText_toEditorWithComposing() {
        // before commit: "123456|789"
        //                    ---
        // before commit: "123text|789"
        prepareContent("123456789", 6, 6, 3, 6);
        assertThat(mBaseInputConnection.commitText("text", 1)).isTrue();
        verifyContent("123text789", 7, 7, -1, -1);

        // before commit: "123456789|"
        //                    ---
        // before commit: "123text|789"
        prepareContent("123456789", 6, 6, 3, 6);
        assertThat(mBaseInputConnection.commitText("text", 1)).isTrue();
        verifyContent("123text789", 7, 7, -1, -1);

        // before commit: "|123456789|"
        //                     ---
        // before commit: "123text|789"
        prepareContent("123456789", 0, 9, 3, 6);
        assertThat(mBaseInputConnection.commitText("text", 1)).isTrue();
        verifyContent("123text789", 7, 7, -1, -1);
    }

    @Test
    public void deleteSurroundingText_fromEditorWithoutSelectionAndComposing() {
        // before delete: "123456789|"
        // after delete: "123456|"
        prepareContent("123456789", 9, 9, -1, -1);
        assertThat(mBaseInputConnection.deleteSurroundingText(3, 0)).isTrue();
        verifyContent("123456", 6, 6, -1, -1);

        // before delete: "123456|"
        // after delete: "|"
        assertThat(mBaseInputConnection.deleteSurroundingText(100, 0)).isTrue();
        verifyContent("", 0, 0, -1, -1);

        // before commit: "|123456789"
        // after delete: "|456789"
        prepareContent("123456789", 0, 0, -1, -1);
        assertThat(mBaseInputConnection.deleteSurroundingText(0, 3)).isTrue();
        verifyContent("456789", 0, 0, -1, -1);

        // before delete: "|123456789"
        // after delete: "|"
        assertThat(mBaseInputConnection.deleteSurroundingText(0, 100)).isTrue();
        verifyContent("", 0, 0, -1, -1);

        // before delete: "123|456789"
        // after delete: "1|789"
        prepareContent("123456789", 3, 3, -1, -1);
        assertThat(mBaseInputConnection.deleteSurroundingText(2, 3)).isTrue();
        verifyContent("1789", 1, 1, -1, -1);
    }

    @Test
    public void deleteSurroundingText_fromEditorSelectionOrComposing() {
        // before delete: "123|456|789"
        // before delete: "12|456|9"
        prepareContent("123456789", 3, 6, -1, -1);
        assertThat(mBaseInputConnection.deleteSurroundingText(1, 2)).isTrue();
        verifyContent("124569", 2, 5, -1, -1);

        // before delete: "12|456|9"
        // before delete: "|456|"
        assertThat(mBaseInputConnection.deleteSurroundingText(100, 100)).isTrue();
        verifyContent("456", 0, 3, -1, -1);

        // before commit: "123456|789"
        //                    ---
        // before commit: "1[456]|89"
        prepareContent("123456789", 6, 6, 3, 6);
        assertThat(mBaseInputConnection.deleteSurroundingText(2, 1)).isTrue();
        verifyContent("145689", 4, 4, 1, 4);

        // before commit: "1234|56789"
        //                    - --
        // before commit: "124|56|89"
        //                   - --
        prepareContent("123456789", 4, 4, 3, 6);
        assertThat(mBaseInputConnection.deleteSurroundingText(1, 1)).isTrue();
        verifyContent("1245689", 3, 3, 2, 5);
    }

    @Test
    public void deleteSurroundingText_negativeLength_willBeIgnored() {
        // before delete: "123|45678"
        // after delete: "123|45678"
        prepareContent("123456789", 3, 3, -1, -1);
        assertThat(mBaseInputConnection.deleteSurroundingText(-1, -1)).isTrue();
        verifyContent("123456789", 3, 3, -1, -1);

        // before delete: "123|45678"
        // after delete: "123|5678"
        assertThat(mBaseInputConnection.deleteSurroundingText(-1, 1)).isTrue();
        verifyContent("12356789", 3, 3, -1, -1);

        // before delete: "123|45678"
        // after delete: "12|45678"
        prepareContent("123456789", 3, 3, -1, -1);
        assertThat(mBaseInputConnection.deleteSurroundingText(1, -1)).isTrue();
        verifyContent("12456789", 2, 2, -1, -1);
    }

    @Test
    public void testFinishComposingText() {
        // before finish composing: "123456|789"
        //                             ---
        // before finish composing: "123456|789"
        prepareContent("123456789", 6, 6, 3, 6);
        assertThat(mBaseInputConnection.finishComposingText()).isTrue();
        verifyContent("123456789", 6, 6, -1, -1);

        // before finish composing: "123456789|"
        //                             ---
        // before finish composing: "123456789|"
        prepareContent("123456789", 9, 9, 3, 6);
        assertThat(mBaseInputConnection.finishComposingText()).isTrue();
        verifyContent("123456789", 9, 9, -1, -1);

        // before finish composing: "|123456789|"
        //                              ---
        // before finish composing: "|123456789|"
        prepareContent("123456789", 0, 9, 3, 6);
        assertThat(mBaseInputConnection.finishComposingText()).isTrue();
        verifyContent("123456789", 0, 9, -1, -1);

        // before finish composing: "1234|5|6789|"
        //                           ---- - ----
        // before finish composing: "1234|5|6789"
        prepareContent("123456789", 4, 5, 0, 9);
        assertThat(mBaseInputConnection.finishComposingText()).isTrue();
        verifyContent("123456789", 4, 5, -1, -1);
    }

    @Test
    public void testGetCursorCapsMode() {
        // "|"
        prepareContent("", 0, 0, -1, -1);
        verifyCursorCapsModeWithMode("", 0);

        // Hello|
        prepareContent("Hello", 5, 5, -1, -1);
        verifyCursorCapsModeWithMode("Hello", 5);

        // Hello. |
        prepareContent("Hello. ", 7, 7, -1, -1);
        verifyCursorCapsModeWithMode("Hello. ", 7);

        // Hello. |Hi|
        prepareContent("Hello. Hi", 7, 9, -1, -1);
        verifyCursorCapsModeWithMode("Hello. Hi", 7);

        // Hello. |
        // -----
        prepareContent("Hello. ", 7, 7, 0, 5);
        verifyCursorCapsModeWithMode("Hello. ", 7);
    }

    private void verifyCursorCapsModeWithMode(CharSequence text, int off) {
        for (int reqMode : CURSOR_CAPS_MODES) {
            assertThat(mBaseInputConnection.getCursorCapsMode(reqMode))
                    .isEqualTo(TextUtils.getCapsMode(text, off, reqMode));
        }
    }

    @Test
    public void testSetComposingText_toEditorWithoutSelectionAndComposing() {
        // before set composing text: "|"
        // after set composing text: "abc|"
        //                            ---
        assertThat(mBaseInputConnection.setComposingText("abc", 1)).isTrue();
        verifyContent("abc", 3, 3, 0, 3);

        // before set composing text: "abc|"
        // after set composing text: "abcdef|"
        //                               ---
        prepareContent("abc", 3, 3, -1, -1);
        assertThat(mBaseInputConnection.setComposingText("def", 100)).isTrue();
        verifyContent("abcdef", 6, 6, 3, 6);

        // before set composing text: "abc|"
        // after set composing text: "abc|def"
        //                                ---
        prepareContent("abc", 3, 3, -1, -1);
        assertThat(mBaseInputConnection.setComposingText("def", 0)).isTrue();
        verifyContent("abcdef", 3, 3, 3, 6);

        // before set composing text: "abc|"
        // after set composing text: "ab|cdef"
        //                                ---
        prepareContent("abc", 3, 3, -1, -1);
        assertThat(mBaseInputConnection.setComposingText("def", -1)).isTrue();
        verifyContent("abcdef", 2, 2, 3, 6);

        // before set composing text: "abc|"
        // after set composing text: "|abcdef"
        //                                ---
        prepareContent("abc", 3, 3, -1, -1);
        assertThat(mBaseInputConnection.setComposingText("def", -100)).isTrue();
        verifyContent("abcdef", 0, 0, 3, 6);
    }

    @Test
    public void testSetComposingText_toEditorWithComposing() {
        // before set composing text: "abc|"
        //                             ---
        // after set composing text: "def|"
        //                            ---
        prepareContent("abc", 3, 3, 0, 3);
        assertThat(mBaseInputConnection.setComposingText("def", 1)).isTrue();
        verifyContent("def", 3, 3, 0, 3);

        // before set composing text: "abc|"
        //                             ---
        // after set composing text: "hijkl|"
        //                            -----
        assertThat(mBaseInputConnection.setComposingText("hijkl", 1)).isTrue();
        verifyContent("hijkl", 5, 5, 0, 5);

        // before set composing text: "hijkl|"
        //                             -----
        // after set composing text: "|mn"
        //                             --
        assertThat(mBaseInputConnection.setComposingText("mn", 0)).isTrue();
        verifyContent("mn", 0, 0, 0, 2);

        // before set composing text: "|mn"
        //                              --
        // after set composing text: "|opq"
        //                             ---
        assertThat(mBaseInputConnection.setComposingText("opq", -1)).isTrue();
        verifyContent("opq", 0, 0, 0, 3);
    }

    @Test
    public void testSetComposingText_toEditorWithSelection() {
        // before set composing text: "|abc|"
        // after set composing text: "defgh|"
        //                            -----
        prepareContent("abc", 0, 3, -1, -1);
        assertThat(mBaseInputConnection.setComposingText("defgh", 1)).isTrue();
        verifyContent("defgh", 5, 5, 0, 5);

        // before set composing text: "a|bcdef|g"
        // after set composing text: "a|123g"
        //                              ---
        prepareContent("abcdefg", 1, 6, -1, -1);
        assertThat(mBaseInputConnection.setComposingText("123", 0)).isTrue();
        verifyContent("a123g", 1, 1, 1, 4);

        // before set composing text: "a|bcdef|g"
        //                                ---
        // after set composing text: "ab123456|fg"
        //                              ------
        prepareContent("abcdefg", 1, 6, 2, 5);
        assertThat(mBaseInputConnection.setComposingText("123456", 1)).isTrue();
        verifyContent("ab123456fg", 8, 8, 2, 8);

        // before set composing text: "a|bc"
        //                             ----
        // after set composing text: "|12345"
        //                             -----
        prepareContent("abc", 1, 1, 0, 3);
        assertThat(mBaseInputConnection.setComposingText("12345", -1)).isTrue();
        verifyContent("12345", 0, 0, 0, 5);
    }

    @Test
    public void testSetComposingRegion_toEditorWithoutSelectionAndComposing() {
        // before set composing region: "|"
        // after set composing region: "|"
        assertThat(mBaseInputConnection.setComposingRegion(1, 1)).isTrue();
        verifyContent("", 0, 0, -1, -1);

        // before set composing region: "abc|"
        // after set composing region: "abc|"
        //                              ---
        prepareContent("abc", 3, 3, -1, -1);
        assertThat(mBaseInputConnection.setComposingRegion(0, 3)).isTrue();
        verifyContent("abc", 3, 3, 0, 3);

        // before set composing region: "abc|"
        // after set composing region: "abc|"
        prepareContent("abc", 3, 3, -1, -1);
        assertThat(mBaseInputConnection.setComposingRegion(1, 1)).isTrue();
        verifyContent("abc", 3, 3, -1, -1);

        // before set composing region: "abc|"
        // after set composing region: "abc|"
        //                               -
        prepareContent("abc", 3, 3, -1, -1);
        assertThat(mBaseInputConnection.setComposingRegion(1, 2)).isTrue();
        verifyContent("abc", 3, 3, 1, 2);

        // before set composing region: "abc|"
        // after set composing region: "abc|"
        //                              ---
        prepareContent("abc", 3, 3, -1, -1);
        assertThat(mBaseInputConnection.setComposingRegion(3, 0)).isTrue();
        verifyContent("abc", 3, 3, 0, 3);

        // before set composing region: "abc|"
        // after set composing region: "abc|"
        //                              ---
        prepareContent("abc", 3, 3, -1, -1);
        assertThat(mBaseInputConnection.setComposingRegion(-100, 100)).isTrue();
        verifyContent("abc", 3, 3, 0, 3);
    }

    @Test
    public void testSetComposingRegion_toEditorWithSelection() {
        // before set composing region: "|abc|"
        // after set composing region: "|abc|"
        //                               ---
        prepareContent("abc", 0, 3, -1, -1);
        assertThat(mBaseInputConnection.setComposingRegion(0, 3)).isTrue();
        verifyContent("abc", 0, 3, 0, 3);

        // before set composing region: "ab|cd|ef"
        // after set composing region: "ab|cd|ef"
        //                               - -- -
        prepareContent("abcdef", 2, 4, -1, -1);
        assertThat(mBaseInputConnection.setComposingRegion(1, 5)).isTrue();
        verifyContent("abcdef", 2, 4, 1, 5);
    }

    @Test
    public void testSetComposingRegion_toEditorWithComposing() {
        // before set composing region: "abc|"
        //                               ---
        // after set composing region: "abc|"
        //                               -
        prepareContent("abc", 3, 3, -1, -1);
        assertThat(mBaseInputConnection.setComposingRegion(1, 2)).isTrue();
        verifyContent("abc", 3, 3, 1, 2);

        // before set composing region: "ab|cd|ef"
        //                                  --
        // after set composing region: "ab|cd|ef"
        //                               - -- -
        prepareContent("abcdef", 2, 4, 2, 4);
        assertThat(mBaseInputConnection.setComposingRegion(1, 5)).isTrue();
        verifyContent("abcdef", 2, 4, 1, 5);
    }

    @Test
    public void testSetSelection_toEditorWithoutComposing() {
        // before set selection: "|"
        // after set selection: "|"
        assertThat(mBaseInputConnection.setSelection(0, 0)).isTrue();
        assertThat(mBaseInputConnection.setSelection(1, 1)).isTrue();
        assertThat(mBaseInputConnection.setSelection(-1, -1)).isTrue();

        // before set selection: "abc|"
        // after set selection: "a|b|c"
        prepareContent("abc", 3, 3, -1, -1);
        assertThat(mBaseInputConnection.setSelection(1, 1)).isTrue();
        verifyContent("abc", 1, 1, -1, -1);

        // before set selection: "abcdef|"
        // after set selection: "ab|cd|ef"
        prepareContent("abcdef", 6, 6, -1, -1);
        assertThat(mBaseInputConnection.setSelection(4, 2)).isTrue();
        verifyContent("abcdef", 4, 2, -1, -1);

        // before set selection: "|abc"
        // after set selection: "|abc"
        prepareContent("abc", 0, 0, -1, -1);
        assertThat(mBaseInputConnection.setSelection(0, 100)).isTrue();
        verifyContent("abc", 0, 0, -1, -1);

        // before set selection: "|abc"
        // after set selection: "ab|c"
        prepareContent("abc", 0, 0, -1, -1);
        assertThat(mBaseInputConnection.setSelection(2, 2)).isTrue();
        verifyContent("abc", 2, 2, -1, -1);

        // before set selection: "|abc"
        // after set selection: "|abc"
        prepareContent("abc", 0, 0, -1, -1);
        assertThat(mBaseInputConnection.setSelection(-1, 2)).isTrue();
        verifyContent("abc", 0, 0, -1, -1);
    }

    @Test
    public void testSetSelection_toEditorWithComposing() {
        // before set selection: "abc|"
        //                        ---
        // after set selection: "a|bc"
        //                       - --
        prepareContent("abc", 3, 3, 0, 3);
        assertThat(mBaseInputConnection.setSelection(1, 1)).isTrue();
        verifyContent("abc", 1, 1, 0, 3);

        // before set selection: "abcdef|"
        //                          ---
        // after set selection: "|abcdef|"
        //                          ---
        prepareContent("abcdef", 6, 6, 2, 5);
        assertThat(mBaseInputConnection.setSelection(0, 6)).isTrue();
        verifyContent("abcdef", 0, 6, 2, 5);
    }

    @Test
    public void testGetText_noStyle() {
        // "123|456|789"
        prepareContent("123456789", 3, 6, -1, -1);

        verifyContentEquals(mBaseInputConnection.getTextBeforeCursor(1, 0), "3");
        verifyContentEquals(mBaseInputConnection.getTextAfterCursor(1, 0), "7");
        verifyContentEquals(mBaseInputConnection.getSelectedText(0), "456");
        // This falls back to default implementation in {@code InputConnection}, which always return
        // -1 for offset.
        assertThat(
                        mBaseInputConnection
                                .getSurroundingText(1, 1, 0)
                                .isEqualTo(new SurroundingText("34567", 1, 4, -1)))
                .isTrue();

        verifyContentEquals(mBaseInputConnection.getTextBeforeCursor(100, 0), "123");
        verifyContentEquals(mBaseInputConnection.getTextAfterCursor(100, 0), "789");
        assertThat(
                        mBaseInputConnection
                                .getSurroundingText(100, 100, 0)
                                .isEqualTo(new SurroundingText("123456789", 3, 6, -1)))
                .isTrue();

        verifyContentEquals(mBaseInputConnection.getTextBeforeCursor(0, 0), "");
        verifyContentEquals(mBaseInputConnection.getTextAfterCursor(0, 0), "");
        assertThat(
                        mBaseInputConnection
                                .getSurroundingText(0, 0, 0)
                                .isEqualTo(new SurroundingText("456", 0, 3, -1)))
                .isTrue();

        verifyContentEquals(mBaseInputConnection.getTextBeforeCursor(Integer.MAX_VALUE, 0), "123");
        verifyContentEquals(mBaseInputConnection.getTextAfterCursor(Integer.MAX_VALUE, 0), "789");
        assertThat(
                mBaseInputConnection
                        .getSurroundingText(Integer.MAX_VALUE, Integer.MAX_VALUE, 0)
                        .isEqualTo(new SurroundingText("123456789", 3, 6, -1)))
                .isTrue();

        int cursorCapsMode =
                TextUtils.getCapsMode(
                        "123456789",
                        3,
                        TextUtils.CAP_MODE_CHARACTERS
                                | TextUtils.CAP_MODE_WORDS
                                | TextUtils.CAP_MODE_SENTENCES);
        TextSnapshot expectedTextSnapshot =
                new TextSnapshot(
                        new SurroundingText("123456789", 3, 6, -1), -1, -1, cursorCapsMode);
        verifyTextSnapshotContentEquals(mBaseInputConnection.takeSnapshot(), expectedTextSnapshot);
    }

    @Test
    public void testGetText_withStyle() {
        // "123|456|789"
        SpannableStringBuilder text = new SpannableStringBuilder("123456789");
        SuggestionSpan suggestionSpanA =
                new SuggestionSpan(Locale.US, new String[] {"a"}, SuggestionSpan.FLAG_EASY_CORRECT);
        SuggestionSpan suggestionSpanB =
                new SuggestionSpan(Locale.US, new String[] {"b"}, SuggestionSpan.FLAG_EASY_CORRECT);
        SuggestionSpan suggestionSpanC =
                new SuggestionSpan(Locale.US, new String[] {"c"}, SuggestionSpan.FLAG_EASY_CORRECT);
        text.setSpan(suggestionSpanA, 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(suggestionSpanB, 3, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(suggestionSpanC, 6, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        prepareContent(text, 3, 6, -1, -1);

        verifySpannableString(
                mBaseInputConnection.getTextBeforeCursor(1, InputConnection.GET_TEXT_WITH_STYLES),
                "3",
                1,
                new int[][] {new int[] {0, 1}},
                new Object[] {suggestionSpanA});
        verifySpannableString(
                mBaseInputConnection.getTextAfterCursor(1, InputConnection.GET_TEXT_WITH_STYLES),
                "7",
                1,
                new int[][] {new int[] {0, 1}},
                new Object[] {suggestionSpanC});
        verifySpannableString(
                mBaseInputConnection.getSelectedText(InputConnection.GET_TEXT_WITH_STYLES),
                "456",
                1,
                new int[][] {new int[] {0, 3}},
                new Object[] {suggestionSpanB});
        CharSequence surroundTextString =
                TextUtils.concat(
                        text.subSequence(0, 3), text.subSequence(3, 6), text.subSequence(6, 9));
        assertThat(
                        mBaseInputConnection
                                .getSurroundingText(100, 100, InputConnection.GET_TEXT_WITH_STYLES)
                                .isEqualTo(new SurroundingText(surroundTextString, 3, 6, -1)))
                .isTrue();

        int cursorCapsMode =
                TextUtils.getCapsMode(
                        "123456789",
                        3,
                        TextUtils.CAP_MODE_CHARACTERS
                                | TextUtils.CAP_MODE_WORDS
                                | TextUtils.CAP_MODE_SENTENCES);
        TextSnapshot expectedTextSnapshot =
                new TextSnapshot(
                        new SurroundingText(surroundTextString, 3, 6, -1), -1, -1, cursorCapsMode);
        verifyTextSnapshotContentEquals(mBaseInputConnection.takeSnapshot(), expectedTextSnapshot);
    }

    @Test
    public void testGetText_emptyText() {
        // ""
        prepareContent("", 0, 0, -1, -1);

        verifyContentEquals(mBaseInputConnection.getTextBeforeCursor(1, 0), "");
        verifyContentEquals(mBaseInputConnection.getTextAfterCursor(1, 0), "");
        assertThat(mBaseInputConnection.getSelectedText(0)).isNull();

        // This falls back to default implementation in {@code InputConnection}, which always return
        // -1 for offset.
        assertThat(
                mBaseInputConnection
                        .getSurroundingText(1, 1, 0)
                        .isEqualTo(new SurroundingText("", 0, 0, -1)))
                .isTrue();

        verifyContentEquals(mBaseInputConnection.getTextBeforeCursor(0, 0), "");
        verifyContentEquals(mBaseInputConnection.getTextAfterCursor(0, 0), "");
        assertThat(mBaseInputConnection.getSelectedText(0)).isNull();
        // This falls back to default implementation in {@code InputConnection}, which always return
        // -1 for offset.
        assertThat(
                mBaseInputConnection
                        .getSurroundingText(0, 0, 0)
                        .isEqualTo(new SurroundingText("", 0, 0, -1)))
                .isTrue();

        verifyContentEquals(mBaseInputConnection.getTextBeforeCursor(Integer.MAX_VALUE, 0), "");
        verifyContentEquals(mBaseInputConnection.getTextAfterCursor(Integer.MAX_VALUE, 0), "");
        assertThat(mBaseInputConnection.getSelectedText(0)).isNull();
        assertThat(
                mBaseInputConnection
                        .getSurroundingText(Integer.MAX_VALUE, Integer.MAX_VALUE, 0)
                        .isEqualTo(new SurroundingText("", 0, 0, -1)))
                .isTrue();
    }


    @Test
    public void testReplaceText_toEditorWithoutSelectionAndComposing() {
        // before replace: "|"
        // after replace: "text1|"
        assertThat(mBaseInputConnection.replaceText(0, 0, "text1", 1, null)).isTrue();
        verifyContent("text1", 5, 5, -1, -1);

        // before replace: "text1|"
        // after replace: "text2|"
        assertThat(mBaseInputConnection.replaceText(0, 5, "text2", 1, null)).isTrue();
        verifyContent("text2", 5, 5, -1, -1);

        // before replace: "text1|"
        // after replace: "|text3"
        assertThat(mBaseInputConnection.replaceText(0, 5, "text3", -1, null)).isTrue();
        verifyContent("text3", 0, 0, -1, -1);

        // before replace: "|text3"
        // after replace: "ttext4|t3"
        // BUG(b/21476564): this behavior is inconsistent with API description.
        assertThat(mBaseInputConnection.replaceText(1, 3, "text4", 1, null)).isTrue();
        verifyContent("ttext4t3", 6, 6, -1, -1);

        // before replace: "ttext4|t3"
        // after replace: "|text5t3"
        assertThat(mBaseInputConnection.replaceText(0, 6, "text5", -1, null)).isTrue();
        verifyContent("text5t3", 0, 0, -1, -1);
    }

    @Test
    public void testReplaceText_toEditorWithSelection() {
        // before replace: "123|456|789"
        // before replace: "123text|6789"
        prepareContent("123456789", 3, 6, -1, -1);
        assertThat(mBaseInputConnection.replaceText(3, 5, "text", 1, null)).isTrue();
        verifyContent("123text6789", 7, 7, -1, -1);

        // before replace: "|123|"
        // before replace: "|text23"
        prepareContent("123", 0, 3, -1, -1);
        assertThat(mBaseInputConnection.replaceText(0, 1, "text", 0, null)).isTrue();
        verifyContent("text23", 0, 0, -1, -1);
    }

    @Test
    public void testReplaceText_toEditorWithComposing() {
        // before replace: "123456|789"
        //                     ---
        // before replace: "123456text|"
        prepareContent("123456789", 6, 6, 3, 6);
        assertThat(mBaseInputConnection.replaceText(6, 9, "text", 1, null)).isTrue();
        verifyContent("123456text", 10, 10, -1, -1);

        // before replace: "123456789|"
        //                     ---
        // before replace: "text|123456789"
        prepareContent("123456789", 9, 9, 3, 6);
        assertThat(mBaseInputConnection.replaceText(0, 0, "text", 1, null)).isTrue();
        verifyContent("text123456789", 4, 4, -1, -1);

        // before replace: "|123456789|"
        //                      ---
        // before replace: "12text|9"
        prepareContent("123456789", 0, 9, 3, 6);
        assertThat(mBaseInputConnection.replaceText(2, 8, "text", 1, null)).isTrue();
        verifyContent("12text9", 6, 6, -1, -1);
    }

    private void prepareContent(
            CharSequence text,
            int selectionStart,
            int selectionEnd,
            int composingSpanStart,
            int composingSpanEnd) {
        mEditable.clear();
        mEditable.append(text);
        Selection.setSelection(mEditable, selectionStart, selectionEnd);
        if (isValidComposingSpan(text.length(), composingSpanStart, composingSpanEnd)) {
            BaseInputConnection.setComposingSpans(mEditable, composingSpanStart, composingSpanEnd);
        }
        verifyContent(text, selectionStart, selectionEnd, composingSpanStart, composingSpanEnd);
    }

    private boolean isValidComposingSpan(
            int textLength, int composingSpanStart, int composingSpanEnd) {
        return composingSpanStart >= 0
                && composingSpanStart <= textLength
                && composingSpanEnd >= 0
                && composingSpanEnd <= textLength;
    }

    private void verifyContent(
            CharSequence text,
            int selectionStart,
            int selectionEnd,
            int composingSpanStart,
            int composingSpanEnd) {
        assertThat(mEditable).isNotNull();
        verifyContentEquals(mEditable, text.toString());
        assertThat(Selection.getSelectionStart(mEditable)).isEqualTo(selectionStart);
        assertThat(Selection.getSelectionEnd(mEditable)).isEqualTo(selectionEnd);
        assertThat(BaseInputConnection.getComposingSpanStart(mEditable))
                .isEqualTo(composingSpanStart);
        assertThat(BaseInputConnection.getComposingSpanEnd(mEditable)).isEqualTo(composingSpanEnd);
    }

    private void verifySpannableString(
            CharSequence text,
            String expectedString,
            int expectedSpanSize,
            int[][] expectedSpanRanges,
            Object[] expectedSpans) {
        verifyContentEquals(text, expectedString);
        SpannableStringBuilder spannableString = new SpannableStringBuilder(text);
        Object[] spanList = spannableString.getSpans(0, text.length(), Object.class);
        assertThat(spanList).isNotNull();
        assertThat(spanList).hasLength(expectedSpanSize);
        for (int i = 0; i < expectedSpanSize; i++) {
            assertThat(spannableString.getSpanStart(spanList[i]))
                    .isEqualTo(expectedSpanRanges[i][0]);
            assertThat(spannableString.getSpanEnd(spanList[i])).isEqualTo(expectedSpanRanges[i][1]);
        }
        for (int i = 0; i < expectedSpanSize; i++) {
            assertThat(spanList[i]).isEqualTo(expectedSpans[i]);
        }
    }

    private void verifyContentEquals(CharSequence text, String expectedText) {
        assertThat(text.toString().contentEquals(expectedText)).isTrue();
    }

    private void verifyTextSnapshotContentEquals(TextSnapshot t1, TextSnapshot t2) {
        assertThat(t1.getCompositionStart()).isEqualTo(t2.getCompositionStart());
        assertThat(t1.getCompositionEnd()).isEqualTo(t2.getCompositionEnd());
        assertThat(t1.getCursorCapsMode()).isEqualTo(t2.getCursorCapsMode());
        assertThat(t1.getSurroundingText().isEqualTo(t2.getSurroundingText())).isTrue();
    }
}
