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

import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.GrowingArrayUtils;

import libcore.util.EmptyArray;

import java.lang.reflect.Array;

/* package */ abstract class SpannableStringInternal
{
    /* package */ SpannableStringInternal(CharSequence source,
                                          int start, int end, boolean ignoreNoCopySpan) {
        if (start == 0 && end == source.length())
            mText = source.toString();
        else
            mText = source.toString().substring(start, end);

        mSpans = EmptyArray.OBJECT;
        // Invariant: mSpanData.length = mSpans.length * COLUMNS
        mSpanData = EmptyArray.INT;

        if (source instanceof Spanned) {
            if (source instanceof SpannableStringInternal) {
                copySpansFromInternal(
                        (SpannableStringInternal) source, start, end, ignoreNoCopySpan);
            } else {
                copySpansFromSpanned((Spanned) source, start, end, ignoreNoCopySpan);
            }
        }
    }

    /**
     * This unused method is left since this is listed in hidden api list.
     *
     * Due to backward compatibility reasons, we copy even NoCopySpan by default
     */
    @UnsupportedAppUsage
    /* package */ SpannableStringInternal(CharSequence source, int start, int end) {
        this(source, start, end, false /* ignoreNoCopySpan */);
    }

    /**
     * Copies another {@link Spanned} object's spans between [start, end] into this object.
     *
     * @param src Source object to copy from.
     * @param start Start index in the source object.
     * @param end End index in the source object.
     * @param ignoreNoCopySpan whether to copy NoCopySpans in the {@code source}
     */
    private void copySpansFromSpanned(Spanned src, int start, int end, boolean ignoreNoCopySpan) {
        Object[] spans = src.getSpans(start, end, Object.class);

        for (int i = 0; i < spans.length; i++) {
            if (ignoreNoCopySpan && spans[i] instanceof NoCopySpan) {
                continue;
            }
            int st = src.getSpanStart(spans[i]);
            int en = src.getSpanEnd(spans[i]);
            int fl = src.getSpanFlags(spans[i]);

            if (st < start)
                st = start;
            if (en > end)
                en = end;

            setSpan(spans[i], st - start, en - start, fl, false/*enforceParagraph*/);
        }
    }

    /**
     * Copies a {@link SpannableStringInternal} object's spans between [start, end] into this
     * object.
     *
     * @param src Source object to copy from.
     * @param start Start index in the source object.
     * @param end End index in the source object.
     * @param ignoreNoCopySpan copy NoCopySpan for backward compatible reasons.
     */
    private void copySpansFromInternal(SpannableStringInternal src, int start, int end,
            boolean ignoreNoCopySpan) {
        int count = 0;
        final int[] srcData = src.mSpanData;
        final Object[] srcSpans = src.mSpans;
        final int limit = src.mSpanCount;
        boolean hasNoCopySpan = false;

        for (int i = 0; i < limit; i++) {
            int spanStart = srcData[i * COLUMNS + START];
            int spanEnd = srcData[i * COLUMNS + END];
            if (isOutOfCopyRange(start, end, spanStart, spanEnd)) continue;
            if (srcSpans[i] instanceof NoCopySpan) {
                hasNoCopySpan = true;
                if (ignoreNoCopySpan) {
                    continue;
                }
            }
            count++;
        }

        if (count == 0) return;

        if (!hasNoCopySpan && start == 0 && end == src.length()) {
            mSpans = ArrayUtils.newUnpaddedObjectArray(src.mSpans.length);
            mSpanData = new int[src.mSpanData.length];
            mSpanCount = src.mSpanCount;
            System.arraycopy(src.mSpans, 0, mSpans, 0, src.mSpans.length);
            System.arraycopy(src.mSpanData, 0, mSpanData, 0, mSpanData.length);
        } else {
            mSpanCount = count;
            mSpans = ArrayUtils.newUnpaddedObjectArray(mSpanCount);
            mSpanData = new int[mSpans.length * COLUMNS];
            for (int i = 0, j = 0; i < limit; i++) {
                int spanStart = srcData[i * COLUMNS + START];
                int spanEnd = srcData[i * COLUMNS + END];
                if (isOutOfCopyRange(start, end, spanStart, spanEnd)
                        || (ignoreNoCopySpan && srcSpans[i] instanceof NoCopySpan)) {
                    continue;
                }
                if (spanStart < start) spanStart = start;
                if (spanEnd > end) spanEnd = end;

                mSpans[j] = srcSpans[i];
                mSpanData[j * COLUMNS + START] = spanStart - start;
                mSpanData[j * COLUMNS + END] = spanEnd - start;
                mSpanData[j * COLUMNS + FLAGS] = srcData[i * COLUMNS + FLAGS];
                j++;
            }
        }
    }

    /**
     * Checks if [spanStart, spanEnd] interval is excluded from [start, end].
     *
     * @return True if excluded, false if included.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private final boolean isOutOfCopyRange(int start, int end, int spanStart, int spanEnd) {
        if (spanStart > end || spanEnd < start) return true;
        if (spanStart != spanEnd && start != end) {
            if (spanStart == end || spanEnd == start) return true;
        }
        return false;
    }

    public final int length() {
        return mText.length();
    }

    public final char charAt(int i) {
        return mText.charAt(i);
    }

    public final String toString() {
        return mText;
    }

    /* subclasses must do subSequence() to preserve type */

    public final void getChars(int start, int end, char[] dest, int off) {
        mText.getChars(start, end, dest, off);
    }

    @UnsupportedAppUsage
    /* package */ void setSpan(Object what, int start, int end, int flags) {
        setSpan(what, start, end, flags, true/*enforceParagraph*/);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private boolean isIndexFollowsNextLine(int index) {
        return index != 0 && index != length() && charAt(index - 1) != '\n';
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void setSpan(Object what, int start, int end, int flags, boolean enforceParagraph) {
        int nstart = start;
        int nend = end;

        checkRange("setSpan", start, end);

        if ((flags & Spannable.SPAN_PARAGRAPH) == Spannable.SPAN_PARAGRAPH) {
            if (isIndexFollowsNextLine(start)) {
                if (!enforceParagraph) {
                    // do not set the span
                    return;
                }
                throw new RuntimeException("PARAGRAPH span must start at paragraph boundary"
                        + " (" + start + " follows " + charAt(start - 1) + ")");
            }

            if (isIndexFollowsNextLine(end)) {
                if (!enforceParagraph) {
                    // do not set the span
                    return;
                }
                throw new RuntimeException("PARAGRAPH span must end at paragraph boundary"
                        + " (" + end + " follows " + charAt(end - 1) + ")");
            }
        }

        int count = mSpanCount;
        Object[] spans = mSpans;
        int[] data = mSpanData;

        for (int i = 0; i < count; i++) {
            if (spans[i] == what) {
                int ostart = data[i * COLUMNS + START];
                int oend = data[i * COLUMNS + END];

                data[i * COLUMNS + START] = start;
                data[i * COLUMNS + END] = end;
                data[i * COLUMNS + FLAGS] = flags;

                sendSpanChanged(what, ostart, oend, nstart, nend);
                return;
            }
        }

        if (mSpanCount + 1 >= mSpans.length) {
            Object[] newtags = ArrayUtils.newUnpaddedObjectArray(
                    GrowingArrayUtils.growSize(mSpanCount));
            int[] newdata = new int[newtags.length * 3];

            System.arraycopy(mSpans, 0, newtags, 0, mSpanCount);
            System.arraycopy(mSpanData, 0, newdata, 0, mSpanCount * 3);

            mSpans = newtags;
            mSpanData = newdata;
        }

        mSpans[mSpanCount] = what;
        mSpanData[mSpanCount * COLUMNS + START] = start;
        mSpanData[mSpanCount * COLUMNS + END] = end;
        mSpanData[mSpanCount * COLUMNS + FLAGS] = flags;
        mSpanCount++;

        if (this instanceof Spannable)
            sendSpanAdded(what, nstart, nend);
    }

    @UnsupportedAppUsage
    /* package */ void removeSpan(Object what) {
        removeSpan(what, 0 /* flags */);
    }

    /**
     * @hide
     */
    public void removeSpan(Object what, int flags) {
        int count = mSpanCount;
        Object[] spans = mSpans;
        int[] data = mSpanData;

        for (int i = count - 1; i >= 0; i--) {
            if (spans[i] == what) {
                int ostart = data[i * COLUMNS + START];
                int oend = data[i * COLUMNS + END];

                int c = count - (i + 1);

                System.arraycopy(spans, i + 1, spans, i, c);
                System.arraycopy(data, (i + 1) * COLUMNS,
                        data, i * COLUMNS, c * COLUMNS);

                mSpanCount--;

                if ((flags & Spanned.SPAN_INTERMEDIATE) == 0) {
                    sendSpanRemoved(what, ostart, oend);
                }
                return;
            }
        }
    }

    @UnsupportedAppUsage
    public int getSpanStart(Object what) {
        int count = mSpanCount;
        Object[] spans = mSpans;
        int[] data = mSpanData;

        for (int i = count - 1; i >= 0; i--) {
            if (spans[i] == what) {
                return data[i * COLUMNS + START];
            }
        }

        return -1;
    }

    @UnsupportedAppUsage
    public int getSpanEnd(Object what) {
        int count = mSpanCount;
        Object[] spans = mSpans;
        int[] data = mSpanData;

        for (int i = count - 1; i >= 0; i--) {
            if (spans[i] == what) {
                return data[i * COLUMNS + END];
            }
        }

        return -1;
    }

    @UnsupportedAppUsage
    public int getSpanFlags(Object what) {
        int count = mSpanCount;
        Object[] spans = mSpans;
        int[] data = mSpanData;

        for (int i = count - 1; i >= 0; i--) {
            if (spans[i] == what) {
                return data[i * COLUMNS + FLAGS];
            }
        }

        return 0; 
    }

    @UnsupportedAppUsage
    public <T> T[] getSpans(int queryStart, int queryEnd, Class<T> kind) {
        int count = 0;

        int spanCount = mSpanCount;
        Object[] spans = mSpans;
        int[] data = mSpanData;
        Object[] ret = null;
        Object ret1 = null;

        for (int i = 0; i < spanCount; i++) {
            int spanStart = data[i * COLUMNS + START];
            int spanEnd = data[i * COLUMNS + END];

            if (spanStart > queryEnd) {
                continue;
            }
            if (spanEnd < queryStart) {
                continue;
            }

            if (spanStart != spanEnd && queryStart != queryEnd) {
                if (spanStart == queryEnd) {
                    continue;
                }
                if (spanEnd == queryStart) {
                    continue;
                }
            }

            // verify span class as late as possible, since it is expensive
            if (kind != null && kind != Object.class && !kind.isInstance(spans[i])) {
                continue;
            }

            if (count == 0) {
                ret1 = spans[i];
                count++;
            } else {
                if (count == 1) {
                    ret = (Object[]) Array.newInstance(kind, spanCount - i + 1);
                    ret[0] = ret1;
                }

                int prio = data[i * COLUMNS + FLAGS] & Spanned.SPAN_PRIORITY;
                if (prio != 0) {
                    int j;

                    for (j = 0; j < count; j++) {
                        int p = getSpanFlags(ret[j]) & Spanned.SPAN_PRIORITY;

                        if (prio > p) {
                            break;
                        }
                    }

                    System.arraycopy(ret, j, ret, j + 1, count - j);
                    ret[j] = spans[i];
                    count++;
                } else {
                    ret[count++] = spans[i];
                }
            }
        }

        if (count == 0) {
            return (T[]) ArrayUtils.emptyArray(kind);
        }
        if (count == 1) {
            ret = (Object[]) Array.newInstance(kind, 1);
            ret[0] = ret1;
            return (T[]) ret;
        }
        if (count == ret.length) {
            return (T[]) ret;
        }

        Object[] nret = (Object[]) Array.newInstance(kind, count);
        System.arraycopy(ret, 0, nret, 0, count);
        return (T[]) nret;
    }

    @UnsupportedAppUsage
    public int nextSpanTransition(int start, int limit, Class kind) {
        int count = mSpanCount;
        Object[] spans = mSpans;
        int[] data = mSpanData;

        if (kind == null) {
            kind = Object.class;
        }

        for (int i = 0; i < count; i++) {
            int st = data[i * COLUMNS + START];
            int en = data[i * COLUMNS + END];

            if (st > start && st < limit && kind.isInstance(spans[i]))
                limit = st;
            if (en > start && en < limit && kind.isInstance(spans[i]))
                limit = en;
        }

        return limit;
    }

    @UnsupportedAppUsage
    private void sendSpanAdded(Object what, int start, int end) {
        SpanWatcher[] recip = getSpans(start, end, SpanWatcher.class);
        int n = recip.length;

        for (int i = 0; i < n; i++) {
            recip[i].onSpanAdded((Spannable) this, what, start, end);
        }
    }

    @UnsupportedAppUsage
    private void sendSpanRemoved(Object what, int start, int end) {
        SpanWatcher[] recip = getSpans(start, end, SpanWatcher.class);
        int n = recip.length;

        for (int i = 0; i < n; i++) {
            recip[i].onSpanRemoved((Spannable) this, what, start, end);
        }
    }

    @UnsupportedAppUsage
    private void sendSpanChanged(Object what, int s, int e, int st, int en) {
        SpanWatcher[] recip = getSpans(Math.min(s, st), Math.max(e, en),
                                       SpanWatcher.class);
        int n = recip.length;

        for (int i = 0; i < n; i++) {
            recip[i].onSpanChanged((Spannable) this, what, s, e, st, en);
        }
    }

    @UnsupportedAppUsage
    private static String region(int start, int end) {
        return "(" + start + " ... " + end + ")";
    }

    @UnsupportedAppUsage
    private void checkRange(final String operation, int start, int end) {
        if (end < start) {
            throw new IndexOutOfBoundsException(operation + " " +
                                                region(start, end) +
                                                " has end before start");
        }

        int len = length();

        if (start > len || end > len) {
            throw new IndexOutOfBoundsException(operation + " " +
                                                region(start, end) +
                                                " ends beyond length " + len);
        }

        if (start < 0 || end < 0) {
            throw new IndexOutOfBoundsException(operation + " " +
                                                region(start, end) +
                                                " starts before 0");
        }
    }

    // Same as SpannableStringBuilder
    @Override
    public boolean equals(@Nullable Object o) {
        if (o instanceof Spanned &&
                toString().equals(o.toString())) {
            final Spanned other = (Spanned) o;
            // Check span data
            final Object[] otherSpans = other.getSpans(0, other.length(), Object.class);
            final Object[] thisSpans = getSpans(0, length(), Object.class);
            if (mSpanCount == otherSpans.length) {
                for (int i = 0; i < mSpanCount; ++i) {
                    final Object thisSpan = thisSpans[i];
                    final Object otherSpan = otherSpans[i];
                    if (thisSpan == this) {
                        if (other != otherSpan ||
                                getSpanStart(thisSpan) != other.getSpanStart(otherSpan) ||
                                getSpanEnd(thisSpan) != other.getSpanEnd(otherSpan) ||
                                getSpanFlags(thisSpan) != other.getSpanFlags(otherSpan)) {
                            return false;
                        }
                    } else if (!thisSpan.equals(otherSpan) ||
                            getSpanStart(thisSpan) != other.getSpanStart(otherSpan) ||
                            getSpanEnd(thisSpan) != other.getSpanEnd(otherSpan) ||
                            getSpanFlags(thisSpan) != other.getSpanFlags(otherSpan)) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    // Same as SpannableStringBuilder
    @Override
    public int hashCode() {
        int hash = toString().hashCode();
        hash = hash * 31 + mSpanCount;
        for (int i = 0; i < mSpanCount; ++i) {
            Object span = mSpans[i];
            if (span != this) {
                hash = hash * 31 + span.hashCode();
            }
            hash = hash * 31 + getSpanStart(span);
            hash = hash * 31 + getSpanEnd(span);
            hash = hash * 31 + getSpanFlags(span);
        }
        return hash;
    }

    /**
     * Following two unused methods are left since these are listed in hidden api list.
     *
     * Due to backward compatibility reasons, we copy even NoCopySpan by default
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void copySpans(Spanned src, int start, int end) {
        copySpansFromSpanned(src, start, end, false);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void copySpans(SpannableStringInternal src, int start, int end) {
        copySpansFromInternal(src, start, end, false);
    }



    @UnsupportedAppUsage
    private String mText;
    @UnsupportedAppUsage
    private Object[] mSpans;
    @UnsupportedAppUsage
    private int[] mSpanData;
    @UnsupportedAppUsage
    private int mSpanCount;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    /* package */ static final Object[] EMPTY = new Object[0];

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private static final int START = 0;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private static final int END = 1;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private static final int FLAGS = 2;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private static final int COLUMNS = 3;
}
