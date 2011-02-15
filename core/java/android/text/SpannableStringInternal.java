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

import com.android.internal.util.ArrayUtils;

import java.lang.reflect.Array;

/* package */ abstract class SpannableStringInternal
{
    /* package */ SpannableStringInternal(CharSequence source,
                                          int start, int end) {
        if (start == 0 && end == source.length())
            mText = source.toString();
        else
            mText = source.toString().substring(start, end);

        int initial = ArrayUtils.idealIntArraySize(0);
        mSpans = new Object[initial];
        mSpanData = new int[initial * 3];

        if (source instanceof Spanned) {
            Spanned sp = (Spanned) source;
            Object[] spans = sp.getSpans(start, end, Object.class);

            for (int i = 0; i < spans.length; i++) {
                int st = sp.getSpanStart(spans[i]);
                int en = sp.getSpanEnd(spans[i]);
                int fl = sp.getSpanFlags(spans[i]);

                if (st < start)
                    st = start;
                if (en > end)
                    en = end;

                setSpan(spans[i], st - start, en - start, fl);
            }
        }
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

    /* package */ void setSpan(Object what, int start, int end, int flags) {
        int nstart = start;
        int nend = end;

        checkRange("setSpan", start, end);

        if ((flags & Spannable.SPAN_PARAGRAPH) == Spannable.SPAN_PARAGRAPH) {
            if (start != 0 && start != length()) {
                char c = charAt(start - 1);

                if (c != '\n')
                    throw new RuntimeException(
                            "PARAGRAPH span must start at paragraph boundary" +
                            " (" + start + " follows " + c + ")");
            }

            if (end != 0 && end != length()) {
                char c = charAt(end - 1);

                if (c != '\n')
                    throw new RuntimeException(
                            "PARAGRAPH span must end at paragraph boundary" +
                            " (" + end + " follows " + c + ")");
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
            int newsize = ArrayUtils.idealIntArraySize(mSpanCount + 1);
            Object[] newtags = new Object[newsize];
            int[] newdata = new int[newsize * 3];

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

    /* package */ void removeSpan(Object what) {
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

                sendSpanRemoved(what, ostart, oend);
                return;
            }
        }
    }

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

    public <T> T[] getSpans(int queryStart, int queryEnd, Class<T> kind) {
        int count = 0;

        int spanCount = mSpanCount;
        Object[] spans = mSpans;
        int[] data = mSpanData;
        Object[] ret = null;
        Object ret1 = null;

        for (int i = 0; i < spanCount; i++) {
            if (kind != null && !kind.isInstance(spans[i])) {
                continue;
            }

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

    private void sendSpanAdded(Object what, int start, int end) {
        SpanWatcher[] recip = getSpans(start, end, SpanWatcher.class);
        int n = recip.length;

        for (int i = 0; i < n; i++) {
            recip[i].onSpanAdded((Spannable) this, what, start, end);
        }
    }

    private void sendSpanRemoved(Object what, int start, int end) {
        SpanWatcher[] recip = getSpans(start, end, SpanWatcher.class);
        int n = recip.length;

        for (int i = 0; i < n; i++) {
            recip[i].onSpanRemoved((Spannable) this, what, start, end);
        }
    }

    private void sendSpanChanged(Object what, int s, int e, int st, int en) {
        SpanWatcher[] recip = getSpans(Math.min(s, st), Math.max(e, en),
                                       SpanWatcher.class);
        int n = recip.length;

        for (int i = 0; i < n; i++) {
            recip[i].onSpanChanged((Spannable) this, what, s, e, st, en);
        }
    }

    private static String region(int start, int end) {
        return "(" + start + " ... " + end + ")";
    }

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

    private String mText;
    private Object[] mSpans;
    private int[] mSpanData;
    private int mSpanCount;

    /* package */ static final Object[] EMPTY = new Object[0];

    private static final int START = 0;
    private static final int END = 1;
    private static final int FLAGS = 2;
    private static final int COLUMNS = 3;
}
