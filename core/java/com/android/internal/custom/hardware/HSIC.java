/*
 * Copyright (C) 2016 The CyanogenMod Project
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
package com.android.internal.custom.hardware;

import android.graphics.Color;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.Locale;

public class HSIC implements Parcelable {

    private final float mHue;
    private final float mSaturation;
    private final float mIntensity;
    private final float mContrast;
    private final float mSaturationThreshold;

    public HSIC(float hue, float saturation, float intensity,
                float contrast, float saturationThreshold) {
        mHue = hue;
        mSaturation = saturation;
        mIntensity = intensity;
        mContrast = contrast;
        mSaturationThreshold = saturationThreshold;
    }

    public float getHue() {
        return mHue;
    }

    public float getSaturation() {
        return mSaturation;
    }

    public float getIntensity() {
        return mIntensity;
    }

    public float getContrast() {
        return mContrast;
    }

    public float getSaturationThreshold() {
        return mSaturationThreshold;
    }

    public String flatten() {
        return String.format(Locale.US, "%f|%f|%f|%f|%f", mHue, mSaturation,
                mIntensity, mContrast, mSaturationThreshold);
    }

    public static HSIC unflattenFrom(String flat) throws NumberFormatException {
        final String[] unflat = TextUtils.split(flat, "\\|");
        if (unflat.length != 4 && unflat.length != 5) {
            throw new NumberFormatException("Failed to unflatten HSIC values: " + flat);
        }
        return new HSIC(Float.parseFloat(unflat[0]), Float.parseFloat(unflat[1]),
                Float.parseFloat(unflat[2]), Float.parseFloat(unflat[3]),
                unflat.length == 5 ? Float.parseFloat(unflat[4]) : 0.0f);
    }

    public int[] toRGB() {
        final int c = Color.HSVToColor(toFloatArray());
        return new int[] { Color.red(c), Color.green(c), Color.blue(c) };
    }

    public float[] toFloatArray() {
        return new float[] { mHue, mSaturation, mIntensity, mContrast, mSaturationThreshold };
    }

    public static HSIC fromFloatArray(float[] hsic) {
        if (hsic.length == 5) {
            return new HSIC(hsic[0], hsic[1], hsic[2], hsic[3], hsic[4]);
        } else if (hsic.length == 4) {
            return new HSIC(hsic[0], hsic[1], hsic[2], hsic[3], 0.0f);
        }
        return null;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "HSIC={ hue=%f saturation=%f intensity=%f " +
                             "contrast=%f saturationThreshold=%f }",
                mHue, mSaturation, mIntensity, mContrast, mSaturationThreshold);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeFloatArray(toFloatArray());
    }

    /**
     * @hide
     */
    public static final Parcelable.Creator<HSIC> CREATOR =
            new Parcelable.Creator<HSIC>() {
                public HSIC createFromParcel(Parcel in) {
                    float[] fromParcel = new float[5];
                    in.readFloatArray(fromParcel);
                    return HSIC.fromFloatArray(fromParcel);
                }

                @Override
                public HSIC[] newArray(int size) {
                    return new HSIC[size];
                }
            };
};
