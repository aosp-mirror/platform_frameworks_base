/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.bluetooth.le;

import android.bluetooth.BluetoothDevice;

/**
 * Bluetooth LE periodic advertising callbacks, used to deliver periodic
 * advertising operation status.
 *
 * @hide
 * @see PeriodicAdvertisingManager#createSync
 */
public abstract class PeriodicAdvertisingCallback {

    /**
     * The requested operation was successful.
     *
     * @hide
     */
    public static final int SYNC_SUCCESS = 0;

    /**
     * Sync failed to be established because remote device did not respond.
     */
    public static final int SYNC_NO_RESPONSE = 1;

    /**
     * Sync failed to be established because controller can't support more syncs.
     */
    public static final int SYNC_NO_RESOURCES = 2;


    /**
     * Callback when synchronization was established.
     *
     * @param syncHandle handle used to identify this synchronization.
     * @param device remote device.
     * @param advertisingSid synchronized advertising set id.
     * @param skip The number of periodic advertising packets that can be skipped after a successful
     * receive in force. @see PeriodicAdvertisingManager#createSync
     * @param timeout Synchronization timeout for the periodic advertising in force. One unit is
     * 10ms. @see PeriodicAdvertisingManager#createSync
     * @param timeout
     * @param status operation status.
     */
    public void onSyncEstablished(int syncHandle, BluetoothDevice device,
            int advertisingSid, int skip, int timeout,
            int status) {
    }

    /**
     * Callback when periodic advertising report is received.
     *
     * @param report periodic advertising report.
     */
    public void onPeriodicAdvertisingReport(PeriodicAdvertisingReport report) {
    }

    /**
     * Callback when periodic advertising synchronization was lost.
     *
     * @param syncHandle handle used to identify this synchronization.
     */
    public void onSyncLost(int syncHandle) {
    }
}
