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
import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * The span that moves the position of the text baseline higher.
 * <p>
 * The span can be used like this:
 * <pre>{@code
 *  SpannableString string = new SpannableString("1st example");
 *string.setSpan(new SuperscriptSpan(), 1, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}</pre>
 * <img src="{@docRoot}reference/android/images/text/style/superscriptspan.png" />
 * <figcaption>Text with <code>SuperscriptSpan</code>.</figcaption>
 * Note: Since the span affects the position of the text, if the text is on the first line of a
 * TextView, it may appear cut. This can be avoided by decreasing the text size with an {@link
 * AbsoluteSizeSpan}
 */
public class SuperscriptSpan extends MetricAffectingSpan implements ParcelableSpan {
    /**
     * Creates a {@link SuperscriptSpan}.
     */
    public SuperscriptSpan() {
    }

    /**
     * Creates a {@link SuperscriptSpan} from a parcel.
     */
    public SuperscriptSpan(@NonNull Parcel src) {
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /** @hide */
    @Override
    public int getSpanTypeIdInternal() {
        return TextUtils.SUPERSCRIPT_SPAN;
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
    }

    @Override
    public void updateDrawState(@NonNull TextPaint textPaint) {
        textPaint.baselineShift += (int) (textPaint.ascent() / 2);
    }

    @Override
    public void updateMeasureState(@NonNull TextPaint textPaint) {
        textPaint.baselineShift += (int) (textPaint.ascent() / 2);
    }
}
