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

package com.android.server.hdmi;

import android.hardware.hdmi.HdmiCec;
import android.hardware.hdmi.HdmiCecMessage;
import android.hardware.hdmi.IHdmiCecDevice;
import android.hardware.hdmi.IHdmiCecListener;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * CecDevice class represents a CEC logical device characterized
 * by its device type. A physical device can contain the functions of
 * more than one logical device, in which case it should create
 * as many logical devices as necessary.
 *
 * <p>Note that if a physical device has multiple instances of a particular
 * functionality, it should advertize only one instance. For instance, if
 * a device has multiple tuners, it should only expose one for control
 * via CEC. In this case, it is up to the device itself to manage multiple tuners.
 *
 * <p>The version of HDMI-CEC protocol supported in this class is 1.3a.
 *
 * <p>Declared as package-private, accessed by HdmiCecService only.
 */
final class HdmiCecDevice {
    private static final String TAG = "HdmiCecDevice";

    private final int mType;

    // List of listeners to the message/event coming to the device.
    private final List<IHdmiCecListener> mListeners = new ArrayList<IHdmiCecListener>();
    private final Binder mBinder = new Binder();

    private String mName;
    private boolean mIsActiveSource;

    /**
     * Constructor.
     */
    public HdmiCecDevice(int type) {
        mType = type;
        mIsActiveSource = false;
    }

    /**
     * Return the binder token that identifies this instance.
     */
    public Binder getToken() {
        return mBinder;
    }

    /**
     * Return the type of this device.
     */
    public int getType() {
        return mType;
    }

    /**
     * Set the name of the device. The name will be transferred via the message
     * &lt;Set OSD Name&gt; to other HDMI-CEC devices connected through HDMI
     * cables and shown on TV screen to identify the devicie.
     *
     * @param name name of the device
     */
    public void setName(String name) {
        mName = name;
    }

    /**
     * Return the name of this device.
     */
    public String getName() {
        return mName;
    }

    /**
     * Register a listener to be invoked when events occur.
     *
     * @param listener the listern that will run
     */
    public void addListener(IHdmiCecListener listener) {
        mListeners.add(listener);
    }

    /**
     * Remove the listener that was previously registered.
     *
     * @param listener IHdmiCecListener instance to be removed
     */
    public void removeListener(IHdmiCecListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Indicate if the device has listeners.
     *
     * @return true if there are listener instances for this device
     */
    public boolean hasListener() {
        return !mListeners.isEmpty();
    }

    /**
     * Handle HDMI-CEC message coming to the device by invoking the registered
     * listeners.
     */
    public void handleMessage(int srcAddress, int dstAddress, int opcode, byte[] params) {
        if (opcode == HdmiCec.MESSAGE_ACTIVE_SOURCE) {
            mIsActiveSource = false;
        }
        if (mListeners.size() == 0) {
            return;
        }
        HdmiCecMessage message = new HdmiCecMessage(srcAddress, dstAddress, opcode, params);
        for (IHdmiCecListener listener : mListeners) {
            try {
                listener.onMessageReceived(message);
            } catch (RemoteException e) {
                Log.e(TAG, "listener.onMessageReceived failed.");
            }
        }
    }

    public void handleHotplug(boolean connected) {
        for (IHdmiCecListener listener : mListeners) {
            try {
                listener.onCableStatusChanged(connected);
            } catch (RemoteException e) {
                Log.e(TAG, "listener.onCableStatusChanged failed.");
            }
        }
    }

    /**
     * Return the active status of the device.
     *
     * @return true if the device is the active source among the connected
     *         HDMI-CEC-enabled devices; otherwise false.
     */
    public boolean isActiveSource() {
        return mIsActiveSource;
    }

    /**
     * Update the active source state of the device.
     */
    public void setIsActiveSource(boolean state) {
        mIsActiveSource = state;
    }
}
