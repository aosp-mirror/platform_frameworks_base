/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.util.IntArray;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;

/**
 * A text which has the character metrics data
 *
 * This text holds a part of the text layout result. You can accelerate
 * {@link android.widget.TextView} or {@link StaticLayout} by using this text.
 *
 * <pre>
 * Example of background measurement.
 * <code>
 *  void asyncSetText(final TextView textView, final String expensiveLongString, Handler handler) {
 *      final PrecomputedText.Params params = textView.getTextParams();
 *      handler.post(() -> {
 *          final PrecomputedText precomputedText
 *                  = PrecomputedText.create(expensiveLongString, params);
 *          textView.post(() -> {
 *              textView.setPrecomputedTextOrThrow(precomputedText);
 *          });
 *      });
 *  }
 * </code>
 * </pre>
 *
 * Note that the {@link PrecomputedText} created from different parameters of the target
 * {@link android.widget.TextView} will be rejected internally and compute the text layout again
 * with the current {@link android.widget.TextView} parameters.
 */
public class PrecomputedText {
    private static final char LINE_FEED = '\n';

    /**
     * The information required for building {@link PrecomputedText}.
     *
     * Contains information required for precomputing text measurement metadata, so it can be done
     * in isolation of a {@link android.widget.TextView} or {@link StaticLayout}, when final layout
     * constraints are not known.
     */
    public static class Params {
        // The TextPaint used for measurement.
        private final @NonNull TextPaint mPaint;

        // The requested text direction.
        private final @NonNull TextDirectionHeuristic mTextDir;

        // The break strategy for this measured text.
        private final @Layout.BreakStrategy int mBreakStrategy;

        // The hyphenation frequency for this measured text.
        private final @Layout.HyphenationFrequency int mHyphenationFrequency;

        /**
         * A builder for creating {@link Params}.
         */
        public static class Builder {
            // The TextPaint used for measurement.
            private final @NonNull TextPaint mPaint;

            // The requested text direction.
            private TextDirectionHeuristic mTextDir = TextDirectionHeuristics.FIRSTSTRONG_LTR;

            // The break strategy for this measured text.
            private @Layout.BreakStrategy int mBreakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY;

            // The hyphenation frequency for this measured text.
            private @Layout.HyphenationFrequency int mHyphenationFrequency =
                    Layout.HYPHENATION_FREQUENCY_NORMAL;

            /**
             * Builder constructor
             *
             * @param paint The paint to be used for drawing
             */
            public Builder(@NonNull TextPaint paint) {
                mPaint = paint;
            }

            /**
             * Set the line break strategy
             *
             * The default value is {@link Layout#BREAK_STRATEGY_HIGH_QUALITY}.
             *
             * @param strategy The break strategy
             * @return this builder, useful for chaining
             */
            public Builder setBreakStrategy(@Layout.BreakStrategy int strategy) {
                mBreakStrategy = strategy;
                return this;
            }

            /**
             * Set the hyphenation frequency
             *
             * The default value is {@link Layout#HYPHENATION_FREQUENCY_NORMAL}.
             *
             * @param frequency The hyphenation frequency
             * @return this builder, useful for chaining
             */
            public Builder setHyphenationFrequency(@Layout.HyphenationFrequency int frequency) {
                mHyphenationFrequency = frequency;
                return this;
            }

            /**
             * Set the text direction heuristic
             *
             * The default value is {@link TextDirectionHeuristics#FIRSTSTRONG_LTR}.
             *
             * @param textDir The text direction heuristic for resolving bidi behavior
             * @return this builder, useful for chaining
             */
            public Builder setTextDirection(@NonNull TextDirectionHeuristic textDir) {
                mTextDir = textDir;
                return this;
            }

            /**
             * Build the {@link Params}
             *
             * @return the layout parameter
             */
            public @NonNull Params build() {
                return new Params(mPaint, mTextDir, mBreakStrategy, mHyphenationFrequency);
            }
        }

        // Use Builder instead.
        /** @hide */
        public Params(@NonNull TextPaint paint, @NonNull TextDirectionHeuristic textDir,
                @Layout.BreakStrategy int strategy, @Layout.HyphenationFrequency int frequency) {
            mPaint = paint;
            mTextDir = textDir;
            mBreakStrategy = strategy;
            mHyphenationFrequency = frequency;
        }

        /**
         * Returns the {@link TextPaint} for this text
         *
         * @return A {@link TextPaint}
         */
        public @NonNull TextPaint getTextPaint() {
            return mPaint;
        }

        /**
         * Returns the {@link TextDirectionHeuristic} for this text
         *
         * @return A {@link TextDirectionHeuristic}
         */
        public @NonNull TextDirectionHeuristic getTextDirection() {
            return mTextDir;
        }

        /**
         * Returns the break strategy for this text
         *
         * @return A line break strategy
         */
        public @Layout.BreakStrategy int getBreakStrategy() {
            return mBreakStrategy;
        }

        /**
         * Returns the hyphenation frequency for this text
         *
         * @return A hyphenation frequency
         */
        public @Layout.HyphenationFrequency int getHyphenationFrequency() {
            return mHyphenationFrequency;
        }

        private boolean sameTextMetricsInternal(@NonNull TextPaint paint,
                @NonNull TextDirectionHeuristic textDir, @Layout.BreakStrategy int strategy,
                @Layout.HyphenationFrequency int frequency) {
            return mTextDir == textDir
                && mBreakStrategy == strategy
                && mHyphenationFrequency == frequency
                && mPaint.equalsForTextMeasurement(paint);
        }

        /**
         * Check if the same text layout.
         *
         * @return true if this and the given param result in the same text layout
         */
        public boolean sameTextMetrics(@NonNull Params param) {
            return sameTextMetricsInternal(param.mPaint, param.mTextDir, param.mBreakStrategy,
                    param.mHyphenationFrequency);
        }
    };

    // The original text.
    private final @NonNull CharSequence mText;

    // The inclusive start offset of the measuring target.
    private final @IntRange(from = 0) int mStart;

    // The exclusive end offset of the measuring target.
    private final @IntRange(from = 0) int mEnd;

    private final @NonNull Params mParams;

    // The measured paragraph texts.
    private final @NonNull MeasuredParagraph[] mMeasuredParagraphs;

    // The sorted paragraph end offsets.
    private final @NonNull int[] mParagraphBreakPoints;

    /**
     * Create a new {@link PrecomputedText} which will pre-compute text measurement and glyph
     * positioning information.
     * <p>
     * This can be expensive, so computing this on a background thread before your text will be
     * presented can save work on the UI thread.
     * </p>
     *
     * @param text The text to be measured
     * @param param Parameters that define how text will be precomputed
     * @return A {@link PrecomputedText}
     */
    public static PrecomputedText create(@NonNull CharSequence text, @NonNull Params param) {
        return createInternal(text, param, 0, text.length(), true /* compute full Layout */);
    }


    /** @hide */
    public static PrecomputedText createWidthOnly(@NonNull CharSequence text, @NonNull Params param,
            @IntRange(from = 0) int start, @IntRange(from = 0) int end) {
        return createInternal(text, param, start, end, false /* compute width only */);
    }

    private static PrecomputedText createInternal(@NonNull CharSequence text, @NonNull Params param,
            @IntRange(from = 0) int start, @IntRange(from = 0) int end, boolean computeLayout) {
        Preconditions.checkNotNull(text);
        Preconditions.checkNotNull(param);
        final boolean needHyphenation = param.getBreakStrategy() != Layout.BREAK_STRATEGY_SIMPLE
                && param.getHyphenationFrequency() != Layout.HYPHENATION_FREQUENCY_NONE;

        final IntArray paragraphEnds = new IntArray();
        final ArrayList<MeasuredParagraph> measuredTexts = new ArrayList<>();

        int paraEnd = 0;
        for (int paraStart = start; paraStart < end; paraStart = paraEnd) {
            paraEnd = TextUtils.indexOf(text, LINE_FEED, paraStart, end);
            if (paraEnd < 0) {
                // No LINE_FEED(U+000A) character found. Use end of the text as the paragraph
                // end.
                paraEnd = end;
            } else {
                paraEnd++;  // Includes LINE_FEED(U+000A) to the prev paragraph.
            }

            paragraphEnds.add(paraEnd);
            measuredTexts.add(MeasuredParagraph.buildForStaticLayout(
                    param.getTextPaint(), text, paraStart, paraEnd, param.getTextDirection(),
                    needHyphenation, computeLayout, null /* no recycle */));
        }

        return new PrecomputedText(text, start, end, param,
                                measuredTexts.toArray(new MeasuredParagraph[measuredTexts.size()]),
                                paragraphEnds.toArray());
    }

    // Use PrecomputedText.create instead.
    private PrecomputedText(@NonNull CharSequence text, @IntRange(from = 0) int start,
            @IntRange(from = 0) int end, @NonNull Params param,
            @NonNull MeasuredParagraph[] measuredTexts, @NonNull int[] paragraphBreakPoints) {
        mText = TextUtils.stringOrSpannedString(text);
        mStart = start;
        mEnd = end;
        mParams = param;
        mMeasuredParagraphs = measuredTexts;
        mParagraphBreakPoints = paragraphBreakPoints;
    }

    /**
     * Return the underlying text.
     */
    public @NonNull CharSequence getText() {
        return mText;
    }

    /**
     * Returns the inclusive start offset of measured region.
     * @hide
     */
    public @IntRange(from = 0) int getStart() {
        return mStart;
    }

    /**
     * Returns the exclusive end offset of measured region.
     * @hide
     */
    public @IntRange(from = 0) int getEnd() {
        return mEnd;
    }

    /**
     * Returns the layout parameters used to measure this text.
     */
    public @NonNull Params getParams() {
        return mParams;
    }

    /**
     * Returns the length of the paragraph of this text.
     */
    public @IntRange(from = 0) int getParagraphCount() {
        return mParagraphBreakPoints.length;
    }

    /**
     * Returns the paragraph start offset of the text.
     */
    public @IntRange(from = 0) int getParagraphStart(@IntRange(from = 0) int paraIndex) {
        Preconditions.checkArgumentInRange(paraIndex, 0, getParagraphCount(), "paraIndex");
        return paraIndex == 0 ? mStart : mParagraphBreakPoints[paraIndex - 1];
    }

    /**
     * Returns the paragraph end offset of the text.
     */
    public @IntRange(from = 0) int getParagraphEnd(@IntRange(from = 0) int paraIndex) {
        Preconditions.checkArgumentInRange(paraIndex, 0, getParagraphCount(), "paraIndex");
        return mParagraphBreakPoints[paraIndex];
    }

    /** @hide */
    public @NonNull MeasuredParagraph getMeasuredParagraph(@IntRange(from = 0) int paraIndex) {
        return mMeasuredParagraphs[paraIndex];
    }

    /**
     * Returns true if the given TextPaint gives the same result of text layout for this text.
     * @hide
     */
    public boolean canUseMeasuredResult(@IntRange(from = 0) int start, @IntRange(from = 0) int end,
            @NonNull TextDirectionHeuristic textDir, @NonNull TextPaint paint,
            @Layout.BreakStrategy int strategy, @Layout.HyphenationFrequency int frequency) {
        final TextPaint mtPaint = mParams.getTextPaint();
        return mStart == start
            && mEnd == end
            && mParams.sameTextMetricsInternal(paint, textDir, strategy, frequency);
    }

    /** @hide */
    public int findParaIndex(@IntRange(from = 0) int pos) {
        // TODO: Maybe good to remove paragraph concept from PrecomputedText and add substring
        //       layout support to StaticLayout.
        for (int i = 0; i < mParagraphBreakPoints.length; ++i) {
            if (pos < mParagraphBreakPoints[i]) {
                return i;
            }
        }
        throw new IndexOutOfBoundsException(
            "pos must be less than " + mParagraphBreakPoints[mParagraphBreakPoints.length - 1]
            + ", gave " + pos);
    }

    /** @hide */
    public float getWidth(@IntRange(from = 0) int start, @IntRange(from = 0) int end) {
        final int paraIndex = findParaIndex(start);
        final int paraStart = getParagraphStart(paraIndex);
        final int paraEnd = getParagraphEnd(paraIndex);
        if (start < paraStart || paraEnd < end) {
            throw new RuntimeException("Cannot measured across the paragraph:"
                + "para: (" + paraStart + ", " + paraEnd + "), "
                + "request: (" + start + ", " + end + ")");
        }
        return getMeasuredParagraph(paraIndex).getWidth(start - paraStart, end - paraStart);
    }

    /**
     * Returns the size of native PrecomputedText memory usage
     *
     * Note that this is not guaranteed to be accurate. Must be used only for testing purposes.
     * @hide
     */
    public int getMemoryUsage() {
        int r = 0;
        for (int i = 0; i < getParagraphCount(); ++i) {
            r += getMeasuredParagraph(i).getMemoryUsage();
        }
        return r;
    }
}
