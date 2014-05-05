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

package android.hardware.hdmi;

import android.os.IBinder;
import android.os.RemoteException;

import android.util.Log;

/**
 * HdmiCecClient is used to control HDMI-CEC logical device instance in the system.
 * It is connected to actual hardware part via HdmiCecService. It provides with methods
 * to send CEC messages to other device on the bus, and listener that allows to receive
 * incoming messages to the device.
 *
 * @hide
 */
public final class HdmiCecClient {
    private static final String TAG = "HdmiCecClient";

    private final IHdmiCecService mService;
    private final IBinder mBinder;

    /**
     * Listener used by the client to get the incoming messages.
     */
    public static abstract class Listener {
        /**
         * Called when CEC message arrives. Override this method to receive the incoming
         * CEC messages from other device on the bus.
         *
         * @param message {@link HdmiCecMessage} object
         */
        public void onMessageReceived(HdmiCecMessage message) { }

        /**
         * Called when hotplug event occurs. Override this method to receive the events.
         *
         * @param connected true if the cable is connected; otherwise false.
         */
        public void onCableStatusChanged(boolean connected) { }
    }

    // Private constructor.
    private HdmiCecClient(IHdmiCecService service, IBinder b) {
        mService = service;
        mBinder = b;
    }

    // Factory method for HdmiCecClient.
    // Declared package-private. Accessed by HdmiCecManager only.
    static HdmiCecClient create(IHdmiCecService service, IBinder b) {
        return new HdmiCecClient(service, b);
    }

    /**
     * Send &lt;Active Source&gt; message.
     */
    public void sendActiveSource() {
        try {
            mService.sendActiveSource(mBinder);
        } catch (RemoteException e) {
            Log.e(TAG, "sendActiveSource threw exception ", e);
        }
    }

    /**
     * Send &lt;Inactive Source&gt; message.
     */
    public void sendInactiveSource() {
        try {
            mService.sendInactiveSource(mBinder);
        } catch (RemoteException e) {
            Log.e(TAG, "sendInactiveSource threw exception ", e);
        }
    }

    /**
     * Send &lt;Text View On&gt; message.
     */
    public void sendTextViewOn() {
        try {
            mService.sendTextViewOn(mBinder);
        } catch (RemoteException e) {
            Log.e(TAG, "sendTextViewOn threw exception ", e);
        }
    }

    /**
     * Send &lt;Image View On&gt; message.
     */
    public void sendImageViewOn() {
        try {
            mService.sendImageViewOn(mBinder);
        } catch (RemoteException e) {
            Log.e(TAG, "sendImageViewOn threw exception ", e);
        }
    }

    /**
     * Send &lt;Give Device Power Status&gt; message.
     *
     * @param address logical address of the device to send the message to, such as
     *        {@link HdmiCec#ADDR_TV}.
     */
    public void sendGiveDevicePowerStatus(int address) {
        try {
            mService.sendGiveDevicePowerStatus(mBinder, address);
        } catch (RemoteException e) {
            Log.e(TAG, "sendGiveDevicePowerStatus threw exception ", e);
        }
    }

    /**
     * Returns true if the TV or attached display is powered on.
     * <p>
     * The result of this method is only meaningful on playback devices (where the device
     * type is {@link HdmiCec#DEVICE_PLAYBACK}).
     * </p>
     *
     * @return true if TV is on; otherwise false.
     */
    public boolean isTvOn() {
        try {
            return mService.isTvOn(mBinder);
        } catch (RemoteException e) {
            Log.e(TAG, "isTvOn threw exception ", e);
        }
        return false;
    }
}
