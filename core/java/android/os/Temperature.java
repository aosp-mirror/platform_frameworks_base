/*
 * Copyright (c) 2017 The Android Open Source Project
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

package android.os;

/**
 * Temperature values used by IThermalService.
 */

/**
 * @hide
 */
public class Temperature implements Parcelable {
    /* Temperature value */
    private float mValue;
    /* A temperature type from HardwarePropertiesManager */
    private int mType;

    public Temperature() {
        mType = Integer.MIN_VALUE;
        mValue = HardwarePropertiesManager.UNDEFINED_TEMPERATURE;
    }

    public Temperature(float value, int type) {
        mValue = value;
        mType = type;
    }

    /**
     * Return the temperature value.
     * @return a temperature value in floating point.
     */
    public float getValue() {
        return mValue;
    }

    /**
     * Return the temperature type.
     * @return a temperature type:
     *         HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU, etc.
     */
    public int getType() {
        return mType;
    }

    /*
     * Parcel read/write code must be kept in sync with
     * frameworks/native/services/thermalservice/aidl/android/os/
     * Temperature.cpp
     */

    private Temperature(Parcel p) {
        readFromParcel(p);
    }

    /**
     * Fill in Temperature members from a Parcel.
     * @param p the parceled Temperature object.
     */
    public void readFromParcel(Parcel p) {
        mValue = p.readFloat();
        mType = p.readInt();
    }

    @Override
    public void writeToParcel(Parcel p, int flags) {
        p.writeFloat(mValue);
        p.writeInt(mType);
    }

    public static final Parcelable.Creator<Temperature> CREATOR =
            new Parcelable.Creator<Temperature>() {
        @Override
        public Temperature createFromParcel(Parcel p) {
            return new Temperature(p);
        }

        @Override
        public Temperature[] newArray(int size) {
            return new Temperature[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }
}
