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

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Scales horizontally the size of the text to which it's attached by a certain factor.
 * <p>
 * Values > 1.0 will stretch the text wider. Values < 1.0 will stretch the text narrower.
 * <p>
 * For example, a <code>ScaleXSpan</code> that stretches the text size by 100% can be
 * constructed like this:
 * <pre>{@code
 * SpannableString string = new SpannableString("Text with ScaleX span");
 *string.setSpan(new ScaleXSpan(2f), 10, 16, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}</pre>
 * <img src="{@docRoot}reference/android/images/text/style/scalexspan.png" />
 * <figcaption>Text scaled by 100% with <code>ScaleXSpan</code>.</figcaption>
 */
public class ScaleXSpan extends MetricAffectingSpan implements ParcelableSpan {

    private final float mProportion;

    /**
     * Creates a {@link ScaleXSpan} based on a proportion. Values > 1.0 will stretch the text wider.
     * Values < 1.0 will stretch the text narrower.
     *
     * @param proportion the horizontal scale factor.
     */
    public ScaleXSpan(@FloatRange(from = 0) float proportion) {
        mProportion = proportion;
    }

    /**
     * Creates a {@link ScaleXSpan} from a parcel.
     */
    public ScaleXSpan(@NonNull Parcel src) {
        mProportion = src.readFloat();
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /** @hide */
    @Override
    public int getSpanTypeIdInternal() {
        return TextUtils.SCALE_X_SPAN;
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
        dest.writeFloat(mProportion);
    }

    /**
     * Get the horizontal scale factor for the text.
     *
     * @return the horizontal scale factor.
     */
    public float getScaleX() {
        return mProportion;
    }

    @Override
    public void updateDrawState(TextPaint ds) {
        ds.setTextScaleX(ds.getTextScaleX() * mProportion);
    }

    @Override
    public void updateMeasureState(TextPaint ds) {
        ds.setTextScaleX(ds.getTextScaleX() * mProportion);
    }
}
