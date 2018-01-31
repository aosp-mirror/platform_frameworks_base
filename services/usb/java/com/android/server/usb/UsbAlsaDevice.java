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
 * Represents an instance of an Alsa-supported USB peripheral.
 */
public final class UsbAlsaDevice {
    private static final String TAG = "UsbAlsaDevice";
    protected static final boolean DEBUG = false;

    public final int mCard;
    public final int mDevice;
    public final boolean mHasPlayback;
    public final boolean mHasCapture;

    // Device "class" flags
    static final int AUDIO_DEVICE_CLASS_MASK = 0x00FFFFFF;
    static final int AUDIO_DEVICE_CLASS_UNDEFINED = 0x00000000;
    static final int AUDIO_DEVICE_CLASS_INTERNAL = 0x00000001;
    static final int AUDIO_DEVICE_CLASS_EXTERNAL = 0x00000002;
    // Device meta-data flags
    static final int AUDIO_DEVICE_META_MASK = 0xFF000000;
    static final int AUDIO_DEVICE_META_ALSA = 0x80000000;
    // This member is a combination of the above bit-flags
    public final int mDeviceClass;

    private String mDeviceName = "";
    private String mDeviceDescription = "";

    public UsbAlsaDevice(int card, int device,
            boolean hasPlayback, boolean hasCapture, int deviceClass) {
        mCard = card;
        mDevice = device;
        mHasPlayback = hasPlayback;
        mHasCapture = hasCapture;
        mDeviceClass = deviceClass;
    }

    /**
     * @Override
     * @return a String representation of the object.
     */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("UsbAlsaDevice: [card: " + mCard);
        sb.append(", device: " + mDevice);
        sb.append(", name: " + mDeviceName);
        sb.append(", hasPlayback: " + mHasPlayback);
        sb.append(", hasCapture: " + mHasCapture);
        sb.append(", class: 0x" + Integer.toHexString(mDeviceClass) + "]");
        return sb.toString();
    }

    // called by logDevices
    String toShortString() {
        return "[card:" + mCard + " device:" + mDevice + " " + mDeviceName + "]";
    }

    String getDeviceName() {
        return mDeviceName;
    }

    void setDeviceNameAndDescription(String deviceName, String deviceDescription) {
        mDeviceName = deviceName;
        mDeviceDescription = deviceDescription;
    }

}

