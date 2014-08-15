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

import android.graphics.Bitmap;
import android.graphics.Paint;
import android.text.style.LeadingMarginSpan;
import android.text.style.LeadingMarginSpan.LeadingMarginSpan2;
import android.text.style.LineHeightSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.TabStopSpan;
import android.util.Log;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.GrowingArrayUtils;

/**
 * StaticLayout is a Layout for text that will not be edited after it
 * is laid out.  Use {@link DynamicLayout} for text that may change.
 * <p>This is used by widgets to control text layout. You should not need
 * to use this class directly unless you are implementing your own widget
 * or custom display object, or would be tempted to call
 * {@link android.graphics.Canvas#drawText(java.lang.CharSequence, int, int,
 * float, float, android.graphics.Paint)
 * Canvas.drawText()} directly.</p>
 */
public class StaticLayout extends Layout {

    static final String TAG = "StaticLayout";

    public StaticLayout(CharSequence source, TextPaint paint,
                        int width,
                        Alignment align, float spacingmult, float spacingadd,
                        boolean includepad) {
        this(source, 0, source.length(), paint, width, align,
             spacingmult, spacingadd, includepad);
    }

    /**
     * @hide
     */
    public StaticLayout(CharSequence source, TextPaint paint,
            int width, Alignment align, TextDirectionHeuristic textDir,
            float spacingmult, float spacingadd,
            boolean includepad) {
        this(source, 0, source.length(), paint, width, align, textDir,
                spacingmult, spacingadd, includepad);
    }

    public StaticLayout(CharSequence source, int bufstart, int bufend,
                        TextPaint paint, int outerwidth,
                        Alignment align,
                        float spacingmult, float spacingadd,
                        boolean includepad) {
        this(source, bufstart, bufend, paint, outerwidth, align,
             spacingmult, spacingadd, includepad, null, 0);
    }

    /**
     * @hide
     */
    public StaticLayout(CharSequence source, int bufstart, int bufend,
            TextPaint paint, int outerwidth,
            Alignment align, TextDirectionHeuristic textDir,
            float spacingmult, float spacingadd,
            boolean includepad) {
        this(source, bufstart, bufend, paint, outerwidth, align, textDir,
                spacingmult, spacingadd, includepad, null, 0, Integer.MAX_VALUE);
}

    public StaticLayout(CharSequence source, int bufstart, int bufend,
            TextPaint paint, int outerwidth,
            Alignment align,
            float spacingmult, float spacingadd,
            boolean includepad,
            TextUtils.TruncateAt ellipsize, int ellipsizedWidth) {
        this(source, bufstart, bufend, paint, outerwidth, align,
                TextDirectionHeuristics.FIRSTSTRONG_LTR,
                spacingmult, spacingadd, includepad, ellipsize, ellipsizedWidth, Integer.MAX_VALUE);
    }

    /**
     * @hide
     */
    public StaticLayout(CharSequence source, int bufstart, int bufend,
                        TextPaint paint, int outerwidth,
                        Alignment align, TextDirectionHeuristic textDir,
                        float spacingmult, float spacingadd,
                        boolean includepad,
                        TextUtils.TruncateAt ellipsize, int ellipsizedWidth, int maxLines) {
        super((ellipsize == null)
                ? source
                : (source instanceof Spanned)
                    ? new SpannedEllipsizer(source)
                    : new Ellipsizer(source),
              paint, outerwidth, align, textDir, spacingmult, spacingadd);

        /*
         * This is annoying, but we can't refer to the layout until
         * superclass construction is finished, and the superclass
         * constructor wants the reference to the display text.
         *
         * This will break if the superclass constructor ever actually
         * cares about the content instead of just holding the reference.
         */
        if (ellipsize != null) {
            Ellipsizer e = (Ellipsizer) getText();

            e.mLayout = this;
            e.mWidth = ellipsizedWidth;
            e.mMethod = ellipsize;
            mEllipsizedWidth = ellipsizedWidth;

            mColumns = COLUMNS_ELLIPSIZE;
        } else {
            mColumns = COLUMNS_NORMAL;
            mEllipsizedWidth = outerwidth;
        }

        mLineDirections = ArrayUtils.newUnpaddedArray(Directions.class, 2 * mColumns);
        mLines = new int[mLineDirections.length];
        mMaximumVisibleLineCount = maxLines;

        mMeasured = MeasuredText.obtain();

        generate(source, bufstart, bufend, paint, outerwidth, textDir, spacingmult,
                 spacingadd, includepad, includepad, ellipsizedWidth,
                 ellipsize);

        mMeasured = MeasuredText.recycle(mMeasured);
        mFontMetricsInt = null;
    }

    /* package */ StaticLayout(CharSequence text) {
        super(text, null, 0, null, 0, 0);

        mColumns = COLUMNS_ELLIPSIZE;
        mLineDirections = ArrayUtils.newUnpaddedArray(Directions.class, 2 * mColumns);
        mLines = new int[mLineDirections.length];
        // FIXME This is never recycled
        mMeasured = MeasuredText.obtain();
    }

    /* package */ void generate(CharSequence source, int bufStart, int bufEnd,
                        TextPaint paint, int outerWidth,
                        TextDirectionHeuristic textDir, float spacingmult,
                        float spacingadd, boolean includepad,
                        boolean trackpad, float ellipsizedWidth,
                        TextUtils.TruncateAt ellipsize) {
        int[] breakOpp = null;
        final String localeLanguageTag = paint.getTextLocale().toLanguageTag();

        mLineCount = 0;

        int v = 0;
        boolean needMultiply = (spacingmult != 1 || spacingadd != 0);

        Paint.FontMetricsInt fm = mFontMetricsInt;
        int[] chooseHtv = null;

        MeasuredText measured = mMeasured;

        Spanned spanned = null;
        if (source instanceof Spanned)
            spanned = (Spanned) source;

        int paraEnd;
        for (int paraStart = bufStart; paraStart <= bufEnd; paraStart = paraEnd) {
            paraEnd = TextUtils.indexOf(source, CHAR_NEW_LINE, paraStart, bufEnd);
            if (paraEnd < 0)
                paraEnd = bufEnd;
            else
                paraEnd++;

            int firstWidthLineLimit = mLineCount + 1;
            int firstWidth = outerWidth;
            int restWidth = outerWidth;

            LineHeightSpan[] chooseHt = null;

            if (spanned != null) {
                LeadingMarginSpan[] sp = getParagraphSpans(spanned, paraStart, paraEnd,
                        LeadingMarginSpan.class);
                for (int i = 0; i < sp.length; i++) {
                    LeadingMarginSpan lms = sp[i];
                    firstWidth -= sp[i].getLeadingMargin(true);
                    restWidth -= sp[i].getLeadingMargin(false);

                    // LeadingMarginSpan2 is odd.  The count affects all
                    // leading margin spans, not just this particular one
                    if (lms instanceof LeadingMarginSpan2) {
                        LeadingMarginSpan2 lms2 = (LeadingMarginSpan2) lms;
                        int lmsFirstLine = getLineForOffset(spanned.getSpanStart(lms2));
                        firstWidthLineLimit = Math.max(firstWidthLineLimit,
                                lmsFirstLine + lms2.getLeadingMarginLineCount());
                    }
                }

                chooseHt = getParagraphSpans(spanned, paraStart, paraEnd, LineHeightSpan.class);

                if (chooseHt.length != 0) {
                    if (chooseHtv == null ||
                        chooseHtv.length < chooseHt.length) {
                        chooseHtv = ArrayUtils.newUnpaddedIntArray(chooseHt.length);
                    }

                    for (int i = 0; i < chooseHt.length; i++) {
                        int o = spanned.getSpanStart(chooseHt[i]);

                        if (o < paraStart) {
                            // starts in this layout, before the
                            // current paragraph

                            chooseHtv[i] = getLineTop(getLineForOffset(o));
                        } else {
                            // starts in this paragraph

                            chooseHtv[i] = v;
                        }
                    }
                }
            }

            measured.setPara(source, paraStart, paraEnd, textDir);
            char[] chs = measured.mChars;
            float[] widths = measured.mWidths;
            byte[] chdirs = measured.mLevels;
            int dir = measured.mDir;
            boolean easy = measured.mEasy;

            breakOpp = nLineBreakOpportunities(localeLanguageTag, chs, paraEnd - paraStart, breakOpp);
            int breakOppIndex = 0;

            int width = firstWidth;

            float w = 0;
            // here is the offset of the starting character of the line we are currently measuring
            int here = paraStart;

            // ok is a character offset located after a word separator (space, tab, number...) where
            // we would prefer to cut the current line. Equals to here when no such break was found.
            int ok = paraStart;
            float okWidth = w;
            int okAscent = 0, okDescent = 0, okTop = 0, okBottom = 0;

            // fit is a character offset such that the [here, fit[ range fits in the allowed width.
            // We will cut the line there if no ok position is found.
            int fit = paraStart;
            float fitWidth = w;
            int fitAscent = 0, fitDescent = 0, fitTop = 0, fitBottom = 0;

            boolean hasTabOrEmoji = false;
            boolean hasTab = false;
            TabStops tabStops = null;

            for (int spanStart = paraStart, spanEnd; spanStart < paraEnd; spanStart = spanEnd) {

                if (spanned == null) {
                    spanEnd = paraEnd;
                    int spanLen = spanEnd - spanStart;
                    measured.addStyleRun(paint, spanLen, fm);
                } else {
                    spanEnd = spanned.nextSpanTransition(spanStart, paraEnd,
                            MetricAffectingSpan.class);
                    int spanLen = spanEnd - spanStart;
                    MetricAffectingSpan[] spans =
                            spanned.getSpans(spanStart, spanEnd, MetricAffectingSpan.class);
                    spans = TextUtils.removeEmptySpans(spans, spanned, MetricAffectingSpan.class);
                    measured.addStyleRun(paint, spans, spanLen, fm);
                }

                int fmTop = fm.top;
                int fmBottom = fm.bottom;
                int fmAscent = fm.ascent;
                int fmDescent = fm.descent;

                for (int j = spanStart; j < spanEnd; j++) {
                    char c = chs[j - paraStart];

                    if (c == CHAR_NEW_LINE) {
                        // intentionally left empty
                    } else if (c == CHAR_TAB) {
                        if (hasTab == false) {
                            hasTab = true;
                            hasTabOrEmoji = true;
                            if (spanned != null) {
                                // First tab this para, check for tabstops
                                TabStopSpan[] spans = getParagraphSpans(spanned, paraStart,
                                        paraEnd, TabStopSpan.class);
                                if (spans.length > 0) {
                                    tabStops = new TabStops(TAB_INCREMENT, spans);
                                }
                            }
                        }
                        if (tabStops != null) {
                            w = tabStops.nextTab(w);
                        } else {
                            w = TabStops.nextDefaultStop(w, TAB_INCREMENT);
                        }
                    } else if (c >= CHAR_FIRST_HIGH_SURROGATE && c <= CHAR_LAST_LOW_SURROGATE
                            && j + 1 < spanEnd) {
                        int emoji = Character.codePointAt(chs, j - paraStart);

                        if (emoji >= MIN_EMOJI && emoji <= MAX_EMOJI) {
                            Bitmap bm = EMOJI_FACTORY.getBitmapFromAndroidPua(emoji);

                            if (bm != null) {
                                Paint whichPaint;

                                if (spanned == null) {
                                    whichPaint = paint;
                                } else {
                                    whichPaint = mWorkPaint;
                                }

                                float wid = bm.getWidth() * -whichPaint.ascent() / bm.getHeight();

                                w += wid;
                                hasTabOrEmoji = true;
                                j++;
                            } else {
                                w += widths[j - paraStart];
                            }
                        } else {
                            w += widths[j - paraStart];
                        }
                    } else {
                        w += widths[j - paraStart];
                    }

                    boolean isSpaceOrTab = c == CHAR_SPACE || c == CHAR_TAB || c == CHAR_ZWSP;

                    if (w <= width || isSpaceOrTab) {
                        fitWidth = w;
                        fit = j + 1;

                        if (fmTop < fitTop)
                            fitTop = fmTop;
                        if (fmAscent < fitAscent)
                            fitAscent = fmAscent;
                        if (fmDescent > fitDescent)
                            fitDescent = fmDescent;
                        if (fmBottom > fitBottom)
                            fitBottom = fmBottom;

                        while (breakOpp[breakOppIndex] != -1
                                && breakOpp[breakOppIndex] < j - paraStart + 1) {
                            breakOppIndex++;
                        }
                        boolean isLineBreak = breakOppIndex < breakOpp.length &&
                                breakOpp[breakOppIndex] == j - paraStart + 1;

                        if (isLineBreak) {
                            okWidth = w;
                            ok = j + 1;

                            if (fitTop < okTop)
                                okTop = fitTop;
                            if (fitAscent < okAscent)
                                okAscent = fitAscent;
                            if (fitDescent > okDescent)
                                okDescent = fitDescent;
                            if (fitBottom > okBottom)
                                okBottom = fitBottom;
                        }
                    } else {
                        final boolean moreChars;
                        int endPos;
                        int above, below, top, bottom;
                        float currentTextWidth;

                        if (ok != here) {
                            endPos = ok;
                            above = okAscent;
                            below = okDescent;
                            top = okTop;
                            bottom = okBottom;
                            currentTextWidth = okWidth;
                            moreChars = (j + 1 < spanEnd);
                        } else if (fit != here) {
                            endPos = fit;
                            above = fitAscent;
                            below = fitDescent;
                            top = fitTop;
                            bottom = fitBottom;
                            currentTextWidth = fitWidth;
                            moreChars = (j + 1 < spanEnd);
                        } else {
                            // must make progress, so take next character
                            endPos = here + 1;
                            // but to deal properly with clusters
                            // take all zero width characters following that
                            while (endPos < spanEnd && widths[endPos - paraStart] == 0) {
                                endPos++;
                            }
                            above = fmAscent;
                            below = fmDescent;
                            top = fmTop;
                            bottom = fmBottom;
                            currentTextWidth = widths[here - paraStart];
                            moreChars = (endPos < spanEnd);
                        }

                        v = out(source, here, endPos,
                                above, below, top, bottom,
                                v, spacingmult, spacingadd, chooseHt,chooseHtv, fm, hasTabOrEmoji,
                                needMultiply, chdirs, dir, easy, bufEnd, includepad, trackpad,
                                chs, widths, paraStart, ellipsize, ellipsizedWidth,
                                currentTextWidth, paint, moreChars);

                        here = endPos;
                        j = here - 1; // restart j-span loop from here, compensating for the j++
                        ok = fit = here;
                        w = 0;
                        fitAscent = fitDescent = fitTop = fitBottom = 0;
                        okAscent = okDescent = okTop = okBottom = 0;

                        if (--firstWidthLineLimit <= 0) {
                            width = restWidth;
                        }

                        if (here < spanStart) {
                            // The text was cut before the beginning of the current span range.
                            // Exit the span loop, and get spanStart to start over from here.
                            measured.setPos(here);
                            spanEnd = here;
                            break;
                        }

                        if (mLineCount >= mMaximumVisibleLineCount) {
                            return;
                        }
                    }
                }
            }

            if (paraEnd != here && mLineCount < mMaximumVisibleLineCount) {
                if ((fitTop | fitBottom | fitDescent | fitAscent) == 0) {
                    paint.getFontMetricsInt(fm);

                    fitTop = fm.top;
                    fitBottom = fm.bottom;
                    fitAscent = fm.ascent;
                    fitDescent = fm.descent;
                }

                // Log.e("text", "output rest " + here + " to " + end);

                v = out(source,
                        here, paraEnd, fitAscent, fitDescent,
                        fitTop, fitBottom,
                        v,
                        spacingmult, spacingadd, chooseHt,
                        chooseHtv, fm, hasTabOrEmoji,
                        needMultiply, chdirs, dir, easy, bufEnd,
                        includepad, trackpad, chs,
                        widths, paraStart, ellipsize,
                        ellipsizedWidth, w, paint, paraEnd != bufEnd);
            }

            paraStart = paraEnd;

            if (paraEnd == bufEnd)
                break;
        }

        if ((bufEnd == bufStart || source.charAt(bufEnd - 1) == CHAR_NEW_LINE) &&
                mLineCount < mMaximumVisibleLineCount) {
            // Log.e("text", "output last " + bufEnd);

            measured.setPara(source, bufStart, bufEnd, textDir);

            paint.getFontMetricsInt(fm);

            v = out(source,
                    bufEnd, bufEnd, fm.ascent, fm.descent,
                    fm.top, fm.bottom,
                    v,
                    spacingmult, spacingadd, null,
                    null, fm, false,
                    needMultiply, measured.mLevels, measured.mDir, measured.mEasy, bufEnd,
                    includepad, trackpad, null,
                    null, bufStart, ellipsize,
                    ellipsizedWidth, 0, paint, false);
        }
    }

    private int out(CharSequence text, int start, int end,
                      int above, int below, int top, int bottom, int v,
                      float spacingmult, float spacingadd,
                      LineHeightSpan[] chooseHt, int[] chooseHtv,
                      Paint.FontMetricsInt fm, boolean hasTabOrEmoji,
                      boolean needMultiply, byte[] chdirs, int dir,
                      boolean easy, int bufEnd, boolean includePad,
                      boolean trackPad, char[] chs,
                      float[] widths, int widthStart, TextUtils.TruncateAt ellipsize,
                      float ellipsisWidth, float textWidth,
                      TextPaint paint, boolean moreChars) {
        int j = mLineCount;
        int off = j * mColumns;
        int want = off + mColumns + TOP;
        int[] lines = mLines;

        if (want >= lines.length) {
            Directions[] grow2 = ArrayUtils.newUnpaddedArray(
                    Directions.class, GrowingArrayUtils.growSize(want));
            System.arraycopy(mLineDirections, 0, grow2, 0,
                             mLineDirections.length);
            mLineDirections = grow2;

            int[] grow = new int[grow2.length];
            System.arraycopy(lines, 0, grow, 0, lines.length);
            mLines = grow;
            lines = grow;
        }

        if (chooseHt != null) {
            fm.ascent = above;
            fm.descent = below;
            fm.top = top;
            fm.bottom = bottom;

            for (int i = 0; i < chooseHt.length; i++) {
                if (chooseHt[i] instanceof LineHeightSpan.WithDensity) {
                    ((LineHeightSpan.WithDensity) chooseHt[i]).
                        chooseHeight(text, start, end, chooseHtv[i], v, fm, paint);

                } else {
                    chooseHt[i].chooseHeight(text, start, end, chooseHtv[i], v, fm);
                }
            }

            above = fm.ascent;
            below = fm.descent;
            top = fm.top;
            bottom = fm.bottom;
        }

        boolean firstLine = (j == 0);
        boolean currentLineIsTheLastVisibleOne = (j + 1 == mMaximumVisibleLineCount);
        boolean lastLine = currentLineIsTheLastVisibleOne || (end == bufEnd);

        if (firstLine) {
            if (trackPad) {
                mTopPadding = top - above;
            }

            if (includePad) {
                above = top;
            }
        }

        int extra;

        if (lastLine) {
            if (trackPad) {
                mBottomPadding = bottom - below;
            }

            if (includePad) {
                below = bottom;
            }
        }


        if (needMultiply && !lastLine) {
            double ex = (below - above) * (spacingmult - 1) + spacingadd;
            if (ex >= 0) {
                extra = (int)(ex + EXTRA_ROUNDING);
            } else {
                extra = -(int)(-ex + EXTRA_ROUNDING);
            }
        } else {
            extra = 0;
        }

        lines[off + START] = start;
        lines[off + TOP] = v;
        lines[off + DESCENT] = below + extra;

        v += (below - above) + extra;
        lines[off + mColumns + START] = end;
        lines[off + mColumns + TOP] = v;

        if (hasTabOrEmoji)
            lines[off + TAB] |= TAB_MASK;

        lines[off + DIR] |= dir << DIR_SHIFT;
        Directions linedirs = DIRS_ALL_LEFT_TO_RIGHT;
        // easy means all chars < the first RTL, so no emoji, no nothing
        // XXX a run with no text or all spaces is easy but might be an empty
        // RTL paragraph.  Make sure easy is false if this is the case.
        if (easy) {
            mLineDirections[j] = linedirs;
        } else {
            mLineDirections[j] = AndroidBidi.directions(dir, chdirs, start - widthStart, chs,
                    start - widthStart, end - start);
        }

        if (ellipsize != null) {
            // If there is only one line, then do any type of ellipsis except when it is MARQUEE
            // if there are multiple lines, just allow END ellipsis on the last line
            boolean forceEllipsis = moreChars && (mLineCount + 1 == mMaximumVisibleLineCount);

            boolean doEllipsis =
                        (((mMaximumVisibleLineCount == 1 && moreChars) || (firstLine && !moreChars)) &&
                                ellipsize != TextUtils.TruncateAt.MARQUEE) ||
                        (!firstLine && (currentLineIsTheLastVisibleOne || !moreChars) &&
                                ellipsize == TextUtils.TruncateAt.END);
            if (doEllipsis) {
                calculateEllipsis(start, end, widths, widthStart,
                        ellipsisWidth, ellipsize, j,
                        textWidth, paint, forceEllipsis);
            }
        }

        mLineCount++;
        return v;
    }

    private void calculateEllipsis(int lineStart, int lineEnd,
                                   float[] widths, int widthStart,
                                   float avail, TextUtils.TruncateAt where,
                                   int line, float textWidth, TextPaint paint,
                                   boolean forceEllipsis) {
        if (textWidth <= avail && !forceEllipsis) {
            // Everything fits!
            mLines[mColumns * line + ELLIPSIS_START] = 0;
            mLines[mColumns * line + ELLIPSIS_COUNT] = 0;
            return;
        }

        float ellipsisWidth = paint.measureText(
                (where == TextUtils.TruncateAt.END_SMALL) ?
                        ELLIPSIS_TWO_DOTS : ELLIPSIS_NORMAL, 0, 1);
        int ellipsisStart = 0;
        int ellipsisCount = 0;
        int len = lineEnd - lineStart;

        // We only support start ellipsis on a single line
        if (where == TextUtils.TruncateAt.START) {
            if (mMaximumVisibleLineCount == 1) {
                float sum = 0;
                int i;

                for (i = len; i >= 0; i--) {
                    float w = widths[i - 1 + lineStart - widthStart];

                    if (w + sum + ellipsisWidth > avail) {
                        break;
                    }

                    sum += w;
                }

                ellipsisStart = 0;
                ellipsisCount = i;
            } else {
                if (Log.isLoggable(TAG, Log.WARN)) {
                    Log.w(TAG, "Start Ellipsis only supported with one line");
                }
            }
        } else if (where == TextUtils.TruncateAt.END || where == TextUtils.TruncateAt.MARQUEE ||
                where == TextUtils.TruncateAt.END_SMALL) {
            float sum = 0;
            int i;

            for (i = 0; i < len; i++) {
                float w = widths[i + lineStart - widthStart];

                if (w + sum + ellipsisWidth > avail) {
                    break;
                }

                sum += w;
            }

            ellipsisStart = i;
            ellipsisCount = len - i;
            if (forceEllipsis && ellipsisCount == 0 && len > 0) {
                ellipsisStart = len - 1;
                ellipsisCount = 1;
            }
        } else {
            // where = TextUtils.TruncateAt.MIDDLE We only support middle ellipsis on a single line
            if (mMaximumVisibleLineCount == 1) {
                float lsum = 0, rsum = 0;
                int left = 0, right = len;

                float ravail = (avail - ellipsisWidth) / 2;
                for (right = len; right >= 0; right--) {
                    float w = widths[right - 1 + lineStart - widthStart];

                    if (w + rsum > ravail) {
                        break;
                    }

                    rsum += w;
                }

                float lavail = avail - ellipsisWidth - rsum;
                for (left = 0; left < right; left++) {
                    float w = widths[left + lineStart - widthStart];

                    if (w + lsum > lavail) {
                        break;
                    }

                    lsum += w;
                }

                ellipsisStart = left;
                ellipsisCount = right - left;
            } else {
                if (Log.isLoggable(TAG, Log.WARN)) {
                    Log.w(TAG, "Middle Ellipsis only supported with one line");
                }
            }
        }

        mLines[mColumns * line + ELLIPSIS_START] = ellipsisStart;
        mLines[mColumns * line + ELLIPSIS_COUNT] = ellipsisCount;
    }

    // Override the base class so we can directly access our members,
    // rather than relying on member functions.
    // The logic mirrors that of Layout.getLineForVertical
    // FIXME: It may be faster to do a linear search for layouts without many lines.
    @Override
    public int getLineForVertical(int vertical) {
        int high = mLineCount;
        int low = -1;
        int guess;
        int[] lines = mLines;
        while (high - low > 1) {
            guess = (high + low) >> 1;
            if (lines[mColumns * guess + TOP] > vertical){
                high = guess;
            } else {
                low = guess;
            }
        }
        if (low < 0) {
            return 0;
        } else {
            return low;
        }
    }

    @Override
    public int getLineCount() {
        return mLineCount;
    }

    @Override
    public int getLineTop(int line) {
        int top = mLines[mColumns * line + TOP];
        if (mMaximumVisibleLineCount > 0 && line >= mMaximumVisibleLineCount &&
                line != mLineCount) {
            top += getBottomPadding();
        }
        return top;
    }

    @Override
    public int getLineDescent(int line) {
        int descent = mLines[mColumns * line + DESCENT];
        if (mMaximumVisibleLineCount > 0 && line >= mMaximumVisibleLineCount - 1 && // -1 intended
                line != mLineCount) {
            descent += getBottomPadding();
        }
        return descent;
    }

    @Override
    public int getLineStart(int line) {
        return mLines[mColumns * line + START] & START_MASK;
    }

    @Override
    public int getParagraphDirection(int line) {
        return mLines[mColumns * line + DIR] >> DIR_SHIFT;
    }

    @Override
    public boolean getLineContainsTab(int line) {
        return (mLines[mColumns * line + TAB] & TAB_MASK) != 0;
    }

    @Override
    public final Directions getLineDirections(int line) {
        return mLineDirections[line];
    }

    @Override
    public int getTopPadding() {
        return mTopPadding;
    }

    @Override
    public int getBottomPadding() {
        return mBottomPadding;
    }

    @Override
    public int getEllipsisCount(int line) {
        if (mColumns < COLUMNS_ELLIPSIZE) {
            return 0;
        }

        return mLines[mColumns * line + ELLIPSIS_COUNT];
    }

    @Override
    public int getEllipsisStart(int line) {
        if (mColumns < COLUMNS_ELLIPSIZE) {
            return 0;
        }

        return mLines[mColumns * line + ELLIPSIS_START];
    }

    @Override
    public int getEllipsizedWidth() {
        return mEllipsizedWidth;
    }

    void prepare() {
        mMeasured = MeasuredText.obtain();
    }
    
    void finish() {
        mMeasured = MeasuredText.recycle(mMeasured);
    }

    // returns an array with terminal sentinel value -1 to indicate end
    // this is so that arrays can be recycled instead of allocating new arrays
    // every time
    private static native int[] nLineBreakOpportunities(String locale, char[] text, int length, int[] recycle);

    private int mLineCount;
    private int mTopPadding, mBottomPadding;
    private int mColumns;
    private int mEllipsizedWidth;

    private static final int COLUMNS_NORMAL = 3;
    private static final int COLUMNS_ELLIPSIZE = 5;
    private static final int START = 0;
    private static final int DIR = START;
    private static final int TAB = START;
    private static final int TOP = 1;
    private static final int DESCENT = 2;
    private static final int ELLIPSIS_START = 3;
    private static final int ELLIPSIS_COUNT = 4;

    private int[] mLines;
    private Directions[] mLineDirections;
    private int mMaximumVisibleLineCount = Integer.MAX_VALUE;

    private static final int START_MASK = 0x1FFFFFFF;
    private static final int DIR_SHIFT  = 30;
    private static final int TAB_MASK   = 0x20000000;

    private static final int TAB_INCREMENT = 20; // same as Layout, but that's private

    private static final char CHAR_NEW_LINE = '\n';
    private static final char CHAR_TAB = '\t';
    private static final char CHAR_SPACE = ' ';
    private static final char CHAR_ZWSP = '\u200B';

    private static final double EXTRA_ROUNDING = 0.5;

    private static final int CHAR_FIRST_HIGH_SURROGATE = 0xD800;
    private static final int CHAR_LAST_LOW_SURROGATE = 0xDFFF;

    /*
     * This is reused across calls to generate()
     */
    private MeasuredText mMeasured;
    private Paint.FontMetricsInt mFontMetricsInt = new Paint.FontMetricsInt();
}
