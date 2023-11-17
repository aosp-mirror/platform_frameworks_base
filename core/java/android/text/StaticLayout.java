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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.text.LineBreakConfig;
import android.graphics.text.LineBreaker;
import android.os.Build;
import android.text.style.LeadingMarginSpan;
import android.text.style.LeadingMarginSpan.LeadingMarginSpan2;
import android.text.style.LineHeightSpan;
import android.text.style.TabStopSpan;
import android.util.Log;
import android.util.Pools.SynchronizedPool;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.GrowingArrayUtils;

import java.util.Arrays;

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
    /*
     * The break iteration is done in native code. The protocol for using the native code is as
     * follows.
     *
     * First, call nInit to setup native line breaker object. Then, for each paragraph, do the
     * following:
     *
     *   - Create MeasuredParagraph by MeasuredParagraph.buildForStaticLayout which measures in
     *     native.
     *   - Run LineBreaker.computeLineBreaks() to obtain line breaks for the paragraph.
     *
     * After all paragraphs, call finish() to release expensive buffers.
     */

    static final String TAG = "StaticLayout";

    /**
     * Builder for static layouts. The builder is the preferred pattern for constructing
     * StaticLayout objects and should be preferred over the constructors, particularly to access
     * newer features. To build a static layout, first call {@link #obtain} with the required
     * arguments (text, paint, and width), then call setters for optional parameters, and finally
     * {@link #build} to build the StaticLayout object. Parameters not explicitly set will get
     * default values.
     */
    public final static class Builder {
        private Builder() {}

        /**
         * Obtain a builder for constructing StaticLayout objects.
         *
         * @param source The text to be laid out, optionally with spans
         * @param start The index of the start of the text
         * @param end The index + 1 of the end of the text
         * @param paint The base paint used for layout
         * @param width The width in pixels
         * @return a builder object used for constructing the StaticLayout
         */
        @NonNull
        public static Builder obtain(@NonNull CharSequence source, @IntRange(from = 0) int start,
                @IntRange(from = 0) int end, @NonNull TextPaint paint,
                @IntRange(from = 0) int width) {
            Builder b = sPool.acquire();
            if (b == null) {
                b = new Builder();
            }

            // set default initial values
            b.mText = source;
            b.mStart = start;
            b.mEnd = end;
            b.mPaint = paint;
            b.mWidth = width;
            b.mAlignment = Alignment.ALIGN_NORMAL;
            b.mTextDir = TextDirectionHeuristics.FIRSTSTRONG_LTR;
            b.mSpacingMult = DEFAULT_LINESPACING_MULTIPLIER;
            b.mSpacingAdd = DEFAULT_LINESPACING_ADDITION;
            b.mIncludePad = true;
            b.mFallbackLineSpacing = false;
            b.mEllipsizedWidth = width;
            b.mEllipsize = null;
            b.mMaxLines = Integer.MAX_VALUE;
            b.mBreakStrategy = Layout.BREAK_STRATEGY_SIMPLE;
            b.mHyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE;
            b.mJustificationMode = Layout.JUSTIFICATION_MODE_NONE;
            b.mLineBreakConfig = LineBreakConfig.NONE;
            b.mMinimumFontMetrics = null;
            return b;
        }

        /**
         * This method should be called after the layout is finished getting constructed and the
         * builder needs to be cleaned up and returned to the pool.
         */
        private static void recycle(@NonNull Builder b) {
            b.mPaint = null;
            b.mText = null;
            b.mLeftIndents = null;
            b.mRightIndents = null;
            b.mMinimumFontMetrics = null;
            sPool.release(b);
        }

        // release any expensive state
        /* package */ void finish() {
            mText = null;
            mPaint = null;
            mLeftIndents = null;
            mRightIndents = null;
            mMinimumFontMetrics = null;
        }

        public Builder setText(CharSequence source) {
            return setText(source, 0, source.length());
        }

        /**
         * Set the text. Only useful when re-using the builder, which is done for
         * the internal implementation of {@link DynamicLayout} but not as part
         * of normal {@link StaticLayout} usage.
         *
         * @param source The text to be laid out, optionally with spans
         * @param start The index of the start of the text
         * @param end The index + 1 of the end of the text
         * @return this builder, useful for chaining
         *
         * @hide
         */
        @NonNull
        public Builder setText(@NonNull CharSequence source, int start, int end) {
            mText = source;
            mStart = start;
            mEnd = end;
            return this;
        }

        /**
         * Set the paint. Internal for reuse cases only.
         *
         * @param paint The base paint used for layout
         * @return this builder, useful for chaining
         *
         * @hide
         */
        @NonNull
        public Builder setPaint(@NonNull TextPaint paint) {
            mPaint = paint;
            return this;
        }

        /**
         * Set the width. Internal for reuse cases only.
         *
         * @param width The width in pixels
         * @return this builder, useful for chaining
         *
         * @hide
         */
        @NonNull
        public Builder setWidth(@IntRange(from = 0) int width) {
            mWidth = width;
            if (mEllipsize == null) {
                mEllipsizedWidth = width;
            }
            return this;
        }

        /**
         * Set the alignment. The default is {@link Layout.Alignment#ALIGN_NORMAL}.
         *
         * @param alignment Alignment for the resulting {@link StaticLayout}
         * @return this builder, useful for chaining
         */
        @NonNull
        public Builder setAlignment(@NonNull Alignment alignment) {
            mAlignment = alignment;
            return this;
        }

        /**
         * Set the text direction heuristic. The text direction heuristic is used to
         * resolve text direction per-paragraph based on the input text. The default is
         * {@link TextDirectionHeuristics#FIRSTSTRONG_LTR}.
         *
         * @param textDir text direction heuristic for resolving bidi behavior.
         * @return this builder, useful for chaining
         */
        @NonNull
        public Builder setTextDirection(@NonNull TextDirectionHeuristic textDir) {
            mTextDir = textDir;
            return this;
        }

        /**
         * Set line spacing parameters. Each line will have its line spacing multiplied by
         * {@code spacingMult} and then increased by {@code spacingAdd}. The default is 0.0 for
         * {@code spacingAdd} and 1.0 for {@code spacingMult}.
         *
         * @param spacingAdd the amount of line spacing addition
         * @param spacingMult the line spacing multiplier
         * @return this builder, useful for chaining
         * @see android.widget.TextView#setLineSpacing
         */
        @NonNull
        public Builder setLineSpacing(float spacingAdd, @FloatRange(from = 0.0) float spacingMult) {
            mSpacingAdd = spacingAdd;
            mSpacingMult = spacingMult;
            return this;
        }

        /**
         * Set whether to include extra space beyond font ascent and descent (which is
         * needed to avoid clipping in some languages, such as Arabic and Kannada). The
         * default is {@code true}.
         *
         * @param includePad whether to include padding
         * @return this builder, useful for chaining
         * @see android.widget.TextView#setIncludeFontPadding
         */
        @NonNull
        public Builder setIncludePad(boolean includePad) {
            mIncludePad = includePad;
            return this;
        }

        /**
         * Set whether to respect the ascent and descent of the fallback fonts that are used in
         * displaying the text (which is needed to avoid text from consecutive lines running into
         * each other). If set, fallback fonts that end up getting used can increase the ascent
         * and descent of the lines that they are used on.
         *
         * <p>For backward compatibility reasons, the default is {@code false}, but setting this to
         * true is strongly recommended. It is required to be true if text could be in languages
         * like Burmese or Tibetan where text is typically much taller or deeper than Latin text.
         *
         * @param useLineSpacingFromFallbacks whether to expand linespacing based on fallback fonts
         * @return this builder, useful for chaining
         */
        @NonNull
        public Builder setUseLineSpacingFromFallbacks(boolean useLineSpacingFromFallbacks) {
            mFallbackLineSpacing = useLineSpacingFromFallbacks;
            return this;
        }

        /**
         * Set the width as used for ellipsizing purposes, if it differs from the
         * normal layout width. The default is the {@code width}
         * passed to {@link #obtain}.
         *
         * @param ellipsizedWidth width used for ellipsizing, in pixels
         * @return this builder, useful for chaining
         * @see android.widget.TextView#setEllipsize
         */
        @NonNull
        public Builder setEllipsizedWidth(@IntRange(from = 0) int ellipsizedWidth) {
            mEllipsizedWidth = ellipsizedWidth;
            return this;
        }

        /**
         * Set ellipsizing on the layout. Causes words that are longer than the view
         * is wide, or exceeding the number of lines (see #setMaxLines) in the case
         * of {@link android.text.TextUtils.TruncateAt#END} or
         * {@link android.text.TextUtils.TruncateAt#MARQUEE}, to be ellipsized instead
         * of broken. The default is {@code null}, indicating no ellipsis is to be applied.
         *
         * @param ellipsize type of ellipsis behavior
         * @return this builder, useful for chaining
         * @see android.widget.TextView#setEllipsize
         */
        @NonNull
        public Builder setEllipsize(@Nullable TextUtils.TruncateAt ellipsize) {
            mEllipsize = ellipsize;
            return this;
        }

        /**
         * Set maximum number of lines. This is particularly useful in the case of
         * ellipsizing, where it changes the layout of the last line. The default is
         * unlimited.
         *
         * @param maxLines maximum number of lines in the layout
         * @return this builder, useful for chaining
         * @see android.widget.TextView#setMaxLines
         */
        @NonNull
        public Builder setMaxLines(@IntRange(from = 0) int maxLines) {
            mMaxLines = maxLines;
            return this;
        }

        /**
         * Set break strategy, useful for selecting high quality or balanced paragraph
         * layout options. The default is {@link Layout#BREAK_STRATEGY_SIMPLE}.
         * <p/>
         * Enabling hyphenation with either using {@link Layout#HYPHENATION_FREQUENCY_NORMAL} or
         * {@link Layout#HYPHENATION_FREQUENCY_FULL} while line breaking is set to one of
         * {@link Layout#BREAK_STRATEGY_BALANCED}, {@link Layout#BREAK_STRATEGY_HIGH_QUALITY}
         * improves the structure of text layout however has performance impact and requires more
         * time to do the text layout.
         *
         * @param breakStrategy break strategy for paragraph layout
         * @return this builder, useful for chaining
         * @see android.widget.TextView#setBreakStrategy
         * @see #setHyphenationFrequency(int)
         */
        @NonNull
        public Builder setBreakStrategy(@BreakStrategy int breakStrategy) {
            mBreakStrategy = breakStrategy;
            return this;
        }

        /**
         * Set hyphenation frequency, to control the amount of automatic hyphenation used. The
         * possible values are defined in {@link Layout}, by constants named with the pattern
         * {@code HYPHENATION_FREQUENCY_*}. The default is
         * {@link Layout#HYPHENATION_FREQUENCY_NONE}.
         * <p/>
         * Enabling hyphenation with either using {@link Layout#HYPHENATION_FREQUENCY_NORMAL} or
         * {@link Layout#HYPHENATION_FREQUENCY_FULL} while line breaking is set to one of
         * {@link Layout#BREAK_STRATEGY_BALANCED}, {@link Layout#BREAK_STRATEGY_HIGH_QUALITY}
         * improves the structure of text layout however has performance impact and requires more
         * time to do the text layout.
         *
         * @param hyphenationFrequency hyphenation frequency for the paragraph
         * @return this builder, useful for chaining
         * @see android.widget.TextView#setHyphenationFrequency
         * @see #setBreakStrategy(int)
         */
        @NonNull
        public Builder setHyphenationFrequency(@HyphenationFrequency int hyphenationFrequency) {
            mHyphenationFrequency = hyphenationFrequency;
            return this;
        }

        /**
         * Set indents. Arguments are arrays holding an indent amount, one per line, measured in
         * pixels. For lines past the last element in the array, the last element repeats.
         *
         * @param leftIndents array of indent values for left margin, in pixels
         * @param rightIndents array of indent values for right margin, in pixels
         * @return this builder, useful for chaining
         */
        @NonNull
        public Builder setIndents(@Nullable int[] leftIndents, @Nullable int[] rightIndents) {
            mLeftIndents = leftIndents;
            mRightIndents = rightIndents;
            return this;
        }

        /**
         * Set paragraph justification mode. The default value is
         * {@link Layout#JUSTIFICATION_MODE_NONE}. If the last line is too short for justification,
         * the last line will be displayed with the alignment set by {@link #setAlignment}.
         * When Justification mode is JUSTIFICATION_MODE_INTER_WORD, wordSpacing on the given
         * {@link Paint} will be ignored. This behavior also affects Spans which change the
         * wordSpacing.
         *
         * @param justificationMode justification mode for the paragraph.
         * @return this builder, useful for chaining.
         * @see Paint#setWordSpacing(float)
         */
        @NonNull
        public Builder setJustificationMode(@JustificationMode int justificationMode) {
            mJustificationMode = justificationMode;
            return this;
        }

        /**
         * Sets whether the line spacing should be applied for the last line. Default value is
         * {@code false}.
         *
         * @hide
         */
        @NonNull
        /* package */ Builder setAddLastLineLineSpacing(boolean value) {
            mAddLastLineLineSpacing = value;
            return this;
        }

        /**
         * Set the line break configuration. The line break will be passed to native used for
         * calculating the text wrapping. The default value of the line break style is
         * {@link LineBreakConfig#LINE_BREAK_STYLE_NONE}
         *
         * @param lineBreakConfig the line break configuration for text wrapping.
         * @return this builder, useful for chaining.
         * @see android.widget.TextView#setLineBreakStyle
         * @see android.widget.TextView#setLineBreakWordStyle
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
         * @see Layout.Builder#setUseBoundsForWidth(boolean)
         */
        @NonNull
        @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
        public Builder setUseBoundsForWidth(boolean useBoundsForWidth) {
            mUseBoundsForWidth = useBoundsForWidth;
            return this;
        }

        /**
         * Internal API that tells underlying line breaker that calculating bounding boxes even if
         * the line break is performed with advances. This is useful for DynamicLayout internal
         * implementation because it uses bounding box as well as advances.
         * @hide
         */
        public Builder setCalculateBounds(boolean value) {
            mCalculateBounds = value;
            return this;
        }

        /**
         * Set the minimum font metrics used for line spacing.
         *
         * <p>
         * {@code null} is the default value. If {@code null} is set or left as default, the
         * font metrics obtained by {@link Paint#getFontMetricsForLocale(Paint.FontMetrics)} is
         * used.
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
         * @see Layout.Builder#setMinimumFontMetrics(Paint.FontMetrics)
         * @see DynamicLayout.Builder#setMinimumFontMetrics(Paint.FontMetrics)
         */
        @NonNull
        @FlaggedApi(FLAG_FIX_LINE_HEIGHT_FOR_LOCALE)
        public Builder setMinimumFontMetrics(@Nullable Paint.FontMetrics minimumFontMetrics) {
            mMinimumFontMetrics = minimumFontMetrics;
            return this;
        }

        /**
         * Build the {@link StaticLayout} after options have been set.
         *
         * <p>Note: the builder object must not be reused in any way after calling this
         * method. Setting parameters after calling this method, or calling it a second
         * time on the same builder object, will likely lead to unexpected results.
         *
         * @return the newly constructed {@link StaticLayout} object
         */
        @NonNull
        public StaticLayout build() {
            StaticLayout result = new StaticLayout(this, mIncludePad, mEllipsize != null
                    ? COLUMNS_ELLIPSIZE : COLUMNS_NORMAL);
            Builder.recycle(this);
            return result;
        }

        /**
         * DO NOT USE THIS METHOD OTHER THAN DynamicLayout.
         *
         * This class generates a very weird StaticLayout only for getting a result of line break.
         * Since DynamicLayout keeps StaticLayout reference in the static context for object
         * recycling but keeping text reference in static context will end up with leaking Context
         * due to TextWatcher via TextView.
         *
         * So, this is a dirty work around that creating StaticLayout without passing text reference
         * to the super constructor, but calculating the text layout by calling generate function
         * directly.
         */
        /* package */ @NonNull StaticLayout buildPartialStaticLayoutForDynamicLayout(
                boolean trackpadding, StaticLayout recycle) {
            if (recycle == null) {
                recycle = new StaticLayout();
            }
            recycle.generate(this, mIncludePad, trackpadding);
            return recycle;
        }

        private CharSequence mText;
        private int mStart;
        private int mEnd;
        private TextPaint mPaint;
        private int mWidth;
        private Alignment mAlignment;
        private TextDirectionHeuristic mTextDir;
        private float mSpacingMult;
        private float mSpacingAdd;
        private boolean mIncludePad;
        private boolean mFallbackLineSpacing;
        private int mEllipsizedWidth;
        private TextUtils.TruncateAt mEllipsize;
        private int mMaxLines;
        private int mBreakStrategy;
        private int mHyphenationFrequency;
        @Nullable private int[] mLeftIndents;
        @Nullable private int[] mRightIndents;
        private int mJustificationMode;
        private boolean mAddLastLineLineSpacing;
        private LineBreakConfig mLineBreakConfig = LineBreakConfig.NONE;
        private boolean mUseBoundsForWidth;
        private boolean mCalculateBounds;
        @Nullable private Paint.FontMetrics mMinimumFontMetrics;

        private final Paint.FontMetricsInt mFontMetricsInt = new Paint.FontMetricsInt();

        private static final SynchronizedPool<Builder> sPool = new SynchronizedPool<>(3);
    }

    /**
     * DO NOT USE THIS CONSTRUCTOR OTHER THAN FOR DYNAMIC LAYOUT.
     * See Builder#buildPartialStaticLayoutForDynamicLayout for the reason of this constructor.
     */
    private StaticLayout() {
        super(
                null,  // text
                null,  // paint
                0,  // width
                null, // alignment
                null, // textDir
                1, // spacing multiplier
                0, // spacing amount
                false, // include font padding
                false, // fallback line spacing
                0,  // ellipsized width
                null, // ellipsize
                1,  // maxLines
                BREAK_STRATEGY_SIMPLE,
                HYPHENATION_FREQUENCY_NONE,
                null,  // leftIndents
                null,  // rightIndents
                JUSTIFICATION_MODE_NONE,
                null,  // lineBreakConfig,
                false,  // useBoundsForWidth
                null  // minimumFontMetrics
        );

        mColumns = COLUMNS_ELLIPSIZE;
        mLineDirections = ArrayUtils.newUnpaddedArray(Directions.class, 2);
        mLines  = ArrayUtils.newUnpaddedIntArray(2 * mColumns);
    }

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    public StaticLayout(CharSequence source, TextPaint paint,
                        int width,
                        Alignment align, float spacingmult, float spacingadd,
                        boolean includepad) {
        this(source, 0, source.length(), paint, width, align,
             spacingmult, spacingadd, includepad);
    }

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    public StaticLayout(CharSequence source, int bufstart, int bufend,
                        TextPaint paint, int outerwidth,
                        Alignment align,
                        float spacingmult, float spacingadd,
                        boolean includepad) {
        this(source, bufstart, bufend, paint, outerwidth, align,
             spacingmult, spacingadd, includepad, null, 0);
    }

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
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
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 117521430)
    public StaticLayout(CharSequence source, int bufstart, int bufend,
                        TextPaint paint, int outerwidth,
                        Alignment align, TextDirectionHeuristic textDir,
                        float spacingmult, float spacingadd,
                        boolean includepad,
                        TextUtils.TruncateAt ellipsize, int ellipsizedWidth, int maxLines) {
        this(Builder.obtain(source, bufstart, bufend, paint, outerwidth)
                .setAlignment(align)
                .setTextDirection(textDir)
                .setLineSpacing(spacingadd, spacingmult)
                .setIncludePad(includepad)
                .setEllipsize(ellipsize)
                .setEllipsizedWidth(ellipsizedWidth)
                .setMaxLines(maxLines), includepad,
                ellipsize != null ? COLUMNS_ELLIPSIZE : COLUMNS_NORMAL);
    }

    private StaticLayout(Builder b, boolean trackPadding, int columnSize) {
        super((b.mEllipsize == null) ? b.mText : (b.mText instanceof Spanned)
                    ? new SpannedEllipsizer(b.mText) : new Ellipsizer(b.mText),
                b.mPaint, b.mWidth, b.mAlignment, b.mTextDir, b.mSpacingMult, b.mSpacingAdd,
                b.mIncludePad, b.mFallbackLineSpacing, b.mEllipsizedWidth, b.mEllipsize,
                b.mMaxLines, b.mBreakStrategy, b.mHyphenationFrequency, b.mLeftIndents,
                b.mRightIndents, b.mJustificationMode, b.mLineBreakConfig, b.mUseBoundsForWidth,
                b.mMinimumFontMetrics);

        mColumns = columnSize;
        if (b.mEllipsize != null) {
            Ellipsizer e = (Ellipsizer) getText();

            e.mLayout = this;
            e.mWidth = b.mEllipsizedWidth;
            e.mMethod = b.mEllipsize;
        }

        mLineDirections = ArrayUtils.newUnpaddedArray(Directions.class, 2);
        mLines  = ArrayUtils.newUnpaddedIntArray(2 * mColumns);
        mMaximumVisibleLineCount = b.mMaxLines;

        mLeftIndents = b.mLeftIndents;
        mRightIndents = b.mRightIndents;

        generate(b, b.mIncludePad, trackPadding);
    }

    private static int getBaseHyphenationFrequency(int frequency) {
        switch (frequency) {
            case Layout.HYPHENATION_FREQUENCY_FULL:
            case Layout.HYPHENATION_FREQUENCY_FULL_FAST:
                return LineBreaker.HYPHENATION_FREQUENCY_FULL;
            case Layout.HYPHENATION_FREQUENCY_NORMAL:
            case Layout.HYPHENATION_FREQUENCY_NORMAL_FAST:
                return LineBreaker.HYPHENATION_FREQUENCY_NORMAL;
            case Layout.HYPHENATION_FREQUENCY_NONE:
            default:
                return LineBreaker.HYPHENATION_FREQUENCY_NONE;
        }
    }

    /* package */ void generate(Builder b, boolean includepad, boolean trackpad) {
        final CharSequence source = b.mText;
        final int bufStart = b.mStart;
        final int bufEnd = b.mEnd;
        TextPaint paint = b.mPaint;
        int outerWidth = b.mWidth;
        TextDirectionHeuristic textDir = b.mTextDir;
        float spacingmult = b.mSpacingMult;
        float spacingadd = b.mSpacingAdd;
        float ellipsizedWidth = b.mEllipsizedWidth;
        TextUtils.TruncateAt ellipsize = b.mEllipsize;
        final boolean addLastLineSpacing = b.mAddLastLineLineSpacing;

        int lineBreakCapacity = 0;
        int[] breaks = null;
        float[] lineWidths = null;
        float[] ascents = null;
        float[] descents = null;
        boolean[] hasTabs = null;
        int[] hyphenEdits = null;

        mLineCount = 0;
        mEllipsized = false;
        mMaxLineHeight = mMaximumVisibleLineCount < 1 ? 0 : DEFAULT_MAX_LINE_HEIGHT;
        mDrawingBounds = null;
        boolean isFallbackLineSpacing = b.mFallbackLineSpacing;

        int v = 0;
        boolean needMultiply = (spacingmult != 1 || spacingadd != 0);

        Paint.FontMetricsInt fm = b.mFontMetricsInt;
        int[] chooseHtv = null;

        final int[] indents;
        if (mLeftIndents != null || mRightIndents != null) {
            final int leftLen = mLeftIndents == null ? 0 : mLeftIndents.length;
            final int rightLen = mRightIndents == null ? 0 : mRightIndents.length;
            final int indentsLen = Math.max(leftLen, rightLen);
            indents = new int[indentsLen];
            for (int i = 0; i < leftLen; i++) {
                indents[i] = mLeftIndents[i];
            }
            for (int i = 0; i < rightLen; i++) {
                indents[i] += mRightIndents[i];
            }
        } else {
            indents = null;
        }

        int defaultTop;
        int defaultAscent;
        int defaultDescent;
        int defaultBottom;
        if (ClientFlags.fixLineHeightForLocale()) {
            if (b.mMinimumFontMetrics != null) {
                defaultTop = (int) Math.floor(b.mMinimumFontMetrics.top);
                defaultAscent = Math.round(b.mMinimumFontMetrics.ascent);
                defaultDescent = Math.round(b.mMinimumFontMetrics.descent);
                defaultBottom = (int) Math.ceil(b.mMinimumFontMetrics.bottom);
            } else {
                paint.getFontMetricsIntForLocale(fm);
                defaultTop = fm.top;
                defaultAscent = fm.ascent;
                defaultDescent = fm.descent;
                defaultBottom = fm.bottom;
            }

            // Because the font metrics is provided by public APIs, adjust the top/bottom with
            // ascent/descent: top must be smaller than ascent, bottom must be larger than descent.
            defaultTop = Math.min(defaultTop, defaultAscent);
            defaultBottom = Math.max(defaultBottom, defaultDescent);
        } else {
            defaultTop = 0;
            defaultAscent = 0;
            defaultDescent = 0;
            defaultBottom = 0;
        }

        final LineBreaker lineBreaker = new LineBreaker.Builder()
                .setBreakStrategy(b.mBreakStrategy)
                .setHyphenationFrequency(getBaseHyphenationFrequency(b.mHyphenationFrequency))
                // TODO: Support more justification mode, e.g. letter spacing, stretching.
                .setJustificationMode(b.mJustificationMode)
                .setIndents(indents)
                .setUseBoundsForWidth(b.mUseBoundsForWidth)
                .build();

        LineBreaker.ParagraphConstraints constraints =
                new LineBreaker.ParagraphConstraints();

        PrecomputedText.ParagraphInfo[] paragraphInfo = null;
        final Spanned spanned = (source instanceof Spanned) ? (Spanned) source : null;
        if (source instanceof PrecomputedText) {
            PrecomputedText precomputed = (PrecomputedText) source;
            final @PrecomputedText.Params.CheckResultUsableResult int checkResult =
                    precomputed.checkResultUsable(bufStart, bufEnd, textDir, paint,
                            b.mBreakStrategy, b.mHyphenationFrequency, b.mLineBreakConfig);
            switch (checkResult) {
                case PrecomputedText.Params.UNUSABLE:
                    break;
                case PrecomputedText.Params.NEED_RECOMPUTE:
                    final PrecomputedText.Params newParams =
                            new PrecomputedText.Params.Builder(paint)
                                .setBreakStrategy(b.mBreakStrategy)
                                .setHyphenationFrequency(b.mHyphenationFrequency)
                                .setTextDirection(textDir)
                                .setLineBreakConfig(b.mLineBreakConfig)
                                .build();
                    precomputed = PrecomputedText.create(precomputed, newParams);
                    paragraphInfo = precomputed.getParagraphInfo();
                    break;
                case PrecomputedText.Params.USABLE:
                    // Some parameters are different from the ones when measured text is created.
                    paragraphInfo = precomputed.getParagraphInfo();
                    break;
            }
        }

        if (paragraphInfo == null) {
            final PrecomputedText.Params param = new PrecomputedText.Params(paint,
                    b.mLineBreakConfig, textDir, b.mBreakStrategy, b.mHyphenationFrequency);
            paragraphInfo = PrecomputedText.createMeasuredParagraphs(source, param, bufStart,
                    bufEnd, false /* computeLayout */, b.mCalculateBounds);
        }

        for (int paraIndex = 0; paraIndex < paragraphInfo.length; paraIndex++) {
            final int paraStart = paraIndex == 0
                    ? bufStart : paragraphInfo[paraIndex - 1].paragraphEnd;
            final int paraEnd = paragraphInfo[paraIndex].paragraphEnd;

            int firstWidthLineCount = 1;
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
                        firstWidthLineCount = Math.max(firstWidthLineCount,
                                lms2.getLeadingMarginLineCount());
                    }
                }

                chooseHt = getParagraphSpans(spanned, paraStart, paraEnd, LineHeightSpan.class);

                if (chooseHt.length == 0) {
                    chooseHt = null; // So that out() would not assume it has any contents
                } else {
                    if (chooseHtv == null || chooseHtv.length < chooseHt.length) {
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
            // tab stop locations
            float[] variableTabStops = null;
            if (spanned != null) {
                TabStopSpan[] spans = getParagraphSpans(spanned, paraStart,
                        paraEnd, TabStopSpan.class);
                if (spans.length > 0) {
                    float[] stops = new float[spans.length];
                    for (int i = 0; i < spans.length; i++) {
                        stops[i] = (float) spans[i].getTabStop();
                    }
                    Arrays.sort(stops, 0, stops.length);
                    variableTabStops = stops;
                }
            }

            final MeasuredParagraph measuredPara = paragraphInfo[paraIndex].measured;
            final char[] chs = measuredPara.getChars();
            final int[] spanEndCache = measuredPara.getSpanEndCache().getRawArray();
            final int[] fmCache = measuredPara.getFontMetrics().getRawArray();

            constraints.setWidth(restWidth);
            constraints.setIndent(firstWidth, firstWidthLineCount);
            constraints.setTabStops(variableTabStops, TAB_INCREMENT);

            LineBreaker.Result res = lineBreaker.computeLineBreaks(
                    measuredPara.getMeasuredText(), constraints, mLineCount);
            int breakCount = res.getLineCount();
            if (lineBreakCapacity < breakCount) {
                lineBreakCapacity = breakCount;
                breaks = new int[lineBreakCapacity];
                lineWidths = new float[lineBreakCapacity];
                ascents = new float[lineBreakCapacity];
                descents = new float[lineBreakCapacity];
                hasTabs = new boolean[lineBreakCapacity];
                hyphenEdits = new int[lineBreakCapacity];
            }

            for (int i = 0; i < breakCount; ++i) {
                breaks[i] = res.getLineBreakOffset(i);
                lineWidths[i] = res.getLineWidth(i);
                ascents[i] = res.getLineAscent(i);
                descents[i] = res.getLineDescent(i);
                hasTabs[i] = res.hasLineTab(i);
                hyphenEdits[i] =
                    packHyphenEdit(res.getStartLineHyphenEdit(i), res.getEndLineHyphenEdit(i));
            }

            final int remainingLineCount = mMaximumVisibleLineCount - mLineCount;
            final boolean ellipsisMayBeApplied = ellipsize != null
                    && (ellipsize == TextUtils.TruncateAt.END
                        || (mMaximumVisibleLineCount == 1
                                && ellipsize != TextUtils.TruncateAt.MARQUEE));
            if (0 < remainingLineCount && remainingLineCount < breakCount
                    && ellipsisMayBeApplied) {
                // Calculate width
                float width = 0;
                boolean hasTab = false;  // XXX May need to also have starting hyphen edit
                for (int i = remainingLineCount - 1; i < breakCount; i++) {
                    if (i == breakCount - 1) {
                        width += lineWidths[i];
                    } else {
                        for (int j = (i == 0 ? 0 : breaks[i - 1]); j < breaks[i]; j++) {
                            width += measuredPara.getCharWidthAt(j);
                        }
                    }
                    hasTab |= hasTabs[i];
                }
                // Treat the last line and overflowed lines as a single line.
                breaks[remainingLineCount - 1] = breaks[breakCount - 1];
                lineWidths[remainingLineCount - 1] = width;
                hasTabs[remainingLineCount - 1] = hasTab;

                breakCount = remainingLineCount;
            }

            // here is the offset of the starting character of the line we are currently
            // measuring
            int here = paraStart;

            int fmTop = defaultTop;
            int fmBottom = defaultBottom;
            int fmAscent = defaultAscent;
            int fmDescent = defaultDescent;
            int fmCacheIndex = 0;
            int spanEndCacheIndex = 0;
            int breakIndex = 0;
            for (int spanStart = paraStart, spanEnd; spanStart < paraEnd; spanStart = spanEnd) {
                // retrieve end of span
                spanEnd = spanEndCache[spanEndCacheIndex++];

                // retrieve cached metrics, order matches above
                fm.top = fmCache[fmCacheIndex * 4 + 0];
                fm.bottom = fmCache[fmCacheIndex * 4 + 1];
                fm.ascent = fmCache[fmCacheIndex * 4 + 2];
                fm.descent = fmCache[fmCacheIndex * 4 + 3];
                fmCacheIndex++;

                if (fm.top < fmTop) {
                    fmTop = fm.top;
                }
                if (fm.ascent < fmAscent) {
                    fmAscent = fm.ascent;
                }
                if (fm.descent > fmDescent) {
                    fmDescent = fm.descent;
                }
                if (fm.bottom > fmBottom) {
                    fmBottom = fm.bottom;
                }

                // skip breaks ending before current span range
                while (breakIndex < breakCount && paraStart + breaks[breakIndex] < spanStart) {
                    breakIndex++;
                }

                while (breakIndex < breakCount && paraStart + breaks[breakIndex] <= spanEnd) {
                    int endPos = paraStart + breaks[breakIndex];

                    boolean moreChars = (endPos < bufEnd);

                    final int ascent = isFallbackLineSpacing
                            ? Math.min(fmAscent, Math.round(ascents[breakIndex]))
                            : fmAscent;
                    final int descent = isFallbackLineSpacing
                            ? Math.max(fmDescent, Math.round(descents[breakIndex]))
                            : fmDescent;

                    // The fallback ascent/descent may be larger than top/bottom of the default font
                    // metrics. Adjust top/bottom with ascent/descent for avoiding unexpected
                    // clipping.
                    if (isFallbackLineSpacing) {
                        if (ascent < fmTop) {
                            fmTop = ascent;
                        }
                        if (descent > fmBottom) {
                            fmBottom = descent;
                        }
                    }

                    v = out(source, here, endPos,
                            ascent, descent, fmTop, fmBottom,
                            v, spacingmult, spacingadd, chooseHt, chooseHtv, fm,
                            hasTabs[breakIndex], hyphenEdits[breakIndex], needMultiply,
                            measuredPara, bufEnd, includepad, trackpad, addLastLineSpacing, chs,
                            paraStart, ellipsize, ellipsizedWidth, lineWidths[breakIndex],
                            paint, moreChars);

                    if (endPos < spanEnd) {
                        // preserve metrics for current span
                        fmTop = fm.top;
                        fmBottom = fm.bottom;
                        fmAscent = fm.ascent;
                        fmDescent = fm.descent;
                    } else {
                        fmTop = fmBottom = fmAscent = fmDescent = 0;
                    }

                    here = endPos;
                    breakIndex++;

                    if (mLineCount >= mMaximumVisibleLineCount && mEllipsized) {
                        return;
                    }
                }
            }

            if (paraEnd == bufEnd) {
                break;
            }
        }

        if ((bufEnd == bufStart || source.charAt(bufEnd - 1) == CHAR_NEW_LINE)
                && mLineCount < mMaximumVisibleLineCount) {
            final MeasuredParagraph measuredPara =
                    MeasuredParagraph.buildForBidi(source, bufEnd, bufEnd, textDir, null);
            if (ClientFlags.fixLineHeightForLocale()) {
                fm.top = defaultTop;
                fm.ascent = defaultAscent;
                fm.descent = defaultDescent;
                fm.bottom = defaultBottom;
            } else {
                paint.getFontMetricsInt(fm);
            }

            v = out(source,
                    bufEnd, bufEnd, fm.ascent, fm.descent,
                    fm.top, fm.bottom,
                    v,
                    spacingmult, spacingadd, null,
                    null, fm, false, 0,
                    needMultiply, measuredPara, bufEnd,
                    includepad, trackpad, addLastLineSpacing, null,
                    bufStart, ellipsize,
                    ellipsizedWidth, 0, paint, false);
        }
    }

    private int out(final CharSequence text, final int start, final int end, int above, int below,
            int top, int bottom, int v, final float spacingmult, final float spacingadd,
            final LineHeightSpan[] chooseHt, final int[] chooseHtv, final Paint.FontMetricsInt fm,
            final boolean hasTab, final int hyphenEdit, final boolean needMultiply,
            @NonNull final MeasuredParagraph measured,
            final int bufEnd, final boolean includePad, final boolean trackPad,
            final boolean addLastLineLineSpacing, final char[] chs,
            final int widthStart, final TextUtils.TruncateAt ellipsize, final float ellipsisWidth,
            final float textWidth, final TextPaint paint, final boolean moreChars) {
        final int j = mLineCount;
        final int off = j * mColumns;
        final int want = off + mColumns + TOP;
        int[] lines = mLines;
        final int dir = measured.getParagraphDir();

        if (want >= lines.length) {
            final int[] grow = ArrayUtils.newUnpaddedIntArray(GrowingArrayUtils.growSize(want));
            System.arraycopy(lines, 0, grow, 0, lines.length);
            mLines = grow;
            lines = grow;
        }

        if (j >= mLineDirections.length) {
            final Directions[] grow = ArrayUtils.newUnpaddedArray(Directions.class,
                    GrowingArrayUtils.growSize(j));
            System.arraycopy(mLineDirections, 0, grow, 0, mLineDirections.length);
            mLineDirections = grow;
        }

        if (chooseHt != null) {
            fm.ascent = above;
            fm.descent = below;
            fm.top = top;
            fm.bottom = bottom;

            for (int i = 0; i < chooseHt.length; i++) {
                if (chooseHt[i] instanceof LineHeightSpan.WithDensity) {
                    ((LineHeightSpan.WithDensity) chooseHt[i])
                            .chooseHeight(text, start, end, chooseHtv[i], v, fm, paint);
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
                calculateEllipsis(start, end, measured, widthStart,
                        ellipsisWidth, ellipsize, j,
                        textWidth, paint, forceEllipsis);
            } else {
                mLines[mColumns * j + ELLIPSIS_START] = 0;
                mLines[mColumns * j + ELLIPSIS_COUNT] = 0;
            }
        }

        final boolean lastLine;
        if (mEllipsized) {
            lastLine = true;
        } else {
            final boolean lastCharIsNewLine = widthStart != bufEnd && bufEnd > 0
                    && text.charAt(bufEnd - 1) == CHAR_NEW_LINE;
            if (end == bufEnd && !lastCharIsNewLine) {
                lastLine = true;
            } else if (start == bufEnd && lastCharIsNewLine) {
                lastLine = true;
            } else {
                lastLine = false;
            }
        }

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

        if (needMultiply && (addLastLineLineSpacing || !lastLine)) {
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
        lines[off + EXTRA] = extra;

        // special case for non-ellipsized last visible line when maxLines is set
        // store the height as if it was ellipsized
        if (!mEllipsized && currentLineIsTheLastVisibleOne) {
            // below calculation as if it was the last line
            int maxLineBelow = includePad ? bottom : below;
            // similar to the calculation of v below, without the extra.
            mMaxLineHeight = v + (maxLineBelow - above);
        }

        v += (below - above) + extra;
        lines[off + mColumns + START] = end;
        lines[off + mColumns + TOP] = v;

        // TODO: could move TAB to share same column as HYPHEN, simplifying this code and gaining
        // one bit for start field
        lines[off + TAB] |= hasTab ? TAB_MASK : 0;
        if (mEllipsized) {
            if (ellipsize == TextUtils.TruncateAt.START) {
                lines[off + HYPHEN] = packHyphenEdit(Paint.START_HYPHEN_EDIT_NO_EDIT,
                        unpackEndHyphenEdit(hyphenEdit));
            } else if (ellipsize == TextUtils.TruncateAt.END) {
                lines[off + HYPHEN] = packHyphenEdit(unpackStartHyphenEdit(hyphenEdit),
                        Paint.END_HYPHEN_EDIT_NO_EDIT);
            } else {  // Middle and marquee ellipsize should show text at the start/end edge.
                lines[off + HYPHEN] = packHyphenEdit(
                        Paint.START_HYPHEN_EDIT_NO_EDIT, Paint.END_HYPHEN_EDIT_NO_EDIT);
            }
        } else {
            lines[off + HYPHEN] = hyphenEdit;
        }

        lines[off + DIR] |= dir << DIR_SHIFT;
        mLineDirections[j] = measured.getDirections(start - widthStart, end - widthStart);

        mLineCount++;
        return v;
    }

    private void calculateEllipsis(int lineStart, int lineEnd,
                                   MeasuredParagraph measured, int widthStart,
                                   float avail, TextUtils.TruncateAt where,
                                   int line, float textWidth, TextPaint paint,
                                   boolean forceEllipsis) {
        avail -= getTotalInsets(line);
        if (textWidth <= avail && !forceEllipsis) {
            // Everything fits!
            mLines[mColumns * line + ELLIPSIS_START] = 0;
            mLines[mColumns * line + ELLIPSIS_COUNT] = 0;
            return;
        }

        float ellipsisWidth = paint.measureText(TextUtils.getEllipsisString(where));
        int ellipsisStart = 0;
        int ellipsisCount = 0;
        int len = lineEnd - lineStart;

        // We only support start ellipsis on a single line
        if (where == TextUtils.TruncateAt.START) {
            if (mMaximumVisibleLineCount == 1) {
                float sum = 0;
                int i;

                for (i = len; i > 0; i--) {
                    float w = measured.getCharWidthAt(i - 1 + lineStart - widthStart);
                    if (w + sum + ellipsisWidth > avail) {
                        while (i < len
                                && measured.getCharWidthAt(i + lineStart - widthStart) == 0.0f) {
                            i++;
                        }
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
                float w = measured.getCharWidthAt(i + lineStart - widthStart);

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
                for (right = len; right > 0; right--) {
                    float w = measured.getCharWidthAt(right - 1 + lineStart - widthStart);

                    if (w + rsum > ravail) {
                        while (right < len
                                && measured.getCharWidthAt(right + lineStart - widthStart)
                                    == 0.0f) {
                            right++;
                        }
                        break;
                    }
                    rsum += w;
                }

                float lavail = avail - ellipsisWidth - rsum;
                for (left = 0; left < right; left++) {
                    float w = measured.getCharWidthAt(left + lineStart - widthStart);

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
        mEllipsized = true;
        mLines[mColumns * line + ELLIPSIS_START] = ellipsisStart;
        mLines[mColumns * line + ELLIPSIS_COUNT] = ellipsisCount;
    }

    private float getTotalInsets(int line) {
        int totalIndent = 0;
        if (mLeftIndents != null) {
            totalIndent = mLeftIndents[Math.min(line, mLeftIndents.length - 1)];
        }
        if (mRightIndents != null) {
            totalIndent += mRightIndents[Math.min(line, mRightIndents.length - 1)];
        }
        return totalIndent;
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
        return mLines[mColumns * line + TOP];
    }

    /**
     * @hide
     */
    @Override
    public int getLineExtra(int line) {
        return mLines[mColumns * line + EXTRA];
    }

    @Override
    public int getLineDescent(int line) {
        return mLines[mColumns * line + DESCENT];
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
        if (line > getLineCount()) {
            throw new ArrayIndexOutOfBoundsException();
        }
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

    // To store into single int field, pack the pair of start and end hyphen edit.
    static int packHyphenEdit(
            @Paint.StartHyphenEdit int start, @Paint.EndHyphenEdit int end) {
        return start << START_HYPHEN_BITS_SHIFT | end;
    }

    static int unpackStartHyphenEdit(int packedHyphenEdit) {
        return (packedHyphenEdit & START_HYPHEN_MASK) >> START_HYPHEN_BITS_SHIFT;
    }

    static int unpackEndHyphenEdit(int packedHyphenEdit) {
        return packedHyphenEdit & END_HYPHEN_MASK;
    }

    /**
     * Returns the start hyphen edit value for this line.
     *
     * @param lineNumber a line number
     * @return A start hyphen edit value.
     * @hide
     */
    @Override
    public @Paint.StartHyphenEdit int getStartHyphenEdit(int lineNumber) {
        return unpackStartHyphenEdit(mLines[mColumns * lineNumber + HYPHEN] & HYPHEN_MASK);
    }

    /**
     * Returns the packed hyphen edit value for this line.
     *
     * @param lineNumber a line number
     * @return An end hyphen edit value.
     * @hide
     */
    @Override
    public @Paint.EndHyphenEdit int getEndHyphenEdit(int lineNumber) {
        return unpackEndHyphenEdit(mLines[mColumns * lineNumber + HYPHEN] & HYPHEN_MASK);
    }

    /**
     * @hide
     */
    @Override
    public int getIndentAdjust(int line, Alignment align) {
        if (align == Alignment.ALIGN_LEFT) {
            if (mLeftIndents == null) {
                return 0;
            } else {
                return mLeftIndents[Math.min(line, mLeftIndents.length - 1)];
            }
        } else if (align == Alignment.ALIGN_RIGHT) {
            if (mRightIndents == null) {
                return 0;
            } else {
                return -mRightIndents[Math.min(line, mRightIndents.length - 1)];
            }
        } else if (align == Alignment.ALIGN_CENTER) {
            int left = 0;
            if (mLeftIndents != null) {
                left = mLeftIndents[Math.min(line, mLeftIndents.length - 1)];
            }
            int right = 0;
            if (mRightIndents != null) {
                right = mRightIndents[Math.min(line, mRightIndents.length - 1)];
            }
            return (left - right) >> 1;
        } else {
            throw new AssertionError("unhandled alignment " + align);
        }
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
    @NonNull
    public RectF computeDrawingBoundingBox() {
        // Cache the drawing bounds result because it does not change after created.
        if (mDrawingBounds == null) {
            mDrawingBounds = super.computeDrawingBoundingBox();
        }
        return mDrawingBounds;
    }

    /**
     * Return the total height of this layout.
     *
     * @param cap if true and max lines is set, returns the height of the layout at the max lines.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public int getHeight(boolean cap) {
        if (cap && mLineCount > mMaximumVisibleLineCount && mMaxLineHeight == -1
                && Log.isLoggable(TAG, Log.WARN)) {
            Log.w(TAG, "maxLineHeight should not be -1. "
                    + " maxLines:" + mMaximumVisibleLineCount
                    + " lineCount:" + mLineCount);
        }

        return cap && mLineCount > mMaximumVisibleLineCount && mMaxLineHeight != -1
                ? mMaxLineHeight : super.getHeight();
    }

    @UnsupportedAppUsage
    private int mLineCount;
    private int mTopPadding, mBottomPadding;
    @UnsupportedAppUsage
    private int mColumns;
    private RectF mDrawingBounds = null;  // lazy calculation.

    /**
     * Keeps track if ellipsize is applied to the text.
     */
    private boolean mEllipsized;

    /**
     * If maxLines is set, ellipsize is not set, and the actual line count of text is greater than
     * or equal to maxLine, this variable holds the ideal visual height of the maxLine'th line
     * starting from the top of the layout. If maxLines is not set its value will be -1.
     *
     * The value is the same as getLineTop(maxLines) for ellipsized version where structurally no
     * more than maxLines is contained.
     */
    private int mMaxLineHeight = DEFAULT_MAX_LINE_HEIGHT;

    private static final int COLUMNS_NORMAL = 5;
    private static final int COLUMNS_ELLIPSIZE = 7;
    private static final int START = 0;
    private static final int DIR = START;
    private static final int TAB = START;
    private static final int TOP = 1;
    private static final int DESCENT = 2;
    private static final int EXTRA = 3;
    private static final int HYPHEN = 4;
    @UnsupportedAppUsage
    private static final int ELLIPSIS_START = 5;
    private static final int ELLIPSIS_COUNT = 6;

    @UnsupportedAppUsage
    private int[] mLines;
    @UnsupportedAppUsage
    private Directions[] mLineDirections;
    @UnsupportedAppUsage
    private int mMaximumVisibleLineCount = Integer.MAX_VALUE;

    private static final int START_MASK = 0x1FFFFFFF;
    private static final int DIR_SHIFT  = 30;
    private static final int TAB_MASK   = 0x20000000;
    private static final int HYPHEN_MASK = 0xFF;
    private static final int START_HYPHEN_BITS_SHIFT = 3;
    private static final int START_HYPHEN_MASK = 0x18; // 0b11000
    private static final int END_HYPHEN_MASK = 0x7;  // 0b00111

    private static final float TAB_INCREMENT = 20; // same as Layout, but that's private

    private static final char CHAR_NEW_LINE = '\n';

    private static final double EXTRA_ROUNDING = 0.5;

    private static final int DEFAULT_MAX_LINE_HEIGHT = -1;

    // Unused, here because of gray list private API accesses.
    /*package*/ static class LineBreaks {
        private static final int INITIAL_SIZE = 16;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public int[] breaks = new int[INITIAL_SIZE];
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public float[] widths = new float[INITIAL_SIZE];
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public float[] ascents = new float[INITIAL_SIZE];
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public float[] descents = new float[INITIAL_SIZE];
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public int[] flags = new int[INITIAL_SIZE]; // hasTab
        // breaks, widths, and flags should all have the same length
    }

    @Nullable private int[] mLeftIndents;
    @Nullable private int[] mRightIndents;
}
