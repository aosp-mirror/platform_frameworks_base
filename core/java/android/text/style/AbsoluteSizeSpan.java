/*
 * Copyright (C) 2008 The Android Open Source Project
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
 * A span that changes the size of the text it's attached to.
 * <p>
 * For example, the size of the text can be changed to 55dp like this:
 * <pre>{@code
 * SpannableString string = new SpannableString("Text with absolute size span");
 *string.setSpan(new AbsoluteSizeSpan(55, true), 10, 23, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}</pre>
 * <img src="{@docRoot}reference/android/images/text/style/absolutesizespan.png" />
 * <figcaption>Text with text size updated.</figcaption>
 */
public class AbsoluteSizeSpan extends MetricAffectingSpan implements ParcelableSpan {

    private final int mSize;
    private final boolean mDip;

    /**
     * Set the text size to <code>size</code> physical pixels.
     */
    public AbsoluteSizeSpan(int size) {
        this(size, false);
    }

    /**
     * Set the text size to <code>size</code> physical pixels, or to <code>size</code>
     * device-independent pixels if <code>dip</code> is true.
     */
    public AbsoluteSizeSpan(int size, boolean dip) {
        mSize = size;
        mDip = dip;
    }

    /**
     * Creates an {@link AbsoluteSizeSpan} from a parcel.
     */
    public AbsoluteSizeSpan(@NonNull Parcel src) {
        mSize = src.readInt();
        mDip = src.readInt() != 0;
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /** @hide */
    @Override
    public int getSpanTypeIdInternal() {
        return TextUtils.ABSOLUTE_SIZE_SPAN;
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
        dest.writeInt(mSize);
        dest.writeInt(mDip ? 1 : 0);
    }

    /**
     * Get the text size. This is in physical pixels if {@link #getDip()} returns false or in
     * device-independent pixels if {@link #getDip()} returns true.
     *
     * @return the text size, either in physical pixels or device-independent pixels.
     * @see AbsoluteSizeSpan#AbsoluteSizeSpan(int, boolean)
     */
    public int getSize() {
        return mSize;
    }

    /**
     * Returns whether the size is in device-independent pixels or not, depending on the
     * <code>dip</code> flag passed in {@link #AbsoluteSizeSpan(int, boolean)}
     *
     * @return <code>true</code> if the size is in device-independent pixels, <code>false</code>
     * otherwise
     *
     * @see #AbsoluteSizeSpan(int, boolean)
     */
    public boolean getDip() {
        return mDip;
    }

    @Override
    public void updateDrawState(@NonNull TextPaint ds) {
        if (mDip) {
            ds.setTextSize(mSize * ds.density);
        } else {
            ds.setTextSize(mSize);
        }
    }

    @Override
    public void updateMeasureState(@NonNull TextPaint ds) {
        if (mDip) {
            ds.setTextSize(mSize * ds.density);
        } else {
            ds.setTextSize(mSize);
        }
    }


    @Override
    public String toString() {
        return "AbsoluteSizeSpan{size=" + getSize() + ", isDip=" + getDip() + '}';
    }
}
