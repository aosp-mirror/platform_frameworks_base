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

import android.annotation.NonNull;
import android.service.usb.UsbAlsaDeviceProto;
import android.util.Slog;

import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.audio.AudioService;

/**
 * Represents the ALSA specification, and attributes of an ALSA device.
 */
public final class UsbAlsaDevice {
    private static final String TAG = "UsbAlsaDevice";
    protected static final boolean DEBUG = false;

    private final int mCardNum;
    private final int mDeviceNum;
    private final boolean mHasOutput;
    private final boolean mHasInput;

    private final boolean mIsInputHeadset;
    private final boolean mIsOutputHeadset;

    private final String mDeviceAddress;

    private String mDeviceName = "";
    private String mDeviceDescription = "";

    public UsbAlsaDevice(int card, int device, String deviceAddress,
            boolean hasOutput, boolean hasInput,
            boolean isInputHeadset, boolean isOutputHeadset) {
        mCardNum = card;
        mDeviceNum = device;
        mDeviceAddress = deviceAddress;
        mHasOutput = hasOutput;
        mHasInput = hasInput;
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
     * @returns the ALSA card/device address string.
     */
    public String getAlsaCardDeviceString() {
        if (mCardNum < 0 || mDeviceNum < 0) {
            Slog.e(TAG, "Invalid alsa card or device alsaCard: " + mCardNum
                        + " alsaDevice: " + mDeviceNum);
            return null;
        }
        return AudioService.makeAlsaAddressString(mCardNum, mDeviceNum);
    }

    /**
     * @returns true if the device supports output.
     */
    public boolean hasOutput() {
        return mHasOutput;
    }

    /**
     * @returns true if the device supports input (recording).
     */
    public boolean hasInput() {
        return mHasInput;
    }

    /**
     * @returns true if the device is a headset for purposes of input.
     */
    public boolean isInputHeadset() {
        return mIsInputHeadset;
    }

    /**
     * @returns true if the device is a headset for purposes of output.
     */
    public boolean isOutputHeadset() {
        return mIsOutputHeadset;
    }

    /**
     * @Override
     * @returns a string representation of the object.
     */
    public synchronized String toString() {
        return "UsbAlsaDevice: [card: " + mCardNum
            + ", device: " + mDeviceNum
            + ", name: " + mDeviceName
            + ", hasOutput: " + mHasOutput
            + ", hasInput: " + mHasInput + "]";
    }

    /**
     * Write a description of the device to a dump stream.
     */
    public synchronized void dump(@NonNull DualDumpOutputStream dump, String idName, long id) {
        long token = dump.start(idName, id);

        dump.write("card", UsbAlsaDeviceProto.CARD, mCardNum);
        dump.write("device", UsbAlsaDeviceProto.DEVICE, mDeviceNum);
        dump.write("name", UsbAlsaDeviceProto.NAME, mDeviceName);
        dump.write("has_output", UsbAlsaDeviceProto.HAS_PLAYBACK, mHasOutput);
        dump.write("has_input", UsbAlsaDeviceProto.HAS_CAPTURE, mHasInput);
        dump.write("address", UsbAlsaDeviceProto.ADDRESS, mDeviceAddress);

        dump.end(token);
    }

    // called by logDevices
    synchronized String toShortString() {
        return "[card:" + mCardNum + " device:" + mDeviceNum + " " + mDeviceName + "]";
    }

    synchronized String getDeviceName() {
        return mDeviceName;
    }

    synchronized void setDeviceNameAndDescription(String deviceName, String deviceDescription) {
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
                && mHasOutput == other.mHasOutput
                && mHasInput == other.mHasInput
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
        result = prime * result + (mHasOutput ? 0 : 1);
        result = prime * result + (mHasInput ? 0 : 1);
        result = prime * result + (mIsInputHeadset ? 0 : 1);
        result = prime * result + (mIsOutputHeadset ? 0 : 1);

        return result;
    }
}

