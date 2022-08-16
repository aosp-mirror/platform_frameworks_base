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

/**
 * Represents the ALSA specification, and attributes of an ALSA device.
 */
public final class UsbAlsaDevice {
    private static final String TAG = "UsbAlsaDevice";
    protected static final boolean DEBUG = false;

    private final int mCardNum;
    private final int mDeviceNum;
    private final String mDeviceAddress;
    private final boolean mHasOutput;
    private final boolean mHasInput;

    private final boolean mIsInputHeadset;
    private final boolean mIsOutputHeadset;
    private final boolean mIsDock;

    private boolean mSelected = false;
    private int mOutputState;
    private int mInputState;
    private UsbAlsaJackDetector mJackDetector;
    private IAudioService mAudioService;

    private String mDeviceName = "";
    private String mDeviceDescription = "";

    public UsbAlsaDevice(IAudioService audioService, int card, int device, String deviceAddress,
            boolean hasOutput, boolean hasInput,
            boolean isInputHeadset, boolean isOutputHeadset, boolean isDock) {
        mAudioService = audioService;
        mCardNum = card;
        mDeviceNum = device;
        mDeviceAddress = deviceAddress;
        mHasOutput = hasOutput;
        mHasInput = hasInput;
        mIsInputHeadset = isInputHeadset;
        mIsOutputHeadset = isOutputHeadset;
        mIsDock = isDock;
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
        return mHasOutput;
    }

    /**
     * @return true if the device supports input (recording).
     */
    public boolean hasInput() {
        return mHasInput;
    }

    /**
     * @return true if the device is a headset for purposes of input.
     */
    public boolean isInputHeadset() {
        return mIsInputHeadset;
    }

    /**
     * @return true if the device is a headset for purposes of output.
     */
    public boolean isOutputHeadset() {
        return mIsOutputHeadset;
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
        // If no jack detect capabilities exist, mJackDetector will be null.
        mJackDetector = UsbAlsaJackDetector.startJackDetect(this);
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
        mSelected = true;
        mInputState = 0;
        mOutputState = 0;
        startJackDetect();
        updateWiredDeviceConnectionState(true);
    }

    /** Stop using this device as the selected USB Audio Device. */
    public synchronized void stop() {
        stopJackDetect();
        updateWiredDeviceConnectionState(false);
        mSelected = false;
    }

    /** Updates AudioService with the connection state of the alsaDevice.
     *  Checks ALSA Jack state for inputs and outputs before reporting.
     */
    public synchronized void updateWiredDeviceConnectionState(boolean enable) {
        if (!mSelected) {
            Slog.e(TAG, "updateWiredDeviceConnectionState on unselected AlsaDevice!");
            return;
        }
        String alsaCardDeviceString = getAlsaCardDeviceString();
        if (alsaCardDeviceString == null) {
            return;
        }
        try {
            // Output Device
            if (mHasOutput) {
                int device = mIsDock ? AudioSystem.DEVICE_OUT_DGTL_DOCK_HEADSET
                        : (mIsOutputHeadset
                            ? AudioSystem.DEVICE_OUT_USB_HEADSET
                            : AudioSystem.DEVICE_OUT_USB_DEVICE);
                if (DEBUG) {
                    Slog.d(TAG, "pre-call device:0x" + Integer.toHexString(device)
                            + " addr:" + alsaCardDeviceString
                            + " name:" + mDeviceName);
                }
                boolean connected = isOutputJackConnected();
                Slog.i(TAG, "OUTPUT JACK connected: " + connected);
                int outputState = (enable && connected) ? 1 : 0;
                if (outputState != mOutputState) {
                    mOutputState = outputState;
                    AudioDeviceAttributes attributes = new AudioDeviceAttributes(device,
                            alsaCardDeviceString, mDeviceName);
                    mAudioService.setWiredDeviceConnectionState(attributes, outputState, TAG);
                }
            }

            // Input Device
            if (mHasInput) {
                int device = mIsInputHeadset
                        ? AudioSystem.DEVICE_IN_USB_HEADSET
                        : AudioSystem.DEVICE_IN_USB_DEVICE;
                boolean connected = isInputJackConnected();
                Slog.i(TAG, "INPUT JACK connected: " + connected);
                int inputState = (enable && connected) ? 1 : 0;
                if (inputState != mInputState) {
                    mInputState = inputState;
                    AudioDeviceAttributes attributes = new AudioDeviceAttributes(device,
                            alsaCardDeviceString, mDeviceName);
                    mAudioService.setWiredDeviceConnectionState(attributes, inputState, TAG);
                }
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException in setWiredDeviceConnectionState");
        }
    }


    /**
     * @Override
     * @return a string representation of the object.
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
     * @return true if the objects are equivalent.
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
                && mIsOutputHeadset == other.mIsOutputHeadset
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
        result = prime * result + (mHasOutput ? 0 : 1);
        result = prime * result + (mHasInput ? 0 : 1);
        result = prime * result + (mIsInputHeadset ? 0 : 1);
        result = prime * result + (mIsOutputHeadset ? 0 : 1);
        result = prime * result + (mIsDock ? 0 : 1);

        return result;
    }
}

