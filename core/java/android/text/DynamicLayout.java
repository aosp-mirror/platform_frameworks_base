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
import static com.android.text.flags.Flags.FLAG_NO_BREAK_NO_HYPHENATION_SPAN;
import static com.android.text.flags.Flags.FLAG_USE_BOUNDS_FOR_WIDTH;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.text.LineBreakConfig;
import android.os.Build;
import android.os.Trace;
import android.text.method.OffsetMapping;
import android.text.style.ReplacementSpan;
import android.text.style.UpdateLayout;
import android.text.style.WrapTogetherSpan;
import android.util.ArraySet;
import android.util.Pools.SynchronizedPool;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.GrowingArrayUtils;
import com.android.text.flags.Flags;

import java.lang.ref.WeakReference;

/**
 * DynamicLayout is a text layout that updates itself as the text is edited.
 * <p>This is used by widgets to control text layout. You should not need
 * to use this class directly unless you are implementing your own widget
 * or custom display object, or need to call
 * {@link android.graphics.Canvas#drawText(java.lang.CharSequence, int, int, float, float, android.graphics.Paint)
 *  Canvas.drawText()} directly.</p>
 */
public class DynamicLayout extends Layout {
    private static final int PRIORITY = 128;
    private static final int BLOCK_MINIMUM_CHARACTER_LENGTH = 400;

    /**
     * Builder for dynamic layouts. The builder is the preferred pattern for constructing
     * DynamicLayout objects and should be preferred over the constructors, particularly to access
     * newer features. To build a dynamic layout, first call {@link #obtain} with the required
     * arguments (base, paint, and width), then call setters for optional parameters, and finally
     * {@link #build} to build the DynamicLayout object. Parameters not explicitly set will get
     * default values.
     */
    public static final class Builder {
        private Builder() {
        }

        /**
         * Obtain a builder for constructing DynamicLayout objects.
         */
        @NonNull
        public static Builder obtain(@NonNull CharSequence base, @NonNull TextPaint paint,
                @IntRange(from = 0) int width) {
            Builder b = sPool.acquire();
            if (b == null) {
                b = new Builder();
            }

            // set default initial values
            b.mBase = base;
            b.mDisplay = base;
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
            b.mBreakStrategy = Layout.BREAK_STRATEGY_SIMPLE;
            b.mHyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE;
            b.mJustificationMode = Layout.JUSTIFICATION_MODE_NONE;
            b.mLineBreakConfig = LineBreakConfig.NONE;
            return b;
        }

        /**
         * This method should be called after the layout is finished getting constructed and the
         * builder needs to be cleaned up and returned to the pool.
         */
        private static void recycle(@NonNull Builder b) {
            b.mBase = null;
            b.mDisplay = null;
            b.mPaint = null;
            sPool.release(b);
        }

        /**
         * Set the transformed text (password transformation being the primary example of a
         * transformation) that will be updated as the base text is changed. The default is the
         * 'base' text passed to the builder's constructor.
         *
         * @param display the transformed text
         * @return this builder, useful for chaining
         */
        @NonNull
        public Builder setDisplayText(@NonNull CharSequence display) {
            mDisplay = display;
            return this;
        }

        /**
         * Set the alignment. The default is {@link Layout.Alignment#ALIGN_NORMAL}.
         *
         * @param alignment Alignment for the resulting {@link DynamicLayout}
         * @return this builder, useful for chaining
         */
        @NonNull
        public Builder setAlignment(@NonNull Alignment alignment) {
            mAlignment = alignment;
            return this;
        }

        /**
         * Set the text direction heuristic. The text direction heuristic is used to resolve text
         * direction per-paragraph based on the input text. The default is
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
         * Set whether to include extra space beyond font ascent and descent (which is needed to
         * avoid clipping in some languages, such as Arabic and Kannada). The default is
         * {@code true}.
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
         * Set the width as used for ellipsizing purposes, if it differs from the normal layout
         * width. The default is the {@code width} passed to {@link #obtain}.
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
         * Set ellipsizing on the layout. Causes words that are longer than the view is wide, or
         * exceeding the number of lines (see #setMaxLines) in the case of
         * {@link android.text.TextUtils.TruncateAt#END} or
         * {@link android.text.TextUtils.TruncateAt#MARQUEE}, to be ellipsized instead of broken.
         * The default is {@code null}, indicating no ellipsis is to be applied.
         *
         * @param ellipsize type of ellipsis behavior
         * @return this builder, useful for chaining
         * @see android.widget.TextView#setEllipsize
         */
        public Builder setEllipsize(@Nullable TextUtils.TruncateAt ellipsize) {
            mEllipsize = ellipsize;
            return this;
        }

        /**
         * Set break strategy, useful for selecting high quality or balanced paragraph layout
         * options. The default is {@link Layout#BREAK_STRATEGY_SIMPLE}.
         *
         * @param breakStrategy break strategy for paragraph layout
         * @return this builder, useful for chaining
         * @see android.widget.TextView#setBreakStrategy
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
         *
         * @param hyphenationFrequency hyphenation frequency for the paragraph
         * @return this builder, useful for chaining
         * @see android.widget.TextView#setHyphenationFrequency
         */
        @NonNull
        public Builder setHyphenationFrequency(@HyphenationFrequency int hyphenationFrequency) {
            mHyphenationFrequency = hyphenationFrequency;
            return this;
        }

        /**
         * Set paragraph justification mode. The default value is
         * {@link Layout#JUSTIFICATION_MODE_NONE}. If the last line is too short for justification,
         * the last line will be displayed with the alignment set by {@link #setAlignment}.
         *
         * @param justificationMode justification mode for the paragraph.
         * @return this builder, useful for chaining.
         */
        @NonNull
        public Builder setJustificationMode(@JustificationMode int justificationMode) {
            mJustificationMode = justificationMode;
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
        @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
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
         * Set true for shifting the drawing x offset for showing overhang at the start position.
         *
         * This flag is ignored if the {@link #getUseBoundsForWidth()} is false.
         *
         * If this value is false, the Layout draws text from the zero even if there is a glyph
         * stroke in a region where the x coordinate is negative.
         *
         * If this value is true, the Layout draws text with shifting the x coordinate of the
         * drawing bounding box.
         *
         * This value is false by default.
         *
         * @param shiftDrawingOffsetForStartOverhang true for shifting the drawing offset for
         *                                          showing the stroke that is in the region where
         *                                          the x coordinate is negative.
         * @see #setUseBoundsForWidth(boolean)
         * @see #getUseBoundsForWidth()
         */
        @NonNull
        // The corresponding getter is getShiftDrawingOffsetForStartOverhang()
        @SuppressLint("MissingGetterMatchingBuilder")
        @FlaggedApi(FLAG_USE_BOUNDS_FOR_WIDTH)
        public Builder setShiftDrawingOffsetForStartOverhang(
                boolean shiftDrawingOffsetForStartOverhang) {
            mShiftDrawingOffsetForStartOverhang = shiftDrawingOffsetForStartOverhang;
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
         * @see StaticLayout.Builder#setMinimumFontMetrics(Paint.FontMetrics)
         */
        @NonNull
        @FlaggedApi(FLAG_FIX_LINE_HEIGHT_FOR_LOCALE)
        public Builder setMinimumFontMetrics(@Nullable Paint.FontMetrics minimumFontMetrics) {
            mMinimumFontMetrics = minimumFontMetrics;
            return this;
        }

        /**
         * Build the {@link DynamicLayout} after options have been set.
         *
         * <p>Note: the builder object must not be reused in any way after calling this method.
         * Setting parameters after calling this method, or calling it a second time on the same
         * builder object, will likely lead to unexpected results.
         *
         * @return the newly constructed {@link DynamicLayout} object
         */
        @NonNull
        public DynamicLayout build() {
            final DynamicLayout result = new DynamicLayout(this);
            Builder.recycle(this);
            return result;
        }

        private CharSequence mBase;
        private CharSequence mDisplay;
        private TextPaint mPaint;
        private int mWidth;
        private Alignment mAlignment;
        private TextDirectionHeuristic mTextDir;
        private float mSpacingMult;
        private float mSpacingAdd;
        private boolean mIncludePad;
        private boolean mFallbackLineSpacing;
        private int mBreakStrategy;
        private int mHyphenationFrequency;
        private int mJustificationMode;
        private TextUtils.TruncateAt mEllipsize;
        private int mEllipsizedWidth;
        private LineBreakConfig mLineBreakConfig = LineBreakConfig.NONE;
        private boolean mUseBoundsForWidth;
        private boolean mShiftDrawingOffsetForStartOverhang;
        private @Nullable Paint.FontMetrics mMinimumFontMetrics;

        private final Paint.FontMetricsInt mFontMetricsInt = new Paint.FontMetricsInt();

        private static final SynchronizedPool<Builder> sPool = new SynchronizedPool<>(3);
    }

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    public DynamicLayout(@NonNull CharSequence base,
                         @NonNull TextPaint paint,
                         @IntRange(from = 0) int width, @NonNull Alignment align,
                         @FloatRange(from = 0.0) float spacingmult, float spacingadd,
                         boolean includepad) {
        this(base, base, paint, width, align, spacingmult, spacingadd,
             includepad);
    }

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    public DynamicLayout(@NonNull CharSequence base, @NonNull CharSequence display,
                         @NonNull TextPaint paint,
                         @IntRange(from = 0) int width, @NonNull Alignment align,
                         @FloatRange(from = 0.0) float spacingmult, float spacingadd,
                         boolean includepad) {
        this(base, display, paint, width, align, spacingmult, spacingadd,
             includepad, null, 0);
    }

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    public DynamicLayout(@NonNull CharSequence base, @NonNull CharSequence display,
                         @NonNull TextPaint paint,
                         @IntRange(from = 0) int width, @NonNull Alignment align,
                         @FloatRange(from = 0.0) float spacingmult, float spacingadd,
                         boolean includepad,
                         @Nullable TextUtils.TruncateAt ellipsize,
                         @IntRange(from = 0) int ellipsizedWidth) {
        this(base, display, paint, width, align, TextDirectionHeuristics.FIRSTSTRONG_LTR,
                spacingmult, spacingadd, includepad,
                Layout.BREAK_STRATEGY_SIMPLE, Layout.HYPHENATION_FREQUENCY_NONE,
                Layout.JUSTIFICATION_MODE_NONE, LineBreakConfig.NONE, ellipsize, ellipsizedWidth);
    }

    /**
     * Make a layout for the transformed text (password transformation being the primary example of
     * a transformation) that will be updated as the base text is changed. If ellipsize is non-null,
     * the Layout will ellipsize the text down to ellipsizedWidth.
     *
     * @hide
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public DynamicLayout(@NonNull CharSequence base, @NonNull CharSequence display,
                         @NonNull TextPaint paint,
                         @IntRange(from = 0) int width,
                         @NonNull Alignment align, @NonNull TextDirectionHeuristic textDir,
                         @FloatRange(from = 0.0) float spacingmult, float spacingadd,
                         boolean includepad, @BreakStrategy int breakStrategy,
                         @HyphenationFrequency int hyphenationFrequency,
                         @JustificationMode int justificationMode,
                         @NonNull LineBreakConfig lineBreakConfig,
                         @Nullable TextUtils.TruncateAt ellipsize,
                         @IntRange(from = 0) int ellipsizedWidth) {
        super(createEllipsizer(ellipsize, display),
              paint, width, align, textDir, spacingmult, spacingadd, includepad,
                false /* fallbackLineSpacing */, ellipsizedWidth, ellipsize,
                Integer.MAX_VALUE /* maxLines */, breakStrategy, hyphenationFrequency,
                null /* leftIndents */, null /* rightIndents */, justificationMode,
                lineBreakConfig, false /* useBoundsForWidth */, false,
                null /* minimumFontMetrics */);

        final Builder b = Builder.obtain(base, paint, width)
                .setAlignment(align)
                .setTextDirection(textDir)
                .setLineSpacing(spacingadd, spacingmult)
                .setEllipsizedWidth(ellipsizedWidth)
                .setEllipsize(ellipsize);
        mDisplay = display;
        mIncludePad = includepad;
        mBreakStrategy = breakStrategy;
        mJustificationMode = justificationMode;
        mHyphenationFrequency = hyphenationFrequency;
        mLineBreakConfig = lineBreakConfig;

        generate(b);

        Builder.recycle(b);
    }

    private DynamicLayout(@NonNull Builder b) {
        super(createEllipsizer(b.mEllipsize, b.mDisplay),
                b.mPaint, b.mWidth, b.mAlignment, b.mTextDir, b.mSpacingMult, b.mSpacingAdd,
                b.mIncludePad, b.mFallbackLineSpacing, b.mEllipsizedWidth, b.mEllipsize,
                Integer.MAX_VALUE /* maxLines */, b.mBreakStrategy, b.mHyphenationFrequency,
                null /* leftIndents */, null /* rightIndents */, b.mJustificationMode,
                b.mLineBreakConfig, b.mUseBoundsForWidth, b.mShiftDrawingOffsetForStartOverhang,
                b.mMinimumFontMetrics);

        mDisplay = b.mDisplay;
        mIncludePad = b.mIncludePad;
        mBreakStrategy = b.mBreakStrategy;
        mJustificationMode = b.mJustificationMode;
        mHyphenationFrequency = b.mHyphenationFrequency;
        mLineBreakConfig = b.mLineBreakConfig;

        generate(b);
    }

    @NonNull
    private static CharSequence createEllipsizer(@Nullable TextUtils.TruncateAt ellipsize,
            @NonNull CharSequence display) {
        if (ellipsize == null) {
            return display;
        } else if (display instanceof Spanned) {
            return new SpannedEllipsizer(display);
        } else {
            return new Ellipsizer(display);
        }
    }

    private void generate(@NonNull Builder b) {
        mBase = b.mBase;
        mFallbackLineSpacing = b.mFallbackLineSpacing;
        mUseBoundsForWidth = b.mUseBoundsForWidth;
        mShiftDrawingOffsetForStartOverhang = b.mShiftDrawingOffsetForStartOverhang;
        mMinimumFontMetrics = b.mMinimumFontMetrics;
        if (b.mEllipsize != null) {
            mInts = new PackedIntVector(COLUMNS_ELLIPSIZE);
            mEllipsizedWidth = b.mEllipsizedWidth;
            mEllipsizeAt = b.mEllipsize;

            /*
             * This is annoying, but we can't refer to the layout until superclass construction is
             * finished, and the superclass constructor wants the reference to the display text.
             *
             * In other words, the two Ellipsizer classes in Layout.java need a
             * (Dynamic|Static)Layout as a parameter to do their calculations, but the Ellipsizers
             * also need to be the input to the superclass's constructor (Layout). In order to go
             * around the circular dependency, we construct the Ellipsizer with only one of the
             * parameters, the text (in createEllipsizer). And we fill in the rest of the needed
             * information (layout, width, and method) later, here.
             *
             * This will break if the superclass constructor ever actually cares about the content
             * instead of just holding the reference.
             */
            final Ellipsizer e = (Ellipsizer) getText();
            e.mLayout = this;
            e.mWidth = b.mEllipsizedWidth;
            e.mMethod = b.mEllipsize;
            mEllipsize = true;
        } else {
            mInts = new PackedIntVector(COLUMNS_NORMAL);
            mEllipsizedWidth = b.mWidth;
            mEllipsizeAt = null;
        }

        mObjects = new PackedObjectVector<>(1);

        // Initial state is a single line with 0 characters (0 to 0), with top at 0 and bottom at
        // whatever is natural, and undefined ellipsis.

        int[] start;

        if (b.mEllipsize != null) {
            start = new int[COLUMNS_ELLIPSIZE];
            start[ELLIPSIS_START] = ELLIPSIS_UNDEFINED;
        } else {
            start = new int[COLUMNS_NORMAL];
        }

        final Directions[] dirs = new Directions[] { DIRS_ALL_LEFT_TO_RIGHT };

        final Paint.FontMetricsInt fm = b.mFontMetricsInt;
        b.mPaint.getFontMetricsInt(fm);
        final int asc = fm.ascent;
        final int desc = fm.descent;

        start[DIR] = DIR_LEFT_TO_RIGHT << DIR_SHIFT;
        start[TOP] = 0;
        start[DESCENT] = desc;
        mInts.insertAt(0, start);

        start[TOP] = desc - asc;
        mInts.insertAt(1, start);

        mObjects.insertAt(0, dirs);

        // Update from 0 characters to whatever the displayed text is
        reflow(mBase, 0, 0, mDisplay.length());

        if (mBase instanceof Spannable) {
            if (mWatcher == null)
                mWatcher = new ChangeWatcher(this);

            // Strip out any watchers for other DynamicLayouts.
            final Spannable sp = (Spannable) mBase;
            final int baseLength = mBase.length();
            final ChangeWatcher[] spans = sp.getSpans(0, baseLength, ChangeWatcher.class);
            for (int i = 0; i < spans.length; i++) {
                sp.removeSpan(spans[i]);
            }

            sp.setSpan(mWatcher, 0, baseLength,
                       Spannable.SPAN_INCLUSIVE_INCLUSIVE |
                       (PRIORITY << Spannable.SPAN_PRIORITY_SHIFT));
        }
    }

    /** @hide */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void reflow(CharSequence s, int where, int before, int after) {
        if (TRACE_LAYOUT) {
            Trace.beginSection("DynamicLayout#reflow");
        }
        try {
            if (s != mBase) {
                return;
            }

            CharSequence text = mDisplay;
            int len = text.length();

            // seek back to the start of the paragraph

            int find = TextUtils.lastIndexOf(text, '\n', where - 1);
            if (find < 0) {
                find = 0;
            } else {
                find = find + 1;
            }

            {
                int diff = where - find;
                before += diff;
                after += diff;
                where -= diff;
            }

            // seek forward to the end of the paragraph

            int look = TextUtils.indexOf(text, '\n', where + after);
            if (look < 0) {
                look = len;
            } else {
                look++; // we want the index after the \n
            }

            int change = look - (where + after);
            before += change;
            after += change;

            // seek further out to cover anything that is forced to wrap together

            if (text instanceof Spanned) {
                Spanned sp = (Spanned) text;
                boolean again;

                do {
                    again = false;

                    Object[] force = sp.getSpans(where, where + after,
                            WrapTogetherSpan.class);

                    for (int i = 0; i < force.length; i++) {
                        int st = sp.getSpanStart(force[i]);
                        int en = sp.getSpanEnd(force[i]);

                        if (st < where) {
                            again = true;

                            int diff = where - st;
                            before += diff;
                            after += diff;
                            where -= diff;
                        }

                        if (en > where + after) {
                            again = true;

                            int diff = en - (where + after);
                            before += diff;
                            after += diff;
                        }
                    }
                } while (again);
            }

            // find affected region of old layout

            int startline = getLineForOffset(where);
            int startv = getLineTop(startline);

            int endline = getLineForOffset(where + before);
            if (where + after == len) {
                endline = getLineCount();
            }
            int endv = getLineTop(endline);
            boolean islast = (endline == getLineCount());

            // generate new layout for affected text

            StaticLayout reflowed;
            StaticLayout.Builder b;

            synchronized (sLock) {
                reflowed = sStaticLayout;
                b = sBuilder;
                sStaticLayout = null;
                sBuilder = null;
            }

            if (b == null) {
                b = StaticLayout.Builder.obtain(text, where, where + after, getPaint(), getWidth());
            }

            b.setText(text, where, where + after)
                    .setPaint(getPaint())
                    .setWidth(getWidth())
                    .setTextDirection(getTextDirectionHeuristic())
                    .setLineSpacing(getSpacingAdd(), getSpacingMultiplier())
                    .setUseLineSpacingFromFallbacks(mFallbackLineSpacing)
                    .setEllipsizedWidth(mEllipsizedWidth)
                    .setEllipsize(mEllipsizeAt)
                    .setBreakStrategy(mBreakStrategy)
                    .setHyphenationFrequency(mHyphenationFrequency)
                    .setJustificationMode(mJustificationMode)
                    .setLineBreakConfig(mLineBreakConfig)
                    .setAddLastLineLineSpacing(!islast)
                    .setIncludePad(false)
                    .setUseBoundsForWidth(mUseBoundsForWidth)
                    .setShiftDrawingOffsetForStartOverhang(mShiftDrawingOffsetForStartOverhang)
                    .setMinimumFontMetrics(mMinimumFontMetrics)
                    .setCalculateBounds(true);

            reflowed = b.buildPartialStaticLayoutForDynamicLayout(true /* trackpadding */,
                    reflowed);
            int n = reflowed.getLineCount();
            // If the new layout has a blank line at the end, but it is not
            // the very end of the buffer, then we already have a line that
            // starts there, so disregard the blank line.

            if (where + after != len && reflowed.getLineStart(n - 1) == where + after) {
                n--;
            }

            // remove affected lines from old layout
            mInts.deleteAt(startline, endline - startline);
            mObjects.deleteAt(startline, endline - startline);

            // adjust offsets in layout for new height and offsets

            int ht = reflowed.getLineTop(n);
            int toppad = 0, botpad = 0;

            if (mIncludePad && startline == 0) {
                toppad = reflowed.getTopPadding();
                mTopPadding = toppad;
                ht -= toppad;
            }
            if (mIncludePad && islast) {
                botpad = reflowed.getBottomPadding();
                mBottomPadding = botpad;
                ht += botpad;
            }

            mInts.adjustValuesBelow(startline, START, after - before);
            mInts.adjustValuesBelow(startline, TOP, startv - endv + ht);

            // insert new layout

            int[] ints;

            if (mEllipsize) {
                ints = new int[COLUMNS_ELLIPSIZE];
                ints[ELLIPSIS_START] = ELLIPSIS_UNDEFINED;
            } else {
                ints = new int[COLUMNS_NORMAL];
            }

            Directions[] objects = new Directions[1];

            for (int i = 0; i < n; i++) {
                final int start = reflowed.getLineStart(i);
                ints[START] = start;
                ints[DIR] |= reflowed.getParagraphDirection(i) << DIR_SHIFT;
                ints[TAB] |= reflowed.getLineContainsTab(i) ? TAB_MASK : 0;

                int top = reflowed.getLineTop(i) + startv;
                if (i > 0) {
                    top -= toppad;
                }
                ints[TOP] = top;

                int desc = reflowed.getLineDescent(i);
                if (i == n - 1) {
                    desc += botpad;
                }

                ints[DESCENT] = desc;
                ints[EXTRA] = reflowed.getLineExtra(i);
                objects[0] = reflowed.getLineDirections(i);

                final int end = (i == n - 1) ? where + after : reflowed.getLineStart(i + 1);
                ints[HYPHEN] = StaticLayout.packHyphenEdit(
                        reflowed.getStartHyphenEdit(i), reflowed.getEndHyphenEdit(i));
                ints[MAY_PROTRUDE_FROM_TOP_OR_BOTTOM] |=
                        contentMayProtrudeFromLineTopOrBottom(text, start, end)
                                ? MAY_PROTRUDE_FROM_TOP_OR_BOTTOM_MASK : 0;

                if (mEllipsize) {
                    ints[ELLIPSIS_START] = reflowed.getEllipsisStart(i);
                    ints[ELLIPSIS_COUNT] = reflowed.getEllipsisCount(i);
                }

                mInts.insertAt(startline + i, ints);
                mObjects.insertAt(startline + i, objects);
            }

            updateBlocks(startline, endline - 1, n);

            b.finish();
            synchronized (sLock) {
                sStaticLayout = reflowed;
                sBuilder = b;
            }
        } finally {
            if (TRACE_LAYOUT) {
                Trace.endSection();
            }
        }
    }

    private boolean contentMayProtrudeFromLineTopOrBottom(CharSequence text, int start, int end) {
        if (text instanceof Spanned) {
            final Spanned spanned = (Spanned) text;
            if (spanned.getSpans(start, end, ReplacementSpan.class).length > 0) {
                return true;
            }
        }
        // Spans other than ReplacementSpan can be ignored because line top and bottom are
        // disjunction of all tops and bottoms, although it's not optimal.
        final Paint paint = getPaint();
        if (text instanceof PrecomputedText) {
            PrecomputedText precomputed = (PrecomputedText) text;
            precomputed.getBounds(start, end, mTempRect);
        } else {
            paint.getTextBounds(text, start, end, mTempRect);
        }
        final Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        return mTempRect.top < fm.top || mTempRect.bottom > fm.bottom;
    }

    /**
     * Create the initial block structure, cutting the text into blocks of at least
     * BLOCK_MINIMUM_CHARACTER_SIZE characters, aligned on the ends of paragraphs.
     */
    private void createBlocks() {
        int offset = BLOCK_MINIMUM_CHARACTER_LENGTH;
        mNumberOfBlocks = 0;
        final CharSequence text = mDisplay;

        while (true) {
            offset = TextUtils.indexOf(text, '\n', offset);
            if (offset < 0) {
                addBlockAtOffset(text.length());
                break;
            } else {
                addBlockAtOffset(offset);
                offset += BLOCK_MINIMUM_CHARACTER_LENGTH;
            }
        }

        // mBlockIndices and mBlockEndLines should have the same length
        mBlockIndices = new int[mBlockEndLines.length];
        for (int i = 0; i < mBlockEndLines.length; i++) {
            mBlockIndices[i] = INVALID_BLOCK_INDEX;
        }
    }

    /**
     * @hide
     */
    public ArraySet<Integer> getBlocksAlwaysNeedToBeRedrawn() {
        return mBlocksAlwaysNeedToBeRedrawn;
    }

    private void updateAlwaysNeedsToBeRedrawn(int blockIndex) {
        int startLine = blockIndex == 0 ? 0 : (mBlockEndLines[blockIndex - 1] + 1);
        int endLine = mBlockEndLines[blockIndex];
        for (int i = startLine; i <= endLine; i++) {
            if (getContentMayProtrudeFromTopOrBottom(i)) {
                if (mBlocksAlwaysNeedToBeRedrawn == null) {
                    mBlocksAlwaysNeedToBeRedrawn = new ArraySet<>();
                }
                mBlocksAlwaysNeedToBeRedrawn.add(blockIndex);
                return;
            }
        }
        if (mBlocksAlwaysNeedToBeRedrawn != null) {
            mBlocksAlwaysNeedToBeRedrawn.remove(blockIndex);
        }
    }

    /**
     * Create a new block, ending at the specified character offset.
     * A block will actually be created only if has at least one line, i.e. this offset is
     * not on the end line of the previous block.
     */
    private void addBlockAtOffset(int offset) {
        final int line = getLineForOffset(offset);
        if (mBlockEndLines == null) {
            // Initial creation of the array, no test on previous block ending line
            mBlockEndLines = ArrayUtils.newUnpaddedIntArray(1);
            mBlockEndLines[mNumberOfBlocks] = line;
            updateAlwaysNeedsToBeRedrawn(mNumberOfBlocks);
            mNumberOfBlocks++;
            return;
        }

        final int previousBlockEndLine = mBlockEndLines[mNumberOfBlocks - 1];
        if (line > previousBlockEndLine) {
            mBlockEndLines = GrowingArrayUtils.append(mBlockEndLines, mNumberOfBlocks, line);
            updateAlwaysNeedsToBeRedrawn(mNumberOfBlocks);
            mNumberOfBlocks++;
        }
    }

    /**
     * This method is called every time the layout is reflowed after an edition.
     * It updates the internal block data structure. The text is split in blocks
     * of contiguous lines, with at least one block for the entire text.
     * When a range of lines is edited, new blocks (from 0 to 3 depending on the
     * overlap structure) will replace the set of overlapping blocks.
     * Blocks are listed in order and are represented by their ending line number.
     * An index is associated to each block (which will be used by display lists),
     * this class simply invalidates the index of blocks overlapping a modification.
     *
     * @param startLine the first line of the range of modified lines
     * @param endLine the last line of the range, possibly equal to startLine, lower
     * than getLineCount()
     * @param newLineCount the number of lines that will replace the range, possibly 0
     *
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void updateBlocks(int startLine, int endLine, int newLineCount) {
        if (mBlockEndLines == null) {
            createBlocks();
            return;
        }

        /*final*/ int firstBlock = -1;
        /*final*/ int lastBlock = -1;
        for (int i = 0; i < mNumberOfBlocks; i++) {
            if (mBlockEndLines[i] >= startLine) {
                firstBlock = i;
                break;
            }
        }
        for (int i = firstBlock; i < mNumberOfBlocks; i++) {
            if (mBlockEndLines[i] >= endLine) {
                lastBlock = i;
                break;
            }
        }
        final int lastBlockEndLine = mBlockEndLines[lastBlock];

        final boolean createBlockBefore = startLine > (firstBlock == 0 ? 0 :
                mBlockEndLines[firstBlock - 1] + 1);
        final boolean createBlock = newLineCount > 0;
        final boolean createBlockAfter = endLine < mBlockEndLines[lastBlock];

        int numAddedBlocks = 0;
        if (createBlockBefore) numAddedBlocks++;
        if (createBlock) numAddedBlocks++;
        if (createBlockAfter) numAddedBlocks++;

        final int numRemovedBlocks = lastBlock - firstBlock + 1;
        final int newNumberOfBlocks = mNumberOfBlocks + numAddedBlocks - numRemovedBlocks;

        if (newNumberOfBlocks == 0) {
            // Even when text is empty, there is actually one line and hence one block
            mBlockEndLines[0] = 0;
            mBlockIndices[0] = INVALID_BLOCK_INDEX;
            mNumberOfBlocks = 1;
            return;
        }

        if (newNumberOfBlocks > mBlockEndLines.length) {
            int[] blockEndLines = ArrayUtils.newUnpaddedIntArray(
                    Math.max(mBlockEndLines.length * 2, newNumberOfBlocks));
            int[] blockIndices = new int[blockEndLines.length];
            System.arraycopy(mBlockEndLines, 0, blockEndLines, 0, firstBlock);
            System.arraycopy(mBlockIndices, 0, blockIndices, 0, firstBlock);
            System.arraycopy(mBlockEndLines, lastBlock + 1,
                    blockEndLines, firstBlock + numAddedBlocks, mNumberOfBlocks - lastBlock - 1);
            System.arraycopy(mBlockIndices, lastBlock + 1,
                    blockIndices, firstBlock + numAddedBlocks, mNumberOfBlocks - lastBlock - 1);
            mBlockEndLines = blockEndLines;
            mBlockIndices = blockIndices;
        } else if (numAddedBlocks + numRemovedBlocks != 0) {
            System.arraycopy(mBlockEndLines, lastBlock + 1,
                    mBlockEndLines, firstBlock + numAddedBlocks, mNumberOfBlocks - lastBlock - 1);
            System.arraycopy(mBlockIndices, lastBlock + 1,
                    mBlockIndices, firstBlock + numAddedBlocks, mNumberOfBlocks - lastBlock - 1);
        }

        if (numAddedBlocks + numRemovedBlocks != 0 && mBlocksAlwaysNeedToBeRedrawn != null) {
            final ArraySet<Integer> set = new ArraySet<>();
            final int changedBlockCount = numAddedBlocks - numRemovedBlocks;
            for (int i = 0; i < mBlocksAlwaysNeedToBeRedrawn.size(); i++) {
                Integer block = mBlocksAlwaysNeedToBeRedrawn.valueAt(i);
                if (block < firstBlock) {
                    // block index is before firstBlock add it since it did not change
                    set.add(block);
                }
                if (block > lastBlock) {
                    // block index is after lastBlock, the index reduced to += changedBlockCount
                    block += changedBlockCount;
                    set.add(block);
                }
            }
            mBlocksAlwaysNeedToBeRedrawn = set;
        }

        mNumberOfBlocks = newNumberOfBlocks;
        int newFirstChangedBlock;
        final int deltaLines = newLineCount - (endLine - startLine + 1);
        if (deltaLines != 0) {
            // Display list whose index is >= mIndexFirstChangedBlock is valid
            // but it needs to update its drawing location.
            newFirstChangedBlock = firstBlock + numAddedBlocks;
            for (int i = newFirstChangedBlock; i < mNumberOfBlocks; i++) {
                mBlockEndLines[i] += deltaLines;
            }
        } else {
            newFirstChangedBlock = mNumberOfBlocks;
        }
        mIndexFirstChangedBlock = Math.min(mIndexFirstChangedBlock, newFirstChangedBlock);

        int blockIndex = firstBlock;
        if (createBlockBefore) {
            mBlockEndLines[blockIndex] = startLine - 1;
            updateAlwaysNeedsToBeRedrawn(blockIndex);
            mBlockIndices[blockIndex] = INVALID_BLOCK_INDEX;
            blockIndex++;
        }

        if (createBlock) {
            mBlockEndLines[blockIndex] = startLine + newLineCount - 1;
            updateAlwaysNeedsToBeRedrawn(blockIndex);
            mBlockIndices[blockIndex] = INVALID_BLOCK_INDEX;
            blockIndex++;
        }

        if (createBlockAfter) {
            mBlockEndLines[blockIndex] = lastBlockEndLine + deltaLines;
            updateAlwaysNeedsToBeRedrawn(blockIndex);
            mBlockIndices[blockIndex] = INVALID_BLOCK_INDEX;
        }
    }

    /**
     * This method is used for test purposes only.
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void setBlocksDataForTest(int[] blockEndLines, int[] blockIndices, int numberOfBlocks,
            int totalLines) {
        mBlockEndLines = new int[blockEndLines.length];
        mBlockIndices = new int[blockIndices.length];
        System.arraycopy(blockEndLines, 0, mBlockEndLines, 0, blockEndLines.length);
        System.arraycopy(blockIndices, 0, mBlockIndices, 0, blockIndices.length);
        mNumberOfBlocks = numberOfBlocks;
        while (mInts.size() < totalLines) {
            mInts.insertAt(mInts.size(), new int[COLUMNS_NORMAL]);
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public int[] getBlockEndLines() {
        return mBlockEndLines;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public int[] getBlockIndices() {
        return mBlockIndices;
    }

    /**
     * @hide
     */
    public int getBlockIndex(int index) {
        return mBlockIndices[index];
    }

    /**
     * @hide
     * @param index
     */
    public void setBlockIndex(int index, int blockIndex) {
        mBlockIndices[index] = blockIndex;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public int getNumberOfBlocks() {
        return mNumberOfBlocks;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int getIndexFirstChangedBlock() {
        return mIndexFirstChangedBlock;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setIndexFirstChangedBlock(int i) {
        mIndexFirstChangedBlock = i;
    }

    @Override
    public int getLineCount() {
        return mInts.size() - 1;
    }

    @Override
    public int getLineTop(int line) {
        return mInts.getValue(line, TOP);
    }

    @Override
    public int getLineDescent(int line) {
        return mInts.getValue(line, DESCENT);
    }

    /**
     * @hide
     */
    @Override
    public int getLineExtra(int line) {
        return mInts.getValue(line, EXTRA);
    }

    @Override
    public int getLineStart(int line) {
        return mInts.getValue(line, START) & START_MASK;
    }

    @Override
    public boolean getLineContainsTab(int line) {
        return (mInts.getValue(line, TAB) & TAB_MASK) != 0;
    }

    @Override
    public int getParagraphDirection(int line) {
        return mInts.getValue(line, DIR) >> DIR_SHIFT;
    }

    @Override
    public final Directions getLineDirections(int line) {
        return mObjects.getValue(line, 0);
    }

    @Override
    public int getTopPadding() {
        return mTopPadding;
    }

    @Override
    public int getBottomPadding() {
        return mBottomPadding;
    }

    /**
     * @hide
     */
    @Override
    public @Paint.StartHyphenEdit int getStartHyphenEdit(int line) {
        return StaticLayout.unpackStartHyphenEdit(mInts.getValue(line, HYPHEN) & HYPHEN_MASK);
    }

    /**
     * @hide
     */
    @Override
    public @Paint.EndHyphenEdit int getEndHyphenEdit(int line) {
        return StaticLayout.unpackEndHyphenEdit(mInts.getValue(line, HYPHEN) & HYPHEN_MASK);
    }

    private boolean getContentMayProtrudeFromTopOrBottom(int line) {
        return (mInts.getValue(line, MAY_PROTRUDE_FROM_TOP_OR_BOTTOM)
                & MAY_PROTRUDE_FROM_TOP_OR_BOTTOM_MASK) != 0;
    }

    @Override
    public int getEllipsizedWidth() {
        return mEllipsizedWidth;
    }

    private static class ChangeWatcher implements TextWatcher, SpanWatcher {
        public ChangeWatcher(DynamicLayout layout) {
            mLayout = new WeakReference<>(layout);
        }

        private void reflow(CharSequence s, int where, int before, int after) {
            DynamicLayout ml = mLayout.get();

            if (ml != null) {
                ml.reflow(s, where, before, after);
            } else if (s instanceof Spannable) {
                ((Spannable) s).removeSpan(this);
            }
        }

        public void beforeTextChanged(CharSequence s, int where, int before, int after) {
            final DynamicLayout dynamicLayout = mLayout.get();
            if (dynamicLayout != null && dynamicLayout.mDisplay instanceof OffsetMapping) {
                final OffsetMapping transformedText = (OffsetMapping) dynamicLayout.mDisplay;
                if (mTransformedTextUpdate == null) {
                    mTransformedTextUpdate = new OffsetMapping.TextUpdate(where, before, after);
                } else {
                    mTransformedTextUpdate.where = where;
                    mTransformedTextUpdate.before = before;
                    mTransformedTextUpdate.after = after;
                }
                // When there is a transformed text, we have to reflow the DynamicLayout based on
                // the transformed indices instead of the range in base text.
                // For example,
                //   base text:         abcd    >   abce
                //   updated range:     where = 3, before = 1, after = 1
                //   transformed text:  abxxcd  >   abxxce
                //   updated range:     where = 5, before = 1, after = 1
                //
                // Because the transformedText is udapted simultaneously with the base text,
                // the range must be transformed before the base text changes.
                transformedText.originalToTransformed(mTransformedTextUpdate);
            }
        }

        public void onTextChanged(CharSequence s, int where, int before, int after) {
            final DynamicLayout dynamicLayout = mLayout.get();
            if (dynamicLayout != null && dynamicLayout.mDisplay instanceof OffsetMapping) {
                if (mTransformedTextUpdate != null && mTransformedTextUpdate.where >= 0) {
                    where = mTransformedTextUpdate.where;
                    before = mTransformedTextUpdate.before;
                    after = mTransformedTextUpdate.after;
                    // Set where to -1 so that we know if beforeTextChanged is called.
                    mTransformedTextUpdate.where = -1;
                } else {
                    // onTextChanged is called without beforeTextChanged. Reflow the entire text.
                    where = 0;
                    // We can't get the before length from the text, use the line end of the
                    // last line instead.
                    before = dynamicLayout.getLineEnd(dynamicLayout.getLineCount() - 1);
                    after = dynamicLayout.mDisplay.length();
                }
            }
            reflow(s, where, before, after);
        }

        public void afterTextChanged(Editable s) {
            // Intentionally empty
        }

        /**
         * Reflow the {@link DynamicLayout} at the given range from {@code start} to the
         * {@code end}.
         * If the display text in this {@link DynamicLayout} is a {@link OffsetMapping} instance
         * (which means it's also a transformed text), it will transform the given range first and
         * then reflow.
         */
        private void transformAndReflow(Spannable s, int start, int end) {
            final DynamicLayout dynamicLayout = mLayout.get();
            if (dynamicLayout != null && dynamicLayout.mDisplay instanceof OffsetMapping) {
                final OffsetMapping transformedText = (OffsetMapping) dynamicLayout.mDisplay;
                start = transformedText.originalToTransformed(start,
                        OffsetMapping.MAP_STRATEGY_CHARACTER);
                end = transformedText.originalToTransformed(end,
                        OffsetMapping.MAP_STRATEGY_CHARACTER);
            }
            reflow(s, start, end - start, end - start);
        }

        public void onSpanAdded(Spannable s, Object o, int start, int end) {
            if (o instanceof UpdateLayout) {
                transformAndReflow(s, start, end);
            }
        }

        public void onSpanRemoved(Spannable s, Object o, int start, int end) {
            if (o instanceof UpdateLayout) {
                if (Flags.insertModeCrashWhenDelete()) {
                    final DynamicLayout dynamicLayout = mLayout.get();
                    if (dynamicLayout != null && dynamicLayout.mDisplay instanceof OffsetMapping) {
                        // It's possible that a Span is removed when the text covering it is
                        // deleted, in this case, the original start and end of the span might be
                        // OOB. So it'll reflow the entire string instead.
                        reflow(s, 0, 0, s.length());
                    } else {
                        reflow(s, start, end - start, end - start);
                    }
                } else {
                    transformAndReflow(s, start, end);
                }
            }
        }

        public void onSpanChanged(Spannable s, Object o, int start, int end, int nstart, int nend) {
            if (o instanceof UpdateLayout) {
                if (start > end) {
                    // Bug: 67926915 start cannot be determined, fallback to reflow from start
                    // instead of causing an exception
                    start = 0;
                }
                if (Flags.insertModeCrashWhenDelete()) {
                    final DynamicLayout dynamicLayout = mLayout.get();
                    if (dynamicLayout != null && dynamicLayout.mDisplay instanceof OffsetMapping) {
                        // When text is changed, it'll also trigger onSpanChanged. In this case we
                        // can't determine the updated range in the transformed text. So it'll
                        // reflow the entire range instead.
                        reflow(s, 0, 0, s.length());
                    } else {
                        reflow(s, start, end - start, end - start);
                        reflow(s, nstart, nend - nstart, nend - nstart);
                    }
                } else {
                    transformAndReflow(s, start, end);
                    transformAndReflow(s, nstart, nend);
                }
            }
        }

        private WeakReference<DynamicLayout> mLayout;
        private OffsetMapping.TextUpdate mTransformedTextUpdate;
    }

    @Override
    public int getEllipsisStart(int line) {
        if (mEllipsizeAt == null) {
            return 0;
        }

        return mInts.getValue(line, ELLIPSIS_START);
    }

    @Override
    public int getEllipsisCount(int line) {
        if (mEllipsizeAt == null) {
            return 0;
        }

        return mInts.getValue(line, ELLIPSIS_COUNT);
    }

    /**
     * Gets the {@link LineBreakConfig} used in this DynamicLayout.
     * Use this only to consult the LineBreakConfig's properties and not
     * to change them.
     *
     * @return The line break config in this DynamicLayout.
     */
    @NonNull
    public LineBreakConfig getLineBreakConfig() {
        return mLineBreakConfig;
    }

    private CharSequence mBase;
    private CharSequence mDisplay;
    private ChangeWatcher mWatcher;
    private boolean mIncludePad;
    private boolean mFallbackLineSpacing;
    private boolean mEllipsize;
    private int mEllipsizedWidth;
    private TextUtils.TruncateAt mEllipsizeAt;
    private int mBreakStrategy;
    private int mHyphenationFrequency;
    private int mJustificationMode;
    private LineBreakConfig mLineBreakConfig;

    private PackedIntVector mInts;
    private PackedObjectVector<Directions> mObjects;

    /**
     * Value used in mBlockIndices when a block has been created or recycled and indicating that its
     * display list needs to be re-created.
     * @hide
     */
    public static final int INVALID_BLOCK_INDEX = -1;
    // Stores the line numbers of the last line of each block (inclusive)
    private int[] mBlockEndLines;
    // The indices of this block's display list in TextView's internal display list array or
    // INVALID_BLOCK_INDEX if this block has been invalidated during an edition
    private int[] mBlockIndices;
    // Set of blocks that always need to be redrawn.
    private ArraySet<Integer> mBlocksAlwaysNeedToBeRedrawn;
    // Number of items actually currently being used in the above 2 arrays
    private int mNumberOfBlocks;
    // The first index of the blocks whose locations are changed
    private int mIndexFirstChangedBlock;

    private int mTopPadding, mBottomPadding;

    private Rect mTempRect = new Rect();

    private boolean mUseBoundsForWidth;
    private boolean mShiftDrawingOffsetForStartOverhang;
    @Nullable Paint.FontMetrics mMinimumFontMetrics;

    @UnsupportedAppUsage
    private static StaticLayout sStaticLayout = null;
    private static StaticLayout.Builder sBuilder = null;

    private static final Object[] sLock = new Object[0];

    // START, DIR, and TAB share the same entry.
    private static final int START = 0;
    private static final int DIR = START;
    private static final int TAB = START;
    private static final int TOP = 1;
    private static final int DESCENT = 2;
    private static final int EXTRA = 3;
    // HYPHEN and MAY_PROTRUDE_FROM_TOP_OR_BOTTOM share the same entry.
    private static final int HYPHEN = 4;
    private static final int MAY_PROTRUDE_FROM_TOP_OR_BOTTOM = HYPHEN;
    private static final int COLUMNS_NORMAL = 5;

    private static final int ELLIPSIS_START = 5;
    private static final int ELLIPSIS_COUNT = 6;
    private static final int COLUMNS_ELLIPSIZE = 7;

    private static final int START_MASK = 0x1FFFFFFF;
    private static final int DIR_SHIFT  = 30;
    private static final int TAB_MASK   = 0x20000000;
    private static final int HYPHEN_MASK = 0xFF;
    private static final int MAY_PROTRUDE_FROM_TOP_OR_BOTTOM_MASK = 0x100;

    private static final int ELLIPSIS_UNDEFINED = 0x80000000;
}
