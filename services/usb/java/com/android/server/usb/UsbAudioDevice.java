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
 * See the License for the specific language governing permissions an
 * limitations under the License.
 */

package com.android.server.usb;

public final class UsbAudioDevice {
    private static final String TAG = "UsbAudioDevice";
    protected static final boolean DEBUG = false;

    public int mCard;
    public int mDevice;
    public boolean mHasPlayback;
    public boolean mHasCapture;

    // Device "class" flags
    public static final int kAudioDeviceClassMask = 0x00FFFFFF;
    public static final int kAudioDeviceClass_Undefined = 0x00000000;
    public static final int kAudioDeviceClass_Internal = 0x00000001;
    public static final int kAudioDeviceClass_External = 0x00000002;
    // Device meta-data flags
    public static final int kAudioDeviceMetaMask = 0xFF000000;
    public static final int kAudioDeviceMeta_Alsa = 0x80000000;
    // This member is a combination of the above bit-flags
    public int mDeviceClass;

    public String mDeviceName = "";
    public String mDeviceDescription = "";

    public UsbAudioDevice(int card, int device,
            boolean hasPlayback, boolean hasCapture, int deviceClass) {
        mCard = card;
        mDevice = device;
        mHasPlayback = hasPlayback;
        mHasCapture = hasCapture;
        mDeviceClass = deviceClass;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("UsbAudioDevice: [card: " + mCard);
        sb.append(", device: " + mDevice);
        sb.append(", name: " + mDeviceName);
        sb.append(", hasPlayback: " + mHasPlayback);
        sb.append(", hasCapture: " + mHasCapture);
        sb.append(", class: 0x" + Integer.toHexString(mDeviceClass) + "]");
        return sb.toString();
    }

    public String toShortString() {
        return "[card:" + mCard + " device:" + mDevice + " " + mDeviceName + "]";
    }
}

