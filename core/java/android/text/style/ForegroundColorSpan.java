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
import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Changes the color of the text to which the span is attached.
 * <p>
 * For example, to set a green text color you would create a {@link
 * android.text.SpannableString} based on the text and set the span.
 * <pre>{@code
 * SpannableString string = new SpannableString("Text with a foreground color span");
 *string.setSpan(new ForegroundColorSpan(color), 12, 28, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}</pre>
 * <img src="{@docRoot}reference/android/images/text/style/foregroundcolorspan.png" />
 * <figcaption>Set a text color.</figcaption>
 */
public class ForegroundColorSpan extends CharacterStyle
        implements UpdateAppearance, ParcelableSpan {

    private final int mColor;

    /**
     * Creates a {@link ForegroundColorSpan} from a color integer.
     * <p>
     * To get the color integer associated with a particular color resource ID, use
     * {@link android.content.res.Resources#getColor(int, Resources.Theme)}
     *
     * @param color color integer that defines the text color
     */
    public ForegroundColorSpan(@ColorInt int color) {
        mColor = color;
    }

    /**
     * Creates a {@link ForegroundColorSpan} from a parcel.
     */
    public ForegroundColorSpan(@NonNull Parcel src) {
        mColor = src.readInt();
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /** @hide */
    @Override
    public int getSpanTypeIdInternal() {
        return TextUtils.FOREGROUND_COLOR_SPAN;
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
     * @return the foreground color of this span.
     * @see ForegroundColorSpan#ForegroundColorSpan(int)
     */
    @ColorInt
    public int getForegroundColor() {
        return mColor;
    }

    /**
     * Updates the color of the TextPaint to the foreground color.
     */
    @Override
    public void updateDrawState(@NonNull TextPaint textPaint) {
        textPaint.setColor(mColor);
    }

    @Override
    public String toString() {
        return "ForegroundColorSpan{color=#" + String.format("%08X", getForegroundColor()) + '}';
    }
}
