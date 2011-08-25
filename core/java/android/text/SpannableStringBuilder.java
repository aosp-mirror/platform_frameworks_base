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

import android.graphics.Canvas;
import android.graphics.Paint;

import com.android.internal.util.ArrayUtils;

import java.lang.reflect.Array;

/**
 * This is the class for text whose content and markup can both be changed.
 */
public class SpannableStringBuilder implements CharSequence, GetChars, Spannable, Editable,
        Appendable, GraphicsOperations {
    /**
     * Create a new SpannableStringBuilder with empty contents
     */
    public SpannableStringBuilder() {
        this("");
    }

    /**
     * Create a new SpannableStringBuilder containing a copy of the
     * specified text, including its spans if any.
     */
    public SpannableStringBuilder(CharSequence text) {
        this(text, 0, text.length());
    }

    /**
     * Create a new SpannableStringBuilder containing a copy of the
     * specified slice of the specified text, including its spans if any.
     */
    public SpannableStringBuilder(CharSequence text, int start, int end) {
        int srclen = end - start;

        int len = ArrayUtils.idealCharArraySize(srclen + 1);
        mText = new char[len];
        mGapStart = srclen;
        mGapLength = len - srclen;

        TextUtils.getChars(text, start, end, mText, 0);

        mSpanCount = 0;
        int alloc = ArrayUtils.idealIntArraySize(0);
        mSpans = new Object[alloc];
        mSpanStarts = new int[alloc];
        mSpanEnds = new int[alloc];
        mSpanFlags = new int[alloc];

        if (text instanceof Spanned) {
            Spanned sp = (Spanned) text;
            Object[] spans = sp.getSpans(start, end, Object.class);

            for (int i = 0; i < spans.length; i++) {
                if (spans[i] instanceof NoCopySpan) {
                    continue;
                }
                
                int st = sp.getSpanStart(spans[i]) - start;
                int en = sp.getSpanEnd(spans[i]) - start;
                int fl = sp.getSpanFlags(spans[i]);

                if (st < 0)
                    st = 0;
                if (st > end - start)
                    st = end - start;

                if (en < 0)
                    en = 0;
                if (en > end - start)
                    en = end - start;

                setSpan(spans[i], st, en, fl);
            }
        }
    }

    public static SpannableStringBuilder valueOf(CharSequence source) {
        if (source instanceof SpannableStringBuilder) {
            return (SpannableStringBuilder) source;
        } else {
            return new SpannableStringBuilder(source);
        }
    }

    /**
     * Return the char at the specified offset within the buffer.
     */
    public char charAt(int where) {
        int len = length();
        if (where < 0) {
            throw new IndexOutOfBoundsException("charAt: " + where + " < 0");
        } else if (where >= len) {
            throw new IndexOutOfBoundsException("charAt: " + where + " >= length " + len);
        }

        if (where >= mGapStart)
            return mText[where + mGapLength];
        else
            return mText[where];
    }

    /**
     * Return the number of chars in the buffer.
     */
    public int length() {
        return mText.length - mGapLength;
    }

    private void resizeFor(int size) {
        int newlen = ArrayUtils.idealCharArraySize(size + 1);
        char[] newtext = new char[newlen];

        int after = mText.length - (mGapStart + mGapLength);

        System.arraycopy(mText, 0, newtext, 0, mGapStart);
        System.arraycopy(mText, mText.length - after,
                         newtext, newlen - after, after);

        for (int i = 0; i < mSpanCount; i++) {
            if (mSpanStarts[i] > mGapStart)
                mSpanStarts[i] += newlen - mText.length;
            if (mSpanEnds[i] > mGapStart)
                mSpanEnds[i] += newlen - mText.length;
        }

        int oldlen = mText.length;
        mText = newtext;
        mGapLength += mText.length - oldlen;

        if (mGapLength < 1)
            new Exception("mGapLength < 1").printStackTrace();
    }

    private void moveGapTo(int where) {
        if (where == mGapStart)
            return;

        boolean atend = (where == length());

        if (where < mGapStart) {
            int overlap = mGapStart - where;

            System.arraycopy(mText, where,
                             mText, mGapStart + mGapLength - overlap, overlap);
        } else /* where > mGapStart */ {
            int overlap = where - mGapStart;

            System.arraycopy(mText, where + mGapLength - overlap,
                             mText, mGapStart, overlap);
        }

        // XXX be more clever
        for (int i = 0; i < mSpanCount; i++) {
            int start = mSpanStarts[i];
            int end = mSpanEnds[i];

            if (start > mGapStart)
                start -= mGapLength;
            if (start > where)
                start += mGapLength;
            else if (start == where) {
                int flag = (mSpanFlags[i] & START_MASK) >> START_SHIFT;

                if (flag == POINT || (atend && flag == PARAGRAPH))
                    start += mGapLength;
            }

            if (end > mGapStart)
                end -= mGapLength;
            if (end > where)
                end += mGapLength;
            else if (end == where) {
                int flag = (mSpanFlags[i] & END_MASK);

                if (flag == POINT || (atend && flag == PARAGRAPH))
                    end += mGapLength;
            }

            mSpanStarts[i] = start;
            mSpanEnds[i] = end;
        }

        mGapStart = where;
    }

    // Documentation from interface
    public SpannableStringBuilder insert(int where, CharSequence tb, int start, int end) {
        return replace(where, where, tb, start, end);
    }

    // Documentation from interface
    public SpannableStringBuilder insert(int where, CharSequence tb) {
        return replace(where, where, tb, 0, tb.length());
    }

    // Documentation from interface
    public SpannableStringBuilder delete(int start, int end) {
        SpannableStringBuilder ret = replace(start, end, "", 0, 0);

        if (mGapLength > 2 * length())
            resizeFor(length());
        
        return ret; // == this
    }

    // Documentation from interface
    public void clear() {
        replace(0, length(), "", 0, 0);
    }
    
    // Documentation from interface
    public void clearSpans() {
        for (int i = mSpanCount - 1; i >= 0; i--) {
            Object what = mSpans[i];
            int ostart = mSpanStarts[i];
            int oend = mSpanEnds[i];

            if (ostart > mGapStart)
                ostart -= mGapLength;
            if (oend > mGapStart)
                oend -= mGapLength;

            mSpanCount = i;
            mSpans[i] = null;

            sendSpanRemoved(what, ostart, oend);
        }
    }

    // Documentation from interface
    public SpannableStringBuilder append(CharSequence text) {
        int length = length();
        return replace(length, length, text, 0, text.length());
    }

    // Documentation from interface
    public SpannableStringBuilder append(CharSequence text, int start, int end) {
        int length = length();
        return replace(length, length, text, start, end);
    }

    // Documentation from interface
    public SpannableStringBuilder append(char text) {
        return append(String.valueOf(text));
    }

    private int change(int start, int end, CharSequence tb, int tbstart, int tbend) {
        return change(true, start, end, tb, tbstart, tbend);
    }

    private int change(boolean notify, int start, int end,
                       CharSequence tb, int tbstart, int tbend) {
        checkRange("replace", start, end);
        int ret = tbend - tbstart;
        TextWatcher[] recipients = null;

        if (notify) {
            recipients = sendTextWillChange(start, end - start, tbend - tbstart);
        }

        for (int i = mSpanCount - 1; i >= 0; i--) {
            if ((mSpanFlags[i] & SPAN_PARAGRAPH) == SPAN_PARAGRAPH) {
                int st = mSpanStarts[i];
                if (st > mGapStart)
                    st -= mGapLength;

                int en = mSpanEnds[i];
                if (en > mGapStart)
                    en -= mGapLength;

                int ost = st;
                int oen = en;
                int clen = length();

                if (st > start && st <= end) {
                    for (st = end; st < clen; st++)
                        if (st > end && charAt(st - 1) == '\n')
                            break;
                }

                if (en > start && en <= end) {
                    for (en = end; en < clen; en++)
                        if (en > end && charAt(en - 1) == '\n')
                            break;
                }

                if (st != ost || en != oen)
                    setSpan(mSpans[i], st, en, mSpanFlags[i]);
            }
        }

        moveGapTo(end);

        // Can be negative
        final int nbNewChars = (tbend - tbstart) - (end - start);

        if (nbNewChars >= mGapLength) {
            resizeFor(mText.length + nbNewChars - mGapLength);
        }

        mGapStart += nbNewChars;
        mGapLength -= nbNewChars;

        if (mGapLength < 1)
            new Exception("mGapLength < 1").printStackTrace();

        TextUtils.getChars(tb, tbstart, tbend, mText, start);

        if (tb instanceof Spanned) {
            Spanned sp = (Spanned) tb;
            Object[] spans = sp.getSpans(tbstart, tbend, Object.class);

            for (int i = 0; i < spans.length; i++) {
                int st = sp.getSpanStart(spans[i]);
                int en = sp.getSpanEnd(spans[i]);

                if (st < tbstart)
                    st = tbstart;
                if (en > tbend)
                    en = tbend;

                if (getSpanStart(spans[i]) < 0) {
                    setSpan(false, spans[i],
                            st - tbstart + start,
                            en - tbstart + start,
                            sp.getSpanFlags(spans[i]));
                }
            }
        }

        // no need for span fixup on pure insertion
        if (tbend > tbstart && end - start == 0) {
            if (notify) {
                sendTextChange(recipients, start, end - start, tbend - tbstart);
                sendTextHasChanged(recipients);
            }

            return ret;
        }

        boolean atend = (mGapStart + mGapLength == mText.length);

        for (int i = mSpanCount - 1; i >= 0; i--) {
            if (mSpanStarts[i] >= start &&
                mSpanStarts[i] < mGapStart + mGapLength) {
                int flag = (mSpanFlags[i] & START_MASK) >> START_SHIFT;

                if (flag == POINT || (flag == PARAGRAPH && atend))
                    mSpanStarts[i] = mGapStart + mGapLength;
                else
                    mSpanStarts[i] = start;
            }

            if (mSpanEnds[i] >= start &&
                mSpanEnds[i] < mGapStart + mGapLength) {
                int flag = (mSpanFlags[i] & END_MASK);

                if (flag == POINT || (flag == PARAGRAPH && atend))
                    mSpanEnds[i] = mGapStart + mGapLength;
                else
                    mSpanEnds[i] = start;
            }

            // remove 0-length SPAN_EXCLUSIVE_EXCLUSIVE
            if (mSpanEnds[i] < mSpanStarts[i]) {
                removeSpan(i);
            }
        }

        if (notify) {
            sendTextChange(recipients, start, end - start, tbend - tbstart);
            sendTextHasChanged(recipients);
        }

        return ret;
    }

    private void removeSpan(int i) {
        Object object = mSpans[i];

        int start = mSpanStarts[i];
        int end = mSpanEnds[i];

        if (start > mGapStart) start -= mGapLength;
        if (end > mGapStart) end -= mGapLength;

        int count = mSpanCount - (i + 1);
        System.arraycopy(mSpans, i + 1, mSpans, i, count);
        System.arraycopy(mSpanStarts, i + 1, mSpanStarts, i, count);
        System.arraycopy(mSpanEnds, i + 1, mSpanEnds, i, count);
        System.arraycopy(mSpanFlags, i + 1, mSpanFlags, i, count);

        mSpanCount--;

        mSpans[mSpanCount] = null;

        sendSpanRemoved(object, start, end);
    }

    // Documentation from interface
    public SpannableStringBuilder replace(int start, int end, CharSequence tb) {
        return replace(start, end, tb, 0, tb.length());
    }

    // Documentation from interface
    public SpannableStringBuilder replace(final int start, final int end,
                        CharSequence tb, int tbstart, int tbend) {
        int filtercount = mFilters.length;
        for (int i = 0; i < filtercount; i++) {
            CharSequence repl = mFilters[i].filter(tb, tbstart, tbend,
                                                   this, start, end);

            if (repl != null) {
                tb = repl;
                tbstart = 0;
                tbend = repl.length();
            }
        }

        if (end == start && tbstart == tbend) {
            return this;
        }

        if (end == start || tbstart == tbend) {
            change(start, end, tb, tbstart, tbend);
        } else {
            int selstart = Selection.getSelectionStart(this);
            int selend = Selection.getSelectionEnd(this);

            // XXX just make the span fixups in change() do the right thing
            // instead of this madness!

            checkRange("replace", start, end);
            moveGapTo(end);
            TextWatcher[] recipients;

            int origlen = end - start;

            recipients = sendTextWillChange(start, origlen, tbend - tbstart);

            if (mGapLength < 2)
                resizeFor(length() + 1);

            for (int i = mSpanCount - 1; i >= 0; i--) {
                if (mSpanStarts[i] == mGapStart)
                    mSpanStarts[i]++;

                if (mSpanEnds[i] == mGapStart)
                    mSpanEnds[i]++;
            }

            mText[mGapStart] = ' ';
            mGapStart++;
            mGapLength--;

            if (mGapLength < 1) {
                new Exception("mGapLength < 1").printStackTrace();
            }

            int inserted = change(false, start + 1, start + 1, tb, tbstart, tbend);
            change(false, start, start + 1, "", 0, 0);
            change(false, start + inserted, start + inserted + origlen, "", 0, 0);

            /*
             * Special case to keep the cursor in the same position
             * if it was somewhere in the middle of the replaced region.
             * If it was at the start or the end or crossing the whole
             * replacement, it should already be where it belongs.
             * TODO: Is there some more general mechanism that could
             * accomplish this?
             */
            if (selstart > start && selstart < end) {
                long off = selstart - start;

                off = off * inserted / (end - start);
                selstart = (int) off + start;

                setSpan(false, Selection.SELECTION_START, selstart, selstart,
                        Spanned.SPAN_POINT_POINT);
            }
            if (selend > start && selend < end) {
                long off = selend - start;

                off = off * inserted / (end - start);
                selend = (int) off + start;

                setSpan(false, Selection.SELECTION_END, selend, selend, Spanned.SPAN_POINT_POINT);
            }
            sendTextChange(recipients, start, origlen, inserted);
            sendTextHasChanged(recipients);
        }

        return this; 
    }

    /**
     * Mark the specified range of text with the specified object.
     * The flags determine how the span will behave when text is
     * inserted at the start or end of the span's range.
     */
    public void setSpan(Object what, int start, int end, int flags) {
        setSpan(true, what, start, end, flags);
    }

    private void setSpan(boolean send, Object what, int start, int end, int flags) {
        int nstart = start;
        int nend = end;

        checkRange("setSpan", start, end);

        if ((flags & START_MASK) == (PARAGRAPH << START_SHIFT)) {
            if (start != 0 && start != length()) {
                char c = charAt(start - 1);

                if (c != '\n')
                    throw new RuntimeException("PARAGRAPH span must start at paragraph boundary");
            }
        }

        if ((flags & END_MASK) == PARAGRAPH) {
            if (end != 0 && end != length()) {
                char c = charAt(end - 1);

                if (c != '\n')
                    throw new RuntimeException("PARAGRAPH span must end at paragraph boundary");
            }
        }

        if (start > mGapStart) {
            start += mGapLength;
        } else if (start == mGapStart) {
            int flag = (flags & START_MASK) >> START_SHIFT;

            if (flag == POINT || (flag == PARAGRAPH && start == length()))
                start += mGapLength;
        }

        if (end > mGapStart) {
            end += mGapLength;
        } else if (end == mGapStart) {
            int flag = (flags & END_MASK);

            if (flag == POINT || (flag == PARAGRAPH && end == length()))
                end += mGapLength;
        }

        int count = mSpanCount;
        Object[] spans = mSpans;

        for (int i = 0; i < count; i++) {
            if (spans[i] == what) {
                int ostart = mSpanStarts[i];
                int oend = mSpanEnds[i];

                if (ostart > mGapStart)
                    ostart -= mGapLength;
                if (oend > mGapStart)
                    oend -= mGapLength;

                mSpanStarts[i] = start;
                mSpanEnds[i] = end;
                mSpanFlags[i] = flags;

                if (send) 
                    sendSpanChanged(what, ostart, oend, nstart, nend);

                return;
            }
        }

        if (mSpanCount + 1 >= mSpans.length) {
            int newsize = ArrayUtils.idealIntArraySize(mSpanCount + 1);
            Object[] newspans = new Object[newsize];
            int[] newspanstarts = new int[newsize];
            int[] newspanends = new int[newsize];
            int[] newspanflags = new int[newsize];

            System.arraycopy(mSpans, 0, newspans, 0, mSpanCount);
            System.arraycopy(mSpanStarts, 0, newspanstarts, 0, mSpanCount);
            System.arraycopy(mSpanEnds, 0, newspanends, 0, mSpanCount);
            System.arraycopy(mSpanFlags, 0, newspanflags, 0, mSpanCount);

            mSpans = newspans;
            mSpanStarts = newspanstarts;
            mSpanEnds = newspanends;
            mSpanFlags = newspanflags;
        }

        mSpans[mSpanCount] = what;
        mSpanStarts[mSpanCount] = start;
        mSpanEnds[mSpanCount] = end;
        mSpanFlags[mSpanCount] = flags;
        mSpanCount++;

        if (send)
            sendSpanAdded(what, nstart, nend);
    }

    /**
     * Remove the specified markup object from the buffer.
     */
    public void removeSpan(Object what) {
        for (int i = mSpanCount - 1; i >= 0; i--) {
            if (mSpans[i] == what) {
                removeSpan(i);
                return;
            }
        }
    }

    /**
     * Return the buffer offset of the beginning of the specified
     * markup object, or -1 if it is not attached to this buffer.
     */
    public int getSpanStart(Object what) {
        int count = mSpanCount;
        Object[] spans = mSpans;

        for (int i = count - 1; i >= 0; i--) {
            if (spans[i] == what) {
                int where = mSpanStarts[i];

                if (where > mGapStart)
                    where -= mGapLength;

                return where;
            }
        }

        return -1;
    }

    /**
     * Return the buffer offset of the end of the specified
     * markup object, or -1 if it is not attached to this buffer.
     */
    public int getSpanEnd(Object what) {
        int count = mSpanCount;
        Object[] spans = mSpans;

        for (int i = count - 1; i >= 0; i--) {
            if (spans[i] == what) {
                int where = mSpanEnds[i];

                if (where > mGapStart)
                    where -= mGapLength;

                return where;
            }
        }

        return -1;
    }

    /**
     * Return the flags of the end of the specified
     * markup object, or 0 if it is not attached to this buffer.
     */
    public int getSpanFlags(Object what) {
        int count = mSpanCount;
        Object[] spans = mSpans;

        for (int i = count - 1; i >= 0; i--) {
            if (spans[i] == what) {
                return mSpanFlags[i];
            }
        }

        return 0; 
    }

    /**
     * Return an array of the spans of the specified type that overlap
     * the specified range of the buffer.  The kind may be Object.class to get
     * a list of all the spans regardless of type.
     */
    @SuppressWarnings("unchecked")
    public <T> T[] getSpans(int queryStart, int queryEnd, Class<T> kind) {
        if (kind == null) return ArrayUtils.emptyArray(kind);

        int spanCount = mSpanCount;
        Object[] spans = mSpans;
        int[] starts = mSpanStarts;
        int[] ends = mSpanEnds;
        int[] flags = mSpanFlags;
        int gapstart = mGapStart;
        int gaplen = mGapLength;

        int count = 0;
        T[] ret = null;
        T ret1 = null;

        for (int i = 0; i < spanCount; i++) {
            if (!kind.isInstance(spans[i])) continue;

            int spanStart = starts[i];
            int spanEnd = ends[i];

            if (spanStart > gapstart) {
                spanStart -= gaplen;
            }
            if (spanEnd > gapstart) {
                spanEnd -= gaplen;
            }

            if (spanStart > queryEnd) {
                continue;
            }
            if (spanEnd < queryStart) {
                continue;
            }

            if (spanStart != spanEnd && queryStart != queryEnd) {
                if (spanStart == queryEnd)
                    continue;
                if (spanEnd == queryStart)
                    continue;
            }

            if (count == 0) {
                // Safe conversion thanks to the isInstance test above
                ret1 = (T) spans[i];
                count++;
            } else {
                if (count == 1) {
                    // Safe conversion, but requires a suppressWarning
                    ret = (T[]) Array.newInstance(kind, spanCount - i + 1);
                    ret[0] = ret1;
                }

                int prio = flags[i] & SPAN_PRIORITY;
                if (prio != 0) {
                    int j;

                    for (j = 0; j < count; j++) {
                        int p = getSpanFlags(ret[j]) & SPAN_PRIORITY;

                        if (prio > p) {
                            break;
                        }
                    }

                    System.arraycopy(ret, j, ret, j + 1, count - j);
                    // Safe conversion thanks to the isInstance test above
                    ret[j] = (T) spans[i];
                    count++;
                } else {
                    // Safe conversion thanks to the isInstance test above
                    ret[count++] = (T) spans[i];
                }
            }
        }

        if (count == 0) {
            return ArrayUtils.emptyArray(kind);
        }
        if (count == 1) {
            // Safe conversion, but requires a suppressWarning
            ret = (T[]) Array.newInstance(kind, 1);
            ret[0] = ret1;
            return ret;
        }
        if (count == ret.length) {
            return ret;
        }

        // Safe conversion, but requires a suppressWarning
        T[] nret = (T[]) Array.newInstance(kind, count);
        System.arraycopy(ret, 0, nret, 0, count);
        return nret;
    }

    /**
     * Return the next offset after <code>start</code> but less than or
     * equal to <code>limit</code> where a span of the specified type
     * begins or ends.
     */
    public int nextSpanTransition(int start, int limit, Class kind) {
        int count = mSpanCount;
        Object[] spans = mSpans;
        int[] starts = mSpanStarts;
        int[] ends = mSpanEnds;
        int gapstart = mGapStart;
        int gaplen = mGapLength;

        if (kind == null) {
            kind = Object.class;
        }

        for (int i = 0; i < count; i++) {
            int st = starts[i];
            int en = ends[i];

            if (st > gapstart)
                st -= gaplen;
            if (en > gapstart)
                en -= gaplen;

            if (st > start && st < limit && kind.isInstance(spans[i]))
                limit = st;
            if (en > start && en < limit && kind.isInstance(spans[i]))
                limit = en;
        }

        return limit;
    }

    /**
     * Return a new CharSequence containing a copy of the specified
     * range of this buffer, including the overlapping spans.
     */
    public CharSequence subSequence(int start, int end) {
        return new SpannableStringBuilder(this, start, end);
    }

    /**
     * Copy the specified range of chars from this buffer into the
     * specified array, beginning at the specified offset.
     */
    public void getChars(int start, int end, char[] dest, int destoff) {
        checkRange("getChars", start, end);

        if (end <= mGapStart) {
            System.arraycopy(mText, start, dest, destoff, end - start);
        } else if (start >= mGapStart) {
            System.arraycopy(mText, start + mGapLength,
                             dest, destoff, end - start);
        } else {
            System.arraycopy(mText, start, dest, destoff, mGapStart - start);
            System.arraycopy(mText, mGapStart + mGapLength,
                             dest, destoff + (mGapStart - start),
                             end - mGapStart);
        }
    }

    /**
     * Return a String containing a copy of the chars in this buffer.
     */
    @Override
    public String toString() {
        int len = length();
        char[] buf = new char[len];

        getChars(0, len, buf, 0);
        return new String(buf);
    }

    private TextWatcher[] sendTextWillChange(int start, int before, int after) {
        TextWatcher[] recip = getSpans(start, start + before, TextWatcher.class);
        int n = recip.length;

        for (int i = 0; i < n; i++) {
            recip[i].beforeTextChanged(this, start, before, after);
        }

        return recip;
    }

    private void sendTextChange(TextWatcher[] recip, int start, int before, int after) {
        int n = recip.length;

        for (int i = 0; i < n; i++) {
            recip[i].onTextChanged(this, start, before, after);
        }
    }

    private void sendTextHasChanged(TextWatcher[] recip) {
        int n = recip.length;

        for (int i = 0; i < n; i++) {
            recip[i].afterTextChanged(this);
        }
    }

    private void sendSpanAdded(Object what, int start, int end) {
        SpanWatcher[] recip = getSpans(start, end, SpanWatcher.class);
        int n = recip.length;

        for (int i = 0; i < n; i++) {
            recip[i].onSpanAdded(this, what, start, end);
        }
    }

    private void sendSpanRemoved(Object what, int start, int end) {
        SpanWatcher[] recip = getSpans(start, end, SpanWatcher.class);
        int n = recip.length;

        for (int i = 0; i < n; i++) {
            recip[i].onSpanRemoved(this, what, start, end);
        }
    }

    private void sendSpanChanged(Object what, int s, int e, int st, int en) {
        SpanWatcher[] recip = getSpans(Math.min(s, st), Math.max(e, en), SpanWatcher.class);
        int n = recip.length;

        for (int i = 0; i < n; i++) {
            recip[i].onSpanChanged(this, what, s, e, st, en);
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

/*
    private boolean isprint(char c) { // XXX
        if (c >= ' ' && c <= '~')
            return true;
        else
            return false;
    }

    private static final int startFlag(int flag) {
        return (flag >> 4) & 0x0F;
    }

    private static final int endFlag(int flag) {
        return flag & 0x0F;
    }

    public void dump() { // XXX
        for (int i = 0; i < mGapStart; i++) {
            System.out.print('|');
            System.out.print(' ');
            System.out.print(isprint(mText[i]) ? mText[i] : '.');
            System.out.print(' ');
        }

        for (int i = mGapStart; i < mGapStart + mGapLength; i++) {
            System.out.print('|');
            System.out.print('(');
            System.out.print(isprint(mText[i]) ? mText[i] : '.');
            System.out.print(')');
        }

        for (int i = mGapStart + mGapLength; i < mText.length; i++) {
            System.out.print('|');
            System.out.print(' ');
            System.out.print(isprint(mText[i]) ? mText[i] : '.');
            System.out.print(' ');
        }

        System.out.print('\n');

        for (int i = 0; i < mText.length + 1; i++) {
            int found = 0;
            int wfound = 0;

            for (int j = 0; j < mSpanCount; j++) {
                if (mSpanStarts[j] == i) {
                    found = 1;
                    wfound = j;
                    break;
                }

                if (mSpanEnds[j] == i) {
                    found = 2;
                    wfound = j;
                    break;
                }
            }

            if (found == 1) {
                if (startFlag(mSpanFlags[wfound]) == MARK)
                    System.out.print("(   ");
                if (startFlag(mSpanFlags[wfound]) == PARAGRAPH)
                    System.out.print("<   ");
                else
                    System.out.print("[   ");
            } else if (found == 2) {
                if (endFlag(mSpanFlags[wfound]) == POINT)
                    System.out.print(")   ");
                if (endFlag(mSpanFlags[wfound]) == PARAGRAPH)
                    System.out.print(">   ");
                else
                    System.out.print("]   ");
            } else {
                System.out.print("    ");
            }
        }

        System.out.print("\n");
    }
*/

    /**
     * Don't call this yourself -- exists for Canvas to use internally.
     * {@hide}
     */
    public void drawText(Canvas c, int start, int end,
                         float x, float y, Paint p) {
        checkRange("drawText", start, end);

        if (end <= mGapStart) {
            c.drawText(mText, start, end - start, x, y, p);
        } else if (start >= mGapStart) {
            c.drawText(mText, start + mGapLength, end - start, x, y, p);
        } else {
            char[] buf = TextUtils.obtain(end - start);

            getChars(start, end, buf, 0);
            c.drawText(buf, 0, end - start, x, y, p);
            TextUtils.recycle(buf);
        }
    }


    /**
     * Don't call this yourself -- exists for Canvas to use internally.
     * {@hide}
     */
    public void drawTextRun(Canvas c, int start, int end,
            int contextStart, int contextEnd,
            float x, float y, int flags, Paint p) {
        checkRange("drawTextRun", start, end);

        int contextLen = contextEnd - contextStart;
        int len = end - start;
        if (contextEnd <= mGapStart) {
            c.drawTextRun(mText, start, len, contextStart, contextLen, x, y, flags, p);
        } else if (contextStart >= mGapStart) {
            c.drawTextRun(mText, start + mGapLength, len, contextStart + mGapLength,
                    contextLen, x, y, flags, p);
        } else {
            char[] buf = TextUtils.obtain(contextLen);
            getChars(contextStart, contextEnd, buf, 0);
            c.drawTextRun(buf, start - contextStart, len, 0, contextLen, x, y, flags, p);
            TextUtils.recycle(buf);
        }
    }

   /**
     * Don't call this yourself -- exists for Paint to use internally.
     * {@hide}
     */
    public float measureText(int start, int end, Paint p) {
        checkRange("measureText", start, end);

        float ret;

        if (end <= mGapStart) {
            ret = p.measureText(mText, start, end - start);
        } else if (start >= mGapStart) {
            ret = p.measureText(mText, start + mGapLength, end - start);
        } else {
            char[] buf = TextUtils.obtain(end - start);

            getChars(start, end, buf, 0);
            ret = p.measureText(buf, 0, end - start);
            TextUtils.recycle(buf);
        }

        return ret;
    }

    /**
     * Don't call this yourself -- exists for Paint to use internally.
     * {@hide}
     */
    public int getTextWidths(int start, int end, float[] widths, Paint p) {
        checkRange("getTextWidths", start, end);

        int ret;

        if (end <= mGapStart) {
            ret = p.getTextWidths(mText, start, end - start, widths);
        } else if (start >= mGapStart) {
            ret = p.getTextWidths(mText, start + mGapLength, end - start,
                                  widths);
        } else {
            char[] buf = TextUtils.obtain(end - start);

            getChars(start, end, buf, 0);
            ret = p.getTextWidths(buf, 0, end - start, widths);
            TextUtils.recycle(buf);
        }

        return ret;
    }

    /**
     * Don't call this yourself -- exists for Paint to use internally.
     * {@hide}
     */
    public float getTextRunAdvances(int start, int end, int contextStart, int contextEnd, int flags,
            float[] advances, int advancesPos, Paint p) {

        float ret;

        int contextLen = contextEnd - contextStart;
        int len = end - start;

        if (end <= mGapStart) {
            ret = p.getTextRunAdvances(mText, start, len, contextStart, contextLen,
                    flags, advances, advancesPos);
        } else if (start >= mGapStart) {
            ret = p.getTextRunAdvances(mText, start + mGapLength, len,
                    contextStart + mGapLength, contextLen, flags, advances, advancesPos);
        } else {
            char[] buf = TextUtils.obtain(contextLen);
            getChars(contextStart, contextEnd, buf, 0);
            ret = p.getTextRunAdvances(buf, start - contextStart, len,
                    0, contextLen, flags, advances, advancesPos);
            TextUtils.recycle(buf);
        }

        return ret;
    }

    /**
     * Don't call this yourself -- exists for Paint to use internally.
     * {@hide}
     */
    public float getTextRunAdvances(int start, int end, int contextStart, int contextEnd, int flags,
            float[] advances, int advancesPos, Paint p, int reserved) {

        float ret;

        int contextLen = contextEnd - contextStart;
        int len = end - start;

        if (end <= mGapStart) {
            ret = p.getTextRunAdvances(mText, start, len, contextStart, contextLen,
                    flags, advances, advancesPos, reserved);
        } else if (start >= mGapStart) {
            ret = p.getTextRunAdvances(mText, start + mGapLength, len,
                    contextStart + mGapLength, contextLen, flags, advances, advancesPos, reserved);
        } else {
            char[] buf = TextUtils.obtain(contextLen);
            getChars(contextStart, contextEnd, buf, 0);
            ret = p.getTextRunAdvances(buf, start - contextStart, len,
                    0, contextLen, flags, advances, advancesPos, reserved);
            TextUtils.recycle(buf);
        }

        return ret;
    }

    /**
     * Returns the next cursor position in the run.  This avoids placing the cursor between
     * surrogates, between characters that form conjuncts, between base characters and combining
     * marks, or within a reordering cluster.
     *
     * <p>The context is the shaping context for cursor movement, generally the bounds of the metric
     * span enclosing the cursor in the direction of movement.
     * <code>contextStart</code>, <code>contextEnd</code> and <code>offset</code> are relative to
     * the start of the string.</p>
     *
     * <p>If cursorOpt is CURSOR_AT and the offset is not a valid cursor position,
     * this returns -1.  Otherwise this will never return a value before contextStart or after
     * contextEnd.</p>
     *
     * @param contextStart the start index of the context
     * @param contextEnd the (non-inclusive) end index of the context
     * @param flags either DIRECTION_RTL or DIRECTION_LTR
     * @param offset the cursor position to move from
     * @param cursorOpt how to move the cursor, one of CURSOR_AFTER,
     * CURSOR_AT_OR_AFTER, CURSOR_BEFORE,
     * CURSOR_AT_OR_BEFORE, or CURSOR_AT
     * @param p the Paint object that is requesting this information
     * @return the offset of the next position, or -1
     * @deprecated This is an internal method, refrain from using it in your code
     */
    @Deprecated
    public int getTextRunCursor(int contextStart, int contextEnd, int flags, int offset,
            int cursorOpt, Paint p) {

        int ret;

        int contextLen = contextEnd - contextStart;
        if (contextEnd <= mGapStart) {
            ret = p.getTextRunCursor(mText, contextStart, contextLen,
                    flags, offset, cursorOpt);
        } else if (contextStart >= mGapStart) {
            ret = p.getTextRunCursor(mText, contextStart + mGapLength, contextLen,
                    flags, offset + mGapLength, cursorOpt) - mGapLength;
        } else {
            char[] buf = TextUtils.obtain(contextLen);
            getChars(contextStart, contextEnd, buf, 0);
            ret = p.getTextRunCursor(buf, 0, contextLen,
                    flags, offset - contextStart, cursorOpt) + contextStart;
            TextUtils.recycle(buf);
        }

        return ret;
    }

    // Documentation from interface
    public void setFilters(InputFilter[] filters) {
        if (filters == null) {
            throw new IllegalArgumentException();
        }

        mFilters = filters;
    }

    // Documentation from interface
    public InputFilter[] getFilters() {
        return mFilters;
    }

    private static final InputFilter[] NO_FILTERS = new InputFilter[0];
    private InputFilter[] mFilters = NO_FILTERS;

    private char[] mText;
    private int mGapStart;
    private int mGapLength;

    private Object[] mSpans;
    private int[] mSpanStarts;
    private int[] mSpanEnds;
    private int[] mSpanFlags;
    private int mSpanCount;

    private static final int POINT = 2;
    private static final int PARAGRAPH = 3;

    private static final int START_MASK = 0xF0;
    private static final int END_MASK = 0x0F;
    private static final int START_SHIFT = 4;
}
