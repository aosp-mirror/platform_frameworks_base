/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.SystemApi;

/**
 * A class with constants representing possible return values for Bluetooth APIs. General return
 * values occupy the range 0 to 99. Profile-specific return values occupy the range 100-999.
 * API-specific return values start at 1000. The exception to this is the "other" error code which
 * occupies the max integer value.
 */
public final class BluetoothStatusCodes {

    private BluetoothStatusCodes() {}

    /**
     * Indicates that the API call was successful
     */
    public static final int SUCCESS = 0;

    /**
     * Error code indicating that Bluetooth is not enabled
     */
    public static final int ERROR_BLUETOOTH_NOT_ENABLED = 1;

    /**
     * Error code indicating that the API call was initiated by neither the system nor the active
     * Zuser
     */
    public static final int ERROR_BLUETOOTH_NOT_ALLOWED = 2;

    /**
     * Error code indicating that the Bluetooth Device specified is not bonded
     */
    public static final int ERROR_DEVICE_NOT_BONDED = 3;

    /**
     * Error code indicating that the Bluetooth Device specified is not connected, but is bonded
     *
     * @hide
     */
    public static final int ERROR_DEVICE_NOT_CONNECTED = 4;

    /**
     * Error code indicating that the caller does not have the
     * {@link android.Manifest.permission#BLUETOOTH_ADVERTISE} permission
     *
     * @hide
     */
    public static final int ERROR_MISSING_BLUETOOTH_ADVERTISE_PERMISSION = 5;

    /**
     * Error code indicating that the caller does not have the
     * {@link android.Manifest.permission#BLUETOOTH_CONNECT} permission
     */
    public static final int ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION = 6;

    /**
     * Error code indicating that the caller does not have the
     * {@link android.Manifest.permission#BLUETOOTH_SCAN} permission
     *
     * @hide
     */
    public static final int ERROR_MISSING_BLUETOOTH_SCAN_PERMISSION = 7;

    /**
     * If another application has already requested {@link OobData} then another fetch will be
     * disallowed until the callback is removed.
     *
     * @hide
     */
    @SystemApi
    public static final int ERROR_ANOTHER_ACTIVE_OOB_REQUEST = 1000;

    /**
     * Indicates that the ACL disconnected due to an explicit request from the local device.
     * <p>
     * Example cause: This is a normal disconnect reason, e.g., user/app initiates
     * disconnection.
     *
     * @hide
     */
    public static final int ERROR_DISCONNECT_REASON_LOCAL_REQUEST = 1100;

    /**
     * Indicates that the ACL disconnected due to an explicit request from the remote device.
     * <p>
     * Example cause: This is a normal disconnect reason, e.g., user/app initiates
     * disconnection.
     * <p>
     * Example solution: The app can also prompt the user to check their remote device.
     *
     * @hide
     */
    public static final int ERROR_DISCONNECT_REASON_REMOTE_REQUEST = 1101;

    /**
     * Generic disconnect reason indicating the ACL disconnected due to an error on the local
     * device.
     * <p>
     * Example solution: Prompt the user to check their local device (e.g., phone, car
     * headunit).
     *
     * @hide
     */
    public static final int ERROR_DISCONNECT_REASON_LOCAL = 1102;

    /**
     * Generic disconnect reason indicating the ACL disconnected due to an error on the remote
     * device.
     * <p>
     * Example solution: Prompt the user to check their remote device (e.g., headset, car
     * headunit, watch).
     *
     * @hide
     */
    public static final int ERROR_DISCONNECT_REASON_REMOTE = 1103;

    /**
     * Indicates that the ACL disconnected due to a timeout.
     * <p>
     * Example cause: remote device might be out of range.
     * <p>
     * Example solution: Prompt user to verify their remote device is on or in
     * connection/pairing mode.
     *
     * @hide
     */
    public static final int ERROR_DISCONNECT_REASON_TIMEOUT = 1104;

    /**
     * Indicates that the ACL disconnected due to link key issues.
     * <p>
     * Example cause: Devices are either unpaired or remote device is refusing our pairing
     * request.
     * <p>
     * Example solution: Prompt user to unpair and pair again.
     *
     * @hide
     */
    public static final int ERROR_DISCONNECT_REASON_SECURITY = 1105;

    /**
     * Indicates that the ACL disconnected due to the local device's system policy.
     * <p>
     * Example cause: privacy policy, power management policy, permissions, etc.
     * <p>
     * Example solution: Prompt the user to check settings, or check with their system
     * administrator (e.g. some corp-managed devices do not allow OPP connection).
     *
     * @hide
     */
    public static final int ERROR_DISCONNECT_REASON_SYSTEM_POLICY = 1106;

    /**
     * Indicates that the ACL disconnected due to resource constraints, either on the local
     * device or the remote device.
     * <p>
     * Example cause: controller is busy, memory limit reached, maximum number of connections
     * reached.
     * <p>
     * Example solution: The app should wait and try again. If still failing, prompt the user
     * to disconnect some devices, or toggle Bluetooth on the local and/or the remote device.
     *
     * @hide
     */
    public static final int ERROR_DISCONNECT_REASON_RESOURCE_LIMIT_REACHED = 1107;

    /**
     * Indicates that the ACL disconnected because another ACL connection already exists.
     *
     * @hide
     */
    public static final int ERROR_DISCONNECT_REASON_CONNECTION_ALREADY_EXISTS = 1108;

    /**
     * Indicates that the ACL disconnected due to incorrect parameters passed in from the app.
     * <p>
     * Example solution: Change parameters and try again. If error persists, the app can report
     * telemetry and/or log the error in a bugreport.
     *
     * @hide
     */
    public static final int ERROR_DISCONNECT_REASON_BAD_PARAMETERS = 1109;

    /**
     * Indicates that an unknown error has occurred has occurred.
     */
    public static final int ERROR_UNKNOWN = Integer.MAX_VALUE;
}
