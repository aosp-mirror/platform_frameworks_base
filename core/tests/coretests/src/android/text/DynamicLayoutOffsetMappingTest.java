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

package android.text;

import static android.text.Layout.Alignment.ALIGN_NORMAL;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;
import android.text.method.OffsetMapping;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class DynamicLayoutOffsetMappingTest {
    private static final int WIDTH = 10000;
    private static final TextPaint sTextPaint = new TextPaint();

    @Test
    public void textWithOffsetMapping() {
        final String text = "abcde";
        final SpannableStringBuilder spannable = new SpannableStringBuilder(text);
        final CharSequence transformedText = new TestOffsetMapping(spannable, 2, "\n");

        final DynamicLayout layout = DynamicLayout.Builder.obtain(spannable, sTextPaint, WIDTH)
                .setAlignment(ALIGN_NORMAL)
                .setIncludePad(false)
                .setDisplayText(transformedText)
                .build();

        assertThat(transformedText.toString()).isEqualTo("ab\ncde");
        assertLineRange(layout, /* lineBreaks */ 0, 3, 6);
    }

    @Test
    public void textWithOffsetMapping_deletion() {
        final String text = "abcdef";
        final SpannableStringBuilder spannable = new SpannableStringBuilder(text);
        final CharSequence transformedText =
                new TestOffsetMapping(spannable, 3, "\n\n");

        final DynamicLayout layout = DynamicLayout.Builder.obtain(spannable, sTextPaint, WIDTH)
                .setAlignment(ALIGN_NORMAL)
                .setIncludePad(false)
                .setDisplayText(transformedText)
                .build();

        // delete character 'c', original text becomes "abdef"
        spannable.delete(2, 3);
        assertThat(transformedText.toString()).isEqualTo("ab\n\ndef");
        assertLineRange(layout, /* lineBreaks */ 0, 3, 4, 7);

        // delete character 'd', original text becomes "abef"
        spannable.delete(2, 3);
        assertThat(transformedText.toString()).isEqualTo("ab\n\nef");
        assertLineRange(layout, /* lineBreaks */ 0, 3, 4, 6);

        // delete "be", original text becomes "af"
        spannable.delete(1, 3);
        assertThat(transformedText.toString()).isEqualTo("a\n\nf");
        assertLineRange(layout, /* lineBreaks */ 0, 2, 3, 4);
    }

    @Test
    public void textWithOffsetMapping_insertion() {
        final String text = "abcdef";
        final SpannableStringBuilder spannable = new SpannableStringBuilder(text);
        final CharSequence transformedText = new TestOffsetMapping(spannable, 3, "\n\n");

        final DynamicLayout layout = DynamicLayout.Builder.obtain(spannable, sTextPaint, WIDTH)
                .setAlignment(ALIGN_NORMAL)
                .setIncludePad(false)
                .setDisplayText(transformedText)
                .build();

        spannable.insert(3, "x");
        assertThat(transformedText.toString()).isEqualTo("abcx\n\ndef");
        assertLineRange(layout, /* lineBreaks */ 0, 5, 6, 9);

        spannable.insert(5, "x");
        assertThat(transformedText.toString()).isEqualTo("abcx\n\ndxef");
        assertLineRange(layout, /* lineBreaks */ 0, 5, 6, 10);
    }

    @Test
    public void textWithOffsetMapping_replace() {
        final String text = "abcdef";
        final SpannableStringBuilder spannable = new SpannableStringBuilder(text);
        final CharSequence transformedText = new TestOffsetMapping(spannable, 3, "\n\n");

        final DynamicLayout layout = DynamicLayout.Builder.obtain(spannable, sTextPaint, WIDTH)
                .setAlignment(ALIGN_NORMAL)
                .setIncludePad(false)
                .setDisplayText(transformedText)
                .build();

        spannable.replace(2, 4, "xx");
        assertThat(transformedText.toString()).isEqualTo("abxx\n\nef");
        assertLineRange(layout, /* lineBreaks */ 0, 5, 6, 8);
    }

    @Test
    public void textWithOffsetMapping_blockBeforeTextChanged_deletion() {
        final String text = "abcdef";
        final SpannableStringBuilder spannable = new TestNoBeforeTextChangeSpannableString(text);
        final CharSequence transformedText =
                new TestOffsetMapping(spannable, 5, "\n\n");

        final DynamicLayout layout = DynamicLayout.Builder.obtain(spannable, sTextPaint, WIDTH)
                .setAlignment(ALIGN_NORMAL)
                .setIncludePad(false)
                .setDisplayText(transformedText)
                .build();

        // delete "cd", original text becomes "abef"
        spannable.delete(2, 4);
        assertThat(transformedText.toString()).isEqualTo("abe\n\nf");
        assertLineRange(layout, /* lineBreaks */ 0, 4, 5, 6);

        // delete "abe", original text becomes "f"
        spannable.delete(0, 3);
        assertThat(transformedText.toString()).isEqualTo("\n\nf");
        assertLineRange(layout, /* lineBreaks */ 0, 1, 2, 3);
    }

    @Test
    public void textWithOffsetMapping_blockBeforeTextChanged_insertion() {
        final String text = "abcdef";
        final SpannableStringBuilder spannable = new TestNoBeforeTextChangeSpannableString(text);
        final CharSequence transformedText = new TestOffsetMapping(spannable, 3, "\n\n");

        final DynamicLayout layout = DynamicLayout.Builder.obtain(spannable, sTextPaint, WIDTH)
                .setAlignment(ALIGN_NORMAL)
                .setIncludePad(false)
                .setDisplayText(transformedText)
                .build();

        spannable.insert(3, "x");
        assertThat(transformedText.toString()).isEqualTo("abcx\n\ndef");
        assertLineRange(layout, /* lineBreaks */ 0, 5, 6, 9);

        spannable.insert(5, "x");
        assertThat(transformedText.toString()).isEqualTo("abcx\n\ndxef");
        assertLineRange(layout, /* lineBreaks */ 0, 5, 6, 10);
    }

    @Test
    public void textWithOffsetMapping_blockBeforeTextChanged_replace() {
        final String text = "abcdef";
        final SpannableStringBuilder spannable = new TestNoBeforeTextChangeSpannableString(text);
        final CharSequence transformedText = new TestOffsetMapping(spannable, 3, "\n\n");

        final DynamicLayout layout = DynamicLayout.Builder.obtain(spannable, sTextPaint, WIDTH)
                .setAlignment(ALIGN_NORMAL)
                .setIncludePad(false)
                .setDisplayText(transformedText)
                .build();

        spannable.replace(2, 4, "xx");
        assertThat(transformedText.toString()).isEqualTo("abxx\n\nef");
        assertLineRange(layout, /* lineBreaks */ 0, 5, 6, 8);
    }

    @Test
    public void textWithOffsetMapping_onlyCallOnTextChanged_notCrash() {
        String text = "abcdef";
        SpannableStringBuilder spannable = new SpannableStringBuilder(text);
        CharSequence transformedText = new TestOffsetMapping(spannable, 3, "\n\n");

        DynamicLayout.Builder.obtain(spannable, sTextPaint, WIDTH)
                .setAlignment(ALIGN_NORMAL)
                .setIncludePad(false)
                .setDisplayText(transformedText)
                .build();

        TextWatcher[] textWatcher = spannable.getSpans(0, spannable.length(), TextWatcher.class);
        assertThat(textWatcher.length).isEqualTo(1);

        textWatcher[0].onTextChanged(spannable, 0, 2, 2);
    }

    private void assertLineRange(Layout layout, int... lineBreaks) {
        final int lineCount = lineBreaks.length - 1;
        assertThat(layout.getLineCount()).isEqualTo(lineCount);
        for (int line = 0; line < lineCount; ++line) {
            assertThat(layout.getLineStart(line)).isEqualTo(lineBreaks[line]);
        }
        assertThat(layout.getLineEnd(lineCount - 1)).isEqualTo(lineBreaks[lineCount]);
    }

    /**
     * A test SpannableStringBuilder that doesn't call beforeTextChanged. It's used to test
     * DynamicLayout against some special cases where beforeTextChanged callback is not properly
     * called.
     */
    private static class TestNoBeforeTextChangeSpannableString extends SpannableStringBuilder {

        TestNoBeforeTextChangeSpannableString(CharSequence text) {
            super(text);
        }

        @Override
        public void setSpan(Object what, int start, int end, int flags) {
            if (what instanceof TextWatcher) {
                super.setSpan(new TestNoBeforeTextChangeWatcherWrapper((TextWatcher) what), start,
                        end, flags);
            } else {
                super.setSpan(what, start, end, flags);
            }
        }
    }

    /** A TextWatcherWrapper that blocks beforeTextChanged callback. */
    private static class TestNoBeforeTextChangeWatcherWrapper implements TextWatcher {
        private final TextWatcher mTextWatcher;

        TestNoBeforeTextChangeWatcherWrapper(TextWatcher textWatcher) {
            mTextWatcher = textWatcher;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            mTextWatcher.onTextChanged(s, start, before, count);
        }

        @Override
        public void afterTextChanged(Editable s) {
            mTextWatcher.afterTextChanged(s);
        }
    }

    /**
     * A test TransformedText that inserts some text at the given offset.
     */
    private static class TestOffsetMapping implements OffsetMapping, CharSequence {
        private final int mOriginalInsertOffset;
        private final CharSequence mOriginal;
        private final CharSequence mInsertText;
        TestOffsetMapping(CharSequence original, int insertOffset,
                CharSequence insertText) {
            mOriginal = original;
            if (mOriginal instanceof Spannable) {
                ((Spannable) mOriginal).setSpan(INSERT_POINT, insertOffset, insertOffset,
                        Spanned.SPAN_POINT_POINT);
            }
            mOriginalInsertOffset = insertOffset;
            mInsertText = insertText;
        }

        private int getInsertOffset() {
            if (mOriginal instanceof Spannable) {
                return ((Spannable) mOriginal).getSpanStart(INSERT_POINT);
            }
            return mOriginalInsertOffset;
        }

        @Override
        public int originalToTransformed(int offset, int strategy) {
            final int insertOffset = getInsertOffset();
            if (strategy == OffsetMapping.MAP_STRATEGY_CURSOR && offset == insertOffset) {
                return offset;
            }
            if (offset < getInsertOffset()) {
                return offset;
            }
            return offset + mInsertText.length();
        }

        @Override
        public int transformedToOriginal(int offset, int strategy) {
            final int insertOffset = getInsertOffset();
            if (offset < insertOffset) {
                return offset;
            }
            if (offset < insertOffset + mInsertText.length()) {
                return insertOffset;
            }
            return offset - mInsertText.length();
        }

        @Override
        public void originalToTransformed(TextUpdate textUpdate) {
            final int insertOffset = getInsertOffset();
            if (textUpdate.where <= insertOffset) {
                if (textUpdate.where + textUpdate.before > insertOffset) {
                    textUpdate.before += mInsertText.length();
                    textUpdate.after += mInsertText.length();
                }
            } else {
                textUpdate.where += mInsertText.length();
            }
        }

        @Override
        public int length() {
            return mOriginal.length() + mInsertText.length();
        }

        @Override
        public char charAt(int index) {
            final int insertOffset = getInsertOffset();
            if (index < insertOffset) {
                return mOriginal.charAt(index);
            }
            if (index < insertOffset + mInsertText.length()) {
                return mInsertText.charAt(index - insertOffset);
            }
            return mOriginal.charAt(index - mInsertText.length());
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            StringBuilder stringBuilder = new StringBuilder();
            for (int index = start; index < end; ++index) {
                stringBuilder.append(charAt(index));
            }
            return stringBuilder.toString();
        }

        @Override
        public String toString() {
            StringBuilder stringBuilder = new StringBuilder();
            for (int index = 0; index < length(); ++index) {
                stringBuilder.append(charAt(index));
            }
            return stringBuilder.toString();
        }

        static final NoCopySpan INSERT_POINT = new NoCopySpan() { };
    }
}
