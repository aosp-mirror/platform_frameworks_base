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

import android.emoji.EmojiFactory;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Path;
import com.android.internal.util.ArrayUtils;

import junit.framework.Assert;
import android.text.style.*;
import android.text.method.TextKeyListener;
import android.view.KeyEvent;

/**
 * A base class that manages text layout in visual elements on 
 * the screen. 
 * <p>For text that will be edited, use a {@link DynamicLayout}, 
 * which will be updated as the text changes.  
 * For text that will not change, use a {@link StaticLayout}.
 */
public abstract class Layout {
    private static final boolean DEBUG = false;
    private static final ParagraphStyle[] NO_PARA_SPANS =
        ArrayUtils.emptyArray(ParagraphStyle.class);

    /* package */ static final EmojiFactory EMOJI_FACTORY =
        EmojiFactory.newAvailableInstance();
    /* package */ static final int MIN_EMOJI, MAX_EMOJI;

    static {
        if (EMOJI_FACTORY != null) {
            MIN_EMOJI = EMOJI_FACTORY.getMinimumAndroidPua();
            MAX_EMOJI = EMOJI_FACTORY.getMaximumAndroidPua();
        } else {
            MIN_EMOJI = -1;
            MAX_EMOJI = -1;
        }
    };

    private RectF mEmojiRect;

    /**
     * Return how wide a layout must be in order to display the
     * specified text with one line per paragraph.
     */
    public static float getDesiredWidth(CharSequence source,
                                        TextPaint paint) {
        return getDesiredWidth(source, 0, source.length(), paint);
    }
    
    /**
     * Return how wide a layout must be in order to display the
     * specified text slice with one line per paragraph.
     */
    public static float getDesiredWidth(CharSequence source,
                                        int start, int end,
                                        TextPaint paint) {
        float need = 0;
        TextPaint workPaint = new TextPaint();

        int next;
        for (int i = start; i <= end; i = next) {
            next = TextUtils.indexOf(source, '\n', i, end);

            if (next < 0)
                next = end;

            // note, omits trailing paragraph char
            float w = measureText(paint, workPaint,
                                  source, i, next, null, true, null);

            if (w > need)
                need = w;

            next++;
        }

        return need;
    }

    /**
     * Subclasses of Layout use this constructor to set the display text,
     * width, and other standard properties.
     * @param text the text to render
     * @param paint the default paint for the layout.  Styles can override
     * various attributes of the paint.
     * @param width the wrapping width for the text.
     * @param align whether to left, right, or center the text.  Styles can
     * override the alignment.
     * @param spacingMult factor by which to scale the font size to get the
     * default line spacing
     * @param spacingAdd amount to add to the default line spacing
     */
    protected Layout(CharSequence text, TextPaint paint,
                     int width, Alignment align,
                     float spacingMult, float spacingAdd) {
        if (width < 0)
            throw new IllegalArgumentException("Layout: " + width + " < 0");

        mText = text;
        mPaint = paint;
        mWorkPaint = new TextPaint();
        mWidth = width;
        mAlignment = align;
        mSpacingMult = spacingMult;
        mSpacingAdd = spacingAdd;
        mSpannedText = text instanceof Spanned;
    }

    /**
     * Replace constructor properties of this Layout with new ones.  Be careful.
     */
    /* package */ void replaceWith(CharSequence text, TextPaint paint,
                              int width, Alignment align,
                              float spacingmult, float spacingadd) {
        if (width < 0) {
            throw new IllegalArgumentException("Layout: " + width + " < 0");
        }

        mText = text;
        mPaint = paint;
        mWidth = width;
        mAlignment = align;
        mSpacingMult = spacingmult;
        mSpacingAdd = spacingadd;
        mSpannedText = text instanceof Spanned;
    }

    /**
     * Draw this Layout on the specified Canvas.
     */
    public void draw(Canvas c) {
        draw(c, null, null, 0);
    }

    /**
     * Draw this Layout on the specified canvas, with the highlight path drawn
     * between the background and the text.
     *
     * @param c the canvas
     * @param highlight the path of the highlight or cursor; can be null
     * @param highlightPaint the paint for the highlight
     * @param cursorOffsetVertical the amount to temporarily translate the
     *        canvas while rendering the highlight
     */
    public void draw(Canvas c, Path highlight, Paint highlightPaint,
                     int cursorOffsetVertical) {
        int dtop, dbottom;

        synchronized (sTempRect) {
            if (!c.getClipBounds(sTempRect)) {
                return;
            }

            dtop = sTempRect.top;
            dbottom = sTempRect.bottom;
        }


        int top = 0;
        int bottom = getLineTop(getLineCount());

        if (dtop > top) {
            top = dtop;
        }
        if (dbottom < bottom) {
            bottom = dbottom;
        }
        
        int first = getLineForVertical(top); 
        int last = getLineForVertical(bottom);
        
        int previousLineBottom = getLineTop(first);
        int previousLineEnd = getLineStart(first);
        
        TextPaint paint = mPaint;
        CharSequence buf = mText;
        int width = mWidth;
        boolean spannedText = mSpannedText;

        ParagraphStyle[] spans = NO_PARA_SPANS;
        int spanend = 0;
        int textLength = 0;

        // First, draw LineBackgroundSpans.
        // LineBackgroundSpans know nothing about the alignment or direction of
        // the layout or line.  XXX: Should they?
        if (spannedText) {
            textLength = buf.length();
            for (int i = first; i <= last; i++) {
                int start = previousLineEnd;
                int end = getLineStart(i+1);
                previousLineEnd = end;

                int ltop = previousLineBottom;
                int lbottom = getLineTop(i+1);
                previousLineBottom = lbottom;
                int lbaseline = lbottom - getLineDescent(i);

                if (start >= spanend) {
                   Spanned sp = (Spanned) buf;
                   spanend = sp.nextSpanTransition(start, textLength,
                                                   LineBackgroundSpan.class);
                   spans = sp.getSpans(start, spanend,
                                       LineBackgroundSpan.class);
                }

                for (int n = 0; n < spans.length; n++) {
                    LineBackgroundSpan back = (LineBackgroundSpan) spans[n];

                    back.drawBackground(c, paint, 0, width,
                                       ltop, lbaseline, lbottom,
                                       buf, start, end,
                                       i);
                }
            }
            // reset to their original values
            spanend = 0;
            previousLineBottom = getLineTop(first);
            previousLineEnd = getLineStart(first);
            spans = NO_PARA_SPANS;
        } 

        // There can be a highlight even without spans if we are drawing
        // a non-spanned transformation of a spanned editing buffer.
        if (highlight != null) {
            if (cursorOffsetVertical != 0) {
                c.translate(0, cursorOffsetVertical);
            }

            c.drawPath(highlight, highlightPaint);

            if (cursorOffsetVertical != 0) {
                c.translate(0, -cursorOffsetVertical);
            }
        }

        Alignment align = mAlignment;
        
        // Next draw the lines, one at a time.
        // the baseline is the top of the following line minus the current
        // line's descent.
        for (int i = first; i <= last; i++) {
            int start = previousLineEnd;

            previousLineEnd = getLineStart(i+1);
            int end = getLineVisibleEnd(i, start, previousLineEnd);

            int ltop = previousLineBottom;
            int lbottom = getLineTop(i+1);
            previousLineBottom = lbottom;
            int lbaseline = lbottom - getLineDescent(i);

            boolean isFirstParaLine = false;
            if (spannedText) { 
                if (start == 0 || buf.charAt(start - 1) == '\n') {
                    isFirstParaLine = true;
                }
                // New batch of paragraph styles, compute the alignment.
                // Last alignment style wins.
                if (start >= spanend) {
                    Spanned sp = (Spanned) buf;
                    spanend = sp.nextSpanTransition(start, textLength,
                                                    ParagraphStyle.class);
                    spans = sp.getSpans(start, spanend, ParagraphStyle.class);
                    
                    align = mAlignment;
                    for (int n = spans.length-1; n >= 0; n--) {
                        if (spans[n] instanceof AlignmentSpan) {
                            align = ((AlignmentSpan) spans[n]).getAlignment();
                            break;
                        }
                    }
                }
            }
            
            int dir = getParagraphDirection(i);
            int left = 0;
            int right = mWidth;

            // Draw all leading margin spans.  Adjust left or right according
            // to the paragraph direction of the line.
            if (spannedText) {
                final int length = spans.length;
                for (int n = 0; n < length; n++) {
                    if (spans[n] instanceof LeadingMarginSpan) {
                        LeadingMarginSpan margin = (LeadingMarginSpan) spans[n];

                        if (dir == DIR_RIGHT_TO_LEFT) {
                            margin.drawLeadingMargin(c, paint, right, dir, ltop,
                                                     lbaseline, lbottom, buf,
                                                     start, end, isFirstParaLine, this);
                                
                            right -= margin.getLeadingMargin(isFirstParaLine);
                        } else {
                            margin.drawLeadingMargin(c, paint, left, dir, ltop,
                                                     lbaseline, lbottom, buf,
                                                     start, end, isFirstParaLine, this);

                            boolean useMargin = isFirstParaLine;
                            if (margin instanceof LeadingMarginSpan.LeadingMarginSpan2) {
                                int count = ((LeadingMarginSpan.LeadingMarginSpan2)margin).getLeadingMarginLineCount();
                                useMargin = count > i;
                            }
                            left += margin.getLeadingMargin(useMargin);
                        }
                    }
                }
            }

            // Adjust the point at which to start rendering depending on the
            // alignment of the paragraph.
            int x;
            if (align == Alignment.ALIGN_NORMAL) {
                if (dir == DIR_LEFT_TO_RIGHT) {
                    x = left;
                } else {
                    x = right;
                }
            } else {
                int max = (int)getLineMax(i, spans, false);
                if (align == Alignment.ALIGN_OPPOSITE) {
                    if (dir == DIR_RIGHT_TO_LEFT) {
                        x = left + max;
                    } else {
                        x = right - max;
                    }
                } else {
                    // Alignment.ALIGN_CENTER
                    max = max & ~1;
                    int half = (right - left - max) >> 1;
                    if (dir == DIR_RIGHT_TO_LEFT) {
                        x = right - half;
                    } else {
                        x = left + half;
                    }
                }
            }

            Directions directions = getLineDirections(i);
            boolean hasTab = getLineContainsTab(i);
            if (directions == DIRS_ALL_LEFT_TO_RIGHT &&
                    !spannedText && !hasTab) {
                if (DEBUG) {
                    Assert.assertTrue(dir == DIR_LEFT_TO_RIGHT);
                    Assert.assertNotNull(c);
                }
                // XXX: assumes there's nothing additional to be done
                c.drawText(buf, start, end, x, lbaseline, paint);
            } else {
                drawText(c, buf, start, end, dir, directions,
                    x, ltop, lbaseline, lbottom, paint, mWorkPaint,
                    hasTab, spans);
            }
        }
    }

    /**
     * Return the text that is displayed by this Layout.
     */
    public final CharSequence getText() {
        return mText;
    }

    /**
     * Return the base Paint properties for this layout.
     * Do NOT change the paint, which may result in funny
     * drawing for this layout.
     */
    public final TextPaint getPaint() {
        return mPaint;
    }

    /**
     * Return the width of this layout.
     */
    public final int getWidth() {
        return mWidth;
    }

    /**
     * Return the width to which this Layout is ellipsizing, or
     * {@link #getWidth} if it is not doing anything special.
     */
    public int getEllipsizedWidth() {
        return mWidth;
    }

    /**
     * Increase the width of this layout to the specified width.
     * Be careful to use this only when you know it is appropriate&mdash;
     * it does not cause the text to reflow to use the full new width.
     */
    public final void increaseWidthTo(int wid) {
        if (wid < mWidth) {
            throw new RuntimeException("attempted to reduce Layout width");
        }

        mWidth = wid;
    }
    
    /**
     * Return the total height of this layout.
     */
    public int getHeight() {
        return getLineTop(getLineCount());
    }

    /**
     * Return the base alignment of this layout.
     */
    public final Alignment getAlignment() {
        return mAlignment;
    }

    /**
     * Return what the text height is multiplied by to get the line height.
     */
    public final float getSpacingMultiplier() {
        return mSpacingMult;
    }

    /**
     * Return the number of units of leading that are added to each line.
     */
    public final float getSpacingAdd() {
        return mSpacingAdd;
    }

    /**
     * Return the number of lines of text in this layout.
     */
    public abstract int getLineCount();
    
    /**
     * Return the baseline for the specified line (0&hellip;getLineCount() - 1)
     * If bounds is not null, return the top, left, right, bottom extents
     * of the specified line in it.
     * @param line which line to examine (0..getLineCount() - 1)
     * @param bounds Optional. If not null, it returns the extent of the line
     * @return the Y-coordinate of the baseline
     */
    public int getLineBounds(int line, Rect bounds) {
        if (bounds != null) {
            bounds.left = 0;     // ???
            bounds.top = getLineTop(line);
            bounds.right = mWidth;   // ???
            bounds.bottom = getLineTop(line + 1);
        }
        return getLineBaseline(line);
    }

    /**
     * Return the vertical position of the top of the specified line
     * (0&hellip;getLineCount()).
     * If the specified line is equal to the line count, returns the
     * bottom of the last line.
     */
    public abstract int getLineTop(int line);

    /**
     * Return the descent of the specified line(0&hellip;getLineCount() - 1).
     */
    public abstract int getLineDescent(int line);

    /**
     * Return the text offset of the beginning of the specified line (
     * 0&hellip;getLineCount()). If the specified line is equal to the line
     * count, returns the length of the text.
     */
    public abstract int getLineStart(int line);

    /**
     * Returns the primary directionality of the paragraph containing the
     * specified line, either 1 for left-to-right lines, or -1 for right-to-left
     * lines (see {@link #DIR_LEFT_TO_RIGHT}, {@link #DIR_RIGHT_TO_LEFT}).
     */
    public abstract int getParagraphDirection(int line);

    /**
     * Returns whether the specified line contains one or more
     * characters that need to be handled specially, like tabs
     * or emoji.
     */
    public abstract boolean getLineContainsTab(int line);

    /**
     * Returns the directional run information for the specified line.
     * The array alternates counts of characters in left-to-right
     * and right-to-left segments of the line.
     *
     * <p>NOTE: this is inadequate to support bidirectional text, and will change.
     */
    public abstract Directions getLineDirections(int line);

    /**
     * Returns the (negative) number of extra pixels of ascent padding in the
     * top line of the Layout.
     */
    public abstract int getTopPadding();

    /**
     * Returns the number of extra pixels of descent padding in the
     * bottom line of the Layout.
     */
    public abstract int getBottomPadding();

    /**
     * Get the primary horizontal position for the specified text offset.
     * This is the location where a new character would be inserted in
     * the paragraph's primary direction.
     */
    public float getPrimaryHorizontal(int offset) {
        return getHorizontal(offset, false, true);
    }

    /**
     * Get the secondary horizontal position for the specified text offset.
     * This is the location where a new character would be inserted in
     * the direction other than the paragraph's primary direction.
     */
    public float getSecondaryHorizontal(int offset) {
        return getHorizontal(offset, true, true);
    }

    private float getHorizontal(int offset, boolean trailing, boolean alt) {
        int line = getLineForOffset(offset);

        return getHorizontal(offset, trailing, alt, line);
    }

    private float getHorizontal(int offset, boolean trailing, boolean alt,
                                int line) {
        int start = getLineStart(line);
        int end = getLineVisibleEnd(line);
        int dir = getParagraphDirection(line);
        boolean tab = getLineContainsTab(line);
        Directions directions = getLineDirections(line);

        TabStopSpan[] tabs = null;
        if (tab && mText instanceof Spanned) {
            tabs = ((Spanned) mText).getSpans(start, end, TabStopSpan.class);
        }

        float wid = measureText(mPaint, mWorkPaint, mText, start, offset, end,
                                dir, directions, trailing, alt, tab, tabs);

        if (offset > end) {
            if (dir == DIR_RIGHT_TO_LEFT)
                wid -= measureText(mPaint, mWorkPaint,
                                   mText, end, offset, null, tab, tabs);
            else
                wid += measureText(mPaint, mWorkPaint,
                                   mText, end, offset, null, tab, tabs);
        }

        Alignment align = getParagraphAlignment(line);
        int left = getParagraphLeft(line);
        int right = getParagraphRight(line);

        if (align == Alignment.ALIGN_NORMAL) {
            if (dir == DIR_RIGHT_TO_LEFT)
                return right + wid;
            else
                return left + wid;
        }

        float max = getLineMax(line);

        if (align == Alignment.ALIGN_OPPOSITE) {
            if (dir == DIR_RIGHT_TO_LEFT)
                return left + max + wid;
            else
                return right - max + wid;
        } else { /* align == Alignment.ALIGN_CENTER */
            int imax = ((int) max) & ~1;

            if (dir == DIR_RIGHT_TO_LEFT)
                return right - (((right - left) - imax) / 2) + wid;
            else
                return left + ((right - left) - imax) / 2 + wid;
        }
    }

    /**
     * Get the leftmost position that should be exposed for horizontal
     * scrolling on the specified line.
     */
    public float getLineLeft(int line) {
        int dir = getParagraphDirection(line);
        Alignment align = getParagraphAlignment(line);

        if (align == Alignment.ALIGN_NORMAL) {
            if (dir == DIR_RIGHT_TO_LEFT)
                return getParagraphRight(line) - getLineMax(line);
            else
                return 0;
        } else if (align == Alignment.ALIGN_OPPOSITE) {
            if (dir == DIR_RIGHT_TO_LEFT)
                return 0;
            else
                return mWidth - getLineMax(line);
        } else { /* align == Alignment.ALIGN_CENTER */
            int left = getParagraphLeft(line);
            int right = getParagraphRight(line);
            int max = ((int) getLineMax(line)) & ~1;

            return left + ((right - left) - max) / 2;
        }
    }

    /**
     * Get the rightmost position that should be exposed for horizontal
     * scrolling on the specified line.
     */
    public float getLineRight(int line) {
        int dir = getParagraphDirection(line);
        Alignment align = getParagraphAlignment(line);

        if (align == Alignment.ALIGN_NORMAL) {
            if (dir == DIR_RIGHT_TO_LEFT)
                return mWidth;
            else
                return getParagraphLeft(line) + getLineMax(line);
        } else if (align == Alignment.ALIGN_OPPOSITE) {
            if (dir == DIR_RIGHT_TO_LEFT)
                return getLineMax(line);
            else
                return mWidth;
        } else { /* align == Alignment.ALIGN_CENTER */
            int left = getParagraphLeft(line);
            int right = getParagraphRight(line);
            int max = ((int) getLineMax(line)) & ~1;

            return right - ((right - left) - max) / 2;
        }
    }

    /**
     * Gets the horizontal extent of the specified line, excluding
     * trailing whitespace.
     */
    public float getLineMax(int line) {
        return getLineMax(line, null, false);
    }

    /**
     * Gets the horizontal extent of the specified line, including
     * trailing whitespace.
     */
    public float getLineWidth(int line) {
        return getLineMax(line, null, true);
    }

    private float getLineMax(int line, Object[] tabs, boolean full) {
        int start = getLineStart(line);
        int end;

        if (full) {
            end = getLineEnd(line);
        } else {
            end = getLineVisibleEnd(line);
        } 
        boolean tab = getLineContainsTab(line);

        if (tabs == null && tab && mText instanceof Spanned) {
            tabs = ((Spanned) mText).getSpans(start, end, TabStopSpan.class);
        }

        return measureText(mPaint, mWorkPaint,
                           mText, start, end, null, tab, tabs);
    }

    /**
     * Get the line number corresponding to the specified vertical position.
     * If you ask for a position above 0, you get 0; if you ask for a position
     * below the bottom of the text, you get the last line.
     */
    // FIXME: It may be faster to do a linear search for layouts without many lines.
    public int getLineForVertical(int vertical) {
        int high = getLineCount(), low = -1, guess;

        while (high - low > 1) {
            guess = (high + low) / 2;

            if (getLineTop(guess) > vertical)
                high = guess;
            else
                low = guess;
        }

        if (low < 0)
            return 0;
        else
            return low;
    }

    /**
     * Get the line number on which the specified text offset appears.
     * If you ask for a position before 0, you get 0; if you ask for a position
     * beyond the end of the text, you get the last line.
     */
    public int getLineForOffset(int offset) {
        int high = getLineCount(), low = -1, guess;

        while (high - low > 1) {
            guess = (high + low) / 2;

            if (getLineStart(guess) > offset)
                high = guess;
            else
                low = guess;
        }

        if (low < 0)
            return 0;
        else
            return low;
    }

    /**
     * Get the character offset on the specfied line whose position is
     * closest to the specified horizontal position.
     */
    public int getOffsetForHorizontal(int line, float horiz) {
        int max = getLineEnd(line) - 1;
        int min = getLineStart(line);
        Directions dirs = getLineDirections(line);

        if (line == getLineCount() - 1)
            max++;

        int best = min;
        float bestdist = Math.abs(getPrimaryHorizontal(best) - horiz);

        int here = min;
        for (int i = 0; i < dirs.mDirections.length; i++) {
            int there = here + dirs.mDirections[i];
            int swap = ((i & 1) == 0) ? 1 : -1;

            if (there > max)
                there = max;

            int high = there - 1 + 1, low = here + 1 - 1, guess;

            while (high - low > 1) {
                guess = (high + low) / 2;
                int adguess = getOffsetAtStartOf(guess);

                if (getPrimaryHorizontal(adguess) * swap >= horiz * swap)
                    high = guess;
                else
                    low = guess;
            }

            if (low < here + 1)
                low = here + 1;

            if (low < there) {
                low = getOffsetAtStartOf(low);

                float dist = Math.abs(getPrimaryHorizontal(low) - horiz);

                int aft = TextUtils.getOffsetAfter(mText, low);
                if (aft < there) {
                    float other = Math.abs(getPrimaryHorizontal(aft) - horiz);

                    if (other < dist) {
                        dist = other;
                        low = aft;
                    }
                }

                if (dist < bestdist) {
                    bestdist = dist;
                    best = low;   
                }
            }

            float dist = Math.abs(getPrimaryHorizontal(here) - horiz);

            if (dist < bestdist) {
                bestdist = dist;
                best = here;
            }

            here = there;
        }

        float dist = Math.abs(getPrimaryHorizontal(max) - horiz);

        if (dist < bestdist) {
            bestdist = dist;
            best = max;
        }

        return best;
    }

    /**
     * Return the text offset after the last character on the specified line.
     */
    public final int getLineEnd(int line) {
        return getLineStart(line + 1);
    }

    /** 
     * Return the text offset after the last visible character (so whitespace
     * is not counted) on the specified line.
     */
    public int getLineVisibleEnd(int line) {
        return getLineVisibleEnd(line, getLineStart(line), getLineStart(line+1));
    }
    
    private int getLineVisibleEnd(int line, int start, int end) {
        if (DEBUG) {
            Assert.assertTrue(getLineStart(line) == start && getLineStart(line+1) == end);
        }

        CharSequence text = mText;
        char ch;
        if (line == getLineCount() - 1) {
            return end;
        }

        for (; end > start; end--) {
            ch = text.charAt(end - 1);

            if (ch == '\n') {
                return end - 1;
            }

            if (ch != ' ' && ch != '\t') {
                break;
            }

        }

        return end;
    }

    /**
     * Return the vertical position of the bottom of the specified line.
     */
    public final int getLineBottom(int line) {
        return getLineTop(line + 1);
    }

    /**
     * Return the vertical position of the baseline of the specified line.
     */
    public final int getLineBaseline(int line) {
        // getLineTop(line+1) == getLineTop(line)
        return getLineTop(line+1) - getLineDescent(line);
    }

    /**
     * Get the ascent of the text on the specified line.
     * The return value is negative to match the Paint.ascent() convention.
     */
    public final int getLineAscent(int line) {
        // getLineTop(line+1) - getLineDescent(line) == getLineBaseLine(line)
        return getLineTop(line) - (getLineTop(line+1) - getLineDescent(line));
    }

    /**
     * Return the text offset that would be reached by moving left
     * (possibly onto another line) from the specified offset.
     */
    public int getOffsetToLeftOf(int offset) {
        int line = getLineForOffset(offset);
        int start = getLineStart(line);
        int end = getLineEnd(line);
        Directions dirs = getLineDirections(line);

        if (line != getLineCount() - 1)
            end--;

        float horiz = getPrimaryHorizontal(offset);

        int best = offset;
        float besth = Integer.MIN_VALUE;
        int candidate;

        candidate = TextUtils.getOffsetBefore(mText, offset);
        if (candidate >= start && candidate <= end) {
            float h = getPrimaryHorizontal(candidate);

            if (h < horiz && h > besth) {
                best = candidate;
                besth = h;
            }
        }

        candidate = TextUtils.getOffsetAfter(mText, offset);
        if (candidate >= start && candidate <= end) {
            float h = getPrimaryHorizontal(candidate);

            if (h < horiz && h > besth) {
                best = candidate;
                besth = h;
            }
        }

        int here = start;
        for (int i = 0; i < dirs.mDirections.length; i++) {
            int there = here + dirs.mDirections[i];
            if (there > end)
                there = end;

            float h = getPrimaryHorizontal(here);

            if (h < horiz && h > besth) {
                best = here;
                besth = h;
            }

            candidate = TextUtils.getOffsetAfter(mText, here);
            if (candidate >= start && candidate <= end) {
                h = getPrimaryHorizontal(candidate);

                if (h < horiz && h > besth) {
                    best = candidate;
                    besth = h;
                }
            }

            candidate = TextUtils.getOffsetBefore(mText, there);
            if (candidate >= start && candidate <= end) {
                h = getPrimaryHorizontal(candidate);

                if (h < horiz && h > besth) {
                    best = candidate;
                    besth = h;
                }
            }

            here = there;
        }

        float h = getPrimaryHorizontal(end);

        if (h < horiz && h > besth) {
            best = end;
            besth = h;
        }

        if (best != offset)
            return best;

        int dir = getParagraphDirection(line);

        if (dir > 0) {
            if (line == 0)
                return best;
            else
                return getOffsetForHorizontal(line - 1, 10000);
        } else {
            if (line == getLineCount() - 1)
                return best;
            else
                return getOffsetForHorizontal(line + 1, 10000);
        }
    }

    /**
     * Return the text offset that would be reached by moving right
     * (possibly onto another line) from the specified offset.
     */
    public int getOffsetToRightOf(int offset) {
        int line = getLineForOffset(offset);
        int start = getLineStart(line);
        int end = getLineEnd(line);
        Directions dirs = getLineDirections(line);

        if (line != getLineCount() - 1)
            end--;

        float horiz = getPrimaryHorizontal(offset);

        int best = offset;
        float besth = Integer.MAX_VALUE;
        int candidate;

        candidate = TextUtils.getOffsetBefore(mText, offset);
        if (candidate >= start && candidate <= end) {
            float h = getPrimaryHorizontal(candidate);

            if (h > horiz && h < besth) {
                best = candidate;
                besth = h;
            }
        }

        candidate = TextUtils.getOffsetAfter(mText, offset);
        if (candidate >= start && candidate <= end) {
            float h = getPrimaryHorizontal(candidate);

            if (h > horiz && h < besth) {
                best = candidate;
                besth = h;
            }
        }

        int here = start;
        for (int i = 0; i < dirs.mDirections.length; i++) {
            int there = here + dirs.mDirections[i];
            if (there > end)
                there = end;

            float h = getPrimaryHorizontal(here);

            if (h > horiz && h < besth) {
                best = here;
                besth = h;
            }

            candidate = TextUtils.getOffsetAfter(mText, here);
            if (candidate >= start && candidate <= end) {
                h = getPrimaryHorizontal(candidate);

                if (h > horiz && h < besth) {
                    best = candidate;
                    besth = h;
                }
            }

            candidate = TextUtils.getOffsetBefore(mText, there);
            if (candidate >= start && candidate <= end) {
                h = getPrimaryHorizontal(candidate);

                if (h > horiz && h < besth) {
                    best = candidate;
                    besth = h;
                }
            }

            here = there;
        }

        float h = getPrimaryHorizontal(end);

        if (h > horiz && h < besth) {
            best = end;
            besth = h;
        }

        if (best != offset)
            return best;

        int dir = getParagraphDirection(line);

        if (dir > 0) {
            if (line == getLineCount() - 1)
                return best;
            else
                return getOffsetForHorizontal(line + 1, -10000);
        } else {
            if (line == 0)
                return best;
            else
                return getOffsetForHorizontal(line - 1, -10000);
        }
    }

    private int getOffsetAtStartOf(int offset) {
        if (offset == 0)
            return 0;

        CharSequence text = mText;
        char c = text.charAt(offset);

        if (c >= '\uDC00' && c <= '\uDFFF') {
            char c1 = text.charAt(offset - 1);

            if (c1 >= '\uD800' && c1 <= '\uDBFF')
                offset -= 1;
        }

        if (mSpannedText) {
            ReplacementSpan[] spans = ((Spanned) text).getSpans(offset, offset,
                                                       ReplacementSpan.class);

            for (int i = 0; i < spans.length; i++) {
                int start = ((Spanned) text).getSpanStart(spans[i]);
                int end = ((Spanned) text).getSpanEnd(spans[i]);

                if (start < offset && end > offset)
                    offset = start;
            }
        }

        return offset;
    }

    /**
     * Fills in the specified Path with a representation of a cursor
     * at the specified offset.  This will often be a vertical line
     * but can be multiple discontinous lines in text with multiple
     * directionalities.
     */
    public void getCursorPath(int point, Path dest,
                              CharSequence editingBuffer) {
        dest.reset();

        int line = getLineForOffset(point);
        int top = getLineTop(line);
        int bottom = getLineTop(line+1);

        float h1 = getPrimaryHorizontal(point) - 0.5f;
        float h2 = getSecondaryHorizontal(point) - 0.5f;

        int caps = TextKeyListener.getMetaState(editingBuffer,
                                                KeyEvent.META_SHIFT_ON) |
                   TextKeyListener.getMetaState(editingBuffer,
                                                TextKeyListener.META_SELECTING);
        int fn = TextKeyListener.getMetaState(editingBuffer,
                                              KeyEvent.META_ALT_ON);
        int dist = 0;

        if (caps != 0 || fn != 0) {
            dist = (bottom - top) >> 2;

            if (fn != 0)
                top += dist;
            if (caps != 0)
                bottom -= dist;
        }

        if (h1 < 0.5f)
            h1 = 0.5f;
        if (h2 < 0.5f)
            h2 = 0.5f;

        if (h1 == h2) {
            dest.moveTo(h1, top);
            dest.lineTo(h1, bottom);
        } else {
            dest.moveTo(h1, top);
            dest.lineTo(h1, (top + bottom) >> 1);

            dest.moveTo(h2, (top + bottom) >> 1);
            dest.lineTo(h2, bottom);
        }

        if (caps == 2) {
            dest.moveTo(h2, bottom);
            dest.lineTo(h2 - dist, bottom + dist);
            dest.lineTo(h2, bottom);
            dest.lineTo(h2 + dist, bottom + dist);
        } else if (caps == 1) {
            dest.moveTo(h2, bottom);
            dest.lineTo(h2 - dist, bottom + dist);

            dest.moveTo(h2 - dist, bottom + dist - 0.5f);
            dest.lineTo(h2 + dist, bottom + dist - 0.5f);

            dest.moveTo(h2 + dist, bottom + dist);
            dest.lineTo(h2, bottom);
        }

        if (fn == 2) {
            dest.moveTo(h1, top);
            dest.lineTo(h1 - dist, top - dist);
            dest.lineTo(h1, top);
            dest.lineTo(h1 + dist, top - dist);
        } else if (fn == 1) {
            dest.moveTo(h1, top);
            dest.lineTo(h1 - dist, top - dist);

            dest.moveTo(h1 - dist, top - dist + 0.5f);
            dest.lineTo(h1 + dist, top - dist + 0.5f);

            dest.moveTo(h1 + dist, top - dist);
            dest.lineTo(h1, top);
        }
    }

    private void addSelection(int line, int start, int end,
                              int top, int bottom, Path dest) {
        int linestart = getLineStart(line);
        int lineend = getLineEnd(line);
        Directions dirs = getLineDirections(line);

        if (lineend > linestart && mText.charAt(lineend - 1) == '\n')
            lineend--;

        int here = linestart;
        for (int i = 0; i < dirs.mDirections.length; i++) {
            int there = here + dirs.mDirections[i];
            if (there > lineend)
                there = lineend;

            if (start <= there && end >= here) {
                int st = Math.max(start, here);
                int en = Math.min(end, there);

                if (st != en) {
                    float h1 = getHorizontal(st, false, false, line);
                    float h2 = getHorizontal(en, true, false, line);

                    dest.addRect(h1, top, h2, bottom, Path.Direction.CW);
                }
            }

            here = there;
        }
    }

    /**
     * Fills in the specified Path with a representation of a highlight
     * between the specified offsets.  This will often be a rectangle
     * or a potentially discontinuous set of rectangles.  If the start
     * and end are the same, the returned path is empty.
     */
    public void getSelectionPath(int start, int end, Path dest) {
        dest.reset();

        if (start == end)
            return;

        if (end < start) {
            int temp = end;
            end = start;
            start = temp;
        }

        int startline = getLineForOffset(start);
        int endline = getLineForOffset(end);

        int top = getLineTop(startline);
        int bottom = getLineBottom(endline);

        if (startline == endline) {
            addSelection(startline, start, end, top, bottom, dest);
        } else {
            final float width = mWidth;

            addSelection(startline, start, getLineEnd(startline),
                         top, getLineBottom(startline), dest);
            
            if (getParagraphDirection(startline) == DIR_RIGHT_TO_LEFT)
                dest.addRect(getLineLeft(startline), top,
                              0, getLineBottom(startline), Path.Direction.CW);
            else
                dest.addRect(getLineRight(startline), top,
                              width, getLineBottom(startline), Path.Direction.CW);

            for (int i = startline + 1; i < endline; i++) {
                top = getLineTop(i);
                bottom = getLineBottom(i);
                dest.addRect(0, top, width, bottom, Path.Direction.CW);
            }

            top = getLineTop(endline);
            bottom = getLineBottom(endline);

            addSelection(endline, getLineStart(endline), end,
                         top, bottom, dest);

            if (getParagraphDirection(endline) == DIR_RIGHT_TO_LEFT)
                dest.addRect(width, top, getLineRight(endline), bottom, Path.Direction.CW);
            else
                dest.addRect(0, top, getLineLeft(endline), bottom, Path.Direction.CW);
        }
    }

    /**
     * Get the alignment of the specified paragraph, taking into account
     * markup attached to it.
     */
    public final Alignment getParagraphAlignment(int line) {
        Alignment align = mAlignment;

        if (mSpannedText) {
            Spanned sp = (Spanned) mText;
            AlignmentSpan[] spans = sp.getSpans(getLineStart(line),
                                                getLineEnd(line),
                                                AlignmentSpan.class);

            int spanLength = spans.length;
            if (spanLength > 0) {
                align = spans[spanLength-1].getAlignment();
            }
        }

        return align;
    }

    /**
     * Get the left edge of the specified paragraph, inset by left margins.
     */
    public final int getParagraphLeft(int line) {
        int dir = getParagraphDirection(line);

        int left = 0;

        boolean par = false;
        int off = getLineStart(line);
        if (off == 0 || mText.charAt(off - 1) == '\n')
            par = true;

        if (dir == DIR_LEFT_TO_RIGHT) {
            if (mSpannedText) {
                Spanned sp = (Spanned) mText;
                LeadingMarginSpan[] spans = sp.getSpans(getLineStart(line),
                                                        getLineEnd(line),
                                                        LeadingMarginSpan.class);

                for (int i = 0; i < spans.length; i++) {
                    boolean margin = par;
                    LeadingMarginSpan span = spans[i];
                    if (span instanceof LeadingMarginSpan.LeadingMarginSpan2) {
                        int count = ((LeadingMarginSpan.LeadingMarginSpan2)span).getLeadingMarginLineCount();
                        margin = count >= line;
                    }
                    left += span.getLeadingMargin(margin);
                }
            }
        }

        return left;
    }

    /**
     * Get the right edge of the specified paragraph, inset by right margins.
     */
    public final int getParagraphRight(int line) {
        int dir = getParagraphDirection(line);

        int right = mWidth;

        boolean par = false;
        int off = getLineStart(line);
        if (off == 0 || mText.charAt(off - 1) == '\n')
            par = true;


        if (dir == DIR_RIGHT_TO_LEFT) {
            if (mSpannedText) {
                Spanned sp = (Spanned) mText;
                LeadingMarginSpan[] spans = sp.getSpans(getLineStart(line),
                                                        getLineEnd(line),
                                                        LeadingMarginSpan.class);

                for (int i = 0; i < spans.length; i++) {
                    right -= spans[i].getLeadingMargin(par);
                }
            }
        }

        return right;
    }

    private void drawText(Canvas canvas,
                                 CharSequence text, int start, int end,
                                 int dir, Directions directions,
                                 float x, int top, int y, int bottom,
                                 TextPaint paint,
                                 TextPaint workPaint,
                                 boolean hasTabs, Object[] parspans) {
        char[] buf;
        if (!hasTabs) {
            if (directions == DIRS_ALL_LEFT_TO_RIGHT) {
                if (DEBUG) {
                    Assert.assertTrue(DIR_LEFT_TO_RIGHT == dir);
                }
                Styled.drawText(canvas, text, start, end, dir, false, x, top, y, bottom, paint, workPaint, false);
                return;
            }
            buf = null;
        } else {
            buf = TextUtils.obtain(end - start);
            TextUtils.getChars(text, start, end, buf, 0);
        }

        float h = 0;

        int here = 0;
        for (int i = 0; i < directions.mDirections.length; i++) {
            int there = here + directions.mDirections[i];
            if (there > end - start)
                there = end - start;

            int segstart = here;
            for (int j = hasTabs ? here : there; j <= there; j++) {
                if (j == there || buf[j] == '\t') {
                    h += Styled.drawText(canvas, text,
                                         start + segstart, start + j,
                                         dir, (i & 1) != 0, x + h,
                                         top, y, bottom, paint, workPaint,
                                         start + j != end);

                    if (j != there && buf[j] == '\t')
                        h = dir * nextTab(text, start, end, h * dir, parspans);

                    segstart = j + 1;
                } else if (hasTabs && buf[j] >= 0xD800 && buf[j] <= 0xDFFF && j + 1 < there) {
                    int emoji = Character.codePointAt(buf, j);

                    if (emoji >= MIN_EMOJI && emoji <= MAX_EMOJI) {
                        Bitmap bm = EMOJI_FACTORY.
                            getBitmapFromAndroidPua(emoji);

                        if (bm != null) {
                            h += Styled.drawText(canvas, text,
                                                 start + segstart, start + j,
                                                 dir, (i & 1) != 0, x + h,
                                                 top, y, bottom, paint, workPaint,
                                                 start + j != end);

                            if (mEmojiRect == null) {
                                mEmojiRect = new RectF();
                            }

                            workPaint.set(paint);
                            Styled.measureText(paint, workPaint, text,
                                               start + j, start + j + 1,
                                               null);
                                        
                            float bitmapHeight = bm.getHeight();
                            float textHeight = -workPaint.ascent();
                            float scale = textHeight / bitmapHeight;
                            float width = bm.getWidth() * scale;

                            mEmojiRect.set(x + h, y - textHeight,
                                           x + h + width, y);

                            canvas.drawBitmap(bm, null, mEmojiRect, paint);
                            h += width;

                            j++;
                            segstart = j + 1;
                        }
                    }
                }
            }

            here = there;
        }

        if (hasTabs)
            TextUtils.recycle(buf);
    }

    private static float measureText(TextPaint paint,
                                     TextPaint workPaint,
                                     CharSequence text,
                                     int start, int offset, int end,
                                     int dir, Directions directions,
                                     boolean trailing, boolean alt,
                                     boolean hasTabs, Object[] tabs) {
        char[] buf = null;

        if (hasTabs) {
            buf = TextUtils.obtain(end - start);
            TextUtils.getChars(text, start, end, buf, 0);
        }

        float h = 0;

        if (alt) {
            if (dir == DIR_RIGHT_TO_LEFT)
                trailing = !trailing;
        }

        int here = 0;
        for (int i = 0; i < directions.mDirections.length; i++) {
            if (alt)
                trailing = !trailing;

            int there = here + directions.mDirections[i];
            if (there > end - start)
                there = end - start;

            int segstart = here;
            for (int j = hasTabs ? here : there; j <= there; j++) {
                int codept = 0;
                Bitmap bm = null;

                if (hasTabs && j < there) {
                    codept = buf[j];
                }

                if (codept >= 0xD800 && codept <= 0xDFFF && j + 1 < there) {
                    codept = Character.codePointAt(buf, j);

                    if (codept >= MIN_EMOJI && codept <= MAX_EMOJI) {
                        bm = EMOJI_FACTORY.getBitmapFromAndroidPua(codept);
                    }
                }

                if (j == there || codept == '\t' || bm != null) {
                    float segw;

                    if (offset < start + j ||
                       (trailing && offset <= start + j)) {
                        if (dir == DIR_LEFT_TO_RIGHT && (i & 1) == 0) {
                            h += Styled.measureText(paint, workPaint, text,
                                                    start + segstart, offset,
                                                    null);
                            return h;
                        }

                        if (dir == DIR_RIGHT_TO_LEFT && (i & 1) != 0) {
                            h -= Styled.measureText(paint, workPaint, text,
                                                    start + segstart, offset,
                                                    null);
                            return h;
                        }
                    }

                    segw = Styled.measureText(paint, workPaint, text,
                                              start + segstart, start + j,
                                              null);

                    if (offset < start + j ||
                        (trailing && offset <= start + j)) {
                        if (dir == DIR_LEFT_TO_RIGHT) {
                            h += segw - Styled.measureText(paint, workPaint,
                                                           text,
                                                           start + segstart,
                                                           offset, null);
                            return h;
                        }

                        if (dir == DIR_RIGHT_TO_LEFT) {
                            h -= segw - Styled.measureText(paint, workPaint,
                                                           text,
                                                           start + segstart,
                                                           offset, null);
                            return h;
                        }
                    }

                    if (dir == DIR_RIGHT_TO_LEFT)
                        h -= segw;
                    else
                        h += segw;

                    if (j != there && buf[j] == '\t') {
                        if (offset == start + j)
                            return h;

                        h = dir * nextTab(text, start, end, h * dir, tabs);
                    }

                    if (bm != null) {
                        workPaint.set(paint);
                        Styled.measureText(paint, workPaint, text,
                                           j, j + 2, null);

                        float wid = (float) bm.getWidth() *
                                    -workPaint.ascent() / bm.getHeight();

                        if (dir == DIR_RIGHT_TO_LEFT) {
                            h -= wid;
                        } else {
                            h += wid;
                        }

                        j++;
                    }

                    segstart = j + 1;
                }
            }

            here = there;
        }

        if (hasTabs)
            TextUtils.recycle(buf);

        return h;
    }

    /**
     * Measure width of a run of text on a single line that is known to all be
     * in the same direction as the paragraph base direction. Returns the width,
     * and the line metrics in fm if fm is not null.
     *
     * @param paint the paint for the text; will not be modified
     * @param workPaint paint available for modification
     * @param text text
     * @param start start of the line
     * @param end limit of the line
     * @param fm object to return integer metrics in, can be null
     * @param hasTabs true if it is known that the line has tabs
     * @param tabs tab position information
     * @return the width of the text from start to end
     */
    /* package */ static float measureText(TextPaint paint,
                                           TextPaint workPaint,
                                           CharSequence text,
                                           int start, int end,
                                           Paint.FontMetricsInt fm,
                                           boolean hasTabs, Object[] tabs) {
        char[] buf = null;
  
        if (hasTabs) {
            buf = TextUtils.obtain(end - start);
            TextUtils.getChars(text, start, end, buf, 0);
        }

        int len = end - start;

        int lastPos = 0;
        float width = 0;
        int ascent = 0, descent = 0, top = 0, bottom = 0;

        if (fm != null) {
            fm.ascent = 0;
            fm.descent = 0;
        }

        for (int pos = hasTabs ? 0 : len; pos <= len; pos++) {
            int codept = 0;
            Bitmap bm = null;

            if (hasTabs && pos < len) {
                codept = buf[pos];
            }

            if (codept >= 0xD800 && codept <= 0xDFFF && pos < len) {
                codept = Character.codePointAt(buf, pos);

                if (codept >= MIN_EMOJI && codept <= MAX_EMOJI) {
                    bm = EMOJI_FACTORY.getBitmapFromAndroidPua(codept);
                }
            }

            if (pos == len || codept == '\t' || bm != null) {
                workPaint.baselineShift = 0;

                width += Styled.measureText(paint, workPaint, text,
                                        start + lastPos, start + pos,
                                        fm);

                if (fm != null) {
                    if (workPaint.baselineShift < 0) {
                        fm.ascent += workPaint.baselineShift;
                        fm.top += workPaint.baselineShift;
                    } else {
                        fm.descent += workPaint.baselineShift;
                        fm.bottom += workPaint.baselineShift;
                    }
                }

                if (pos != len) {
                    if (bm == null) {
                        // no emoji, must have hit a tab
                        width = nextTab(text, start, end, width, tabs);
                    } else {
                        // This sets up workPaint with the font on the emoji
                        // text, so that we can extract the ascent and scale.

                        // We can't use the result of the previous call to
                        // measureText because the emoji might have its own style.
                        // We have to initialize workPaint here because if the
                        // text is unstyled measureText might not use workPaint
                        // at all.
                        workPaint.set(paint);
                        Styled.measureText(paint, workPaint, text,
                                           start + pos, start + pos + 1, null);

                        width += (float) bm.getWidth() *
                                    -workPaint.ascent() / bm.getHeight();

                        // Since we had an emoji, we bump past the second half
                        // of the surrogate pair.
                        pos++;
                    }
                }

                if (fm != null) {
                    if (fm.ascent < ascent) {
                        ascent = fm.ascent;
                    }
                    if (fm.descent > descent) {
                        descent = fm.descent;
                    }

                    if (fm.top < top) {
                        top = fm.top;
                    }
                    if (fm.bottom > bottom) {
                        bottom = fm.bottom;
                    }

                    // No need to take bitmap height into account here,
                    // since it is scaled to match the text height.
                }

                lastPos = pos + 1;
            }
        }

        if (fm != null) {
            fm.ascent = ascent;
            fm.descent = descent;
            fm.top = top;
            fm.bottom = bottom;
        }

        if (hasTabs)
            TextUtils.recycle(buf);

        return width;
    }

    /**
     * Returns the position of the next tab stop after h on the line.
     *
     * @param text the text
     * @param start start of the line
     * @param end limit of the line
     * @param h the current horizontal offset
     * @param tabs the tabs, can be null.  If it is null, any tabs in effect
     * on the line will be used.  If there are no tabs, a default offset
     * will be used to compute the tab stop.
     * @return the offset of the next tab stop.
     */
    /* package */ static float nextTab(CharSequence text, int start, int end,
                                       float h, Object[] tabs) {
        float nh = Float.MAX_VALUE;
        boolean alltabs = false;

        if (text instanceof Spanned) {
            if (tabs == null) {
                tabs = ((Spanned) text).getSpans(start, end, TabStopSpan.class);
                alltabs = true;
            }

            for (int i = 0; i < tabs.length; i++) {
                if (!alltabs) {
                    if (!(tabs[i] instanceof TabStopSpan))
                        continue;
                }

                int where = ((TabStopSpan) tabs[i]).getTabStop();

                if (where < nh && where > h)
                    nh = where;
            }

            if (nh != Float.MAX_VALUE)
                return nh;
        }

        return ((int) ((h + TAB_INCREMENT) / TAB_INCREMENT)) * TAB_INCREMENT;
    }

    protected final boolean isSpanned() {
        return mSpannedText;
    }

    private void ellipsize(int start, int end, int line,
                           char[] dest, int destoff) {
        int ellipsisCount = getEllipsisCount(line);

        if (ellipsisCount == 0) {
            return;
        }

        int ellipsisStart = getEllipsisStart(line);
        int linestart = getLineStart(line);

        for (int i = ellipsisStart; i < ellipsisStart + ellipsisCount; i++) {
            char c;

            if (i == ellipsisStart) {
                c = '\u2026'; // ellipsis
            } else {
                c = '\uFEFF'; // 0-width space
            }

            int a = i + linestart;

            if (a >= start && a < end) {
                dest[destoff + a - start] = c;
            }
        }
    }

    /**
     * Stores information about bidirectional (left-to-right or right-to-left)
     * text within the layout of a line.  TODO: This work is not complete
     * or correct and will be fleshed out in a later revision.
     */
    public static class Directions {
        private short[] mDirections;

        // The values in mDirections are the offsets from the first character
        // in the line to the next flip in direction.  Runs at even indices
        // are left-to-right, the others are right-to-left.  So, for example,
        // a line that starts with a right-to-left run has 0 at mDirections[0],
        // since the 'first' (ltr) run is zero length.
        //
        // The code currently assumes that each run is adjacent to the previous
        // one, progressing in the base line direction.  This isn't sufficient
        // to handle nested runs, for example numeric text in an rtl context
        // in an ltr paragraph.
        /* package */ Directions(short[] dirs) {
            mDirections = dirs;
        }
    }

    /**
     * Return the offset of the first character to be ellipsized away,
     * relative to the start of the line.  (So 0 if the beginning of the
     * line is ellipsized, not getLineStart().)
     */
    public abstract int getEllipsisStart(int line);
    /**
     * Returns the number of characters to be ellipsized away, or 0 if
     * no ellipsis is to take place.
     */
    public abstract int getEllipsisCount(int line);

    /* package */ static class Ellipsizer implements CharSequence, GetChars {
        /* package */ CharSequence mText;
        /* package */ Layout mLayout;
        /* package */ int mWidth;
        /* package */ TextUtils.TruncateAt mMethod;

        public Ellipsizer(CharSequence s) {
            mText = s;
        }

        public char charAt(int off) {
            char[] buf = TextUtils.obtain(1);
            getChars(off, off + 1, buf, 0);
            char ret = buf[0];

            TextUtils.recycle(buf);
            return ret;
        }

        public void getChars(int start, int end, char[] dest, int destoff) {
            int line1 = mLayout.getLineForOffset(start);
            int line2 = mLayout.getLineForOffset(end);

            TextUtils.getChars(mText, start, end, dest, destoff);

            for (int i = line1; i <= line2; i++) {
                mLayout.ellipsize(start, end, i, dest, destoff);
            }
        }

        public int length() {
            return mText.length();
        }
    
        public CharSequence subSequence(int start, int end) {
            char[] s = new char[end - start];
            getChars(start, end, s, 0);
            return new String(s);
        }

        public String toString() {
            char[] s = new char[length()];
            getChars(0, length(), s, 0);
            return new String(s);
        }

    }

    /* package */ static class SpannedEllipsizer
                    extends Ellipsizer implements Spanned {
        private Spanned mSpanned;

        public SpannedEllipsizer(CharSequence display) {
            super(display);
            mSpanned = (Spanned) display;
        }

        public <T> T[] getSpans(int start, int end, Class<T> type) {
            return mSpanned.getSpans(start, end, type);
        }

        public int getSpanStart(Object tag) {
            return mSpanned.getSpanStart(tag);
        }

        public int getSpanEnd(Object tag) {
            return mSpanned.getSpanEnd(tag);
        }

        public int getSpanFlags(Object tag) {
            return mSpanned.getSpanFlags(tag);
        }

        public int nextSpanTransition(int start, int limit, Class type) {
            return mSpanned.nextSpanTransition(start, limit, type);
        }

        public CharSequence subSequence(int start, int end) {
            char[] s = new char[end - start];
            getChars(start, end, s, 0);

            SpannableString ss = new SpannableString(new String(s));
            TextUtils.copySpansFrom(mSpanned, start, end, Object.class, ss, 0);
            return ss;
        }
    }

    private CharSequence mText;
    private TextPaint mPaint;
    /* package */ TextPaint mWorkPaint;
    private int mWidth;
    private Alignment mAlignment = Alignment.ALIGN_NORMAL;
    private float mSpacingMult;
    private float mSpacingAdd;
    private static Rect sTempRect = new Rect();
    private boolean mSpannedText;

    public static final int DIR_LEFT_TO_RIGHT = 1;
    public static final int DIR_RIGHT_TO_LEFT = -1;
    
    /* package */ static final int DIR_REQUEST_LTR = 1;
    /* package */ static final int DIR_REQUEST_RTL = -1;
    /* package */ static final int DIR_REQUEST_DEFAULT_LTR = 2;
    /* package */ static final int DIR_REQUEST_DEFAULT_RTL = -2;

    public enum Alignment {
        ALIGN_NORMAL,
        ALIGN_OPPOSITE,
        ALIGN_CENTER,
        // XXX ALIGN_LEFT,
        // XXX ALIGN_RIGHT,
    }

    private static final int TAB_INCREMENT = 20;

    /* package */ static final Directions DIRS_ALL_LEFT_TO_RIGHT =
                                       new Directions(new short[] { 32767 });
    /* package */ static final Directions DIRS_ALL_RIGHT_TO_LEFT =
                                       new Directions(new short[] { 0, 32767 });

}

