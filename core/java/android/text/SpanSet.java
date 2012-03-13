/*
 * Copyright (C) 2012 The Android Open Source Project
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

import java.lang.reflect.Array;

/**
 * A cached set of spans. Caches the result of {@link Spanned#getSpans(int, int, Class)} and then
 * provides faster access to {@link Spanned#nextSpanTransition(int, int, Class)}.
 *
 * Fields are left public for a convenient direct access.
 *
 * Note that empty spans are ignored by this class.
 * @hide
 */
public class SpanSet<E> {
    private final Class<? extends E> classType;

    int numberOfSpans;
    E[] spans;
    int[] spanStarts;
    int[] spanEnds;
    int[] spanFlags;

    SpanSet(Class<? extends E> type) {
        classType = type;
        numberOfSpans = 0;
    }

    @SuppressWarnings("unchecked")
    public void init(Spanned spanned, int start, int limit) {
        final E[] allSpans = spanned.getSpans(start, limit, classType);
        final int length = allSpans.length;

        if (length > 0 && (spans == null || spans.length < length)) {
            // These arrays may end up being too large because of the discarded empty spans
            spans = (E[]) Array.newInstance(classType, length);
            spanStarts = new int[length];
            spanEnds = new int[length];
            spanFlags = new int[length];
        }

        numberOfSpans = 0;
        for (int i = 0; i < length; i++) {
            final E span = allSpans[i];

            final int spanStart = spanned.getSpanStart(span);
            final int spanEnd = spanned.getSpanEnd(span);
            if (spanStart == spanEnd) continue;

            final int spanFlag = spanned.getSpanFlags(span);

            spans[numberOfSpans] = span;
            spanStarts[numberOfSpans] = spanStart;
            spanEnds[numberOfSpans] = spanEnd;
            spanFlags[numberOfSpans] = spanFlag;

            numberOfSpans++;
        }
    }

    /**
     * Returns true if there are spans intersecting the given interval.
     * @param end must be strictly greater than start
     */
    public boolean hasSpansIntersecting(int start, int end) {
        for (int i = 0; i < numberOfSpans; i++) {
            // equal test is valid since both intervals are not empty by construction
            if (spanStarts[i] >= end || spanEnds[i] <= start) continue;
            return true;
        }
        return false;
    }

    /**
     * Similar to {@link Spanned#nextSpanTransition(int, int, Class)}
     */
    int getNextTransition(int start, int limit) {
        for (int i = 0; i < numberOfSpans; i++) {
            final int spanStart = spanStarts[i];
            final int spanEnd = spanEnds[i];
            if (spanStart > start && spanStart < limit) limit = spanStart;
            if (spanEnd > start && spanEnd < limit) limit = spanEnd;
        }
        return limit;
    }

    /**
     * Removes all internal references to the spans to avoid memory leaks.
     */
    public void recycle() {
        // The spans array is guaranteed to be not null when numberOfSpans is > 0
        for (int i = 0; i < numberOfSpans; i++) {
            spans[i] = null; // prevent a leak: no reference kept when TextLine is recycled
        }
    }
}
