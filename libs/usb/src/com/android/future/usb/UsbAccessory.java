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

package com.android.future.usb;

/**
 * A class representing a USB accessory.
 */
public class UsbAccessory {

    private final String mManufacturer;
    private final String mModel;
    private final String mDescription;
    private final String mVersion;
    private final String mUri;
    private final String mSerial;

    /* package */ UsbAccessory(android.hardware.usb.UsbAccessory accessory) {
        mManufacturer = accessory.getManufacturer();
        mModel = accessory.getModel();
        mDescription = accessory.getDescription();
        mVersion = accessory.getVersion();
        mUri = accessory.getUri();
        mSerial = accessory.getSerial();
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
     * Returns a user visible description of the accessory.
     *
     * @return the accessory description
     */
    public String getDescription() {
        return mDescription;
    }

    /**
     * Returns the version of the accessory.
     *
     * @return the accessory version
     */
    public String getVersion() {
        return mVersion;
    }

    /**
     * Returns the URI for the accessory.
     * This is an optional URI that might show information about the accessory
     * or provide the option to download an application for the accessory
     *
     * @return the accessory URI
     */
    public String getUri() {
        return mUri;
    }

    /**
     * Returns the unique serial number for the accessory.
     * This is an optional serial number that can be used to differentiate
     * between individual accessories of the same model and manufacturer
     *
     * @return the unique serial number
     */
    public String getSerial() {
        return mSerial;
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
                    compare(mDescription, accessory.getDescription()) &&
                    compare(mVersion, accessory.getVersion()) &&
                    compare(mUri, accessory.getUri()) &&
                    compare(mSerial, accessory.getSerial()));
        }
        return false;
    }

    @Override
    public int hashCode() {
        return ((mManufacturer == null ? 0 : mManufacturer.hashCode()) ^
                (mModel == null ? 0 : mModel.hashCode()) ^
                (mDescription == null ? 0 : mDescription.hashCode()) ^
                (mVersion == null ? 0 : mVersion.hashCode()) ^
                (mUri == null ? 0 : mUri.hashCode()) ^
                (mSerial == null ? 0 : mSerial.hashCode()));
    }

    @Override
    public String toString() {
        return "UsbAccessory[mManufacturer=" + mManufacturer +
                            ", mModel=" + mModel +
                            ", mDescription=" + mDescription +
                            ", mVersion=" + mVersion +
                            ", mUri=" + mUri +
                            ", mSerial=" + mSerial + "]";
    }
}
