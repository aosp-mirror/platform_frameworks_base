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

import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.graphics.Paint;
import android.graphics.Rect;

import dalvik.annotation.optimization.CriticalNative;

import libcore.util.NativeAllocationRegistry;

/**
 * A native implementation of measured paragraph.
 * TODO: Consider to make this class public.
 * @hide
 */
public class NativeMeasuredParagraph {
    private static final NativeAllocationRegistry sRegistry = new NativeAllocationRegistry(
            NativeMeasuredParagraph.class.getClassLoader(), nGetReleaseFunc(), 1024);

    private long mNativePtr;
    private @NonNull char[] mChars;

    // Use builder instead.
    private NativeMeasuredParagraph(long ptr, @NonNull char[] chars) {
        mNativePtr = ptr;
        mChars = chars;
    }

    /**
     * Returns a characters of this paragraph.
     */
    public char[] getChars() {
        return mChars;
    }

    /**
     * Returns a width of the given region
     */
    public float getWidth(int start, int end) {
        return nGetWidth(mNativePtr, start, end);
    }

    /**
     * Returns a memory usage of the native object.
     */
    public int getMemoryUsage() {
        return nGetMemoryUsage(mNativePtr);
    }

    /**
     * Fills the boundary box of the given region
     */
    public void getBounds(char[] buf, int start, int end, Rect rect) {
        nGetBounds(mNativePtr, buf, start, end, rect);
    }

    /**
     * Returns the width of the character at the given offset
     */
    public float getCharWidthAt(int offset) {
        return nGetCharWidthAt(mNativePtr, offset);
    }

    /**
     * Returns a native pointer of the underlying native object.
     */
    public long getNativePtr() {
        return mNativePtr;
    }

    @CriticalNative
    private static native float nGetWidth(/* Non Zero */ long nativePtr,
                                         @IntRange(from = 0) int start,
                                         @IntRange(from = 0) int end);

    @CriticalNative
    private static native /* Non Zero */ long nGetReleaseFunc();

    @CriticalNative
    private static native int nGetMemoryUsage(/* Non Zero */ long nativePtr);

    private static native void nGetBounds(long nativePtr, char[] buf, int start, int end,
            Rect rect);

    @CriticalNative
    private static native float nGetCharWidthAt(long nativePtr, int offset);

    /**
     * A builder for the NativeMeasuredParagraph
     */
    public static class Builder {
        private final long mNativePtr;

        public Builder() {
            mNativePtr = nInitBuilder();
        }

        /**
         * Apply styles to given range
         */
        public void addStyleRun(@NonNull Paint paint, int start, int end, boolean isRtl) {
            nAddStyleRun(mNativePtr, paint.getNativeInstance(), start, end, isRtl);
        }

        /**
         * Tells native that the given range is replaced with the object of given width.
         */
        public void addReplacementRun(@NonNull Paint paint, int start, int end, float width) {
            nAddReplacementRun(mNativePtr, paint.getNativeInstance(), start, end, width);
        }

        /**
         * Build the NativeMeasuredParagraph
         */
        public NativeMeasuredParagraph build(char[] text, boolean computeHyphenation,
                boolean computeLayout) {
            try {
                long ptr = nBuildNativeMeasuredParagraph(mNativePtr, text, computeHyphenation,
                        computeLayout);
                NativeMeasuredParagraph res = new NativeMeasuredParagraph(ptr, text);
                sRegistry.registerNativeAllocation(res, ptr);
                return res;
            } finally {
                nFreeBuilder(mNativePtr);
            }
        }

        private static native /* Non Zero */ long nInitBuilder();

        /**
         * Apply style to make native measured text.
         *
         * @param nativeBuilderPtr The native MeasuredParagraph builder pointer.
         * @param paintPtr The native paint pointer to be applied.
         * @param start The start offset in the copied buffer.
         * @param end The end offset in the copied buffer.
         * @param isRtl True if the text is RTL.
         */
        private static native void nAddStyleRun(/* Non Zero */ long nativeBuilderPtr,
                                                /* Non Zero */ long paintPtr,
                                                @IntRange(from = 0) int start,
                                                @IntRange(from = 0) int end,
                                                boolean isRtl);
        /**
         * Apply ReplacementRun to make native measured text.
         *
         * @param nativeBuilderPtr The native MeasuredParagraph builder pointer.
         * @param paintPtr The native paint pointer to be applied.
         * @param start The start offset in the copied buffer.
         * @param end The end offset in the copied buffer.
         * @param width The width of the replacement.
         */
        private static native void nAddReplacementRun(/* Non Zero */ long nativeBuilderPtr,
                                                      /* Non Zero */ long paintPtr,
                                                      @IntRange(from = 0) int start,
                                                      @IntRange(from = 0) int end,
                                                      @FloatRange(from = 0) float width);

        private static native long nBuildNativeMeasuredParagraph(
                /* Non Zero */ long nativeBuilderPtr,
                @NonNull char[] text,
                boolean computeHyphenation,
                boolean computeLayout);

        private static native void nFreeBuilder(/* Non Zero */ long nativeBuilderPtr);
    }
}
