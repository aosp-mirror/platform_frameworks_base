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

import static com.android.text.flags.Flags.FLAG_FIX_LINE_HEIGHT_FOR_LOCALE;
import static com.android.text.flags.Flags.FLAG_USE_BOUNDS_FOR_WIDTH;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.text.LineBreakConfig;
import android.graphics.text.LineBreaker;
import android.os.Build;
import android.text.method.TextKeyListener;
import android.text.style.AlignmentSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LeadingMarginSpan.LeadingMarginSpan2;
import android.text.style.LineBackgroundSpan;
import android.text.style.ParagraphStyle;
import android.text.style.ReplacementSpan;
import android.text.style.TabStopSpan;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.GrowingArrayUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.List;

/**
 * A base class that manages text layout in visual elements on
 * the screen.
 * <p>For text that will be edited, use a {@link DynamicLayout},
 * which will be updated as the text changes.
 * For text that will not change, use a {@link StaticLayout}.
 */
public abstract class Layout {
    /** @hide */
    @IntDef(prefix = { "BREAK_STRATEGY_" }, value = {
            LineBreaker.BREAK_STRATEGY_SIMPLE,
            LineBreaker.BREAK_STRATEGY_HIGH_QUALITY,
            LineBreaker.BREAK_STRATEGY_BALANCED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BreakStrategy {}

    /**
     * Value for break strategy indicating simple line breaking. Automatic hyphens are not added
     * (though soft hyphens are respected), and modifying text generally doesn't affect the layout
     * before it (which yields a more consistent user experience when editing), but layout may not
     * be the highest quality.
     */
    public static final int BREAK_STRATEGY_SIMPLE = LineBreaker.BREAK_STRATEGY_SIMPLE;

    /**
     * Value for break strategy indicating high quality line breaking, including automatic
     * hyphenation and doing whole-paragraph optimization of line breaks.
     */
    public static final int BREAK_STRATEGY_HIGH_QUALITY = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY;

    /**
     * Value for break strategy indicating balanced line breaking. The breaks are chosen to
     * make all lines as close to the same length as possible, including automatic hyphenation.
     */
    public static final int BREAK_STRATEGY_BALANCED = LineBreaker.BREAK_STRATEGY_BALANCED;

    /** @hide */
    @IntDef(prefix = { "HYPHENATION_FREQUENCY_" }, value = {
            HYPHENATION_FREQUENCY_NORMAL,
            HYPHENATION_FREQUENCY_NORMAL_FAST,
            HYPHENATION_FREQUENCY_FULL,
            HYPHENATION_FREQUENCY_FULL_FAST,
            HYPHENATION_FREQUENCY_NONE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface HyphenationFrequency {}

    /**
     * Value for hyphenation frequency indicating no automatic hyphenation. Useful
     * for backward compatibility, and for cases where the automatic hyphenation algorithm results
     * in incorrect hyphenation. Mid-word breaks may still happen when a word is wider than the
     * layout and there is otherwise no valid break. Soft hyphens are ignored and will not be used
     * as suggestions for potential line breaks.
     */
    public static final int HYPHENATION_FREQUENCY_NONE = 0;

    /**
     * Value for hyphenation frequency indicating a light amount of automatic hyphenation, which
     * is a conservative default. Useful for informal cases, such as short sentences or chat
     * messages.
     */
    public static final int HYPHENATION_FREQUENCY_NORMAL = 1;

    /**
     * Value for hyphenation frequency indicating the full amount of automatic hyphenation, typical
     * in typography. Useful for running text and where it's important to put the maximum amount of
     * text in a screen with limited space.
     */
    public static final int HYPHENATION_FREQUENCY_FULL = 2;

    /**
     * Value for hyphenation frequency indicating a light amount of automatic hyphenation with
     * using faster algorithm.
     *
     * This option is useful for informal cases, such as short sentences or chat messages. To make
     * text rendering faster with hyphenation, this algorithm ignores some hyphen character related
     * typographic features, e.g. kerning.
     */
    public static final int HYPHENATION_FREQUENCY_NORMAL_FAST = 3;
    /**
     * Value for hyphenation frequency indicating the full amount of automatic hyphenation with
     * using faster algorithm.
     *
     * This option is useful for running text and where it's important to put the maximum amount of
     * text in a screen with limited space. To make text rendering faster with hyphenation, this
     * algorithm ignores some hyphen character related typographic features, e.g. kerning.
     */
    public static final int HYPHENATION_FREQUENCY_FULL_FAST = 4;

    private static final ParagraphStyle[] NO_PARA_SPANS =
        ArrayUtils.emptyArray(ParagraphStyle.class);

    /** @hide */
    @IntDef(prefix = { "JUSTIFICATION_MODE_" }, value = {
            LineBreaker.JUSTIFICATION_MODE_NONE,
            LineBreaker.JUSTIFICATION_MODE_INTER_WORD
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface JustificationMode {}

    /**
     * Value for justification mode indicating no justification.
     */
    public static final int JUSTIFICATION_MODE_NONE = LineBreaker.JUSTIFICATION_MODE_NONE;

    /**
     * Value for justification mode indicating the text is justified by stretching word spacing.
     */
    public static final int JUSTIFICATION_MODE_INTER_WORD =
            LineBreaker.JUSTIFICATION_MODE_INTER_WORD;

    /*
     * Line spacing multiplier for default line spacing.
     */
    public static final float DEFAULT_LINESPACING_MULTIPLIER = 1.0f;

    /*
     * Line spacing addition for default line spacing.
     */
    public static final float DEFAULT_LINESPACING_ADDITION = 0.0f;

    /**
     * Strategy which considers a text segment to be inside a rectangle area if the segment bounds
     * intersect the rectangle.
     */
    @NonNull
    public static final TextInclusionStrategy INCLUSION_STRATEGY_ANY_OVERLAP =
            RectF::intersects;

    /**
     * Strategy which considers a text segment to be inside a rectangle area if the center of the
     * segment bounds is inside the rectangle.
     */
    @NonNull
    public static final TextInclusionStrategy INCLUSION_STRATEGY_CONTAINS_CENTER =
            (segmentBounds, area) ->
                    area.contains(segmentBounds.centerX(), segmentBounds.centerY());

    /**
     * Strategy which considers a text segment to be inside a rectangle area if the segment bounds
     * are completely contained within the rectangle.
     */
    @NonNull
    public static final TextInclusionStrategy INCLUSION_STRATEGY_CONTAINS_ALL =
            (segmentBounds, area) -> area.contains(segmentBounds);

    /**
     * Return how wide a layout must be in order to display the specified text with one line per
     * paragraph.
     *
     * <p>As of O, Uses
     * {@link TextDirectionHeuristics#FIRSTSTRONG_LTR} as the default text direction heuristics. In
     * the earlier versions uses {@link TextDirectionHeuristics#LTR} as the default.</p>
     */
    public static float getDesiredWidth(CharSequence source,
                                        TextPaint paint) {
        return getDesiredWidth(source, 0, source.length(), paint);
    }

    /**
     * Return how wide a layout must be in order to display the specified text slice with one
     * line per paragraph.
     *
     * <p>As of O, Uses
     * {@link TextDirectionHeuristics#FIRSTSTRONG_LTR} as the default text direction heuristics. In
     * the earlier versions uses {@link TextDirectionHeuristics#LTR} as the default.</p>
     */
    public static float getDesiredWidth(CharSequence source, int start, int end, TextPaint paint) {
        return getDesiredWidth(source, start, end, paint, TextDirectionHeuristics.FIRSTSTRONG_LTR);
    }

    /**
     * Return how wide a layout must be in order to display the
     * specified text slice with one line per paragraph.
     *
     * @hide
     */
    public static float getDesiredWidth(CharSequence source, int start, int end, TextPaint paint,
            TextDirectionHeuristic textDir) {
        return getDesiredWidthWithLimit(source, start, end, paint, textDir, Float.MAX_VALUE, false);
    }
    /**
     * Return how wide a layout must be in order to display the
     * specified text slice with one line per paragraph.
     *
     * If the measured width exceeds given limit, returns limit value instead.
     * @hide
     */
    public static float getDesiredWidthWithLimit(CharSequence source, int start, int end,
            TextPaint paint, TextDirectionHeuristic textDir, float upperLimit,
            boolean useBoundsForWidth) {
        float need = 0;

        int next;
        for (int i = start; i <= end; i = next) {
            next = TextUtils.indexOf(source, '\n', i, end);

            if (next < 0)
                next = end;

            // note, omits trailing paragraph char
            float w = measurePara(paint, source, i, next, textDir, useBoundsForWidth);
            if (w > upperLimit) {
                return upperLimit;
            }

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
        this(text, paint, width, align, TextDirectionHeuristics.FIRSTSTRONG_LTR,
                spacingMult, spacingAdd, false, false, 0, null, Integer.MAX_VALUE,
                BREAK_STRATEGY_SIMPLE, HYPHENATION_FREQUENCY_NONE, null, null,
                JUSTIFICATION_MODE_NONE, LineBreakConfig.NONE, false, null);
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
     * @param textDir a text direction heuristic.
     * @param spacingMult factor by which to scale the font size to get the
     * default line spacing
     * @param spacingAdd amount to add to the default line spacing
     * @param includePad true for enabling including font padding
     * @param fallbackLineSpacing true for enabling fallback line spacing
     * @param ellipsizedWidth width as used for ellipsizing purpose
     * @param ellipsize an ellipsize option
     * @param maxLines a maximum number of lines.
     * @param breakStrategy a break strategy.
     * @param hyphenationFrequency a hyphenation frequency
     * @param leftIndents a visually left margins
     * @param rightIndents a visually right margins
     * @param justificationMode a justification mode
     * @param lineBreakConfig a line break config
     *
     * @hide
     */
    protected Layout(
            CharSequence text,
            TextPaint paint,
            int width,
            Alignment align,
            TextDirectionHeuristic textDir,
            float spacingMult,
            float spacingAdd,
            boolean includePad,
            boolean fallbackLineSpacing,
            int ellipsizedWidth,
            TextUtils.TruncateAt ellipsize,
            int maxLines,
            int breakStrategy,
            int hyphenationFrequency,
            int[] leftIndents,
            int[] rightIndents,
            int justificationMode,
            LineBreakConfig lineBreakConfig,
            boolean useBoundsForWidth,
            Paint.FontMetrics minimumFontMetrics
    ) {

        if (width < 0)
            throw new IllegalArgumentException("Layout: " + width + " < 0");

        // Ensure paint doesn't have baselineShift set.
        // While normally we don't modify the paint the user passed in,
        // we were already doing this in Styled.drawUniformRun with both
        // baselineShift and bgColor.  We probably should reevaluate bgColor.
        if (paint != null) {
            paint.bgColor = 0;
            paint.baselineShift = 0;
        }

        mText = text;
        mPaint = paint;
        mWidth = width;
        mAlignment = align;
        mSpacingMult = spacingMult;
        mSpacingAdd = spacingAdd;
        mSpannedText = text instanceof Spanned;
        mTextDir = textDir;
        mIncludePad = includePad;
        mFallbackLineSpacing = fallbackLineSpacing;
        mEllipsizedWidth = ellipsize == null ? width : ellipsizedWidth;
        mEllipsize = ellipsize;
        mMaxLines = maxLines;
        mBreakStrategy = breakStrategy;
        mHyphenationFrequency = hyphenationFrequency;
        mLeftIndents = leftIndents;
        mRightIndents = rightIndents;
        mJustificationMode = justificationMode;
        mLineBreakConfig = lineBreakConfig;
        mUseBoundsForWidth = useBoundsForWidth;
        mMinimumFontMetrics = minimumFontMetrics;
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
     *
     * This API draws background first, then draws text on top of it.
     *
     * @see #draw(Canvas, List, List, Path, Paint, int)
     */
    public void draw(Canvas c) {
        draw(c, (Path) null, (Paint) null, 0);
    }

    /**
     * Draw this Layout on the specified canvas, with the highlight path drawn
     * between the background and the text.
     *
     * @param canvas the canvas
     * @param selectionHighlight the path of the selection highlight or cursor; can be null
     * @param selectionHighlightPaint the paint for the selection highlight
     * @param cursorOffsetVertical the amount to temporarily translate the
     *        canvas while rendering the highlight
     *
     * @see #draw(Canvas, List, List, Path, Paint, int)
     */
    public void draw(
            Canvas canvas, Path selectionHighlight,
            Paint selectionHighlightPaint, int cursorOffsetVertical) {
        draw(canvas, null, null, selectionHighlight, selectionHighlightPaint, cursorOffsetVertical);
    }

    /**
     * Draw this layout on the specified canvas.
     *
     * This API draws background first, then draws highlight paths on top of it, then draws
     * selection or cursor, then finally draws text on top of it.
     *
     * @see #drawBackground(Canvas)
     * @see #drawText(Canvas)
     *
     * @param canvas the canvas
     * @param highlightPaths the path of the highlights. The highlightPaths and highlightPaints must
     *                      have the same length and aligned in the same order. For example, the
     *                      paint of the n-th of the highlightPaths should be stored at the n-th of
     *                      highlightPaints.
     * @param highlightPaints the paints for the highlights. The highlightPaths and highlightPaints
     *                        must have the same length and aligned in the same order. For example,
     *                        the paint of the n-th of the highlightPaths should be stored at the
     *                        n-th of highlightPaints.
     * @param selectionPath the selection or cursor path
     * @param selectionPaint the paint for the selection or cursor.
     * @param cursorOffsetVertical the amount to temporarily translate the canvas while rendering
     *                            the highlight
     */
    public void draw(@NonNull Canvas canvas,
            @Nullable List<Path> highlightPaths,
            @Nullable List<Paint> highlightPaints,
            @Nullable Path selectionPath,
            @Nullable Paint selectionPaint,
            int cursorOffsetVertical) {
        if (mUseBoundsForWidth) {
            canvas.save();
            RectF drawingRect = computeDrawingBoundingBox();
            if (drawingRect.left < 0) {
                canvas.translate(-drawingRect.left, 0);
            }
        }
        final long lineRange = getLineRangeForDraw(canvas);
        int firstLine = TextUtils.unpackRangeStartFromLong(lineRange);
        int lastLine = TextUtils.unpackRangeEndFromLong(lineRange);
        if (lastLine < 0) return;

        drawWithoutText(canvas, highlightPaths, highlightPaints, selectionPath, selectionPaint,
                cursorOffsetVertical, firstLine, lastLine);
        drawText(canvas, firstLine, lastLine);
        if (mUseBoundsForWidth) {
            canvas.restore();
        }
    }

    /**
     * Draw text part of this layout.
     *
     * Different from {@link #draw(Canvas, List, List, Path, Paint, int)} API, this API only draws
     * text part, not drawing highlights, selections, or backgrounds.
     *
     * @see #draw(Canvas, List, List, Path, Paint, int)
     * @see #drawBackground(Canvas)
     *
     * @param canvas the canvas
     */
    public void drawText(@NonNull Canvas canvas) {
        final long lineRange = getLineRangeForDraw(canvas);
        int firstLine = TextUtils.unpackRangeStartFromLong(lineRange);
        int lastLine = TextUtils.unpackRangeEndFromLong(lineRange);
        if (lastLine < 0) return;
        drawText(canvas, firstLine, lastLine);
    }

    /**
     * Draw background of this layout.
     *
     * Different from {@link #draw(Canvas, List, List, Path, Paint, int)} API, this API only draws
     * background, not drawing text, highlights or selections. The background here is drawn by
     * {@link LineBackgroundSpan} attached to the text.
     *
     * @see #draw(Canvas, List, List, Path, Paint, int)
     * @see #drawText(Canvas)
     *
     * @param canvas the canvas
     */
    public void drawBackground(@NonNull Canvas canvas) {
        final long lineRange = getLineRangeForDraw(canvas);
        int firstLine = TextUtils.unpackRangeStartFromLong(lineRange);
        int lastLine = TextUtils.unpackRangeEndFromLong(lineRange);
        if (lastLine < 0) return;
        drawBackground(canvas, firstLine, lastLine);
    }

    /**
     * @hide public for Editor.java
     */
    public void drawWithoutText(
            @NonNull Canvas canvas,
            @Nullable List<Path> highlightPaths,
            @Nullable List<Paint> highlightPaints,
            @Nullable Path selectionPath,
            @Nullable Paint selectionPaint,
            int cursorOffsetVertical,
            int firstLine,
            int lastLine) {
        drawBackground(canvas, firstLine, lastLine);
        if (highlightPaths == null && highlightPaints == null) {
            return;
        }
        if (cursorOffsetVertical != 0) canvas.translate(0, cursorOffsetVertical);
        try {
            if (highlightPaths != null) {
                if (highlightPaints == null) {
                    throw new IllegalArgumentException(
                            "if highlight is specified, highlightPaint must be specified.");
                }
                if (highlightPaints.size() != highlightPaths.size()) {
                    throw new IllegalArgumentException(
                            "The highlight path size is different from the size of highlight"
                                    + " paints");
                }
                for (int i = 0; i < highlightPaths.size(); ++i) {
                    final Path highlight = highlightPaths.get(i);
                    final Paint highlightPaint = highlightPaints.get(i);
                    if (highlight != null) {
                        canvas.drawPath(highlight, highlightPaint);
                    }
                }
            }

            if (selectionPath != null) {
                canvas.drawPath(selectionPath, selectionPaint);
            }
        } finally {
            if (cursorOffsetVertical != 0) canvas.translate(0, -cursorOffsetVertical);
        }
    }

    private boolean isJustificationRequired(int lineNum) {
        if (mJustificationMode == JUSTIFICATION_MODE_NONE) return false;
        final int lineEnd = getLineEnd(lineNum);
        return lineEnd < mText.length() && mText.charAt(lineEnd - 1) != '\n';
    }

    private float getJustifyWidth(int lineNum) {
        Alignment paraAlign = mAlignment;

        int left = 0;
        int right = mWidth;

        final int dir = getParagraphDirection(lineNum);

        ParagraphStyle[] spans = NO_PARA_SPANS;
        if (mSpannedText) {
            Spanned sp = (Spanned) mText;
            final int start = getLineStart(lineNum);

            final boolean isFirstParaLine = (start == 0 || mText.charAt(start - 1) == '\n');

            if (isFirstParaLine) {
                final int spanEnd = sp.nextSpanTransition(start, mText.length(),
                        ParagraphStyle.class);
                spans = getParagraphSpans(sp, start, spanEnd, ParagraphStyle.class);

                for (int n = spans.length - 1; n >= 0; n--) {
                    if (spans[n] instanceof AlignmentSpan) {
                        paraAlign = ((AlignmentSpan) spans[n]).getAlignment();
                        break;
                    }
                }
            }

            final int length = spans.length;
            boolean useFirstLineMargin = isFirstParaLine;
            for (int n = 0; n < length; n++) {
                if (spans[n] instanceof LeadingMarginSpan2) {
                    int count = ((LeadingMarginSpan2) spans[n]).getLeadingMarginLineCount();
                    int startLine = getLineForOffset(sp.getSpanStart(spans[n]));
                    if (lineNum < startLine + count) {
                        useFirstLineMargin = true;
                        break;
                    }
                }
            }
            for (int n = 0; n < length; n++) {
                if (spans[n] instanceof LeadingMarginSpan) {
                    LeadingMarginSpan margin = (LeadingMarginSpan) spans[n];
                    if (dir == DIR_RIGHT_TO_LEFT) {
                        right -= margin.getLeadingMargin(useFirstLineMargin);
                    } else {
                        left += margin.getLeadingMargin(useFirstLineMargin);
                    }
                }
            }
        }

        final Alignment align;
        if (paraAlign == Alignment.ALIGN_LEFT) {
            align = (dir == DIR_LEFT_TO_RIGHT) ?  Alignment.ALIGN_NORMAL : Alignment.ALIGN_OPPOSITE;
        } else if (paraAlign == Alignment.ALIGN_RIGHT) {
            align = (dir == DIR_LEFT_TO_RIGHT) ?  Alignment.ALIGN_OPPOSITE : Alignment.ALIGN_NORMAL;
        } else {
            align = paraAlign;
        }

        final int indentWidth;
        if (align == Alignment.ALIGN_NORMAL) {
            if (dir == DIR_LEFT_TO_RIGHT) {
                indentWidth = getIndentAdjust(lineNum, Alignment.ALIGN_LEFT);
            } else {
                indentWidth = -getIndentAdjust(lineNum, Alignment.ALIGN_RIGHT);
            }
        } else if (align == Alignment.ALIGN_OPPOSITE) {
            if (dir == DIR_LEFT_TO_RIGHT) {
                indentWidth = -getIndentAdjust(lineNum, Alignment.ALIGN_RIGHT);
            } else {
                indentWidth = getIndentAdjust(lineNum, Alignment.ALIGN_LEFT);
            }
        } else { // Alignment.ALIGN_CENTER
            indentWidth = getIndentAdjust(lineNum, Alignment.ALIGN_CENTER);
        }

        return right - left - indentWidth;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void drawText(Canvas canvas, int firstLine, int lastLine) {
        int previousLineBottom = getLineTop(firstLine);
        int previousLineEnd = getLineStart(firstLine);
        ParagraphStyle[] spans = NO_PARA_SPANS;
        int spanEnd = 0;
        final TextPaint paint = mWorkPaint;
        paint.set(mPaint);
        CharSequence buf = mText;

        Alignment paraAlign = mAlignment;
        TabStops tabStops = null;
        boolean tabStopsIsInitialized = false;

        TextLine tl = TextLine.obtain();

        // Draw the lines, one at a time.
        // The baseline is the top of the following line minus the current line's descent.
        for (int lineNum = firstLine; lineNum <= lastLine; lineNum++) {
            int start = previousLineEnd;
            previousLineEnd = getLineStart(lineNum + 1);
            final boolean justify = isJustificationRequired(lineNum);
            int end = getLineVisibleEnd(lineNum, start, previousLineEnd);
            paint.setStartHyphenEdit(getStartHyphenEdit(lineNum));
            paint.setEndHyphenEdit(getEndHyphenEdit(lineNum));

            int ltop = previousLineBottom;
            int lbottom = getLineTop(lineNum + 1);
            previousLineBottom = lbottom;
            int lbaseline = lbottom - getLineDescent(lineNum);

            int dir = getParagraphDirection(lineNum);
            int left = 0;
            int right = mWidth;

            if (mSpannedText) {
                Spanned sp = (Spanned) buf;
                int textLength = buf.length();
                boolean isFirstParaLine = (start == 0 || buf.charAt(start - 1) == '\n');

                // New batch of paragraph styles, collect into spans array.
                // Compute the alignment, last alignment style wins.
                // Reset tabStops, we'll rebuild if we encounter a line with
                // tabs.
                // We expect paragraph spans to be relatively infrequent, use
                // spanEnd so that we can check less frequently.  Since
                // paragraph styles ought to apply to entire paragraphs, we can
                // just collect the ones present at the start of the paragraph.
                // If spanEnd is before the end of the paragraph, that's not
                // our problem.
                if (start >= spanEnd && (lineNum == firstLine || isFirstParaLine)) {
                    spanEnd = sp.nextSpanTransition(start, textLength,
                                                    ParagraphStyle.class);
                    spans = getParagraphSpans(sp, start, spanEnd, ParagraphStyle.class);

                    paraAlign = mAlignment;
                    for (int n = spans.length - 1; n >= 0; n--) {
                        if (spans[n] instanceof AlignmentSpan) {
                            paraAlign = ((AlignmentSpan) spans[n]).getAlignment();
                            break;
                        }
                    }

                    tabStopsIsInitialized = false;
                }

                // Draw all leading margin spans.  Adjust left or right according
                // to the paragraph direction of the line.
                final int length = spans.length;
                boolean useFirstLineMargin = isFirstParaLine;
                for (int n = 0; n < length; n++) {
                    if (spans[n] instanceof LeadingMarginSpan2) {
                        int count = ((LeadingMarginSpan2) spans[n]).getLeadingMarginLineCount();
                        int startLine = getLineForOffset(sp.getSpanStart(spans[n]));
                        // if there is more than one LeadingMarginSpan2, use
                        // the count that is greatest
                        if (lineNum < startLine + count) {
                            useFirstLineMargin = true;
                            break;
                        }
                    }
                }
                for (int n = 0; n < length; n++) {
                    if (spans[n] instanceof LeadingMarginSpan) {
                        LeadingMarginSpan margin = (LeadingMarginSpan) spans[n];
                        if (dir == DIR_RIGHT_TO_LEFT) {
                            margin.drawLeadingMargin(canvas, paint, right, dir, ltop,
                                                     lbaseline, lbottom, buf,
                                                     start, end, isFirstParaLine, this);
                            right -= margin.getLeadingMargin(useFirstLineMargin);
                        } else {
                            margin.drawLeadingMargin(canvas, paint, left, dir, ltop,
                                                     lbaseline, lbottom, buf,
                                                     start, end, isFirstParaLine, this);
                            left += margin.getLeadingMargin(useFirstLineMargin);
                        }
                    }
                }
            }

            boolean hasTab = getLineContainsTab(lineNum);
            // Can't tell if we have tabs for sure, currently
            if (hasTab && !tabStopsIsInitialized) {
                if (tabStops == null) {
                    tabStops = new TabStops(TAB_INCREMENT, spans);
                } else {
                    tabStops.reset(TAB_INCREMENT, spans);
                }
                tabStopsIsInitialized = true;
            }

            // Determine whether the line aligns to normal, opposite, or center.
            Alignment align = paraAlign;
            if (align == Alignment.ALIGN_LEFT) {
                align = (dir == DIR_LEFT_TO_RIGHT) ?
                    Alignment.ALIGN_NORMAL : Alignment.ALIGN_OPPOSITE;
            } else if (align == Alignment.ALIGN_RIGHT) {
                align = (dir == DIR_LEFT_TO_RIGHT) ?
                    Alignment.ALIGN_OPPOSITE : Alignment.ALIGN_NORMAL;
            }

            int x;
            final int indentWidth;
            if (align == Alignment.ALIGN_NORMAL) {
                if (dir == DIR_LEFT_TO_RIGHT) {
                    indentWidth = getIndentAdjust(lineNum, Alignment.ALIGN_LEFT);
                    x = left + indentWidth;
                } else {
                    indentWidth = -getIndentAdjust(lineNum, Alignment.ALIGN_RIGHT);
                    x = right - indentWidth;
                }
            } else {
                int max = (int)getLineExtent(lineNum, tabStops, false);
                if (align == Alignment.ALIGN_OPPOSITE) {
                    if (dir == DIR_LEFT_TO_RIGHT) {
                        indentWidth = -getIndentAdjust(lineNum, Alignment.ALIGN_RIGHT);
                        x = right - max - indentWidth;
                    } else {
                        indentWidth = getIndentAdjust(lineNum, Alignment.ALIGN_LEFT);
                        x = left - max + indentWidth;
                    }
                } else { // Alignment.ALIGN_CENTER
                    indentWidth = getIndentAdjust(lineNum, Alignment.ALIGN_CENTER);
                    max = max & ~1;
                    x = ((right + left - max) >> 1) + indentWidth;
                }
            }

            Directions directions = getLineDirections(lineNum);
            if (directions == DIRS_ALL_LEFT_TO_RIGHT && !mSpannedText && !hasTab && !justify) {
                // XXX: assumes there's nothing additional to be done
                canvas.drawText(buf, start, end, x, lbaseline, paint);
            } else {
                tl.set(paint, buf, start, end, dir, directions, hasTab, tabStops,
                        getEllipsisStart(lineNum),
                        getEllipsisStart(lineNum) + getEllipsisCount(lineNum),
                        isFallbackLineSpacingEnabled());
                if (justify) {
                    tl.justify(right - left - indentWidth);
                }
                tl.draw(canvas, x, ltop, lbaseline, lbottom);
            }
        }

        TextLine.recycle(tl);
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void drawBackground(
            @NonNull Canvas canvas,
            int firstLine, int lastLine) {
        // First, draw LineBackgroundSpans.
        // LineBackgroundSpans know nothing about the alignment, margins, or
        // direction of the layout or line.  XXX: Should they?
        // They are evaluated at each line.
        if (mSpannedText) {
            if (mLineBackgroundSpans == null) {
                mLineBackgroundSpans = new SpanSet<LineBackgroundSpan>(LineBackgroundSpan.class);
            }

            Spanned buffer = (Spanned) mText;
            int textLength = buffer.length();
            mLineBackgroundSpans.init(buffer, 0, textLength);

            if (mLineBackgroundSpans.numberOfSpans > 0) {
                int previousLineBottom = getLineTop(firstLine);
                int previousLineEnd = getLineStart(firstLine);
                ParagraphStyle[] spans = NO_PARA_SPANS;
                int spansLength = 0;
                TextPaint paint = mPaint;
                int spanEnd = 0;
                final int width = mWidth;
                for (int i = firstLine; i <= lastLine; i++) {
                    int start = previousLineEnd;
                    int end = getLineStart(i + 1);
                    previousLineEnd = end;

                    int ltop = previousLineBottom;
                    int lbottom = getLineTop(i + 1);
                    previousLineBottom = lbottom;
                    int lbaseline = lbottom - getLineDescent(i);

                    if (end >= spanEnd) {
                        // These should be infrequent, so we'll use this so that
                        // we don't have to check as often.
                        spanEnd = mLineBackgroundSpans.getNextTransition(start, textLength);
                        // All LineBackgroundSpans on a line contribute to its background.
                        spansLength = 0;
                        // Duplication of the logic of getParagraphSpans
                        if (start != end || start == 0) {
                            // Equivalent to a getSpans(start, end), but filling the 'spans' local
                            // array instead to reduce memory allocation
                            for (int j = 0; j < mLineBackgroundSpans.numberOfSpans; j++) {
                                // equal test is valid since both intervals are not empty by
                                // construction
                                if (mLineBackgroundSpans.spanStarts[j] >= end ||
                                        mLineBackgroundSpans.spanEnds[j] <= start) continue;
                                spans = GrowingArrayUtils.append(
                                        spans, spansLength, mLineBackgroundSpans.spans[j]);
                                spansLength++;
                            }
                        }
                    }

                    for (int n = 0; n < spansLength; n++) {
                        LineBackgroundSpan lineBackgroundSpan = (LineBackgroundSpan) spans[n];
                        lineBackgroundSpan.drawBackground(canvas, paint, 0, width,
                                ltop, lbaseline, lbottom,
                                buffer, start, end, i);
                    }
                }
            }
            mLineBackgroundSpans.recycle();
        }
    }

    /**
     * @param canvas
     * @return The range of lines that need to be drawn, possibly empty.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public long getLineRangeForDraw(Canvas canvas) {
        int dtop, dbottom;

        synchronized (sTempRect) {
            if (!canvas.getClipBounds(sTempRect)) {
                // Negative range end used as a special flag
                return TextUtils.packRangeInLong(0, -1);
            }

            dtop = sTempRect.top;
            dbottom = sTempRect.bottom;
        }

        final int top = Math.max(dtop, 0);
        final int bottom = Math.min(getLineTop(getLineCount()), dbottom);

        if (top >= bottom) return TextUtils.packRangeInLong(0, -1);
        return TextUtils.packRangeInLong(getLineForVertical(top), getLineForVertical(bottom));
    }

    /**
     * Return the start position of the line, given the left and right bounds of the margins.
     *
     * @param line the line index
     * @param left the left bounds (0, or leading margin if ltr para)
     * @param right the right bounds (width, minus leading margin if rtl para)
     * @return the start position of the line (to right of line if rtl para)
     */
    private int getLineStartPos(int line, int left, int right) {
        // Adjust the point at which to start rendering depending on the
        // alignment of the paragraph.
        Alignment align = getParagraphAlignment(line);
        int dir = getParagraphDirection(line);

        if (align == Alignment.ALIGN_LEFT) {
            align = (dir == DIR_LEFT_TO_RIGHT) ? Alignment.ALIGN_NORMAL : Alignment.ALIGN_OPPOSITE;
        } else if (align == Alignment.ALIGN_RIGHT) {
            align = (dir == DIR_LEFT_TO_RIGHT) ? Alignment.ALIGN_OPPOSITE : Alignment.ALIGN_NORMAL;
        }

        int x;
        if (align == Alignment.ALIGN_NORMAL) {
            if (dir == DIR_LEFT_TO_RIGHT) {
                x = left + getIndentAdjust(line, Alignment.ALIGN_LEFT);
            } else {
                x = right + getIndentAdjust(line, Alignment.ALIGN_RIGHT);
            }
        } else {
            TabStops tabStops = null;
            if (mSpannedText && getLineContainsTab(line)) {
                Spanned spanned = (Spanned) mText;
                int start = getLineStart(line);
                int spanEnd = spanned.nextSpanTransition(start, spanned.length(),
                        TabStopSpan.class);
                TabStopSpan[] tabSpans = getParagraphSpans(spanned, start, spanEnd,
                        TabStopSpan.class);
                if (tabSpans.length > 0) {
                    tabStops = new TabStops(TAB_INCREMENT, tabSpans);
                }
            }
            int max = (int)getLineExtent(line, tabStops, false);
            if (align == Alignment.ALIGN_OPPOSITE) {
                if (dir == DIR_LEFT_TO_RIGHT) {
                    x = right - max + getIndentAdjust(line, Alignment.ALIGN_RIGHT);
                } else {
                    // max is negative here
                    x = left - max + getIndentAdjust(line, Alignment.ALIGN_LEFT);
                }
            } else { // Alignment.ALIGN_CENTER
                max = max & ~1;
                x = (left + right - max) >> 1 + getIndentAdjust(line, Alignment.ALIGN_CENTER);
            }
        }
        return x;
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
     * Return the total height of this layout.
     *
     * @param cap if true and max lines is set, returns the height of the layout at the max lines.
     *
     * @hide
     */
    public int getHeight(boolean cap) {
        return getHeight();
    }

    /**
     * Return the number of lines of text in this layout.
     */
    public abstract int getLineCount();

    /**
     * Get an actual bounding box that draws text content.
     *
     * Note that the {@link RectF#top} and {@link RectF#bottom} may be different from the
     * {@link Layout#getLineTop(int)} of the first line and {@link Layout#getLineBottom(int)} of
     * the last line. The line top and line bottom are calculated based on yMin/yMax or
     * ascent/descent value of font file. On the other hand, the drawing bounding boxes are
     * calculated based on actual glyphs used there.
     *
     * @return bounding rectangle
     */
    @NonNull
    @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
    public RectF computeDrawingBoundingBox() {
        float left = 0;
        float right = 0;
        float top = 0;
        float bottom = 0;
        TextLine tl = TextLine.obtain();
        RectF rectF = new RectF();
        for (int line = 0; line < getLineCount(); ++line) {
            final int start = getLineStart(line);
            final int end = getLineVisibleEnd(line);

            final boolean hasTabs = getLineContainsTab(line);
            TabStops tabStops = null;
            if (hasTabs && mText instanceof Spanned) {
                // Just checking this line should be good enough, tabs should be
                // consistent across all lines in a paragraph.
                TabStopSpan[] tabs = getParagraphSpans((Spanned) mText, start, end,
                        TabStopSpan.class);
                if (tabs.length > 0) {
                    tabStops = new TabStops(TAB_INCREMENT, tabs); // XXX should reuse
                }
            }
            final Directions directions = getLineDirections(line);
            // Returned directions can actually be null
            if (directions == null) {
                continue;
            }
            final int dir = getParagraphDirection(line);

            final TextPaint paint = mWorkPaint;
            paint.set(mPaint);
            paint.setStartHyphenEdit(getStartHyphenEdit(line));
            paint.setEndHyphenEdit(getEndHyphenEdit(line));
            tl.set(paint, mText, start, end, dir, directions, hasTabs, tabStops,
                    getEllipsisStart(line), getEllipsisStart(line) + getEllipsisCount(line),
                    isFallbackLineSpacingEnabled());
            if (isJustificationRequired(line)) {
                tl.justify(getJustifyWidth(line));
            }
            tl.metrics(null, rectF, false);

            float lineLeft = rectF.left;
            float lineRight = rectF.right;
            float lineTop = rectF.top + getLineBaseline(line);
            float lineBottom = rectF.bottom + getLineBaseline(line);
            if (getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT) {
                lineLeft += getWidth();
                lineRight += getWidth();
            }

            if (line == 0) {
                left = lineLeft;
                right = lineRight;
                top = lineTop;
                bottom = lineBottom;
            } else {
                left = Math.min(left, lineLeft);
                right = Math.max(right, lineRight);
                top = Math.min(top, lineTop);
                bottom = Math.max(bottom, lineBottom);
            }
        }
        TextLine.recycle(tl);
        return new RectF(left, top, right, bottom);
    }

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
     * characters that need to be handled specially, like tabs.
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
     * Returns the start hyphen edit for a line.
     *
     * @hide
     */
    public @Paint.StartHyphenEdit int getStartHyphenEdit(int line) {
        return Paint.START_HYPHEN_EDIT_NO_EDIT;
    }

    /**
     * Returns the end hyphen edit for a line.
     *
     * @hide
     */
    public @Paint.EndHyphenEdit int getEndHyphenEdit(int line) {
        return Paint.END_HYPHEN_EDIT_NO_EDIT;
    }

    /**
     * Returns the left indent for a line.
     *
     * @hide
     */
    public int getIndentAdjust(int line, Alignment alignment) {
        return 0;
    }

    /**
     * Returns true if the character at offset and the preceding character
     * are at different run levels (and thus there's a split caret).
     * @param offset the offset
     * @return true if at a level boundary
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isLevelBoundary(int offset) {
        int line = getLineForOffset(offset);
        Directions dirs = getLineDirections(line);
        if (dirs == DIRS_ALL_LEFT_TO_RIGHT || dirs == DIRS_ALL_RIGHT_TO_LEFT) {
            return false;
        }

        int[] runs = dirs.mDirections;
        int lineStart = getLineStart(line);
        int lineEnd = getLineEnd(line);
        if (offset == lineStart || offset == lineEnd) {
            int paraLevel = getParagraphDirection(line) == 1 ? 0 : 1;
            int runIndex = offset == lineStart ? 0 : runs.length - 2;
            return ((runs[runIndex + 1] >>> RUN_LEVEL_SHIFT) & RUN_LEVEL_MASK) != paraLevel;
        }

        offset -= lineStart;
        for (int i = 0; i < runs.length; i += 2) {
            if (offset == runs[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the character at offset is right to left (RTL).
     * @param offset the offset
     * @return true if the character is RTL, false if it is LTR
     */
    public boolean isRtlCharAt(int offset) {
        int line = getLineForOffset(offset);
        Directions dirs = getLineDirections(line);
        if (dirs == DIRS_ALL_LEFT_TO_RIGHT) {
            return false;
        }
        if (dirs == DIRS_ALL_RIGHT_TO_LEFT) {
            return  true;
        }
        int[] runs = dirs.mDirections;
        int lineStart = getLineStart(line);
        for (int i = 0; i < runs.length; i += 2) {
            int start = lineStart + runs[i];
            int limit = start + (runs[i+1] & RUN_LENGTH_MASK);
            if (offset >= start && offset < limit) {
                int level = (runs[i+1] >>> RUN_LEVEL_SHIFT) & RUN_LEVEL_MASK;
                return ((level & 1) != 0);
            }
        }
        // Should happen only if the offset is "out of bounds"
        return false;
    }

    /**
     * Returns the range of the run that the character at offset belongs to.
     * @param offset the offset
     * @return The range of the run
     * @hide
     */
    public long getRunRange(int offset) {
        int line = getLineForOffset(offset);
        Directions dirs = getLineDirections(line);
        if (dirs == DIRS_ALL_LEFT_TO_RIGHT || dirs == DIRS_ALL_RIGHT_TO_LEFT) {
            return TextUtils.packRangeInLong(0, getLineEnd(line));
        }
        int[] runs = dirs.mDirections;
        int lineStart = getLineStart(line);
        for (int i = 0; i < runs.length; i += 2) {
            int start = lineStart + runs[i];
            int limit = start + (runs[i+1] & RUN_LENGTH_MASK);
            if (offset >= start && offset < limit) {
                return TextUtils.packRangeInLong(start, limit);
            }
        }
        // Should happen only if the offset is "out of bounds"
        return TextUtils.packRangeInLong(0, getLineEnd(line));
    }

    /**
     * Checks if the trailing BiDi level should be used for an offset
     *
     * This method is useful when the offset is at the BiDi level transition point and determine
     * which run need to be used. For example, let's think about following input: (L* denotes
     * Left-to-Right characters, R* denotes Right-to-Left characters.)
     * Input (Logical Order): L1 L2 L3 R1 R2 R3 L4 L5 L6
     * Input (Display Order): L1 L2 L3 R3 R2 R1 L4 L5 L6
     *
     * Then, think about selecting the range (3, 6). The offset=3 and offset=6 are ambiguous here
     * since they are at the BiDi transition point.  In Android, the offset is considered to be
     * associated with the trailing run if the BiDi level of the trailing run is higher than of the
     * previous run.  In this case, the BiDi level of the input text is as follows:
     *
     * Input (Logical Order): L1 L2 L3 R1 R2 R3 L4 L5 L6
     *              BiDi Run: [ Run 0 ][ Run 1 ][ Run 2 ]
     *            BiDi Level:  0  0  0  1  1  1  0  0  0
     *
     * Thus, offset = 3 is part of Run 1 and this method returns true for offset = 3, since the BiDi
     * level of Run 1 is higher than the level of Run 0.  Similarly, the offset = 6 is a part of Run
     * 1 and this method returns false for the offset = 6 since the BiDi level of Run 1 is higher
     * than the level of Run 2.
     *
     * @returns true if offset is at the BiDi level transition point and trailing BiDi level is
     *          higher than previous BiDi level. See above for the detail.
     * @hide
     */
    @VisibleForTesting
    public boolean primaryIsTrailingPrevious(int offset) {
        int line = getLineForOffset(offset);
        int lineStart = getLineStart(line);
        int lineEnd = getLineEnd(line);
        int[] runs = getLineDirections(line).mDirections;

        int levelAt = -1;
        for (int i = 0; i < runs.length; i += 2) {
            int start = lineStart + runs[i];
            int limit = start + (runs[i+1] & RUN_LENGTH_MASK);
            if (limit > lineEnd) {
                limit = lineEnd;
            }
            if (offset >= start && offset < limit) {
                if (offset > start) {
                    // Previous character is at same level, so don't use trailing.
                    return false;
                }
                levelAt = (runs[i+1] >>> RUN_LEVEL_SHIFT) & RUN_LEVEL_MASK;
                break;
            }
        }
        if (levelAt == -1) {
            // Offset was limit of line.
            levelAt = getParagraphDirection(line) == 1 ? 0 : 1;
        }

        // At level boundary, check previous level.
        int levelBefore = -1;
        if (offset == lineStart) {
            levelBefore = getParagraphDirection(line) == 1 ? 0 : 1;
        } else {
            offset -= 1;
            for (int i = 0; i < runs.length; i += 2) {
                int start = lineStart + runs[i];
                int limit = start + (runs[i+1] & RUN_LENGTH_MASK);
                if (limit > lineEnd) {
                    limit = lineEnd;
                }
                if (offset >= start && offset < limit) {
                    levelBefore = (runs[i+1] >>> RUN_LEVEL_SHIFT) & RUN_LEVEL_MASK;
                    break;
                }
            }
        }

        return levelBefore < levelAt;
    }

    /**
     * Computes in linear time the results of calling
     * #primaryIsTrailingPrevious for all offsets on a line.
     * @param line The line giving the offsets we compute the information for
     * @return The array of results, indexed from 0, where 0 corresponds to the line start offset
     * @hide
     */
    @VisibleForTesting
    public boolean[] primaryIsTrailingPreviousAllLineOffsets(int line) {
        int lineStart = getLineStart(line);
        int lineEnd = getLineEnd(line);
        int[] runs = getLineDirections(line).mDirections;

        boolean[] trailing = new boolean[lineEnd - lineStart + 1];

        byte[] level = new byte[lineEnd - lineStart + 1];
        for (int i = 0; i < runs.length; i += 2) {
            int start = lineStart + runs[i];
            int limit = start + (runs[i + 1] & RUN_LENGTH_MASK);
            if (limit > lineEnd) {
                limit = lineEnd;
            }
            if (limit == start) {
                continue;
            }
            level[limit - lineStart - 1] =
                    (byte) ((runs[i + 1] >>> RUN_LEVEL_SHIFT) & RUN_LEVEL_MASK);
        }

        for (int i = 0; i < runs.length; i += 2) {
            int start = lineStart + runs[i];
            byte currentLevel = (byte) ((runs[i + 1] >>> RUN_LEVEL_SHIFT) & RUN_LEVEL_MASK);
            trailing[start - lineStart] = currentLevel > (start == lineStart
                    ? (getParagraphDirection(line) == 1 ? 0 : 1)
                    : level[start - lineStart - 1]);
        }

        return trailing;
    }

    /**
     * Get the primary horizontal position for the specified text offset.
     * This is the location where a new character would be inserted in
     * the paragraph's primary direction.
     */
    public float getPrimaryHorizontal(int offset) {
        return getPrimaryHorizontal(offset, false /* not clamped */);
    }

    /**
     * Get the primary horizontal position for the specified text offset, but
     * optionally clamp it so that it doesn't exceed the width of the layout.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public float getPrimaryHorizontal(int offset, boolean clamped) {
        boolean trailing = primaryIsTrailingPrevious(offset);
        return getHorizontal(offset, trailing, clamped);
    }

    /**
     * Get the secondary horizontal position for the specified text offset.
     * This is the location where a new character would be inserted in
     * the direction other than the paragraph's primary direction.
     */
    public float getSecondaryHorizontal(int offset) {
        return getSecondaryHorizontal(offset, false /* not clamped */);
    }

    /**
     * Get the secondary horizontal position for the specified text offset, but
     * optionally clamp it so that it doesn't exceed the width of the layout.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public float getSecondaryHorizontal(int offset, boolean clamped) {
        boolean trailing = primaryIsTrailingPrevious(offset);
        return getHorizontal(offset, !trailing, clamped);
    }

    private float getHorizontal(int offset, boolean primary) {
        return primary ? getPrimaryHorizontal(offset) : getSecondaryHorizontal(offset);
    }

    private float getHorizontal(int offset, boolean trailing, boolean clamped) {
        int line = getLineForOffset(offset);

        return getHorizontal(offset, trailing, line, clamped);
    }

    private float getHorizontal(int offset, boolean trailing, int line, boolean clamped) {
        int start = getLineStart(line);
        int end = getLineEnd(line);
        int dir = getParagraphDirection(line);
        boolean hasTab = getLineContainsTab(line);
        Directions directions = getLineDirections(line);

        TabStops tabStops = null;
        if (hasTab && mText instanceof Spanned) {
            // Just checking this line should be good enough, tabs should be
            // consistent across all lines in a paragraph.
            TabStopSpan[] tabs = getParagraphSpans((Spanned) mText, start, end, TabStopSpan.class);
            if (tabs.length > 0) {
                tabStops = new TabStops(TAB_INCREMENT, tabs); // XXX should reuse
            }
        }

        TextLine tl = TextLine.obtain();
        tl.set(mPaint, mText, start, end, dir, directions, hasTab, tabStops,
                getEllipsisStart(line), getEllipsisStart(line) + getEllipsisCount(line),
                isFallbackLineSpacingEnabled());
        float wid = tl.measure(offset - start, trailing, null, null);
        TextLine.recycle(tl);

        if (clamped && wid > mWidth) {
            wid = mWidth;
        }
        int left = getParagraphLeft(line);
        int right = getParagraphRight(line);

        return getLineStartPos(line, left, right) + wid;
    }

    /**
     * Computes in linear time the results of calling #getHorizontal for all offsets on a line.
     *
     * @param line The line giving the offsets we compute information for
     * @param clamped Whether to clamp the results to the width of the layout
     * @param primary Whether the results should be the primary or the secondary horizontal
     * @return The array of results, indexed from 0, where 0 corresponds to the line start offset
     */
    private float[] getLineHorizontals(int line, boolean clamped, boolean primary) {
        int start = getLineStart(line);
        int end = getLineEnd(line);
        int dir = getParagraphDirection(line);
        boolean hasTab = getLineContainsTab(line);
        Directions directions = getLineDirections(line);

        TabStops tabStops = null;
        if (hasTab && mText instanceof Spanned) {
            // Just checking this line should be good enough, tabs should be
            // consistent across all lines in a paragraph.
            TabStopSpan[] tabs = getParagraphSpans((Spanned) mText, start, end, TabStopSpan.class);
            if (tabs.length > 0) {
                tabStops = new TabStops(TAB_INCREMENT, tabs); // XXX should reuse
            }
        }

        TextLine tl = TextLine.obtain();
        tl.set(mPaint, mText, start, end, dir, directions, hasTab, tabStops,
                getEllipsisStart(line), getEllipsisStart(line) + getEllipsisCount(line),
                isFallbackLineSpacingEnabled());
        boolean[] trailings = primaryIsTrailingPreviousAllLineOffsets(line);
        if (!primary) {
            for (int offset = 0; offset < trailings.length; ++offset) {
                trailings[offset] = !trailings[offset];
            }
        }
        float[] wid = tl.measureAllOffsets(trailings, null);
        TextLine.recycle(tl);

        if (clamped) {
            for (int offset = 0; offset < wid.length; ++offset) {
                if (wid[offset] > mWidth) {
                    wid[offset] = mWidth;
                }
            }
        }
        int left = getParagraphLeft(line);
        int right = getParagraphRight(line);

        int lineStartPos = getLineStartPos(line, left, right);
        float[] horizontal = new float[end - start + 1];
        for (int offset = 0; offset < horizontal.length; ++offset) {
            horizontal[offset] = lineStartPos + wid[offset];
        }
        return horizontal;
    }

    private void fillHorizontalBoundsForLine(int line, float[] horizontalBounds) {
        final int lineStart = getLineStart(line);
        final int lineEnd = getLineEnd(line);
        final int lineLength = lineEnd - lineStart;

        final int dir = getParagraphDirection(line);
        final Directions directions = getLineDirections(line);

        final boolean hasTab = getLineContainsTab(line);
        TabStops tabStops = null;
        if (hasTab && mText instanceof Spanned) {
            // Just checking this line should be good enough, tabs should be
            // consistent across all lines in a paragraph.
            TabStopSpan[] tabs =
                    getParagraphSpans((Spanned) mText, lineStart, lineEnd, TabStopSpan.class);
            if (tabs.length > 0) {
                tabStops = new TabStops(TAB_INCREMENT, tabs); // XXX should reuse
            }
        }

        final TextLine tl = TextLine.obtain();
        tl.set(mPaint, mText, lineStart, lineEnd, dir, directions, hasTab, tabStops,
                getEllipsisStart(line), getEllipsisStart(line) + getEllipsisCount(line),
                isFallbackLineSpacingEnabled());
        if (horizontalBounds == null || horizontalBounds.length < 2 * lineLength) {
            horizontalBounds = new float[2 * lineLength];
        }

        tl.measureAllBounds(horizontalBounds, null);
        TextLine.recycle(tl);
    }

    /**
     * Return the characters' bounds in the given range. The {@code bounds} array will be filled
     * starting from {@code boundsStart} (inclusive). The coordinates are in local text layout.
     *
     * @param start the start index to compute the character bounds, inclusive.
     * @param end the end index to compute the character bounds, exclusive.
     * @param bounds the array to fill in the character bounds. The array is divided into segments
     *               of four where each index in that segment represents left, top, right and
     *               bottom of the character.
     * @param boundsStart the inclusive start index in the array to start filling in the values
     *                    from.
     *
     * @throws IndexOutOfBoundsException if the range defined by {@code start} and {@code end}
     * exceeds the range of the text, or {@code bounds} doesn't have enough space to store the
     * result.
     * @throws IllegalArgumentException if {@code bounds} is null.
     */
    public void fillCharacterBounds(@IntRange(from = 0) int start, @IntRange(from = 0) int end,
            @NonNull float[] bounds, @IntRange(from = 0) int boundsStart) {
        if (start < 0 || end < start || end > mText.length()) {
            throw new IndexOutOfBoundsException("given range: " + start + ", " + end + " is "
                    + "out of the text range: 0, " + mText.length());
        }

        if (bounds == null) {
            throw  new IllegalArgumentException("bounds can't be null.");
        }

        final int neededLength = 4 * (end - start);
        if (neededLength > bounds.length - boundsStart) {
            throw new IndexOutOfBoundsException("bounds doesn't have enough space to store the "
                    + "result, needed: " + neededLength + " had: "
                    + (bounds.length - boundsStart));
        }

        if (start == end) {
            return;
        }

        final int startLine = getLineForOffset(start);
        final int endLine = getLineForOffset(end - 1);
        float[] horizontalBounds = null;
        for (int line = startLine; line <= endLine; ++line) {
            final int lineStart = getLineStart(line);
            final int lineEnd = getLineEnd(line);
            final int lineLength = lineEnd - lineStart;
            if (horizontalBounds == null || horizontalBounds.length < 2 * lineLength) {
                horizontalBounds = new float[2 * lineLength];
            }
            fillHorizontalBoundsForLine(line, horizontalBounds);

            final int lineLeft = getParagraphLeft(line);
            final int lineRight = getParagraphRight(line);
            final int lineStartPos = getLineStartPos(line, lineLeft, lineRight);

            final int lineTop = getLineTop(line);
            final int lineBottom = getLineBottom(line);

            final int startIndex = Math.max(start, lineStart);
            final int endIndex = Math.min(end, lineEnd);
            for (int index = startIndex; index < endIndex; ++index) {
                final int offset = index - lineStart;
                final float left = horizontalBounds[offset * 2] + lineStartPos;
                final float right = horizontalBounds[offset * 2 + 1] + lineStartPos;

                final int boundsIndex = boundsStart + 4 * (index - start);
                bounds[boundsIndex] = left;
                bounds[boundsIndex + 1] = lineTop;
                bounds[boundsIndex + 2] = right;
                bounds[boundsIndex + 3] = lineBottom;
            }
        }
    }

    /**
     * Get the leftmost position that should be exposed for horizontal
     * scrolling on the specified line.
     */
    public float getLineLeft(int line) {
        final int dir = getParagraphDirection(line);
        Alignment align = getParagraphAlignment(line);
        // Before Q, StaticLayout.Builder.setAlignment didn't check whether the input alignment
        // is null. And when it is null, the old behavior is the same as ALIGN_CENTER.
        // To keep consistency, we convert a null alignment to ALIGN_CENTER.
        if (align == null) {
            align = Alignment.ALIGN_CENTER;
        }

        // First convert combinations of alignment and direction settings to
        // three basic cases: ALIGN_LEFT, ALIGN_RIGHT and ALIGN_CENTER.
        // For unexpected cases, it will fallback to ALIGN_LEFT.
        final Alignment resultAlign;
        switch(align) {
            case ALIGN_NORMAL:
                resultAlign =
                        dir == DIR_RIGHT_TO_LEFT ? Alignment.ALIGN_RIGHT : Alignment.ALIGN_LEFT;
                break;
            case ALIGN_OPPOSITE:
                resultAlign =
                        dir == DIR_RIGHT_TO_LEFT ? Alignment.ALIGN_LEFT : Alignment.ALIGN_RIGHT;
                break;
            case ALIGN_CENTER:
                resultAlign = Alignment.ALIGN_CENTER;
                break;
            case ALIGN_RIGHT:
                resultAlign = Alignment.ALIGN_RIGHT;
                break;
            default: /* align == Alignment.ALIGN_LEFT */
                resultAlign = Alignment.ALIGN_LEFT;
        }

        // Here we must use getLineMax() to do the computation, because it maybe overridden by
        // derived class. And also note that line max equals the width of the text in that line
        // plus the leading margin.
        switch (resultAlign) {
            case ALIGN_CENTER:
                final int left = getParagraphLeft(line);
                final float max = getLineMax(line);
                // This computation only works when mWidth equals leadingMargin plus
                // the width of text in this line. If this condition doesn't meet anymore,
                // please change here too.
                return (float) Math.floor(left + (mWidth - max) / 2);
            case ALIGN_RIGHT:
                return mWidth - getLineMax(line);
            default: /* resultAlign == Alignment.ALIGN_LEFT */
                return 0;
        }
    }

    /**
     * Get the rightmost position that should be exposed for horizontal
     * scrolling on the specified line.
     */
    public float getLineRight(int line) {
        final int dir = getParagraphDirection(line);
        Alignment align = getParagraphAlignment(line);
        // Before Q, StaticLayout.Builder.setAlignment didn't check whether the input alignment
        // is null. And when it is null, the old behavior is the same as ALIGN_CENTER.
        // To keep consistency, we convert a null alignment to ALIGN_CENTER.
        if (align == null) {
            align = Alignment.ALIGN_CENTER;
        }

        final Alignment resultAlign;
        switch(align) {
            case ALIGN_NORMAL:
                resultAlign =
                        dir == DIR_RIGHT_TO_LEFT ? Alignment.ALIGN_RIGHT : Alignment.ALIGN_LEFT;
                break;
            case ALIGN_OPPOSITE:
                resultAlign =
                        dir == DIR_RIGHT_TO_LEFT ? Alignment.ALIGN_LEFT : Alignment.ALIGN_RIGHT;
                break;
            case ALIGN_CENTER:
                resultAlign = Alignment.ALIGN_CENTER;
                break;
            case ALIGN_RIGHT:
                resultAlign = Alignment.ALIGN_RIGHT;
                break;
            default: /* align == Alignment.ALIGN_LEFT */
                resultAlign = Alignment.ALIGN_LEFT;
        }

        switch (resultAlign) {
            case ALIGN_CENTER:
                final int right = getParagraphRight(line);
                final float max = getLineMax(line);
                // This computation only works when mWidth equals leadingMargin plus width of the
                // text in this line. If this condition doesn't meet anymore, please change here.
                return (float) Math.ceil(right - (mWidth - max) / 2);
            case ALIGN_RIGHT:
                return mWidth;
            default: /* resultAlign == Alignment.ALIGN_LEFT */
                return getLineMax(line);
        }
    }

    /**
     * Gets the unsigned horizontal extent of the specified line, including
     * leading margin indent, but excluding trailing whitespace.
     */
    public float getLineMax(int line) {
        float margin = getParagraphLeadingMargin(line);
        float signedExtent = getLineExtent(line, false);
        return margin + (signedExtent >= 0 ? signedExtent : -signedExtent);
    }

    /**
     * Gets the unsigned horizontal extent of the specified line, including
     * leading margin indent and trailing whitespace.
     */
    public float getLineWidth(int line) {
        float margin = getParagraphLeadingMargin(line);
        float signedExtent = getLineExtent(line, true);
        return margin + (signedExtent >= 0 ? signedExtent : -signedExtent);
    }

    /**
     * Like {@link #getLineExtent(int,TabStops,boolean)} but determines the
     * tab stops instead of using the ones passed in.
     * @param line the index of the line
     * @param full whether to include trailing whitespace
     * @return the extent of the line
     */
    private float getLineExtent(int line, boolean full) {
        final int start = getLineStart(line);
        final int end = full ? getLineEnd(line) : getLineVisibleEnd(line);

        final boolean hasTabs = getLineContainsTab(line);
        TabStops tabStops = null;
        if (hasTabs && mText instanceof Spanned) {
            // Just checking this line should be good enough, tabs should be
            // consistent across all lines in a paragraph.
            TabStopSpan[] tabs = getParagraphSpans((Spanned) mText, start, end, TabStopSpan.class);
            if (tabs.length > 0) {
                tabStops = new TabStops(TAB_INCREMENT, tabs); // XXX should reuse
            }
        }
        final Directions directions = getLineDirections(line);
        // Returned directions can actually be null
        if (directions == null) {
            return 0f;
        }
        final int dir = getParagraphDirection(line);

        final TextLine tl = TextLine.obtain();
        final TextPaint paint = mWorkPaint;
        paint.set(mPaint);
        paint.setStartHyphenEdit(getStartHyphenEdit(line));
        paint.setEndHyphenEdit(getEndHyphenEdit(line));
        tl.set(paint, mText, start, end, dir, directions, hasTabs, tabStops,
                getEllipsisStart(line), getEllipsisStart(line) + getEllipsisCount(line),
                isFallbackLineSpacingEnabled());
        if (isJustificationRequired(line)) {
            tl.justify(getJustifyWidth(line));
        }
        final float width = tl.metrics(null, null, mUseBoundsForWidth);
        TextLine.recycle(tl);
        return width;
    }

    /**
     * Returns the signed horizontal extent of the specified line, excluding
     * leading margin.  If full is false, excludes trailing whitespace.
     * @param line the index of the line
     * @param tabStops the tab stops, can be null if we know they're not used.
     * @param full whether to include trailing whitespace
     * @return the extent of the text on this line
     */
    private float getLineExtent(int line, TabStops tabStops, boolean full) {
        final int start = getLineStart(line);
        final int end = full ? getLineEnd(line) : getLineVisibleEnd(line);
        final boolean hasTabs = getLineContainsTab(line);
        final Directions directions = getLineDirections(line);
        final int dir = getParagraphDirection(line);

        final TextLine tl = TextLine.obtain();
        final TextPaint paint = mWorkPaint;
        paint.set(mPaint);
        paint.setStartHyphenEdit(getStartHyphenEdit(line));
        paint.setEndHyphenEdit(getEndHyphenEdit(line));
        tl.set(paint, mText, start, end, dir, directions, hasTabs, tabStops,
                getEllipsisStart(line), getEllipsisStart(line) + getEllipsisCount(line),
                isFallbackLineSpacingEnabled());
        if (isJustificationRequired(line)) {
            tl.justify(getJustifyWidth(line));
        }
        final float width = tl.metrics(null, null, mUseBoundsForWidth);
        TextLine.recycle(tl);
        return width;
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

        if (low < 0) {
            return 0;
        } else {
            return low;
        }
    }

    /**
     * Get the character offset on the specified line whose position is
     * closest to the specified horizontal position.
     */
    public int getOffsetForHorizontal(int line, float horiz) {
        return getOffsetForHorizontal(line, horiz, true);
    }

    /**
     * Get the character offset on the specified line whose position is
     * closest to the specified horizontal position.
     *
     * @param line the line used to find the closest offset
     * @param horiz the horizontal position used to find the closest offset
     * @param primary whether to use the primary position or secondary position to find the offset
     *
     * @hide
     */
    public int getOffsetForHorizontal(int line, float horiz, boolean primary) {
        // TODO: use Paint.getOffsetForAdvance to avoid binary search
        final int lineEndOffset = getLineEnd(line);
        final int lineStartOffset = getLineStart(line);

        Directions dirs = getLineDirections(line);

        TextLine tl = TextLine.obtain();
        // XXX: we don't care about tabs as we just use TextLine#getOffsetToLeftRightOf here.
        tl.set(mPaint, mText, lineStartOffset, lineEndOffset, getParagraphDirection(line), dirs,
                false, null,
                getEllipsisStart(line), getEllipsisStart(line) + getEllipsisCount(line),
                isFallbackLineSpacingEnabled());
        final HorizontalMeasurementProvider horizontal =
                new HorizontalMeasurementProvider(line, primary);

        final int max;
        if (line == getLineCount() - 1) {
            max = lineEndOffset;
        } else {
            max = tl.getOffsetToLeftRightOf(lineEndOffset - lineStartOffset,
                    !isRtlCharAt(lineEndOffset - 1)) + lineStartOffset;
        }
        int best = lineStartOffset;
        float bestdist = Math.abs(horizontal.get(lineStartOffset) - horiz);

        for (int i = 0; i < dirs.mDirections.length; i += 2) {
            int here = lineStartOffset + dirs.mDirections[i];
            int there = here + (dirs.mDirections[i+1] & RUN_LENGTH_MASK);
            boolean isRtl = (dirs.mDirections[i+1] & RUN_RTL_FLAG) != 0;
            int swap = isRtl ? -1 : 1;

            if (there > max)
                there = max;
            int high = there - 1 + 1, low = here + 1 - 1, guess;

            while (high - low > 1) {
                guess = (high + low) / 2;
                int adguess = getOffsetAtStartOf(guess);

                if (horizontal.get(adguess) * swap >= horiz * swap) {
                    high = guess;
                } else {
                    low = guess;
                }
            }

            if (low < here + 1)
                low = here + 1;

            if (low < there) {
                int aft = tl.getOffsetToLeftRightOf(low - lineStartOffset, isRtl) + lineStartOffset;
                low = tl.getOffsetToLeftRightOf(aft - lineStartOffset, !isRtl) + lineStartOffset;
                if (low >= here && low < there) {
                    float dist = Math.abs(horizontal.get(low) - horiz);
                    if (aft < there) {
                        float other = Math.abs(horizontal.get(aft) - horiz);

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
            }

            float dist = Math.abs(horizontal.get(here) - horiz);

            if (dist < bestdist) {
                bestdist = dist;
                best = here;
            }
        }

        float dist = Math.abs(horizontal.get(max) - horiz);

        if (dist <= bestdist) {
            best = max;
        }

        TextLine.recycle(tl);
        return best;
    }

    /**
     * Responds to #getHorizontal queries, by selecting the better strategy between:
     * - calling #getHorizontal explicitly for each query
     * - precomputing all #getHorizontal measurements, and responding to any query in constant time
     * The first strategy is used for LTR-only text, while the second is used for all other cases.
     * The class is currently only used in #getOffsetForHorizontal, so reuse with care in other
     * contexts.
     */
    private class HorizontalMeasurementProvider {
        private final int mLine;
        private final boolean mPrimary;

        private float[] mHorizontals;
        private int mLineStartOffset;

        HorizontalMeasurementProvider(final int line, final boolean primary) {
            mLine = line;
            mPrimary = primary;
            init();
        }

        private void init() {
            final Directions dirs = getLineDirections(mLine);
            if (dirs == DIRS_ALL_LEFT_TO_RIGHT) {
                return;
            }

            mHorizontals = getLineHorizontals(mLine, false, mPrimary);
            mLineStartOffset = getLineStart(mLine);
        }

        float get(final int offset) {
            final int index = offset - mLineStartOffset;
            if (mHorizontals == null || index < 0 || index >= mHorizontals.length) {
                return getHorizontal(offset, mPrimary);
            } else {
                return mHorizontals[index];
            }
        }
    }

    /**
     * Finds the range of text which is inside the specified rectangle area. The start of the range
     * is the start of the first text segment inside the area, and the end of the range is the end
     * of the last text segment inside the area.
     *
     * <p>A text segment is considered to be inside the area according to the provided {@link
     * TextInclusionStrategy}. If a text segment spans multiple lines or multiple directional runs
     * (e.g. a hyphenated word), the text segment is divided into pieces at the line and run breaks,
     * then the text segment is considered to be inside the area if any of its pieces are inside the
     * area.
     *
     * <p>The returned range may also include text segments which are not inside the specified area,
     * if those text segments are in between text segments which are inside the area. For example,
     * the returned range may be "segment1 segment2 segment3" if "segment1" and "segment3" are
     * inside the area and "segment2" is not.
     *
     * @param area area for which the text range will be found
     * @param segmentFinder SegmentFinder for determining the ranges of text to be considered as a
     *     text segment
     * @param inclusionStrategy strategy for determining whether a text segment is inside the
     *     specified area
     * @return int array of size 2 containing the start (inclusive) and end (exclusive) character
     *     offsets of the text range, or null if there are no text segments inside the area
     */
    @Nullable
    public int[] getRangeForRect(@NonNull RectF area, @NonNull SegmentFinder segmentFinder,
            @NonNull TextInclusionStrategy inclusionStrategy) {
        // Find the first line whose bottom (without line spacing) is below the top of the area.
        int startLine = getLineForVertical((int) area.top);
        if (area.top > getLineBottom(startLine, /* includeLineSpacing= */ false)) {
            startLine++;
            if (startLine >= getLineCount()) {
                // The entire area is below the last line, so it does not contain any text.
                return null;
            }
        }

        // Find the last line whose top is above the bottom of the area.
        int endLine = getLineForVertical((int) area.bottom);
        if (endLine == 0 && area.bottom < getLineTop(0)) {
            // The entire area is above the first line, so it does not contain any text.
            return null;
        }
        if (endLine < startLine) {
            // The entire area is between two lines, so it does not contain any text.
            return null;
        }

        int start = getStartOrEndOffsetForAreaWithinLine(
                startLine, area, segmentFinder, inclusionStrategy, /* getStart= */ true);
        // If the area does not contain any text on this line, keep trying subsequent lines until
        // the end line is reached.
        while (start == -1 && startLine < endLine) {
            startLine++;
            start = getStartOrEndOffsetForAreaWithinLine(
                    startLine, area, segmentFinder, inclusionStrategy, /* getStart= */ true);
        }
        if (start == -1) {
            // All lines were checked, the area does not contain any text.
            return null;
        }

        int end = getStartOrEndOffsetForAreaWithinLine(
                endLine, area, segmentFinder, inclusionStrategy, /* getStart= */ false);
        // If the area does not contain any text on this line, keep trying previous lines until
        // the start line is reached.
        while (end == -1 && startLine < endLine) {
            endLine--;
            end = getStartOrEndOffsetForAreaWithinLine(
                    endLine, area, segmentFinder, inclusionStrategy, /* getStart= */ false);
        }
        if (end == -1) {
            // All lines were checked, the area does not contain any text.
            return null;
        }

        // If a text segment spans multiple lines or multiple directional runs (e.g. a hyphenated
        // word), then getStartOrEndOffsetForAreaWithinLine() can return an offset in the middle of
        // a text segment. Adjust the range to include the rest of any partial text segments. If
        // start is already the start boundary of a text segment, then this is a no-op.
        start = segmentFinder.previousStartBoundary(start + 1);
        end = segmentFinder.nextEndBoundary(end - 1);

        return new int[] {start, end};
    }

    /**
     * Finds the start character offset of the first text segment within a line inside the specified
     * rectangle area, or the end character offset of the last text segment inside the area.
     *
     * @param line index of the line to search
     * @param area area inside which text segments will be found
     * @param segmentFinder SegmentFinder for determining the ranges of text to be considered as a
     *     text segment
     * @param inclusionStrategy strategy for determining whether a text segment is inside the
     *     specified area
     * @param getStart true to find the start of the first text segment inside the area, false to
     *     find the end of the last text segment
     * @return the start character offset of the first text segment inside the area, or the end
     *     character offset of the last text segment inside the area.
     */
    private int getStartOrEndOffsetForAreaWithinLine(
            @IntRange(from = 0) int line,
            @NonNull RectF area,
            @NonNull SegmentFinder segmentFinder,
            @NonNull TextInclusionStrategy inclusionStrategy,
            boolean getStart) {
        int lineTop = getLineTop(line);
        int lineBottom = getLineBottom(line, /* includeLineSpacing= */ false);

        int lineStartOffset = getLineStart(line);
        int lineEndOffset = getLineEnd(line);
        if (lineStartOffset == lineEndOffset) {
            return -1;
        }

        float[] horizontalBounds = new float[2 * (lineEndOffset - lineStartOffset)];
        fillHorizontalBoundsForLine(line, horizontalBounds);

        int lineStartPos = getLineStartPos(line, getParagraphLeft(line), getParagraphRight(line));

        // Loop through the runs forwards or backwards depending on getStart value.
        Layout.Directions directions = getLineDirections(line);
        int runIndex = getStart ? 0 : directions.getRunCount() - 1;
        while ((getStart && runIndex < directions.getRunCount()) || (!getStart && runIndex >= 0)) {
            // runStartOffset and runEndOffset are offset indices within the line.
            int runStartOffset = directions.getRunStart(runIndex);
            int runEndOffset = Math.min(
                    runStartOffset + directions.getRunLength(runIndex),
                    lineEndOffset - lineStartOffset);
            boolean isRtl = directions.isRunRtl(runIndex);
            float runLeft = lineStartPos
                    + (isRtl
                            ? horizontalBounds[2 * (runEndOffset - 1)]
                            : horizontalBounds[2 * runStartOffset]);
            float runRight = lineStartPos
                    + (isRtl
                            ? horizontalBounds[2 * runStartOffset + 1]
                            : horizontalBounds[2 * (runEndOffset - 1) + 1]);

            int result =
                    getStart
                            ? getStartOffsetForAreaWithinRun(
                                    area, lineTop, lineBottom,
                                    lineStartOffset, lineStartPos, horizontalBounds,
                                    runStartOffset, runEndOffset, runLeft, runRight, isRtl,
                                    segmentFinder, inclusionStrategy)
                            : getEndOffsetForAreaWithinRun(
                                    area, lineTop, lineBottom,
                                    lineStartOffset, lineStartPos, horizontalBounds,
                                    runStartOffset, runEndOffset, runLeft, runRight, isRtl,
                                    segmentFinder, inclusionStrategy);
            if (result >= 0) {
                return result;
            }

            runIndex += getStart ? 1 : -1;
        }
        return -1;
    }

    /**
     * Finds the start character offset of the first text segment within a directional run inside
     * the specified rectangle area.
     *
     * @param area area inside which text segments will be found
     * @param lineTop top of the line containing this run
     * @param lineBottom bottom (not including line spacing) of the line containing this run
     * @param lineStartOffset start character offset of the line containing this run
     * @param lineStartPos start position of the line containing this run
     * @param horizontalBounds array containing the signed horizontal bounds of the characters in
     *     the line. The left and right bounds of the character at offset i are stored at index (2 *
     *     i) and index (2 * i + 1). Bounds are relative to {@code lineStartPos}.
     * @param runStartOffset start offset of the run relative to {@code lineStartOffset}
     * @param runEndOffset end offset of the run relative to {@code lineStartOffset}
     * @param runLeft left bound of the run
     * @param runRight right bound of the run
     * @param isRtl whether the run is right-to-left
     * @param segmentFinder SegmentFinder for determining the ranges of text to be considered as a
     *     text segment
     * @param inclusionStrategy strategy for determining whether a text segment is inside the
     *     specified area
     * @return the start character offset of the first text segment inside the area
     */
    private static int getStartOffsetForAreaWithinRun(
            @NonNull RectF area,
            int lineTop, int lineBottom,
            @IntRange(from = 0) int lineStartOffset,
            @IntRange(from = 0) int lineStartPos,
            @NonNull float[] horizontalBounds,
            @IntRange(from = 0) int runStartOffset, @IntRange(from = 0) int runEndOffset,
            float runLeft, float runRight,
            boolean isRtl,
            @NonNull SegmentFinder segmentFinder,
            @NonNull TextInclusionStrategy inclusionStrategy) {
        if (runRight < area.left || runLeft > area.right) {
            // The run does not overlap the area.
            return -1;
        }

        // Find the first character in the run whose bounds overlap with the area.
        // firstCharOffset is an offset index within the line.
        int firstCharOffset;
        if ((!isRtl && area.left <= runLeft) || (isRtl && area.right >= runRight)) {
            firstCharOffset = runStartOffset;
        } else {
            int low = runStartOffset;
            int high = runEndOffset;
            int guess;
            while (high - low > 1) {
                guess = (high + low) / 2;
                // Left edge of the character at guess
                float pos = lineStartPos + horizontalBounds[2 * guess];
                if ((!isRtl && pos > area.left) || (isRtl && pos < area.right)) {
                    high = guess;
                } else {
                    low = guess;
                }
            }
            // The area edge is between the left edge of the character at low and the left edge of
            // the character at high. For LTR text, this is within the character at low. For RTL
            // text, this is within the character at high.
            firstCharOffset = isRtl ? high : low;
        }

        // Find the first text segment containing this character (or, if no text segment contains
        // this character, the first text segment after this character). All previous text segments
        // in this run are to the left (for LTR) of the area.
        int segmentEndOffset =
                segmentFinder.nextEndBoundary(lineStartOffset + firstCharOffset);
        if (segmentEndOffset == SegmentFinder.DONE) {
            // There are no text segments containing or after firstCharOffset, so no text segments
            // in this run overlap the area.
            return -1;
        }
        int segmentStartOffset = segmentFinder.previousStartBoundary(segmentEndOffset);
        if (segmentStartOffset >= lineStartOffset + runEndOffset) {
            // The text segment is after the end of this run, so no text segments in this run
            // overlap the area.
            return -1;
        }
        // If the segment extends outside of this run, only consider the piece of the segment within
        // this run.
        segmentStartOffset = Math.max(segmentStartOffset, lineStartOffset + runStartOffset);
        segmentEndOffset = Math.min(segmentEndOffset, lineStartOffset + runEndOffset);

        RectF segmentBounds = new RectF(0, lineTop, 0, lineBottom);
        while (true) {
            // Start (left for LTR, right for RTL) edge of the character at segmentStartOffset.
            float segmentStart = lineStartPos + horizontalBounds[
                    2 * (segmentStartOffset - lineStartOffset) + (isRtl ? 1 : 0)];
            if ((!isRtl && segmentStart > area.right) || (isRtl && segmentStart < area.left)) {
                // The entire area is to the left (for LTR) of the text segment. So the area does
                // not contain any text segments within this run.
                return -1;
            }
            // End (right for LTR, left for RTL) edge of the character at (segmentStartOffset - 1).
            float segmentEnd = lineStartPos + horizontalBounds[
                    2 * (segmentEndOffset - lineStartOffset - 1) + (isRtl ? 0 : 1)];
            segmentBounds.left = isRtl ? segmentEnd : segmentStart;
            segmentBounds.right = isRtl ? segmentStart : segmentEnd;
            if (inclusionStrategy.isSegmentInside(segmentBounds, area)) {
                return segmentStartOffset;
            }
            // Try the next text segment.
            segmentStartOffset = segmentFinder.nextStartBoundary(segmentStartOffset);
            if (segmentStartOffset == SegmentFinder.DONE
                    || segmentStartOffset >= lineStartOffset + runEndOffset) {
                // No more text segments within this run.
                return -1;
            }
            segmentEndOffset = segmentFinder.nextEndBoundary(segmentStartOffset);
            // If the segment extends past the end of this run, only consider the piece of the
            // segment within this run.
            segmentEndOffset = Math.min(segmentEndOffset, lineStartOffset + runEndOffset);
        }
    }

    /**
     * Finds the end character offset of the last text segment within a directional run inside the
     * specified rectangle area.
     *
     * @param area area inside which text segments will be found
     * @param lineTop top of the line containing this run
     * @param lineBottom bottom (not including line spacing) of the line containing this run
     * @param lineStartOffset start character offset of the line containing this run
     * @param lineStartPos start position of the line containing this run
     * @param horizontalBounds array containing the signed horizontal bounds of the characters in
     *     the line. The left and right bounds of the character at offset i are stored at index (2 *
     *     i) and index (2 * i + 1). Bounds are relative to {@code lineStartPos}.
     * @param runStartOffset start offset of the run relative to {@code lineStartOffset}
     * @param runEndOffset end offset of the run relative to {@code lineStartOffset}
     * @param runLeft left bound of the run
     * @param runRight right bound of the run
     * @param isRtl whether the run is right-to-left
     * @param segmentFinder SegmentFinder for determining the ranges of text to be considered as a
     *     text segment
     * @param inclusionStrategy strategy for determining whether a text segment is inside the
     *     specified area
     * @return the end character offset of the last text segment inside the area
     */
    private static int getEndOffsetForAreaWithinRun(
            @NonNull RectF area,
            int lineTop, int lineBottom,
            @IntRange(from = 0) int lineStartOffset,
            @IntRange(from = 0) int lineStartPos,
            @NonNull float[] horizontalBounds,
            @IntRange(from = 0) int runStartOffset, @IntRange(from = 0) int runEndOffset,
            float runLeft, float runRight,
            boolean isRtl,
            @NonNull SegmentFinder segmentFinder,
            @NonNull TextInclusionStrategy inclusionStrategy) {
        if (runRight < area.left || runLeft > area.right) {
            // The run does not overlap the area.
            return -1;
        }

        // Find the last character in the run whose bounds overlap with the area.
        // firstCharOffset is an offset index within the line.
        int lastCharOffset;
        if ((!isRtl && area.right >= runRight) || (isRtl && area.left <= runLeft)) {
            lastCharOffset = runEndOffset - 1;
        } else {
            int low = runStartOffset;
            int high = runEndOffset;
            int guess;
            while (high - low > 1) {
                guess = (high + low) / 2;
                // Left edge of the character at guess
                float pos = lineStartPos + horizontalBounds[2 * guess];
                if ((!isRtl && pos > area.right) || (isRtl && pos < area.left)) {
                    high = guess;
                } else {
                    low = guess;
                }
            }
            // The area edge is between the left edge of the character at low and the left edge of
            // the character at high. For LTR text, this is within the character at low. For RTL
            // text, this is within the character at high.
            lastCharOffset = isRtl ? high : low;
        }

        // Find the last text segment containing this character (or, if no text segment contains
        // this character, the first text segment before this character). All following text
        // segments in this run are to the right (for LTR) of the area.
        // + 1 to allow segmentStartOffset = lineStartOffset + lastCharOffset
        int segmentStartOffset =
                segmentFinder.previousStartBoundary(lineStartOffset + lastCharOffset + 1);
        if (segmentStartOffset == SegmentFinder.DONE) {
            // There are no text segments containing or before lastCharOffset, so no text segments
            // in this run overlap the area.
            return -1;
        }
        int segmentEndOffset = segmentFinder.nextEndBoundary(segmentStartOffset);
        if (segmentEndOffset <= lineStartOffset + runStartOffset) {
            // The text segment is before the start of this run, so no text segments in this run
            // overlap the area.
            return -1;
        }
        // If the segment extends outside of this run, only consider the piece of the segment within
        // this run.
        segmentStartOffset = Math.max(segmentStartOffset, lineStartOffset + runStartOffset);
        segmentEndOffset = Math.min(segmentEndOffset, lineStartOffset + runEndOffset);

        RectF segmentBounds = new RectF(0, lineTop, 0, lineBottom);
        while (true) {
            // End (right for LTR, left for RTL) edge of the character at (segmentStartOffset - 1).
            float segmentEnd = lineStartPos + horizontalBounds[
                    2 * (segmentEndOffset - lineStartOffset - 1) + (isRtl ? 0 : 1)];
            if ((!isRtl && segmentEnd < area.left) || (isRtl && segmentEnd > area.right)) {
                // The entire area is to the right (for LTR) of the text segment. So the
                // area does not contain any text segments within this run.
                return -1;
            }
            // Start (left for LTR, right for RTL) edge of the character at segmentStartOffset.
            float segmentStart = lineStartPos + horizontalBounds[
                    2 * (segmentStartOffset - lineStartOffset) + (isRtl ? 1 : 0)];
            segmentBounds.left = isRtl ? segmentEnd : segmentStart;
            segmentBounds.right = isRtl ? segmentStart : segmentEnd;
            if (inclusionStrategy.isSegmentInside(segmentBounds, area)) {
                return segmentEndOffset;
            }
            // Try the previous text segment.
            segmentEndOffset = segmentFinder.previousEndBoundary(segmentEndOffset);
            if (segmentEndOffset == SegmentFinder.DONE
                    || segmentEndOffset <= lineStartOffset + runStartOffset) {
                // No more text segments within this run.
                return -1;
            }
            segmentStartOffset = segmentFinder.previousStartBoundary(segmentEndOffset);
            // If the segment extends past the start of this run, only consider the piece of the
            // segment within this run.
            segmentStartOffset = Math.max(segmentStartOffset, lineStartOffset + runStartOffset);
        }
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

            if (!TextLine.isLineEndSpace(ch)) {
                break;
            }

        }

        return end;
    }

    /**
     * Return the vertical position of the bottom of the specified line.
     */
    public final int getLineBottom(int line) {
        return getLineBottom(line, /* includeLineSpacing= */ true);
    }

    /**
     * Return the vertical position of the bottom of the specified line.
     *
     * @param line index of the line
     * @param includeLineSpacing whether to include the line spacing
     */
    public int getLineBottom(int line, boolean includeLineSpacing) {
        if (includeLineSpacing) {
            return getLineTop(line + 1);
        } else {
            return getLineTop(line + 1) - getLineExtra(line);
        }
    }

    /**
     * Return the vertical position of the baseline of the specified line.
     */
    public final int getLineBaseline(int line) {
        // getLineTop(line+1) == getLineBottom(line)
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
     * Return the extra space added as a result of line spacing attributes
     * {@link #getSpacingAdd()} and {@link #getSpacingMultiplier()}. Default value is {@code zero}.
     *
     * @param line the index of the line, the value should be equal or greater than {@code zero}
     * @hide
     */
    public int getLineExtra(@IntRange(from = 0) int line) {
        return 0;
    }

    public int getOffsetToLeftOf(int offset) {
        return getOffsetToLeftRightOf(offset, true);
    }

    public int getOffsetToRightOf(int offset) {
        return getOffsetToLeftRightOf(offset, false);
    }

    private int getOffsetToLeftRightOf(int caret, boolean toLeft) {
        int line = getLineForOffset(caret);
        int lineStart = getLineStart(line);
        int lineEnd = getLineEnd(line);
        int lineDir = getParagraphDirection(line);

        boolean lineChanged = false;
        boolean advance = toLeft == (lineDir == DIR_RIGHT_TO_LEFT);
        // if walking off line, look at the line we're headed to
        if (advance) {
            if (caret == lineEnd) {
                if (line < getLineCount() - 1) {
                    lineChanged = true;
                    ++line;
                } else {
                    return caret; // at very end, don't move
                }
            }
        } else {
            if (caret == lineStart) {
                if (line > 0) {
                    lineChanged = true;
                    --line;
                } else {
                    return caret; // at very start, don't move
                }
            }
        }

        if (lineChanged) {
            lineStart = getLineStart(line);
            lineEnd = getLineEnd(line);
            int newDir = getParagraphDirection(line);
            if (newDir != lineDir) {
                // unusual case.  we want to walk onto the line, but it runs
                // in a different direction than this one, so we fake movement
                // in the opposite direction.
                toLeft = !toLeft;
                lineDir = newDir;
            }
        }

        Directions directions = getLineDirections(line);

        TextLine tl = TextLine.obtain();
        // XXX: we don't care about tabs
        tl.set(mPaint, mText, lineStart, lineEnd, lineDir, directions, false, null,
                getEllipsisStart(line), getEllipsisStart(line) + getEllipsisCount(line),
                isFallbackLineSpacingEnabled());
        caret = lineStart + tl.getOffsetToLeftRightOf(caret - lineStart, toLeft);
        TextLine.recycle(tl);
        return caret;
    }

    private int getOffsetAtStartOf(int offset) {
        // XXX this probably should skip local reorderings and
        // zero-width characters, look at callers
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
     * Determine whether we should clamp cursor position. Currently it's
     * only robust for left-aligned displays.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean shouldClampCursor(int line) {
        // Only clamp cursor position in left-aligned displays.
        switch (getParagraphAlignment(line)) {
            case ALIGN_LEFT:
                return true;
            case ALIGN_NORMAL:
                return getParagraphDirection(line) > 0;
            default:
                return false;
        }

    }

    /**
     * Fills in the specified Path with a representation of a cursor
     * at the specified offset.  This will often be a vertical line
     * but can be multiple discontinuous lines in text with multiple
     * directionalities.
     */
    public void getCursorPath(final int point, final Path dest, final CharSequence editingBuffer) {
        dest.reset();

        int line = getLineForOffset(point);
        int top = getLineTop(line);
        int bottom = getLineBottom(line, /* includeLineSpacing= */ false);

        boolean clamped = shouldClampCursor(line);
        float h1 = getPrimaryHorizontal(point, clamped) - 0.5f;

        int caps = TextKeyListener.getMetaState(editingBuffer, TextKeyListener.META_SHIFT_ON) |
                   TextKeyListener.getMetaState(editingBuffer, TextKeyListener.META_SELECTING);
        int fn = TextKeyListener.getMetaState(editingBuffer, TextKeyListener.META_ALT_ON);
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

        dest.moveTo(h1, top);
        dest.lineTo(h1, bottom);

        if (caps == 2) {
            dest.moveTo(h1, bottom);
            dest.lineTo(h1 - dist, bottom + dist);
            dest.lineTo(h1, bottom);
            dest.lineTo(h1 + dist, bottom + dist);
        } else if (caps == 1) {
            dest.moveTo(h1, bottom);
            dest.lineTo(h1 - dist, bottom + dist);

            dest.moveTo(h1 - dist, bottom + dist - 0.5f);
            dest.lineTo(h1 + dist, bottom + dist - 0.5f);

            dest.moveTo(h1 + dist, bottom + dist);
            dest.lineTo(h1, bottom);
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
            int top, int bottom, SelectionRectangleConsumer consumer) {
        int linestart = getLineStart(line);
        int lineend = getLineEnd(line);
        Directions dirs = getLineDirections(line);

        if (lineend > linestart && mText.charAt(lineend - 1) == '\n') {
            lineend--;
        }

        for (int i = 0; i < dirs.mDirections.length; i += 2) {
            int here = linestart + dirs.mDirections[i];
            int there = here + (dirs.mDirections[i + 1] & RUN_LENGTH_MASK);

            if (there > lineend) {
                there = lineend;
            }

            if (start <= there && end >= here) {
                int st = Math.max(start, here);
                int en = Math.min(end, there);

                if (st != en) {
                    float h1 = getHorizontal(st, false, line, false /* not clamped */);
                    float h2 = getHorizontal(en, true, line, false /* not clamped */);

                    float left = Math.min(h1, h2);
                    float right = Math.max(h1, h2);

                    final @TextSelectionLayout int layout =
                            ((dirs.mDirections[i + 1] & RUN_RTL_FLAG) != 0)
                                    ? TEXT_SELECTION_LAYOUT_RIGHT_TO_LEFT
                                    : TEXT_SELECTION_LAYOUT_LEFT_TO_RIGHT;

                    consumer.accept(left, top, right, bottom, layout);
                }
            }
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
        getSelection(start, end, (left, top, right, bottom, textSelectionLayout) ->
                dest.addRect(left, top, right, bottom, Path.Direction.CW));
    }

    /**
     * Calculates the rectangles which should be highlighted to indicate a selection between start
     * and end and feeds them into the given {@link SelectionRectangleConsumer}.
     *
     * @param start    the starting index of the selection
     * @param end      the ending index of the selection
     * @param consumer the {@link SelectionRectangleConsumer} which will receive the generated
     *                 rectangles. It will be called every time a rectangle is generated.
     * @hide
     * @see #getSelectionPath(int, int, Path)
     */
    public final void getSelection(int start, int end, final SelectionRectangleConsumer consumer) {
        if (start == end) {
            return;
        }

        if (end < start) {
            int temp = end;
            end = start;
            start = temp;
        }

        final int startline = getLineForOffset(start);
        final int endline = getLineForOffset(end);

        int top = getLineTop(startline);
        int bottom = getLineBottom(endline, /* includeLineSpacing= */ false);

        if (startline == endline) {
            addSelection(startline, start, end, top, bottom, consumer);
        } else {
            final float width = mWidth;

            addSelection(startline, start, getLineEnd(startline),
                    top, getLineBottom(startline), consumer);

            if (getParagraphDirection(startline) == DIR_RIGHT_TO_LEFT) {
                consumer.accept(getLineLeft(startline), top, 0, getLineBottom(startline),
                        TEXT_SELECTION_LAYOUT_RIGHT_TO_LEFT);
            } else {
                consumer.accept(getLineRight(startline), top, width, getLineBottom(startline),
                        TEXT_SELECTION_LAYOUT_LEFT_TO_RIGHT);
            }

            for (int i = startline + 1; i < endline; i++) {
                top = getLineTop(i);
                bottom = getLineBottom(i);
                if (getParagraphDirection(i) == DIR_RIGHT_TO_LEFT) {
                    consumer.accept(0, top, width, bottom, TEXT_SELECTION_LAYOUT_RIGHT_TO_LEFT);
                } else {
                    consumer.accept(0, top, width, bottom, TEXT_SELECTION_LAYOUT_LEFT_TO_RIGHT);
                }
            }

            top = getLineTop(endline);
            bottom = getLineBottom(endline, /* includeLineSpacing= */ false);

            addSelection(endline, getLineStart(endline), end, top, bottom, consumer);

            if (getParagraphDirection(endline) == DIR_RIGHT_TO_LEFT) {
                consumer.accept(width, top, getLineRight(endline), bottom,
                        TEXT_SELECTION_LAYOUT_RIGHT_TO_LEFT);
            } else {
                consumer.accept(0, top, getLineLeft(endline), bottom,
                        TEXT_SELECTION_LAYOUT_LEFT_TO_RIGHT);
            }
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
            AlignmentSpan[] spans = getParagraphSpans(sp, getLineStart(line),
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
        int left = 0;
        int dir = getParagraphDirection(line);
        if (dir == DIR_RIGHT_TO_LEFT || !mSpannedText) {
            return left; // leading margin has no impact, or no styles
        }
        return getParagraphLeadingMargin(line);
    }

    /**
     * Get the right edge of the specified paragraph, inset by right margins.
     */
    public final int getParagraphRight(int line) {
        int right = mWidth;
        int dir = getParagraphDirection(line);
        if (dir == DIR_LEFT_TO_RIGHT || !mSpannedText) {
            return right; // leading margin has no impact, or no styles
        }
        return right - getParagraphLeadingMargin(line);
    }

    /**
     * Returns the effective leading margin (unsigned) for this line,
     * taking into account LeadingMarginSpan and LeadingMarginSpan2.
     * @param line the line index
     * @return the leading margin of this line
     */
    private int getParagraphLeadingMargin(int line) {
        if (!mSpannedText) {
            return 0;
        }
        Spanned spanned = (Spanned) mText;

        int lineStart = getLineStart(line);
        int lineEnd = getLineEnd(line);
        int spanEnd = spanned.nextSpanTransition(lineStart, lineEnd,
                LeadingMarginSpan.class);
        LeadingMarginSpan[] spans = getParagraphSpans(spanned, lineStart, spanEnd,
                                                LeadingMarginSpan.class);
        if (spans.length == 0) {
            return 0; // no leading margin span;
        }

        int margin = 0;

        boolean useFirstLineMargin = lineStart == 0 || spanned.charAt(lineStart - 1) == '\n';
        for (int i = 0; i < spans.length; i++) {
            if (spans[i] instanceof LeadingMarginSpan2) {
                int spStart = spanned.getSpanStart(spans[i]);
                int spanLine = getLineForOffset(spStart);
                int count = ((LeadingMarginSpan2) spans[i]).getLeadingMarginLineCount();
                // if there is more than one LeadingMarginSpan2, use the count that is greatest
                useFirstLineMargin |= line < spanLine + count;
            }
        }
        for (int i = 0; i < spans.length; i++) {
            LeadingMarginSpan span = spans[i];
            margin += span.getLeadingMargin(useFirstLineMargin);
        }

        return margin;
    }

    private static float measurePara(TextPaint paint, CharSequence text, int start, int end,
            TextDirectionHeuristic textDir, boolean useBoundsForWidth) {
        MeasuredParagraph mt = null;
        TextLine tl = TextLine.obtain();
        try {
            mt = MeasuredParagraph.buildForBidi(text, start, end, textDir, mt);
            final char[] chars = mt.getChars();
            final int len = chars.length;
            final Directions directions = mt.getDirections(0, len);
            final int dir = mt.getParagraphDir();
            boolean hasTabs = false;
            TabStops tabStops = null;
            // leading margins should be taken into account when measuring a paragraph
            int margin = 0;
            if (text instanceof Spanned) {
                Spanned spanned = (Spanned) text;
                LeadingMarginSpan[] spans = getParagraphSpans(spanned, start, end,
                        LeadingMarginSpan.class);
                for (LeadingMarginSpan lms : spans) {
                    margin += lms.getLeadingMargin(true);
                }
            }
            for (int i = 0; i < len; ++i) {
                if (chars[i] == '\t') {
                    hasTabs = true;
                    if (text instanceof Spanned) {
                        Spanned spanned = (Spanned) text;
                        int spanEnd = spanned.nextSpanTransition(start, end,
                                TabStopSpan.class);
                        TabStopSpan[] spans = getParagraphSpans(spanned, start, spanEnd,
                                TabStopSpan.class);
                        if (spans.length > 0) {
                            tabStops = new TabStops(TAB_INCREMENT, spans);
                        }
                    }
                    break;
                }
            }
            tl.set(paint, text, start, end, dir, directions, hasTabs, tabStops,
                    0 /* ellipsisStart */, 0 /* ellipsisEnd */,
                    false /* use fallback line spacing. unused */);
            return margin + Math.abs(tl.metrics(null, null, useBoundsForWidth));
        } finally {
            TextLine.recycle(tl);
            if (mt != null) {
                mt.recycle();
            }
        }
    }

    /**
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public static class TabStops {
        private float[] mStops;
        private int mNumStops;
        private float mIncrement;

        public TabStops(float increment, Object[] spans) {
            reset(increment, spans);
        }

        void reset(float increment, Object[] spans) {
            this.mIncrement = increment;

            int ns = 0;
            if (spans != null) {
                float[] stops = this.mStops;
                for (Object o : spans) {
                    if (o instanceof TabStopSpan) {
                        if (stops == null) {
                            stops = new float[10];
                        } else if (ns == stops.length) {
                            float[] nstops = new float[ns * 2];
                            for (int i = 0; i < ns; ++i) {
                                nstops[i] = stops[i];
                            }
                            stops = nstops;
                        }
                        stops[ns++] = ((TabStopSpan) o).getTabStop();
                    }
                }
                if (ns > 1) {
                    Arrays.sort(stops, 0, ns);
                }
                if (stops != this.mStops) {
                    this.mStops = stops;
                }
            }
            this.mNumStops = ns;
        }

        float nextTab(float h) {
            int ns = this.mNumStops;
            if (ns > 0) {
                float[] stops = this.mStops;
                for (int i = 0; i < ns; ++i) {
                    float stop = stops[i];
                    if (stop > h) {
                        return stop;
                    }
                }
            }
            return nextDefaultStop(h, mIncrement);
        }

        /**
         * Returns the position of next tab stop.
         */
        public static float nextDefaultStop(float h, float inc) {
            return ((int) ((h + inc) / inc)) * inc;
        }
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
                tabs = getParagraphSpans((Spanned) text, start, end, TabStopSpan.class);
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

    /**
     * Returns the same as <code>text.getSpans()</code>, except where
     * <code>start</code> and <code>end</code> are the same and are not
     * at the very beginning of the text, in which case an empty array
     * is returned instead.
     * <p>
     * This is needed because of the special case that <code>getSpans()</code>
     * on an empty range returns the spans adjacent to that range, which is
     * primarily for the sake of <code>TextWatchers</code> so they will get
     * notifications when text goes from empty to non-empty.  But it also
     * has the unfortunate side effect that if the text ends with an empty
     * paragraph, that paragraph accidentally picks up the styles of the
     * preceding paragraph (even though those styles will not be picked up
     * by new text that is inserted into the empty paragraph).
     * <p>
     * The reason it just checks whether <code>start</code> and <code>end</code>
     * is the same is that the only time a line can contain 0 characters
     * is if it is the final paragraph of the Layout; otherwise any line will
     * contain at least one printing or newline character.  The reason for the
     * additional check if <code>start</code> is greater than 0 is that
     * if the empty paragraph is the entire content of the buffer, paragraph
     * styles that are already applied to the buffer will apply to text that
     * is inserted into it.
     */
    /* package */static <T> T[] getParagraphSpans(Spanned text, int start, int end, Class<T> type) {
        if (start == end && start > 0) {
            return ArrayUtils.emptyArray(type);
        }

        if(text instanceof SpannableStringBuilder) {
            return ((SpannableStringBuilder) text).getSpans(start, end, type, false);
        } else {
            return text.getSpans(start, end, type);
        }
    }

    private void ellipsize(int start, int end, int line,
                           char[] dest, int destoff, TextUtils.TruncateAt method) {
        final int ellipsisCount = getEllipsisCount(line);
        if (ellipsisCount == 0) {
            return;
        }
        final int ellipsisStart = getEllipsisStart(line);
        final int lineStart = getLineStart(line);

        final String ellipsisString = TextUtils.getEllipsisString(method);
        final int ellipsisStringLen = ellipsisString.length();
        // Use the ellipsis string only if there are that at least as many characters to replace.
        final boolean useEllipsisString = ellipsisCount >= ellipsisStringLen;
        final int min = Math.max(0, start - ellipsisStart - lineStart);
        final int max = Math.min(ellipsisCount, end - ellipsisStart - lineStart);

        for (int i = min; i < max; i++) {
            final char c;
            if (useEllipsisString && i < ellipsisStringLen) {
                c = ellipsisString.charAt(i);
            } else {
                c = TextUtils.ELLIPSIS_FILLER;
            }

            final int a = i + ellipsisStart + lineStart;
            dest[destoff + a - start] = c;
        }
    }

    /**
     * Stores information about bidirectional (left-to-right or right-to-left)
     * text within the layout of a line.
     */
    public static class Directions {
        /**
         * Directions represents directional runs within a line of text. Runs are pairs of ints
         * listed in visual order, starting from the leading margin.  The first int of each pair is
         * the offset from the first character of the line to the start of the run.  The second int
         * represents both the length and level of the run. The length is in the lower bits,
         * accessed by masking with RUN_LENGTH_MASK.  The level is in the higher bits, accessed by
         * shifting by RUN_LEVEL_SHIFT and masking by RUN_LEVEL_MASK. To simply test for an RTL
         * direction, test the bit using RUN_RTL_FLAG, if set then the direction is rtl.
         * @hide
         */
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public int[] mDirections;

        /**
         * @hide
         */
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public Directions(int[] dirs) {
            mDirections = dirs;
        }

        /**
         * Returns number of BiDi runs.
         *
         * @hide
         */
        public @IntRange(from = 0) int getRunCount() {
            return mDirections.length / 2;
        }

        /**
         * Returns the start offset of the BiDi run.
         *
         * @param runIndex the index of the BiDi run
         * @return the start offset of the BiDi run.
         * @hide
         */
        public @IntRange(from = 0) int getRunStart(@IntRange(from = 0) int runIndex) {
            return mDirections[runIndex * 2];
        }

        /**
         * Returns the length of the BiDi run.
         *
         * Note that this method may return too large number due to reducing the number of object
         * allocations. The too large number means the remaining part is assigned to this run. The
         * caller must clamp the returned value.
         *
         * @param runIndex the index of the BiDi run
         * @return the length of the BiDi run.
         * @hide
         */
        public @IntRange(from = 0) int getRunLength(@IntRange(from = 0) int runIndex) {
            return mDirections[runIndex * 2 + 1] & RUN_LENGTH_MASK;
        }

        /**
         * Returns the BiDi level of this run.
         *
         * @param runIndex the index of the BiDi run
         * @return the BiDi level of this run.
         * @hide
         */
        @IntRange(from = 0)
        public int getRunLevel(int runIndex) {
            return (mDirections[runIndex * 2 + 1] >>> RUN_LEVEL_SHIFT) & RUN_LEVEL_MASK;
        }

        /**
         * Returns true if the BiDi run is RTL.
         *
         * @param runIndex the index of the BiDi run
         * @return true if the BiDi run is RTL.
         * @hide
         */
        public boolean isRunRtl(int runIndex) {
            return (mDirections[runIndex * 2 + 1] & RUN_RTL_FLAG) != 0;
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
                mLayout.ellipsize(start, end, i, dest, destoff, mMethod);
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

        @Override
        public String toString() {
            char[] s = new char[length()];
            getChars(0, length(), s, 0);
            return new String(s);
        }

    }

    /* package */ static class SpannedEllipsizer extends Ellipsizer implements Spanned {
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

        @SuppressWarnings("rawtypes")
        public int nextSpanTransition(int start, int limit, Class type) {
            return mSpanned.nextSpanTransition(start, limit, type);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            char[] s = new char[end - start];
            getChars(start, end, s, 0);

            SpannableString ss = new SpannableString(new String(s));
            TextUtils.copySpansFrom(mSpanned, start, end, Object.class, ss, 0);
            return ss;
        }
    }

    private CharSequence mText;
    @UnsupportedAppUsage
    private TextPaint mPaint;
    private TextPaint mWorkPaint = new TextPaint();
    private int mWidth;
    private Alignment mAlignment = Alignment.ALIGN_NORMAL;
    private float mSpacingMult;
    private float mSpacingAdd;
    private static final Rect sTempRect = new Rect();
    private boolean mSpannedText;
    private TextDirectionHeuristic mTextDir;
    private SpanSet<LineBackgroundSpan> mLineBackgroundSpans;
    private boolean mIncludePad;
    private boolean mFallbackLineSpacing;
    private int mEllipsizedWidth;
    private TextUtils.TruncateAt mEllipsize;
    private int mMaxLines;
    private int mBreakStrategy;
    private int mHyphenationFrequency;
    private int[] mLeftIndents;
    private int[] mRightIndents;
    private int mJustificationMode;
    private LineBreakConfig mLineBreakConfig;
    private boolean mUseBoundsForWidth;
    private @Nullable Paint.FontMetrics mMinimumFontMetrics;

    /** @hide */
    @IntDef(prefix = { "DIR_" }, value = {
            DIR_LEFT_TO_RIGHT,
            DIR_RIGHT_TO_LEFT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Direction {}

    public static final int DIR_LEFT_TO_RIGHT = 1;
    public static final int DIR_RIGHT_TO_LEFT = -1;

    /* package */ static final int DIR_REQUEST_LTR = 1;
    /* package */ static final int DIR_REQUEST_RTL = -1;
    @UnsupportedAppUsage
    /* package */ static final int DIR_REQUEST_DEFAULT_LTR = 2;
    /* package */ static final int DIR_REQUEST_DEFAULT_RTL = -2;

    /* package */ static final int RUN_LENGTH_MASK = 0x03ffffff;
    /* package */ static final int RUN_LEVEL_SHIFT = 26;
    /* package */ static final int RUN_LEVEL_MASK = 0x3f;
    /* package */ static final int RUN_RTL_FLAG = 1 << RUN_LEVEL_SHIFT;

    public enum Alignment {
        ALIGN_NORMAL,
        ALIGN_OPPOSITE,
        ALIGN_CENTER,
        /** @hide */
        @UnsupportedAppUsage
        ALIGN_LEFT,
        /** @hide */
        @UnsupportedAppUsage
        ALIGN_RIGHT,
    }

    private static final float TAB_INCREMENT = 20;

    /** @hide */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @UnsupportedAppUsage
    public static final Directions DIRS_ALL_LEFT_TO_RIGHT =
        new Directions(new int[] { 0, RUN_LENGTH_MASK });

    /** @hide */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @UnsupportedAppUsage
    public static final Directions DIRS_ALL_RIGHT_TO_LEFT =
        new Directions(new int[] { 0, RUN_LENGTH_MASK | RUN_RTL_FLAG });

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "TEXT_SELECTION_LAYOUT_" }, value = {
            TEXT_SELECTION_LAYOUT_RIGHT_TO_LEFT,
            TEXT_SELECTION_LAYOUT_LEFT_TO_RIGHT
    })
    public @interface TextSelectionLayout {}

    /** @hide */
    public static final int TEXT_SELECTION_LAYOUT_RIGHT_TO_LEFT = 0;
    /** @hide */
    public static final int TEXT_SELECTION_LAYOUT_LEFT_TO_RIGHT = 1;

    /** @hide */
    @FunctionalInterface
    public interface SelectionRectangleConsumer {
        /**
         * Performs this operation on the given rectangle.
         *
         * @param left   the left edge of the rectangle
         * @param top    the top edge of the rectangle
         * @param right  the right edge of the rectangle
         * @param bottom the bottom edge of the rectangle
         * @param textSelectionLayout the layout (RTL or LTR) of the text covered by this
         *                            selection rectangle
         */
        void accept(float left, float top, float right, float bottom,
                @TextSelectionLayout int textSelectionLayout);
    }

    /**
     * Strategy for determining whether a text segment is inside a rectangle area.
     *
     * @see #getRangeForRect(RectF, SegmentFinder, TextInclusionStrategy)
     */
    @FunctionalInterface
    public interface TextInclusionStrategy {
        /**
         * Returns true if this {@link TextInclusionStrategy} considers the segment with bounds
         * {@code segmentBounds} to be inside {@code area}.
         *
         * <p>The segment is a range of text which does not cross line boundaries or directional run
         * boundaries. The horizontal bounds of the segment are the start bound of the first
         * character to the end bound of the last character. The vertical bounds match the line
         * bounds ({@code getLineTop(line)} and {@code getLineBottom(line, false)}).
         */
        boolean isSegmentInside(@NonNull RectF segmentBounds, @NonNull RectF area);
    }

    /**
     * A builder class for Layout object.
     *
     * Different from {@link StaticLayout.Builder}, this builder generates the optimal layout based
     * on input. If the given text and parameters can be rendered with {@link BoringLayout}, this
     * builder generates {@link BoringLayout} instance. Otherwise, {@link StaticLayout} instance is
     * generated.
     *
     * @see StaticLayout.Builder
     */
    @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
    public static final class Builder {
        /**
         * Construct a builder class.
         *
         * @param text a text to be displayed.
         * @param start an inclusive start index of the text to be displayed.
         * @param end an exclusive end index of the text to be displayed.
         * @param paint a paint object to be used for drawing text.
         * @param width a width constraint in pixels.
         */
        public Builder(
                @NonNull CharSequence text,
                @IntRange(from = 0) int start,
                @IntRange(from = 0) int end,
                @NonNull TextPaint paint,
                @IntRange(from = 0) int width) {
            mText = text;
            mStart = start;
            mEnd = end;
            mPaint = paint;
            mWidth = width;
            mEllipsizedWidth = width;
        }

        /**
         * Set the text alignment.
         *
         * The default value is {@link Layout.Alignment#ALIGN_NORMAL}.
         *
         * @param alignment an alignment.
         * @return this builder instance.
         * @see Layout.Alignment
         * @see Layout#getAlignment()
         * @see StaticLayout.Builder#setAlignment(Alignment)
         */
        @NonNull
        public Builder setAlignment(@NonNull Alignment alignment) {
            mAlignment = alignment;
            return this;
        }

        /**
         * Set the text direction heuristics.
         *
         * The text direction heuristics is used to resolve text direction on the text.
         *
         * The default value is {@link TextDirectionHeuristics#FIRSTSTRONG_LTR}
         *
         * @param textDirection a text direction heuristic.
         * @return this builder instance.
         * @see TextDirectionHeuristics
         * @see Layout#getTextDirectionHeuristic()
         * @see StaticLayout.Builder#setTextDirection(TextDirectionHeuristic)
         */
        @NonNull
        public Builder setTextDirectionHeuristic(@NonNull TextDirectionHeuristic textDirection) {
            mTextDir = textDirection;
            return this;
        }

        /**
         * Set the line spacing amount.
         *
         * The specified amount of pixels will be added to each line.
         *
         * The default value is {@code 0}. The negative value is allowed for squeezing lines.
         *
         * @param amount an amount of pixels to be added to line height.
         * @return this builder instance.
         * @see Layout#getLineSpacingAmount()
         * @see Layout#getSpacingAdd()
         * @see StaticLayout.Builder#setLineSpacing(float, float)
         */
        @NonNull
        public Builder setLineSpacingAmount(float amount) {
            mSpacingAdd = amount;
            return this;
        }

        /**
         * Set the line spacing multiplier.
         *
         * The specified value will be multiplied to each line.
         *
         * The default value is {@code 1}.
         *
         * @param multiplier a multiplier to be applied to the line height
         * @return this builder instance.
         * @see Layout#getLineSpacingMultiplier()
         * @see Layout#getSpacingMultiplier()
         * @see StaticLayout.Builder#setLineSpacing(float, float)
         */
        @NonNull
        public Builder setLineSpacingMultiplier(@FloatRange(from = 0) float multiplier) {
            mSpacingMult = multiplier;
            return this;
        }

        /**
         * Set whether including extra padding into the first and the last line height.
         *
         * By setting true, the first line of the text and the last line of the text will have extra
         * vertical space for avoiding clipping.
         *
         * The default value is {@code true}.
         *
         * @param includeFontPadding true for including extra space into first and last line.
         * @return this builder instance.
         * @see Layout#isFontPaddingIncluded()
         * @see StaticLayout.Builder#setIncludePad(boolean)
         */
        @NonNull
        public Builder setFontPaddingIncluded(boolean includeFontPadding) {
            mIncludePad = includeFontPadding;
            return this;
        }

        /**
         * Set whether to respect the ascent and descent of the fallback fonts.
         *
         * Set whether to respect the ascent and descent of the fallback fonts that are used in
         * displaying the text (which is needed to avoid text from consecutive lines running into
         * each other). If set, fallback fonts that end up getting used can increase the ascent
         * and descent of the lines that they are used on.
         *
         * The default value is {@code false}
         *
         * @param fallbackLineSpacing whether to expand line height based on fallback fonts.
         * @return this builder instance.
         * @see Layout#isFallbackLineSpacingEnabled()
         * @see StaticLayout.Builder#setUseLineSpacingFromFallbacks(boolean)
         */
        @NonNull
        public Builder setFallbackLineSpacingEnabled(boolean fallbackLineSpacing) {
            mFallbackLineSpacing = fallbackLineSpacing;
            return this;
        }

        /**
         * Set the width as used for ellipsizing purpose in pixels.
         *
         * The passed value is ignored and forced to set to the value of width constraint passed in
         * constructor if no ellipsize option is set.
         *
         * The default value is the width constraint.
         *
         * @param ellipsizeWidth a ellipsizing width in pixels.
         * @return this builder instance.
         * @see Layout#getEllipsizedWidth()
         * @see StaticLayout.Builder#setEllipsizedWidth(int)
         */
        @NonNull
        public Builder setEllipsizedWidth(@IntRange(from = 0) int ellipsizeWidth) {
            mEllipsizedWidth = ellipsizeWidth;
            return this;
        }

        /**
         * Set the ellipsizing type.
         *
         * By setting null, the ellipsize is disabled.
         *
         * The default value is {@code null}.
         *
         * @param ellipsize type of the ellipsize. null for disabling ellipsize.
         * @return this builder instance.
         * @see Layout#getEllipsize()
         * @see StaticLayout.Builder#getEllipsize()
         * @see android.text.TextUtils.TruncateAt
         */
        @NonNull
        public Builder setEllipsize(@Nullable TextUtils.TruncateAt ellipsize) {
            mEllipsize = ellipsize;
            return this;
        }

        /**
         * Set the maximum number of lines.
         *
         * The default value is unlimited.
         *
         * @param maxLines maximum number of lines in the layout.
         * @return this builder instance.
         * @see Layout#getMaxLines()
         * @see StaticLayout.Builder#setMaxLines(int)
         */
        @NonNull
        public Builder setMaxLines(@IntRange(from = 1) int maxLines) {
            mMaxLines = maxLines;
            return this;
        }

        /**
         * Set the line break strategy.
         *
         * The default value is {@link Layout#BREAK_STRATEGY_SIMPLE}.
         *
         * @param breakStrategy a break strategy for line breaking.
         * @return this builder instance.
         * @see Layout#getBreakStrategy()
         * @see StaticLayout.Builder#setBreakStrategy(int)
         * @see Layout#BREAK_STRATEGY_SIMPLE
         * @see Layout#BREAK_STRATEGY_HIGH_QUALITY
         * @see Layout#BREAK_STRATEGY_BALANCED
         */
        @NonNull
        public Builder setBreakStrategy(@BreakStrategy int breakStrategy) {
            mBreakStrategy = breakStrategy;
            return this;
        }

        /**
         * Set the hyphenation frequency.
         *
         * The default value is {@link Layout#HYPHENATION_FREQUENCY_NONE}.
         *
         * @param hyphenationFrequency a hyphenation frequency.
         * @return this builder instance.
         * @see Layout#getHyphenationFrequency()
         * @see StaticLayout.Builder#setHyphenationFrequency(int)
         * @see Layout#HYPHENATION_FREQUENCY_NONE
         * @see Layout#HYPHENATION_FREQUENCY_NORMAL
         * @see Layout#HYPHENATION_FREQUENCY_FULL
         * @see Layout#HYPHENATION_FREQUENCY_NORMAL_FAST
         * @see Layout#HYPHENATION_FREQUENCY_FULL_FAST
         */
        @NonNull
        public Builder setHyphenationFrequency(@HyphenationFrequency int hyphenationFrequency) {
            mHyphenationFrequency = hyphenationFrequency;
            return this;
        }

        /**
         * Set visually left indents in pixels per lines.
         *
         * For the lines past the last element in the array, the last element repeats. Passing null
         * for disabling indents.
         *
         * Note that even with the RTL layout, this method reserve spacing at the visually left of
         * the line.
         *
         * The default value is {@code null}.
         *
         * @param leftIndents array of indents values for the left margins in pixels.
         * @return this builder instance.
         * @see Layout#getLeftIndents()
         * @see Layout#getRightIndents()
         * @see Layout.Builder#setRightIndents(int[])
         * @see StaticLayout.Builder#setIndents(int[], int[])
         */
        @NonNull
        public Builder setLeftIndents(@Nullable int[] leftIndents) {
            mLeftIndents = leftIndents;
            return this;
        }

        /**
         * Set visually right indents in pixels per lines.
         *
         * For the lines past the last element in the array, the last element repeats. Passing null
         * for disabling indents.
         *
         * Note that even with the RTL layout, this method reserve spacing at the visually right of
         * the line.
         *
         * The default value is {@code null}.
         *
         * @param rightIndents array of indents values for the right margins in pixels.
         * @return this builder instance.
         * @see Layout#getLeftIndents()
         * @see Layout#getRightIndents()
         * @see Layout.Builder#setLeftIndents(int[])
         * @see StaticLayout.Builder#setIndents(int[], int[])
         */
        @NonNull
        public Builder setRightIndents(@Nullable int[] rightIndents) {
            mRightIndents = rightIndents;
            return this;
        }

        /**
         * Set justification mode.
         *
         * When justification mode is {@link Layout#JUSTIFICATION_MODE_INTER_WORD}, the word spacing
         * on the given Paint passed to the constructor will be ignored. This behavior also affects
         * spans which change the word spacing.
         *
         * The default value is {@link Layout#JUSTIFICATION_MODE_NONE}.
         *
         * @param justificationMode justification mode.
         * @return this builder instance.
         * @see Layout#getJustificationMode()
         * @see StaticLayout.Builder#setJustificationMode(int)
         * @see Layout#JUSTIFICATION_MODE_NONE
         * @see Layout#JUSTIFICATION_MODE_INTER_WORD
         */
        @NonNull
        public Builder setJustificationMode(@JustificationMode int justificationMode) {
            mJustificationMode = justificationMode;
            return this;
        }

        /**
         * Set the line break configuration.
         *
         * The default value is a LinebreakConfig instance that has
         * {@link LineBreakConfig#LINE_BREAK_STYLE_NONE} and
         * {@link LineBreakConfig#LINE_BREAK_WORD_STYLE_NONE}.
         *
         * @param lineBreakConfig the line break configuration
         * @return this builder instance.
         * @see Layout#getLineBreakConfig()
         * @see StaticLayout.Builder#setLineBreakConfig(LineBreakConfig)
         */
        @NonNull
        public Builder setLineBreakConfig(@NonNull LineBreakConfig lineBreakConfig) {
            mLineBreakConfig = lineBreakConfig;
            return this;
        }

        /**
         * Set true for using width of bounding box as a source of automatic line breaking and
         * drawing.
         *
         * If this value is false, the Layout determines the drawing offset and automatic line
         * breaking based on total advances. By setting true, use all joined glyph's bounding boxes
         * as a source of text width.
         *
         * If the font has glyphs that have negative bearing X or its xMax is greater than advance,
         * the glyph clipping can happen because the drawing area may be bigger. By setting this to
         * true, the Layout will reserve more spaces for drawing.
         *
         * @param useBoundsForWidth True for using bounding box, false for advances.
         * @return this builder instance
         * @see Layout#getUseBoundsForWidth()
         * @see StaticLayout.Builder#setUseBoundsForWidth(boolean)
         */
        // The corresponding getter is getUseBoundsForWidth
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
        public Builder setUseBoundsForWidth(boolean useBoundsForWidth) {
            mUseBoundsForWidth = useBoundsForWidth;
            return this;
        }

        /**
         * Set the minimum font metrics used for line spacing.
         *
         * <p>
         * {@code null} is the default value. If {@code null} is set or left it as default, the font
         * metrics obtained by {@link Paint#getFontMetricsForLocale(Paint.FontMetrics)} is used.
         *
         * <p>
         * The minimum meaning here is the minimum value of line spacing: maximum value of
         * {@link Paint#ascent()}, minimum value of {@link Paint#descent()}.
         *
         * <p>
         * By setting this value, each line will have minimum line spacing regardless of the text
         * rendered. For example, usually Japanese script has larger vertical metrics than Latin
         * script. By setting the metrics obtained by
         * {@link Paint#getFontMetricsForLocale(Paint.FontMetrics)} for Japanese or leave it
         * {@code null} if the Paint's locale is Japanese, the line spacing for Japanese is reserved
         * if the text is an English text. If the vertical metrics of the text is larger than
         * Japanese, for example Burmese, the bigger font metrics is used.
         *
         * @param minimumFontMetrics A minimum font metrics. Passing {@code null} for using the
         *                          value obtained by
         *                          {@link Paint#getFontMetricsForLocale(Paint.FontMetrics)}
         * @see android.widget.TextView#setMinimumFontMetrics(Paint.FontMetrics)
         * @see android.widget.TextView#getMinimumFontMetrics()
         * @see Layout#getMinimumFontMetrics()
         * @see StaticLayout.Builder#setMinimumFontMetrics(Paint.FontMetrics)
         * @see DynamicLayout.Builder#setMinimumFontMetrics(Paint.FontMetrics)
         */
        @NonNull
        @FlaggedApi(FLAG_FIX_LINE_HEIGHT_FOR_LOCALE)
        public Builder setMinimumFontMetrics(@Nullable Paint.FontMetrics minimumFontMetrics) {
            mMinimumFontMetrics = minimumFontMetrics;
            return this;
        }

        private BoringLayout.Metrics isBoring() {
            if (mStart != 0 || mEnd != mText.length()) {  // BoringLayout only support entire text.
                return null;
            }
            BoringLayout.Metrics metrics = BoringLayout.isBoring(mText, mPaint, mTextDir,
                    mFallbackLineSpacing, mMinimumFontMetrics, null);
            if (metrics == null) {
                return null;
            }
            if (metrics.width <= mWidth) {
                return metrics;
            }
            if (mEllipsize != null) {
                return metrics;
            }
            return null;
        }

        /**
         * Build a Layout object.
         */
        @NonNull
        public Layout build() {
            BoringLayout.Metrics metrics = isBoring();
            if (metrics == null) {  // we cannot use BoringLayout, create StaticLayout.
                return StaticLayout.Builder.obtain(mText, mStart, mEnd, mPaint, mWidth)
                        .setAlignment(mAlignment)
                        .setLineSpacing(mSpacingAdd, mSpacingMult)
                        .setTextDirection(mTextDir)
                        .setIncludePad(mIncludePad)
                        .setUseLineSpacingFromFallbacks(mFallbackLineSpacing)
                        .setEllipsizedWidth(mEllipsizedWidth)
                        .setEllipsize(mEllipsize)
                        .setMaxLines(mMaxLines)
                        .setBreakStrategy(mBreakStrategy)
                        .setHyphenationFrequency(mHyphenationFrequency)
                        .setIndents(mLeftIndents, mRightIndents)
                        .setJustificationMode(mJustificationMode)
                        .setLineBreakConfig(mLineBreakConfig)
                        .setUseBoundsForWidth(mUseBoundsForWidth)
                        .build();
            } else {
                return new BoringLayout(
                        mText, mPaint, mWidth, mAlignment, mTextDir, mSpacingMult, mSpacingAdd,
                        mIncludePad, mFallbackLineSpacing, mEllipsizedWidth, mEllipsize, mMaxLines,
                        mBreakStrategy, mHyphenationFrequency, mLeftIndents, mRightIndents,
                        mJustificationMode, mLineBreakConfig, metrics, mUseBoundsForWidth,
                        mMinimumFontMetrics);
            }
        }

        private final CharSequence mText;
        private final int mStart;
        private final int mEnd;
        private final TextPaint mPaint;
        private final int mWidth;
        private Alignment mAlignment = Alignment.ALIGN_NORMAL;
        private float mSpacingMult = 1.0f;
        private float mSpacingAdd = 0.0f;
        private TextDirectionHeuristic mTextDir = TextDirectionHeuristics.FIRSTSTRONG_LTR;
        private boolean mIncludePad = true;
        private boolean mFallbackLineSpacing = false;
        private int mEllipsizedWidth;
        private TextUtils.TruncateAt mEllipsize = null;
        private int mMaxLines = Integer.MAX_VALUE;
        private int mBreakStrategy = BREAK_STRATEGY_SIMPLE;
        private int mHyphenationFrequency = HYPHENATION_FREQUENCY_NONE;
        private int[] mLeftIndents = null;
        private int[] mRightIndents = null;
        private int mJustificationMode = JUSTIFICATION_MODE_NONE;
        private LineBreakConfig mLineBreakConfig = LineBreakConfig.NONE;
        private boolean mUseBoundsForWidth;
        private Paint.FontMetrics mMinimumFontMetrics;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Getters of parameters that is used for building Layout instance
    ///////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Return the text used for creating this layout.
     *
     * @return the text used for creating this layout.
     * @see Layout.Builder
     */
    @NonNull
    public final CharSequence getText() {
        return mText;
    }

    /**
     * Return the paint used for creating this layout.
     *
     * Do not modify the returned paint object. This paint object will still be used for
     * drawing/measuring text.
     *
     * @return the paint used for creating this layout.
     * @see Layout.Builder
     */
    @NonNull
    public final TextPaint getPaint() {
        return mPaint;
    }

    /**
     * Return the width used for creating this layout in pixels.
     *
     * @return the width used for creating this layout in pixels.
     * @see Layout.Builder
     */
    @IntRange(from = 0)
    public final int getWidth() {
        return mWidth;
    }

    /**
     * Returns the alignment used for creating this layout in pixels.
     *
     * @return the alignment used for creating this layout.
     * @see Layout.Builder#setAlignment(Alignment)
     * @see StaticLayout.Builder#setAlignment(Alignment)
     */
    @NonNull
    public final Alignment getAlignment() {
        return mAlignment;
    }

    /**
     * Returns the text direction heuristic used for creating this layout.
     *
     * @return the text direction heuristic used for creating this layout
     * @see Layout.Builder#setTextDirectionHeuristic(TextDirectionHeuristic)
     * @see StaticLayout.Builder#setTextDirection(TextDirectionHeuristic)
     */
    @NonNull
    @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
    public final TextDirectionHeuristic getTextDirectionHeuristic() {
        return mTextDir;
    }

    /**
     * Returns the multiplier applied to the line height.
     *
     * This is an alias of {@link #getLineSpacingMultiplier}.
     *
     * @return the line height multiplier.
     * @see Layout.Builder#setLineSpacingMultiplier(float)
     * @see StaticLayout.Builder#setLineSpacing(float, float)
     * @see Layout#getLineSpacingMultiplier()
     */
    public final float getSpacingMultiplier() {
        return getLineSpacingMultiplier();
    }

    /**
     * Returns the multiplier applied to the line height.
     *
     * @return the line height multiplier.
     * @see Layout.Builder#setLineSpacingMultiplier(float)
     * @see StaticLayout.Builder#setLineSpacing(float, float)
     * @see Layout#getSpacingMultiplier()
     */
    @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
    public final float getLineSpacingMultiplier() {
        return mSpacingMult;
    }

    /**
     * Returns the amount added to the line height.
     *
     * This is an alias of {@link #getLineSpacingAmount()}.
     *
     * @return the line height additional amount.
     * @see Layout.Builder#setLineSpacingAmount(float)
     * @see StaticLayout.Builder#setLineSpacing(float, float)
     * @see Layout#getLineSpacingAmount()
     */
    public final float getSpacingAdd() {
        return getLineSpacingAmount();
    }

    /**
     * Returns the amount added to the line height.
     *
     * @return the line height additional amount.
     * @see Layout.Builder#setLineSpacingAmount(float)
     * @see StaticLayout.Builder#setLineSpacing(float, float)
     * @see Layout#getSpacingAdd()
     */
    @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
    public final float getLineSpacingAmount() {
        return mSpacingAdd;
    }

    /**
     * Returns true if this layout is created with increased line height.
     *
     * @return true if the layout is created with increased line height.
     * @see Layout.Builder#setFontPaddingIncluded(boolean)
     * @see StaticLayout.Builder#setIncludePad(boolean)
     */
    @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
    public final boolean isFontPaddingIncluded() {
        return mIncludePad;
    }

    /**
     * Return true if the fallback line space is enabled in this Layout.
     *
     * @return true if the fallback line space is enabled. Otherwise, returns false.
     * @see Layout.Builder#setFallbackLineSpacingEnabled(boolean)
     * @see StaticLayout.Builder#setUseLineSpacingFromFallbacks(boolean)
     */
    // not being final because of already published API.
    public boolean isFallbackLineSpacingEnabled() {
        return mFallbackLineSpacing;
    }

    /**
     * Return the width to which this layout is ellipsized.
     *
     * If no ellipsize is applied, the same amount of {@link #getWidth} is returned.
     *
     * @return the amount of ellipsized width in pixels.
     * @see Layout.Builder#setEllipsizedWidth(int)
     * @see StaticLayout.Builder#setEllipsizedWidth(int)
     * @see Layout.Builder#setEllipsize(TextUtils.TruncateAt)
     * @see StaticLayout.Builder#setEllipsize(TextUtils.TruncateAt)
     * @see Layout#getEllipsize()
     */
    @IntRange(from = 0)
    public int getEllipsizedWidth() {  // not being final because of already published API.
        return mEllipsizedWidth;
    }

    /**
     * Return the ellipsize option used for creating this layout.
     *
     * May return null if no ellipsize option was selected.
     *
     * @return The ellipsize option used for creating this layout, or null if no ellipsize option
     * was selected.
     * @see Layout.Builder#setEllipsize(TextUtils.TruncateAt)
     * @see StaticLayout.Builder#setEllipsize(TextUtils.TruncateAt)
     * @see Layout.Builder#setEllipsizedWidth(int)
     * @see StaticLayout.Builder#setEllipsizedWidth(int)
     * @see Layout#getEllipsizedWidth()
     */
    @Nullable
    @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
    public final TextUtils.TruncateAt getEllipsize() {
        return mEllipsize;
    }

    /**
     * Return the maximum lines allowed used for creating this layout.
     *
     * Note that this is not an actual line count of this layout. Use {@link #getLineCount()} for
     * getting the actual line count of this layout.
     *
     * @return the maximum lines allowed used for creating this layout.
     * @see Layout.Builder#setMaxLines(int)
     * @see StaticLayout.Builder#setMaxLines(int)
     */
    @IntRange(from = 1)
    @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
    public final int getMaxLines() {
        return mMaxLines;
    }

    /**
     * Return the break strategy used for creating this layout.
     *
     * @return the break strategy used for creating this layout.
     * @see Layout.Builder#setBreakStrategy(int)
     * @see StaticLayout.Builder#setBreakStrategy(int)
     */
    @BreakStrategy
    @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
    public final int getBreakStrategy() {
        return mBreakStrategy;
    }

    /**
     * Return the hyphenation frequency used for creating this layout.
     *
     * @return the hyphenation frequency used for creating this layout.
     * @see Layout.Builder#setHyphenationFrequency(int)
     * @see StaticLayout.Builder#setHyphenationFrequency(int)
     */
    @HyphenationFrequency
    @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
    public final int getHyphenationFrequency() {
        return mHyphenationFrequency;
    }

    /**
     * Return a copy of the left indents used for this layout.
     *
     * May return null if no left indentation is applied.
     *
     * @return the array of left indents in pixels.
     * @see Layout.Builder#setLeftIndents(int[])
     * @see Layout.Builder#setRightIndents(int[])
     * @see StaticLayout.Builder#setIndents(int[], int[])
     */
    @Nullable
    @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
    public final int[] getLeftIndents() {
        if (mLeftIndents == null) {
            return null;
        }
        int[] newArray = new int[mLeftIndents.length];
        System.arraycopy(mLeftIndents, 0, newArray, 0, newArray.length);
        return newArray;
    }

    /**
     * Return a copy of the right indents used for this layout.
     *
     * May return null if no right indentation is applied.
     *
     * @return the array of right indents in pixels.
     * @see Layout.Builder#setLeftIndents(int[])
     * @see Layout.Builder#setRightIndents(int[])
     * @see StaticLayout.Builder#setIndents(int[], int[])
     */
    @Nullable
    @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
    public final int[] getRightIndents() {
        if (mRightIndents == null) {
            return null;
        }
        int[] newArray = new int[mRightIndents.length];
        System.arraycopy(mRightIndents, 0, newArray, 0, newArray.length);
        return newArray;
    }

    /**
     * Return the justification mode used for creating this layout.
     *
     * @return the justification mode used for creating this layout.
     * @see Layout.Builder#setJustificationMode(int)
     * @see StaticLayout.Builder#setJustificationMode(int)
     */
    @JustificationMode
    @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
    public final int getJustificationMode() {
        return mJustificationMode;
    }

    /**
     * Gets the {@link LineBreakConfig} used for creating this layout.
     *
     * Do not modify the returned object.
     *
     * @return The line break config used for creating this layout.
     */
    // not being final because of subclass has already published API.
    @NonNull
    @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
    public LineBreakConfig getLineBreakConfig() {
        return mLineBreakConfig;
    }

    /**
     * Returns true if using bounding box as a width, false for using advance as a width.
     *
     * @return True if using bounding box for width, false if using advance for width.
     * @see android.widget.TextView#setUseBoundsForWidth(boolean)
     * @see android.widget.TextView#getUseBoundsForWidth()
     * @see StaticLayout.Builder#setUseBoundsForWidth(boolean)
     * @see DynamicLayout.Builder#setUseBoundsForWidth(boolean)
     */
    @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
    public boolean getUseBoundsForWidth() {
        return mUseBoundsForWidth;
    }

    /**
     * Get the minimum font metrics used for line spacing.
     *
     * @see android.widget.TextView#setMinimumFontMetrics(Paint.FontMetrics)
     * @see android.widget.TextView#getMinimumFontMetrics()
     * @see Layout.Builder#setMinimumFontMetrics(Paint.FontMetrics)
     * @see StaticLayout.Builder#setMinimumFontMetrics(Paint.FontMetrics)
     * @see DynamicLayout.Builder#setMinimumFontMetrics(Paint.FontMetrics)
     *
     * @return a minimum font metrics. {@code null} for using the value obtained by
     *         {@link Paint#getFontMetricsForLocale(Paint.FontMetrics)}
     */
    @Nullable
    @FlaggedApi(FLAG_FIX_LINE_HEIGHT_FOR_LOCALE)
    public Paint.FontMetrics getMinimumFontMetrics() {
        return mMinimumFontMetrics;
    }
}
