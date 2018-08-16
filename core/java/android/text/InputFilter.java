/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.UnsupportedAppUsage;

import com.android.internal.util.Preconditions;

import java.util.Locale;

/**
 * InputFilters can be attached to {@link Editable}s to constrain the
 * changes that can be made to them.
 */
public interface InputFilter
{
    /**
     * This method is called when the buffer is going to replace the
     * range <code>dstart &hellip; dend</code> of <code>dest</code>
     * with the new text from the range <code>start &hellip; end</code>
     * of <code>source</code>.  Return the CharSequence that you would
     * like to have placed there instead, including an empty string
     * if appropriate, or <code>null</code> to accept the original
     * replacement.  Be careful to not to reject 0-length replacements,
     * as this is what happens when you delete text.  Also beware that
     * you should not attempt to make any changes to <code>dest</code>
     * from this method; you may only examine it for context.
     *
     * Note: If <var>source</var> is an instance of {@link Spanned} or
     * {@link Spannable}, the span objects in the <var>source</var> should be
     * copied into the filtered result (i.e. the non-null return value).
     * {@link TextUtils#copySpansFrom} can be used for convenience if the
     * span boundary indices would be remaining identical relative to the source.
     */
    public CharSequence filter(CharSequence source, int start, int end,
                               Spanned dest, int dstart, int dend);

    /**
     * This filter will capitalize all the lowercase and titlecase letters that are added
     * through edits. (Note that if there are no lowercase or titlecase letters in the input, the
     * text would not be transformed, even if the result of capitalization of the string is
     * different from the string.)
     */
    public static class AllCaps implements InputFilter {
        private final Locale mLocale;

        public AllCaps() {
            mLocale = null;
        }

        /**
         * Constructs a locale-specific AllCaps filter, to make sure capitalization rules of that
         * locale are used for transforming the sequence.
         */
        public AllCaps(@NonNull Locale locale) {
            Preconditions.checkNotNull(locale);
            mLocale = locale;
        }

        public CharSequence filter(CharSequence source, int start, int end,
                                   Spanned dest, int dstart, int dend) {
            final CharSequence wrapper = new CharSequenceWrapper(source, start, end);

            boolean lowerOrTitleFound = false;
            final int length = end - start;
            for (int i = 0, cp; i < length; i += Character.charCount(cp)) {
                // We access 'wrapper' instead of 'source' to make sure no code unit beyond 'end' is
                // ever accessed.
                cp = Character.codePointAt(wrapper, i);
                if (Character.isLowerCase(cp) || Character.isTitleCase(cp)) {
                    lowerOrTitleFound = true;
                    break;
                }
            }
            if (!lowerOrTitleFound) {
                return null; // keep original
            }

            final boolean copySpans = source instanceof Spanned;
            final CharSequence upper = TextUtils.toUpperCase(mLocale, wrapper, copySpans);
            if (upper == wrapper) {
                // Nothing was changed in the uppercasing operation. This is weird, since
                // we had found at least one lowercase or titlecase character. But we can't
                // do anything better than keeping the original in this case.
                return null; // keep original
            }
            // Return a SpannableString or String for backward compatibility.
            return copySpans ? new SpannableString(upper) : upper.toString();
        }

        private static class CharSequenceWrapper implements CharSequence, Spanned {
            private final CharSequence mSource;
            private final int mStart, mEnd;
            private final int mLength;

            CharSequenceWrapper(CharSequence source, int start, int end) {
                mSource = source;
                mStart = start;
                mEnd = end;
                mLength = end - start;
            }

            public int length() {
                return mLength;
            }

            public char charAt(int index) {
                if (index < 0 || index >= mLength) {
                    throw new IndexOutOfBoundsException();
                }
                return mSource.charAt(mStart + index);
            }

            public CharSequence subSequence(int start, int end) {
                if (start < 0 || end < 0 || end > mLength || start > end) {
                    throw new IndexOutOfBoundsException();
                }
                return new CharSequenceWrapper(mSource, mStart + start, mStart + end);
            }

            public String toString() {
                return mSource.subSequence(mStart, mEnd).toString();
            }

            public <T> T[] getSpans(int start, int end, Class<T> type) {
                return ((Spanned) mSource).getSpans(mStart + start, mStart + end, type);
            }

            public int getSpanStart(Object tag) {
                return ((Spanned) mSource).getSpanStart(tag) - mStart;
            }

            public int getSpanEnd(Object tag) {
                return ((Spanned) mSource).getSpanEnd(tag) - mStart;
            }

            public int getSpanFlags(Object tag) {
                return ((Spanned) mSource).getSpanFlags(tag);
            }

            public int nextSpanTransition(int start, int limit, Class type) {
                return ((Spanned) mSource).nextSpanTransition(mStart + start, mStart + limit, type)
                        - mStart;
            }
        }
    }

    /**
     * This filter will constrain edits not to make the length of the text
     * greater than the specified length.
     */
    public static class LengthFilter implements InputFilter {
        @UnsupportedAppUsage
        private final int mMax;

        public LengthFilter(int max) {
            mMax = max;
        }

        public CharSequence filter(CharSequence source, int start, int end, Spanned dest,
                int dstart, int dend) {
            int keep = mMax - (dest.length() - (dend - dstart));
            if (keep <= 0) {
                return "";
            } else if (keep >= end - start) {
                return null; // keep original
            } else {
                keep += start;
                if (Character.isHighSurrogate(source.charAt(keep - 1))) {
                    --keep;
                    if (keep == start) {
                        return "";
                    }
                }
                return source.subSequence(start, keep);
            }
        }

        /**
         * @return the maximum length enforced by this input filter
         */
        public int getMax() {
            return mMax;
        }
    }
}
