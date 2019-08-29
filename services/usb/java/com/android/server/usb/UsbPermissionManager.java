/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

class UsbPermissionManager {
    private static final String LOG_TAG = UsbPermissionManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    /** Context to be used by this module */
    private final @NonNull Context mContext;

    /** Map from user id to {@link UsbUserPermissionManager} for the user */
    @GuardedBy("mPermissionsByUser")
    private final SparseArray<UsbUserPermissionManager> mPermissionsByUser = new SparseArray<>();

    final UsbService mUsbService;

    UsbPermissionManager(@NonNull Context context,
            @NonNull UsbService usbService) {
        mContext = context;
        mUsbService = usbService;
    }

    @NonNull UsbUserPermissionManager getPermissionsForUser(@UserIdInt int userId) {
        synchronized (mPermissionsByUser) {
            UsbUserPermissionManager permissions = mPermissionsByUser.get(userId);
            if (permissions == null) {
                permissions = new UsbUserPermissionManager(mContext, UserHandle.of(userId),
                        mUsbService.getSettingsForUser(userId));
                mPermissionsByUser.put(userId, permissions);
            }
            return permissions;
        }
    }

    @NonNull UsbUserPermissionManager getPermissionsForUser(@NonNull UserHandle user) {
        return getPermissionsForUser(user.getIdentifier());
    }

    void remove(@NonNull UserHandle userToRemove) {
        synchronized (mPermissionsByUser) {
            mPermissionsByUser.remove(userToRemove.getIdentifier());
        }
    }

    /**
     * Remove temporary access permission and broadcast that a device was removed.
     *
     * @param device The device that is removed
     */
    void usbDeviceRemoved(@NonNull UsbDevice device) {
        synchronized (mPermissionsByUser) {
            for (int i = 0; i < mPermissionsByUser.size(); i++) {
                // clear temporary permissions for the device
                mPermissionsByUser.valueAt(i).removeDevicePermissions(device);
            }
        }

        Intent intent = new Intent(UsbManager.ACTION_USB_DEVICE_DETACHED);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        intent.putExtra(UsbManager.EXTRA_DEVICE, device);

        if (DEBUG) {
            Slog.d(LOG_TAG, "usbDeviceRemoved, sending " + intent);
        }
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Remove temporary access permission and broadcast that a accessory was removed.
     *
     * @param accessory The accessory that is removed
     */
    void usbAccessoryRemoved(@NonNull UsbAccessory accessory) {
        synchronized (mPermissionsByUser) {
            for (int i = 0; i < mPermissionsByUser.size(); i++) {
                // clear temporary permissions for the accessory
                mPermissionsByUser.valueAt(i).removeAccessoryPermissions(accessory);
            }
        }

        Intent intent = new Intent(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        intent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

}
