/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.graphics.RectF;
import android.text.Layout.Directions;
import android.text.Layout.TabStops;
import android.text.style.CharacterStyle;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;
import android.util.Log;

import com.android.internal.util.ArrayUtils;

/**
 * Represents a line of styled text, for measuring in visual order and
 * for rendering.
 *
 * <p>Get a new instance using obtain(), and when finished with it, return it
 * to the pool using recycle().
 *
 * <p>Call set to prepare the instance for use, then either draw, measure,
 * metrics, or caretToLeftRightOf.
 *
 * @hide
 */
class TextLine {
    private static final boolean DEBUG = false;

    private TextPaint mPaint;
    private CharSequence mText;
    private int mStart;
    private int mLen;
    private int mDir;
    private Directions mDirections;
    private boolean mHasTabs;
    private TabStops mTabs;
    private char[] mChars;
    private boolean mCharsValid;
    private Spanned mSpanned;
    private final TextPaint mWorkPaint = new TextPaint();
    private final SpanSet<MetricAffectingSpan> mMetricAffectingSpanSpanSet =
            new SpanSet<MetricAffectingSpan>(MetricAffectingSpan.class);
    private final SpanSet<CharacterStyle> mCharacterStyleSpanSet =
            new SpanSet<CharacterStyle>(CharacterStyle.class);
    private final SpanSet<ReplacementSpan> mReplacementSpanSpanSet =
            new SpanSet<ReplacementSpan>(ReplacementSpan.class);

    private static final TextLine[] sCached = new TextLine[3];

    /**
     * Returns a new TextLine from the shared pool.
     *
     * @return an uninitialized TextLine
     */
    static TextLine obtain() {
        TextLine tl;
        synchronized (sCached) {
            for (int i = sCached.length; --i >= 0;) {
                if (sCached[i] != null) {
                    tl = sCached[i];
                    sCached[i] = null;
                    return tl;
                }
            }
        }
        tl = new TextLine();
        if (DEBUG) {
            Log.v("TLINE", "new: " + tl);
        }
        return tl;
    }

    /**
     * Puts a TextLine back into the shared pool. Do not use this TextLine once
     * it has been returned.
     * @param tl the textLine
     * @return null, as a convenience from clearing references to the provided
     * TextLine
     */
    static TextLine recycle(TextLine tl) {
        tl.mText = null;
        tl.mPaint = null;
        tl.mDirections = null;
        tl.mSpanned = null;
        tl.mTabs = null;
        tl.mChars = null;

        tl.mMetricAffectingSpanSpanSet.recycle();
        tl.mCharacterStyleSpanSet.recycle();
        tl.mReplacementSpanSpanSet.recycle();

        synchronized(sCached) {
            for (int i = 0; i < sCached.length; ++i) {
                if (sCached[i] == null) {
                    sCached[i] = tl;
                    break;
                }
            }
        }
        return null;
    }

    /**
     * Initializes a TextLine and prepares it for use.
     *
     * @param paint the base paint for the line
     * @param text the text, can be Styled
     * @param start the start of the line relative to the text
     * @param limit the limit of the line relative to the text
     * @param dir the paragraph direction of this line
     * @param directions the directions information of this line
     * @param hasTabs true if the line might contain tabs
     * @param tabStops the tabStops. Can be null.
     */
    void set(TextPaint paint, CharSequence text, int start, int limit, int dir,
            Directions directions, boolean hasTabs, TabStops tabStops) {
        mPaint = paint;
        mText = text;
        mStart = start;
        mLen = limit - start;
        mDir = dir;
        mDirections = directions;
        if (mDirections == null) {
            throw new IllegalArgumentException("Directions cannot be null");
        }
        mHasTabs = hasTabs;
        mSpanned = null;

        boolean hasReplacement = false;
        if (text instanceof Spanned) {
            mSpanned = (Spanned) text;
            mReplacementSpanSpanSet.init(mSpanned, start, limit);
            hasReplacement = mReplacementSpanSpanSet.numberOfSpans > 0;
        }

        mCharsValid = hasReplacement || hasTabs || directions != Layout.DIRS_ALL_LEFT_TO_RIGHT;

        if (mCharsValid) {
            if (mChars == null || mChars.length < mLen) {
                mChars = ArrayUtils.newUnpaddedCharArray(mLen);
            }
            TextUtils.getChars(text, start, limit, mChars, 0);
            if (hasReplacement) {
                // Handle these all at once so we don't have to do it as we go.
                // Replace the first character of each replacement run with the
                // object-replacement character and the remainder with zero width
                // non-break space aka BOM.  Cursor movement code skips these
                // zero-width characters.
                char[] chars = mChars;
                for (int i = start, inext; i < limit; i = inext) {
                    inext = mReplacementSpanSpanSet.getNextTransition(i, limit);
                    if (mReplacementSpanSpanSet.hasSpansIntersecting(i, inext)) {
                        // transition into a span
                        chars[i - start] = '\ufffc';
                        for (int j = i - start + 1, e = inext - start; j < e; ++j) {
                            chars[j] = '\ufeff'; // used as ZWNBS, marks positions to skip
                        }
                    }
                }
            }
        }
        mTabs = tabStops;
    }

    /**
     * Renders the TextLine.
     *
     * @param c the canvas to render on
     * @param x the leading margin position
     * @param top the top of the line
     * @param y the baseline
     * @param bottom the bottom of the line
     */
    void draw(Canvas c, float x, int top, int y, int bottom) {
        if (!mHasTabs) {
            if (mDirections == Layout.DIRS_ALL_LEFT_TO_RIGHT) {
                drawRun(c, 0, mLen, false, x, top, y, bottom, false);
                return;
            }
            if (mDirections == Layout.DIRS_ALL_RIGHT_TO_LEFT) {
                drawRun(c, 0, mLen, true, x, top, y, bottom, false);
                return;
            }
        }

        float h = 0;
        int[] runs = mDirections.mDirections;

        int lastRunIndex = runs.length - 2;
        for (int i = 0; i < runs.length; i += 2) {
            int runStart = runs[i];
            int runLimit = runStart + (runs[i+1] & Layout.RUN_LENGTH_MASK);
            if (runLimit > mLen) {
                runLimit = mLen;
            }
            boolean runIsRtl = (runs[i+1] & Layout.RUN_RTL_FLAG) != 0;

            int segstart = runStart;
            for (int j = mHasTabs ? runStart : runLimit; j <= runLimit; j++) {
                int codept = 0;
                if (mHasTabs && j < runLimit) {
                    codept = mChars[j];
                    if (codept >= 0xD800 && codept < 0xDC00 && j + 1 < runLimit) {
                        codept = Character.codePointAt(mChars, j);
                        if (codept > 0xFFFF) {
                            ++j;
                            continue;
                        }
                    }
                }

                if (j == runLimit || codept == '\t') {
                    h += drawRun(c, segstart, j, runIsRtl, x+h, top, y, bottom,
                            i != lastRunIndex || j != mLen);

                    if (codept == '\t') {
                        h = mDir * nextTab(h * mDir);
                    }
                    segstart = j + 1;
                }
            }
        }
    }

    /**
     * Returns metrics information for the entire line.
     *
     * @param fmi receives font metrics information, can be null
     * @return the signed width of the line
     */
    float metrics(FontMetricsInt fmi) {
        return measure(mLen, false, fmi);
    }

    /**
     * Returns information about a position on the line.
     *
     * @param offset the line-relative character offset, between 0 and the
     * line length, inclusive
     * @param trailing true to measure the trailing edge of the character
     * before offset, false to measure the leading edge of the character
     * at offset.
     * @param fmi receives metrics information about the requested
     * character, can be null.
     * @return the signed offset from the leading margin to the requested
     * character edge.
     */
    float measure(int offset, boolean trailing, FontMetricsInt fmi) {
        int target = trailing ? offset - 1 : offset;
        if (target < 0) {
            return 0;
        }

        float h = 0;

        if (!mHasTabs) {
            if (mDirections == Layout.DIRS_ALL_LEFT_TO_RIGHT) {
                return measureRun(0, offset, mLen, false, fmi);
            }
            if (mDirections == Layout.DIRS_ALL_RIGHT_TO_LEFT) {
                return measureRun(0, offset, mLen, true, fmi);
            }
        }

        char[] chars = mChars;
        int[] runs = mDirections.mDirections;
        for (int i = 0; i < runs.length; i += 2) {
            int runStart = runs[i];
            int runLimit = runStart + (runs[i+1] & Layout.RUN_LENGTH_MASK);
            if (runLimit > mLen) {
                runLimit = mLen;
            }
            boolean runIsRtl = (runs[i+1] & Layout.RUN_RTL_FLAG) != 0;

            int segstart = runStart;
            for (int j = mHasTabs ? runStart : runLimit; j <= runLimit; j++) {
                int codept = 0;
                if (mHasTabs && j < runLimit) {
                    codept = chars[j];
                    if (codept >= 0xD800 && codept < 0xDC00 && j + 1 < runLimit) {
                        codept = Character.codePointAt(chars, j);
                        if (codept > 0xFFFF) {
                            ++j;
                            continue;
                        }
                    }
                }

                if (j == runLimit || codept == '\t') {
                    boolean inSegment = target >= segstart && target < j;

                    boolean advance = (mDir == Layout.DIR_RIGHT_TO_LEFT) == runIsRtl;
                    if (inSegment && advance) {
                        return h += measureRun(segstart, offset, j, runIsRtl, fmi);
                    }

                    float w = measureRun(segstart, j, j, runIsRtl, fmi);
                    h += advance ? w : -w;

                    if (inSegment) {
                        return h += measureRun(segstart, offset, j, runIsRtl, null);
                    }

                    if (codept == '\t') {
                        if (offset == j) {
                            return h;
                        }
                        h = mDir * nextTab(h * mDir);
                        if (target == j) {
                            return h;
                        }
                    }

                    segstart = j + 1;
                }
            }
        }

        return h;
    }

    /**
     * Draws a unidirectional (but possibly multi-styled) run of text.
     *
     *
     * @param c the canvas to draw on
     * @param start the line-relative start
     * @param limit the line-relative limit
     * @param runIsRtl true if the run is right-to-left
     * @param x the position of the run that is closest to the leading margin
     * @param top the top of the line
     * @param y the baseline
     * @param bottom the bottom of the line
     * @param needWidth true if the width value is required.
     * @return the signed width of the run, based on the paragraph direction.
     * Only valid if needWidth is true.
     */
    private float drawRun(Canvas c, int start,
            int limit, boolean runIsRtl, float x, int top, int y, int bottom,
            boolean needWidth) {

        if ((mDir == Layout.DIR_LEFT_TO_RIGHT) == runIsRtl) {
            float w = -measureRun(start, limit, limit, runIsRtl, null);
            handleRun(start, limit, limit, runIsRtl, c, x + w, top,
                    y, bottom, null, false);
            return w;
        }

        return handleRun(start, limit, limit, runIsRtl, c, x, top,
                y, bottom, null, needWidth);
    }

    /**
     * Measures a unidirectional (but possibly multi-styled) run of text.
     *
     *
     * @param start the line-relative start of the run
     * @param offset the offset to measure to, between start and limit inclusive
     * @param limit the line-relative limit of the run
     * @param runIsRtl true if the run is right-to-left
     * @param fmi receives metrics information about the requested
     * run, can be null.
     * @return the signed width from the start of the run to the leading edge
     * of the character at offset, based on the run (not paragraph) direction
     */
    private float measureRun(int start, int offset, int limit, boolean runIsRtl,
            FontMetricsInt fmi) {
        return handleRun(start, offset, limit, runIsRtl, null, 0, 0, 0, 0, fmi, true);
    }

    /**
     * Walk the cursor through this line, skipping conjuncts and
     * zero-width characters.
     *
     * <p>This function cannot properly walk the cursor off the ends of the line
     * since it does not know about any shaping on the previous/following line
     * that might affect the cursor position. Callers must either avoid these
     * situations or handle the result specially.
     *
     * @param cursor the starting position of the cursor, between 0 and the
     * length of the line, inclusive
     * @param toLeft true if the caret is moving to the left.
     * @return the new offset.  If it is less than 0 or greater than the length
     * of the line, the previous/following line should be examined to get the
     * actual offset.
     */
    int getOffsetToLeftRightOf(int cursor, boolean toLeft) {
        // 1) The caret marks the leading edge of a character. The character
        // logically before it might be on a different level, and the active caret
        // position is on the character at the lower level. If that character
        // was the previous character, the caret is on its trailing edge.
        // 2) Take this character/edge and move it in the indicated direction.
        // This gives you a new character and a new edge.
        // 3) This position is between two visually adjacent characters.  One of
        // these might be at a lower level.  The active position is on the
        // character at the lower level.
        // 4) If the active position is on the trailing edge of the character,
        // the new caret position is the following logical character, else it
        // is the character.

        int lineStart = 0;
        int lineEnd = mLen;
        boolean paraIsRtl = mDir == -1;
        int[] runs = mDirections.mDirections;

        int runIndex, runLevel = 0, runStart = lineStart, runLimit = lineEnd, newCaret = -1;
        boolean trailing = false;

        if (cursor == lineStart) {
            runIndex = -2;
        } else if (cursor == lineEnd) {
            runIndex = runs.length;
        } else {
          // First, get information about the run containing the character with
          // the active caret.
          for (runIndex = 0; runIndex < runs.length; runIndex += 2) {
            runStart = lineStart + runs[runIndex];
            if (cursor >= runStart) {
              runLimit = runStart + (runs[runIndex+1] & Layout.RUN_LENGTH_MASK);
              if (runLimit > lineEnd) {
                  runLimit = lineEnd;
              }
              if (cursor < runLimit) {
                runLevel = (runs[runIndex+1] >>> Layout.RUN_LEVEL_SHIFT) &
                    Layout.RUN_LEVEL_MASK;
                if (cursor == runStart) {
                  // The caret is on a run boundary, see if we should
                  // use the position on the trailing edge of the previous
                  // logical character instead.
                  int prevRunIndex, prevRunLevel, prevRunStart, prevRunLimit;
                  int pos = cursor - 1;
                  for (prevRunIndex = 0; prevRunIndex < runs.length; prevRunIndex += 2) {
                    prevRunStart = lineStart + runs[prevRunIndex];
                    if (pos >= prevRunStart) {
                      prevRunLimit = prevRunStart +
                          (runs[prevRunIndex+1] & Layout.RUN_LENGTH_MASK);
                      if (prevRunLimit > lineEnd) {
                          prevRunLimit = lineEnd;
                      }
                      if (pos < prevRunLimit) {
                        prevRunLevel = (runs[prevRunIndex+1] >>> Layout.RUN_LEVEL_SHIFT)
                            & Layout.RUN_LEVEL_MASK;
                        if (prevRunLevel < runLevel) {
                          // Start from logically previous character.
                          runIndex = prevRunIndex;
                          runLevel = prevRunLevel;
                          runStart = prevRunStart;
                          runLimit = prevRunLimit;
                          trailing = true;
                          break;
                        }
                      }
                    }
                  }
                }
                break;
              }
            }
          }

          // caret might be == lineEnd.  This is generally a space or paragraph
          // separator and has an associated run, but might be the end of
          // text, in which case it doesn't.  If that happens, we ran off the
          // end of the run list, and runIndex == runs.length.  In this case,
          // we are at a run boundary so we skip the below test.
          if (runIndex != runs.length) {
              boolean runIsRtl = (runLevel & 0x1) != 0;
              boolean advance = toLeft == runIsRtl;
              if (cursor != (advance ? runLimit : runStart) || advance != trailing) {
                  // Moving within or into the run, so we can move logically.
                  newCaret = getOffsetBeforeAfter(runIndex, runStart, runLimit,
                          runIsRtl, cursor, advance);
                  // If the new position is internal to the run, we're at the strong
                  // position already so we're finished.
                  if (newCaret != (advance ? runLimit : runStart)) {
                      return newCaret;
                  }
              }
          }
        }

        // If newCaret is -1, we're starting at a run boundary and crossing
        // into another run. Otherwise we've arrived at a run boundary, and
        // need to figure out which character to attach to.  Note we might
        // need to run this twice, if we cross a run boundary and end up at
        // another run boundary.
        while (true) {
          boolean advance = toLeft == paraIsRtl;
          int otherRunIndex = runIndex + (advance ? 2 : -2);
          if (otherRunIndex >= 0 && otherRunIndex < runs.length) {
            int otherRunStart = lineStart + runs[otherRunIndex];
            int otherRunLimit = otherRunStart +
            (runs[otherRunIndex+1] & Layout.RUN_LENGTH_MASK);
            if (otherRunLimit > lineEnd) {
                otherRunLimit = lineEnd;
            }
            int otherRunLevel = (runs[otherRunIndex+1] >>> Layout.RUN_LEVEL_SHIFT) &
                Layout.RUN_LEVEL_MASK;
            boolean otherRunIsRtl = (otherRunLevel & 1) != 0;

            advance = toLeft == otherRunIsRtl;
            if (newCaret == -1) {
                newCaret = getOffsetBeforeAfter(otherRunIndex, otherRunStart,
                        otherRunLimit, otherRunIsRtl,
                        advance ? otherRunStart : otherRunLimit, advance);
                if (newCaret == (advance ? otherRunLimit : otherRunStart)) {
                    // Crossed and ended up at a new boundary,
                    // repeat a second and final time.
                    runIndex = otherRunIndex;
                    runLevel = otherRunLevel;
                    continue;
                }
                break;
            }

            // The new caret is at a boundary.
            if (otherRunLevel < runLevel) {
              // The strong character is in the other run.
              newCaret = advance ? otherRunStart : otherRunLimit;
            }
            break;
          }

          if (newCaret == -1) {
              // We're walking off the end of the line.  The paragraph
              // level is always equal to or lower than any internal level, so
              // the boundaries get the strong caret.
              newCaret = advance ? mLen + 1 : -1;
              break;
          }

          // Else we've arrived at the end of the line.  That's a strong position.
          // We might have arrived here by crossing over a run with no internal
          // breaks and dropping out of the above loop before advancing one final
          // time, so reset the caret.
          // Note, we use '<=' below to handle a situation where the only run
          // on the line is a counter-directional run.  If we're not advancing,
          // we can end up at the 'lineEnd' position but the caret we want is at
          // the lineStart.
          if (newCaret <= lineEnd) {
              newCaret = advance ? lineEnd : lineStart;
          }
          break;
        }

        return newCaret;
    }

    /**
     * Returns the next valid offset within this directional run, skipping
     * conjuncts and zero-width characters.  This should not be called to walk
     * off the end of the line, since the returned values might not be valid
     * on neighboring lines.  If the returned offset is less than zero or
     * greater than the line length, the offset should be recomputed on the
     * preceding or following line, respectively.
     *
     * @param runIndex the run index
     * @param runStart the start of the run
     * @param runLimit the limit of the run
     * @param runIsRtl true if the run is right-to-left
     * @param offset the offset
     * @param after true if the new offset should logically follow the provided
     * offset
     * @return the new offset
     */
    private int getOffsetBeforeAfter(int runIndex, int runStart, int runLimit,
            boolean runIsRtl, int offset, boolean after) {

        if (runIndex < 0 || offset == (after ? mLen : 0)) {
            // Walking off end of line.  Since we don't know
            // what cursor positions are available on other lines, we can't
            // return accurate values.  These are a guess.
            if (after) {
                return TextUtils.getOffsetAfter(mText, offset + mStart) - mStart;
            }
            return TextUtils.getOffsetBefore(mText, offset + mStart) - mStart;
        }

        TextPaint wp = mWorkPaint;
        wp.set(mPaint);

        int spanStart = runStart;
        int spanLimit;
        if (mSpanned == null) {
            spanLimit = runLimit;
        } else {
            int target = after ? offset + 1 : offset;
            int limit = mStart + runLimit;
            while (true) {
                spanLimit = mSpanned.nextSpanTransition(mStart + spanStart, limit,
                        MetricAffectingSpan.class) - mStart;
                if (spanLimit >= target) {
                    break;
                }
                spanStart = spanLimit;
            }

            MetricAffectingSpan[] spans = mSpanned.getSpans(mStart + spanStart,
                    mStart + spanLimit, MetricAffectingSpan.class);
            spans = TextUtils.removeEmptySpans(spans, mSpanned, MetricAffectingSpan.class);

            if (spans.length > 0) {
                ReplacementSpan replacement = null;
                for (int j = 0; j < spans.length; j++) {
                    MetricAffectingSpan span = spans[j];
                    if (span instanceof ReplacementSpan) {
                        replacement = (ReplacementSpan)span;
                    } else {
                        span.updateMeasureState(wp);
                    }
                }

                if (replacement != null) {
                    // If we have a replacement span, we're moving either to
                    // the start or end of this span.
                    return after ? spanLimit : spanStart;
                }
            }
        }

        int dir = runIsRtl ? Paint.DIRECTION_RTL : Paint.DIRECTION_LTR;
        int cursorOpt = after ? Paint.CURSOR_AFTER : Paint.CURSOR_BEFORE;
        if (mCharsValid) {
            return wp.getTextRunCursor(mChars, spanStart, spanLimit - spanStart,
                    dir, offset, cursorOpt);
        } else {
            return wp.getTextRunCursor(mText, mStart + spanStart,
                    mStart + spanLimit, dir, mStart + offset, cursorOpt) - mStart;
        }
    }

    /**
     * @param wp
     */
    private static void expandMetricsFromPaint(FontMetricsInt fmi, TextPaint wp) {
        final int previousTop     = fmi.top;
        final int previousAscent  = fmi.ascent;
        final int previousDescent = fmi.descent;
        final int previousBottom  = fmi.bottom;
        final int previousLeading = fmi.leading;

        wp.getFontMetricsInt(fmi);

        updateMetrics(fmi, previousTop, previousAscent, previousDescent, previousBottom,
                previousLeading);
    }

    static void updateMetrics(FontMetricsInt fmi, int previousTop, int previousAscent,
            int previousDescent, int previousBottom, int previousLeading) {
        fmi.top     = Math.min(fmi.top,     previousTop);
        fmi.ascent  = Math.min(fmi.ascent,  previousAscent);
        fmi.descent = Math.max(fmi.descent, previousDescent);
        fmi.bottom  = Math.max(fmi.bottom,  previousBottom);
        fmi.leading = Math.max(fmi.leading, previousLeading);
    }

    /**
     * Utility function for measuring and rendering text.  The text must
     * not include a tab.
     *
     * @param wp the working paint
     * @param start the start of the text
     * @param end the end of the text
     * @param runIsRtl true if the run is right-to-left
     * @param c the canvas, can be null if rendering is not needed
     * @param x the edge of the run closest to the leading margin
     * @param top the top of the line
     * @param y the baseline
     * @param bottom the bottom of the line
     * @param fmi receives metrics information, can be null
     * @param needWidth true if the width of the run is needed
     * @param offset the offset for the purpose of measuring
     * @return the signed width of the run based on the run direction; only
     * valid if needWidth is true
     */
    private float handleText(TextPaint wp, int start, int end,
            int contextStart, int contextEnd, boolean runIsRtl,
            Canvas c, float x, int top, int y, int bottom,
            FontMetricsInt fmi, boolean needWidth, int offset) {

        // Get metrics first (even for empty strings or "0" width runs)
        if (fmi != null) {
            expandMetricsFromPaint(fmi, wp);
        }

        int runLen = end - start;
        // No need to do anything if the run width is "0"
        if (runLen == 0) {
            return 0f;
        }

        float ret = 0;

        if (needWidth || (c != null && (wp.bgColor != 0 || wp.underlineColor != 0 || runIsRtl))) {
            if (mCharsValid) {
                ret = wp.getRunAdvance(mChars, start, end, contextStart, contextEnd,
                        runIsRtl, offset);
            } else {
                int delta = mStart;
                ret = wp.getRunAdvance(mText, delta + start, delta + end,
                        delta + contextStart, delta + contextEnd, runIsRtl, delta + offset);
            }
        }

        if (c != null) {
            if (runIsRtl) {
                x -= ret;
            }

            if (wp.bgColor != 0) {
                int previousColor = wp.getColor();
                Paint.Style previousStyle = wp.getStyle();

                wp.setColor(wp.bgColor);
                wp.setStyle(Paint.Style.FILL);
                c.drawRect(x, top, x + ret, bottom, wp);

                wp.setStyle(previousStyle);
                wp.setColor(previousColor);
            }

            if (wp.underlineColor != 0) {
                // kStdUnderline_Offset = 1/9, defined in SkTextFormatParams.h
                float underlineTop = y + wp.baselineShift + (1.0f / 9.0f) * wp.getTextSize();

                int previousColor = wp.getColor();
                Paint.Style previousStyle = wp.getStyle();
                boolean previousAntiAlias = wp.isAntiAlias();

                wp.setStyle(Paint.Style.FILL);
                wp.setAntiAlias(true);

                wp.setColor(wp.underlineColor);
                c.drawRect(x, underlineTop, x + ret, underlineTop + wp.underlineThickness, wp);

                wp.setStyle(previousStyle);
                wp.setColor(previousColor);
                wp.setAntiAlias(previousAntiAlias);
            }

            drawTextRun(c, wp, start, end, contextStart, contextEnd, runIsRtl,
                    x, y + wp.baselineShift);
        }

        return runIsRtl ? -ret : ret;
    }

    /**
     * Utility function for measuring and rendering a replacement.
     *
     *
     * @param replacement the replacement
     * @param wp the work paint
     * @param start the start of the run
     * @param limit the limit of the run
     * @param runIsRtl true if the run is right-to-left
     * @param c the canvas, can be null if not rendering
     * @param x the edge of the replacement closest to the leading margin
     * @param top the top of the line
     * @param y the baseline
     * @param bottom the bottom of the line
     * @param fmi receives metrics information, can be null
     * @param needWidth true if the width of the replacement is needed
     * @return the signed width of the run based on the run direction; only
     * valid if needWidth is true
     */
    private float handleReplacement(ReplacementSpan replacement, TextPaint wp,
            int start, int limit, boolean runIsRtl, Canvas c,
            float x, int top, int y, int bottom, FontMetricsInt fmi,
            boolean needWidth) {

        float ret = 0;

        int textStart = mStart + start;
        int textLimit = mStart + limit;

        if (needWidth || (c != null && runIsRtl)) {
            int previousTop = 0;
            int previousAscent = 0;
            int previousDescent = 0;
            int previousBottom = 0;
            int previousLeading = 0;

            boolean needUpdateMetrics = (fmi != null);

            if (needUpdateMetrics) {
                previousTop     = fmi.top;
                previousAscent  = fmi.ascent;
                previousDescent = fmi.descent;
                previousBottom  = fmi.bottom;
                previousLeading = fmi.leading;
            }

            ret = replacement.getSize(wp, mText, textStart, textLimit, fmi);

            if (needUpdateMetrics) {
                updateMetrics(fmi, previousTop, previousAscent, previousDescent, previousBottom,
                        previousLeading);
            }
        }

        if (c != null) {
            if (runIsRtl) {
                x -= ret;
            }
            replacement.draw(c, mText, textStart, textLimit,
                    x, top, y, bottom, wp);
        }

        return runIsRtl ? -ret : ret;
    }

    /**
     * Utility function for handling a unidirectional run.  The run must not
     * contain tabs but can contain styles.
     *
     *
     * @param start the line-relative start of the run
     * @param measureLimit the offset to measure to, between start and limit inclusive
     * @param limit the limit of the run
     * @param runIsRtl true if the run is right-to-left
     * @param c the canvas, can be null
     * @param x the end of the run closest to the leading margin
     * @param top the top of the line
     * @param y the baseline
     * @param bottom the bottom of the line
     * @param fmi receives metrics information, can be null
     * @param needWidth true if the width is required
     * @return the signed width of the run based on the run direction; only
     * valid if needWidth is true
     */
    private float handleRun(int start, int measureLimit,
            int limit, boolean runIsRtl, Canvas c, float x, int top, int y,
            int bottom, FontMetricsInt fmi, boolean needWidth) {

        // Case of an empty line, make sure we update fmi according to mPaint
        if (start == measureLimit) {
            TextPaint wp = mWorkPaint;
            wp.set(mPaint);
            if (fmi != null) {
                expandMetricsFromPaint(fmi, wp);
            }
            return 0f;
        }

        if (mSpanned == null) {
            TextPaint wp = mWorkPaint;
            wp.set(mPaint);
            final int mlimit = measureLimit;
            return handleText(wp, start, limit, start, limit, runIsRtl, c, x, top,
                    y, bottom, fmi, needWidth || mlimit < measureLimit, mlimit);
        }

        mMetricAffectingSpanSpanSet.init(mSpanned, mStart + start, mStart + limit);
        mCharacterStyleSpanSet.init(mSpanned, mStart + start, mStart + limit);

        // Shaping needs to take into account context up to metric boundaries,
        // but rendering needs to take into account character style boundaries.
        // So we iterate through metric runs to get metric bounds,
        // then within each metric run iterate through character style runs
        // for the run bounds.
        final float originalX = x;
        for (int i = start, inext; i < measureLimit; i = inext) {
            TextPaint wp = mWorkPaint;
            wp.set(mPaint);

            inext = mMetricAffectingSpanSpanSet.getNextTransition(mStart + i, mStart + limit) -
                    mStart;
            int mlimit = Math.min(inext, measureLimit);

            ReplacementSpan replacement = null;

            for (int j = 0; j < mMetricAffectingSpanSpanSet.numberOfSpans; j++) {
                // Both intervals [spanStarts..spanEnds] and [mStart + i..mStart + mlimit] are NOT
                // empty by construction. This special case in getSpans() explains the >= & <= tests
                if ((mMetricAffectingSpanSpanSet.spanStarts[j] >= mStart + mlimit) ||
                        (mMetricAffectingSpanSpanSet.spanEnds[j] <= mStart + i)) continue;
                MetricAffectingSpan span = mMetricAffectingSpanSpanSet.spans[j];
                if (span instanceof ReplacementSpan) {
                    replacement = (ReplacementSpan)span;
                } else {
                    // We might have a replacement that uses the draw
                    // state, otherwise measure state would suffice.
                    span.updateDrawState(wp);
                }
            }

            if (replacement != null) {
                x += handleReplacement(replacement, wp, i, mlimit, runIsRtl, c, x, top, y,
                        bottom, fmi, needWidth || mlimit < measureLimit);
                continue;
            }

            for (int j = i, jnext; j < mlimit; j = jnext) {
                jnext = mCharacterStyleSpanSet.getNextTransition(mStart + j, mStart + inext) -
                        mStart;
                int offset = Math.min(jnext, mlimit);

                wp.set(mPaint);
                for (int k = 0; k < mCharacterStyleSpanSet.numberOfSpans; k++) {
                    // Intentionally using >= and <= as explained above
                    if ((mCharacterStyleSpanSet.spanStarts[k] >= mStart + offset) ||
                            (mCharacterStyleSpanSet.spanEnds[k] <= mStart + j)) continue;

                    CharacterStyle span = mCharacterStyleSpanSet.spans[k];
                    span.updateDrawState(wp);
                }

                // Only draw hyphen on last run in line
                if (jnext < mLen) {
                    wp.setHyphenEdit(0);
                }
                x += handleText(wp, j, jnext, i, inext, runIsRtl, c, x,
                        top, y, bottom, fmi, needWidth || jnext < measureLimit, offset);
            }
        }

        return x - originalX;
    }

    /**
     * Render a text run with the set-up paint.
     *
     * @param c the canvas
     * @param wp the paint used to render the text
     * @param start the start of the run
     * @param end the end of the run
     * @param contextStart the start of context for the run
     * @param contextEnd the end of the context for the run
     * @param runIsRtl true if the run is right-to-left
     * @param x the x position of the left edge of the run
     * @param y the baseline of the run
     */
    private void drawTextRun(Canvas c, TextPaint wp, int start, int end,
            int contextStart, int contextEnd, boolean runIsRtl, float x, int y) {

        if (mCharsValid) {
            int count = end - start;
            int contextCount = contextEnd - contextStart;
            c.drawTextRun(mChars, start, count, contextStart, contextCount,
                    x, y, runIsRtl, wp);
        } else {
            int delta = mStart;
            c.drawTextRun(mText, delta + start, delta + end,
                    delta + contextStart, delta + contextEnd, x, y, runIsRtl, wp);
        }
    }

    /**
     * Returns the next tab position.
     *
     * @param h the (unsigned) offset from the leading margin
     * @return the (unsigned) tab position after this offset
     */
    float nextTab(float h) {
        if (mTabs != null) {
            return mTabs.nextTab(h);
        }
        return TabStops.nextDefaultStop(h, TAB_INCREMENT);
    }

    private static final int TAB_INCREMENT = 20;
}
