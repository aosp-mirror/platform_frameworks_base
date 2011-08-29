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

        mLines = new int[ArrayUtils.idealIntArraySize(2 * mColumns)];
        mLineDirections = new Directions[
                             ArrayUtils.idealIntArraySize(2 * mColumns)];
        mMaximumVisibleLineCount = maxLines;

        mMeasured = MeasuredText.obtain();

        generate(source, bufstart, bufend, paint, outerwidth, align, textDir,
                 spacingmult, spacingadd, includepad, includepad,
                 ellipsizedWidth, ellipsize);

        mMeasured = MeasuredText.recycle(mMeasured);
        mFontMetricsInt = null;
    }

    /* package */ StaticLayout(CharSequence text) {
        super(text, null, 0, null, 0, 0);

        mColumns = COLUMNS_ELLIPSIZE;
        mLines = new int[ArrayUtils.idealIntArraySize(2 * mColumns)];
        mLineDirections = new Directions[
                             ArrayUtils.idealIntArraySize(2 * mColumns)];
        mMeasured = MeasuredText.obtain();
    }

    /* package */ void generate(CharSequence source, int bufStart, int bufEnd,
                        TextPaint paint, int outerWidth,
                        Alignment align, TextDirectionHeuristic textDir,
                        float spacingmult, float spacingadd,
                        boolean includepad, boolean trackpad,
                        float ellipsizedWidth, TextUtils.TruncateAt ellipsize) {
        mLineCount = 0;

        int v = 0;
        boolean needMultiply = (spacingmult != 1 || spacingadd != 0);

        Paint.FontMetricsInt fm = mFontMetricsInt;
        int[] chooseHtv = null;

        MeasuredText measured = mMeasured;

        Spanned spanned = null;
        if (source instanceof Spanned)
            spanned = (Spanned) source;

        int DEFAULT_DIR = DIR_LEFT_TO_RIGHT; // XXX

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
                    // leading margin spans, not just this particular one,
                    // and start from the top of the span, not the top of the
                    // paragraph.
                    if (lms instanceof LeadingMarginSpan2) {
                        LeadingMarginSpan2 lms2 = (LeadingMarginSpan2) lms;
                        int lmsFirstLine = getLineForOffset(spanned.getSpanStart(lms2));
                        firstWidthLineLimit = lmsFirstLine + lms2.getLeadingMarginLineCount();
                    }
                }

                chooseHt = getParagraphSpans(spanned, paraStart, paraEnd, LineHeightSpan.class);

                if (chooseHt.length != 0) {
                    if (chooseHtv == null ||
                        chooseHtv.length < chooseHt.length) {
                        chooseHtv = new int[ArrayUtils.idealIntArraySize(
                                            chooseHt.length)];
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

            int width = firstWidth;

            float w = 0;
            int here = paraStart;

            int ok = paraStart;
            float okWidth = w;
            int okAscent = 0, okDescent = 0, okTop = 0, okBottom = 0;

            int fit = paraStart;
            float fitWidth = w;
            int fitAscent = 0, fitDescent = 0, fitTop = 0, fitBottom = 0;

            boolean hasTabOrEmoji = false;
            boolean hasTab = false;
            TabStops tabStops = null;

            for (int spanStart = paraStart, spanEnd = spanStart, nextSpanStart;
                    spanStart < paraEnd; spanStart = nextSpanStart) {

                if (spanStart == spanEnd) {
                    if (spanned == null)
                        spanEnd = paraEnd;
                    else
                        spanEnd = spanned.nextSpanTransition(spanStart, paraEnd,
                                MetricAffectingSpan.class);

                    int spanLen = spanEnd - spanStart;
                    if (spanned == null) {
                        measured.addStyleRun(paint, spanLen, fm);
                    } else {
                        MetricAffectingSpan[] spans =
                            spanned.getSpans(spanStart, spanEnd, MetricAffectingSpan.class);
                        spans = TextUtils.removeEmptySpans(spans, spanned,
                                MetricAffectingSpan.class);
                        measured.addStyleRun(paint, spans, spanLen, fm);
                    }
                }

                nextSpanStart = spanEnd;

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

                                float wid = bm.getWidth() *
                                            -whichPaint.ascent() /
                                            bm.getHeight();

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

                    // Log.e("text", "was " + before + " now " + w + " after " + c + " within " + width);

                    if (w <= width) {
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

                        /*
                         * From the Unicode Line Breaking Algorithm:
                         * (at least approximately)
                         *
                         * .,:; are class IS: breakpoints
                         *      except when adjacent to digits
                         * /    is class SY: a breakpoint
                         *      except when followed by a digit.
                         * -    is class HY: a breakpoint
                         *      except when followed by a digit.
                         *
                         * Ideographs are class ID: breakpoints when adjacent,
                         * except for NS (non-starters), which can be broken
                         * after but not before.
                         */

                        if (c == CHAR_SPACE || c == CHAR_TAB ||
                            ((c == CHAR_DOT || c == CHAR_COMMA ||
                                    c == CHAR_COLON || c == CHAR_SEMICOLON) &&
                             (j - 1 < here || !Character.isDigit(chs[j - 1 - paraStart])) &&
                             (j + 1 >= spanEnd || !Character.isDigit(chs[j + 1 - paraStart]))) ||
                            ((c == CHAR_SLASH || c == CHAR_HYPHEN) &&
                             (j + 1 >= spanEnd || !Character.isDigit(chs[j + 1 - paraStart]))) ||
                            (c >= CHAR_FIRST_CJK && isIdeographic(c, true) &&
                             j + 1 < spanEnd && isIdeographic(chs[j + 1 - paraStart], false))) {
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
                            final boolean moreChars = (j + 1 < spanEnd);
                            if (ok != here) {
                                // Log.e("text", "output ok " + here + " to " +ok);

                                while (ok < spanEnd && chs[ok - paraStart] == CHAR_SPACE) {
                                    ok++;
                                }

                                v = out(source,
                                        here, ok,
                                        okAscent, okDescent, okTop, okBottom,
                                        v,
                                        spacingmult, spacingadd, chooseHt,
                                        chooseHtv, fm, hasTabOrEmoji,
                                        needMultiply, paraStart, chdirs, dir, easy,
                                        ok == bufEnd, includepad, trackpad,
                                        chs, widths, paraStart,
                                        ellipsize, ellipsizedWidth, okWidth,
                                        paint, moreChars);

                                here = ok;
                            } else if (fit != here) {
                                // Log.e("text", "output fit " + here + " to " +fit);
                                v = out(source,
                                        here, fit,
                                        fitAscent, fitDescent,
                                        fitTop, fitBottom,
                                        v,
                                        spacingmult, spacingadd, chooseHt,
                                        chooseHtv, fm, hasTabOrEmoji,
                                        needMultiply, paraStart, chdirs, dir, easy,
                                        fit == bufEnd, includepad, trackpad,
                                        chs, widths, paraStart,
                                        ellipsize, ellipsizedWidth, fitWidth,
                                        paint, moreChars);

                                here = fit;
                            } else {
                                // Log.e("text", "output one " + here + " to " +(here + 1));
                                // XXX not sure why the existing fm wasn't ok.
                                // measureText(paint, mWorkPaint,
                                //             source, here, here + 1, fm, tab,
                                //             null);

                                v = out(source,
                                        here, here+1,
                                        fm.ascent, fm.descent,
                                        fm.top, fm.bottom,
                                        v,
                                        spacingmult, spacingadd, chooseHt,
                                        chooseHtv, fm, hasTabOrEmoji,
                                        needMultiply, paraStart, chdirs, dir, easy,
                                        here + 1 == bufEnd, includepad,
                                        trackpad,
                                        chs, widths, paraStart,
                                        ellipsize, ellipsizedWidth,
                                        widths[here - paraStart], paint, moreChars);

                                here = here + 1;
                            }

                        if (here < spanStart) {
                            // didn't output all the text for this span
                            // we've measured the raw widths, though, so
                            // just reset the start point
                            j = nextSpanStart = here;
                        } else {
                            j = here - 1;    // continue looping
                        }

                        ok = fit = here;
                        w = 0;
                        fitAscent = fitDescent = fitTop = fitBottom = 0;
                        okAscent = okDescent = okTop = okBottom = 0;

                        if (--firstWidthLineLimit <= 0) {
                            width = restWidth;
                        }
                    }
                    if (mLineCount >= mMaximumVisibleLineCount) {
                        break;
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
                        needMultiply, paraStart, chdirs, dir, easy,
                        paraEnd == bufEnd, includepad, trackpad,
                        chs, widths, paraStart,
                        ellipsize, ellipsizedWidth, w, paint, paraEnd != bufEnd);
            }

            paraStart = paraEnd;

            if (paraEnd == bufEnd)
                break;
        }

        if ((bufEnd == bufStart || source.charAt(bufEnd - 1) == CHAR_NEW_LINE) &&
                mLineCount < mMaximumVisibleLineCount) {
            // Log.e("text", "output last " + bufEnd);

            paint.getFontMetricsInt(fm);

            v = out(source,
                    bufEnd, bufEnd, fm.ascent, fm.descent,
                    fm.top, fm.bottom,
                    v,
                    spacingmult, spacingadd, null,
                    null, fm, false,
                    needMultiply, bufEnd, null, DEFAULT_DIR, true,
                    true, includepad, trackpad,
                    null, null, bufStart,
                    ellipsize, ellipsizedWidth, 0, paint, false);
        }
    }

    /**
     * Returns true if the specified character is one of those specified
     * as being Ideographic (class ID) by the Unicode Line Breaking Algorithm
     * (http://www.unicode.org/unicode/reports/tr14/), and is therefore OK
     * to break between a pair of.
     *
     * @param includeNonStarters also return true for category NS
     *                           (non-starters), which can be broken
     *                           after but not before.
     */
    private static final boolean isIdeographic(char c, boolean includeNonStarters) {
        if (c >= '\u2E80' && c <= '\u2FFF') {
            return true; // CJK, KANGXI RADICALS, DESCRIPTION SYMBOLS
        }
        if (c == '\u3000') {
            return true; // IDEOGRAPHIC SPACE
        }
        if (c >= '\u3040' && c <= '\u309F') {
            if (!includeNonStarters) {
                switch (c) {
                case '\u3041': //  # HIRAGANA LETTER SMALL A
                case '\u3043': //  # HIRAGANA LETTER SMALL I
                case '\u3045': //  # HIRAGANA LETTER SMALL U
                case '\u3047': //  # HIRAGANA LETTER SMALL E
                case '\u3049': //  # HIRAGANA LETTER SMALL O
                case '\u3063': //  # HIRAGANA LETTER SMALL TU
                case '\u3083': //  # HIRAGANA LETTER SMALL YA
                case '\u3085': //  # HIRAGANA LETTER SMALL YU
                case '\u3087': //  # HIRAGANA LETTER SMALL YO
                case '\u308E': //  # HIRAGANA LETTER SMALL WA
                case '\u3095': //  # HIRAGANA LETTER SMALL KA
                case '\u3096': //  # HIRAGANA LETTER SMALL KE
                case '\u309B': //  # KATAKANA-HIRAGANA VOICED SOUND MARK
                case '\u309C': //  # KATAKANA-HIRAGANA SEMI-VOICED SOUND MARK
                case '\u309D': //  # HIRAGANA ITERATION MARK
                case '\u309E': //  # HIRAGANA VOICED ITERATION MARK
                    return false;
                }
            }
            return true; // Hiragana (except small characters)
        }
        if (c >= '\u30A0' && c <= '\u30FF') {
            if (!includeNonStarters) {
                switch (c) {
                case '\u30A0': //  # KATAKANA-HIRAGANA DOUBLE HYPHEN
                case '\u30A1': //  # KATAKANA LETTER SMALL A
                case '\u30A3': //  # KATAKANA LETTER SMALL I
                case '\u30A5': //  # KATAKANA LETTER SMALL U
                case '\u30A7': //  # KATAKANA LETTER SMALL E
                case '\u30A9': //  # KATAKANA LETTER SMALL O
                case '\u30C3': //  # KATAKANA LETTER SMALL TU
                case '\u30E3': //  # KATAKANA LETTER SMALL YA
                case '\u30E5': //  # KATAKANA LETTER SMALL YU
                case '\u30E7': //  # KATAKANA LETTER SMALL YO
                case '\u30EE': //  # KATAKANA LETTER SMALL WA
                case '\u30F5': //  # KATAKANA LETTER SMALL KA
                case '\u30F6': //  # KATAKANA LETTER SMALL KE
                case '\u30FB': //  # KATAKANA MIDDLE DOT
                case '\u30FC': //  # KATAKANA-HIRAGANA PROLONGED SOUND MARK
                case '\u30FD': //  # KATAKANA ITERATION MARK
                case '\u30FE': //  # KATAKANA VOICED ITERATION MARK
                    return false;
                }
            }
            return true; // Katakana (except small characters)
        }
        if (c >= '\u3400' && c <= '\u4DB5') {
            return true; // CJK UNIFIED IDEOGRAPHS EXTENSION A
        }
        if (c >= '\u4E00' && c <= '\u9FBB') {
            return true; // CJK UNIFIED IDEOGRAPHS
        }
        if (c >= '\uF900' && c <= '\uFAD9') {
            return true; // CJK COMPATIBILITY IDEOGRAPHS
        }
        if (c >= '\uA000' && c <= '\uA48F') {
            return true; // YI SYLLABLES
        }
        if (c >= '\uA490' && c <= '\uA4CF') {
            return true; // YI RADICALS
        }
        if (c >= '\uFE62' && c <= '\uFE66') {
            return true; // SMALL PLUS SIGN to SMALL EQUALS SIGN
        }
        if (c >= '\uFF10' && c <= '\uFF19') {
            return true; // WIDE DIGITS
        }

        return false;
    }

    private int out(CharSequence text, int start, int end,
                      int above, int below, int top, int bottom, int v,
                      float spacingmult, float spacingadd,
                      LineHeightSpan[] chooseHt, int[] chooseHtv,
                      Paint.FontMetricsInt fm, boolean hasTabOrEmoji,
                      boolean needMultiply, int pstart, byte[] chdirs,
                      int dir, boolean easy, boolean last,
                      boolean includePad, boolean trackPad,
                      char[] chs, float[] widths, int widthStart,
                      TextUtils.TruncateAt ellipsize, float ellipsisWidth,
                      float textWidth, TextPaint paint, boolean moreChars) {
        int j = mLineCount;
        int off = j * mColumns;
        int want = off + mColumns + TOP;
        int[] lines = mLines;

        if (want >= lines.length) {
            int nlen = ArrayUtils.idealIntArraySize(want + 1);
            int[] grow = new int[nlen];
            System.arraycopy(lines, 0, grow, 0, lines.length);
            mLines = grow;
            lines = grow;

            Directions[] grow2 = new Directions[nlen];
            System.arraycopy(mLineDirections, 0, grow2, 0,
                             mLineDirections.length);
            mLineDirections = grow2;
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

        if (j == 0) {
            if (trackPad) {
                mTopPadding = top - above;
            }

            if (includePad) {
                above = top;
            }
        }
        if (last) {
            if (trackPad) {
                mBottomPadding = bottom - below;
            }

            if (includePad) {
                below = bottom;
            }
        }

        int extra;

        if (needMultiply) {
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
            boolean firstLine = (j == 0);
            boolean currentLineIsTheLastVisibleOne = (j + 1 == mMaximumVisibleLineCount);
            boolean forceEllipsis = moreChars && (mLineCount + 1 == mMaximumVisibleLineCount);

            boolean doEllipsis = (firstLine && !moreChars &&
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

        float ellipsisWidth = paint.measureText(HORIZONTAL_ELLIPSIS);
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
        } else if (where == TextUtils.TruncateAt.END || where == TextUtils.TruncateAt.MARQUEE) {
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

    private static final char CHAR_FIRST_CJK = '\u2E80';

    private static final char CHAR_NEW_LINE = '\n';
    private static final char CHAR_TAB = '\t';
    private static final char CHAR_SPACE = ' ';
    private static final char CHAR_DOT = '.';
    private static final char CHAR_COMMA = ',';
    private static final char CHAR_COLON = ':';
    private static final char CHAR_SEMICOLON = ';';
    private static final char CHAR_SLASH = '/';
    private static final char CHAR_HYPHEN = '-';

    private static final double EXTRA_ROUNDING = 0.5;
    private static final String HORIZONTAL_ELLIPSIS = "\u2026"; // this is "..."

    private static final int CHAR_FIRST_HIGH_SURROGATE = 0xD800;
    private static final int CHAR_LAST_LOW_SURROGATE = 0xDFFF;

    /*
     * This is reused across calls to generate()
     */
    private MeasuredText mMeasured;
    private Paint.FontMetricsInt mFontMetricsInt = new Paint.FontMetricsInt();
}
