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
import android.util.Log;

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

        if (srclen < 0) throw new StringIndexOutOfBoundsException();

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

                setSpan(false, spans[i], st, en, fl);
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
        final int oldLength = mText.length;
        final int newLength = ArrayUtils.idealCharArraySize(size + 1);
        final int delta = newLength - oldLength;
        if (delta == 0) return;

        char[] newText = new char[newLength];
        System.arraycopy(mText, 0, newText, 0, mGapStart);
        final int after = oldLength - (mGapStart + mGapLength);
        System.arraycopy(mText, oldLength - after, newText, newLength - after, after);
        mText = newText;

        mGapLength += delta;
        if (mGapLength < 1)
            new Exception("mGapLength < 1").printStackTrace();

        for (int i = 0; i < mSpanCount; i++) {
            if (mSpanStarts[i] > mGapStart) mSpanStarts[i] += delta;
            if (mSpanEnds[i] > mGapStart) mSpanEnds[i] += delta;
        }
    }

    private void moveGapTo(int where) {
        if (where == mGapStart)
            return;

        boolean atEnd = (where == length());

        if (where < mGapStart) {
            int overlap = mGapStart - where;
            System.arraycopy(mText, where, mText, mGapStart + mGapLength - overlap, overlap);
        } else /* where > mGapStart */ {
            int overlap = where - mGapStart;
            System.arraycopy(mText, where + mGapLength - overlap, mText, mGapStart, overlap);
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

                if (flag == POINT || (atEnd && flag == PARAGRAPH))
                    start += mGapLength;
            }

            if (end > mGapStart)
                end -= mGapLength;
            if (end > where)
                end += mGapLength;
            else if (end == where) {
                int flag = (mSpanFlags[i] & END_MASK);

                if (flag == POINT || (atEnd && flag == PARAGRAPH))
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

    private void change(int start, int end, CharSequence cs, int csStart, int csEnd) {
        // Can be negative
        final int replacedLength = end - start;
        final int replacementLength = csEnd - csStart;
        final int nbNewChars = replacementLength - replacedLength;

        for (int i = mSpanCount - 1; i >= 0; i--) {
            int spanStart = mSpanStarts[i];
            if (spanStart > mGapStart)
                spanStart -= mGapLength;

            int spanEnd = mSpanEnds[i];
            if (spanEnd > mGapStart)
                spanEnd -= mGapLength;

            if ((mSpanFlags[i] & SPAN_PARAGRAPH) == SPAN_PARAGRAPH) {
                int ost = spanStart;
                int oen = spanEnd;
                int clen = length();

                if (spanStart > start && spanStart <= end) {
                    for (spanStart = end; spanStart < clen; spanStart++)
                        if (spanStart > end && charAt(spanStart - 1) == '\n')
                            break;
                }

                if (spanEnd > start && spanEnd <= end) {
                    for (spanEnd = end; spanEnd < clen; spanEnd++)
                        if (spanEnd > end && charAt(spanEnd - 1) == '\n')
                            break;
                }

                if (spanStart != ost || spanEnd != oen)
                    setSpan(false, mSpans[i], spanStart, spanEnd, mSpanFlags[i]);
            }

            int flags = 0;
            if (spanStart == start) flags |= SPAN_START_AT_START;
            else if (spanStart == end + nbNewChars) flags |= SPAN_START_AT_END;
            if (spanEnd == start) flags |= SPAN_END_AT_START;
            else if (spanEnd == end + nbNewChars) flags |= SPAN_END_AT_END;
            mSpanFlags[i] |= flags;
        }

        moveGapTo(end);

        if (nbNewChars >= mGapLength) {
            resizeFor(mText.length + nbNewChars - mGapLength);
        }

        final boolean textIsRemoved = replacementLength == 0;
        // The removal pass needs to be done before the gap is updated in order to broadcast the
        // correct previous positions to the correct intersecting SpanWatchers
        if (replacedLength > 0) { // no need for span fixup on pure insertion
            // A for loop will not work because the array is being modified
            // Do not iterate in reverse to keep the SpanWatchers notified in ordering
            // Also, a removed SpanWatcher should not get notified of removed spans located
            // further in the span array.
            int i = 0;
            while (i < mSpanCount) {
                if ((mSpanFlags[i] & Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) ==
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE &&
                        mSpanStarts[i] >= start && mSpanStarts[i] < mGapStart + mGapLength &&
                        mSpanEnds[i] >= start && mSpanEnds[i] < mGapStart + mGapLength &&
                        // This condition indicates that the span would become empty
                        (textIsRemoved || mSpanStarts[i] > start || mSpanEnds[i] < mGapStart)) {
                    removeSpan(i);
                    continue; // do not increment i, spans will be shifted left in the array
                }

                i++;
            }
        }

        mGapStart += nbNewChars;
        mGapLength -= nbNewChars;

        if (mGapLength < 1)
            new Exception("mGapLength < 1").printStackTrace();

        TextUtils.getChars(cs, csStart, csEnd, mText, start);

        if (replacedLength > 0) { // no need for span fixup on pure insertion
            final boolean atEnd = (mGapStart + mGapLength == mText.length);

            for (int i = 0; i < mSpanCount; i++) {
                final int startFlag = (mSpanFlags[i] & START_MASK) >> START_SHIFT;
                mSpanStarts[i] = updatedIntervalBound(mSpanStarts[i], start, nbNewChars, startFlag,
                        atEnd, textIsRemoved);

                final int endFlag = (mSpanFlags[i] & END_MASK);
                mSpanEnds[i] = updatedIntervalBound(mSpanEnds[i], start, nbNewChars, endFlag,
                        atEnd, textIsRemoved);
            }
        }

        mSpanCountBeforeAdd = mSpanCount;

        if (cs instanceof Spanned) {
            Spanned sp = (Spanned) cs;
            Object[] spans = sp.getSpans(csStart, csEnd, Object.class);

            for (int i = 0; i < spans.length; i++) {
                int st = sp.getSpanStart(spans[i]);
                int en = sp.getSpanEnd(spans[i]);

                if (st < csStart) st = csStart;
                if (en > csEnd) en = csEnd;

                // Add span only if this object is not yet used as a span in this string
                if (getSpanStart(spans[i]) < 0) {
                    setSpan(false, spans[i], st - csStart + start, en - csStart + start,
                            sp.getSpanFlags(spans[i]));
                }
            }
        }
    }

    private int updatedIntervalBound(int offset, int start, int nbNewChars, int flag, boolean atEnd,
            boolean textIsRemoved) {
        if (offset >= start && offset < mGapStart + mGapLength) {
            if (flag == POINT) {
                // A POINT located inside the replaced range should be moved to the end of the
                // replaced text.
                // The exception is when the point is at the start of the range and we are doing a
                // text replacement (as opposed to a deletion): the point stays there.
                if (textIsRemoved || offset > start) {
                    return mGapStart + mGapLength;
                }
            } else {
                if (flag == PARAGRAPH) {
                    if (atEnd) {
                        return mGapStart + mGapLength;
                    }
                } else { // MARK
                    // MARKs should be moved to the start, with the exception of a mark located at
                    // the end of the range (which will be < mGapStart + mGapLength since mGapLength
                    // is > 0, which should stay 'unchanged' at the end of the replaced text.
                    if (textIsRemoved || offset < mGapStart - nbNewChars) {
                        return start;
                    } else {
                        // Move to the end of replaced text (needed if nbNewChars != 0)
                        return mGapStart;
                    }
                }
            }
        }
        return offset;
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
        checkRange("replace", start, end);

        int filtercount = mFilters.length;
        for (int i = 0; i < filtercount; i++) {
            CharSequence repl = mFilters[i].filter(tb, tbstart, tbend, this, start, end);

            if (repl != null) {
                tb = repl;
                tbstart = 0;
                tbend = repl.length();
            }
        }

        final int origLen = end - start;
        final int newLen = tbend - tbstart;

        if (origLen == 0 && newLen == 0 && !hasNonExclusiveExclusiveSpanAt(tb, tbstart)) {
            // This is a no-op iif there are no spans in tb that would be added (with a 0-length)
            // Early exit so that the text watchers do not get notified
            return this;
        }

        TextWatcher[] textWatchers = getSpans(start, start + origLen, TextWatcher.class);
        sendBeforeTextChanged(textWatchers, start, origLen, newLen);

        // Try to keep the cursor / selection at the same relative position during
        // a text replacement. If replaced or replacement text length is zero, this
        // is already taken care of.
        boolean adjustSelection = origLen != 0 && newLen != 0;
        int selectionStart = 0;
        int selectionEnd = 0;
        if (adjustSelection) {
            selectionStart = Selection.getSelectionStart(this);
            selectionEnd = Selection.getSelectionEnd(this);
        }

        change(start, end, tb, tbstart, tbend);

        if (adjustSelection) {
            if (selectionStart > start && selectionStart < end) {
                final int offset = (selectionStart - start) * newLen / origLen;
                selectionStart = start + offset;

                setSpan(false, Selection.SELECTION_START, selectionStart, selectionStart,
                        Spanned.SPAN_POINT_POINT);
            }
            if (selectionEnd > start && selectionEnd < end) {
                final int offset = (selectionEnd - start) * newLen / origLen;
                selectionEnd = start + offset;

                setSpan(false, Selection.SELECTION_END, selectionEnd, selectionEnd,
                        Spanned.SPAN_POINT_POINT);
            }
        }

        sendTextChanged(textWatchers, start, origLen, newLen);
        sendAfterTextChanged(textWatchers);

        // Span watchers need to be called after text watchers, which may update the layout
        sendToSpanWatchers(start, end, newLen - origLen);

        return this; 
    }

    private static boolean hasNonExclusiveExclusiveSpanAt(CharSequence text, int offset) {
        if (text instanceof Spanned) {
            Spanned spanned = (Spanned) text;
            Object[] spans = spanned.getSpans(offset, offset, Object.class);
            final int length = spans.length;
            for (int i = 0; i < length; i++) {
                Object span = spans[i];
                int flags = spanned.getSpanFlags(span);
                if (flags != Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) return true;
            }
        }
        return false;
    }

    private void sendToSpanWatchers(int replaceStart, int replaceEnd, int nbNewChars) {
        for (int i = 0; i < mSpanCountBeforeAdd; i++) {
            int spanStart = mSpanStarts[i];
            int spanEnd = mSpanEnds[i];
            if (spanStart > mGapStart) spanStart -= mGapLength;
            if (spanEnd > mGapStart) spanEnd -= mGapLength;
            int spanFlags = mSpanFlags[i];

            int newReplaceEnd = replaceEnd + nbNewChars;
            boolean spanChanged = false;

            int previousSpanStart = spanStart;
            if (spanStart > newReplaceEnd) {
                if (nbNewChars != 0) {
                    previousSpanStart -= nbNewChars;
                    spanChanged = true;
                }
            } else if (spanStart >= replaceStart) {
                // No change if span start was already at replace interval boundaries before replace
                if ((spanStart != replaceStart ||
                        ((spanFlags & SPAN_START_AT_START) != SPAN_START_AT_START)) &&
                        (spanStart != newReplaceEnd ||
                        ((spanFlags & SPAN_START_AT_END) != SPAN_START_AT_END))) {
                    // TODO A correct previousSpanStart cannot be computed at this point.
                    // It would require to save all the previous spans' positions before the replace
                    // Using an invalid -1 value to convey this would break the broacast range
                    spanChanged = true;
                }
            }

            int previousSpanEnd = spanEnd;
            if (spanEnd > newReplaceEnd) {
                if (nbNewChars != 0) {
                    previousSpanEnd -= nbNewChars;
                    spanChanged = true;
                }
            } else if (spanEnd >= replaceStart) {
                // No change if span start was already at replace interval boundaries before replace
                if ((spanEnd != replaceStart ||
                        ((spanFlags & SPAN_END_AT_START) != SPAN_END_AT_START)) &&
                        (spanEnd != newReplaceEnd ||
                        ((spanFlags & SPAN_END_AT_END) != SPAN_END_AT_END))) {
                    // TODO same as above for previousSpanEnd
                    spanChanged = true;
                }
            }

            if (spanChanged) {
                sendSpanChanged(mSpans[i], previousSpanStart, previousSpanEnd, spanStart, spanEnd);
            }
            mSpanFlags[i] &= ~SPAN_START_END_MASK;
        }

        // The spans starting at mIntermediateSpanCount were added from the replacement text
        for (int i = mSpanCountBeforeAdd; i < mSpanCount; i++) {
            int spanStart = mSpanStarts[i];
            int spanEnd = mSpanEnds[i];
            if (spanStart > mGapStart) spanStart -= mGapLength;
            if (spanEnd > mGapStart) spanEnd -= mGapLength;
            sendSpanAdded(mSpans[i], spanStart, spanEnd);
        }
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
        checkRange("setSpan", start, end);

        int flagsStart = (flags & START_MASK) >> START_SHIFT;
        if (flagsStart == PARAGRAPH) {
            if (start != 0 && start != length()) {
                char c = charAt(start - 1);

                if (c != '\n')
                    throw new RuntimeException("PARAGRAPH span must start at paragraph boundary");
            }
        }

        int flagsEnd = flags & END_MASK;
        if (flagsEnd == PARAGRAPH) {
            if (end != 0 && end != length()) {
                char c = charAt(end - 1);

                if (c != '\n')
                    throw new RuntimeException("PARAGRAPH span must end at paragraph boundary");
            }
        }

        // 0-length Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        if (flagsStart == POINT && flagsEnd == MARK && start == end) {
            if (send) Log.e("SpannableStringBuilder",
                    "SPAN_EXCLUSIVE_EXCLUSIVE spans cannot have a zero length");
            // Silently ignore invalid spans when they are created from this class.
            // This avoids the duplication of the above test code before all the
            // calls to setSpan that are done in this class
            return;
        }

        int nstart = start;
        int nend = end;

        if (start > mGapStart) {
            start += mGapLength;
        } else if (start == mGapStart) {
            if (flagsStart == POINT || (flagsStart == PARAGRAPH && start == length()))
                start += mGapLength;
        }

        if (end > mGapStart) {
            end += mGapLength;
        } else if (end == mGapStart) {
            if (flagsEnd == POINT || (flagsEnd == PARAGRAPH && end == length()))
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

                if (send) sendSpanChanged(what, ostart, oend, nstart, nend);

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

        if (send) sendSpanAdded(what, nstart, nend);
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
            int spanStart = starts[i];
            if (spanStart > gapstart) {
                spanStart -= gaplen;
            }
            if (spanStart > queryEnd) {
                continue;
            }

            int spanEnd = ends[i];
            if (spanEnd > gapstart) {
                spanEnd -= gaplen;
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

            // Expensive test, should be performed after the previous tests
            if (!kind.isInstance(spans[i])) continue;

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
            System.arraycopy(mText, start + mGapLength, dest, destoff, end - start);
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

    /**
     * Return a String containing a copy of the chars in this buffer, limited to the
     * [start, end[ range.
     * @hide
     */
    public String substring(int start, int end) {
        char[] buf = new char[end - start];
        getChars(start, end, buf, 0);
        return new String(buf);
    }

    private void sendBeforeTextChanged(TextWatcher[] watchers, int start, int before, int after) {
        int n = watchers.length;

        for (int i = 0; i < n; i++) {
            watchers[i].beforeTextChanged(this, start, before, after);
        }
    }

    private void sendTextChanged(TextWatcher[] watchers, int start, int before, int after) {
        int n = watchers.length;

        for (int i = 0; i < n; i++) {
            watchers[i].onTextChanged(this, start, before, after);
        }
    }

    private void sendAfterTextChanged(TextWatcher[] watchers) {
        int n = watchers.length;

        for (int i = 0; i < n; i++) {
            watchers[i].afterTextChanged(this);
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

    private void sendSpanChanged(Object what, int oldStart, int oldEnd, int start, int end) {
        // The bounds of a possible SpanWatcher are guaranteed to be set before this method is
        // called, so that the order of the span does not affect this broadcast.
        SpanWatcher[] spanWatchers = getSpans(Math.min(oldStart, start),
                Math.min(Math.max(oldEnd, end), length()), SpanWatcher.class);
        int n = spanWatchers.length;
        for (int i = 0; i < n; i++) {
            spanWatchers[i].onSpanChanged(this, what, oldStart, oldEnd, start, end);
        }
    }

    private static String region(int start, int end) {
        return "(" + start + " ... " + end + ")";
    }

    private void checkRange(final String operation, int start, int end) {
        if (end < start) {
            throw new IndexOutOfBoundsException(operation + " " +
                    region(start, end) + " has end before start");
        }

        int len = length();

        if (start > len || end > len) {
            throw new IndexOutOfBoundsException(operation + " " +
                    region(start, end) + " ends beyond length " + len);
        }

        if (start < 0 || end < 0) {
            throw new IndexOutOfBoundsException(operation + " " +
                    region(start, end) + " starts before 0");
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
    public void drawText(Canvas c, int start, int end, float x, float y, Paint p) {
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
    public void drawTextRun(Canvas c, int start, int end, int contextStart, int contextEnd,
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
            ret = p.getTextWidths(mText, start + mGapLength, end - start, widths);
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

    // Same as SpannableStringInternal
    @Override
    public boolean equals(Object o) {
        if (o instanceof Spanned &&
                toString().equals(o.toString())) {
            Spanned other = (Spanned) o;
            // Check span data
            Object[] otherSpans = other.getSpans(0, other.length(), Object.class);
            if (mSpanCount == otherSpans.length) {
                for (int i = 0; i < mSpanCount; ++i) {
                    Object thisSpan = mSpans[i];
                    Object otherSpan = otherSpans[i];
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

    // Same as SpannableStringInternal
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
    private int mSpanCountBeforeAdd;

    // TODO These value are tightly related to the public SPAN_MARK/POINT values in {@link Spanned}
    private static final int MARK = 1;
    private static final int POINT = 2;
    private static final int PARAGRAPH = 3;

    private static final int START_MASK = 0xF0;
    private static final int END_MASK = 0x0F;
    private static final int START_SHIFT = 4;

    // These bits are not (currently) used by SPANNED flags
    private static final int SPAN_START_AT_START = 0x1000;
    private static final int SPAN_START_AT_END = 0x2000;
    private static final int SPAN_END_AT_START = 0x4000;
    private static final int SPAN_END_AT_END = 0x8000;
    private static final int SPAN_START_END_MASK = 0xF000;
}
