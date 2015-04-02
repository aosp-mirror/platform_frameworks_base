/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.app.admin;

/**
 * Defines constants designating device provisioning status used with {@link
 * android.app.admin.DevicePolicyManager#sendDeviceInitializerStatus(int,String)}.
 *
 * This class contains flag constants that define special status codes:
 * <ul>
 * <li>{@link #FLAG_STATUS_ERROR} is used to define provisioning error status codes
 * <li>{@link #FLAG_STATUS_CUSTOM} is used to define custom status codes
 * <li>{@link #FLAG_STATUS_HIGH_PRIORITY} is used to define high priority status codes
 * </ul>
 *
 * <p>Status codes used by ManagedProvisioning are also defined in this class. These status codes
 * include provisioning errors and status codes.
 * <ul>
 * <li>{@link #STATUS_ERROR_CONNECT_WIFI}
 * <li>{@link #STATUS_ERROR_RESET_PROTECTION_BLOCKING_PROVISIONING}
 * <li>{@link #STATUS_ERROR_DOWNLOAD_PACKAGE}
 * <li>{@link #STATUS_ERROR_INSTALL_PACKAGE}
 * <li>{@link #STATUS_ERROR_SET_DEVICE_POLICY}
 * <li>{@link #STATUS_ERROR_DELETE_APPS}
 * <li>{@link #STATUS_ERROR_DOUBLE_BUMP}
 * <li>{@link #STATUS_STATE_CONNECT_BLUETOOTH_PROXY}
 * <li>{@link #STATUS_STATE_DISCONNECT_BLUETOOTH_PROXY}
 * <li>{@link #STATUS_STATE_DEVICE_PROVISIONED}
 * </ul>
 */
public class DeviceInitializerStatus {
    /**
     * A flag used to designate an error status.
     *
     * <p>This flag is used with {@code statusCode} values sent through
     * {@link android.app.admin.DevicePolicyManager#sendDeviceInitializerStatus(int,String)}
     * @see #isErrorStatus(int)
     */
    public static final int FLAG_STATUS_ERROR = 0x01000000;

    /**
     * A flag used to designate a custom status. Custom status codes will be defined by device
     * initializer agents.
     *
     * <p>This flag is used with {@code statusCode} values sent through
     * {@link android.app.admin.DevicePolicyManager#sendDeviceInitializerStatus(int,String)}
     * @see #isCustomStatus(int)
     */
    public static final int FLAG_STATUS_CUSTOM = 0x02000000;

    /**
     * A bit flag used to designate a reserved status. Reserved status codes will not be defined
     * in AOSP.
     *
     * <p>This flag is used with {@code statusCode} values sent through
     * {@link android.app.admin.DevicePolicyManager#sendDeviceInitializerStatus(int,String)}
     */
    public static final int FLAG_STATUS_RESERVED = 0x04000000;

    /**
     * A flag used to indicate that a status message is high priority.
     *
     * <p>This flag is used with {@code statusCode} values sent through
     * {@link android.app.admin.DevicePolicyManager#sendDeviceInitializerStatus(int,String)}
     * @see #isHighPriority(int)
     */
    public static final int FLAG_STATUS_HIGH_PRIORITY = 0x08000000;

    /**
     * Device provisioning status code that indicates that a device is connecting to establish
     * a Bluetooth network proxy.
     */
    public static final int STATUS_STATE_CONNECT_BLUETOOTH_PROXY = FLAG_STATUS_HIGH_PRIORITY | 8;

    /**
     * Device provisioning status code that indicates that a connected Bluetooth network proxy
     * is being shut down.
     */
    public static final int STATUS_STATE_DISCONNECT_BLUETOOTH_PROXY = FLAG_STATUS_HIGH_PRIORITY | 9;

    /**
     * Device provisioning status code that indicates that a device has been successfully
     * provisioned.
     */
    public static final int STATUS_STATE_DEVICE_PROVISIONED = FLAG_STATUS_HIGH_PRIORITY | 10;

    /**
     * Device provisioning error status code that indicates that a device could not connect to
     * a Wi-Fi network.
     */
    public static final int STATUS_ERROR_CONNECT_WIFI = FLAG_STATUS_ERROR | 21;

    /**
     * Device provisioning error status indicating that factory reset protection is enabled on
     * the provisioned device and cannot be disabled with the provided data.
     */
    public static final int STATUS_ERROR_RESET_PROTECTION_BLOCKING_PROVISIONING =
            FLAG_STATUS_ERROR | 22;

    /**
     * Device provisioning error status indicating that device administrator and device initializer
     * packages could not be downloaded and verified successfully.
     */
    public static final int STATUS_ERROR_DOWNLOAD_PACKAGE = FLAG_STATUS_ERROR | 23;

    /**
     * Device provisioning error status indicating that device owner and device initializer packages
     * could not be installed.
     */
    public static final int STATUS_ERROR_INSTALL_PACKAGE = FLAG_STATUS_ERROR | 24;

    /**
     * Device provisioning error status indicating that the device owner or device initializer
     * components could not be set.
     */
    public static final int STATUS_ERROR_SET_DEVICE_POLICY = FLAG_STATUS_ERROR | 25;

    /**
     * Device provisioning error status indicating that deleting non-required applications during
     * provisioning failed.
     */
    public static final int STATUS_ERROR_DELETE_APPS = FLAG_STATUS_ERROR | 26;

    /**
     * Device provisioning error status code that indicates that a provisioning attempt has failed
     * because the device has already been provisioned or that provisioning has already started.
     */
    public static final int STATUS_ERROR_DOUBLE_BUMP = FLAG_STATUS_ERROR | 30;

    /**
     * Determine if the specified status code represents an error status.
     * @param statusCode status code to check
     * @return {@code true} if the status code is an error status code
     */
    public static boolean isErrorStatus(int statusCode) {
        return isFlagSet(statusCode, FLAG_STATUS_ERROR);
    }

    /**
     * Determine if the specified status code is a custom status. Custom status codes are defined
     * and sent by device initialization agents.
     * @param statusCode status code to check
     * @return {@code true} if the status code is a custom status code
     */
    public static boolean isCustomStatus(int statusCode) {
        return isFlagSet(statusCode, FLAG_STATUS_CUSTOM);
    }

    /**
     * Determine if the specified status code is a high priority status code.
     * @param statusCode status code to check
     * @return {@code true} if the status code is a high priority status code
     */
    public static boolean isHighPriority(int statusCode) {
        return isFlagSet(statusCode, FLAG_STATUS_HIGH_PRIORITY);
    }

    private static boolean isFlagSet(int statusCode, int flag) {
        return (statusCode & flag) != 0;
    }

    private DeviceInitializerStatus() {}
}
