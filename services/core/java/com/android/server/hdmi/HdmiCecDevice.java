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
import android.hardware.hdmi.IHdmiCecListener;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * HdmiCecDevice class represents a CEC logical device characterized
 * by its device type. It is a superclass of those serving concrete device type.
 * Currently we're interested in playback(one of sources), display(sink) device type
 * only. The support for the other types like recorder, audio system will come later.
 *
 * <p>A physical device can contain the functions of
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
abstract class HdmiCecDevice {
    private static final String TAG = "HdmiCecDevice";

    private final int mType;

    // List of listeners to the message/event coming to the device.
    private final List<IHdmiCecListener> mListeners = new ArrayList<IHdmiCecListener>();
    private final Binder mBinder = new Binder();
    private final HdmiCecService mService;

    private boolean mIsActiveSource;

    /**
     * Factory method that creates HdmiCecDevice instance to the device type.
     */
    public static HdmiCecDevice create(HdmiCecService service, int type) {
        if (type == HdmiCec.DEVICE_PLAYBACK) {
            return new HdmiCecDevicePlayback(service, type);
        } else if (type == HdmiCec.DEVICE_TV) {
            return new HdmiCecDeviceTv(service, type);
        }
        return null;
    }

    /**
     * Constructor.
     */
    public HdmiCecDevice(HdmiCecService service, int type) {
        mService = service;
        mType = type;
        mIsActiveSource = false;
    }

    /**
     * Called right after the class is instantiated. This method can be used to
     * implement any initialization tasks for the instance.
     */
    abstract public void initialize();

    /**
     * Return the binder token that identifies this instance.
     */
    public Binder getToken() {
        return mBinder;
    }

    /**
     * Return the service instance.
     */
    public HdmiCecService getService() {
        return mService;
    }

    /**
     * Return the type of this device.
     */
    public int getType() {
        return mType;
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

    /**
     * Send &lt;Active Source&gt; command. The default implementation does nothing. Should be
     * overriden by subclass.
     */
    public void sendActiveSource(int physicalAddress) {
        logWarning("<Active Source> not valid for the device type: " + mType
                + " address:" + physicalAddress);
    }

    /**
     * Send &lt;Inactive Source&gt; command. The default implementation does nothing. Should be
     * overriden by subclass.
     */
    public void sendInactiveSource(int physicalAddress) {
        logWarning("<Inactive Source> not valid for the device type: " + mType
                + " address:" + physicalAddress);
    }

    /**
     * Send &lt;Image View On&gt; command. The default implementation does nothing. Should be
     * overriden by subclass.
     */
    public void sendImageViewOn() {
        logWarning("<Image View On> not valid for the device type: " + mType);
    }

    /**
     * Send &lt;Text View On&gt; command. The default implementation does nothing. Should be
     * overriden by subclass.
     */
    public void sendTextViewOn() {
        logWarning("<Text View On> not valid for the device type: " + mType);
    }

    /**
     * Check if the connected sink device is in powered-on state. The default implementation
     * simply returns false. Should be overriden by subclass to report the correct state.
     */
    public boolean isSinkDeviceOn() {
        logWarning("isSinkDeviceOn() not valid for the device type: " + mType);
        return false;
    }

    private void logWarning(String msg) {
        Log.w(TAG, msg);
    }
}
