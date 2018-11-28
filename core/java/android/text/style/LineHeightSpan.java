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

package android.text.style;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Px;
import android.graphics.Paint;
import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.TextPaint;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

/**
 * The classes that affect the line height of paragraph should implement this interface.
 */
public interface LineHeightSpan extends ParagraphStyle, WrapTogetherSpan {
    /**
     * Classes that implement this should define how the height is being calculated.
     *
     * @param text       the text
     * @param start      the start of the line
     * @param end        the end of the line
     * @param spanstartv the start of the span
     * @param lineHeight the line height
     * @param fm         font metrics of the paint, in integers
     */
    public void chooseHeight(CharSequence text, int start, int end,
            int spanstartv, int lineHeight,
            Paint.FontMetricsInt fm);

    /**
     * The classes that affect the line height of paragraph with respect to density,
     * should implement this interface.
     */
    public interface WithDensity extends LineHeightSpan {

        /**
         * Classes that implement this should define how the height is being calculated.
         *
         * @param text       the text
         * @param start      the start of the line
         * @param end        the end of the line
         * @param spanstartv the start of the span
         * @param lineHeight the line height
         * @param paint      the paint
         */
        public void chooseHeight(CharSequence text, int start, int end,
                int spanstartv, int lineHeight,
                Paint.FontMetricsInt fm, TextPaint paint);
    }

    /**
     * Default implementation of the {@link LineHeightSpan}, which changes the line height of the
     * attached paragraph.
     * <p>
     * LineHeightSpan will change the line height of the entire paragraph, even though it
     * covers only part of the paragraph.
     * </p>
     */
    class Standard implements LineHeightSpan, ParcelableSpan {

        private final @Px int mHeight;
        /**
         * Set the line height of the paragraph to <code>height</code> physical pixels.
         */
        public Standard(@Px @IntRange(from = 1) int height) {
            Preconditions.checkArgument(height > 0, "Height:" + height + "must be positive");
            mHeight = height;
        }

        /**
         * Constructor called from {@link TextUtils} to restore the span from a parcel
         */
        public Standard(Parcel src) {
            mHeight = src.readInt();
        }

        /**
         * Returns the line height specified by this span.
         */
        @Px
        public int getHeight() {
            return mHeight;
        }

        @Override
        public int getSpanTypeId() {
            return getSpanTypeIdInternal();
        }

        /** @hide */
        @Override
        public int getSpanTypeIdInternal() {
            return TextUtils.LINE_HEIGHT_SPAN;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            writeToParcelInternal(dest, flags);
        }

        /** @hide */
        @Override
        public void writeToParcelInternal(@NonNull Parcel dest, int flags) {
            dest.writeInt(mHeight);
        }

        @Override
        public void chooseHeight(@NonNull CharSequence text, int start, int end,
                int spanstartv, int lineHeight,
                @NonNull Paint.FontMetricsInt fm) {
            final int originHeight = fm.descent - fm.ascent;
            // If original height is not positive, do nothing.
            if (originHeight <= 0) {
                return;
            }
            final float ratio = mHeight * 1.0f / originHeight;
            fm.descent = Math.round(fm.descent * ratio);
            fm.ascent = fm.descent - mHeight;
        }
    }
}
