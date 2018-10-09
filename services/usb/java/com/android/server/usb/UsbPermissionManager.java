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

package com.android.server.usb;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.service.usb.UsbSettingsAccessoryPermissionProto;
import android.service.usb.UsbSettingsDevicePermissionProto;
import android.service.usb.UsbUserSettingsManagerProto;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.dump.DualDumpOutputStream;

import java.util.HashMap;

/**
 * UsbPermissionManager manages usb device or accessory access permissions.
 *
 * @hide
 */
class UsbPermissionManager {
    private static final String LOG_TAG = UsbPermissionManager.class.getSimpleName();

    @GuardedBy("mLock")
    /** Temporary mapping USB device name to list of UIDs with permissions for the device*/
    private final HashMap<String, SparseBooleanArray> mDevicePermissionMap =
            new HashMap<>();
    @GuardedBy("mLock")
    /** Temporary mapping UsbAccessory to list of UIDs with permissions for the accessory*/
    private final HashMap<UsbAccessory, SparseBooleanArray> mAccessoryPermissionMap =
            new HashMap<>();

    private final UserHandle mUser;
    private final boolean mDisablePermissionDialogs;

    private final Object mLock = new Object();

    UsbPermissionManager(@NonNull Context context, @NonNull UserHandle user) {
        mUser = user;
        mDisablePermissionDialogs = context.getResources().getBoolean(
                com.android.internal.R.bool.config_disableUsbPermissionDialogs);
    }

    /**
     * Removes access permissions of all packages for the USB accessory.
     *
     * @param accessory to remove permissions for
     */
    void removeAccessoryPermissions(@NonNull UsbAccessory accessory) {
        synchronized (mLock) {
            mAccessoryPermissionMap.remove(accessory);
        }
    }

    /**
     * Removes access permissions of all packages for the USB device.
     *
     * @param device to remove permissions for
     */
    void removeDevicePermissions(@NonNull UsbDevice device) {
        synchronized (mLock) {
            mDevicePermissionMap.remove(device.getDeviceName());
        }
    }

    /**
     * Grants permission for USB device without showing system dialog for package with uid.
     *
     * @param device to grant permission for
     * @param uid to grant permission for
     */
    void grantDevicePermission(@NonNull UsbDevice device, int uid) {
        synchronized (mLock) {
            String deviceName = device.getDeviceName();
            SparseBooleanArray uidList = mDevicePermissionMap.get(deviceName);
            if (uidList == null) {
                uidList = new SparseBooleanArray(1);
                mDevicePermissionMap.put(deviceName, uidList);
            }
            uidList.put(uid, true);
        }
    }

    /**
     * Grants permission for USB accessory without showing system dialog for package with uid.
     *
     * @param accessory to grant permission for
     * @param uid to grant permission for
     */
    void grantAccessoryPermission(@NonNull UsbAccessory accessory, int uid) {
        synchronized (mLock) {
            SparseBooleanArray uidList = mAccessoryPermissionMap.get(accessory);
            if (uidList == null) {
                uidList = new SparseBooleanArray(1);
                mAccessoryPermissionMap.put(accessory, uidList);
            }
            uidList.put(uid, true);
        }
    }

    /**
     * Returns true if package with uid has permission to access the device.
     *
     * @param device to check permission for
     * @param uid to check permission for
     * @return {@code true} if package with uid has permission
     */
    boolean hasPermission(@NonNull UsbDevice device, int uid) {
        synchronized (mLock) {
            if (uid == Process.SYSTEM_UID || mDisablePermissionDialogs) {
                return true;
            }
            SparseBooleanArray uidList = mDevicePermissionMap.get(device.getDeviceName());
            if (uidList == null) {
                return false;
            }
            return uidList.get(uid);
        }
    }

    /**
     * Returns true if caller has permission to access the accessory.
     *
     * @param accessory to check permission for
     * @param uid to check permission for
     * @return {@code true} if caller has permssion
     */
    boolean hasPermission(@NonNull UsbAccessory accessory, int uid) {
        synchronized (mLock) {
            if (uid == Process.SYSTEM_UID || mDisablePermissionDialogs) {
                return true;
            }
            SparseBooleanArray uidList = mAccessoryPermissionMap.get(accessory);
            if (uidList == null) {
                return false;
            }
            return uidList.get(uid);
        }
    }

    /**
     * Creates UI dialog to request permission for the given package to access the device
     * or accessory.
     *
     * @param device The USB device attached
     * @param accessory The USB accessory attached
     * @param canBeDefault Whether the calling pacakge can set as default handler
     * of the USB device or accessory
     * @param packageName The package name of the calling package
     * @param uid The uid of the calling package
     * @param userContext The context to start the UI dialog
     * @param pi PendingIntent for returning result
     */
    void requestPermissionDialog(@Nullable UsbDevice device,
                                 @Nullable UsbAccessory accessory,
                                 boolean canBeDefault,
                                 @NonNull String packageName,
                                 int uid,
                                 @NonNull Context userContext,
                                 @NonNull PendingIntent pi) {
        long identity = Binder.clearCallingIdentity();
        Intent intent = new Intent();
        if (device != null) {
            intent.putExtra(UsbManager.EXTRA_DEVICE, device);
        } else {
            intent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
        }
        intent.putExtra(Intent.EXTRA_INTENT, pi);
        intent.putExtra(Intent.EXTRA_UID, uid);
        intent.putExtra(UsbManager.EXTRA_CAN_BE_DEFAULT, canBeDefault);
        intent.putExtra(UsbManager.EXTRA_PACKAGE, packageName);
        intent.setClassName("com.android.systemui",
                "com.android.systemui.usb.UsbPermissionActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            userContext.startActivityAsUser(intent, mUser);
        } catch (ActivityNotFoundException e) {
            Slog.e(LOG_TAG, "unable to start UsbPermissionActivity");
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    void dump(@NonNull DualDumpOutputStream dump) {
        synchronized (mLock) {
            for (String deviceName : mDevicePermissionMap.keySet()) {
                long devicePermissionToken = dump.start("device_permissions",
                        UsbUserSettingsManagerProto.DEVICE_PERMISSIONS);

                dump.write("device_name", UsbSettingsDevicePermissionProto.DEVICE_NAME, deviceName);

                SparseBooleanArray uidList = mDevicePermissionMap.get(deviceName);
                int count = uidList.size();
                for (int i = 0; i < count; i++) {
                    dump.write("uids", UsbSettingsDevicePermissionProto.UIDS, uidList.keyAt(i));
                }

                dump.end(devicePermissionToken);
            }

            for (UsbAccessory accessory : mAccessoryPermissionMap.keySet()) {
                long accessoryPermissionToken = dump.start("accessory_permissions",
                        UsbUserSettingsManagerProto.ACCESSORY_PERMISSIONS);

                dump.write("accessory_description",
                        UsbSettingsAccessoryPermissionProto.ACCESSORY_DESCRIPTION,
                        accessory.getDescription());

                SparseBooleanArray uidList = mAccessoryPermissionMap.get(accessory);
                int count = uidList.size();
                for (int i = 0; i < count; i++) {
                    dump.write("uids", UsbSettingsAccessoryPermissionProto.UIDS, uidList.keyAt(i));
                }

                dump.end(accessoryPermissionToken);
            }
        }
    }
}
