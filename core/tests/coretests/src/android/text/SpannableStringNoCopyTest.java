/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.annotation.NonNull;
import android.text.style.QuoteSpan;
import android.text.style.UnderlineSpan;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SpannableStringNoCopyTest {
    @Test
    public void testCopyConstructor_copyNoCopySpans_SpannableStringInternalImpl() {
        final SpannableString first = new SpannableString("t\nest data");
        first.setSpan(new QuoteSpan(), 0, 2, Spanned.SPAN_PARAGRAPH);
        first.setSpan(new NoCopySpan.Concrete(), 2, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        first.setSpan(new UnderlineSpan(), 0, first.length(), Spanned.SPAN_PRIORITY);

        // By default, copy NoCopySpans
        final SpannedString copied = new SpannedString(first);
        final Object[] spans = copied.getSpans(0, copied.length(), Object.class);
        assertNotNull(spans);
        assertEquals(3, spans.length);
    }

    @Test
    public void testCopyConstructor_doesNotCopyNoCopySpans_SpannableStringInternalImpl() {
        final SpannableString first = new SpannableString("t\nest data");
        first.setSpan(new QuoteSpan(), 0, 2, Spanned.SPAN_PARAGRAPH);
        first.setSpan(new NoCopySpan.Concrete(), 2, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        first.setSpan(new UnderlineSpan(), 0, first.length(), Spanned.SPAN_PRIORITY);

        // Do not copy NoCopySpan if specified so.
        final SpannedString copied = new SpannedString(first, true /* ignoreNoCopySpan */);
        final Object[] spans = copied.getSpans(0, copied.length(), Object.class);
        assertNotNull(spans);
        assertEquals(2, spans.length);

        for (int i = 0; i < spans.length; i++) {
            assertFalse(spans[i] instanceof NoCopySpan);
        }
    }

    @Test
    public void testCopyConstructor_copyNoCopySpans_OtherSpannableImpl() {
        final SpannableString first = new SpannableString("t\nest data");
        first.setSpan(new QuoteSpan(), 0, 2, Spanned.SPAN_PARAGRAPH);
        first.setSpan(new NoCopySpan.Concrete(), 2, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        first.setSpan(new UnderlineSpan(), 0, first.length(), Spanned.SPAN_PRIORITY);

        // By default, copy NoCopySpans
        final SpannedString copied = new SpannedString(new CustomSpannable(first));
        final Object[] spans = copied.getSpans(0, copied.length(), Object.class);
        assertNotNull(spans);
        assertEquals(3, spans.length);
    }

    @Test
    public void testCopyConstructor_doesNotCopyNoCopySpans_OtherSpannableImpl() {
        final SpannableString first = new SpannableString("t\nest data");
        first.setSpan(new QuoteSpan(), 0, 2, Spanned.SPAN_PARAGRAPH);
        first.setSpan(new NoCopySpan.Concrete(), 2, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        first.setSpan(new UnderlineSpan(), 0, first.length(), Spanned.SPAN_PRIORITY);

        // Do not copy NoCopySpan if specified so.
        final SpannedString copied = new SpannedString(
                new CustomSpannable(first), true /* ignoreNoCopySpan */);
        final Object[] spans = copied.getSpans(0, copied.length(), Object.class);
        assertNotNull(spans);
        assertEquals(2, spans.length);

        for (int i = 0; i < spans.length; i++) {
            assertFalse(spans[i] instanceof NoCopySpan);
        }
    }

    // A custom implementation of Spannable.
    private static class CustomSpannable implements Spannable {
        private final @NonNull Spannable mText;

        CustomSpannable(@NonNull Spannable text) {
            mText = text;
        }

        @Override
        public void setSpan(Object what, int start, int end, int flags) {
            mText.setSpan(what, start, end, flags);
        }

        @Override
        public void removeSpan(Object what) {
            mText.removeSpan(what);
        }

        @Override
        public <T> T[] getSpans(int start, int end, Class<T> type) {
            return mText.getSpans(start, end, type);
        }

        @Override
        public int getSpanStart(Object tag) {
            return mText.getSpanStart(tag);
        }

        @Override
        public int getSpanEnd(Object tag) {
            return mText.getSpanEnd(tag);
        }

        @Override
        public int getSpanFlags(Object tag) {
            return mText.getSpanFlags(tag);
        }

        @Override
        public int nextSpanTransition(int start, int limit, Class type) {
            return mText.nextSpanTransition(start, limit, type);
        }

        @Override
        public int length() {
            return mText.length();
        }

        @Override
        public char charAt(int index) {
            return mText.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return mText.subSequence(start, end);
        }

        @Override
        public String toString() {
            return mText.toString();
        }
    };
}
