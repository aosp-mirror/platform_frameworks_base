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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

import libcore.util.NativeAllocationRegistry;

/**
 * A native implementation of the line breaker.
 * TODO: Consider to make this class public.
 * @hide
 */
public class NativeLineBreaker {

    /**
     * A result object of a line breaking
     */
    public static class LineBreaks {
        public int breakCount;
        private static final int INITIAL_SIZE = 16;
        public int[] breaks = new int[INITIAL_SIZE];
        public float[] widths = new float[INITIAL_SIZE];
        public float[] ascents = new float[INITIAL_SIZE];
        public float[] descents = new float[INITIAL_SIZE];
        public int[] flags = new int[INITIAL_SIZE];
        // breaks, widths, and flags should all have the same length
    }

    private static final NativeAllocationRegistry sRegistry = new NativeAllocationRegistry(
            NativeLineBreaker.class.getClassLoader(), nGetReleaseFunc(), 64);

    private final long mNativePtr;

    /**
     * A constructor of NativeLineBreaker
     */
    public NativeLineBreaker(@Layout.BreakStrategy int breakStrategy,
            @Layout.HyphenationFrequency int hyphenationFrequency,
            boolean justify, @Nullable int[] indents) {
        mNativePtr = nInit(breakStrategy, hyphenationFrequency, justify, indents);
        sRegistry.registerNativeAllocation(this, mNativePtr);
    }

    /**
     * Break text into lines
     *
     * @param chars an array of characters
     * @param measuredPara a result of the text measurement
     * @param length a length of the target text from the begining
     * @param firstWidth a width of the first width of the line in this paragraph
     * @param firstWidthLineCount a number of lines that has the length of the firstWidth
     * @param restWidth a width of the rest of the lines.
     * @param variableTabStops an array of tab stop widths
     * @param defaultTabStop a width of the tab stop
     * @param indentsOffset an offset of the indents to be used.
     * @param out output buffer
     * @return a number of the lines
     */
    @NonNull public int computeLineBreaks(
            @NonNull char[] chars,
            @NonNull NativeMeasuredParagraph measuredPara,
            @IntRange(from = 0) int length,
            @FloatRange(from = 0.0f) float firstWidth,
            @IntRange(from = 0) int firstWidthLineCount,
            @FloatRange(from = 0.0f) float restWidth,
            @Nullable int[] variableTabStops,
            int defaultTabStop,
            @IntRange(from = 0) int indentsOffset,
            @NonNull LineBreaks out) {
        return nComputeLineBreaks(
                mNativePtr,

                // Inputs
                chars,
                measuredPara.getNativePtr(),
                length,
                firstWidth,
                firstWidthLineCount,
                restWidth,
                variableTabStops,
                defaultTabStop,
                indentsOffset,

                // Outputs
                out,
                out.breaks.length,
                out.breaks,
                out.widths,
                out.ascents,
                out.descents,
                out.flags);

    }

    @FastNative
    private static native long nInit(
            @Layout.BreakStrategy int breakStrategy,
            @Layout.HyphenationFrequency int hyphenationFrequency,
            boolean isJustified,
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
    private static native int nComputeLineBreaks(
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
            @IntRange(from = 0) int indentsOffset,

            // Outputs
            @NonNull LineBreaks recycle,
            @IntRange(from  = 0) int recycleLength,
            @NonNull int[] recycleBreaks,
            @NonNull float[] recycleWidths,
            @NonNull float[] recycleAscents,
            @NonNull float[] recycleDescents,
            @NonNull int[] recycleFlags);
}
