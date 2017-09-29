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

import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Paint;
import android.os.LocaleList;
import android.text.style.LeadingMarginSpan;
import android.text.style.LeadingMarginSpan.LeadingMarginSpan2;
import android.text.style.LineHeightSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.TabStopSpan;
import android.util.Log;
import android.util.Pair;
import android.util.Pools.SynchronizedPool;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.GrowingArrayUtils;

import java.util.Arrays;
import java.util.Locale;

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

    /**
     * Builder for static layouts. The builder is the preferred pattern for constructing
     * StaticLayout objects and should be preferred over the constructors, particularly to access
     * newer features. To build a static layout, first call {@link #obtain} with the required
     * arguments (text, paint, and width), then call setters for optional parameters, and finally
     * {@link #build} to build the StaticLayout object. Parameters not explicitly set will get
     * default values.
     */
    public final static class Builder {
        private Builder() {
            mNativePtr = nNewBuilder();
        }

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
            b.mLocales = null;

            b.mMeasuredText = MeasuredText.obtain();
            return b;
        }

        /**
         * This method should be called after the layout is finished getting constructed and the
         * builder needs to be cleaned up and returned to the pool.
         */
        private static void recycle(@NonNull Builder b) {
            b.mPaint = null;
            b.mText = null;
            MeasuredText.recycle(b.mMeasuredText);
            b.mMeasuredText = null;
            b.mLeftIndents = null;
            b.mRightIndents = null;
            b.mLocales = null;
            b.mLeftPaddings = null;
            b.mRightPaddings = null;
            nFinishBuilder(b.mNativePtr);
            sPool.release(b);
        }

        // release any expensive state
        /* package */ void finish() {
            nFinishBuilder(mNativePtr);
            mText = null;
            mPaint = null;
            mLeftIndents = null;
            mRightIndents = null;
            mLeftPaddings = null;
            mRightPaddings = null;
            mMeasuredText.finish();
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
         * Set available paddings to draw overhanging text on. Arguments are arrays holding the
         * amount of padding available, one per line, measured in pixels. For lines past the last
         * element in the array, the last element repeats.
         *
         * The individual padding amounts should be non-negative. The result of passing negative
         * paddings is undefined.
         *
         * @param leftPaddings array of amounts of available padding for left margin, in pixels
         * @param rightPaddings array of amounts of available padding for right margin, in pixels
         * @return this builder, useful for chaining
         *
         * @hide
         */
        @NonNull
        public Builder setAvailablePaddings(@Nullable int[] leftPaddings,
                @Nullable int[] rightPaddings) {
            mLeftPaddings = leftPaddings;
            mRightPaddings = rightPaddings;
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

        @NonNull
        private long[] getHyphenators(@NonNull LocaleList locales) {
            final int length = locales.size();
            final long[] result = new long[length];
            for (int i = 0; i < length; i++) {
                final Locale locale = locales.get(i);
                result[i] = Hyphenator.get(locale).getNativePtr();
            }
            return result;
        }

        /**
         * Measurement and break iteration is done in native code. The protocol for using
         * the native code is as follows.
         *
         * For each paragraph, do a nSetupParagraph, which sets paragraph text, line width, tab
         * stops, break strategy, and hyphenation frequency (and possibly other parameters in the
         * future).
         *
         * Then, for each run within the paragraph:
         *  - one of the following, depending on the type of run:
         *    + addStyleRun (a text run, to be measured in native code)
         *    + addMeasuredRun (a run already measured in Java, passed into native code)
         *    + addReplacementRun (a replacement run, width is given)
         *
         * After measurement, nGetWidths() is valid if the widths are needed (eg for ellipsis).
         * Run nComputeLineBreaks() to obtain line breaks for the paragraph.
         *
         * After all paragraphs, call finish() to release expensive buffers.
         */

        private Pair<String, long[]> getLocaleAndHyphenatorIfChanged(TextPaint paint) {
            final LocaleList locales = paint.getTextLocales();
            final String languageTags;
            long[] hyphenators;
            if (!locales.equals(mLocales)) {
                mLocales = locales;
                return new Pair(locales.toLanguageTags(), getHyphenators(locales));
            } else {
                // passing null means keep current locale.
                // TODO: move locale change detection to native.
                return new Pair(null, null);
            }
        }

        /* package */ float addStyleRun(TextPaint paint, int start, int end, boolean isRtl) {
            Pair<String, long[]> locHyph = getLocaleAndHyphenatorIfChanged(paint);
            return nAddStyleRun(mNativePtr, paint.getNativeInstance(), start, end, isRtl,
                    locHyph.first, locHyph.second);
        }

        /* package */ void addMeasuredRun(TextPaint paint, int start, int end, float[] widths) {
            Pair<String, long[]> locHyph = getLocaleAndHyphenatorIfChanged(paint);
            nAddMeasuredRun(mNativePtr, start, end, widths, locHyph.first, locHyph.second);
        }

        /* package */ void addReplacementRun(TextPaint paint, int start, int end, float width) {
            Pair<String, long[]> locHyph = getLocaleAndHyphenatorIfChanged(paint);
            nAddReplacementRun(mNativePtr, start, end, width, locHyph.first, locHyph.second);
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
            StaticLayout result = new StaticLayout(this);
            Builder.recycle(this);
            return result;
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                nFreeBuilder(mNativePtr);
            } finally {
                super.finalize();
            }
        }

        /* package */ long mNativePtr;

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
        @Nullable private int[] mLeftPaddings;
        @Nullable private int[] mRightPaddings;
        private int mJustificationMode;
        private boolean mAddLastLineLineSpacing;

        private final Paint.FontMetricsInt mFontMetricsInt = new Paint.FontMetricsInt();

        // This will go away and be subsumed by native builder code
        private MeasuredText mMeasuredText;

        private LocaleList mLocales;

        private static final SynchronizedPool<Builder> sPool = new SynchronizedPool<Builder>(3);
    }

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

        Builder b = Builder.obtain(source, bufstart, bufend, paint, outerwidth)
            .setAlignment(align)
            .setTextDirection(textDir)
            .setLineSpacing(spacingadd, spacingmult)
            .setIncludePad(includepad)
            .setEllipsizedWidth(ellipsizedWidth)
            .setEllipsize(ellipsize)
            .setMaxLines(maxLines);
        /*
         * This is annoying, but we can't refer to the layout until superclass construction is
         * finished, and the superclass constructor wants the reference to the display text.
         *
         * In other words, the two Ellipsizer classes in Layout.java need a (Dynamic|Static)Layout
         * as a parameter to do their calculations, but the Ellipsizers also need to be the input
         * to the superclass's constructor (Layout). In order to go around the circular
         * dependency, we construct the Ellipsizer with only one of the parameters, the text. And
         * we fill in the rest of the needed information (layout, width, and method) later, here.
         *
         * This will break if the superclass constructor ever actually cares about the content
         * instead of just holding the reference.
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

        mLineDirections = ArrayUtils.newUnpaddedArray(Directions.class, 2);
        mLines  = ArrayUtils.newUnpaddedIntArray(2 * mColumns);
        mMaximumVisibleLineCount = maxLines;

        generate(b, b.mIncludePad, b.mIncludePad);

        Builder.recycle(b);
    }

    /* package */ StaticLayout(@Nullable CharSequence text) {
        super(text, null, 0, null, 0, 0);

        mColumns = COLUMNS_ELLIPSIZE;
        mLineDirections = ArrayUtils.newUnpaddedArray(Directions.class, 2);
        mLines  = ArrayUtils.newUnpaddedIntArray(2 * mColumns);
    }

    private StaticLayout(Builder b) {
        super((b.mEllipsize == null)
                ? b.mText
                : (b.mText instanceof Spanned)
                    ? new SpannedEllipsizer(b.mText)
                    : new Ellipsizer(b.mText),
                b.mPaint, b.mWidth, b.mAlignment, b.mTextDir, b.mSpacingMult, b.mSpacingAdd);

        if (b.mEllipsize != null) {
            Ellipsizer e = (Ellipsizer) getText();

            e.mLayout = this;
            e.mWidth = b.mEllipsizedWidth;
            e.mMethod = b.mEllipsize;
            mEllipsizedWidth = b.mEllipsizedWidth;

            mColumns = COLUMNS_ELLIPSIZE;
        } else {
            mColumns = COLUMNS_NORMAL;
            mEllipsizedWidth = b.mWidth;
        }

        mLineDirections = ArrayUtils.newUnpaddedArray(Directions.class, 2);
        mLines  = ArrayUtils.newUnpaddedIntArray(2 * mColumns);
        mMaximumVisibleLineCount = b.mMaxLines;

        mLeftIndents = b.mLeftIndents;
        mRightIndents = b.mRightIndents;
        mLeftPaddings = b.mLeftPaddings;
        mRightPaddings = b.mRightPaddings;
        setJustificationMode(b.mJustificationMode);

        generate(b, b.mIncludePad, b.mIncludePad);
    }

    /* package */ void generate(Builder b, boolean includepad, boolean trackpad) {
        final CharSequence source = b.mText;
        int bufStart = b.mStart;
        int bufEnd = b.mEnd;
        TextPaint paint = b.mPaint;
        int outerWidth = b.mWidth;
        TextDirectionHeuristic textDir = b.mTextDir;
        final boolean fallbackLineSpacing = b.mFallbackLineSpacing;
        float spacingmult = b.mSpacingMult;
        float spacingadd = b.mSpacingAdd;
        float ellipsizedWidth = b.mEllipsizedWidth;
        TextUtils.TruncateAt ellipsize = b.mEllipsize;
        final boolean addLastLineSpacing = b.mAddLastLineLineSpacing;
        LineBreaks lineBreaks = new LineBreaks();  // TODO: move to builder to avoid allocation costs
        // store span end locations
        int[] spanEndCache = new int[4];
        // store fontMetrics per span range
        // must be a multiple of 4 (and > 0) (store top, bottom, ascent, and descent per range)
        int[] fmCache = new int[4 * 4];

        mLineCount = 0;
        mEllipsized = false;
        mMaxLineHeight = mMaximumVisibleLineCount < 1 ? 0 : DEFAULT_MAX_LINE_HEIGHT;

        int v = 0;
        boolean needMultiply = (spacingmult != 1 || spacingadd != 0);

        Paint.FontMetricsInt fm = b.mFontMetricsInt;
        int[] chooseHtv = null;

        MeasuredText measured = b.mMeasuredText;

        Spanned spanned = null;
        if (source instanceof Spanned)
            spanned = (Spanned) source;

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

        int paraEnd;
        for (int paraStart = bufStart; paraStart <= bufEnd; paraStart = paraEnd) {
            paraEnd = TextUtils.indexOf(source, CHAR_NEW_LINE, paraStart, bufEnd);
            if (paraEnd < 0)
                paraEnd = bufEnd;
            else
                paraEnd++;

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

            measured.setPara(source, paraStart, paraEnd, textDir, b);
            char[] chs = measured.mChars;
            float[] widths = measured.mWidths;
            byte[] chdirs = measured.mLevels;
            int dir = measured.mDir;
            boolean easy = measured.mEasy;

            // tab stop locations
            int[] variableTabStops = null;
            if (spanned != null) {
                TabStopSpan[] spans = getParagraphSpans(spanned, paraStart,
                        paraEnd, TabStopSpan.class);
                if (spans.length > 0) {
                    int[] stops = new int[spans.length];
                    for (int i = 0; i < spans.length; i++) {
                        stops[i] = spans[i].getTabStop();
                    }
                    Arrays.sort(stops, 0, stops.length);
                    variableTabStops = stops;
                }
            }

            // TODO: Move locale tracking code to native.
            b.mLocales = null;  // Reset the locale tracking.

            nSetupParagraph(b.mNativePtr, chs, paraEnd - paraStart,
                    firstWidth, firstWidthLineCount, restWidth,
                    variableTabStops, TAB_INCREMENT, b.mBreakStrategy, b.mHyphenationFrequency,
                    // TODO: Support more justification mode, e.g. letter spacing, stretching.
                    b.mJustificationMode != Layout.JUSTIFICATION_MODE_NONE,
                    // TODO: indents and paddings don't need to get passed to native code for every
                    // paragraph. Pass them to native code just once.
                    indents, mLeftPaddings, mRightPaddings, mLineCount);

            // measurement has to be done before performing line breaking
            // but we don't want to recompute fontmetrics or span ranges the
            // second time, so we cache those and then use those stored values
            int fmCacheCount = 0;
            int spanEndCacheCount = 0;
            for (int spanStart = paraStart, spanEnd; spanStart < paraEnd; spanStart = spanEnd) {
                if (fmCacheCount * 4 >= fmCache.length) {
                    int[] grow = new int[fmCacheCount * 4 * 2];
                    System.arraycopy(fmCache, 0, grow, 0, fmCacheCount * 4);
                    fmCache = grow;
                }

                if (spanEndCacheCount >= spanEndCache.length) {
                    int[] grow = new int[spanEndCacheCount * 2];
                    System.arraycopy(spanEndCache, 0, grow, 0, spanEndCacheCount);
                    spanEndCache = grow;
                }

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

                // the order of storage here (top, bottom, ascent, descent) has to match the code below
                // where these values are retrieved
                fmCache[fmCacheCount * 4 + 0] = fm.top;
                fmCache[fmCacheCount * 4 + 1] = fm.bottom;
                fmCache[fmCacheCount * 4 + 2] = fm.ascent;
                fmCache[fmCacheCount * 4 + 3] = fm.descent;
                fmCacheCount++;

                spanEndCache[spanEndCacheCount] = spanEnd;
                spanEndCacheCount++;
            }

            nGetWidths(b.mNativePtr, widths);
            int breakCount = nComputeLineBreaks(b.mNativePtr, lineBreaks, lineBreaks.breaks,
                    lineBreaks.widths, lineBreaks.ascents, lineBreaks.descents, lineBreaks.flags,
                    lineBreaks.breaks.length);

            final int[] breaks = lineBreaks.breaks;
            final float[] lineWidths = lineBreaks.widths;
            final float[] ascents = lineBreaks.ascents;
            final float[] descents = lineBreaks.descents;
            final int[] flags = lineBreaks.flags;

            final int remainingLineCount = mMaximumVisibleLineCount - mLineCount;
            final boolean ellipsisMayBeApplied = ellipsize != null
                    && (ellipsize == TextUtils.TruncateAt.END
                        || (mMaximumVisibleLineCount == 1
                                && ellipsize != TextUtils.TruncateAt.MARQUEE));
            if (0 < remainingLineCount && remainingLineCount < breakCount
                    && ellipsisMayBeApplied) {
                // Calculate width and flag.
                float width = 0;
                int flag = 0; // XXX May need to also have starting hyphen edit
                for (int i = remainingLineCount - 1; i < breakCount; i++) {
                    if (i == breakCount - 1) {
                        width += lineWidths[i];
                    } else {
                        for (int j = (i == 0 ? 0 : breaks[i - 1]); j < breaks[i]; j++) {
                            width += widths[j];
                        }
                    }
                    flag |= flags[i] & TAB_MASK;
                }
                // Treat the last line and overflowed lines as a single line.
                breaks[remainingLineCount - 1] = breaks[breakCount - 1];
                lineWidths[remainingLineCount - 1] = width;
                flags[remainingLineCount - 1] = flag;

                breakCount = remainingLineCount;
            }

            // here is the offset of the starting character of the line we are currently measuring
            int here = paraStart;

            int fmTop = 0, fmBottom = 0, fmAscent = 0, fmDescent = 0;
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

                    final int ascent = fallbackLineSpacing
                            ? Math.min(fmAscent, (int) Math.round(ascents[breakIndex]))
                            : fmAscent;
                    final int descent = fallbackLineSpacing
                            ? Math.max(fmDescent, (int) Math.round(descents[breakIndex]))
                            : fmDescent;
                    v = out(source, here, endPos,
                            ascent, descent, fmTop, fmBottom,
                            v, spacingmult, spacingadd, chooseHt, chooseHtv, fm, flags[breakIndex],
                            needMultiply, chdirs, dir, easy, bufEnd, includepad, trackpad,
                            addLastLineSpacing, chs, widths, paraStart, ellipsize,
                            ellipsizedWidth, lineWidths[breakIndex], paint, moreChars);

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

            if (paraEnd == bufEnd)
                break;
        }

        if ((bufEnd == bufStart || source.charAt(bufEnd - 1) == CHAR_NEW_LINE) &&
                mLineCount < mMaximumVisibleLineCount) {
            measured.setPara(source, bufEnd, bufEnd, textDir, b);

            paint.getFontMetricsInt(fm);

            v = out(source,
                    bufEnd, bufEnd, fm.ascent, fm.descent,
                    fm.top, fm.bottom,
                    v,
                    spacingmult, spacingadd, null,
                    null, fm, 0,
                    needMultiply, measured.mLevels, measured.mDir, measured.mEasy, bufEnd,
                    includepad, trackpad, addLastLineSpacing, null,
                    null, bufStart, ellipsize,
                    ellipsizedWidth, 0, paint, false);
        }
    }

    // The parameters that are not changed in the method are marked as final to make the code
    // easier to understand.
    private int out(final CharSequence text, final int start, final int end, int above, int below,
            int top, int bottom, int v, final float spacingmult, final float spacingadd,
            final LineHeightSpan[] chooseHt, final int[] chooseHtv, final Paint.FontMetricsInt fm,
            final int flags, final boolean needMultiply, final byte[] chdirs, final int dir,
            final boolean easy, final int bufEnd, final boolean includePad, final boolean trackPad,
            final boolean addLastLineLineSpacing, final char[] chs, final float[] widths,
            final int widthStart, final TextUtils.TruncateAt ellipsize, final float ellipsisWidth,
            final float textWidth, final TextPaint paint, final boolean moreChars) {
        final int j = mLineCount;
        final int off = j * mColumns;
        final int want = off + mColumns + TOP;
        int[] lines = mLines;

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

        lines[off + START] = start;
        lines[off + TOP] = v;

        // Information about hyphenation, tabs, and directions are needed for determining
        // ellipsization, so the values should be assigned before ellipsization.

        // TODO: could move TAB to share same column as HYPHEN, simplifying this code and gaining
        // one bit for start field
        lines[off + TAB] |= flags & TAB_MASK;
        lines[off + HYPHEN] = flags;

        lines[off + DIR] |= dir << DIR_SHIFT;
        // easy means all chars < the first RTL, so no emoji, no nothing
        // XXX a run with no text or all spaces is easy but might be an empty
        // RTL paragraph.  Make sure easy is false if this is the case.
        if (easy) {
            mLineDirections[j] = DIRS_ALL_LEFT_TO_RIGHT;
        } else {
            mLineDirections[j] = AndroidBidi.directions(dir, chdirs, start - widthStart, chs,
                    start - widthStart, end - start);
        }

        final boolean firstLine = (j == 0);
        final boolean currentLineIsTheLastVisibleOne = (j + 1 == mMaximumVisibleLineCount);

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
                calculateEllipsis(text, start, end, widths, widthStart,
                        ellipsisWidth - getTotalInsets(j), ellipsize, j,
                        textWidth, paint, forceEllipsis, dir);
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

        if (firstLine) {
            if (trackPad) {
                mTopPadding = top - above;
            }

            if (includePad) {
                above = top;
            }
        }

        if (lastLine) {
            if (trackPad) {
                mBottomPadding = bottom - below;
            }

            if (includePad) {
                below = bottom;
            }
        }

        final int extra;
        if (needMultiply && (addLastLineLineSpacing || !lastLine)) {
            final double ex = (below - above) * (spacingmult - 1) + spacingadd;
            if (ex >= 0) {
                extra = (int)(ex + EXTRA_ROUNDING);
            } else {
                extra = -(int)(-ex + EXTRA_ROUNDING);
            }
        } else {
            extra = 0;
        }

        lines[off + DESCENT] = below + extra;
        lines[off + EXTRA] = extra;

        // special case for non-ellipsized last visible line when maxLines is set
        // store the height as if it was ellipsized
        if (!mEllipsized && currentLineIsTheLastVisibleOne) {
            // below calculation as if it was the last line
            final int maxLineBelow = includePad ? bottom : below;
            // similar to the calculation of v below, without the extra.
            mMaxLineHeight = v + (maxLineBelow - above);
        }

        v += (below - above) + extra;
        lines[off + mColumns + START] = end;
        lines[off + mColumns + TOP] = v;

        mLineCount++;
        return v;
    }

    private void calculateEllipsis(CharSequence text, int lineStart, int lineEnd, float[] widths,
            int widthStart, float avail, TextUtils.TruncateAt where, int line, float textWidth,
            TextPaint paint, boolean forceEllipsis, int dir) {
        if (textWidth <= avail && !forceEllipsis) {
            // Everything fits!
            mLines[mColumns * line + ELLIPSIS_START] = 0;
            mLines[mColumns * line + ELLIPSIS_COUNT] = 0;
            return;
        }

        float tempAvail = avail;
        int numberOfTries = 0;
        boolean lineFits = false;
        mWorkPaint.set(paint);
        do {
            final float ellipsizedWidth = guessEllipsis(text, lineStart, lineEnd, widths,
                    widthStart, tempAvail, where, line, textWidth, mWorkPaint, forceEllipsis, dir);
            if (ellipsizedWidth <= avail) {
                lineFits = true;
            } else {
                numberOfTries++;
                if (numberOfTries > 10) {
                    // If the text still doesn't fit after ten tries, assume it will never fit and
                    // ellipsize it all.
                    mLines[mColumns * line + ELLIPSIS_START] = 0;
                    mLines[mColumns * line + ELLIPSIS_COUNT] = lineEnd - lineStart;
                    lineFits = true;
                } else {
                    // Some side effect of ellipsization has caused the text to go over the
                    // available width. Let's make the available width shorter by exactly that
                    // amount and retry.
                    tempAvail -= ellipsizedWidth - avail;
                }
            }
        } while (!lineFits);
        mEllipsized = true;
    }

    // Returns the width of the ellipsized line which in some rare cases can actually be larger
    // than 'avail' (due to kerning or other context-based effect of replacement of text by
    // ellipsis). If all the line needs to ellipsized away, or it's an invalud hyphenation mode,
    // returns 0 so the caller can stop iterating.
    //
    // This method temporarily modifies the TextPaint passed to it, so the TextPaint passed to it
    // should not be accessed while the method is running.
    private float guessEllipsis(CharSequence text, int lineStart, int lineEnd, float[] widths,
            int widthStart, float avail, TextUtils.TruncateAt where, int line, float textWidth,
            TextPaint paint, boolean forceEllipsis, int dir) {
        final int savedHyphenEdit = paint.getHyphenEdit();
        paint.setHyphenEdit(0);
        final float ellipsisWidth = paint.measureText(TextUtils.getEllipsisString(where));
        final int ellipsisStart;
        final int ellipsisCount;
        final int len = lineEnd - lineStart;
        final int offset = lineStart - widthStart;

        int hyphen = getHyphen(line);
        // We only support start ellipsis on a single line
        if (where == TextUtils.TruncateAt.START) {
            if (mMaximumVisibleLineCount == 1) {
                float sum = 0;
                int i;

                for (i = len; i > 0; i--) {
                    final float w = widths[i - 1 + offset];
                    if (w + sum + ellipsisWidth > avail) {
                        while (i < len && widths[i + offset] == 0.0f) {
                            i++;
                        }
                        break;
                    }

                    sum += w;
                }

                ellipsisStart = 0;
                ellipsisCount = i;
                // Strip the potential hyphenation at beginning of line.
                hyphen &= ~Paint.HYPHENEDIT_MASK_START_OF_LINE;
            } else {
                ellipsisStart = 0;
                ellipsisCount = 0;
                if (Log.isLoggable(TAG, Log.WARN)) {
                    Log.w(TAG, "Start ellipsis only supported with one line");
                }
            }
        } else if (where == TextUtils.TruncateAt.END || where == TextUtils.TruncateAt.MARQUEE ||
                where == TextUtils.TruncateAt.END_SMALL) {
            float sum = 0;
            int i;

            for (i = 0; i < len; i++) {
                final float w = widths[i + offset];

                if (w + sum + ellipsisWidth > avail) {
                    break;
                }

                sum += w;
            }

            if (forceEllipsis && i == len && len > 0) {
                ellipsisStart = len - 1;
                ellipsisCount = 1;
            } else {
                ellipsisStart = i;
                ellipsisCount = len - i;
            }
            // Strip the potential hyphenation at end of line.
            hyphen &= ~Paint.HYPHENEDIT_MASK_END_OF_LINE;
        } else { // where = TextUtils.TruncateAt.MIDDLE
            // We only support middle ellipsis on a single line.
            if (mMaximumVisibleLineCount == 1) {
                float lsum = 0, rsum = 0;
                int left = 0, right = len;

                final float ravail = (avail - ellipsisWidth) / 2;
                for (right = len; right > 0; right--) {
                    final float w = widths[right - 1 + offset];

                    if (w + rsum > ravail) {
                        while (right < len && widths[right + offset] == 0.0f) {
                            right++;
                        }
                        break;
                    }
                    rsum += w;
                }

                final float lavail = avail - ellipsisWidth - rsum;
                for (left = 0; left < right; left++) {
                    final float w = widths[left + offset];

                    if (w + lsum > lavail) {
                        break;
                    }

                    lsum += w;
                }

                ellipsisStart = left;
                ellipsisCount = right - left;
            } else {
                ellipsisStart = 0;
                ellipsisCount = 0;
                if (Log.isLoggable(TAG, Log.WARN)) {
                    Log.w(TAG, "Middle ellipsis only supported with one line");
                }
            }
        }
        mLines[mColumns * line + ELLIPSIS_START] = ellipsisStart;
        mLines[mColumns * line + ELLIPSIS_COUNT] = ellipsisCount;

        if (ellipsisStart == 0 && (ellipsisCount == 0 || ellipsisCount == len)) {
            // Unsupported ellipsization mode or all text is ellipsized away. Return 0.
            return 0.0f;
        }

        final boolean isSpanned = text instanceof Spanned;
        final Ellipsizer ellipsizedText = isSpanned
                        ? new SpannedEllipsizer(text)
                        : new Ellipsizer(text);
        ellipsizedText.mLayout = this;
        ellipsizedText.mMethod = where;

        final boolean hasTabs = getLineContainsTab(line);
        final TabStops tabStops;
        if (hasTabs && isSpanned) {
            final TabStopSpan[] tabs = getParagraphSpans((Spanned) ellipsizedText, lineStart,
                    lineEnd, TabStopSpan.class);
            if (tabs.length == 0) {
                tabStops = null;
            } else {
                tabStops = new TabStops(TAB_INCREMENT, tabs);
            }
        } else {
            tabStops = null;
        }
        paint.setHyphenEdit(hyphen);
        final TextLine textline = TextLine.obtain();
        textline.set(paint, ellipsizedText, lineStart, lineEnd, dir, getLineDirections(line),
                hasTabs, tabStops);
        // Since TextLine.metric() returns negative values for RTL text, multiplication by dir
        // converts it to an actual width. Note that we don't want to use the absolute value,
        // since we may actually have glyphs with negative advances, which by definition always
        // fit.
        final float ellipsizedWidth = textline.metrics(null) * dir;
        TextLine.recycle(textline);
        paint.setHyphenEdit(savedHyphenEdit);
        return ellipsizedWidth;
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

    /**
     * @hide
     */
    @Override
    public int getHyphen(int line) {
        return mLines[mColumns * line + HYPHEN] & HYPHEN_MASK;
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
    public int getEllipsizedWidth() {
        return mEllipsizedWidth;
    }

    /**
     * Return the total height of this layout.
     *
     * @param cap if true and max lines is set, returns the height of the layout at the max lines.
     *
     * @hide
     */
    public int getHeight(boolean cap) {
        if (cap && mLineCount >= mMaximumVisibleLineCount && mMaxLineHeight == -1 &&
                Log.isLoggable(TAG, Log.WARN)) {
            Log.w(TAG, "maxLineHeight should not be -1. "
                    + " maxLines:" + mMaximumVisibleLineCount
                    + " lineCount:" + mLineCount);
        }

        return cap && mLineCount >= mMaximumVisibleLineCount && mMaxLineHeight != -1 ?
                mMaxLineHeight : super.getHeight();
    }

    private static native long nNewBuilder();
    private static native void nFreeBuilder(long nativePtr);
    private static native void nFinishBuilder(long nativePtr);

    // Set up paragraph text and settings; done as one big method to minimize jni crossings
    private static native void nSetupParagraph(
            /* non zero */ long nativePtr, @NonNull char[] text, @IntRange(from = 0) int length,
            @FloatRange(from = 0.0f) float firstWidth, @IntRange(from = 0) int firstWidthLineCount,
            @FloatRange(from = 0.0f) float restWidth, @Nullable int[] variableTabStops,
            int defaultTabStop, @BreakStrategy int breakStrategy,
            @HyphenationFrequency int hyphenationFrequency, boolean isJustified,
            @Nullable int[] indents, @Nullable int[] leftPaddings, @Nullable int[] rightPaddings,
            @IntRange(from = 0) int indentsOffset);

    private static native float nAddStyleRun(
            /* non-zero */ long nativePtr, /* non-zero */ long nativePaint,
            @IntRange(from = 0) int start, @IntRange(from = 0) int end, boolean isRtl,
            @Nullable String languageTags, @Nullable long[] hyphenators);

    private static native void nAddMeasuredRun(/* non-zero */ long nativePtr,
            @IntRange(from = 0) int start, @IntRange(from = 0) int end, @NonNull float[] widths,
            @Nullable String languageTags, @Nullable long[] hyphenators);

    private static native void nAddReplacementRun(/* non-zero */ long nativePtr,
            @IntRange(from = 0) int start, @IntRange(from = 0) int end,
            @FloatRange(from = 0.0f) float width, @Nullable String languageTags,
            @Nullable long[] hyphenators);

    private static native void nGetWidths(long nativePtr, float[] widths);

    // populates LineBreaks and returns the number of breaks found
    //
    // the arrays inside the LineBreaks objects are passed in as well
    // to reduce the number of JNI calls in the common case where the
    // arrays do not have to be resized
    private static native int nComputeLineBreaks(long nativePtr, LineBreaks recycle,
            int[] recycleBreaks, float[] recycleWidths, float[] recycleAscents,
            float[] recycleDescents, int[] recycleFlags, int recycleLength);

    private int mLineCount;
    private int mTopPadding, mBottomPadding;
    private int mColumns;
    private int mEllipsizedWidth;

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

    private TextPaint mWorkPaint = new TextPaint();

    private static final int COLUMNS_NORMAL = 5;
    private static final int COLUMNS_ELLIPSIZE = 7;
    private static final int START = 0;
    private static final int DIR = START;
    private static final int TAB = START;
    private static final int TOP = 1;
    private static final int DESCENT = 2;
    private static final int EXTRA = 3;
    private static final int HYPHEN = 4;
    private static final int ELLIPSIS_START = 5;
    private static final int ELLIPSIS_COUNT = 6;

    private int[] mLines;
    private Directions[] mLineDirections;
    private int mMaximumVisibleLineCount = Integer.MAX_VALUE;

    private static final int START_MASK = 0x1FFFFFFF;
    private static final int DIR_SHIFT  = 30;
    private static final int TAB_MASK   = 0x20000000;
    private static final int HYPHEN_MASK = 0xFF;

    private static final int TAB_INCREMENT = 20; // same as Layout, but that's private

    private static final char CHAR_NEW_LINE = '\n';

    private static final double EXTRA_ROUNDING = 0.5;

    private static final int DEFAULT_MAX_LINE_HEIGHT = -1;

    // This is used to return three arrays from a single JNI call when
    // performing line breaking
    /*package*/ static class LineBreaks {
        private static final int INITIAL_SIZE = 16;
        public int[] breaks = new int[INITIAL_SIZE];
        public float[] widths = new float[INITIAL_SIZE];
        public float[] ascents = new float[INITIAL_SIZE];
        public float[] descents = new float[INITIAL_SIZE];
        public int[] flags = new int[INITIAL_SIZE]; // hasTab
        // breaks, widths, and flags should all have the same length
    }

    @Nullable private int[] mLeftIndents;
    @Nullable private int[] mRightIndents;
    @Nullable private int[] mLeftPaddings;
    @Nullable private int[] mRightPaddings;
}
