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

import static com.android.text.flags.Flags.FLAG_USE_BOUNDS_FOR_WIDTH;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.text.LineBreakConfig;
import android.os.Trace;
import android.text.style.ParagraphStyle;

/**
 * A BoringLayout is a very simple Layout implementation for text that
 * fits on a single line and is all left-to-right characters.
 * You will probably never want to make one of these yourself;
 * if you do, be sure to call {@link #isBoring} first to make sure
 * the text meets the criteria.
 * <p>This class is used by widgets to control text layout. You should not need
 * to use this class directly unless you are implementing your own widget
 * or custom display object, in which case
 * you are encouraged to use a Layout instead of calling
 * {@link android.graphics.Canvas#drawText(java.lang.CharSequence, int, int, float, float, android.graphics.Paint)
 *  Canvas.drawText()} directly.</p>
 */
public class BoringLayout extends Layout implements TextUtils.EllipsizeCallback {

    /**
     * Utility function to construct a BoringLayout instance.
     *
     * @param source the text to render
     * @param paint the default paint for the layout
     * @param outerWidth the wrapping width for the text
     * @param align whether to left, right, or center the text
     * @param spacingMult this value is no longer used by BoringLayout
     * @param spacingAdd this value is no longer used by BoringLayout
     * @param metrics {@code #Metrics} instance that contains information about FontMetrics and
     *                line width
     * @param includePad set whether to include extra space beyond font ascent and descent which is
     *                   needed to avoid clipping in some scripts
     */
    public static BoringLayout make(CharSequence source, TextPaint paint, int outerWidth,
            Alignment align, float spacingMult, float spacingAdd, BoringLayout.Metrics metrics,
            boolean includePad) {
        return new BoringLayout(source, paint, outerWidth, align, spacingMult, spacingAdd, metrics,
                includePad);
    }

    /**
     * Utility function to construct a BoringLayout instance.
     *
     * @param source the text to render
     * @param paint the default paint for the layout
     * @param outerWidth the wrapping width for the text
     * @param align whether to left, right, or center the text
     * @param spacingmult this value is no longer used by BoringLayout
     * @param spacingadd this value is no longer used by BoringLayout
     * @param metrics {@code #Metrics} instance that contains information about FontMetrics and
     *                line width
     * @param includePad set whether to include extra space beyond font ascent and descent which is
     *                   needed to avoid clipping in some scripts
     * @param ellipsize whether to ellipsize the text if width of the text is longer than the
     *                  requested width
     * @param ellipsizedWidth the width to which this Layout is ellipsizing. If {@code ellipsize} is
     *                        {@code null}, or is {@link TextUtils.TruncateAt#MARQUEE} this value is
     *                        not used, {@code outerWidth} is used instead
     */
    public static BoringLayout make(CharSequence source, TextPaint paint, int outerWidth,
            Alignment align, float spacingmult, float spacingadd, BoringLayout.Metrics metrics,
            boolean includePad, TextUtils.TruncateAt ellipsize, int ellipsizedWidth) {
        return new BoringLayout(source, paint, outerWidth, align, spacingmult, spacingadd, metrics,
                includePad, ellipsize, ellipsizedWidth);
    }

    /**
     * Utility function to construct a BoringLayout instance.
     *
     * The spacing multiplier and additional amount spacing are not used by BoringLayout.
     * {@link Layout#getSpacingMultiplier()} will return 1.0 and {@link Layout#getSpacingAdd()} will
     * return 0.0.
     *
     * @param source the text to render
     * @param paint the default paint for the layout
     * @param outerWidth the wrapping width for the text
     * @param align whether to left, right, or center the text
     * @param metrics {@code #Metrics} instance that contains information about FontMetrics and
     *                line width
     * @param includePad set whether to include extra space beyond font ascent and descent which is
     *                   needed to avoid clipping in some scripts
     * @param ellipsize whether to ellipsize the text if width of the text is longer than the
     *                  requested width. null if ellipsis is not applied.
     * @param ellipsizedWidth the width to which this Layout is ellipsizing. If {@code ellipsize} is
     *                        {@code null}, or is {@link TextUtils.TruncateAt#MARQUEE} this value is
     *                        not used, {@code outerWidth} is used instead
     * @param useFallbackLineSpacing True for adjusting the line spacing based on fallback fonts.
     *                              False for keeping the first font's line height. If some glyphs
     *                              requires larger vertical spaces, by passing true to this
     *                              argument, the layout increase the line height to fit all glyphs.
     */
    public static @NonNull BoringLayout make(
            @NonNull CharSequence source, @NonNull TextPaint paint,
            @IntRange(from = 0) int outerWidth,
            @NonNull Alignment align, @NonNull BoringLayout.Metrics metrics,
            boolean includePad, @Nullable TextUtils.TruncateAt ellipsize,
            @IntRange(from = 0) int ellipsizedWidth, boolean useFallbackLineSpacing) {
        return new BoringLayout(source, paint, outerWidth, align, 1f, 0f, metrics, includePad,
                ellipsize, ellipsizedWidth, useFallbackLineSpacing);
    }

    /**
     * Returns a BoringLayout for the specified text, potentially reusing
     * this one if it is already suitable.  The caller must make sure that
     * no one is still using this Layout.
     *
     * @param source the text to render
     * @param paint the default paint for the layout
     * @param outerwidth the wrapping width for the text
     * @param align whether to left, right, or center the text
     * @param spacingMult this value is no longer used by BoringLayout
     * @param spacingAdd this value is no longer used by BoringLayout
     * @param metrics {@code #Metrics} instance that contains information about FontMetrics and
     *                line width
     * @param includePad set whether to include extra space beyond font ascent and descent which is
     *                   needed to avoid clipping in some scripts
     */
    public BoringLayout replaceOrMake(CharSequence source, TextPaint paint, int outerwidth,
            Alignment align, float spacingMult, float spacingAdd, BoringLayout.Metrics metrics,
            boolean includePad) {
        replaceWith(source, paint, outerwidth, align, spacingMult, spacingAdd);

        mEllipsizedWidth = outerwidth;
        mEllipsizedStart = 0;
        mEllipsizedCount = 0;
        mUseFallbackLineSpacing = false;

        init(source, paint, align, metrics, includePad, true, false /* useFallbackLineSpacing */);
        return this;
    }

    /**
     * Returns a BoringLayout for the specified text, potentially reusing
     * this one if it is already suitable.  The caller must make sure that
     * no one is still using this Layout.
     *
     * The spacing multiplier and additional amount spacing are not used by BoringLayout.
     * {@link Layout#getSpacingMultiplier()} will return 1.0 and {@link Layout#getSpacingAdd()} will
     * return 0.0.
     *
     * @param source the text to render
     * @param paint the default paint for the layout
     * @param outerWidth the wrapping width for the text
     * @param align whether to left, right, or center the text
     * @param metrics {@code #Metrics} instance that contains information about FontMetrics and
     *                line width
     * @param includePad set whether to include extra space beyond font ascent and descent which is
     *                   needed to avoid clipping in some scripts
     * @param ellipsize whether to ellipsize the text if width of the text is longer than the
     *                  requested width. null if ellipsis not applied.
     * @param ellipsizedWidth the width to which this Layout is ellipsizing. If {@code ellipsize} is
     *                        {@code null}, or is {@link TextUtils.TruncateAt#MARQUEE} this value is
     *                        not used, {@code outerWidth} is used instead
     * @param useFallbackLineSpacing True for adjusting the line spacing based on fallback fonts.
     *                              False for keeping the first font's line height. If some glyphs
     *                              requires larger vertical spaces, by passing true to this
     *                              argument, the layout increase the line height to fit all glyphs.
     */
    public @NonNull BoringLayout replaceOrMake(@NonNull CharSequence source,
            @NonNull TextPaint paint, @IntRange(from = 0) int outerWidth,
            @NonNull Alignment align, @NonNull BoringLayout.Metrics metrics, boolean includePad,
            @Nullable TextUtils.TruncateAt ellipsize, @IntRange(from = 0) int ellipsizedWidth,
            boolean useFallbackLineSpacing) {
        return replaceOrMake(source, paint, outerWidth, align, 1.0f, 0.0f, metrics, includePad,
                ellipsize, ellipsizedWidth, useFallbackLineSpacing, false /* useBoundsForWidth */,
                null /* minimumFontMetrics */);
    }

    /** @hide */
    public @NonNull BoringLayout replaceOrMake(@NonNull CharSequence source,
            @NonNull TextPaint paint, @IntRange(from = 0) int outerWidth,
            @NonNull Alignment align, float spacingMultiplier, float spacingAmount,
            @NonNull BoringLayout.Metrics metrics, boolean includePad,
            @Nullable TextUtils.TruncateAt ellipsize, @IntRange(from = 0) int ellipsizedWidth,
            boolean useFallbackLineSpacing, boolean useBoundsForWidth,
            @Nullable Paint.FontMetrics minimumFontMetrics) {
        boolean trust;

        if (ellipsize == null || ellipsize == TextUtils.TruncateAt.MARQUEE) {
            replaceWith(source, paint, outerWidth, align, 1f, 0f);

            mEllipsizedWidth = outerWidth;
            mEllipsizedStart = 0;
            mEllipsizedCount = 0;
            trust = true;
        } else {
            replaceWith(TextUtils.ellipsize(source, paint, ellipsizedWidth, ellipsize, true, this),
                    paint, outerWidth, align, spacingMultiplier, spacingAmount);

            mEllipsizedWidth = ellipsizedWidth;
            trust = false;
        }

        mUseFallbackLineSpacing = useFallbackLineSpacing;

        init(getText(), paint, align, metrics, includePad, trust,
                useFallbackLineSpacing);
        return this;
    }

    /**
     * Returns a BoringLayout for the specified text, potentially reusing
     * this one if it is already suitable.  The caller must make sure that
     * no one is still using this Layout.
     *
     * @param source the text to render
     * @param paint the default paint for the layout
     * @param outerWidth the wrapping width for the text
     * @param align whether to left, right, or center the text
     * @param spacingMult this value is no longer used by BoringLayout
     * @param spacingAdd this value is no longer used by BoringLayout
     * @param metrics {@code #Metrics} instance that contains information about FontMetrics and
     *                line width
     * @param includePad set whether to include extra space beyond font ascent and descent which is
     *                   needed to avoid clipping in some scripts
     * @param ellipsize whether to ellipsize the text if width of the text is longer than the
     *                  requested width
     * @param ellipsizedWidth the width to which this Layout is ellipsizing. If {@code ellipsize} is
     *                        {@code null}, or is {@link TextUtils.TruncateAt#MARQUEE} this value is
     *                        not used, {@code outerWidth} is used instead
     */
    public BoringLayout replaceOrMake(CharSequence source, TextPaint paint, int outerWidth,
            Alignment align, float spacingMult, float spacingAdd, BoringLayout.Metrics metrics,
            boolean includePad, TextUtils.TruncateAt ellipsize, int ellipsizedWidth) {
        return replaceOrMake(source, paint, outerWidth, align, metrics,
                includePad, ellipsize, ellipsizedWidth, false /* useFallbackLineSpacing */);
    }

    /**
     * @param source the text to render
     * @param paint the default paint for the layout
     * @param outerwidth the wrapping width for the text
     * @param align whether to left, right, or center the text
     * @param spacingMult this value is no longer used by BoringLayout
     * @param spacingAdd this value is no longer used by BoringLayout
     * @param metrics {@code #Metrics} instance that contains information about FontMetrics and
     *                line width
     * @param includePad set whether to include extra space beyond font ascent and descent which is
     *                   needed to avoid clipping in some scripts
     */
    public BoringLayout(CharSequence source, TextPaint paint, int outerwidth, Alignment align,
            float spacingMult, float spacingAdd, BoringLayout.Metrics metrics, boolean includePad) {
        super(source, paint, outerwidth, align, TextDirectionHeuristics.LTR, spacingMult,
                spacingAdd, includePad, false /* fallbackLineSpacing */,
                outerwidth /* ellipsizedWidth */, null /* ellipsize */, 1 /* maxLines */,
                BREAK_STRATEGY_SIMPLE, HYPHENATION_FREQUENCY_NONE, null /* leftIndents */,
                null /* rightIndents */, JUSTIFICATION_MODE_NONE, LineBreakConfig.NONE, false,
                false /* shiftDrawingOffsetForStartOverhang */, null);

        mEllipsizedWidth = outerwidth;
        mEllipsizedStart = 0;
        mEllipsizedCount = 0;
        mUseFallbackLineSpacing = false;

        init(source, paint, align, metrics, includePad, true, false /* useFallbackLineSpacing */);
    }

    /**
     *
     * @param source the text to render
     * @param paint the default paint for the layout
     * @param outerWidth the wrapping width for the text
     * @param align whether to left, right, or center the text
     * @param spacingMult this value is no longer used by BoringLayout
     * @param spacingAdd this value is no longer used by BoringLayout
     * @param metrics {@code #Metrics} instance that contains information about FontMetrics and
     *                line width
     * @param includePad set whether to include extra space beyond font ascent and descent which is
     *                   needed to avoid clipping in some scripts
     * @param ellipsize whether to ellipsize the text if width of the text is longer than the
     *                  requested {@code outerWidth}
     * @param ellipsizedWidth the width to which this Layout is ellipsizing. If {@code ellipsize} is
     *                        {@code null}, or is {@link TextUtils.TruncateAt#MARQUEE} this value is
     *                        not used, {@code outerWidth} is used instead
     */
    public BoringLayout(CharSequence source, TextPaint paint, int outerWidth, Alignment align,
            float spacingMult, float spacingAdd, BoringLayout.Metrics metrics, boolean includePad,
            TextUtils.TruncateAt ellipsize, int ellipsizedWidth) {
        this(source, paint, outerWidth, align, spacingMult, spacingAdd, metrics, includePad,
                ellipsize, ellipsizedWidth, false /* fallbackLineSpacing */);
    }

    /**
     *
     * @param source the text to render
     * @param paint the default paint for the layout
     * @param outerWidth the wrapping width for the text
     * @param align whether to left, right, or center the text
     * @param spacingMult this value is no longer used by BoringLayout
     * @param spacingAdd this value is no longer used by BoringLayout
     * @param metrics {@code #Metrics} instance that contains information about FontMetrics and
     *                line width
     * @param includePad set whether to include extra space beyond font ascent and descent which is
     *                   needed to avoid clipping in some scripts
     * @param ellipsize whether to ellipsize the text if width of the text is longer than the
     *                  requested {@code outerWidth}. null if ellipsis is not applied.
     * @param ellipsizedWidth the width to which this Layout is ellipsizing. If {@code ellipsize} is
     *                        {@code null}, or is {@link TextUtils.TruncateAt#MARQUEE} this value is
     *                        not used, {@code outerWidth} is used instead
     * @param useFallbackLineSpacing True for adjusting the line spacing based on fallback fonts.
     *                              False for keeping the first font's line height. If some glyphs
     *                              requires larger vertical spaces, by passing true to this
     *                              argument, the layout increase the line height to fit all glyphs.
     */
    public BoringLayout(
            @NonNull CharSequence source, @NonNull TextPaint paint,
            @IntRange(from = 0) int outerWidth, @NonNull Alignment align, float spacingMult,
            float spacingAdd, @NonNull BoringLayout.Metrics metrics, boolean includePad,
            @Nullable TextUtils.TruncateAt ellipsize, @IntRange(from = 0) int ellipsizedWidth,
            boolean useFallbackLineSpacing) {
        /*
         * It is silly to have to call super() and then replaceWith(),
         * but we can't use "this" for the callback until the call to
         * super() finishes.
         */
        this(source, paint, outerWidth, align, TextDirectionHeuristics.LTR, spacingMult,
                spacingAdd, includePad, useFallbackLineSpacing,
                ellipsizedWidth, ellipsize, 1 /* maxLines */,
                BREAK_STRATEGY_SIMPLE, HYPHENATION_FREQUENCY_NONE, null /* leftIndents */,
                null /* rightIndents */, JUSTIFICATION_MODE_NONE,
                LineBreakConfig.NONE, metrics, false /* useBoundsForWidth */,
                false /* shiftDrawingOffsetForStartOverhang */, null);
    }

    /** @hide */
    public BoringLayout(
            CharSequence text,
            TextPaint paint,
            int width,
            Alignment align,
            float spacingMult,
            float spacingAdd,
            boolean includePad,
            boolean fallbackLineSpacing,
            int ellipsizedWidth,
            TextUtils.TruncateAt ellipsize,
            Metrics metrics,
            boolean useBoundsForWidth,
            boolean shiftDrawingOffsetForStartOverhang,
            @Nullable Paint.FontMetrics minimumFontMetrics) {
        this(text, paint, width, align, TextDirectionHeuristics.LTR,
                spacingMult, spacingAdd, includePad, fallbackLineSpacing, ellipsizedWidth,
                ellipsize, 1 /* maxLines */, Layout.BREAK_STRATEGY_SIMPLE,
                Layout.HYPHENATION_FREQUENCY_NONE, null, null, Layout.JUSTIFICATION_MODE_NONE,
                LineBreakConfig.NONE, metrics, useBoundsForWidth,
                shiftDrawingOffsetForStartOverhang, minimumFontMetrics);
    }

    /* package */ BoringLayout(
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
            Metrics metrics,
            boolean useBoundsForWidth,
            boolean shiftDrawingOffsetForStartOverhang,
            @Nullable Paint.FontMetrics minimumFontMetrics) {

        super(text, paint, width, align, textDir, spacingMult, spacingAdd, includePad,
                fallbackLineSpacing, ellipsizedWidth, ellipsize, maxLines, breakStrategy,
                hyphenationFrequency, leftIndents, rightIndents, justificationMode,
                lineBreakConfig, useBoundsForWidth, shiftDrawingOffsetForStartOverhang,
                minimumFontMetrics);


        boolean trust;

        if (ellipsize == null || ellipsize == TextUtils.TruncateAt.MARQUEE) {
            mEllipsizedWidth = width;
            mEllipsizedStart = 0;
            mEllipsizedCount = 0;
            trust = true;
        } else {
            replaceWith(TextUtils.ellipsize(text, paint, ellipsizedWidth, ellipsize, true, this),
                        paint, width, align, spacingMult, spacingAdd);

            mEllipsizedWidth = ellipsizedWidth;
            trust = false;
        }

        mUseFallbackLineSpacing = fallbackLineSpacing;
        init(getText(), paint, align, metrics, includePad, trust, fallbackLineSpacing);
    }

    /* package */ void init(CharSequence source, TextPaint paint, Alignment align,
            BoringLayout.Metrics metrics, boolean includePad, boolean trustWidth,
            boolean useFallbackLineSpacing) {
        int spacing;

        if (source instanceof String && align == Layout.Alignment.ALIGN_NORMAL) {
            mDirect = source.toString();
        } else {
            mDirect = null;
        }

        mPaint = paint;

        if (includePad) {
            spacing = metrics.bottom - metrics.top;
            mDesc = metrics.bottom;
        } else {
            spacing = metrics.descent - metrics.ascent;
            mDesc = metrics.descent;
        }

        mBottom = spacing;

        if (trustWidth) {
            mMax = metrics.width;
        } else {
            /*
             * If we have ellipsized, we have to actually calculate the
             * width because the width that was passed in was for the
             * full text, not the ellipsized form.
             */
            TextLine line = TextLine.obtain();
            line.set(paint, source, 0, source.length(), Layout.DIR_LEFT_TO_RIGHT,
                    Layout.DIRS_ALL_LEFT_TO_RIGHT, false, null,
                    mEllipsizedStart, mEllipsizedStart + mEllipsizedCount, useFallbackLineSpacing);
            mMax = (int) Math.ceil(line.metrics(null, null, false, null));
            TextLine.recycle(line);
        }

        if (includePad) {
            mTopPadding = metrics.top - metrics.ascent;
            mBottomPadding = metrics.bottom - metrics.descent;
        }

        mDrawingBounds.set(metrics.mDrawingBounds);
        mDrawingBounds.offset(0, mBottom - mDesc);
    }

    /**
     * Determine and compute metrics if given text can be handled by BoringLayout.
     *
     * @param text a text
     * @param paint a paint
     * @return layout metric for the given text. null if given text is unable to be handled by
     *         BoringLayout.
     */
    public static Metrics isBoring(CharSequence text, TextPaint paint) {
        return isBoring(text, paint, TextDirectionHeuristics.FIRSTSTRONG_LTR, null);
    }

    /**
     * Determine and compute metrics if given text can be handled by BoringLayout.
     *
     * @param text a text
     * @param paint a paint
     * @param metrics a metrics object to be recycled. If null is passed, this function creat new
     *                object.
     * @return layout metric for the given text. If metrics is not null, this method fills values
     *         to given metrics object instead of allocating new metrics object. null if given text
     *         is unable to be handled by BoringLayout.
     */
    public static Metrics isBoring(CharSequence text, TextPaint paint, Metrics metrics) {
        return isBoring(text, paint, TextDirectionHeuristics.FIRSTSTRONG_LTR, metrics);
    }

    /**
     * Returns true if the text contains any RTL characters, bidi format characters, or surrogate
     * code units.
     */
    private static boolean hasAnyInterestingChars(CharSequence text, int textLength) {
        final int MAX_BUF_LEN = 500;
        final char[] buffer = TextUtils.obtain(MAX_BUF_LEN);
        try {
            for (int start = 0; start < textLength; start += MAX_BUF_LEN) {
                final int end = Math.min(start + MAX_BUF_LEN, textLength);

                // No need to worry about getting half codepoints, since we consider surrogate code
                // units "interesting" as soon we see one.
                TextUtils.getChars(text, start, end, buffer, 0);

                final int len = end - start;
                for (int i = 0; i < len; i++) {
                    final char c = buffer[i];
                    if (c == '\n' || c == '\t' || TextUtils.couldAffectRtl(c)) {
                        return true;
                    }
                }
            }
            return false;
        } finally {
            TextUtils.recycle(buffer);
        }
    }

    /**
     * Returns null if not boring; the width, ascent, and descent in the
     * provided Metrics object (or a new one if the provided one was null)
     * if boring.
     * @hide
     */
    @UnsupportedAppUsage
    public static Metrics isBoring(CharSequence text, TextPaint paint,
            TextDirectionHeuristic textDir, Metrics metrics) {
        return isBoring(text, paint, textDir, false /* useFallbackLineSpacing */, metrics);
    }

    /**
     * Returns null if not boring; the width, ascent, and descent in the
     * provided Metrics object (or a new one if the provided one was null)
     * if boring.
     *
     * @param text a text to be calculated text layout.
     * @param paint a paint object used for styling.
     * @param textDir a text direction.
     * @param useFallbackLineSpacing True for adjusting the line spacing based on fallback fonts.
     *                              False for keeping the first font's line height. If some glyphs
     *                              requires larger vertical spaces, by passing true to this
     *                              argument, the layout increase the line height to fit all glyphs.
     * @param metrics the out metrics.
     * @return metrics on success. null if text cannot be rendered by BoringLayout.
     */
    public static @Nullable Metrics isBoring(@NonNull CharSequence text, @NonNull TextPaint paint,
            @NonNull TextDirectionHeuristic textDir, boolean useFallbackLineSpacing,
            @Nullable Metrics metrics) {
        return isBoring(text, paint, textDir, useFallbackLineSpacing, null, metrics);
    }

    /**
     * @hide
     */
    public static @Nullable Metrics isBoring(@NonNull CharSequence text, @NonNull TextPaint paint,
            @NonNull TextDirectionHeuristic textDir, boolean useFallbackLineSpacing,
            @Nullable Paint.FontMetrics minimumFontMetrics, @Nullable Metrics metrics) {
        if (TRACE_LAYOUT) {
            Trace.beginSection("BoringLayout#isBoring");
            Trace.setCounter("BoringLayout#textLength", text.length());
        }
        try {
            final int textLength = text.length();
            if (hasAnyInterestingChars(text, textLength)) {
                return null;  // There are some interesting characters. Not boring.
            }
            if (textDir != null && textDir.isRtl(text, 0, textLength)) {
                return null;  // The heuristic considers the whole text RTL. Not boring.
            }
            if (text instanceof Spanned) {
                Spanned sp = (Spanned) text;
                Object[] styles = sp.getSpans(0, textLength, ParagraphStyle.class);
                if (styles.length > 0) {
                    return null;  // There are some ParagraphStyle spans. Not boring.
                }
            }

            Metrics fm = metrics;
            if (fm == null) {
                fm = new Metrics();
            } else {
                fm.reset();
            }

            if (ClientFlags.fixLineHeightForLocale()) {
                if (minimumFontMetrics != null) {
                    fm.set(minimumFontMetrics);
                    // Because the font metrics is provided by public APIs, adjust the top/bottom
                    // with ascent/descent: top must be smaller than ascent, bottom must be larger
                    // than descent.
                    fm.top = Math.min(fm.top, fm.ascent);
                    fm.bottom = Math.max(fm.bottom, fm.descent);
                }
            }

            TextLine line = TextLine.obtain();
            line.set(paint, text, 0, textLength, Layout.DIR_LEFT_TO_RIGHT,
                    Layout.DIRS_ALL_LEFT_TO_RIGHT, false, null,
                    0 /* ellipsisStart, 0 since text has not been ellipsized at this point */,
                    0 /* ellipsisEnd, 0 since text has not been ellipsized at this point */,
                    useFallbackLineSpacing);
            fm.width = (int) Math.ceil(line.metrics(fm, fm.mDrawingBounds, false, null));
            TextLine.recycle(line);

            return fm;
        } finally {
            if (TRACE_LAYOUT) {
                Trace.endSection();
            }
        }
    }

    @Override
    public int getHeight() {
        return mBottom;
    }

    @Override
    public int getLineCount() {
        return 1;
    }

    @Override
    public int getLineTop(int line) {
        if (line == 0)
            return 0;
        else
            return mBottom;
    }

    @Override
    public int getLineDescent(int line) {
        return mDesc;
    }

    @Override
    public int getLineStart(int line) {
        if (line == 0)
            return 0;
        else
            return getText().length();
    }

    @Override
    public int getParagraphDirection(int line) {
        return DIR_LEFT_TO_RIGHT;
    }

    @Override
    public boolean getLineContainsTab(int line) {
        return false;
    }

    @Override
    public float getLineMax(int line) {
        if (getUseBoundsForWidth()) {
            return super.getLineMax(line);
        } else {
            return mMax;
        }
    }

    @Override
    public float getLineWidth(int line) {
        if (getUseBoundsForWidth()) {
            return super.getLineWidth(line);
        } else {
            return (line == 0 ? mMax : 0);
        }
    }

    @Override
    public final Directions getLineDirections(int line) {
        return Layout.DIRS_ALL_LEFT_TO_RIGHT;
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
        return mEllipsizedCount;
    }

    @Override
    public int getEllipsisStart(int line) {
        return mEllipsizedStart;
    }

    @Override
    public int getEllipsizedWidth() {
        return mEllipsizedWidth;
    }

    @Override
    public boolean isFallbackLineSpacingEnabled() {
        return mUseFallbackLineSpacing;
    }

    @Override
    public @NonNull RectF computeDrawingBoundingBox() {
        return mDrawingBounds;
    }

    // Override draw so it will be faster.
    @Override
    public void draw(Canvas c, Path highlight, Paint highlightpaint,
                     int cursorOffset) {
        if (mDirect != null && highlight == null) {
            float leftShift = 0;
            if (getUseBoundsForWidth() && getShiftDrawingOffsetForStartOverhang()) {
                RectF drawingRect = computeDrawingBoundingBox();
                if (drawingRect.left < 0) {
                    leftShift = -drawingRect.left;
                    c.translate(leftShift, 0);
                }
            }

            c.drawText(mDirect, 0, mBottom - mDesc, mPaint);

            if (leftShift != 0) {
                // Manually translate back to the original position because of b/324498002, using
                // save/restore disappears the toggle switch drawables.
                c.translate(-leftShift, 0);
            }
        } else {
            super.draw(c, highlight, highlightpaint, cursorOffset);
        }
    }

    /**
     * Callback for the ellipsizer to report what region it ellipsized.
     */
    public void ellipsized(int start, int end) {
        mEllipsizedStart = start;
        mEllipsizedCount = end - start;
    }

    private String mDirect;
    private Paint mPaint;
    private boolean mUseFallbackLineSpacing;

    /* package */ int mBottom, mDesc;   // for Direct
    private int mTopPadding, mBottomPadding;
    private float mMax;
    private int mEllipsizedWidth, mEllipsizedStart, mEllipsizedCount;
    private final RectF mDrawingBounds = new RectF();

    public static class Metrics extends Paint.FontMetricsInt {
        public int width;
        private final RectF mDrawingBounds = new RectF();

        /**
         * Returns drawing bounding box.
         *
         * @return a drawing bounding box.
         */
        @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
        @NonNull public RectF getDrawingBoundingBox() {
            return mDrawingBounds;
        }

        @Override public String toString() {
            return super.toString() + " width=" + width + ", drawingBounds = " + mDrawingBounds;
        }

        private void reset() {
            top = 0;
            bottom = 0;
            ascent = 0;
            descent = 0;
            width = 0;
            leading = 0;
            mDrawingBounds.setEmpty();
        }
    }
}
