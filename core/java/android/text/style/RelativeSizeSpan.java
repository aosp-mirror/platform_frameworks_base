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
 * Uniformly scales the size of the text to which it's attached by a certain proportion.
 * <p>
 * For example, a <code>RelativeSizeSpan</code> that increases the text size by 50% can be
 * constructed like this:
 * <pre>{@code
 *  SpannableString string = new SpannableString("Text with relative size span");
 *string.setSpan(new RelativeSizeSpan(1.5f), 10, 24, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}</pre>
 * <img src="{@docRoot}reference/android/images/text/style/relativesizespan.png" />
 * <figcaption>Text increased by 50% with <code>RelativeSizeSpan</code>.</figcaption>
 */
public class RelativeSizeSpan extends MetricAffectingSpan implements ParcelableSpan {

    private final float mProportion;

    /**
     * Creates a {@link RelativeSizeSpan} based on a proportion.
     *
     * @param proportion the proportion with which the text is scaled.
     */
    public RelativeSizeSpan(@FloatRange(from = 0) float proportion) {
        mProportion = proportion;
    }

    /**
     * Creates a {@link RelativeSizeSpan} from a parcel.
     */
    public RelativeSizeSpan(@NonNull Parcel src) {
        mProportion = src.readFloat();
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /** @hide */
    @Override
    public int getSpanTypeIdInternal() {
        return TextUtils.RELATIVE_SIZE_SPAN;
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
     * @return the proportion with which the text size is changed.
     */
    public float getSizeChange() {
        return mProportion;
    }

    @Override
    public void updateDrawState(@NonNull TextPaint ds) {
        ds.setTextSize(ds.getTextSize() * mProportion);
    }

    @Override
    public void updateMeasureState(@NonNull TextPaint ds) {
        ds.setTextSize(ds.getTextSize() * mProportion);
    }

    @Override
    public String toString() {
        return "RelativeSizeSpan{proportion=" + getSizeChange() + '}';
    }
}
