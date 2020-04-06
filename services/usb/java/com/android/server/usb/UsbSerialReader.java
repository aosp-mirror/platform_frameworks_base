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
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.usb.IUsbSerialReader;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;

import com.android.internal.util.ArrayUtils;

/**
 * Allows an app to read the serial number of the {@link UsbDevice}/{@link UsbAccessory} only if
 * the app has got the permission to do so.
 */
class UsbSerialReader extends IUsbSerialReader.Stub {
    private final @Nullable String mSerialNumber;
    private final @NonNull Context mContext;
    private final @NonNull UsbSettingsManager mSettingsManager;

    private Object mDevice;

    /**
     * Create an new {@link UsbSerialReader}. It is mandatory to call {@link #setDevice(Object)}
     * immediately after this.
     *
     * @param context A context to be used by the reader
     * @param settingsManager The USB settings manager
     * @param serialNumber The serial number that might be read
     */
    UsbSerialReader(@NonNull Context context, @NonNull UsbSettingsManager settingsManager,
            @Nullable String serialNumber) {
        mContext = context;
        mSettingsManager = settingsManager;
        mSerialNumber = serialNumber;
    }

    /**
     * Set the {@link UsbDevice}/{@link UsbAccessory} the serial number belongs to
     *
     * @param device The device/accessory
     */
    public void setDevice(@NonNull Object device) {
        mDevice = device;
    }

    @Override
    public String getSerial(String packageName) throws RemoteException {
        int pid = Binder.getCallingPid();
        int uid = Binder.getCallingUid();

        if (uid != Process.SYSTEM_UID) {
            enforcePackageBelongsToUid(uid, packageName);

            int packageTargetSdkVersion;
            long token = Binder.clearCallingIdentity();
            try {
                PackageInfo pkg;
                try {
                    pkg = mContext.getPackageManager().getPackageInfo(packageName, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    throw new RemoteException("package " + packageName + " cannot be found");
                }
                packageTargetSdkVersion = pkg.applicationInfo.targetSdkVersion;

                if (packageTargetSdkVersion >= Build.VERSION_CODES.Q) {
                    if (mContext.checkPermission(android.Manifest.permission.MANAGE_USB, pid, uid)
                            == PackageManager.PERMISSION_DENIED) {
                        UsbUserSettingsManager settings = mSettingsManager.getSettingsForUser(
                                UserHandle.getUserId(uid));

                        if (mDevice instanceof UsbDevice) {
                            settings.checkPermission((UsbDevice) mDevice, packageName, pid, uid);
                        } else {
                            settings.checkPermission((UsbAccessory) mDevice, uid);
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        return mSerialNumber;
    }

    private void enforcePackageBelongsToUid(int uid, @NonNull String packageName) {
        String[] packages = mContext.getPackageManager().getPackagesForUid(uid);

        if (!ArrayUtils.contains(packages, packageName)) {
            throw new IllegalArgumentException(packageName + " does to belong to the " + uid);
        }
    }
}
