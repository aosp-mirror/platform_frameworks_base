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

import static com.android.text.flags.Flags.FLAG_NO_BREAK_NO_HYPHENATION_SPAN;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Px;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.text.LineBreakConfig;
import android.graphics.text.MeasuredText;
import android.icu.text.Bidi;
import android.text.AutoGrowArray.ByteArray;
import android.text.AutoGrowArray.FloatArray;
import android.text.AutoGrowArray.IntArray;
import android.text.Layout.Directions;
import android.text.style.LineBreakConfigSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;
import android.util.Pools.SynchronizedPool;

import java.util.Arrays;

/**
 * MeasuredParagraph provides text information for rendering purpose.
 *
 * The first motivation of this class is identify the text directions and retrieving individual
 * character widths. However retrieving character widths is slower than identifying text directions.
 * Thus, this class provides several builder methods for specific purposes.
 *
 * - buildForBidi:
 *   Compute only text directions.
 * - buildForMeasurement:
 *   Compute text direction and all character widths.
 * - buildForStaticLayout:
 *   This is bit special. StaticLayout also needs to know text direction and character widths for
 *   line breaking, but all things are done in native code. Similarly, text measurement is done
 *   in native code. So instead of storing result to Java array, this keeps the result in native
 *   code since there is no good reason to move the results to Java layer.
 *
 * In addition to the character widths, some additional information is computed for each purposes,
 * e.g. whole text length for measurement or font metrics for static layout.
 *
 * MeasuredParagraph is NOT a thread safe object.
 * @hide
 */
@TestApi
public class MeasuredParagraph {
    private static final char OBJECT_REPLACEMENT_CHARACTER = '\uFFFC';

    private MeasuredParagraph() {}  // Use build static functions instead.

    private static final SynchronizedPool<MeasuredParagraph> sPool = new SynchronizedPool<>(1);

    private static @NonNull MeasuredParagraph obtain() { // Use build static functions instead.
        final MeasuredParagraph mt = sPool.acquire();
        return mt != null ? mt : new MeasuredParagraph();
    }

    /**
     * Recycle the MeasuredParagraph.
     *
     * Do not call any methods after you call this method.
     * @hide
     */
    public void recycle() {
        release();
        sPool.release(this);
    }

    // The casted original text.
    //
    // This may be null if the passed text is not a Spanned.
    private @Nullable Spanned mSpanned;

    // The start offset of the target range in the original text (mSpanned);
    private @IntRange(from = 0) int mTextStart;

    // The length of the target range in the original text.
    private @IntRange(from = 0) int mTextLength;

    // The copied character buffer for measuring text.
    //
    // The length of this array is mTextLength.
    private @Nullable char[] mCopiedBuffer;

    // The whole paragraph direction.
    private @Layout.Direction int mParaDir;

    // True if the text is LTR direction and doesn't contain any bidi characters.
    private boolean mLtrWithoutBidi;

    // The bidi level for individual characters.
    //
    // This is empty if mLtrWithoutBidi is true.
    private @NonNull ByteArray mLevels = new ByteArray();

    // The bidi level for runs.
    private @NonNull ByteArray mRunLevels = new ByteArray();

    private Bidi mBidi;

    // The whole width of the text.
    // See getWholeWidth comments.
    private @FloatRange(from = 0.0f) float mWholeWidth;

    // Individual characters' widths.
    // See getWidths comments.
    private @Nullable FloatArray mWidths = new FloatArray();

    // The span end positions.
    // See getSpanEndCache comments.
    private @Nullable IntArray mSpanEndCache = new IntArray(4);

    // The font metrics.
    // See getFontMetrics comments.
    private @Nullable IntArray mFontMetrics = new IntArray(4 * 4);

    // The native MeasuredParagraph.
    private @Nullable MeasuredText mMeasuredText;

    // Following three objects are for avoiding object allocation.
    private final @NonNull TextPaint mCachedPaint = new TextPaint();
    private @Nullable Paint.FontMetricsInt mCachedFm;
    private final @NonNull LineBreakConfig.Builder mLineBreakConfigBuilder =
            new LineBreakConfig.Builder();

    /**
     * Releases internal buffers.
     * @hide
     */
    public void release() {
        reset();
        mLevels.clearWithReleasingLargeArray();
        mWidths.clearWithReleasingLargeArray();
        mRunLevels.clearWithReleasingLargeArray();
        mFontMetrics.clearWithReleasingLargeArray();
        mSpanEndCache.clearWithReleasingLargeArray();
    }

    /**
     * Resets the internal state for starting new text.
     */
    private void reset() {
        mSpanned = null;
        mCopiedBuffer = null;
        mWholeWidth = 0;
        mLevels.clear();
        mRunLevels.clear();
        mWidths.clear();
        mFontMetrics.clear();
        mSpanEndCache.clear();
        mMeasuredText = null;
        mBidi = null;
    }

    /**
     * Returns the length of the paragraph.
     *
     * This is always available.
     * @hide
     */
    public int getTextLength() {
        return mTextLength;
    }

    /**
     * Returns the characters to be measured.
     *
     * This is always available.
     * @hide
     */
    public @NonNull char[] getChars() {
        return mCopiedBuffer;
    }

    /**
     * Returns the paragraph direction.
     *
     * This is always available.
     * @hide
     */
    public @Layout.Direction int getParagraphDir() {
        if (ClientFlags.icuBidiMigration()) {
            if (mBidi == null) {
                return Layout.DIR_LEFT_TO_RIGHT;
            }
            return (mBidi.getParaLevel() & 0x01) == 0
                    ? Layout.DIR_LEFT_TO_RIGHT : Layout.DIR_RIGHT_TO_LEFT;
        }
        return mParaDir;
    }

    /**
     * Returns the directions.
     *
     * This is always available.
     * @hide
     */
    public Directions getDirections(@IntRange(from = 0) int start,  // inclusive
                                    @IntRange(from = 0) int end) {  // exclusive
        if (ClientFlags.icuBidiMigration()) {
            // Easy case: mBidi == null means the text is all LTR and no bidi suppot is needed.
            if (mBidi == null) {
                return Layout.DIRS_ALL_LEFT_TO_RIGHT;
            }

            // Easy case: If the original text only contains single directionality run, the
            // substring is only single run.
            if (start == end) {
                if ((mBidi.getParaLevel() & 0x01) == 0) {
                    return Layout.DIRS_ALL_LEFT_TO_RIGHT;
                } else {
                    return Layout.DIRS_ALL_RIGHT_TO_LEFT;
                }
            }

            // Okay, now we need to generate the line instance.
            Bidi bidi = mBidi.createLineBidi(start, end);

            // Easy case: If the line instance only contains single directionality run, no need
            // to reorder visually.
            if (bidi.getRunCount() == 1) {
                if ((bidi.getParaLevel() & 0x01) == 1) {
                    return Layout.DIRS_ALL_RIGHT_TO_LEFT;
                } else {
                    return Layout.DIRS_ALL_LEFT_TO_RIGHT;
                }
            }

            // Reorder directionality run visually.
            mRunLevels.resize(bidi.getRunCount());
            byte[] levels = mRunLevels.getRawArray();
            for (int i = 0; i < bidi.getRunCount(); ++i) {
                levels[i] = (byte) bidi.getRunLevel(i);
            }
            int[] visualOrders = Bidi.reorderVisual(levels);

            int[] dirs = new int[bidi.getRunCount() * 2];
            for (int i = 0; i < bidi.getRunCount(); ++i) {
                int vIndex;
                if ((mBidi.getBaseLevel() & 0x01) == 1) {
                    // For the historical reasons, if the base directionality is RTL, the Android
                    // draws from the right, i.e. the visually reordered run needs to be reversed.
                    vIndex = visualOrders[bidi.getRunCount() - i - 1];
                } else {
                    vIndex = visualOrders[i];
                }

                // Special packing of dire
                dirs[i * 2] = bidi.getRunStart(vIndex);
                dirs[i * 2 + 1] = bidi.getRunLevel(vIndex) << Layout.RUN_LEVEL_SHIFT
                        | (bidi.getRunLimit(vIndex) - dirs[i * 2]);
            }

            return new Directions(dirs);
        }
        if (mLtrWithoutBidi) {
            return Layout.DIRS_ALL_LEFT_TO_RIGHT;
        }

        final int length = end - start;
        return AndroidBidi.directions(mParaDir, mLevels.getRawArray(), start, mCopiedBuffer, start,
                length);
    }

    /**
     * Returns the whole text width.
     *
     * This is available only if the MeasuredParagraph is computed with buildForMeasurement.
     * Returns 0 in other cases.
     * @hide
     */
    public @FloatRange(from = 0.0f) float getWholeWidth() {
        return mWholeWidth;
    }

    /**
     * Returns the individual character's width.
     *
     * This is available only if the MeasuredParagraph is computed with buildForMeasurement.
     * Returns empty array in other cases.
     * @hide
     */
    public @NonNull FloatArray getWidths() {
        return mWidths;
    }

    /**
     * Returns the MetricsAffectingSpan end indices.
     *
     * If the input text is not a spanned string, this has one value that is the length of the text.
     *
     * This is available only if the MeasuredParagraph is computed with buildForStaticLayout.
     * Returns empty array in other cases.
     * @hide
     */
    public @NonNull IntArray getSpanEndCache() {
        return mSpanEndCache;
    }

    /**
     * Returns the int array which holds FontMetrics.
     *
     * This array holds the repeat of top, bottom, ascent, descent of font metrics value.
     *
     * This is available only if the MeasuredParagraph is computed with buildForStaticLayout.
     * Returns empty array in other cases.
     * @hide
     */
    public @NonNull IntArray getFontMetrics() {
        return mFontMetrics;
    }

    /**
     * Returns the native ptr of the MeasuredParagraph.
     *
     * This is available only if the MeasuredParagraph is computed with buildForStaticLayout.
     * Returns null in other cases.
     * @hide
     */
    public MeasuredText getMeasuredText() {
        return mMeasuredText;
    }

    /**
     * Returns the width of the given range.
     *
     * This is not available if the MeasuredParagraph is computed with buildForBidi.
     * Returns 0 if the MeasuredParagraph is computed with buildForBidi.
     *
     * @param start the inclusive start offset of the target region in the text
     * @param end the exclusive end offset of the target region in the text
     * @hide
     */
    public float getWidth(int start, int end) {
        if (mMeasuredText == null) {
            // We have result in Java.
            final float[] widths = mWidths.getRawArray();
            float r = 0.0f;
            for (int i = start; i < end; ++i) {
                r += widths[i];
            }
            return r;
        } else {
            // We have result in native.
            return mMeasuredText.getWidth(start, end);
        }
    }

    /**
     * Retrieves the bounding rectangle that encloses all of the characters, with an implied origin
     * at (0, 0).
     *
     * This is available only if the MeasuredParagraph is computed with buildForStaticLayout.
     * @hide
     */
    public void getBounds(@IntRange(from = 0) int start, @IntRange(from = 0) int end,
            @NonNull Rect bounds) {
        mMeasuredText.getBounds(start, end, bounds);
    }

    /**
     * Retrieves the font metrics for the given range.
     *
     * This is available only if the MeasuredParagraph is computed with buildForStaticLayout.
     * @hide
     */
    public void getFontMetricsInt(@IntRange(from = 0) int start, @IntRange(from = 0) int end,
            @NonNull Paint.FontMetricsInt fmi) {
        mMeasuredText.getFontMetricsInt(start, end, fmi);
    }

    /**
     * Returns a width of the character at the offset.
     *
     * This is available only if the MeasuredParagraph is computed with buildForStaticLayout.
     * @hide
     */
    public float getCharWidthAt(@IntRange(from = 0) int offset) {
        return mMeasuredText.getCharWidthAt(offset);
    }

    /**
     * Generates new MeasuredParagraph for Bidi computation.
     *
     * If recycle is null, this returns new instance. If recycle is not null, this fills computed
     * result to recycle and returns recycle.
     *
     * @param text the character sequence to be measured
     * @param start the inclusive start offset of the target region in the text
     * @param end the exclusive end offset of the target region in the text
     * @param textDir the text direction
     * @param recycle pass existing MeasuredParagraph if you want to recycle it.
     *
     * @return measured text
     * @hide
     */
    public static @NonNull MeasuredParagraph buildForBidi(@NonNull CharSequence text,
                                                     @IntRange(from = 0) int start,
                                                     @IntRange(from = 0) int end,
                                                     @NonNull TextDirectionHeuristic textDir,
                                                     @Nullable MeasuredParagraph recycle) {
        final MeasuredParagraph mt = recycle == null ? obtain() : recycle;
        mt.resetAndAnalyzeBidi(text, start, end, textDir);
        return mt;
    }

    /**
     * Generates new MeasuredParagraph for measuring texts.
     *
     * If recycle is null, this returns new instance. If recycle is not null, this fills computed
     * result to recycle and returns recycle.
     *
     * @param paint the paint to be used for rendering the text.
     * @param text the character sequence to be measured
     * @param start the inclusive start offset of the target region in the text
     * @param end the exclusive end offset of the target region in the text
     * @param textDir the text direction
     * @param recycle pass existing MeasuredParagraph if you want to recycle it.
     *
     * @return measured text
     * @hide
     */
    public static @NonNull MeasuredParagraph buildForMeasurement(@NonNull TextPaint paint,
                                                            @NonNull CharSequence text,
                                                            @IntRange(from = 0) int start,
                                                            @IntRange(from = 0) int end,
                                                            @NonNull TextDirectionHeuristic textDir,
                                                            @Nullable MeasuredParagraph recycle) {
        final MeasuredParagraph mt = recycle == null ? obtain() : recycle;
        mt.resetAndAnalyzeBidi(text, start, end, textDir);

        mt.mWidths.resize(mt.mTextLength);
        if (mt.mTextLength == 0) {
            return mt;
        }

        if (mt.mSpanned == null) {
            // No style change by MetricsAffectingSpan. Just measure all text.
            mt.applyMetricsAffectingSpan(
                    paint, null /* lineBreakConfig */, null /* spans */, null /* lbcSpans */,
                    start, end, null /* native builder ptr */, null);
        } else {
            // There may be a MetricsAffectingSpan. Split into span transitions and apply styles.
            int spanEnd;
            for (int spanStart = start; spanStart < end; spanStart = spanEnd) {
                int maSpanEnd = mt.mSpanned.nextSpanTransition(spanStart, end,
                        MetricAffectingSpan.class);
                int lbcSpanEnd = mt.mSpanned.nextSpanTransition(spanStart, end,
                        LineBreakConfigSpan.class);
                spanEnd = Math.min(maSpanEnd, lbcSpanEnd);
                MetricAffectingSpan[] spans = mt.mSpanned.getSpans(spanStart, spanEnd,
                        MetricAffectingSpan.class);
                LineBreakConfigSpan[] lbcSpans = mt.mSpanned.getSpans(spanStart, spanEnd,
                        LineBreakConfigSpan.class);
                spans = TextUtils.removeEmptySpans(spans, mt.mSpanned, MetricAffectingSpan.class);
                lbcSpans = TextUtils.removeEmptySpans(lbcSpans, mt.mSpanned,
                        LineBreakConfigSpan.class);
                mt.applyMetricsAffectingSpan(
                        paint, null /* line break config */, spans, lbcSpans, spanStart, spanEnd,
                        null /* native builder ptr */, null);
            }
        }
        return mt;
    }

    /**
     * A test interface for observing the style run calculation.
     * @hide
     */
    @TestApi
    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public interface StyleRunCallback {
        /**
         * Called when a single style run is identified.
         */
        @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
        void onAppendStyleRun(@NonNull Paint paint,
                @Nullable LineBreakConfig lineBreakConfig, @IntRange(from = 0) int length,
                boolean isRtl);

        /**
         * Called when a single replacement run is identified.
         */
        @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
        void onAppendReplacementRun(@NonNull Paint paint,
                @IntRange(from = 0) int length, @Px @FloatRange(from = 0) float width);
    }

    /**
     * Generates new MeasuredParagraph for StaticLayout.
     *
     * If recycle is null, this returns new instance. If recycle is not null, this fills computed
     * result to recycle and returns recycle.
     *
     * @param paint the paint to be used for rendering the text.
     * @param lineBreakConfig the line break configuration for text wrapping.
     * @param text the character sequence to be measured
     * @param start the inclusive start offset of the target region in the text
     * @param end the exclusive end offset of the target region in the text
     * @param textDir the text direction
     * @param hyphenationMode a hyphenation mode
     * @param computeLayout true if need to compute full layout, otherwise false.
     * @param hint pass if you already have measured paragraph.
     * @param recycle pass existing MeasuredParagraph if you want to recycle it.
     *
     * @return measured text
     * @hide
     */
    public static @NonNull MeasuredParagraph buildForStaticLayout(
            @NonNull TextPaint paint,
            @Nullable LineBreakConfig lineBreakConfig,
            @NonNull CharSequence text,
            @IntRange(from = 0) int start,
            @IntRange(from = 0) int end,
            @NonNull TextDirectionHeuristic textDir,
            int hyphenationMode,
            boolean computeLayout,
            boolean computeBounds,
            @Nullable MeasuredParagraph hint,
            @Nullable MeasuredParagraph recycle) {
        return buildForStaticLayoutInternal(paint, lineBreakConfig, text, start, end, textDir,
                hyphenationMode, computeLayout, computeBounds, hint, recycle, null);
    }

    /**
     * Generates new MeasuredParagraph for StaticLayout.
     *
     * If recycle is null, this returns new instance. If recycle is not null, this fills computed
     * result to recycle and returns recycle.
     *
     * @param paint the paint to be used for rendering the text.
     * @param lineBreakConfig the line break configuration for text wrapping.
     * @param text the character sequence to be measured
     * @param start the inclusive start offset of the target region in the text
     * @param end the exclusive end offset of the target region in the text
     * @param textDir the text direction
     * @param hyphenationMode a hyphenation mode
     * @param computeLayout true if need to compute full layout, otherwise false.
     *
     * @return measured text
     * @hide
     */
    @SuppressLint("ExecutorRegistration")
    @TestApi
    @NonNull
    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public static MeasuredParagraph buildForStaticLayoutTest(
            @NonNull TextPaint paint,
            @Nullable LineBreakConfig lineBreakConfig,
            @NonNull CharSequence text,
            @IntRange(from = 0) int start,
            @IntRange(from = 0) int end,
            @NonNull TextDirectionHeuristic textDir,
            int hyphenationMode,
            boolean computeLayout,
            @Nullable StyleRunCallback testCallback) {
        return buildForStaticLayoutInternal(paint, lineBreakConfig, text, start, end, textDir,
                hyphenationMode, computeLayout, false, null, null, testCallback);
    }

    private static @NonNull MeasuredParagraph buildForStaticLayoutInternal(
            @NonNull TextPaint paint,
            @Nullable LineBreakConfig lineBreakConfig,
            @NonNull CharSequence text,
            @IntRange(from = 0) int start,
            @IntRange(from = 0) int end,
            @NonNull TextDirectionHeuristic textDir,
            int hyphenationMode,
            boolean computeLayout,
            boolean computeBounds,
            @Nullable MeasuredParagraph hint,
            @Nullable MeasuredParagraph recycle,
            @Nullable StyleRunCallback testCallback) {
        final MeasuredParagraph mt = recycle == null ? obtain() : recycle;
        mt.resetAndAnalyzeBidi(text, start, end, textDir);
        final MeasuredText.Builder builder;
        if (hint == null) {
            builder = new MeasuredText.Builder(mt.mCopiedBuffer)
                    .setComputeHyphenation(hyphenationMode)
                    .setComputeLayout(computeLayout)
                    .setComputeBounds(computeBounds);
        } else {
            builder = new MeasuredText.Builder(hint.mMeasuredText);
        }
        if (mt.mTextLength == 0) {
            // Need to build empty native measured text for StaticLayout.
            // TODO: Stop creating empty measured text for empty lines.
            mt.mMeasuredText = builder.build();
        } else {
            if (mt.mSpanned == null) {
                // No style change by MetricsAffectingSpan. Just measure all text.
                mt.applyMetricsAffectingSpan(paint, lineBreakConfig, null /* spans */, null,
                        start, end, builder, testCallback);
                mt.mSpanEndCache.append(end);
            } else {
                // There may be a MetricsAffectingSpan. Split into span transitions and apply
                // styles.
                int spanEnd;
                for (int spanStart = start; spanStart < end; spanStart = spanEnd) {
                    int maSpanEnd = mt.mSpanned.nextSpanTransition(spanStart, end,
                                                             MetricAffectingSpan.class);
                    int lbcSpanEnd = mt.mSpanned.nextSpanTransition(spanStart, end,
                            LineBreakConfigSpan.class);
                    spanEnd = Math.min(maSpanEnd, lbcSpanEnd);
                    MetricAffectingSpan[] spans = mt.mSpanned.getSpans(spanStart, spanEnd,
                            MetricAffectingSpan.class);
                    LineBreakConfigSpan[] lbcSpans = mt.mSpanned.getSpans(spanStart, spanEnd,
                            LineBreakConfigSpan.class);
                    spans = TextUtils.removeEmptySpans(spans, mt.mSpanned,
                                                       MetricAffectingSpan.class);
                    lbcSpans = TextUtils.removeEmptySpans(lbcSpans, mt.mSpanned,
                                                       LineBreakConfigSpan.class);
                    mt.applyMetricsAffectingSpan(paint, lineBreakConfig, spans, lbcSpans, spanStart,
                            spanEnd, builder, testCallback);
                    mt.mSpanEndCache.append(spanEnd);
                }
            }
            mt.mMeasuredText = builder.build();
        }

        return mt;
    }

    /**
     * Reset internal state and analyzes text for bidirectional runs.
     *
     * @param text the character sequence to be measured
     * @param start the inclusive start offset of the target region in the text
     * @param end the exclusive end offset of the target region in the text
     * @param textDir the text direction
     */
    private void resetAndAnalyzeBidi(@NonNull CharSequence text,
                                     @IntRange(from = 0) int start,  // inclusive
                                     @IntRange(from = 0) int end,  // exclusive
                                     @NonNull TextDirectionHeuristic textDir) {
        reset();
        mSpanned = text instanceof Spanned ? (Spanned) text : null;
        mTextStart = start;
        mTextLength = end - start;

        if (mCopiedBuffer == null || mCopiedBuffer.length != mTextLength) {
            mCopiedBuffer = new char[mTextLength];
        }
        TextUtils.getChars(text, start, end, mCopiedBuffer, 0);

        // Replace characters associated with ReplacementSpan to U+FFFC.
        if (mSpanned != null) {
            ReplacementSpan[] spans = mSpanned.getSpans(start, end, ReplacementSpan.class);

            for (int i = 0; i < spans.length; i++) {
                int startInPara = mSpanned.getSpanStart(spans[i]) - start;
                int endInPara = mSpanned.getSpanEnd(spans[i]) - start;
                // The span interval may be larger and must be restricted to [start, end)
                if (startInPara < 0) startInPara = 0;
                if (endInPara > mTextLength) endInPara = mTextLength;
                Arrays.fill(mCopiedBuffer, startInPara, endInPara, OBJECT_REPLACEMENT_CHARACTER);
            }
        }

        if (ClientFlags.icuBidiMigration()) {
            if ((textDir == TextDirectionHeuristics.LTR
                    || textDir == TextDirectionHeuristics.FIRSTSTRONG_LTR
                    || textDir == TextDirectionHeuristics.ANYRTL_LTR)
                    && TextUtils.doesNotNeedBidi(mCopiedBuffer, 0, mTextLength)) {
                mLevels.clear();
                mLtrWithoutBidi = true;
                return;
            }
            final int bidiRequest;
            if (textDir == TextDirectionHeuristics.LTR) {
                bidiRequest = Bidi.LTR;
            } else if (textDir == TextDirectionHeuristics.RTL) {
                bidiRequest = Bidi.RTL;
            } else if (textDir == TextDirectionHeuristics.FIRSTSTRONG_LTR) {
                bidiRequest = Bidi.LEVEL_DEFAULT_LTR;
            } else if (textDir == TextDirectionHeuristics.FIRSTSTRONG_RTL) {
                bidiRequest = Bidi.LEVEL_DEFAULT_RTL;
            } else {
                final boolean isRtl = textDir.isRtl(mCopiedBuffer, 0, mTextLength);
                bidiRequest = isRtl ? Bidi.RTL : Bidi.LTR;
            }
            mBidi = new Bidi(mCopiedBuffer, 0, null, 0, mCopiedBuffer.length, bidiRequest);
            mLevels.resize(mTextLength);
            byte[] rawArray = mLevels.getRawArray();
            for (int i = 0; i < mTextLength; ++i) {
                rawArray[i] = mBidi.getLevelAt(i);
            }
            mLtrWithoutBidi = false;
            return;
        }
        if ((textDir == TextDirectionHeuristics.LTR
                || textDir == TextDirectionHeuristics.FIRSTSTRONG_LTR
                || textDir == TextDirectionHeuristics.ANYRTL_LTR)
                && TextUtils.doesNotNeedBidi(mCopiedBuffer, 0, mTextLength)) {
            mLevels.clear();
            mParaDir = Layout.DIR_LEFT_TO_RIGHT;
            mLtrWithoutBidi = true;
        } else {
            final int bidiRequest;
            if (textDir == TextDirectionHeuristics.LTR) {
                bidiRequest = Layout.DIR_REQUEST_LTR;
            } else if (textDir == TextDirectionHeuristics.RTL) {
                bidiRequest = Layout.DIR_REQUEST_RTL;
            } else if (textDir == TextDirectionHeuristics.FIRSTSTRONG_LTR) {
                bidiRequest = Layout.DIR_REQUEST_DEFAULT_LTR;
            } else if (textDir == TextDirectionHeuristics.FIRSTSTRONG_RTL) {
                bidiRequest = Layout.DIR_REQUEST_DEFAULT_RTL;
            } else {
                final boolean isRtl = textDir.isRtl(mCopiedBuffer, 0, mTextLength);
                bidiRequest = isRtl ? Layout.DIR_REQUEST_RTL : Layout.DIR_REQUEST_LTR;
            }
            mLevels.resize(mTextLength);
            mParaDir = AndroidBidi.bidi(bidiRequest, mCopiedBuffer, mLevels.getRawArray());
            mLtrWithoutBidi = false;
        }
    }

    private void applyReplacementRun(@NonNull ReplacementSpan replacement,
                                     @IntRange(from = 0) int start,  // inclusive, in copied buffer
                                     @IntRange(from = 0) int end,  // exclusive, in copied buffer
                                     @NonNull TextPaint paint,
                                     @Nullable MeasuredText.Builder builder,
                                     @Nullable StyleRunCallback testCallback) {
        // Use original text. Shouldn't matter.
        // TODO: passing uninitizlied FontMetrics to developers. Do we need to keep this for
        //       backward compatibility? or Should we initialize them for getFontMetricsInt?
        final float width = replacement.getSize(
                paint, mSpanned, start + mTextStart, end + mTextStart, mCachedFm);
        if (builder == null) {
            // Assigns all width to the first character. This is the same behavior as minikin.
            mWidths.set(start, width);
            if (end > start + 1) {
                Arrays.fill(mWidths.getRawArray(), start + 1, end, 0.0f);
            }
            mWholeWidth += width;
        } else {
            builder.appendReplacementRun(paint, end - start, width);
        }
        if (testCallback != null) {
            testCallback.onAppendReplacementRun(paint, end - start, width);
        }
    }

    private void applyStyleRun(@IntRange(from = 0) int start,  // inclusive, in copied buffer
                               @IntRange(from = 0) int end,  // exclusive, in copied buffer
                               @NonNull TextPaint paint,
                               @Nullable LineBreakConfig config,
                               @Nullable MeasuredText.Builder builder,
                               @Nullable StyleRunCallback testCallback) {

        if (mLtrWithoutBidi) {
            // If the whole text is LTR direction, just apply whole region.
            if (builder == null) {
                // For the compatibility reasons, the letter spacing should not be dropped at the
                // left and right edge.
                int oldFlag = paint.getFlags();
                paint.setFlags(paint.getFlags()
                        | (Paint.TEXT_RUN_FLAG_LEFT_EDGE | Paint.TEXT_RUN_FLAG_RIGHT_EDGE));
                try {
                    mWholeWidth += paint.getTextRunAdvances(
                            mCopiedBuffer, start, end - start, start, end - start,
                            false /* isRtl */, mWidths.getRawArray(), start);
                } finally {
                    paint.setFlags(oldFlag);
                }
            } else {
                builder.appendStyleRun(paint, config, end - start, false /* isRtl */);
            }
            if (testCallback != null) {
                testCallback.onAppendStyleRun(paint, config, end - start, false);
            }
        } else {
            // If there is multiple bidi levels, split into individual bidi level and apply style.
            byte level = mLevels.get(start);
            // Note that the empty text or empty range won't reach this method.
            // Safe to search from start + 1.
            for (int levelStart = start, levelEnd = start + 1;; ++levelEnd) {
                if (levelEnd == end || mLevels.get(levelEnd) != level) {  // transition point
                    final boolean isRtl = (level & 0x1) != 0;
                    if (builder == null) {
                        final int levelLength = levelEnd - levelStart;
                        int oldFlag = paint.getFlags();
                        paint.setFlags(paint.getFlags()
                                | (Paint.TEXT_RUN_FLAG_LEFT_EDGE | Paint.TEXT_RUN_FLAG_RIGHT_EDGE));
                        try {
                            mWholeWidth += paint.getTextRunAdvances(
                                    mCopiedBuffer, levelStart, levelLength, levelStart, levelLength,
                                    isRtl, mWidths.getRawArray(), levelStart);
                        } finally {
                            paint.setFlags(oldFlag);
                        }
                    } else {
                        builder.appendStyleRun(paint, config, levelEnd - levelStart, isRtl);
                    }
                    if (testCallback != null) {
                        testCallback.onAppendStyleRun(paint, config, levelEnd - levelStart, isRtl);
                    }
                    if (levelEnd == end) {
                        break;
                    }
                    levelStart = levelEnd;
                    level = mLevels.get(levelEnd);
                }
            }
        }
    }

    private void applyMetricsAffectingSpan(
            @NonNull TextPaint paint,
            @Nullable LineBreakConfig lineBreakConfig,
            @Nullable MetricAffectingSpan[] spans,
            @Nullable LineBreakConfigSpan[] lbcSpans,
            @IntRange(from = 0) int start,  // inclusive, in original text buffer
            @IntRange(from = 0) int end,  // exclusive, in original text buffer
            @Nullable MeasuredText.Builder builder,
            @Nullable StyleRunCallback testCallback) {
        mCachedPaint.set(paint);
        // XXX paint should not have a baseline shift, but...
        mCachedPaint.baselineShift = 0;

        final boolean needFontMetrics = builder != null;

        if (needFontMetrics && mCachedFm == null) {
            mCachedFm = new Paint.FontMetricsInt();
        }

        ReplacementSpan replacement = null;
        if (spans != null) {
            for (int i = 0; i < spans.length; i++) {
                MetricAffectingSpan span = spans[i];
                if (span instanceof ReplacementSpan) {
                    // The last ReplacementSpan is effective for backward compatibility reasons.
                    replacement = (ReplacementSpan) span;
                } else {
                    // TODO: No need to call updateMeasureState for ReplacementSpan as well?
                    span.updateMeasureState(mCachedPaint);
                }
            }
        }

        if (lbcSpans != null) {
            mLineBreakConfigBuilder.reset(lineBreakConfig);
            for (LineBreakConfigSpan lbcSpan : lbcSpans) {
                mLineBreakConfigBuilder.merge(lbcSpan.getLineBreakConfig());
            }
            lineBreakConfig = mLineBreakConfigBuilder.build();
        }

        final int startInCopiedBuffer = start - mTextStart;
        final int endInCopiedBuffer = end - mTextStart;

        if (builder != null) {
            mCachedPaint.getFontMetricsInt(mCachedFm);
        }

        if (replacement != null) {
            applyReplacementRun(replacement, startInCopiedBuffer, endInCopiedBuffer, mCachedPaint,
                    builder, testCallback);
        } else {
            applyStyleRun(startInCopiedBuffer, endInCopiedBuffer, mCachedPaint,
                    lineBreakConfig, builder, testCallback);
        }

        if (needFontMetrics) {
            if (mCachedPaint.baselineShift < 0) {
                mCachedFm.ascent += mCachedPaint.baselineShift;
                mCachedFm.top += mCachedPaint.baselineShift;
            } else {
                mCachedFm.descent += mCachedPaint.baselineShift;
                mCachedFm.bottom += mCachedPaint.baselineShift;
            }

            mFontMetrics.append(mCachedFm.top);
            mFontMetrics.append(mCachedFm.bottom);
            mFontMetrics.append(mCachedFm.ascent);
            mFontMetrics.append(mCachedFm.descent);
        }
    }

    /**
     * Returns the maximum index that the accumulated width not exceeds the width.
     *
     * If forward=false is passed, returns the minimum index from the end instead.
     *
     * This only works if the MeasuredParagraph is computed with buildForMeasurement.
     * Undefined behavior in other case.
     */
    @IntRange(from = 0) int breakText(int limit, boolean forwards, float width) {
        float[] w = mWidths.getRawArray();
        if (forwards) {
            int i = 0;
            while (i < limit) {
                width -= w[i];
                if (width < 0.0f) break;
                i++;
            }
            while (i > 0 && mCopiedBuffer[i - 1] == ' ') i--;
            return i;
        } else {
            int i = limit - 1;
            while (i >= 0) {
                width -= w[i];
                if (width < 0.0f) break;
                i--;
            }
            while (i < limit - 1 && (mCopiedBuffer[i + 1] == ' ' || w[i + 1] == 0.0f)) {
                i++;
            }
            return limit - i - 1;
        }
    }

    /**
     * Returns the length of the substring.
     *
     * This only works if the MeasuredParagraph is computed with buildForMeasurement.
     * Undefined behavior in other case.
     */
    @FloatRange(from = 0.0f) float measure(int start, int limit) {
        float width = 0;
        float[] w = mWidths.getRawArray();
        for (int i = start; i < limit; ++i) {
            width += w[i];
        }
        return width;
    }

    /**
     * This only works if the MeasuredParagraph is computed with buildForStaticLayout.
     * @hide
     */
    public @IntRange(from = 0) int getMemoryUsage() {
        return mMeasuredText.getMemoryUsage();
    }
}
