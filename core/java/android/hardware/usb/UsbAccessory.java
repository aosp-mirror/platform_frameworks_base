/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.hardware.usb;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * A class representing a USB accessory.
 * @hide
 */
public class UsbAccessory implements Parcelable {

    private static final String TAG = "UsbAccessory";

    private final String mManufacturer;
    private final String mModel;
    private final String mType;
    private final String mVersion;

    /**
     * UsbAccessory should only be instantiated by UsbService implementation
     * @hide
     */
    public UsbAccessory(String manufacturer, String model, String type, String version) {
        mManufacturer = manufacturer;
        mModel = model;
        mType = type;
        mVersion = version;
    }

    /**
     * UsbAccessory should only be instantiated by UsbService implementation
     * @hide
     */
    public UsbAccessory(String[] strings) {
        mManufacturer = strings[0];
        mModel = strings[1];
        mType = strings[2];
        mVersion = strings[3];
    }

    /**
     * Returns the manufacturer of the accessory.
     *
     * @return the accessory manufacturer
     */
    public String getManufacturer() {
        return mManufacturer;
    }

    /**
     * Returns the model name of the accessory.
     *
     * @return the accessory model
     */
    public String getModel() {
        return mModel;
    }

    /**
     * Returns the type of the accessory.
     *
     * @return the accessory type
     */
    public String getType() {
        return mType;
    }

    /**
     * Returns the version of the accessory.
     *
     * @return the accessory version
     */
    public String getVersion() {
        return mVersion;
    }

    private static boolean compare(String s1, String s2) {
        if (s1 == null) return (s2 == null);
        return s1.equals(s2);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof UsbAccessory) {
            UsbAccessory accessory = (UsbAccessory)obj;
            return (compare(mManufacturer, accessory.getManufacturer()) &&
                    compare(mModel, accessory.getModel()) &&
                    compare(mType, accessory.getType()) &&
                    compare(mVersion, accessory.getVersion()));
        }
        return false;
    }

    @Override
    public String toString() {
        return "UsbAccessory[mManufacturer=" + mManufacturer +
                            ", mModel=" + mModel +
                            ", mType=" + mType +
                            ", mVersion=" + mVersion + "]";
    }

    public static final Parcelable.Creator<UsbAccessory> CREATOR =
        new Parcelable.Creator<UsbAccessory>() {
        public UsbAccessory createFromParcel(Parcel in) {
            String manufacturer = in.readString();
            String model = in.readString();
            String type = in.readString();
            String version = in.readString();
            return new UsbAccessory(manufacturer, model, type, version);
        }

        public UsbAccessory[] newArray(int size) {
            return new UsbAccessory[size];
        }
    };

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mManufacturer);
        parcel.writeString(mModel);
        parcel.writeString(mType);
        parcel.writeString(mVersion);
   }
}
