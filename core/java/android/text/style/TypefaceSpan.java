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

import android.annotation.NonNull;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Changes the typeface family of the text to which the span is attached. Examples of typeface
 * family include "monospace", "serif", and "sans-serif".
 * <p>
 * For example, change the typeface of a text to "monospace" like this:
 * <pre>
 * SpannableString string = new SpannableString("Text with typeface span");
 * string.setSpan(new TypefaceSpan("monospace"), 10, 18, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
 * </pre>
 * <img src="{@docRoot}reference/android/images/text/style/typefacespan.png" />
 * <figcaption>Text with "monospace" typeface family.</figcaption>
 */
public class TypefaceSpan extends MetricAffectingSpan implements ParcelableSpan {

    private final String mFamily;

    /**
     * Constructs a {@link TypefaceSpan} based on a font family.
     *
     * @param family The font family for this typeface. Examples include
     *               "monospace", "serif", and "sans-serif".
     */
    public TypefaceSpan(String family) {
        mFamily = family;
    }

    public TypefaceSpan(@NonNull Parcel src) {
        mFamily = src.readString();
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /** @hide */
    @Override
    public int getSpanTypeIdInternal() {
        return TextUtils.TYPEFACE_SPAN;
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
        dest.writeString(mFamily);
    }

    /**
     * Returns the font family name.
     */
    public String getFamily() {
        return mFamily;
    }

    @Override
    public void updateDrawState(@NonNull TextPaint textPaint) {
        apply(textPaint, mFamily);
    }

    @Override
    public void updateMeasureState(@NonNull TextPaint textPaint) {
        apply(textPaint, mFamily);
    }

    private static void apply(@NonNull Paint paint, String family) {
        int oldStyle;

        Typeface old = paint.getTypeface();
        if (old == null) {
            oldStyle = 0;
        } else {
            oldStyle = old.getStyle();
        }

        Typeface tf = Typeface.create(family, oldStyle);
        int fake = oldStyle & ~tf.getStyle();

        if ((fake & Typeface.BOLD) != 0) {
            paint.setFakeBoldText(true);
        }

        if ((fake & Typeface.ITALIC) != 0) {
            paint.setTextSkewX(-0.25f);
        }

        paint.setTypeface(tf);
    }
}
