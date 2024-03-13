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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import android.os.Build;
import android.os.Trace;
import android.text.Layout.Directions;
import android.text.Layout.TabStops;
import android.text.style.CharacterStyle;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;

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
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class TextLine {
    private static final boolean DEBUG = false;

    private static final boolean TRACE_TEXTLINE = Build.isDebuggable();

    private static final char TAB_CHAR = '\t';

    private TextPaint mPaint;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private CharSequence mText;
    private int mStart;
    private int mLen;
    private int mDir;
    private Directions mDirections;
    private boolean mHasTabs;
    private TabStops mTabs;
    private char[] mChars;
    private boolean mCharsValid;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private Spanned mSpanned;
    private PrecomputedText mComputed;
    private RectF mTmpRectForMeasure;
    private RectF mTmpRectForPaintAPI;
    private Rect mTmpRectForPrecompute;

    // Recycling object for Paint APIs. Do not use outside getRunAdvances method.
    private Paint.RunInfo mRunInfo;

    public static final class LineInfo {
        private int mClusterCount;

        public int getClusterCount() {
            return mClusterCount;
        }

        public void setClusterCount(int clusterCount) {
            mClusterCount = clusterCount;
        }
    };

    private boolean mUseFallbackExtent = false;

    // The start and end of a potentially existing ellipsis on this text line.
    // We use them to filter out replacement and metric affecting spans on ellipsized away chars.
    private int mEllipsisStart;
    private int mEllipsisEnd;

    // Additional width of whitespace for justification. This value is per whitespace, thus
    // the line width will increase by mAddedWidthForJustify x (number of stretchable whitespaces).
    private float mAddedWordSpacingInPx;
    private float mAddedLetterSpacingInPx;
    private boolean mIsJustifying;

    @VisibleForTesting
    public float getAddedWordSpacingInPx() {
        return mAddedWordSpacingInPx;
    }

    @VisibleForTesting
    public float getAddedLetterSpacingInPx() {
        return mAddedLetterSpacingInPx;
    }

    @VisibleForTesting
    public boolean isJustifying() {
        return mIsJustifying;
    }

    private final TextPaint mWorkPaint = new TextPaint();
    private final TextPaint mActivePaint = new TextPaint();
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private final SpanSet<MetricAffectingSpan> mMetricAffectingSpanSpanSet =
            new SpanSet<MetricAffectingSpan>(MetricAffectingSpan.class);
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private final SpanSet<CharacterStyle> mCharacterStyleSpanSet =
            new SpanSet<CharacterStyle>(CharacterStyle.class);
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private final SpanSet<ReplacementSpan> mReplacementSpanSpanSet =
            new SpanSet<ReplacementSpan>(ReplacementSpan.class);

    private final DecorationInfo mDecorationInfo = new DecorationInfo();
    private final ArrayList<DecorationInfo> mDecorations = new ArrayList<>();

    /** Not allowed to access. If it's for memory leak workaround, it was already fixed M. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private static final TextLine[] sCached = new TextLine[3];

    /**
     * Returns a new TextLine from the shared pool.
     *
     * @return an uninitialized TextLine
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @UnsupportedAppUsage
    public static TextLine obtain() {
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
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public static TextLine recycle(TextLine tl) {
        tl.mText = null;
        tl.mPaint = null;
        tl.mDirections = null;
        tl.mSpanned = null;
        tl.mTabs = null;
        tl.mChars = null;
        tl.mComputed = null;
        tl.mUseFallbackExtent = false;

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
     * @param tabStops the tabStops. Can be null
     * @param ellipsisStart the start of the ellipsis relative to the line
     * @param ellipsisEnd the end of the ellipsis relative to the line. When there
     *                    is no ellipsis, this should be equal to ellipsisStart.
     * @param useFallbackLineSpacing true for enabling fallback line spacing. false for disabling
     *                              fallback line spacing.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void set(TextPaint paint, CharSequence text, int start, int limit, int dir,
            Directions directions, boolean hasTabs, TabStops tabStops,
            int ellipsisStart, int ellipsisEnd, boolean useFallbackLineSpacing) {
        mPaint = paint;
        mText = text;
        mStart = start;
        mLen = limit - start;
        mDir = dir;
        mDirections = directions;
        mUseFallbackExtent = useFallbackLineSpacing;
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

        mComputed = null;
        if (text instanceof PrecomputedText) {
            // Here, no need to check line break strategy or hyphenation frequency since there is no
            // line break concept here.
            mComputed = (PrecomputedText) text;
            if (!mComputed.getParams().getTextPaint().equalsForTextMeasurement(paint)) {
                mComputed = null;
            }
        }

        mCharsValid = hasReplacement;

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
                    if (mReplacementSpanSpanSet.hasSpansIntersecting(i, inext)
                            && (i - start >= ellipsisEnd || inext - start <= ellipsisStart)) {
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
        mAddedWordSpacingInPx = 0;
        mIsJustifying = false;

        mEllipsisStart = ellipsisStart != ellipsisEnd ? ellipsisStart : 0;
        mEllipsisEnd = ellipsisStart != ellipsisEnd ? ellipsisEnd : 0;
    }

    private char charAt(int i) {
        return mCharsValid ? mChars[i] : mText.charAt(i + mStart);
    }

    /**
     * Justify the line to the given width.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void justify(@Layout.JustificationMode int justificationMode, float justifyWidth) {
        int end = mLen;
        while (end > 0 && isLineEndSpace(mText.charAt(mStart + end - 1))) {
            end--;
        }
        if (justificationMode == Layout.JUSTIFICATION_MODE_INTER_WORD) {
            float width = Math.abs(measure(end, false, null, null, null));
            final int spaces = countStretchableSpaces(0, end);
            if (spaces == 0) {
                // There are no stretchable spaces, so we can't help the justification by adding any
                // width.
                return;
            }
            mAddedWordSpacingInPx = (justifyWidth - width) / spaces;
            mAddedLetterSpacingInPx = 0;
        } else {  // justificationMode == Layout.JUSTIFICATION_MODE_LETTER_SPACING
            LineInfo lineInfo = new LineInfo();
            float width = Math.abs(measure(end, false, null, null, lineInfo));

            int lettersCount = lineInfo.getClusterCount();
            if (lettersCount < 2) {
                return;
            }
            mAddedLetterSpacingInPx = (justifyWidth - width) / (lettersCount - 1);
            if (mAddedLetterSpacingInPx > 0.03) {
                // If the letter spacing is more than 0.03em, the ligatures are automatically
                // disabled, so re-calculate everything without ligatures.
                final String oldFontFeatures = mPaint.getFontFeatureSettings();
                mPaint.setFontFeatureSettings(oldFontFeatures + ", \"liga\" off, \"cliga\" off");
                width = Math.abs(measure(end, false, null, null, lineInfo));
                lettersCount = lineInfo.getClusterCount();
                mAddedLetterSpacingInPx = (justifyWidth - width) / (lettersCount - 1);
                mPaint.setFontFeatureSettings(oldFontFeatures);
            }
            mAddedWordSpacingInPx = 0;
        }
        mIsJustifying = true;
    }

    /**
     * Returns the run flag of at the given BiDi run.
     *
     * @param bidiRunIndex a BiDi run index.
     * @return a run flag of the given BiDi run.
     */
    @VisibleForTesting
    public static int calculateRunFlag(int bidiRunIndex, int bidiRunCount, int lineDirection) {
        if (bidiRunCount == 1) {
            // Easy case. If there is only single run, it is most left and most right run.
            return Paint.TEXT_RUN_FLAG_LEFT_EDGE | Paint.TEXT_RUN_FLAG_RIGHT_EDGE;
        }
        if (bidiRunIndex != 0 && bidiRunIndex != (bidiRunCount - 1)) {
            // Easy case. If the given run is the middle of the line, it is not the most left or
            // the most right run.
            return 0;
        }

        int runFlag = 0;
        // For the historical reasons, the BiDi implementation of Android works differently
        // from the Java BiDi APIs. The mDirections holds the BiDi runs in visual order, but
        // it is reversed order if the paragraph direction is RTL. So, the first BiDi run of
        // mDirections is located the most left of the line if the paragraph direction is LTR.
        // If the paragraph direction is RTL, the first BiDi run is located the most right of
        // the line.
        if (bidiRunIndex == 0) {
            if (lineDirection == Layout.DIR_LEFT_TO_RIGHT) {
                runFlag |= Paint.TEXT_RUN_FLAG_LEFT_EDGE;
            } else {
                runFlag |= Paint.TEXT_RUN_FLAG_RIGHT_EDGE;
            }
        }
        if (bidiRunIndex == (bidiRunCount - 1)) {
            if (lineDirection == Layout.DIR_LEFT_TO_RIGHT) {
                runFlag |= Paint.TEXT_RUN_FLAG_RIGHT_EDGE;
            } else {
                runFlag |= Paint.TEXT_RUN_FLAG_LEFT_EDGE;
            }
        }
        return runFlag;
    }

    /**
     * Resolve the runFlag for the inline span range.
     *
     * @param runFlag the runFlag of the current BiDi run.
     * @param isRtlRun true for RTL run, false for LTR run.
     * @param runStart the inclusive BiDi run start offset.
     * @param runEnd the exclusive BiDi run end offset.
     * @param spanStart the inclusive span start offset.
     * @param spanEnd the exclusive span end offset.
     * @return the resolved runFlag.
     */
    @VisibleForTesting
    public static int resolveRunFlagForSubSequence(int runFlag, boolean isRtlRun, int runStart,
            int runEnd, int spanStart, int spanEnd) {
        if (runFlag == 0) {
            // Easy case. If the run is in the middle of the line, any inline span is also in the
            // middle of the line.
            return 0;
        }
        int localRunFlag = runFlag;
        if ((runFlag & Paint.TEXT_RUN_FLAG_LEFT_EDGE) != 0) {
            if (isRtlRun) {
                if (spanEnd != runEnd) {
                    // In the RTL context, the last run is the most left run.
                    localRunFlag &= ~Paint.TEXT_RUN_FLAG_LEFT_EDGE;
                }
            } else {  // LTR
                if (spanStart != runStart) {
                    // In the LTR context, the first run is the most left run.
                    localRunFlag &= ~Paint.TEXT_RUN_FLAG_LEFT_EDGE;
                }
            }
        }
        if ((runFlag & Paint.TEXT_RUN_FLAG_RIGHT_EDGE) != 0) {
            if (isRtlRun) {
                if (spanStart != runStart) {
                    // In the RTL context, the start of the run is the most right run.
                    localRunFlag &= ~Paint.TEXT_RUN_FLAG_RIGHT_EDGE;
                }
            } else {  // LTR
                if (spanEnd != runEnd) {
                    // In the LTR context, the last run is the most right position.
                    localRunFlag &= ~Paint.TEXT_RUN_FLAG_RIGHT_EDGE;
                }
            }
        }
        return localRunFlag;
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
        if (TRACE_TEXTLINE) {
            Trace.beginSection("TextLine#draw");
        }
        try {
            float h = 0;
            final int runCount = mDirections.getRunCount();
            for (int runIndex = 0; runIndex < runCount; runIndex++) {
                final int runStart = mDirections.getRunStart(runIndex);
                if (runStart > mLen) break;
                final int runLimit = Math.min(runStart + mDirections.getRunLength(runIndex), mLen);
                final boolean runIsRtl = mDirections.isRunRtl(runIndex);

                final int runFlag = calculateRunFlag(runIndex, runCount, mDir);

                int segStart = runStart;
                for (int j = mHasTabs ? runStart : runLimit; j <= runLimit; j++) {
                    if (j == runLimit || charAt(j) == TAB_CHAR) {
                        h += drawRun(c, segStart, j, runIsRtl, x + h, top, y, bottom,
                                runIndex != (runCount - 1) || j != mLen, runFlag);

                        if (j != runLimit) {  // charAt(j) == TAB_CHAR
                            h = mDir * nextTab(h * mDir);
                        }
                        segStart = j + 1;
                    }
                }
            }
        } finally {
            if (TRACE_TEXTLINE) {
                Trace.endSection();
            }
        }
    }

    /**
     * Returns metrics information for the entire line.
     *
     * @param fmi receives font metrics information, can be null
     * @param drawBounds output parameter for drawing bounding box. optional.
     * @param returnDrawWidth true for returning width of the bounding box, false for returning
     *                       total advances.
     * @param lineInfo an optional output parameter for filling line information.
     * @return the signed width of the line
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public float metrics(FontMetricsInt fmi, @Nullable RectF drawBounds, boolean returnDrawWidth,
            @Nullable LineInfo lineInfo) {
        if (returnDrawWidth) {
            if (drawBounds == null) {
                if (mTmpRectForMeasure == null) {
                    mTmpRectForMeasure = new RectF();
                }
                drawBounds = mTmpRectForMeasure;
            }
            drawBounds.setEmpty();
            float w = measure(mLen, false, fmi, drawBounds, lineInfo);
            float boundsWidth = drawBounds.width();
            if (Math.abs(w) > boundsWidth) {
                return w;
            } else {
                // bounds width is always positive but output of measure is signed width.
                // To be able to use bounds width as signed width, use the sign of the width.
                return Math.signum(w) * boundsWidth;
            }
        } else {
            return measure(mLen, false, fmi, drawBounds, lineInfo);
        }
    }

    /**
     * Shape the TextLine.
     */
    void shape(TextShaper.GlyphsConsumer consumer) {
        float horizontal = 0;
        float x = 0;
        final int runCount = mDirections.getRunCount();
        for (int runIndex = 0; runIndex < runCount; runIndex++) {
            final int runStart = mDirections.getRunStart(runIndex);
            if (runStart > mLen) break;
            final int runLimit = Math.min(runStart + mDirections.getRunLength(runIndex), mLen);
            final boolean runIsRtl = mDirections.isRunRtl(runIndex);

            final int runFlag = calculateRunFlag(runIndex, runCount, mDir);
            int segStart = runStart;
            for (int j = mHasTabs ? runStart : runLimit; j <= runLimit; j++) {
                if (j == runLimit || charAt(j) == TAB_CHAR) {
                    horizontal += shapeRun(consumer, segStart, j, runIsRtl, x + horizontal,
                            runIndex != (runCount - 1) || j != mLen, runFlag);

                    if (j != runLimit) {  // charAt(j) == TAB_CHAR
                        horizontal = mDir * nextTab(horizontal * mDir);
                    }
                    segStart = j + 1;
                }
            }
        }
    }

    /**
     * Returns the signed graphical offset from the leading margin.
     *
     * Following examples are all for measuring offset=3. LX(e.g. L0, L1, ...) denotes a
     * character which has LTR BiDi property. On the other hand, RX(e.g. R0, R1, ...) denotes a
     * character which has RTL BiDi property. Assuming all character has 1em width.
     *
     * Example 1: All LTR chars within LTR context
     *   Input Text (logical)  :   L0 L1 L2 L3 L4 L5 L6 L7 L8
     *   Input Text (visual)   :   L0 L1 L2 L3 L4 L5 L6 L7 L8
     *   Output(trailing=true) :  |--------| (Returns 3em)
     *   Output(trailing=false):  |--------| (Returns 3em)
     *
     * Example 2: All RTL chars within RTL context.
     *   Input Text (logical)  :   R0 R1 R2 R3 R4 R5 R6 R7 R8
     *   Input Text (visual)   :   R8 R7 R6 R5 R4 R3 R2 R1 R0
     *   Output(trailing=true) :                    |--------| (Returns -3em)
     *   Output(trailing=false):                    |--------| (Returns -3em)
     *
     * Example 3: BiDi chars within LTR context.
     *   Input Text (logical)  :   L0 L1 L2 R3 R4 R5 L6 L7 L8
     *   Input Text (visual)   :   L0 L1 L2 R5 R4 R3 L6 L7 L8
     *   Output(trailing=true) :  |-----------------| (Returns 6em)
     *   Output(trailing=false):  |--------| (Returns 3em)
     *
     * Example 4: BiDi chars within RTL context.
     *   Input Text (logical)  :   L0 L1 L2 R3 R4 R5 L6 L7 L8
     *   Input Text (visual)   :   L6 L7 L8 R5 R4 R3 L0 L1 L2
     *   Output(trailing=true) :           |-----------------| (Returns -6em)
     *   Output(trailing=false):                    |--------| (Returns -3em)
     *
     * @param offset the line-relative character offset, between 0 and the line length, inclusive
     * @param trailing no effect if the offset is not on the BiDi transition offset. If the offset
     *                 is on the BiDi transition offset and true is passed, the offset is regarded
     *                 as the edge of the trailing run's edge. If false, the offset is regarded as
     *                 the edge of the preceding run's edge. See example above.
     * @param fmi receives metrics information about the requested character, can be null
     * @param drawBounds output parameter for drawing bounding box. optional.
     * @param lineInfo an optional output parameter for filling line information.
     * @return the signed graphical offset from the leading margin to the requested character edge.
     *         The positive value means the offset is right from the leading edge. The negative
     *         value means the offset is left from the leading edge.
     */
    public float measure(@IntRange(from = 0) int offset, boolean trailing,
            @NonNull FontMetricsInt fmi, @Nullable RectF drawBounds, @Nullable LineInfo lineInfo) {
        if (TRACE_TEXTLINE) {
            Trace.beginSection("TextLine#measure");
        }
        try {
            if (offset > mLen) {
                throw new IndexOutOfBoundsException(
                        "offset(" + offset + ") should be less than line limit(" + mLen + ")");
            }
            if (lineInfo != null) {
                lineInfo.setClusterCount(0);
            }
            final int target = trailing ? offset - 1 : offset;
            if (target < 0) {
                return 0;
            }

            float h = 0;
            final int runCount = mDirections.getRunCount();
            for (int runIndex = 0; runIndex < runCount; runIndex++) {
                final int runStart = mDirections.getRunStart(runIndex);
                if (runStart > mLen) break;
                final int runLimit = Math.min(runStart + mDirections.getRunLength(runIndex), mLen);
                final boolean runIsRtl = mDirections.isRunRtl(runIndex);
                final int runFlag = calculateRunFlag(runIndex, runCount, mDir);

                int segStart = runStart;
                for (int j = mHasTabs ? runStart : runLimit; j <= runLimit; j++) {
                    if (j == runLimit || charAt(j) == TAB_CHAR) {
                        final boolean targetIsInThisSegment = target >= segStart && target < j;
                        final boolean sameDirection =
                                (mDir == Layout.DIR_RIGHT_TO_LEFT) == runIsRtl;

                        if (targetIsInThisSegment && sameDirection) {
                            return h + measureRun(segStart, offset, j, runIsRtl, fmi, drawBounds,
                                    null,
                                    0, h, lineInfo, runFlag);
                        }

                        final float segmentWidth = measureRun(segStart, j, j, runIsRtl, fmi,
                                drawBounds,
                                null, 0, h, lineInfo, runFlag);
                        h += sameDirection ? segmentWidth : -segmentWidth;

                        if (targetIsInThisSegment) {
                            return h + measureRun(segStart, offset, j, runIsRtl, null, null, null,
                                    0,
                                    h, lineInfo, runFlag);
                        }

                        if (j != runLimit) {  // charAt(j) == TAB_CHAR
                            if (offset == j) {
                                return h;
                            }
                            h = mDir * nextTab(h * mDir);
                            if (target == j) {
                                return h;
                            }
                        }

                        segStart = j + 1;
                    }
                }
            }

            return h;
        } finally {
            if (TRACE_TEXTLINE) {
                Trace.endSection();
            }
        }
    }

    /**
     * Return the signed horizontal bounds of the characters in the line.
     *
     * The length of the returned array equals to 2 * mLen. The left bound of the i th character
     * is stored at index 2 * i. And the right bound of the i th character is stored at index
     * (2 * i + 1).
     *
     * Check the following examples. LX(e.g. L0, L1, ...) denotes a character which has LTR BiDi
     * property. On the other hand, RX(e.g. R0, R1, ...) denotes a character which has RTL BiDi
     * property. Assuming all character has 1em width.
     *
     * Example 1: All LTR chars within LTR context
     *   Input Text (logical)  :   L0 L1 L2 L3
     *   Input Text (visual)   :   L0 L1 L2 L3
     *   Output :  [0em, 1em, 1em, 2em, 2em, 3em, 3em, 4em]
     *
     * Example 2: All RTL chars within RTL context.
     *   Input Text (logical)  :   R0 R1 R2 R3
     *   Input Text (visual)   :   R3 R2 R1 R0
     *   Output :  [-1em, 0em, -2em, -1em, -3em, -2em, -4em, -3em]

     *
     * Example 3: BiDi chars within LTR context.
     *   Input Text (logical)  :   L0 L1 R2 R3 L4 L5
     *   Input Text (visual)   :   L0 L1 R3 R2 L4 L5
     *   Output :  [0em, 1em, 1em, 2em, 3em, 4em, 2em, 3em, 4em, 5em, 5em, 6em]

     *
     * Example 4: BiDi chars within RTL context.
     *   Input Text (logical)  :   L0 L1 R2 R3 L4 L5
     *   Input Text (visual)   :   L4 L5 R3 R2 L0 L1
     *   Output :  [-2em, -1em, -1em, 0em, -3em, -2em, -4em, -3em, -6em, -5em, -5em, -4em]
     *
     * @param bounds the array to receive the character bounds data. Its length should be at least
     *               2 times of the line length.
     * @param advances the array to receive the character advance data, nullable. If provided, its
     *                 length should be equal or larger than the line length.
     *
     * @throws IllegalArgumentException if the given {@code bounds} is null.
     * @throws IndexOutOfBoundsException if the given {@code bounds} or {@code advances} doesn't
     * have enough space to hold the result.
     */
    public void measureAllBounds(@NonNull float[] bounds, @Nullable float[] advances) {
        if (bounds == null) {
            throw new IllegalArgumentException("bounds can't be null");
        }
        if (bounds.length < 2 * mLen) {
            throw new IndexOutOfBoundsException("bounds doesn't have enough space to receive the "
                    + "result, needed: " + (2 * mLen) + " had: " + bounds.length);
        }
        if (advances == null) {
            advances = new float[mLen];
        }
        if (advances.length < mLen) {
            throw new IndexOutOfBoundsException("advance doesn't have enough space to receive the "
                    + "result, needed: " + mLen + " had: " + advances.length);
        }
        float h = 0;
        final int runCount = mDirections.getRunCount();
        for (int runIndex = 0; runIndex < runCount; runIndex++) {
            final int runStart = mDirections.getRunStart(runIndex);
            if (runStart > mLen) break;
            final int runLimit = Math.min(runStart + mDirections.getRunLength(runIndex), mLen);
            final boolean runIsRtl = mDirections.isRunRtl(runIndex);
            final int runFlag = calculateRunFlag(runIndex, runCount, mDir);

            int segStart = runStart;
            for (int j = mHasTabs ? runStart : runLimit; j <= runLimit; j++) {
                if (j == runLimit || charAt(j) == TAB_CHAR) {
                    final boolean sameDirection = (mDir == Layout.DIR_RIGHT_TO_LEFT) == runIsRtl;
                    final float segmentWidth =
                            measureRun(segStart, j, j, runIsRtl, null, null, advances, segStart, 0,
                                    null, runFlag);

                    final float oldh = h;
                    h += sameDirection ? segmentWidth : -segmentWidth;
                    float currh = sameDirection ? oldh : h;
                    for (int offset = segStart; offset < j && offset < mLen; ++offset) {
                        if (runIsRtl) {
                            bounds[2 * offset + 1] = currh;
                            currh -= advances[offset];
                            bounds[2 * offset] = currh;
                        } else {
                            bounds[2 * offset] = currh;
                            currh += advances[offset];
                            bounds[2 * offset + 1] = currh;
                        }
                    }

                    if (j != runLimit) {  // charAt(j) == TAB_CHAR
                        final float leftX;
                        final float rightX;
                        if (runIsRtl) {
                            rightX = h;
                            h = mDir * nextTab(h * mDir);
                            leftX = h;
                        } else {
                            leftX = h;
                            h = mDir * nextTab(h * mDir);
                            rightX = h;
                        }
                        bounds[2 * j] = leftX;
                        bounds[2 * j + 1] = rightX;
                        advances[j] = rightX - leftX;
                    }

                    segStart = j + 1;
                }
            }
        }
    }

    /**
     * @see #measure(int, boolean, FontMetricsInt, RectF, LineInfo)
     * @return The measure results for all possible offsets
     */
    @VisibleForTesting
    public float[] measureAllOffsets(boolean[] trailing, FontMetricsInt fmi) {
        float[] measurement = new float[mLen + 1];
        if (trailing[0]) {
            measurement[0] = 0;
        }

        float horizontal = 0;
        final int runCount = mDirections.getRunCount();
        for (int runIndex = 0; runIndex < runCount; runIndex++) {
            final int runStart = mDirections.getRunStart(runIndex);
            if (runStart > mLen) break;
            final int runLimit = Math.min(runStart + mDirections.getRunLength(runIndex), mLen);
            final boolean runIsRtl = mDirections.isRunRtl(runIndex);
            final int runFlag = calculateRunFlag(runIndex, runCount, mDir);

            int segStart = runStart;
            for (int j = mHasTabs ? runStart : runLimit; j <= runLimit; ++j) {
                if (j == runLimit || charAt(j) == TAB_CHAR) {
                    final float oldHorizontal = horizontal;
                    final boolean sameDirection =
                            (mDir == Layout.DIR_RIGHT_TO_LEFT) == runIsRtl;

                    // We are using measurement to receive character advance here. So that it
                    // doesn't need to allocate a new array.
                    // But be aware that when trailing[segStart] is true, measurement[segStart]
                    // will be computed in the previous run. And we need to store it first in case
                    // measureRun overwrites the result.
                    final float previousSegEndHorizontal = measurement[segStart];
                    final float width =
                            measureRun(segStart, j, j, runIsRtl, fmi, null, measurement, segStart,
                                    0, null, runFlag);
                    horizontal += sameDirection ? width : -width;

                    float currHorizontal = sameDirection ? oldHorizontal : horizontal;
                    final int segLimit = Math.min(j, mLen);

                    for (int offset = segStart; offset <= segLimit; ++offset) {
                        float advance = 0f;
                        // When offset == segLimit, advance is meaningless.
                        if (offset < segLimit) {
                            advance = runIsRtl ? -measurement[offset] : measurement[offset];
                        }

                        if (offset == segStart && trailing[offset]) {
                            // If offset == segStart and trailing[segStart] is true, restore the
                            // value of measurement[segStart] from the previous run.
                            measurement[offset] = previousSegEndHorizontal;
                        } else if (offset != segLimit || trailing[offset]) {
                            measurement[offset] = currHorizontal;
                        }

                        currHorizontal += advance;
                    }

                    if (j != runLimit) {  // charAt(j) == TAB_CHAR
                        if (!trailing[j]) {
                            measurement[j] = horizontal;
                        }
                        horizontal = mDir * nextTab(horizontal * mDir);
                        if (trailing[j + 1]) {
                            measurement[j + 1] = horizontal;
                        }
                    }

                    segStart = j + 1;
                }
            }
        }
        if (!trailing[mLen]) {
            measurement[mLen] = horizontal;
        }
        return measurement;
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
     * @param runFlag the run flag to be applied for this run.
     * @return the signed width of the run, based on the paragraph direction.
     * Only valid if needWidth is true.
     */
    private float drawRun(Canvas c, int start,
            int limit, boolean runIsRtl, float x, int top, int y, int bottom,
            boolean needWidth, int runFlag) {

        if ((mDir == Layout.DIR_LEFT_TO_RIGHT) == runIsRtl) {
            float w = -measureRun(start, limit, limit, runIsRtl, null, null, null, 0, 0, null,
                    runFlag);
            handleRun(start, limit, limit, runIsRtl, c, null, x + w, top,
                    y, bottom, null, null, false, null, 0, null, runFlag);
            return w;
        }

        return handleRun(start, limit, limit, runIsRtl, c, null, x, top,
                y, bottom, null, null, needWidth, null, 0, null, runFlag);
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
     * @param advances receives the advance information about the requested run, can be null.
     * @param advancesIndex the start index to fill in the advance information.
     * @param x horizontal offset of the run.
     * @param lineInfo an optional output parameter for filling line information.
     * @param runFlag the run flag to be applied for this run.
     * @return the signed width from the start of the run to the leading edge
     * of the character at offset, based on the run (not paragraph) direction
     */
    private float measureRun(int start, int offset, int limit, boolean runIsRtl,
            @Nullable FontMetricsInt fmi, @Nullable RectF drawBounds, @Nullable float[] advances,
            int advancesIndex, float x, @Nullable LineInfo lineInfo, int runFlag) {
        if (drawBounds != null && (mDir == Layout.DIR_LEFT_TO_RIGHT) == runIsRtl) {
            float w = -measureRun(start, offset, limit, runIsRtl, null, null, null, 0, 0, null,
                    runFlag);
            return handleRun(start, offset, limit, runIsRtl, null, null, x + w, 0, 0, 0, fmi,
                    drawBounds, true, advances, advancesIndex, lineInfo, runFlag);
        }
        return handleRun(start, offset, limit, runIsRtl, null, null, x, 0, 0, 0, fmi, drawBounds,
                true, advances, advancesIndex, lineInfo, runFlag);
    }

    /**
     * Shape a unidirectional (but possibly multi-styled) run of text.
     *
     * @param consumer the consumer of the shape result
     * @param start the line-relative start
     * @param limit the line-relative limit
     * @param runIsRtl true if the run is right-to-left
     * @param x the position of the run that is closest to the leading margin
     * @param needWidth true if the width value is required.
     * @param runFlag the run flag to be applied for this run.
     * @return the signed width of the run, based on the paragraph direction.
     * Only valid if needWidth is true.
     */
    private float shapeRun(TextShaper.GlyphsConsumer consumer, int start,
            int limit, boolean runIsRtl, float x, boolean needWidth, int runFlag) {

        if ((mDir == Layout.DIR_LEFT_TO_RIGHT) == runIsRtl) {
            float w = -measureRun(start, limit, limit, runIsRtl, null, null, null, 0, 0, null,
                    runFlag);
            handleRun(start, limit, limit, runIsRtl, null, consumer, x + w, 0, 0, 0, null, null,
                    false, null, 0, null, runFlag);
            return w;
        }

        return handleRun(start, limit, limit, runIsRtl, null, consumer, x, 0, 0, 0, null, null,
                needWidth, null, 0, null, runFlag);
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
        if (mIsJustifying) {
            wp.setWordSpacing(mAddedWordSpacingInPx);
            wp.setLetterSpacing(mAddedLetterSpacingInPx / wp.getTextSize());  // Convert to Em
        }

        int spanStart = runStart;
        int spanLimit;
        if (mSpanned == null || runStart == runLimit) {
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

        int cursorOpt = after ? Paint.CURSOR_AFTER : Paint.CURSOR_BEFORE;
        if (mCharsValid) {
            return wp.getTextRunCursor(mChars, spanStart, spanLimit - spanStart,
                    runIsRtl, offset, cursorOpt);
        } else {
            return wp.getTextRunCursor(mText, mStart + spanStart,
                    mStart + spanLimit, runIsRtl, mStart + offset, cursorOpt) - mStart;
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

    private void expandMetricsFromPaint(TextPaint wp, int start, int end,
            int contextStart, int contextEnd, boolean runIsRtl, FontMetricsInt fmi) {

        final int previousTop     = fmi.top;
        final int previousAscent  = fmi.ascent;
        final int previousDescent = fmi.descent;
        final int previousBottom  = fmi.bottom;
        final int previousLeading = fmi.leading;

        int count = end - start;
        int contextCount = contextEnd - contextStart;
        if (mCharsValid) {
            wp.getFontMetricsInt(mChars, start, count, contextStart, contextCount, runIsRtl,
                    fmi);
        } else {
            if (mComputed == null) {
                wp.getFontMetricsInt(mText, mStart + start, count, mStart + contextStart,
                        contextCount, runIsRtl, fmi);
            } else {
                mComputed.getFontMetricsInt(mStart + start, mStart + end, fmi);
            }
        }

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

    private static void drawStroke(TextPaint wp, Canvas c, int color, float position,
            float thickness, float xleft, float xright, float baseline) {
        final float strokeTop = baseline + wp.baselineShift + position;

        final int previousColor = wp.getColor();
        final Paint.Style previousStyle = wp.getStyle();
        final boolean previousAntiAlias = wp.isAntiAlias();

        wp.setStyle(Paint.Style.FILL);
        wp.setAntiAlias(true);

        wp.setColor(color);
        c.drawRect(xleft, strokeTop, xright, strokeTop + thickness, wp);

        wp.setStyle(previousStyle);
        wp.setColor(previousColor);
        wp.setAntiAlias(previousAntiAlias);
    }

    private float getRunAdvance(TextPaint wp, int start, int end, int contextStart, int contextEnd,
            boolean runIsRtl, int offset, @Nullable float[] advances, int advancesIndex,
            RectF drawingBounds, @Nullable LineInfo lineInfo) {
        if (lineInfo != null) {
            if (mRunInfo == null) {
                mRunInfo = new Paint.RunInfo();
            }
            mRunInfo.setClusterCount(0);
        } else {
            mRunInfo = null;
        }
        if (mCharsValid) {
            float r = wp.getRunCharacterAdvance(mChars, start, end, contextStart, contextEnd,
                    runIsRtl, offset, advances, advancesIndex, drawingBounds, mRunInfo);
            if (lineInfo != null) {
                lineInfo.setClusterCount(lineInfo.getClusterCount() + mRunInfo.getClusterCount());
            }
            return r;
        } else {
            final int delta = mStart;
            // TODO: Add cluster information to the PrecomputedText for better performance of
            // justification.
            if (mComputed == null || advances != null || lineInfo != null) {
                float r = wp.getRunCharacterAdvance(mText, delta + start, delta + end,
                        delta + contextStart, delta + contextEnd, runIsRtl,
                        delta + offset, advances, advancesIndex, drawingBounds, mRunInfo);
                if (lineInfo != null) {
                    lineInfo.setClusterCount(
                            lineInfo.getClusterCount() + mRunInfo.getClusterCount());
                }
                return r;
            } else {
                if (drawingBounds != null) {
                    if (mTmpRectForPrecompute == null) {
                        mTmpRectForPrecompute = new Rect();
                    }
                    mComputed.getBounds(start + delta, end + delta, mTmpRectForPrecompute);
                    drawingBounds.set(mTmpRectForPrecompute);
                }
                return mComputed.getWidth(start + delta, end + delta);
            }
        }
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
     * @param consumer the output positioned glyph list, can be null if not necessary
     * @param x the edge of the run closest to the leading margin
     * @param top the top of the line
     * @param y the baseline
     * @param bottom the bottom of the line
     * @param fmi receives metrics information, can be null
     * @param needWidth true if the width of the run is needed
     * @param offset the offset for the purpose of measuring
     * @param decorations the list of locations and paremeters for drawing decorations
     * @param advances receives the advance information about the requested run, can be null.
     * @param advancesIndex the start index to fill in the advance information.
     * @param lineInfo an optional output parameter for filling line information.
     * @param runFlag the run flag to be applied for this run.
     * @return the signed width of the run based on the run direction; only
     * valid if needWidth is true
     */
    private float handleText(TextPaint wp, int start, int end,
            int contextStart, int contextEnd, boolean runIsRtl,
            Canvas c, TextShaper.GlyphsConsumer consumer, float x, int top, int y, int bottom,
            FontMetricsInt fmi, RectF drawBounds, boolean needWidth, int offset,
            @Nullable ArrayList<DecorationInfo> decorations,
            @Nullable float[] advances, int advancesIndex, @Nullable LineInfo lineInfo,
            int runFlag) {
        if (mIsJustifying) {
            wp.setWordSpacing(mAddedWordSpacingInPx);
            wp.setLetterSpacing(mAddedLetterSpacingInPx / wp.getTextSize());  // Convert to Em
        }
        // Get metrics first (even for empty strings or "0" width runs)
        if (drawBounds != null && fmi == null) {
            fmi = new FontMetricsInt();
        }
        if (fmi != null) {
            expandMetricsFromPaint(fmi, wp);
        }

        // No need to do anything if the run width is "0"
        if (end == start) {
            return 0f;
        }

        float totalWidth = 0;
        if ((runFlag & Paint.TEXT_RUN_FLAG_LEFT_EDGE) == Paint.TEXT_RUN_FLAG_LEFT_EDGE) {
            wp.setFlags(wp.getFlags() | Paint.TEXT_RUN_FLAG_LEFT_EDGE);
        } else {
            wp.setFlags(wp.getFlags() & ~Paint.TEXT_RUN_FLAG_LEFT_EDGE);
        }
        if ((runFlag & Paint.TEXT_RUN_FLAG_RIGHT_EDGE) == Paint.TEXT_RUN_FLAG_RIGHT_EDGE) {
            wp.setFlags(wp.getFlags() | Paint.TEXT_RUN_FLAG_RIGHT_EDGE);
        } else {
            wp.setFlags(wp.getFlags() & ~Paint.TEXT_RUN_FLAG_RIGHT_EDGE);
        }
        final int numDecorations = decorations == null ? 0 : decorations.size();
        if (needWidth || ((c != null || consumer != null) && (wp.bgColor != 0
                || numDecorations != 0 || runIsRtl))) {
            if (drawBounds != null && mTmpRectForPaintAPI == null) {
                mTmpRectForPaintAPI = new RectF();
            }
            totalWidth = getRunAdvance(wp, start, end, contextStart, contextEnd, runIsRtl, offset,
                    advances, advancesIndex, drawBounds == null ? null : mTmpRectForPaintAPI,
                    lineInfo);
            if (drawBounds != null) {
                if (runIsRtl) {
                    mTmpRectForPaintAPI.offset(x - totalWidth, 0);
                } else {
                    mTmpRectForPaintAPI.offset(x, 0);
                }
                drawBounds.union(mTmpRectForPaintAPI);
            }
        }

        final float leftX, rightX;
        if (runIsRtl) {
            leftX = x - totalWidth;
            rightX = x;
        } else {
            leftX = x;
            rightX = x + totalWidth;
        }

        if (consumer != null) {
            shapeTextRun(consumer, wp, start, end, contextStart, contextEnd, runIsRtl, leftX);
        }

        if (mUseFallbackExtent && fmi != null) {
            expandMetricsFromPaint(wp, start, end, contextStart, contextEnd, runIsRtl, fmi);
        }

        if (c != null) {
            if (wp.bgColor != 0) {
                int previousColor = wp.getColor();
                Paint.Style previousStyle = wp.getStyle();

                wp.setColor(wp.bgColor);
                wp.setStyle(Paint.Style.FILL);
                c.drawRect(leftX, top, rightX, bottom, wp);

                wp.setStyle(previousStyle);
                wp.setColor(previousColor);
            }

            drawTextRun(c, wp, start, end, contextStart, contextEnd, runIsRtl,
                    leftX, y + wp.baselineShift);

            if (numDecorations != 0) {
                for (int i = 0; i < numDecorations; i++) {
                    final DecorationInfo info = decorations.get(i);

                    final int decorationStart = Math.max(info.start, start);
                    final int decorationEnd = Math.min(info.end, offset);
                    float decorationStartAdvance = getRunAdvance(wp, start, end, contextStart,
                            contextEnd, runIsRtl, decorationStart, null, 0, null, null);
                    float decorationEndAdvance = getRunAdvance(wp, start, end, contextStart,
                            contextEnd, runIsRtl, decorationEnd, null, 0, null, null);
                    final float decorationXLeft, decorationXRight;
                    if (runIsRtl) {
                        decorationXLeft = rightX - decorationEndAdvance;
                        decorationXRight = rightX - decorationStartAdvance;
                    } else {
                        decorationXLeft = leftX + decorationStartAdvance;
                        decorationXRight = leftX + decorationEndAdvance;
                    }

                    // Theoretically, there could be cases where both Paint's and TextPaint's
                    // setUnderLineText() are called. For backward compatibility, we need to draw
                    // both underlines, the one with custom color first.
                    if (info.underlineColor != 0) {
                        drawStroke(wp, c, info.underlineColor, wp.getUnderlinePosition(),
                                info.underlineThickness, decorationXLeft, decorationXRight, y);
                    }
                    if (info.isUnderlineText) {
                        final float thickness =
                                Math.max(wp.getUnderlineThickness(), 1.0f);
                        drawStroke(wp, c, wp.getColor(), wp.getUnderlinePosition(), thickness,
                                decorationXLeft, decorationXRight, y);
                    }

                    if (info.isStrikeThruText) {
                        final float thickness =
                                Math.max(wp.getStrikeThruThickness(), 1.0f);
                        drawStroke(wp, c, wp.getColor(), wp.getStrikeThruPosition(), thickness,
                                decorationXLeft, decorationXRight, y);
                    }
                }
            }

        }

        return runIsRtl ? -totalWidth : totalWidth;
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

    private int adjustStartHyphenEdit(int start, @Paint.StartHyphenEdit int startHyphenEdit) {
        // Only draw hyphens on first in line. Disable them otherwise.
        return start > 0 ? Paint.START_HYPHEN_EDIT_NO_EDIT : startHyphenEdit;
    }

    private int adjustEndHyphenEdit(int limit, @Paint.EndHyphenEdit int endHyphenEdit) {
        // Only draw hyphens on last run in line. Disable them otherwise.
        return limit < mLen ? Paint.END_HYPHEN_EDIT_NO_EDIT : endHyphenEdit;
    }

    private static final class DecorationInfo {
        public boolean isStrikeThruText;
        public boolean isUnderlineText;
        public int underlineColor;
        public float underlineThickness;
        public int start = -1;
        public int end = -1;

        public boolean hasDecoration() {
            return isStrikeThruText || isUnderlineText || underlineColor != 0;
        }

        // Copies the info, but not the start and end range.
        public DecorationInfo copyInfo() {
            final DecorationInfo copy = new DecorationInfo();
            copy.isStrikeThruText = isStrikeThruText;
            copy.isUnderlineText = isUnderlineText;
            copy.underlineColor = underlineColor;
            copy.underlineThickness = underlineThickness;
            return copy;
        }
    }

    private void extractDecorationInfo(@NonNull TextPaint paint, @NonNull DecorationInfo info) {
        info.isStrikeThruText = paint.isStrikeThruText();
        if (info.isStrikeThruText) {
            paint.setStrikeThruText(false);
        }
        info.isUnderlineText = paint.isUnderlineText();
        if (info.isUnderlineText) {
            paint.setUnderlineText(false);
        }
        info.underlineColor = paint.underlineColor;
        info.underlineThickness = paint.underlineThickness;
        paint.setUnderlineText(0, 0.0f);
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
     * @param consumer the output positioned glyphs, can be null
     * @param x the end of the run closest to the leading margin
     * @param top the top of the line
     * @param y the baseline
     * @param bottom the bottom of the line
     * @param fmi receives metrics information, can be null
     * @param needWidth true if the width is required
     * @param advances receives the advance information about the requested run, can be null.
     * @param advancesIndex the start index to fill in the advance information.
     * @param lineInfo an optional output parameter for filling line information.
     * @param runFlag the run flag to be applied for this run.
     * @return the signed width of the run based on the run direction; only
     * valid if needWidth is true
     */
    private float handleRun(int start, int measureLimit,
            int limit, boolean runIsRtl, Canvas c,
            TextShaper.GlyphsConsumer consumer, float x, int top, int y,
            int bottom, FontMetricsInt fmi, RectF drawBounds, boolean needWidth,
            @Nullable float[] advances, int advancesIndex, @Nullable LineInfo lineInfo,
            int runFlag) {

        if (measureLimit < start || measureLimit > limit) {
            throw new IndexOutOfBoundsException("measureLimit (" + measureLimit + ") is out of "
                    + "start (" + start + ") and limit (" + limit + ") bounds");
        }

        if (advances != null && advances.length - advancesIndex < measureLimit - start) {
            throw new IndexOutOfBoundsException("advances doesn't have enough space to receive the "
                    + "result");
        }

        // Case of an empty line, make sure we update fmi according to mPaint
        if (start == measureLimit) {
            final TextPaint wp = mWorkPaint;
            wp.set(mPaint);
            if (fmi != null) {
                expandMetricsFromPaint(fmi, wp);
            }
            if (drawBounds != null) {
                if (fmi == null) {
                    FontMetricsInt tmpFmi = new FontMetricsInt();
                    expandMetricsFromPaint(tmpFmi, wp);
                    fmi = tmpFmi;
                }
                drawBounds.union(0f, fmi.top, 0f, fmi.bottom);
            }
            return 0f;
        }

        final boolean needsSpanMeasurement;
        if (mSpanned == null) {
            needsSpanMeasurement = false;
        } else {
            mMetricAffectingSpanSpanSet.init(mSpanned, mStart + start, mStart + limit);
            mCharacterStyleSpanSet.init(mSpanned, mStart + start, mStart + limit);
            needsSpanMeasurement = mMetricAffectingSpanSpanSet.numberOfSpans != 0
                    || mCharacterStyleSpanSet.numberOfSpans != 0;
        }

        if (!needsSpanMeasurement) {
            final TextPaint wp = mWorkPaint;
            wp.set(mPaint);
            wp.setStartHyphenEdit(adjustStartHyphenEdit(start, wp.getStartHyphenEdit()));
            wp.setEndHyphenEdit(adjustEndHyphenEdit(limit, wp.getEndHyphenEdit()));
            return handleText(wp, start, limit, start, limit, runIsRtl, c, consumer, x, top,
                    y, bottom, fmi, drawBounds, needWidth, measureLimit, null, advances,
                    advancesIndex, lineInfo, runFlag);
        }

        // Shaping needs to take into account context up to metric boundaries,
        // but rendering needs to take into account character style boundaries.
        // So we iterate through metric runs to get metric bounds,
        // then within each metric run iterate through character style runs
        // for the run bounds.
        final float originalX = x;
        for (int i = start, inext; i < measureLimit; i = inext) {
            final TextPaint wp = mWorkPaint;
            wp.set(mPaint);

            inext = mMetricAffectingSpanSpanSet.getNextTransition(mStart + i, mStart + limit) -
                    mStart;
            int mlimit = Math.min(inext, measureLimit);

            ReplacementSpan replacement = null;

            for (int j = 0; j < mMetricAffectingSpanSpanSet.numberOfSpans; j++) {
                // Both intervals [spanStarts..spanEnds] and [mStart + i..mStart + mlimit] are NOT
                // empty by construction. This special case in getSpans() explains the >= & <= tests
                if ((mMetricAffectingSpanSpanSet.spanStarts[j] >= mStart + mlimit)
                        || (mMetricAffectingSpanSpanSet.spanEnds[j] <= mStart + i)) continue;

                boolean insideEllipsis =
                        mStart + mEllipsisStart <= mMetricAffectingSpanSpanSet.spanStarts[j]
                        && mMetricAffectingSpanSpanSet.spanEnds[j] <= mStart + mEllipsisEnd;
                final MetricAffectingSpan span = mMetricAffectingSpanSpanSet.spans[j];
                if (span instanceof ReplacementSpan) {
                    replacement = !insideEllipsis ? (ReplacementSpan) span : null;
                } else {
                    // We might have a replacement that uses the draw
                    // state, otherwise measure state would suffice.
                    span.updateDrawState(wp);
                }
            }

            if (replacement != null) {
                final float width = handleReplacement(replacement, wp, i, mlimit, runIsRtl, c,
                        x, top, y, bottom, fmi, needWidth || mlimit < measureLimit);
                x += width;
                if (advances != null) {
                    // For replacement, the entire width is assigned to the first character.
                    advances[advancesIndex + i - start] = runIsRtl ? -width : width;
                    for (int j = i + 1; j < mlimit; ++j) {
                        advances[advancesIndex + j - start] = 0.0f;
                    }
                }
                continue;
            }

            final TextPaint activePaint = mActivePaint;
            activePaint.set(mPaint);
            int activeStart = i;
            int activeEnd = mlimit;
            final DecorationInfo decorationInfo = mDecorationInfo;
            mDecorations.clear();
            for (int j = i, jnext; j < mlimit; j = jnext) {
                jnext = mCharacterStyleSpanSet.getNextTransition(mStart + j, mStart + inext) -
                        mStart;

                final int offset = Math.min(jnext, mlimit);
                wp.set(mPaint);
                for (int k = 0; k < mCharacterStyleSpanSet.numberOfSpans; k++) {
                    // Intentionally using >= and <= as explained above
                    if ((mCharacterStyleSpanSet.spanStarts[k] >= mStart + offset) ||
                            (mCharacterStyleSpanSet.spanEnds[k] <= mStart + j)) continue;

                    final CharacterStyle span = mCharacterStyleSpanSet.spans[k];
                    span.updateDrawState(wp);
                }

                extractDecorationInfo(wp, decorationInfo);

                if (j == i) {
                    // First chunk of text. We can't handle it yet, since we may need to merge it
                    // with the next chunk. So we just save the TextPaint for future comparisons
                    // and use.
                    activePaint.set(wp);
                } else if (!equalAttributes(wp, activePaint)) {
                    final int spanRunFlag = resolveRunFlagForSubSequence(
                            runFlag, runIsRtl, start, measureLimit, activeStart, activeEnd);

                    // The style of the present chunk of text is substantially different from the
                    // style of the previous chunk. We need to handle the active piece of text
                    // and restart with the present chunk.
                    activePaint.setStartHyphenEdit(
                            adjustStartHyphenEdit(activeStart, mPaint.getStartHyphenEdit()));
                    activePaint.setEndHyphenEdit(
                            adjustEndHyphenEdit(activeEnd, mPaint.getEndHyphenEdit()));
                    x += handleText(activePaint, activeStart, activeEnd, i, inext, runIsRtl, c,
                            consumer, x, top, y, bottom, fmi, drawBounds,
                            needWidth || activeEnd < measureLimit,
                            Math.min(activeEnd, mlimit), mDecorations,
                            advances, advancesIndex + activeStart - start, lineInfo, spanRunFlag);

                    activeStart = j;
                    activePaint.set(wp);
                    mDecorations.clear();
                } else {
                    // The present TextPaint is substantially equal to the last TextPaint except
                    // perhaps for decorations. We just need to expand the active piece of text to
                    // include the present chunk, which we always do anyway. We don't need to save
                    // wp to activePaint, since they are already equal.
                }

                activeEnd = jnext;
                if (decorationInfo.hasDecoration()) {
                    final DecorationInfo copy = decorationInfo.copyInfo();
                    copy.start = j;
                    copy.end = jnext;
                    mDecorations.add(copy);
                }
            }

            final int spanRunFlag = resolveRunFlagForSubSequence(
                    runFlag, runIsRtl, start, measureLimit, activeStart, activeEnd);
            // Handle the final piece of text.
            activePaint.setStartHyphenEdit(
                    adjustStartHyphenEdit(activeStart, mPaint.getStartHyphenEdit()));
            activePaint.setEndHyphenEdit(
                    adjustEndHyphenEdit(activeEnd, mPaint.getEndHyphenEdit()));
            x += handleText(activePaint, activeStart, activeEnd, i, inext, runIsRtl, c, consumer, x,
                    top, y, bottom, fmi, drawBounds, needWidth || activeEnd < measureLimit,
                    Math.min(activeEnd, mlimit), mDecorations,
                    advances, advancesIndex + activeStart - start, lineInfo, spanRunFlag);
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
     * Shape a text run with the set-up paint.
     *
     * @param consumer the output positioned glyphs list
     * @param paint the paint used to render the text
     * @param start the start of the run
     * @param end the end of the run
     * @param contextStart the start of context for the run
     * @param contextEnd the end of the context for the run
     * @param runIsRtl true if the run is right-to-left
     * @param x the x position of the left edge of the run
     */
    private void shapeTextRun(TextShaper.GlyphsConsumer consumer, TextPaint paint,
            int start, int end, int contextStart, int contextEnd, boolean runIsRtl, float x) {

        int count = end - start;
        int contextCount = contextEnd - contextStart;
        PositionedGlyphs glyphs;
        if (mCharsValid) {
            glyphs = TextRunShaper.shapeTextRun(
                    mChars,
                    start, count,
                    contextStart, contextCount,
                    x, 0f,
                    runIsRtl,
                    paint
            );
        } else {
            glyphs = TextRunShaper.shapeTextRun(
                    mText,
                    mStart + start, count,
                    mStart + contextStart, contextCount,
                    x, 0f,
                    runIsRtl,
                    paint
            );
        }
        consumer.accept(start, count, glyphs, paint);
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

    private boolean isStretchableWhitespace(int ch) {
        // TODO: Support NBSP and other stretchable whitespace (b/34013491 and b/68204709).
        return ch == 0x0020;
    }

    /* Return the number of spaces in the text line, for the purpose of justification */
    private int countStretchableSpaces(int start, int end) {
        int count = 0;
        for (int i = start; i < end; i++) {
            final char c = mCharsValid ? mChars[i] : mText.charAt(i + mStart);
            if (isStretchableWhitespace(c)) {
                count++;
            }
        }
        return count;
    }

    // Note: keep this in sync with Minikin LineBreaker::isLineEndSpace()
    public static boolean isLineEndSpace(char ch) {
        return ch == ' ' || ch == '\t' || ch == 0x1680
                || (0x2000 <= ch && ch <= 0x200A && ch != 0x2007)
                || ch == 0x205F || ch == 0x3000;
    }

    private static final int TAB_INCREMENT = 20;

    private static boolean equalAttributes(@NonNull TextPaint lp, @NonNull TextPaint rp) {
        return lp.getColorFilter() == rp.getColorFilter()
                && lp.getMaskFilter() == rp.getMaskFilter()
                && lp.getShader() == rp.getShader()
                && lp.getTypeface() == rp.getTypeface()
                && lp.getXfermode() == rp.getXfermode()
                && lp.getTextLocales().equals(rp.getTextLocales())
                && TextUtils.equals(lp.getFontFeatureSettings(), rp.getFontFeatureSettings())
                && TextUtils.equals(lp.getFontVariationSettings(), rp.getFontVariationSettings())
                && lp.getShadowLayerRadius() == rp.getShadowLayerRadius()
                && lp.getShadowLayerDx() == rp.getShadowLayerDx()
                && lp.getShadowLayerDy() == rp.getShadowLayerDy()
                && lp.getShadowLayerColor() == rp.getShadowLayerColor()
                && lp.getFlags() == rp.getFlags()
                && lp.getHinting() == rp.getHinting()
                && lp.getStyle() == rp.getStyle()
                && lp.getColor() == rp.getColor()
                && lp.getStrokeWidth() == rp.getStrokeWidth()
                && lp.getStrokeMiter() == rp.getStrokeMiter()
                && lp.getStrokeCap() == rp.getStrokeCap()
                && lp.getStrokeJoin() == rp.getStrokeJoin()
                && lp.getTextAlign() == rp.getTextAlign()
                && lp.isElegantTextHeight() == rp.isElegantTextHeight()
                && lp.getTextSize() == rp.getTextSize()
                && lp.getTextScaleX() == rp.getTextScaleX()
                && lp.getTextSkewX() == rp.getTextSkewX()
                && lp.getLetterSpacing() == rp.getLetterSpacing()
                && lp.getWordSpacing() == rp.getWordSpacing()
                && lp.getStartHyphenEdit() == rp.getStartHyphenEdit()
                && lp.getEndHyphenEdit() == rp.getEndHyphenEdit()
                && lp.bgColor == rp.bgColor
                && lp.baselineShift == rp.baselineShift
                && lp.linkColor == rp.linkColor
                && lp.drawableState == rp.drawableState
                && lp.density == rp.density
                && lp.underlineColor == rp.underlineColor
                && lp.underlineThickness == rp.underlineThickness;
    }
}
