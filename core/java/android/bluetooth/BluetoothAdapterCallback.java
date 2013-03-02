/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.bluetooth.BluetoothDevice;

/**
 * This abstract class is used to implement {@link BluetoothAdapter} callbacks.
 */
public abstract class BluetoothAdapterCallback {

    /**
     * Indicates the callback has been registered successfully
     */
    public static final int CALLBACK_REGISTERED = 0;

    /**
     * Indicates the callback registration has failed
     */
    public static final int CALLBACK_REGISTRATION_FAILURE = 1;

    /**
     * Callback to inform change in registration state of the  application.
     *
     * @param status Returns {@link #CALLBACK_REGISTERED} if the application
     *               was successfully registered.
     */
    public void onCallbackRegistration(int status) {
    }

    /**
     * Callback reporting an LE device found during a device scan initiated
     * by the {@link BluetoothAdapter#startLeScan} function.
     *
     * @param device Identifies the remote device
     * @param rssi The RSSI value for the remote device as reported by the
     *             Bluetooth hardware. 0 if no RSSI value is available.
     * @param scanRecord The content of the advertisement record offered by
     *                   the remote device.
     */
    public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
    }
}
