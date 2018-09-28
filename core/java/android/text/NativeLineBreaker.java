/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Px;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

import libcore.util.NativeAllocationRegistry;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A native implementation of the line breaker.
 * TODO: Consider to make this class public.
 * @hide
 */
public class NativeLineBreaker {
    @IntDef(prefix = { "BREAK_STRATEGY_" }, value = {
            BREAK_STRATEGY_SIMPLE,
            BREAK_STRATEGY_HIGH_QUALITY,
            BREAK_STRATEGY_BALANCED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BreakStrategy {}

    /**
     * Value for break strategy indicating simple line breaking. Automatic hyphens are not added
     * (though soft hyphens are respected), and modifying text generally doesn't affect the layout
     * before it (which yields a more consistent user experience when editing), but layout may not
     * be the highest quality.
     */
    public static final int BREAK_STRATEGY_SIMPLE = 0;

    /**
     * Value for break strategy indicating high quality line breaking, including automatic
     * hyphenation and doing whole-paragraph optimization of line breaks.
     */
    public static final int BREAK_STRATEGY_HIGH_QUALITY = 1;

    /**
     * Value for break strategy indicating balanced line breaking. The breaks are chosen to
     * make all lines as close to the same length as possible, including automatic hyphenation.
     */
    public static final int BREAK_STRATEGY_BALANCED = 2;

    @IntDef(prefix = { "HYPHENATION_FREQUENCY_" }, value = {
            HYPHENATION_FREQUENCY_NORMAL,
            HYPHENATION_FREQUENCY_FULL,
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

    @IntDef(prefix = { "JUSTIFICATION_MODE_" }, value = {
            JUSTIFICATION_MODE_NONE,
            JUSTIFICATION_MODE_INTER_WORD
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface JustificationMode {}

    /**
     * Value for justification mode indicating no justification.
     */
    public static final int JUSTIFICATION_MODE_NONE = 0;

    /**
     * Value for justification mode indicating the text is justified by stretching word spacing.
     */
    public static final int JUSTIFICATION_MODE_INTER_WORD = 1;

    /**
     * A builder class of NativeLineBreaker.
     */
    public static class Builder {
        private @BreakStrategy int mBreakStrategy = BREAK_STRATEGY_SIMPLE;
        private @HyphenationFrequency int mHyphenationFrequency = HYPHENATION_FREQUENCY_NONE;
        private @JustificationMode int mJustified = JUSTIFICATION_MODE_NONE;
        private @Nullable int[] mIndents = null;

        /**
         * Construct a builder class.
         */
        public Builder() {}

        /**
         * Set break strategy.
         */
        public Builder setBreakStrategy(@BreakStrategy int breakStrategy) {
            mBreakStrategy = breakStrategy;
            return this;
        }

        /**
         * Set hyphenation frequency.
         */
        public Builder setHyphenationFrequency(@HyphenationFrequency int hyphenationFrequency) {
            mHyphenationFrequency = hyphenationFrequency;
            return this;
        }

        /**
         * Set whether the text is justified.
         */
        public Builder setJustified(@JustificationMode int justified) {
            mJustified = justified;
            return this;
        }

        /**
         * Set indents for entire text.
         *
         * Sets the total (left + right) indents in pixel per lines.
         */
        public Builder setIndents(@Nullable int[] indents) {
            mIndents = indents;
            return this;
        }

        /**
         * Returns the NativeLineBreaker with given parameters.
         */
        NativeLineBreaker build() {
            return new NativeLineBreaker(mBreakStrategy, mHyphenationFrequency, mJustified,
                    mIndents);
        }
    }

    /**
     * Line breaking constraints for single paragraph.
     */
    public static class ParagraphConstraints {
        private @FloatRange(from = 0.0f) float mWidth = 0;
        private @FloatRange(from = 0.0f) float mFirstWidth = 0;
        private @IntRange(from = 0) int mFirstWidthLineCount = 0;
        private @Nullable int[] mVariableTabStops = null;
        private @IntRange(from = 0) int mDefaultTabStop = 0;

        public ParagraphConstraints() {}

        /**
         * Set width for this paragraph.
         */
        public void setWidth(@FloatRange(from = 0.0f) float width) {
            mWidth = width;
        }

        /**
         * Set indent for this paragraph.
         *
         * @param firstWidth the line width of the starting of the paragraph
         * @param firstWidthLineCount the number of lines that applies the firstWidth
         */
        public void setIndent(@FloatRange(from = 0.0f) float firstWidth,
                @IntRange(from = 0) int firstWidthLineCount) {
            mFirstWidth = firstWidth;
            mFirstWidthLineCount = firstWidthLineCount;
        }

        /**
         * Set tab stops for this paragraph.
         *
         * @param tabStops the array of pixels of tap stopping position
         * @param defaultTabStop pixels of the default tab stopping position
         */
        public void setTabStops(@Nullable int[] tabStops, @IntRange(from = 0) int defaultTabStop) {
            mVariableTabStops = tabStops;
            mDefaultTabStop = defaultTabStop;
        }

        /**
         * Return the width for this paragraph in pixels.
         */
        public @FloatRange(from = 0.0f) float getWidth() {
            return mWidth;
        }

        /**
         * Return the first line's width for this paragraph in pixel.
         *
         * @see #setIndent(float, int)
         */
        public @FloatRange(from = 0.0f) float getFirstWidth() {
            return mFirstWidth;
        }

        /**
         * Return the number of lines to apply the first line's width.
         *
         * @see #setIndent(float, int)
         */
        public @IntRange(from = 0) int getFirstWidthLineCount() {
            return mFirstWidthLineCount;
        }

        /**
         * Returns the array of tab stops in pixels.
         *
         * @see #setTabStops(int[], int)
         */
        public @Nullable int[] getTabStops() {
            return mVariableTabStops;
        }

        /**
         * Returns the default tab stops in pixels.
         *
         * @see #setTabStop(int[], int)
         */
        public @IntRange(from = 0) int getDefaultTabStop() {
            return mDefaultTabStop;
        }
    }

    /**
     * A result object of a line breaking
     */
    public static class Result {
        // Following two contstant must be synced with minikin's line breaker.
        private static final int TAB_MASK = 0x20000000;
        private static final int HYPHEN_MASK = 0xFF;

        private static final NativeAllocationRegistry sRegistry = new NativeAllocationRegistry(
                Result.class.getClassLoader(), nGetReleaseResultFunc(), 32);
        private final long mPtr;

        private Result(long ptr) {
            mPtr = ptr;
            sRegistry.registerNativeAllocation(this, mPtr);
        }

        /**
         * Returns a number of line count.
         *
         * @return number of lines
         */
        public @IntRange(from = 0) int getLineCount() {
            return nGetLineCount(mPtr);
        }

        /**
         * Returns a break offset of the line.
         *
         * @param lineIndex an index of the line.
         * @return the break offset.
         */
        public @IntRange(from = 0) int getLineBreakOffset(@IntRange(from = 0) int lineIndex) {
            return nGetLineBreakOffset(mPtr, lineIndex);
        }

        /**
         * Returns a width of the line in pixels.
         *
         * @param lineIndex an index of the line.
         * @return a width of the line in pixexls
         */
        public @Px float getLineWidth(@IntRange(from = 0) int lineIndex) {
            return nGetLineWidth(mPtr, lineIndex);
        }

        /**
         * Returns an entier font ascent of the line in pixels.
         *
         * @param lineIndex an index of the line.
         * @return an entier font ascent of the line in pixels.
         */
        public @Px float getLineAscent(@IntRange(from = 0) int lineIndex) {
            return nGetLineAscent(mPtr, lineIndex);
        }

        /**
         * Returns an entier font descent of the line in pixels.
         *
         * @param lineIndex an index of the line.
         * @return an entier font descent of the line in pixels.
         */
        public @Px float getLineDescent(@IntRange(from = 0) int lineIndex) {
            return nGetLineDescent(mPtr, lineIndex);
        }

        /**
         * Returns true if the line has a TAB character.
         *
         * @param lineIndex an index of the line.
         * @return true if the line has a TAB character
         */
        public boolean hasLineTab(int lineIndex) {
            return (nGetLineFlag(mPtr, lineIndex) & TAB_MASK) != 0;
        }

        /**
         * Returns a packed packed hyphen edit for the line.
         *
         * @param lineIndex an index of the line.
         * @return a packed hyphen edit for the line.
         * @see android.text.Hyphenator#unpackStartHyphenEdit(int)
         * @see android.text.Hyphenator#unpackEndHyphenEdit(int)
         * @see android.text.Hyphenator#packHyphenEdit(int,int)
         */
        public int getLineHyphenEdit(int lineIndex) {
            return (nGetLineFlag(mPtr, lineIndex) & HYPHEN_MASK);
        }
    }

    private static final NativeAllocationRegistry sRegistry = new NativeAllocationRegistry(
            NativeLineBreaker.class.getClassLoader(), nGetReleaseFunc(), 64);

    private final long mNativePtr;

    /**
     * Use Builder instead.
     */
    private NativeLineBreaker(@BreakStrategy int breakStrategy,
            @HyphenationFrequency int hyphenationFrequency, @JustificationMode int justify,
            @Nullable int[] indents) {
        mNativePtr = nInit(breakStrategy, hyphenationFrequency,
                justify == JUSTIFICATION_MODE_INTER_WORD, indents);
        sRegistry.registerNativeAllocation(this, mNativePtr);
    }

    /**
     * Break paragraph into lines.
     *
     * The result is filled to out param.
     *
     * @param measuredPara a result of the text measurement
     * @param constraints for a single paragraph
     * @param lineNumber a line number of this paragraph
     */
    public Result computeLineBreaks(
            @NonNull NativeMeasuredParagraph measuredPara,
            @NonNull ParagraphConstraints constraints,
            @IntRange(from = 0) int lineNumber) {
        return new Result(nComputeLineBreaks(
                mNativePtr,

                // Inputs
                measuredPara.getChars(),
                measuredPara.getNativePtr(),
                measuredPara.getChars().length,
                constraints.mFirstWidth,
                constraints.mFirstWidthLineCount,
                constraints.mWidth,
                constraints.mVariableTabStops,
                constraints.mDefaultTabStop,
                lineNumber));
    }

    @FastNative
    private static native long nInit(@BreakStrategy int breakStrategy,
            @HyphenationFrequency int hyphenationFrequency, boolean isJustified,
            @Nullable int[] indents);

    @CriticalNative
    private static native long nGetReleaseFunc();

    // populates LineBreaks and returns the number of breaks found
    //
    // the arrays inside the LineBreaks objects are passed in as well
    // to reduce the number of JNI calls in the common case where the
    // arrays do not have to be resized
    // The individual character widths will be returned in charWidths. The length of
    // charWidths must be at least the length of the text.
    private static native long nComputeLineBreaks(
            /* non zero */ long nativePtr,

            // Inputs
            @NonNull char[] text,
            /* Non Zero */ long measuredTextPtr,
            @IntRange(from = 0) int length,
            @FloatRange(from = 0.0f) float firstWidth,
            @IntRange(from = 0) int firstWidthLineCount,
            @FloatRange(from = 0.0f) float restWidth,
            @Nullable int[] variableTabStops,
            int defaultTabStop,
            @IntRange(from = 0) int indentsOffset);

    // Result accessors
    @CriticalNative
    private static native int nGetLineCount(long ptr);
    @CriticalNative
    private static native int nGetLineBreakOffset(long ptr, int idx);
    @CriticalNative
    private static native float nGetLineWidth(long ptr, int idx);
    @CriticalNative
    private static native float nGetLineAscent(long ptr, int idx);
    @CriticalNative
    private static native float nGetLineDescent(long ptr, int idx);
    @CriticalNative
    private static native int nGetLineFlag(long ptr, int idx);
    @CriticalNative
    private static native long nGetReleaseResultFunc();
}
