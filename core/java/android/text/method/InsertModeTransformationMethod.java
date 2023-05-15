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

package android.text.method;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ReplacementSpan;
import android.util.DisplayMetrics;
import android.util.MathUtils;
import android.util.TypedValue;
import android.view.View;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;

import java.lang.reflect.Array;

/**
 * The transformation method used by handwriting insert mode.
 * This transformation will insert a placeholder string to the original text at the given
 * offset. And it also provides a highlight range for the newly inserted text and the placeholder
 * text.
 *
 * For example,
 *   original text: "Hello world"
 *   insert mode is started at index:  5,
 *   placeholder text: "\n\n"
 * The transformed text will be: "Hello\n\n world", and the highlight range will be [5, 7)
 * including the inserted placeholder text.
 *
 * If " abc" is inserted to the original text at index 5,
 *   the new original text: "Hello abc world"
 *   the new transformed text: "hello abc\n\n world", and the highlight range will be [5, 11).
 * @hide
 */
public class InsertModeTransformationMethod implements TransformationMethod, TextWatcher {
    /** The start offset of the highlight range in the original text, inclusive. */
    private int mStart;
    /**
     * The end offset of the highlight range in the original text, exclusive. The placeholder text
     * is also inserted at this index.
     */
    private int mEnd;
    /** The transformation method that's already set on the {@link android.widget.TextView}. */
    private final TransformationMethod mOldTransformationMethod;
    /** Whether the {@link android.widget.TextView} is single-lined. */
    private final boolean mSingleLine;

    /**
     * @param offset the original offset to start the insert mode. It must be in the range from 0
     *               to the length of the transformed text.
     * @param singleLine whether the text is single line.
     * @param oldTransformationMethod the old transformation method at the
     * {@link android.widget.TextView}. If it's not null, this {@link TransformationMethod} will
     * first call {@link TransformationMethod#getTransformation(CharSequence, View)} on the old one,
     * and then do the transformation for the insert mode.
     *
     */
    public InsertModeTransformationMethod(@IntRange(from = 0) int offset, boolean singleLine,
            @NonNull TransformationMethod oldTransformationMethod) {
        this(offset, offset, singleLine, oldTransformationMethod);
    }

    private InsertModeTransformationMethod(int start, int end, boolean singleLine,
            @NonNull TransformationMethod oldTransformationMethod) {
        mStart = start;
        mEnd = end;
        mSingleLine = singleLine;
        mOldTransformationMethod = oldTransformationMethod;
    }

    /**
     * Create a new {@code InsertModeTransformation} with the given new inner
     * {@code oldTransformationMethod} and the {@code singleLine} value. The returned
     * {@link InsertModeTransformationMethod} will keep the highlight range.
     *
     * @param oldTransformationMethod the updated inner transformation method at the
     * {@link android.widget.TextView}.
     * @param singleLine the updated singleLine value.
     * @return the new {@link InsertModeTransformationMethod} with the updated
     * {@code oldTransformationMethod} and {@code singleLine} value.
     */
    public InsertModeTransformationMethod update(TransformationMethod oldTransformationMethod,
            boolean singleLine) {
        return new InsertModeTransformationMethod(mStart, mEnd, singleLine,
                oldTransformationMethod);
    }

    public TransformationMethod getOldTransformationMethod() {
        return mOldTransformationMethod;
    }

    private CharSequence getPlaceholderText(View view) {
        if (!mSingleLine) {
            return  "\n\n";
        }
        final SpannableString singleLinePlaceholder = new SpannableString("\uFFFD");
        final DisplayMetrics displayMetrics = view.getResources().getDisplayMetrics();
        final int widthPx = (int) Math.ceil(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 108, displayMetrics));

        singleLinePlaceholder.setSpan(new SingleLinePlaceholderSpan(widthPx), 0, 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return singleLinePlaceholder;
    }

    @Override
    public CharSequence getTransformation(CharSequence source, View view) {
        final CharSequence charSequence;
        if (mOldTransformationMethod != null) {
            charSequence = mOldTransformationMethod.getTransformation(source, view);
            if (source instanceof Spannable) {
                final Spannable spannable = (Spannable) source;
                spannable.setSpan(mOldTransformationMethod, 0, spannable.length(),
                        Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            }
        } else {
            charSequence = source;
        }

        final CharSequence placeholderText = getPlaceholderText(view);
        return new TransformedText(charSequence, placeholderText);
    }

    @Override
    public void onFocusChanged(View view, CharSequence sourceText, boolean focused, int direction,
            Rect previouslyFocusedRect) {
        if (mOldTransformationMethod != null) {
            mOldTransformationMethod.onFocusChanged(view, sourceText, focused, direction,
                    previouslyFocusedRect);
        }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        // The text change is after the offset where placeholder is inserted, return.
        if (start > mEnd) return;
        final int diff = count - before;

        // Note: If start == mStart and before == 0, the change is also considered after the
        // highlight start. It won't modify the mStart in this case.
        if (start < mStart) {
            if (start + before <= mStart) {
                // The text change is before the highlight start, move the highlight start.
                mStart += diff;
            } else {
                // The text change covers the highlight start. Extend the highlight start to the
                // change start. This should be a rare case.
                mStart = start;
            }
        }

        if (start + before <= mEnd) {
            // The text change is before the highlight end, move the highlight end.
            mEnd += diff;
        } else if (start < mEnd) {
            // The text change covers the highlight end. Extend the highlight end to the
            // change end. This should be a rare case.
            mEnd = start + count;
        }
    }

    @Override
    public void afterTextChanged(Editable s) { }

    /**
     * The transformed text returned by the {@link InsertModeTransformationMethod}.
     */
    public class TransformedText implements OffsetMapping, Spanned {
        private final CharSequence mOriginal;
        private final CharSequence mPlaceholder;
        private final Spanned mSpannedOriginal;
        private final Spanned mSpannedPlaceholder;

        TransformedText(CharSequence original, CharSequence placeholder) {
            mOriginal = original;
            if (original instanceof Spanned) {
                mSpannedOriginal = (Spanned) original;
            } else {
                mSpannedOriginal = null;
            }
            mPlaceholder = placeholder;
            if (placeholder instanceof Spanned) {
                mSpannedPlaceholder = (Spanned) placeholder;
            } else {
                mSpannedPlaceholder = null;
            }
        }

        @Override
        public int originalToTransformed(int offset, int strategy) {
            if (offset < 0) return offset;
            Preconditions.checkArgumentInRange(offset, 0, mOriginal.length(), "offset");
            if (offset == mEnd && strategy == OffsetMapping.MAP_STRATEGY_CURSOR) {
                // The offset equals to mEnd. For a cursor position it's considered before the
                // inserted placeholder text.
                return offset;
            }
            if (offset < mEnd) {
                return offset;
            }
            return offset + mPlaceholder.length();
        }

        @Override
        public int transformedToOriginal(int offset, int strategy) {
            if (offset < 0) return offset;
            Preconditions.checkArgumentInRange(offset, 0, length(), "offset");

            // The placeholder text is inserted at mEnd. Because the offset is smaller than
            // mEnd, we can directly return it.
            if (offset < mEnd) return offset;
            if (offset < mEnd + mPlaceholder.length()) {
                return mEnd;
            }
            return offset - mPlaceholder.length();
        }

        @Override
        public void originalToTransformed(TextUpdate textUpdate) {
            if (textUpdate.where > mEnd) {
                textUpdate.where += mPlaceholder.length();
            } else if (textUpdate.where + textUpdate.before > mEnd) {
                // The update also covers the placeholder string.
                textUpdate.before += mPlaceholder.length();
                textUpdate.after += mPlaceholder.length();
            }
        }

        @Override
        public int length() {
            return mOriginal.length() + mPlaceholder.length();
        }

        @Override
        public char charAt(int index) {
            Preconditions.checkArgumentInRange(index, 0, length() - 1, "index");
            if (index < mEnd) {
                return mOriginal.charAt(index);
            }
            if (index < mEnd + mPlaceholder.length()) {
                return mPlaceholder.charAt(index - mEnd);
            }
            return mOriginal.charAt(index - mPlaceholder.length());
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            if (end < start || start < 0 || end > length()) {
                throw new IndexOutOfBoundsException();
            }
            if (start == end) {
                return "";
            }

            final int placeholderLength = mPlaceholder.length();

            final int seg1Start = Math.min(start, mEnd);
            final int seg1End = Math.min(end, mEnd);

            final int seg2Start = MathUtils.constrain(start - mEnd, 0, placeholderLength);
            final int seg2End = MathUtils.constrain(end - mEnd, 0, placeholderLength);

            final int seg3Start = Math.max(start - placeholderLength, mEnd);
            final int seg3End = Math.max(end - placeholderLength, mEnd);

            return TextUtils.concat(
                    mOriginal.subSequence(seg1Start, seg1End),
                    mPlaceholder.subSequence(seg2Start, seg2End),
                    mOriginal.subSequence(seg3Start, seg3End));
        }

        @Override
        public String toString() {
            return String.valueOf(mOriginal.subSequence(0, mEnd))
                    + mPlaceholder
                    + mOriginal.subSequence(mEnd, mOriginal.length());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T[] getSpans(int start, int end, Class<T> type) {
            if (end < start) {
                return ArrayUtils.emptyArray(type);
            }

            T[] spansOriginal = null;
            if (mSpannedOriginal != null) {
                final int originalStart =
                        transformedToOriginal(start, OffsetMapping.MAP_STRATEGY_CURSOR);
                final int originalEnd =
                        transformedToOriginal(end, OffsetMapping.MAP_STRATEGY_CURSOR);
                // We can't simply call SpannedString.getSpans(originalStart, originalEnd) here.
                // When start == end SpannedString.getSpans returns spans whose spanEnd == start.
                // For example,
                //   text: abcd  span: [1, 3)
                // getSpan(3, 3) will return the span [1, 3) but getSpan(3, 4) returns no span.
                //
                // This creates some special cases when originalStart == originalEnd.
                // For example:
                //   original text: abcd    span1: [1, 3) span2: [3, 4) span3: [3, 3)
                //   transformed text: abc\n\nd    span1: [1, 3) span2: [5, 6) span3: [3, 3)
                // Case 1:
                // When start = 3 and end = 4, transformedText#getSpan(3, 4) should return span3.
                // However, because originalStart == originalEnd == 3, originalText#getSpan(3, 3)
                // returns span1, span2 and span3.
                //
                // Case 2:
                // When start == end == 4, transformedText#getSpan(4, 4) should return nothing.
                // However, because originalStart == originalEnd == 3, originalText#getSpan(3, 3)
                // return span1, span2 and span3.
                //
                // Case 3:
                // When start == end == 5, transformedText#getSpan(5, 5) should return span2.
                // However, because originalStart == originalEnd == 3, originalText#getSpan(3, 3)
                // return span1,  span2 and span3.
                //
                // To handle the issue, we need to filter out the invalid spans.
                spansOriginal = mSpannedOriginal.getSpans(originalStart, originalEnd, type);
                spansOriginal = ArrayUtils.filter(spansOriginal,
                        size -> (T[]) Array.newInstance(type, size),
                        span -> intersect(getSpanStart(span), getSpanEnd(span), start, end));
            }

            T[] spansPlaceholder = null;
            if (mSpannedPlaceholder != null
                    && intersect(start, end, mEnd, mEnd + mPlaceholder.length())) {
                int placeholderStart = Math.max(start - mEnd, 0);
                int placeholderEnd = Math.min(end - mEnd, mPlaceholder.length());
                spansPlaceholder =
                        mSpannedPlaceholder.getSpans(placeholderStart, placeholderEnd, type);
            }

            // TODO: sort the spans based on their priority.
            return ArrayUtils.concat(type, spansOriginal, spansPlaceholder);
        }

        @Override
        public int getSpanStart(Object tag) {
            if (mSpannedOriginal != null) {
                final int index = mSpannedOriginal.getSpanStart(tag);
                if (index >= 0) {
                    // When originalSpanStart == originalSpanEnd == mEnd, the span should be
                    // considered "before" the placeholder text. So we return the originalSpanStart.
                    if (index < mEnd
                            || (index == mEnd && mSpannedOriginal.getSpanEnd(tag) == index)) {
                        return index;
                    }
                    return index + mPlaceholder.length();
                }
            }

            // The span is not on original text, try find it on the placeholder.
            if (mSpannedPlaceholder != null) {
                final int index = mSpannedPlaceholder.getSpanStart(tag);
                if (index >= 0) {
                    // Find the span on placeholder, transform it and return.
                    return index + mEnd;
                }
            }
            return -1;
        }

        @Override
        public int getSpanEnd(Object tag) {
            if (mSpannedOriginal != null) {
                final int index = mSpannedOriginal.getSpanEnd(tag);
                if (index >= 0) {
                    if (index <= mEnd) {
                        return index;
                    }
                    return index + mPlaceholder.length();
                }
            }

            // The span is not on original text, try find it on the placeholder.
            if (mSpannedPlaceholder != null) {
                final int index = mSpannedPlaceholder.getSpanEnd(tag);
                if (index >= 0) {
                    // Find the span on placeholder, transform it and return.
                    return index + mEnd;
                }
            }
            return -1;
        }

        @Override
        public int getSpanFlags(Object tag) {
            if (mSpannedOriginal != null) {
                final int flags = mSpannedOriginal.getSpanFlags(tag);
                if (flags != 0) {
                    return flags;
                }
            }
            if (mSpannedPlaceholder != null) {
                return mSpannedPlaceholder.getSpanFlags(tag);
            }
            return 0;
        }

        @Override
        public int nextSpanTransition(int start, int limit, Class type) {
            if (limit <= start) return limit;
            final Object[] spans = getSpans(start, limit, type);
            for (int i = 0; i < spans.length; ++i) {
                int spanStart = getSpanStart(spans[i]);
                int spanEnd = getSpanEnd(spans[i]);
                if (start < spanStart && spanStart < limit) {
                    limit = spanStart;
                }
                if (start < spanEnd && spanEnd < limit) {
                    limit = spanEnd;
                }
            }
            return limit;
        }

        /**
         * Return the start index of the highlight range for the insert mode, inclusive.
         */
        public int getHighlightStart() {
            return mStart;
        }

        /**
         * Return the end index of the highlight range for the insert mode, exclusive.
         */
        public int getHighlightEnd() {
            return mEnd + mPlaceholder.length();
        }
    }

    /**
     * The placeholder span used for single line
     */
    public static class SingleLinePlaceholderSpan extends ReplacementSpan {
        private final int mWidth;
        SingleLinePlaceholderSpan(int width) {
            mWidth = width;
        }
        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                @Nullable Paint.FontMetricsInt fm) {
            return mWidth;
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x,
                int top, int y, int bottom, @NonNull Paint paint) { }
    }

    /**
     * Return true if the given two ranges intersects. This logic is the same one used in
     * {@link Spanned} to determine whether a span range intersect with the query range.
     */
    private static boolean intersect(int s1, int e1, int s2, int e2) {
        if (s1 > e2) return false;
        if (e1 < s2) return false;
        if (s1 != e1 && s2 != e2) {
            if (s1 == e2) return false;
            if (e1 == s2) return false;
        }
        return true;
    }
}
