/*
 * Copyright 2024 The Android Open Source Project
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

package android.view;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class to create and manage FrameRateCategoryRate for
 * categories Normal and High.
 * @hide
 */
public class FrameRateCategoryRate implements Parcelable {

    private final float mNormal;
    private final float mHigh;

    /**
     * Creates a FrameRateCategoryRate with the provided rates
     * for the categories Normal and High respectively;
     *
     * @param normal rate for the category Normal.
     * @param high rate for the category High.
     * @hide
     */
    public FrameRateCategoryRate(float normal, float high) {
        this.mNormal = normal;
        this.mHigh = high;
    }

    /**
     * @return the value for the category normal;
     * @hide
     */
    public float getNormal() {
        return mNormal;
    }

    /**
     * @return the value for the category high;
     * @hide
     */
    public float getHigh() {
        return mHigh;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof FrameRateCategoryRate)) {
            return false;
        }
        FrameRateCategoryRate that = (FrameRateCategoryRate) o;
        return mNormal == that.mNormal && mHigh == that.mHigh;

    }

    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + Float.hashCode(mNormal);
        result = 31 * result + Float.hashCode(mHigh);
        return result;
    }

    @Override
    public String toString() {
        return "FrameRateCategoryRate {"
                + "normal=" + mNormal
                + ", high=" + mHigh
                + '}';
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeFloat(mNormal);
        dest.writeFloat(mHigh);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<FrameRateCategoryRate> CREATOR =
            new Creator<>() {
                @Override
                public FrameRateCategoryRate createFromParcel(Parcel in) {
                    return new FrameRateCategoryRate(in.readFloat(), in.readFloat());
                }

                @Override
                public FrameRateCategoryRate[] newArray(int size) {
                    return new FrameRateCategoryRate[size];
                }
            };
}
