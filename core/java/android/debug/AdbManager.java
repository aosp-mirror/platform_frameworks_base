/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.debug;

import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;

/**
 * This class allows the control of ADB-related functions.
 * @hide
 */
@SystemApi
@SystemService(Context.ADB_SERVICE)
public class AdbManager {
    private static final String TAG = "AdbManager";

    /**
     * Action indicating the state change of wireless debugging. Can be either
     *   STATUS_CONNECTED
     *   STATUS_DISCONNECTED
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_DEBUGGING)
    public static final String WIRELESS_DEBUG_STATE_CHANGED_ACTION =
            "com.android.server.adb.WIRELESS_DEBUG_STATUS";

    /**
     * Contains the list of paired devices.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_DEBUGGING)
    public static final String WIRELESS_DEBUG_PAIRED_DEVICES_ACTION =
            "com.android.server.adb.WIRELESS_DEBUG_PAIRED_DEVICES";

    /**
     * Action indicating the status of a pairing. Can be either
     *   WIRELESS_STATUS_FAIL
     *   WIRELESS_STATUS_SUCCESS
     *   WIRELESS_STATUS_CANCELLED
     *   WIRELESS_STATUS_PAIRING_CODE
     *   WIRELESS_STATUS_CONNECTED
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_DEBUGGING)
    public static final String WIRELESS_DEBUG_PAIRING_RESULT_ACTION =
            "com.android.server.adb.WIRELESS_DEBUG_PAIRING_RESULT";

    /**
     * Extra containing the PairDevice map of paired/pairing devices.
     *
     * @hide
     */
    public static final String WIRELESS_DEVICES_EXTRA = "devices_map";

    /**
     * The status of the pairing/unpairing.
     *
     * @hide
     */
    public static final String WIRELESS_STATUS_EXTRA = "status";

    /**
     * The PairDevice.
     *
     * @hide
     */
    public static final String WIRELESS_PAIR_DEVICE_EXTRA = "pair_device";

    /**
     * The six-digit pairing code.
     *
     * @hide
     */
    public static final String WIRELESS_PAIRING_CODE_EXTRA = "pairing_code";

    /**
     * The adb connection/pairing port that was opened.
     *
     * @hide
     */
    public static final String WIRELESS_DEBUG_PORT_EXTRA = "adb_port";

    /**
     * Status indicating the pairing/unpairing failed.
     *
     * @hide
     */
    public static final int WIRELESS_STATUS_FAIL = 0;

    /**
     * Status indicating the pairing/unpairing succeeded.
     *
     * @hide
     */
    public static final int WIRELESS_STATUS_SUCCESS = 1;

    /**
     * Status indicating the pairing/unpairing was cancelled.
     *
     * @hide
     */
    public static final int WIRELESS_STATUS_CANCELLED = 2;

    /**
     * Status indicating the pairing code for pairing.
     *
     * @hide
     */
    public static final int WIRELESS_STATUS_PAIRING_CODE = 3;

    /**
     * Status indicating wireless debugging is connected.
     *
     * @hide
     */
    public static final int WIRELESS_STATUS_CONNECTED = 4;

    /**
     * Status indicating wireless debugging is disconnected.
     *
     * @hide
     */
    public static final int WIRELESS_STATUS_DISCONNECTED = 5;

    private final Context mContext;
    private final IAdbManager mService;

    /**
     * {@hide}
     */
    public AdbManager(Context context, IAdbManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * @return true if the device supports secure ADB over Wi-Fi.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_DEBUGGING)
    public boolean isAdbWifiSupported() {
        try {
            return mService.isAdbWifiSupported();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @return true if the device supports secure ADB over Wi-Fi and device pairing by
     * QR code.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_DEBUGGING)
    public boolean isAdbWifiQrSupported() {
        try {
            return mService.isAdbWifiQrSupported();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
