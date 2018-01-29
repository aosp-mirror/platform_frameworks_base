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

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;

/**
 * A text which has already been measured.
 */
public class MeasuredText implements Spanned {
    private static final char LINE_FEED = '\n';

    // The original text.
    private final @NonNull CharSequence mText;

    // The inclusive start offset of the measuring target.
    private final @IntRange(from = 0) int mStart;

    // The exclusive end offset of the measuring target.
    private final @IntRange(from = 0) int mEnd;

    // The TextPaint used for measurement.
    private final @NonNull TextPaint mPaint;

    // The requested text direction.
    private final @NonNull TextDirectionHeuristic mTextDir;

    // The measured paragraph texts.
    private final @NonNull MeasuredParagraph[] mMeasuredParagraphs;

    // The sorted paragraph end offsets.
    private final @NonNull int[] mParagraphBreakPoints;

    // The break strategy for this measured text.
    private final @Layout.BreakStrategy int mBreakStrategy;

    // The hyphenation frequency for this measured text.
    private final @Layout.HyphenationFrequency int mHyphenationFrequency;

    /**
     * A Builder for MeasuredText
     */
    public static final class Builder {
        // Mandatory parameters.
        private final @NonNull CharSequence mText;
        private final @NonNull TextPaint mPaint;

        // Members to be updated by setters.
        private @IntRange(from = 0) int mStart;
        private @IntRange(from = 0) int mEnd;
        private TextDirectionHeuristic mTextDir = TextDirectionHeuristics.FIRSTSTRONG_LTR;
        private @Layout.BreakStrategy int mBreakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY;
        private @Layout.HyphenationFrequency int mHyphenationFrequency =
                Layout.HYPHENATION_FREQUENCY_NORMAL;


        /**
         * Builder constructor
         *
         * @param text The text to be measured.
         * @param paint The paint to be used for drawing.
         */
        public Builder(@NonNull CharSequence text, @NonNull TextPaint paint) {
            Preconditions.checkNotNull(text);
            Preconditions.checkNotNull(paint);

            mText = text;
            mPaint = paint;
            mStart = 0;
            mEnd = text.length();
        }

        /**
         * Set the range of measuring target.
         *
         * @param start The measuring target start offset in the text.
         * @param end The measuring target end offset in the text.
         */
        public @NonNull Builder setRange(@IntRange(from = 0) int start,
                                         @IntRange(from = 0) int end) {
            Preconditions.checkArgumentInRange(start, 0, mText.length(), "start");
            Preconditions.checkArgumentInRange(end, 0, mText.length(), "end");
            Preconditions.checkArgument(start <= end, "The range is reversed.");

            mStart = start;
            mEnd = end;
            return this;
        }

        /**
         * Set the text direction heuristic
         *
         * The default value is {@link TextDirectionHeuristics#FIRSTSTRONG_LTR}.
         *
         * @param textDir The text direction heuristic for resolving bidi behavior.
         * @return this builder, useful for chaining.
         */
        public @NonNull Builder setTextDirection(@NonNull TextDirectionHeuristic textDir) {
            Preconditions.checkNotNull(textDir);
            mTextDir = textDir;
            return this;
        }

        /**
         * Set the break strategy
         *
         * The default value is {@link Layout#BREAK_STRATEGY_HIGH_QUALITY}.
         *
         * @param breakStrategy The break strategy.
         * @return this builder, useful for chaining.
         */
        public @NonNull Builder setBreakStrategy(@Layout.BreakStrategy int breakStrategy) {
            mBreakStrategy = breakStrategy;
            return this;
        }

        /**
         * Set the hyphenation frequency
         *
         * The default value is {@link Layout#HYPHENATION_FREQUENCY_NORMAL}.
         *
         * @param hyphenationFrequency The hyphenation frequency.
         * @return this builder, useful for chaining.
         */
        public @NonNull Builder setHyphenationFrequency(
                @Layout.HyphenationFrequency int hyphenationFrequency) {
            mHyphenationFrequency = hyphenationFrequency;
            return this;
        }

        /**
         * Build the measured text
         *
         * @return the measured text.
         */
        public @NonNull MeasuredText build() {
            return build(true /* build full layout result */);
        }

        /** @hide */
        public @NonNull MeasuredText build(boolean computeLayout) {
            final boolean needHyphenation = mBreakStrategy != Layout.BREAK_STRATEGY_SIMPLE
                    && mHyphenationFrequency != Layout.HYPHENATION_FREQUENCY_NONE;

            final IntArray paragraphEnds = new IntArray();
            final ArrayList<MeasuredParagraph> measuredTexts = new ArrayList<>();

            int paraEnd = 0;
            for (int paraStart = mStart; paraStart < mEnd; paraStart = paraEnd) {
                paraEnd = TextUtils.indexOf(mText, LINE_FEED, paraStart, mEnd);
                if (paraEnd < 0) {
                    // No LINE_FEED(U+000A) character found. Use end of the text as the paragraph
                    // end.
                    paraEnd = mEnd;
                } else {
                    paraEnd++;  // Includes LINE_FEED(U+000A) to the prev paragraph.
                }

                paragraphEnds.add(paraEnd);
                measuredTexts.add(MeasuredParagraph.buildForStaticLayout(
                        mPaint, mText, paraStart, paraEnd, mTextDir, needHyphenation,
                        computeLayout, null /* no recycle */));
            }

            return new MeasuredText(mText, mStart, mEnd, mPaint, mTextDir, mBreakStrategy,
                                    mHyphenationFrequency, measuredTexts.toArray(
                                            new MeasuredParagraph[measuredTexts.size()]),
                                    paragraphEnds.toArray());
        }
    };

    // Use MeasuredText.Builder instead.
    private MeasuredText(@NonNull CharSequence text,
                         @IntRange(from = 0) int start,
                         @IntRange(from = 0) int end,
                         @NonNull TextPaint paint,
                         @NonNull TextDirectionHeuristic textDir,
                         @Layout.BreakStrategy int breakStrategy,
                         @Layout.HyphenationFrequency int frequency,
                         @NonNull MeasuredParagraph[] measuredTexts,
                         @NonNull int[] paragraphBreakPoints) {
        mText = text;
        mStart = start;
        mEnd = end;
        // Copy the paint so that we can keep the reference of typeface in native layout result.
        mPaint = new TextPaint(paint);
        mMeasuredParagraphs = measuredTexts;
        mParagraphBreakPoints = paragraphBreakPoints;
        mTextDir = textDir;
        mBreakStrategy = breakStrategy;
        mHyphenationFrequency = frequency;
    }

    /**
     * Return the underlying text.
     */
    public @NonNull CharSequence getText() {
        return mText;
    }

    /**
     * Returns the inclusive start offset of measured region.
     */
    public @IntRange(from = 0) int getStart() {
        return mStart;
    }

    /**
     * Returns the exclusive end offset of measured region.
     */
    public @IntRange(from = 0) int getEnd() {
        return mEnd;
    }

    /**
     * Returns the text direction associated with char sequence.
     */
    public @NonNull TextDirectionHeuristic getTextDir() {
        return mTextDir;
    }

    /**
     * Returns the paint used to measure this text.
     */
    public @NonNull TextPaint getPaint() {
        return mPaint;
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
     * Returns the break strategy for this text.
     */
    public @Layout.BreakStrategy int getBreakStrategy() {
        return mBreakStrategy;
    }

    /**
     * Returns the hyphenation frequency for this text.
     */
    public @Layout.HyphenationFrequency int getHyphenationFrequency() {
        return mHyphenationFrequency;
    }

    /**
     * Returns true if the given TextPaint gives the same result of text layout for this text.
     * @hide
     */
    public boolean canUseMeasuredResult(@NonNull TextPaint paint) {
        return mPaint.getTextSize() == paint.getTextSize()
            && mPaint.getTextSkewX() == paint.getTextSkewX()
            && mPaint.getTextScaleX() == paint.getTextScaleX()
            && mPaint.getLetterSpacing() == paint.getLetterSpacing()
            && mPaint.getWordSpacing() == paint.getWordSpacing()
            && mPaint.getFlags() == paint.getFlags()  // Maybe not all flag affects text layout.
            && mPaint.getTextLocales() == paint.getTextLocales()  // need to be equals?
            && mPaint.getFontVariationSettings() == paint.getFontVariationSettings()
            && mPaint.getTypeface() == paint.getTypeface()
            && TextUtils.equals(mPaint.getFontFeatureSettings(), paint.getFontFeatureSettings());
    }

    /** @hide */
    public int findParaIndex(@IntRange(from = 0) int pos) {
        // TODO: Maybe good to remove paragraph concept from MeasuredText and add substring layout
        //       support to StaticLayout.
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
     * Returns the size of native MeasuredText memory usage
     *
     * Note that this may not be aculate. Must be used only for testing purposes.
     * @hide
     */
    public int getMemoryUsage() {
        int r = 0;
        for (int i = 0; i < getParagraphCount(); ++i) {
            r += getMeasuredParagraph(i).getMemoryUsage();
        }
        return r;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Spanned overrides
    //
    // Just proxy for underlying mText if appropriate.

    @Override
    public <T> T[] getSpans(int start, int end, Class<T> type) {
        if (mText instanceof Spanned) {
            return ((Spanned) mText).getSpans(start, end, type);
        } else {
            return ArrayUtils.emptyArray(type);
        }
    }

    @Override
    public int getSpanStart(Object tag) {
        if (mText instanceof Spanned) {
            return ((Spanned) mText).getSpanStart(tag);
        } else {
            return -1;
        }
    }

    @Override
    public int getSpanEnd(Object tag) {
        if (mText instanceof Spanned) {
            return ((Spanned) mText).getSpanEnd(tag);
        } else {
            return -1;
        }
    }

    @Override
    public int getSpanFlags(Object tag) {
        if (mText instanceof Spanned) {
            return ((Spanned) mText).getSpanFlags(tag);
        } else {
            return 0;
        }
    }

    @Override
    public int nextSpanTransition(int start, int limit, Class type) {
        if (mText instanceof Spanned) {
            return ((Spanned) mText).nextSpanTransition(start, limit, type);
        } else {
            return mText.length();
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // CharSequence overrides.
    //
    // Just proxy for underlying mText.

    @Override
    public int length() {
        return mText.length();
    }

    @Override
    public char charAt(int index) {
        // TODO: Should this be index + mStart ?
        return mText.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        // TODO: return MeasuredText.
        // TODO: Should this be index + mStart, end + mStart ?
        return mText.subSequence(start, end);
    }

    @Override
    public String toString() {
        return mText.toString();
    }
}
