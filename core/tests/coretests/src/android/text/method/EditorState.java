/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.text.method;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ReplacementSpan;

import junit.framework.Assert;

/**
 * Represents an editor state.
 *
 * The editor state can be specified by following string format.
 * - Components are separated by space(U+0020).
 * - Single-quoted string for printable ASCII characters, e.g. 'a', '123'.
 * - U+XXXX form can be used for a Unicode code point.
 * - Components inside '[' and ']' are in selection.
 * - Components inside '(' and ')' are in ReplacementSpan.
 * - '|' is for specifying cursor position.
 *
 * Selection and cursor can not be specified at the same time.
 *
 * Example:
 *   - "'Hello,' | U+0020 'world!'" means "Hello, world!" is displayed and the cursor position
 *     is 6.
 *   - "'abc' [ 'def' ] 'ghi'" means "abcdefghi" is displayed and "def" is selected.
 *   - "U+1F441 | ( U+1F441 U+1F441 )" means three U+1F441 characters are displayed and
 *     ReplacementSpan is set from offset 2 to 6.
 */
public class EditorState {
    private static final String REPLACEMENT_SPAN_START = "(";
    private static final String REPLACEMENT_SPAN_END = ")";
    private static final String SELECTION_START = "[";
    private static final String SELECTION_END = "]";
    private static final String CURSOR = "|";

    public Editable mText;
    public int mSelectionStart = -1;
    public int mSelectionEnd = -1;

    public EditorState() {
    }

    /**
     * A mocked {@link android.text.style.ReplacementSpan} for testing purpose.
     */
    private static class MockReplacementSpan extends ReplacementSpan {
        public int getSize(Paint paint, CharSequence text, int start, int end,
                Paint.FontMetricsInt fm) {
            return 0;
        }
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top,
                int y, int bottom, Paint paint) {
        }
    }

    // Returns true if the code point is ASCII and graph.
    private boolean isGraphicAscii(int codePoint) {
        return 0x20 < codePoint && codePoint < 0x7F;
    }

    // Setup editor state with string. Please see class description for string format.
    public void setByString(String string) {
        final StringBuilder sb = new StringBuilder();
        int replacementSpanStart = -1;
        int replacementSpanEnd = -1;
        mSelectionStart = -1;
        mSelectionEnd = -1;

        final String[] tokens = string.split(" +");
        for (String token : tokens) {
            if (token.startsWith("'") && token.endsWith("'")) {
                for (int i = 1; i < token.length() - 1; ++i) {
                    final char ch = token.charAt(1);
                    if (!isGraphicAscii(ch)) {
                        throw new IllegalArgumentException(
                                "Only printable characters can be in single quote. " +
                                "Use U+" + Integer.toHexString(ch).toUpperCase() + " instead");
                    }
                }
                sb.append(token.substring(1, token.length() - 1));
            } else if (token.startsWith("U+")) {
                final int codePoint = Integer.parseInt(token.substring(2), 16);
                if (codePoint < 0 || 0x10FFFF < codePoint) {
                    throw new IllegalArgumentException("Invalid code point is specified:" + token);
                }
                sb.append(Character.toChars(codePoint));
            } else if (token.equals(CURSOR)) {
                if (mSelectionStart != -1 || mSelectionEnd != -1) {
                    throw new IllegalArgumentException(
                            "Two or more cursor/selection positions are specified.");
                }
                mSelectionStart = mSelectionEnd = sb.length();
            } else if (token.equals(SELECTION_START)) {
                if (mSelectionStart != -1) {
                    throw new IllegalArgumentException(
                            "Two or more cursor/selection positions are specified.");
                }
                mSelectionStart = sb.length();
            } else if (token.equals(SELECTION_END)) {
                if (mSelectionEnd != -1) {
                    throw new IllegalArgumentException(
                            "Two or more cursor/selection positions are specified.");
                }
                mSelectionEnd = sb.length();
            } else if (token.equals(REPLACEMENT_SPAN_START)) {
                if (replacementSpanStart != -1) {
                    throw new IllegalArgumentException(
                            "Only one replacement span is supported");
                }
                replacementSpanStart = sb.length();
            } else if (token.equals(REPLACEMENT_SPAN_END)) {
                if (replacementSpanEnd != -1) {
                    throw new IllegalArgumentException(
                            "Only one replacement span is supported");
                }
                replacementSpanEnd = sb.length();
            } else {
                throw new IllegalArgumentException("Unknown or invalid token: " + token);
            }
        }

        if (mSelectionStart == -1 || mSelectionEnd == -1) {
              if (mSelectionEnd != -1) {
                  throw new IllegalArgumentException(
                          "Selection start position doesn't exist.");
              } else if (mSelectionStart != -1) {
                  throw new IllegalArgumentException(
                          "Selection end position doesn't exist.");
              } else {
                  throw new IllegalArgumentException(
                          "At least cursor position or selection range must be specified.");
              }
        } else if (mSelectionStart > mSelectionEnd) {
              throw new IllegalArgumentException(
                      "Selection start position appears after end position.");
        }

        final Spannable spannable = new SpannableString(sb.toString());

        if (replacementSpanStart != -1 || replacementSpanEnd != -1) {
            if (replacementSpanStart == -1) {
                throw new IllegalArgumentException(
                        "ReplacementSpan start position doesn't exist.");
            }
            if (replacementSpanEnd == -1) {
                throw new IllegalArgumentException(
                        "ReplacementSpan end position doesn't exist.");
            }
            if (replacementSpanStart > replacementSpanEnd) {
                throw new IllegalArgumentException(
                        "ReplacementSpan start position appears after end position.");
            }
            spannable.setSpan(new MockReplacementSpan(), replacementSpanStart, replacementSpanEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        mText = Editable.Factory.getInstance().newEditable(spannable);
    }

    public void assertEquals(String string) {
        EditorState expected = new EditorState();
        expected.setByString(string);

        Assert.assertEquals(expected.mText.toString(), mText.toString());
        Assert.assertEquals(expected.mSelectionStart, mSelectionStart);
        Assert.assertEquals(expected.mSelectionEnd, mSelectionEnd);
    }
}

