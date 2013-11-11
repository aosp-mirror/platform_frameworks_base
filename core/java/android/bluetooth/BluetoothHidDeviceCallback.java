/*
 * Copyright (C) 2013 The Linux Foundation. All rights reserved
 * Not a Contribution.
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

package android.bluetooth;

import android.util.Log;

/** @hide */
public abstract class BluetoothHidDeviceCallback {

    private static final String TAG = BluetoothHidDeviceCallback.class.getSimpleName();

    /**
     * Callback called when application registration state changes. Usually it's
     * called due to either
     * {@link BluetoothHidDevice#registerApp(String, String, String, byte, byte[],
     * BluetoothHidDeviceCallback)}
     * or
     * {@link BluetoothHidDevice#unregisterApp(BluetoothHidDeviceAppConfiguration)}
     * , but can be also unsolicited in case e.g. Bluetooth was turned off in
     * which case application is unregistered automatically.
     *
     * @param pluggedDevice {@link BluetoothDevice} object which represents host
     *            that currently has Virtual Cable established with device. Only
     *            valid when application is registered, can be <code>null</code>
     *            .
     * @param config {@link BluetoothHidDeviceAppConfiguration} object which
     *            represents token required to unregister application using
     *            {@link BluetoothHidDevice#unregisterApp(BluetoothHidDeviceAppConfiguration)}
     *            .
     * @param registered <code>true</code> if application is registered,
     *            <code>false</code> otherwise.
     */
    public void onAppStatusChanged(BluetoothDevice pluggedDevice,
                                    BluetoothHidDeviceAppConfiguration config, boolean registered) {
        Log.d(TAG, "onAppStatusChanged: pluggedDevice=" + (pluggedDevice == null ?
            null : pluggedDevice.toString()) + " registered=" + registered);
    }

    /**
     * Callback called when connection state with remote host was changed.
     * Application can assume than Virtual Cable is established when called with
     * {@link BluetoothProfile#STATE_CONNECTED} <code>state</code>.
     *
     * @param device {@link BluetoothDevice} object representing host device
     *            which connection state was changed.
     * @param state Connection state as defined in {@link BluetoothProfile}.
     */
    public void onConnectionStateChanged(BluetoothDevice device, int state) {
        Log.d(TAG, "onConnectionStateChanged: device=" + device.toString() + " state=" + state);
    }

    /**
     * Callback called when GET_REPORT is received from remote host. Should be
     * replied by application using
     * {@link BluetoothHidDevice#replyReport(byte, byte, byte[])}.
     *
     * @param type Requested Report Type.
     * @param id Requested Report Id, can be 0 if no Report Id are defined in
     *            descriptor.
     * @param bufferSize Requested buffer size, application shall respond with
     *            at least given number of bytes.
     */
    public void onGetReport(byte type, byte id, int bufferSize) {
        Log.d(TAG, "onGetReport: type=" + type + " id=" + id + " bufferSize=" + bufferSize);
    }

    /**
     * Callback called when SET_REPORT is received from remote host. In case
     * received data are invalid, application shall respond with
     * {@link BluetoothHidDevice#reportError()}.
     *
     * @param type Report Type.
     * @param id Report Id.
     * @param data Report data.
     */
    public void onSetReport(byte type, byte id, byte[] data) {
        Log.d(TAG, "onSetReport: type=" + type + " id=" + id);
    }

    /**
     * Callback called when SET_PROTOCOL is received from remote host.
     * Application shall use this information to send only reports valid for
     * given protocol mode. By default,
     * {@link BluetoothHidDevice#PROTOCOL_REPORT_MODE} shall be assumed.
     *
     * @param protocol Protocol Mode.
     */
    public void onSetProtocol(byte protocol) {
        Log.d(TAG, "onSetProtocol: protocol=" + protocol);
    }

    /**
     * Callback called when report data is received over interrupt channel.
     * Report Type is assumed to be
     * {@link BluetoothHidDevice#REPORT_TYPE_OUTPUT}.
     *
     * @param reportId Report Id.
     * @param data Report data.
     */
    public void onIntrData(byte reportId, byte[] data) {
        Log.d(TAG, "onIntrData: reportId=" + reportId);
    }

    /**
     * Callback called when Virtual Cable is removed. This can be either due to
     * {@link BluetoothHidDevice#unplug()} or request from remote side. After
     * this callback is received connection will be disconnected automatically.
     */
    public void onVirtualCableUnplug() {
        Log.d(TAG, "onVirtualCableUnplug");
    }
}
