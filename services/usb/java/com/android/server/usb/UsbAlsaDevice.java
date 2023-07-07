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
import android.media.AudioDeviceAttributes;
import android.media.AudioSystem;
import android.media.IAudioService;
import android.os.RemoteException;
import android.service.usb.UsbAlsaDeviceProto;
import android.util.Slog;

import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.audio.AudioService;

import java.util.Arrays;

/**
 * Represents the ALSA specification, and attributes of an ALSA device.
 */
public final class UsbAlsaDevice {
    private static final String TAG = "UsbAlsaDevice";
    protected static final boolean DEBUG = false;

    private final int mCardNum;
    private final int mDeviceNum;
    private final String mAlsaCardDeviceString;
    private final String mDeviceAddress;

    // The following two constant will be used as index to access arrays.
    private static final int INPUT = 0;
    private static final int OUTPUT = 1;
    private static final int NUM_DIRECTIONS = 2;
    private static final String[] DIRECTION_STR = {"INPUT", "OUTPUT"};
    private final boolean[] mHasDevice = new boolean[NUM_DIRECTIONS];

    private final boolean[] mIsHeadset = new boolean[NUM_DIRECTIONS];
    private final boolean mIsDock;
    private final int[] mDeviceType = new int[NUM_DIRECTIONS];
    private boolean[] mIsSelected = new boolean[NUM_DIRECTIONS];
    private int[] mState = new int[NUM_DIRECTIONS];
    private UsbAlsaJackDetector mJackDetector;
    private IAudioService mAudioService;

    private String mDeviceName = "";
    private String mDeviceDescription = "";

    private boolean mHasJackDetect = true;

    public UsbAlsaDevice(IAudioService audioService, int card, int device, String deviceAddress,
            boolean hasOutput, boolean hasInput,
            boolean isInputHeadset, boolean isOutputHeadset, boolean isDock) {
        mAudioService = audioService;
        mCardNum = card;
        mDeviceNum = device;
        mDeviceAddress = deviceAddress;
        mHasDevice[OUTPUT] = hasOutput;
        mHasDevice[INPUT] = hasInput;
        mIsHeadset[INPUT] = isInputHeadset;
        mIsHeadset[OUTPUT] = isOutputHeadset;
        mIsDock = isDock;
        initDeviceType();
        mAlsaCardDeviceString = getAlsaCardDeviceString();
    }

    /**
     * @return the ALSA card number associated with this peripheral.
     */
    public int getCardNum() {
        return mCardNum;
    }

    /**
     * @return the ALSA device number associated with this peripheral.
     */
    public int getDeviceNum() {
        return mDeviceNum;
    }

    /**
     * @return the USB device device address associated with this peripheral.
     */
    public String getDeviceAddress() {
        return mDeviceAddress;
    }

    /**
     * @return the ALSA card/device address string.
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
     * @return true if the device supports output.
     */
    public boolean hasOutput() {
        return mHasDevice[OUTPUT];
    }

    /**
     * @return true if the device supports input (recording).
     */
    public boolean hasInput() {
        return mHasDevice[INPUT];
    }

    /**
     * @return true if the device is a headset for purposes of output.
     */
    public boolean isOutputHeadset() {
        return mIsHeadset[OUTPUT];
    }

    /**
     * @return true if the device is a headset for purposes of input.
     */
    public boolean isInputHeadset() {
        return mIsHeadset[INPUT];
    }

    /**
     * @return true if the device is a USB dock.
     */
    public boolean isDock() {
        return mIsDock;
    }

    /**
     * @return true if input jack is detected or jack detection is not supported.
     */
    private synchronized boolean isInputJackConnected() {
        if (mJackDetector == null) {
            return true;  // If jack detect isn't supported, say it's connected.
        }
        return mJackDetector.isInputJackConnected();
    }

    /**
     * @return true if input jack is detected or jack detection is not supported.
     */
    private synchronized boolean isOutputJackConnected() {
        if (mJackDetector == null) {
            return true;  // if jack detect isn't supported, say it's connected.
        }
        return mJackDetector.isOutputJackConnected();
    }

    /** Begins a jack-detection thread. */
    private synchronized void startJackDetect() {
        if (mJackDetector != null) {
            return;
        }
        if (!mHasJackDetect) {
            return;
        }
        // If no jack detect capabilities exist, mJackDetector will be null.
        mJackDetector = UsbAlsaJackDetector.startJackDetect(this);
        if (mJackDetector == null) {
            mHasJackDetect = false;
        }
    }

    /** Stops a jack-detection thread. */
    private synchronized void stopJackDetect() {
        if (mJackDetector != null) {
            mJackDetector.pleaseStop();
        }
        mJackDetector = null;
    }

    /** Start using this device as the selected USB Audio Device. */
    public synchronized void start() {
        startOutput();
        startInput();
    }

    /** Start using this device as the selected USB input device. */
    public synchronized void startInput() {
        startDevice(INPUT);
    }

    /** Start using this device as selected USB output device. */
    public synchronized void startOutput() {
        startDevice(OUTPUT);
    }

    private void startDevice(int direction) {
        if (mIsSelected[direction]) {
            return;
        }
        mIsSelected[direction] = true;
        mState[direction] = 0;
        startJackDetect();
        updateWiredDeviceConnectionState(direction, true /*enable*/);
    }

    /** Stop using this device as the selected USB Audio Device. */
    public synchronized void stop() {
        stopOutput();
        stopInput();
    }

    /** Stop using this device as the selected USB input device. */
    public synchronized void stopInput() {
        if (!mIsSelected[INPUT]) {
            return;
        }
        if (!mIsSelected[OUTPUT]) {
            // Stop jack detection when both input and output are stopped
            stopJackDetect();
        }
        updateInputWiredDeviceConnectionState(false /*enable*/);
        mIsSelected[INPUT] = false;
    }

    /** Stop using this device as the selected USB output device. */
    public synchronized void stopOutput() {
        if (!mIsSelected[OUTPUT]) {
            return;
        }
        if (!mIsSelected[INPUT]) {
            // Stop jack detection when both input and output are stopped
            stopJackDetect();
        }
        updateOutputWiredDeviceConnectionState(false /*enable*/);
        mIsSelected[OUTPUT] = false;
    }

    private void initDeviceType() {
        mDeviceType[INPUT] = mHasDevice[INPUT]
                ? (mIsHeadset[INPUT] ? AudioSystem.DEVICE_IN_USB_HEADSET
                                     : AudioSystem.DEVICE_IN_USB_DEVICE)
                : AudioSystem.DEVICE_NONE;
        mDeviceType[OUTPUT] = mHasDevice[OUTPUT]
                ? (mIsDock ? AudioSystem.DEVICE_OUT_DGTL_DOCK_HEADSET
                           : (mIsHeadset[OUTPUT] ? AudioSystem.DEVICE_OUT_USB_HEADSET
                                                 : AudioSystem.DEVICE_OUT_USB_DEVICE))
                : AudioSystem.DEVICE_NONE;
    }

    /**
     * @return the output device type that will be used to notify AudioService about device
     *         connection. If there is no output on this device, {@link AudioSystem#DEVICE_NONE}
     *         will be returned.
     */
    public int getOutputDeviceType() {
        return mDeviceType[OUTPUT];
    }

    /**
     * @return the input device type that will be used to notify AudioService about device
     *         connection. If there is no input on this device, {@link AudioSystem#DEVICE_NONE}
     *         will be returned.
     */
    public int getInputDeviceType() {
        return mDeviceType[INPUT];
    }

    private boolean updateWiredDeviceConnectionState(int direction, boolean enable) {
        if (!mIsSelected[direction]) {
            Slog.e(TAG, "Updating wired device connection state on unselected device");
            return false;
        }
        if (mDeviceType[direction] == AudioSystem.DEVICE_NONE) {
            Slog.d(TAG,
                    "Unable to set device connection state as " + DIRECTION_STR[direction]
                    + " device type is none");
            return false;
        }
        if (mAlsaCardDeviceString == null) {
            Slog.w(TAG, "Failed to update " + DIRECTION_STR[direction] + " device connection "
                    + "state failed as alsa card device string is null");
            return false;
        }
        if (DEBUG) {
            Slog.d(TAG, "pre-call device:0x" + Integer.toHexString(mDeviceType[direction])
                    + " addr:" + mAlsaCardDeviceString
                    + " name:" + mDeviceName);
        }
        boolean connected = direction == INPUT ? isInputJackConnected() : isOutputJackConnected();
        Slog.i(TAG, DIRECTION_STR[direction] + " JACK connected: " + connected);
        int state = (enable && connected) ? 1 : 0;
        if (state != mState[direction]) {
            mState[direction] = state;
            AudioDeviceAttributes attributes = new AudioDeviceAttributes(
                    mDeviceType[direction], mAlsaCardDeviceString, mDeviceName);
            try {
                mAudioService.setWiredDeviceConnectionState(attributes, state, TAG);
            } catch (RemoteException e) {
                Slog.e(TAG, "RemoteException in setWiredDeviceConnectionState for "
                        + DIRECTION_STR[direction]);
                return false;
            }
        }
        return true;
    }

    /**
     * Notify AudioService about the input device connection state.
     *
     * @param enable true to notify the device as connected.
     * @return true only when it successfully notifies AudioService about the device
     *         connection state.
     */
    public synchronized boolean updateInputWiredDeviceConnectionState(boolean enable) {
        return updateWiredDeviceConnectionState(INPUT, enable);
    }

    /**
     * Notify AudioService about the output device connection state.
     *
     * @param enable true to notify the device as connected.
     * @return true only when it successfully notifies AudioService about the device
     *         connection state.
     */
    public synchronized boolean updateOutputWiredDeviceConnectionState(boolean enable) {
        return updateWiredDeviceConnectionState(OUTPUT, enable);
    }

    /**
     * @Override
     * @return a string representation of the object.
     */
    public synchronized String toString() {
        return "UsbAlsaDevice: [card: " + mCardNum
            + ", device: " + mDeviceNum
            + ", name: " + mDeviceName
            + ", hasOutput: " + mHasDevice[OUTPUT]
            + ", hasInput: " + mHasDevice[INPUT] + "]";
    }

    /**
     * Write a description of the device to a dump stream.
     */
    public synchronized void dump(@NonNull DualDumpOutputStream dump, String idName, long id) {
        long token = dump.start(idName, id);

        dump.write("card", UsbAlsaDeviceProto.CARD, mCardNum);
        dump.write("device", UsbAlsaDeviceProto.DEVICE, mDeviceNum);
        dump.write("name", UsbAlsaDeviceProto.NAME, mDeviceName);
        dump.write("has_output", UsbAlsaDeviceProto.HAS_PLAYBACK, mHasDevice[OUTPUT]);
        dump.write("has_input", UsbAlsaDeviceProto.HAS_CAPTURE, mHasDevice[INPUT]);
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
     * @return true if the objects are equivalent.
     */
    public boolean equals(Object obj) {
        if (!(obj instanceof UsbAlsaDevice)) {
            return false;
        }
        UsbAlsaDevice other = (UsbAlsaDevice) obj;
        return (mCardNum == other.mCardNum
                && mDeviceNum == other.mDeviceNum
                && Arrays.equals(mHasDevice, other.mHasDevice)
                && Arrays.equals(mIsHeadset, other.mIsHeadset)
                && mIsDock == other.mIsDock);
    }

    /**
     * @Override
     * @return a hash code generated from the object contents.
     */
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + mCardNum;
        result = prime * result + mDeviceNum;
        result = prime * result + (mHasDevice[OUTPUT] ? 0 : 1);
        result = prime * result + (mHasDevice[INPUT] ? 0 : 1);
        result = prime * result + (mIsHeadset[INPUT] ? 0 : 1);
        result = prime * result + (mIsHeadset[OUTPUT] ? 0 : 1);
        result = prime * result + (mIsDock ? 0 : 1);

        return result;
    }
}

