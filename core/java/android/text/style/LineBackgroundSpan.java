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

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Px;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.TextUtils;

/**
 * Used to change the background of lines where the span is attached to.
 */
public interface LineBackgroundSpan extends ParagraphStyle
{
    /**
     * Draw the background on the canvas.
     *
     * @param canvas      canvas on which the span should be rendered
     * @param paint       paint used to draw text, which should be left unchanged on exit
     * @param left        left position of the line relative to input canvas, in pixels
     * @param right       right position of the line relative to input canvas, in pixels
     * @param top         top position of the line relative to input canvas, in pixels
     * @param baseline    baseline of the text relative to input canvas, in pixels
     * @param bottom      bottom position of the line relative to input canvas, in pixels
     * @param text        current text
     * @param start       start character index of the line
     * @param end         end character index of the line
     * @param lineNumber  line number in the current text layout
     */
    void drawBackground(@NonNull Canvas canvas, @NonNull Paint paint,
                               @Px int left, @Px int right,
                               @Px int top, @Px int baseline, @Px int bottom,
                               @NonNull CharSequence text, int start, int end,
                               int lineNumber);
    /**
     * Default implementation of the {@link LineBackgroundSpan}, which changes the background
     * color of the lines to which the span is attached.
     */
    class Standard implements LineBackgroundSpan, ParcelableSpan {

        private final int mColor;

        /**
         * Constructor taking a color integer.
         *
         * @param color Color integer that defines the background color.
         */
        public Standard(@ColorInt int color) {
            mColor = color;
        }

        /**
         * Creates a {@link LineBackgroundSpan.Standard} from a parcel
         */
        public Standard(@NonNull Parcel src) {
            mColor = src.readInt();
        }

        @Override
        public int getSpanTypeId() {
            return getSpanTypeIdInternal();
        }

        /** @hide */
        @Override
        public int getSpanTypeIdInternal() {
            return TextUtils.LINE_BACKGROUND_SPAN;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            writeToParcelInternal(dest, flags);
        }

        /** @hide */
        @Override
        public void writeToParcelInternal(@NonNull Parcel dest, int flags) {
            dest.writeInt(mColor);
        }

        /**
         * @return the color of this span.
         * @see Standard#Standard(int)
         */
        @ColorInt
        public final int getColor() {
            return mColor;
        }

        @Override
        public void drawBackground(@NonNull Canvas canvas, @NonNull Paint paint,
                @Px int left, @Px int right,
                @Px int top, @Px int baseline, @Px int bottom,
                @NonNull CharSequence text, int start, int end,
                int lineNumber) {
            final int originColor = paint.getColor();
            paint.setColor(mColor);
            canvas.drawRect(left, top, right, bottom, paint);
            paint.setColor(originColor);
        }
    }
}
