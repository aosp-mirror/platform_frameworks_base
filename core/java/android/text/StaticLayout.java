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

import android.annotation.Nullable;
import android.graphics.Paint;
import android.text.style.LeadingMarginSpan;
import android.text.style.LeadingMarginSpan.LeadingMarginSpan2;
import android.text.style.LineHeightSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.TabStopSpan;
import android.util.Log;
import android.util.Pools.SynchronizedPool;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.GrowingArrayUtils;

import java.nio.ByteBuffer;
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
     * Builder for static layouts. The builder is a newer pattern for constructing
     * StaticLayout objects and should be preferred over the constructors,
     * particularly to access newer features. To build a static layout, first
     * call {@link #obtain} with the required arguments (text, paint, and width),
     * then call setters for optional parameters, and finally {@link #build}
     * to build the StaticLayout object. Parameters not explicitly set will get
     * default values.
     */
    public final static class Builder {
        private Builder() {
            mNativePtr = nNewBuilder();
        }

        /**
         * Obtain a builder for constructing StaticLayout objects
         *
         * @param source The text to be laid out, optionally with spans
         * @param start The index of the start of the text
         * @param end The index + 1 of the end of the text
         * @param paint The base paint used for layout
         * @param width The width in pixels
         * @return a builder object used for constructing the StaticLayout
         */
        public static Builder obtain(CharSequence source, int start, int end, TextPaint paint,
                int width) {
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
            b.mSpacingMult = 1.0f;
            b.mSpacingAdd = 0.0f;
            b.mIncludePad = true;
            b.mEllipsizedWidth = width;
            b.mEllipsize = null;
            b.mMaxLines = Integer.MAX_VALUE;
            b.mBreakStrategy = Layout.BREAK_STRATEGY_SIMPLE;
            b.mHyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE;

            b.mMeasuredText = MeasuredText.obtain();
            return b;
        }

        private static void recycle(Builder b) {
            b.mPaint = null;
            b.mText = null;
            MeasuredText.recycle(b.mMeasuredText);
            b.mMeasuredText = null;
            b.mLeftIndents = null;
            b.mRightIndents = null;
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
        public Builder setText(CharSequence source, int start, int end) {
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
        public Builder setPaint(TextPaint paint) {
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
        public Builder setWidth(int width) {
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
        public Builder setAlignment(Alignment alignment) {
            mAlignment = alignment;
            return this;
        }

        /**
         * Set the text direction heuristic. The text direction heuristic is used to
         * resolve text direction based per-paragraph based on the input text. The default is
         * {@link TextDirectionHeuristics#FIRSTSTRONG_LTR}.
         *
         * @param textDir text direction heuristic for resolving BiDi behavior.
         * @return this builder, useful for chaining
         */
        public Builder setTextDirection(TextDirectionHeuristic textDir) {
            mTextDir = textDir;
            return this;
        }

        /**
         * Set line spacing parameters. The default is 0.0 for {@code spacingAdd}
         * and 1.0 for {@code spacingMult}.
         *
         * @param spacingAdd line spacing add
         * @param spacingMult line spacing multiplier
         * @return this builder, useful for chaining
         * @see android.widget.TextView#setLineSpacing
         */
        public Builder setLineSpacing(float spacingAdd, float spacingMult) {
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
        public Builder setIncludePad(boolean includePad) {
            mIncludePad = includePad;
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
        public Builder setEllipsizedWidth(int ellipsizedWidth) {
            mEllipsizedWidth = ellipsizedWidth;
            return this;
        }

        /**
         * Set ellipsizing on the layout. Causes words that are longer than the view
         * is wide, or exceeding the number of lines (see #setMaxLines) in the case
         * of {@link android.text.TextUtils.TruncateAt#END} or
         * {@link android.text.TextUtils.TruncateAt#MARQUEE}, to be ellipsized instead
         * of broken. The default is
         * {@code null}, indicating no ellipsis is to be applied.
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
         * Set maximum number of lines. This is particularly useful in the case of
         * ellipsizing, where it changes the layout of the last line. The default is
         * unlimited.
         *
         * @param maxLines maximum number of lines in the layout
         * @return this builder, useful for chaining
         * @see android.widget.TextView#setMaxLines
         */
        public Builder setMaxLines(int maxLines) {
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
        public Builder setBreakStrategy(@BreakStrategy int breakStrategy) {
            mBreakStrategy = breakStrategy;
            return this;
        }

        /**
         * Set hyphenation frequency, to control the amount of automatic hyphenation used. The
         * default is {@link Layout#HYPHENATION_FREQUENCY_NONE}.
         *
         * @param hyphenationFrequency hyphenation frequency for the paragraph
         * @return this builder, useful for chaining
         * @see android.widget.TextView#setHyphenationFrequency
         */
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
        public Builder setIndents(int[] leftIndents, int[] rightIndents) {
            mLeftIndents = leftIndents;
            mRightIndents = rightIndents;
            int leftLen = leftIndents == null ? 0 : leftIndents.length;
            int rightLen = rightIndents == null ? 0 : rightIndents.length;
            int[] indents = new int[Math.max(leftLen, rightLen)];
            for (int i = 0; i < indents.length; i++) {
                int leftMargin = i < leftLen ? leftIndents[i] : 0;
                int rightMargin = i < rightLen ? rightIndents[i] : 0;
                indents[i] = leftMargin + rightMargin;
            }
            nSetIndents(mNativePtr, indents);
            return this;
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
         *  - setLocale (this must be done at least for the first run, optional afterwards)
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

        private void setLocale(Locale locale) {
            if (!locale.equals(mLocale)) {
                nSetLocale(mNativePtr, locale.toLanguageTag(),
                        Hyphenator.get(locale).getNativePtr());
                mLocale = locale;
            }
        }

        /* package */ float addStyleRun(TextPaint paint, int start, int end, boolean isRtl) {
            return nAddStyleRun(mNativePtr, paint.getNativeInstance(), paint.mNativeTypeface,
                    start, end, isRtl);
        }

        /* package */ void addMeasuredRun(int start, int end, float[] widths) {
            nAddMeasuredRun(mNativePtr, start, end, widths);
        }

        /* package */ void addReplacementRun(int start, int end, float width) {
            nAddReplacementRun(mNativePtr, start, end, width);
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

        CharSequence mText;
        int mStart;
        int mEnd;
        TextPaint mPaint;
        int mWidth;
        Alignment mAlignment;
        TextDirectionHeuristic mTextDir;
        float mSpacingMult;
        float mSpacingAdd;
        boolean mIncludePad;
        int mEllipsizedWidth;
        TextUtils.TruncateAt mEllipsize;
        int mMaxLines;
        int mBreakStrategy;
        int mHyphenationFrequency;
        int[] mLeftIndents;
        int[] mRightIndents;

        Paint.FontMetricsInt mFontMetricsInt = new Paint.FontMetricsInt();

        // This will go away and be subsumed by native builder code
        MeasuredText mMeasuredText;

        Locale mLocale;

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

        generate(b, b.mIncludePad, b.mIncludePad);

        Builder.recycle(b);
    }

    /* package */ StaticLayout(CharSequence text) {
        super(text, null, 0, null, 0, 0);

        mColumns = COLUMNS_ELLIPSIZE;
        mLineDirections = ArrayUtils.newUnpaddedArray(Directions.class, 2 * mColumns);
        mLines = new int[mLineDirections.length];
    }

    private StaticLayout(Builder b) {
        super((b.mEllipsize == null)
                ? b.mText
                : (b.mText instanceof Spanned)
                    ? new SpannedEllipsizer(b.mText)
                    : new Ellipsizer(b.mText),
                b.mPaint, b.mWidth, b.mAlignment, b.mSpacingMult, b.mSpacingAdd);

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

        mLineDirections = ArrayUtils.newUnpaddedArray(Directions.class, 2 * mColumns);
        mLines = new int[mLineDirections.length];
        mMaximumVisibleLineCount = b.mMaxLines;

        mLeftIndents = b.mLeftIndents;
        mRightIndents = b.mRightIndents;

        generate(b, b.mIncludePad, b.mIncludePad);
    }

    /* package */ void generate(Builder b, boolean includepad, boolean trackpad) {
        CharSequence source = b.mText;
        int bufStart = b.mStart;
        int bufEnd = b.mEnd;
        TextPaint paint = b.mPaint;
        int outerWidth = b.mWidth;
        TextDirectionHeuristic textDir = b.mTextDir;
        float spacingmult = b.mSpacingMult;
        float spacingadd = b.mSpacingAdd;
        float ellipsizedWidth = b.mEllipsizedWidth;
        TextUtils.TruncateAt ellipsize = b.mEllipsize;
        LineBreaks lineBreaks = new LineBreaks();  // TODO: move to builder to avoid allocation costs
        // store span end locations
        int[] spanEndCache = new int[4];
        // store fontMetrics per span range
        // must be a multiple of 4 (and > 0) (store top, bottom, ascent, and descent per range)
        int[] fmCache = new int[4 * 4];
        b.setLocale(paint.getTextLocale());  // TODO: also respect LocaleSpan within the text

        mLineCount = 0;

        int v = 0;
        boolean needMultiply = (spacingmult != 1 || spacingadd != 0);

        Paint.FontMetricsInt fm = b.mFontMetricsInt;
        int[] chooseHtv = null;

        MeasuredText measured = b.mMeasuredText;

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

            nSetupParagraph(b.mNativePtr, chs, paraEnd - paraStart,
                    firstWidth, firstWidthLineCount, restWidth,
                    variableTabStops, TAB_INCREMENT, b.mBreakStrategy, b.mHyphenationFrequency);
            if (mLeftIndents != null || mRightIndents != null) {
                // TODO(raph) performance: it would be better to do this once per layout rather
                // than once per paragraph, but that would require a change to the native
                // interface.
                int leftLen = mLeftIndents == null ? 0 : mLeftIndents.length;
                int rightLen = mRightIndents == null ? 0 : mRightIndents.length;
                int indentsLen = Math.max(1, Math.min(leftLen, rightLen) - mLineCount);
                int[] indents = new int[indentsLen];
                for (int i = 0; i < indentsLen; i++) {
                    int leftMargin = mLeftIndents == null ? 0 :
                            mLeftIndents[Math.min(i + mLineCount, leftLen - 1)];
                    int rightMargin = mRightIndents == null ? 0 :
                            mRightIndents[Math.min(i + mLineCount, rightLen - 1)];
                    indents[i] = leftMargin + rightMargin;
                }
                nSetIndents(b.mNativePtr, indents);
            }

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
                    lineBreaks.widths, lineBreaks.flags, lineBreaks.breaks.length);

            int[] breaks = lineBreaks.breaks;
            float[] lineWidths = lineBreaks.widths;
            int[] flags = lineBreaks.flags;

            final int remainingLineCount = mMaximumVisibleLineCount - mLineCount;
            final boolean ellipsisMayBeApplied = ellipsize != null
                    && (ellipsize == TextUtils.TruncateAt.END
                        || (mMaximumVisibleLineCount == 1
                                && ellipsize != TextUtils.TruncateAt.MARQUEE));
            if (remainingLineCount > 0 && remainingLineCount < breakCount &&
                    ellipsisMayBeApplied) {
                // Treat the last line and overflowed lines as a single line.
                breaks[remainingLineCount - 1] = breaks[breakCount - 1];
                // Calculate width and flag.
                float width = 0;
                int flag = 0;
                for (int i = remainingLineCount - 1; i < breakCount; i++) {
                    width += lineWidths[i];
                    flag |= flags[i] & TAB_MASK;
                }
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

                    v = out(source, here, endPos,
                            fmAscent, fmDescent, fmTop, fmBottom,
                            v, spacingmult, spacingadd, chooseHt, chooseHtv, fm, flags[breakIndex],
                            needMultiply, chdirs, dir, easy, bufEnd, includepad, trackpad,
                            chs, widths, paraStart, ellipsize, ellipsizedWidth,
                            lineWidths[breakIndex], paint, moreChars);

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

                    if (mLineCount >= mMaximumVisibleLineCount) {
                        return;
                    }
                }
            }

            if (paraEnd == bufEnd)
                break;
        }

        if ((bufEnd == bufStart || source.charAt(bufEnd - 1) == CHAR_NEW_LINE) &&
                mLineCount < mMaximumVisibleLineCount) {
            // Log.e("text", "output last " + bufEnd);

            measured.setPara(source, bufEnd, bufEnd, textDir, b);

            paint.getFontMetricsInt(fm);

            v = out(source,
                    bufEnd, bufEnd, fm.ascent, fm.descent,
                    fm.top, fm.bottom,
                    v,
                    spacingmult, spacingadd, null,
                    null, fm, 0,
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
                      Paint.FontMetricsInt fm, int flags,
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

        // TODO: could move TAB to share same column as HYPHEN, simplifying this code and gaining
        // one bit for start field
        lines[off + TAB] |= flags & TAB_MASK;
        lines[off + HYPHEN] = flags;

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
                        TextUtils.ELLIPSIS_TWO_DOTS : TextUtils.ELLIPSIS_NORMAL, 0, 1);
        int ellipsisStart = 0;
        int ellipsisCount = 0;
        int len = lineEnd - lineStart;

        // We only support start ellipsis on a single line
        if (where == TextUtils.TruncateAt.START) {
            if (mMaximumVisibleLineCount == 1) {
                float sum = 0;
                int i;

                for (i = len; i > 0; i--) {
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
                for (right = len; right > 0; right--) {
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

    /**
     * @hide
     */
    @Override
    public int getHyphen(int line) {
        return mLines[mColumns * line + HYPHEN] & 0xff;
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

    private static native long nNewBuilder();
    private static native void nFreeBuilder(long nativePtr);
    private static native void nFinishBuilder(long nativePtr);

    /* package */ static native long nLoadHyphenator(ByteBuffer buf, int offset);

    private static native void nSetLocale(long nativePtr, String locale, long nativeHyphenator);

    private static native void nSetIndents(long nativePtr, int[] indents);

    // Set up paragraph text and settings; done as one big method to minimize jni crossings
    private static native void nSetupParagraph(long nativePtr, char[] text, int length,
            float firstWidth, int firstWidthLineCount, float restWidth,
            int[] variableTabStops, int defaultTabStop, int breakStrategy, int hyphenationFrequency);

    private static native float nAddStyleRun(long nativePtr, long nativePaint,
            long nativeTypeface, int start, int end, boolean isRtl);

    private static native void nAddMeasuredRun(long nativePtr,
            int start, int end, float[] widths);

    private static native void nAddReplacementRun(long nativePtr, int start, int end, float width);

    private static native void nGetWidths(long nativePtr, float[] widths);

    // populates LineBreaks and returns the number of breaks found
    //
    // the arrays inside the LineBreaks objects are passed in as well
    // to reduce the number of JNI calls in the common case where the
    // arrays do not have to be resized
    private static native int nComputeLineBreaks(long nativePtr, LineBreaks recycle,
            int[] recycleBreaks, float[] recycleWidths, int[] recycleFlags, int recycleLength);

    private int mLineCount;
    private int mTopPadding, mBottomPadding;
    private int mColumns;
    private int mEllipsizedWidth;

    private static final int COLUMNS_NORMAL = 4;
    private static final int COLUMNS_ELLIPSIZE = 6;
    private static final int START = 0;
    private static final int DIR = START;
    private static final int TAB = START;
    private static final int TOP = 1;
    private static final int DESCENT = 2;
    private static final int HYPHEN = 3;
    private static final int ELLIPSIS_START = 4;
    private static final int ELLIPSIS_COUNT = 5;

    private int[] mLines;
    private Directions[] mLineDirections;
    private int mMaximumVisibleLineCount = Integer.MAX_VALUE;

    private static final int START_MASK = 0x1FFFFFFF;
    private static final int DIR_SHIFT  = 30;
    private static final int TAB_MASK   = 0x20000000;

    private static final int TAB_INCREMENT = 20; // same as Layout, but that's private

    private static final char CHAR_NEW_LINE = '\n';

    private static final double EXTRA_ROUNDING = 0.5;

    // This is used to return three arrays from a single JNI call when
    // performing line breaking
    /*package*/ static class LineBreaks {
        private static final int INITIAL_SIZE = 16;
        public int[] breaks = new int[INITIAL_SIZE];
        public float[] widths = new float[INITIAL_SIZE];
        public int[] flags = new int[INITIAL_SIZE]; // hasTabOrEmoji
        // breaks, widths, and flags should all have the same length
    }

    private int[] mLeftIndents;
    private int[] mRightIndents;
}
