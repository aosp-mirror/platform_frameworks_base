/*
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

package com.android.server.usb;

import android.annotation.NonNull;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.service.usb.UsbSettingsAccessoryPermissionProto;
import android.service.usb.UsbSettingsDevicePermissionProto;
import android.service.usb.UsbUserSettingsManagerProto;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.util.dump.DualDumpOutputStream;

import java.util.HashMap;

class UsbUserSettingsManager {
    private static final String TAG = "UsbUserSettingsManager";
    private static final boolean DEBUG = false;

    private final UserHandle mUser;
    private final boolean mDisablePermissionDialogs;

    private final Context mUserContext;
    private final PackageManager mPackageManager;

    // Temporary mapping USB device name to list of UIDs with permissions for the device
    private final HashMap<String, SparseBooleanArray> mDevicePermissionMap =
            new HashMap<>();
    // Temporary mapping UsbAccessory to list of UIDs with permissions for the accessory
    private final HashMap<UsbAccessory, SparseBooleanArray> mAccessoryPermissionMap =
            new HashMap<>();

    private final Object mLock = new Object();

    public UsbUserSettingsManager(Context context, UserHandle user) {
        if (DEBUG) Slog.v(TAG, "Creating settings for " + user);

        try {
            mUserContext = context.createPackageContextAsUser("android", 0, user);
        } catch (NameNotFoundException e) {
            throw new RuntimeException("Missing android package");
        }

        mPackageManager = mUserContext.getPackageManager();

        mUser = user;

        mDisablePermissionDialogs = context.getResources().getBoolean(
                com.android.internal.R.bool.config_disableUsbPermissionDialogs);
    }

    /**
     * Remove all access permission for a device.
     *
     * @param device The device the permissions are for
     */
    void removeDevicePermissions(@NonNull UsbDevice device) {
        synchronized (mLock) {
            mDevicePermissionMap.remove(device.getDeviceName());
        }
    }

    /**
     * Remove all access permission for a accessory.
     *
     * @param accessory The accessory the permissions are for
     */
    void removeAccessoryPermissions(@NonNull UsbAccessory accessory) {
        synchronized (mLock) {
            mAccessoryPermissionMap.remove(accessory);
        }
    }

    /**
     * Check whether a particular device or any of its interfaces
     * is of class VIDEO.
     *
     * @param device The device that needs to get scanned
     * @return True in case a VIDEO device or interface is present,
     *         False otherwise.
     */
    private boolean isCameraDevicePresent(UsbDevice device) {
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO) {
            return true;
        }

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check for camera permission of the calling process.
     *
     * @param packageName Package name of the caller.
     * @param uid Linux uid of the calling process.
     *
     * @return True in case camera permission is available, False otherwise.
     */
    private boolean isCameraPermissionGranted(String packageName, int uid) {
        int targetSdkVersion = android.os.Build.VERSION_CODES.P;
        try {
            ApplicationInfo aInfo = mPackageManager.getApplicationInfo(packageName, 0);
            // compare uid with packageName to foil apps pretending to be someone else
            if (aInfo.uid != uid) {
                Slog.i(TAG, "Package " + packageName + " does not match caller's uid " + uid);
                return false;
            }
            targetSdkVersion = aInfo.targetSdkVersion;
        } catch (PackageManager.NameNotFoundException e) {
            Slog.i(TAG, "Package not found, likely due to invalid package name!");
            return false;
        }

        if (targetSdkVersion >= android.os.Build.VERSION_CODES.P) {
            int allowed = mUserContext.checkCallingPermission(android.Manifest.permission.CAMERA);
            if (android.content.pm.PackageManager.PERMISSION_DENIED == allowed) {
                Slog.i(TAG, "Camera permission required for USB video class devices");
                return false;
            }
        }

        return true;
    }

    public boolean hasPermission(UsbDevice device, String packageName, int uid) {
        synchronized (mLock) {
            if (isCameraDevicePresent(device)) {
                if (!isCameraPermissionGranted(packageName, uid)) {
                    return false;
                }
            }
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

    public boolean hasPermission(UsbAccessory accessory) {
        synchronized (mLock) {
            int uid = Binder.getCallingUid();
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

    public void checkPermission(UsbDevice device, String packageName, int uid) {
        if (!hasPermission(device, packageName, uid)) {
            throw new SecurityException("User has not given permission to device " + device);
        }
    }

    public void checkPermission(UsbAccessory accessory) {
        if (!hasPermission(accessory)) {
            throw new SecurityException("User has not given permission to accessory " + accessory);
        }
    }

    private void requestPermissionDialog(Intent intent, String packageName, PendingIntent pi) {
        final int uid = Binder.getCallingUid();

        // compare uid with packageName to foil apps pretending to be someone else
        try {
            ApplicationInfo aInfo = mPackageManager.getApplicationInfo(packageName, 0);
            if (aInfo.uid != uid) {
                throw new IllegalArgumentException("package " + packageName +
                        " does not match caller's uid " + uid);
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("package " + packageName + " not found");
        }

        long identity = Binder.clearCallingIdentity();
        intent.setClassName("com.android.systemui",
                "com.android.systemui.usb.UsbPermissionActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(Intent.EXTRA_INTENT, pi);
        intent.putExtra("package", packageName);
        intent.putExtra(Intent.EXTRA_UID, uid);
        try {
            mUserContext.startActivityAsUser(intent, mUser);
        } catch (ActivityNotFoundException e) {
            Slog.e(TAG, "unable to start UsbPermissionActivity");
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void requestPermission(UsbDevice device, String packageName, PendingIntent pi, int uid) {
      Intent intent = new Intent();

        // respond immediately if permission has already been granted
      if (hasPermission(device, packageName, uid)) {
            intent.putExtra(UsbManager.EXTRA_DEVICE, device);
            intent.putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true);
            try {
                pi.send(mUserContext, 0, intent);
            } catch (PendingIntent.CanceledException e) {
                if (DEBUG) Slog.d(TAG, "requestPermission PendingIntent was cancelled");
            }
            return;
        }
        if (isCameraDevicePresent(device)) {
            if (!isCameraPermissionGranted(packageName, uid)) {
                intent.putExtra(UsbManager.EXTRA_DEVICE, device);
                intent.putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                try {
                    pi.send(mUserContext, 0, intent);
                } catch (PendingIntent.CanceledException e) {
                    if (DEBUG) Slog.d(TAG, "requestPermission PendingIntent was cancelled");
                }
                return;
            }
        }

        // start UsbPermissionActivity so user can choose an activity
        intent.putExtra(UsbManager.EXTRA_DEVICE, device);
        requestPermissionDialog(intent, packageName, pi);
    }

    public void requestPermission(UsbAccessory accessory, String packageName, PendingIntent pi) {
        Intent intent = new Intent();

        // respond immediately if permission has already been granted
        if (hasPermission(accessory)) {
            intent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
            intent.putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true);
            try {
                pi.send(mUserContext, 0, intent);
            } catch (PendingIntent.CanceledException e) {
                if (DEBUG) Slog.d(TAG, "requestPermission PendingIntent was cancelled");
            }
            return;
        }

        intent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
        requestPermissionDialog(intent, packageName, pi);
    }

    public void grantDevicePermission(UsbDevice device, int uid) {
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

    public void grantAccessoryPermission(UsbAccessory accessory, int uid) {
        synchronized (mLock) {
            SparseBooleanArray uidList = mAccessoryPermissionMap.get(accessory);
            if (uidList == null) {
                uidList = new SparseBooleanArray(1);
                mAccessoryPermissionMap.put(accessory, uidList);
            }
            uidList.put(uid, true);
        }
    }

    public void dump(@NonNull DualDumpOutputStream dump, @NonNull String idName, long id) {
        long token = dump.start(idName, id);

        synchronized (mLock) {
            dump.write("user_id", UsbUserSettingsManagerProto.USER_ID, mUser.getIdentifier());

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

        dump.end(token);
    }
}
