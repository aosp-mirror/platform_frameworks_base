/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.input;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Encapsulates calibration data for input devices.
 *
 * @hide
 */
public class TouchCalibration implements Parcelable {

    public static final TouchCalibration IDENTITY = new TouchCalibration();

    public static final Parcelable.Creator<TouchCalibration> CREATOR
            = new Parcelable.Creator<TouchCalibration>() {
        public TouchCalibration createFromParcel(Parcel in) {
            return new TouchCalibration(in);
        }

        public TouchCalibration[] newArray(int size) {
            return new TouchCalibration[size];
        }
    };

    private final float mXScale, mXYMix, mXOffset;
    private final float mYXMix, mYScale, mYOffset;

    /**
     * Create a new TouchCalibration initialized to the identity transformation.
     */
    public TouchCalibration() {
        this(1,0,0,0,1,0);
    }

    /**
     * Create a new TouchCalibration from affine transformation paramters.
     * @param xScale   Influence of input x-axis value on output x-axis value.
     * @param xyMix    Influence of input y-axis value on output x-axis value.
     * @param xOffset  Constant offset to be applied to output x-axis value.
     * @param yXMix    Influence of input x-axis value on output y-axis value.
     * @param yScale   Influence of input y-axis value on output y-axis value.
     * @param yOffset  Constant offset to be applied to output y-axis value.
     */
    public TouchCalibration(float xScale, float xyMix, float xOffset,
            float yxMix, float yScale, float yOffset) {
        mXScale  = xScale;
        mXYMix   = xyMix;
        mXOffset = xOffset;
        mYXMix   = yxMix;
        mYScale  = yScale;
        mYOffset = yOffset;
    }

    public TouchCalibration(Parcel in) {
        mXScale  = in.readFloat();
        mXYMix   = in.readFloat();
        mXOffset = in.readFloat();
        mYXMix   = in.readFloat();
        mYScale  = in.readFloat();
        mYOffset = in.readFloat();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeFloat(mXScale);
        dest.writeFloat(mXYMix);
        dest.writeFloat(mXOffset);
        dest.writeFloat(mYXMix);
        dest.writeFloat(mYScale);
        dest.writeFloat(mYOffset);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public float[] getAffineTransform() {
        return new float[] { mXScale, mXYMix, mXOffset, mYXMix, mYScale, mYOffset };
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        } else if (obj instanceof TouchCalibration) {
            TouchCalibration cal = (TouchCalibration)obj;

            return (cal.mXScale  == mXScale)  &&
                   (cal.mXYMix   == mXYMix)   &&
                   (cal.mXOffset == mXOffset) &&
                   (cal.mYXMix   == mYXMix)   &&
                   (cal.mYScale  == mYScale)  &&
                   (cal.mYOffset == mYOffset);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return Float.floatToIntBits(mXScale)  ^
               Float.floatToIntBits(mXYMix)   ^
               Float.floatToIntBits(mXOffset) ^
               Float.floatToIntBits(mYXMix)   ^
               Float.floatToIntBits(mYScale)  ^
               Float.floatToIntBits(mYOffset);
    }
}
