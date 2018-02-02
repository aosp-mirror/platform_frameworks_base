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

package com.android.server.usb;

/**
 * Represents the ALSA specification, and attributes of an ALSA device.
 */
public final class UsbAlsaDevice {
    private static final String TAG = "UsbAlsaDevice";
    protected static final boolean DEBUG = false;

    private final int mCardNum;
    private final int mDeviceNum;
    private final boolean mHasPlayback;
    private final boolean mHasCapture;

    private final boolean mIsInputHeadset;
    private final boolean mIsOutputHeadset;

    private final String mDeviceAddress;

    private String mDeviceName = "";
    private String mDeviceDescription = "";

    public UsbAlsaDevice(int card, int device, String deviceAddress,
            boolean hasPlayback, boolean hasCapture,
            boolean isInputHeadset, boolean isOutputHeadset) {
        mCardNum = card;
        mDeviceNum = device;
        mDeviceAddress = deviceAddress;
        mHasPlayback = hasPlayback;
        mHasCapture = hasCapture;
        mIsInputHeadset = isInputHeadset;
        mIsOutputHeadset = isOutputHeadset;
    }

    /**
     * @returns the ALSA card number associated with this peripheral.
     */
    public int getCardNum() {
        return mCardNum;
    }

    /**
     * @returns the ALSA device number associated with this peripheral.
     */
    public int getDeviceNum() {
        return mDeviceNum;
    }

    /**
     * @returns the USB device device address associated with this peripheral.
     */
    public String getDeviceAddress() {
        return mDeviceAddress;
    }

    /**
     * @returns true if the device supports playback.
     */
    public boolean hasPlayback() {
        return mHasPlayback;
    }

    /**
     * @returns true if the device supports capture (recording).
     */
    public boolean hasCapture() {
        return mHasCapture;
    }

    /**
     * @returns true if the device is a headset for purposes of capture.
     */
    public boolean isInputHeadset() {
        return mIsInputHeadset;
    }

    /**
     * @returns true if the device is a headset for purposes of playback.
     */
    public boolean isOutputHeadset() {
        return mIsOutputHeadset;
    }

    /**
     * @Override
     * @returns a string representation of the object.
     */
    public String toString() {
        return "UsbAlsaDevice: [card: " + mCardNum
            + ", device: " + mDeviceNum
            + ", name: " + mDeviceName
            + ", hasPlayback: " + mHasPlayback
            + ", hasCapture: " + mHasCapture + "]";
    }

    // called by logDevices
    String toShortString() {
        return "[card:" + mCardNum + " device:" + mDeviceNum + " " + mDeviceName + "]";
    }

    String getDeviceName() {
        return mDeviceName;
    }

    void setDeviceNameAndDescription(String deviceName, String deviceDescription) {
        mDeviceName = deviceName;
        mDeviceDescription = deviceDescription;
    }

    /**
     * @Override
     * @returns true if the objects are equivalent.
     */
    public boolean equals(Object obj) {
        if (!(obj instanceof UsbAlsaDevice)) {
            return false;
        }
        UsbAlsaDevice other = (UsbAlsaDevice) obj;
        return (mCardNum == other.mCardNum
                && mDeviceNum == other.mDeviceNum
                && mHasPlayback == other.mHasPlayback
                && mHasCapture == other.mHasCapture
                && mIsInputHeadset == other.mIsInputHeadset
                && mIsOutputHeadset == other.mIsOutputHeadset);
    }

    /**
     * @Override
     * @returns a hash code generated from the object contents.
     */
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + mCardNum;
        result = prime * result + mDeviceNum;
        result = prime * result + (mHasPlayback ? 0 : 1);
        result = prime * result + (mHasCapture ? 0 : 1);
        result = prime * result + (mIsInputHeadset ? 0 : 1);
        result = prime * result + (mIsOutputHeadset ? 0 : 1);

        return result;
    }
}

