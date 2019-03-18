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

package android.graphics.text;

import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Px;
import android.graphics.Paint;
import android.graphics.Rect;

import com.android.internal.util.Preconditions;

import dalvik.annotation.optimization.CriticalNative;

import libcore.util.NativeAllocationRegistry;

/**
 * Result of text shaping of the single paragraph string.
 *
 * <p>
 * <pre>
 * <code>
 * Paint paint = new Paint();
 * Paint bigPaint = new Paint();
 * bigPaint.setTextSize(paint.getTextSize() * 2.0);
 * String text = "Hello, Android.";
 * MeasuredText mt = new MeasuredText.Builder(text.toCharArray())
 *      .appendStyleRun(paint, 7, false)  // Use paint for "Hello, "
 *      .appendStyleRun(bigPaint, 8, false)  // Use bigPaint for "Hello, "
 *      .build();
 * </code>
 * </pre>
 * </p>
 */
public class MeasuredText {
    private long mNativePtr;
    private boolean mComputeHyphenation;
    private boolean mComputeLayout;
    private @NonNull char[] mChars;

    // Use builder instead.
    private MeasuredText(long ptr, @NonNull char[] chars, boolean computeHyphenation,
            boolean computeLayout) {
        mNativePtr = ptr;
        mChars = chars;
        mComputeHyphenation = computeHyphenation;
        mComputeLayout = computeLayout;
    }

    /**
     * Returns the characters in the paragraph used to compute this MeasuredText instance.
     * @hide
     */
    public @NonNull char[] getChars() {
        return mChars;
    }

    /**
     * Returns the width of a given range.
     *
     * @param start an inclusive start index of the range
     * @param end an exclusive end index of the range
     */
    public @FloatRange(from = 0.0) @Px float getWidth(
            @IntRange(from = 0) int start, @IntRange(from = 0) int end) {
        Preconditions.checkArgument(0 <= start && start <= mChars.length,
                "start(" + start + ") must be 0 <= start <= " + mChars.length);
        Preconditions.checkArgument(0 <= end && end <= mChars.length,
                "end(" + end + ") must be 0 <= end <= " + mChars.length);
        Preconditions.checkArgument(start <= end,
                "start(" + start + ") is larger than end(" + end + ")");
        return nGetWidth(mNativePtr, start, end);
    }

    /**
     * Returns a memory usage of the native object.
     *
     * @hide
     */
    public int getMemoryUsage() {
        return nGetMemoryUsage(mNativePtr);
    }

    /**
     * Retrieves the boundary box of the given range
     *
     * @param start an inclusive start index of the range
     * @param end an exclusive end index of the range
     * @param rect an output parameter
     */
    public void getBounds(@IntRange(from = 0) int start, @IntRange(from = 0) int end,
            @NonNull Rect rect) {
        Preconditions.checkArgument(0 <= start && start <= mChars.length,
                "start(" + start + ") must be 0 <= start <= " + mChars.length);
        Preconditions.checkArgument(0 <= end && end <= mChars.length,
                "end(" + end + ") must be 0 <= end <= " + mChars.length);
        Preconditions.checkArgument(start <= end,
                "start(" + start + ") is larger than end(" + end + ")");
        Preconditions.checkNotNull(rect);
        nGetBounds(mNativePtr, mChars, start, end, rect);
    }

    /**
     * Returns the width of the character at the given offset.
     *
     * @param offset an offset of the character.
     */
    public @FloatRange(from = 0.0f) @Px float getCharWidthAt(@IntRange(from = 0) int offset) {
        Preconditions.checkArgument(0 <= offset && offset < mChars.length,
                "offset(" + offset + ") is larger than text length: " + mChars.length);
        return nGetCharWidthAt(mNativePtr, offset);
    }

    /**
     * Returns a native pointer of the underlying native object.
     *
     * @hide
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
     * Helper class for creating a {@link MeasuredText}.
     * <p>
     * <pre>
     * <code>
     * Paint paint = new Paint();
     * String text = "Hello, Android.";
     * MeasuredText mt = new MeasuredText.Builder(text.toCharArray())
     *      .appendStyleRun(paint, text.length, false)
     *      .build();
     * </code>
     * </pre>
     * </p>
     *
     * Note: The appendStyle and appendReplacementRun should be called to cover the text length.
     */
    public static final class Builder {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                MeasuredText.class.getClassLoader(), nGetReleaseFunc());

        private long mNativePtr;

        private final @NonNull char[] mText;
        private boolean mComputeHyphenation = false;
        private boolean mComputeLayout = true;
        private int mCurrentOffset = 0;
        private @Nullable MeasuredText mHintMt = null;

        /**
         * Construct a builder.
         *
         * The MeasuredText returned by build method will hold a reference of the text. Developer is
         * not supposed to modify the text.
         *
         * @param text a text
         */
        public Builder(@NonNull char[] text) {
            Preconditions.checkNotNull(text);
            mText = text;
            mNativePtr = nInitBuilder();
        }

        /**
         * Construct a builder with existing MeasuredText.
         *
         * The MeasuredText returned by build method will hold a reference of the text. Developer is
         * not supposed to modify the text.
         *
         * @param text a text
         */
        public Builder(@NonNull MeasuredText text) {
            Preconditions.checkNotNull(text);
            mText = text.mChars;
            mNativePtr = nInitBuilder();
            if (!text.mComputeLayout) {
                throw new IllegalArgumentException(
                    "The input MeasuredText must not be created with setComputeLayout(false).");
            }
            mComputeHyphenation = text.mComputeHyphenation;
            mComputeLayout = text.mComputeLayout;
            mHintMt = text;
        }

        /**
         * Apply styles to the given length.
         *
         * Keeps an internal offset which increases at every append. The initial value for this
         * offset is zero. After the style is applied the internal offset is moved to {@code offset
         * + length}, and next call will start from this new position.
         *
         * @param paint a paint
         * @param length a length to be applied with a given paint, can not exceed the length of the
         *               text
         * @param isRtl true if the text is in RTL context, otherwise false.
         */
        public @NonNull Builder appendStyleRun(@NonNull Paint paint, @IntRange(from = 0) int length,
                boolean isRtl) {
            Preconditions.checkNotNull(paint);
            Preconditions.checkArgument(length > 0, "length can not be negative");
            final int end = mCurrentOffset + length;
            Preconditions.checkArgument(end <= mText.length, "Style exceeds the text length");
            nAddStyleRun(mNativePtr, paint.getNativeInstance(), mCurrentOffset, end, isRtl);
            mCurrentOffset = end;
            return this;
        }

        /**
         * Used to inform the text layout that the given length is replaced with the object of given
         * width.
         *
         * Keeps an internal offset which increases at every append. The initial value for this
         * offset is zero. After the style is applied the internal offset is moved to {@code offset
         * + length}, and next call will start from this new position.
         *
         * Informs the layout engine that the given length should not be processed, instead the
         * provided width should be used for calculating the width of that range.
         *
         * @param length a length to be replaced with the object, can not exceed the length of the
         *               text
         * @param width a replacement width of the range
         */
        public @NonNull Builder appendReplacementRun(@NonNull Paint paint,
                @IntRange(from = 0) int length, @Px @FloatRange(from = 0) float width) {
            Preconditions.checkArgument(length > 0, "length can not be negative");
            final int end = mCurrentOffset + length;
            Preconditions.checkArgument(end <= mText.length, "Replacement exceeds the text length");
            nAddReplacementRun(mNativePtr, paint.getNativeInstance(), mCurrentOffset, end, width);
            mCurrentOffset = end;
            return this;
        }

        /**
         * By passing true to this method, the build method will compute all possible hyphenation
         * pieces as well.
         *
         * If you don't want to use automatic hyphenation, you can pass false to this method and
         * save the computation time of hyphenation. The default value is false.
         *
         * Even if you pass false to this method, you can still enable automatic hyphenation of
         * LineBreaker but line break computation becomes slower.
         *
         * @param computeHyphenation true if you want to use automatic hyphenations.
         */
        public @NonNull Builder setComputeHyphenation(boolean computeHyphenation) {
            mComputeHyphenation = computeHyphenation;
            return this;
        }

        /**
         * By passing true to this method, the build method will compute all full layout
         * information.
         *
         * If you don't use {@link MeasuredText#getBounds(int,int,android.graphics.Rect)}, you can
         * pass false to this method and save the memory spaces. The default value is true.
         *
         * Even if you pass false to this method, you can still call getBounds but it becomes
         * slower.
         *
         * @param computeLayout true if you want to retrieve full layout info, e.g. bbox.
         */
        public @NonNull Builder setComputeLayout(boolean computeLayout) {
            mComputeLayout = computeLayout;
            return this;
        }

        /**
         * Creates a MeasuredText.
         *
         * Once you called build() method, you can't reuse the Builder class again.
         * @throws IllegalStateException if this Builder is reused.
         * @throws IllegalStateException if the whole text is not covered by one or more runs (style
         *                               or replacement)
         */
        public @NonNull MeasuredText build() {
            ensureNativePtrNoReuse();
            if (mCurrentOffset != mText.length) {
                throw new IllegalStateException("Style info has not been provided for all text.");
            }
            if (mHintMt != null && mHintMt.mComputeHyphenation != mComputeHyphenation) {
                throw new IllegalArgumentException(
                        "The hyphenation configuration is different from given hint MeasuredText");
            }
            try {
                long hintPtr = (mHintMt == null) ? 0 : mHintMt.getNativePtr();
                long ptr = nBuildMeasuredText(mNativePtr, hintPtr, mText, mComputeHyphenation,
                        mComputeLayout);
                final MeasuredText res = new MeasuredText(ptr, mText, mComputeHyphenation,
                        mComputeLayout);
                sRegistry.registerNativeAllocation(res, ptr);
                return res;
            } finally {
                nFreeBuilder(mNativePtr);
                mNativePtr = 0;
            }
        }

        /**
         * Ensures {@link #mNativePtr} is not reused.
         *
         * <p/> This is a method by itself to help increase testability - eg. Robolectric might want
         * to override the validation behavior in test environment.
         */
        private void ensureNativePtrNoReuse() {
            if (mNativePtr == 0) {
                throw new IllegalStateException("Builder can not be reused.");
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

        private static native long nBuildMeasuredText(
                /* Non Zero */ long nativeBuilderPtr,
                long hintMtPtr,
                @NonNull char[] text,
                boolean computeHyphenation,
                boolean computeLayout);

        private static native void nFreeBuilder(/* Non Zero */ long nativeBuilderPtr);
    }
}
