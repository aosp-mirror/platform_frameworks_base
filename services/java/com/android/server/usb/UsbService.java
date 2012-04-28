/*
 * Copyright (C) 2010 The Android Open Source Project
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
 * See the License for the specific language governing permissions an
 * limitations under the License.
 */

package com.android.server.usb;

import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * UsbService manages all USB related state, including both host and device support.
 * Host related events and calls are delegated to UsbHostManager, and device related
 * support is delegated to UsbDeviceManager.
 */
public class UsbService extends IUsbManager.Stub {
    private final Context mContext;
    private UsbDeviceManager mDeviceManager;
    private UsbHostManager mHostManager;
    private final UsbSettingsManager mSettingsManager;


    public UsbService(Context context) {
        mContext = context;
        mSettingsManager = new UsbSettingsManager(context);
        PackageManager pm = mContext.getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            mHostManager = new UsbHostManager(context, mSettingsManager);
        }
        if (new File("/sys/class/android_usb").exists()) {
            mDeviceManager = new UsbDeviceManager(context, mSettingsManager);
        }
    }

    public void systemReady() {
        if (mDeviceManager != null) {
            mDeviceManager.systemReady();
        }
        if (mHostManager != null) {
            mHostManager.systemReady();
        }
    }

    /* Returns a list of all currently attached USB devices (host mdoe) */
    public void getDeviceList(Bundle devices) {
        if (mHostManager != null) {
            mHostManager.getDeviceList(devices);
        }
    }

    /* Opens the specified USB device (host mode) */
    public ParcelFileDescriptor openDevice(String deviceName) {
        if (mHostManager != null) {
            return mHostManager.openDevice(deviceName);
        } else {
            return null;
        }
    }

    /* returns the currently attached USB accessory (device mode) */
    public UsbAccessory getCurrentAccessory() {
        if (mDeviceManager != null) {
            return mDeviceManager.getCurrentAccessory();
        } else {
            return null;
        }
    }

    /* opens the currently attached USB accessory (device mode) */
    public ParcelFileDescriptor openAccessory(UsbAccessory accessory) {
        if (mDeviceManager != null) {
            return mDeviceManager.openAccessory(accessory);
        } else {
            return null;
        }
    }

    public void setDevicePackage(UsbDevice device, String packageName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mSettingsManager.setDevicePackage(device, packageName);
    }

    public void setAccessoryPackage(UsbAccessory accessory, String packageName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mSettingsManager.setAccessoryPackage(accessory, packageName);
    }

    public boolean hasDevicePermission(UsbDevice device) {
        return mSettingsManager.hasPermission(device);
    }

    public boolean hasAccessoryPermission(UsbAccessory accessory) {
        return mSettingsManager.hasPermission(accessory);
    }

    public void requestDevicePermission(UsbDevice device, String packageName,
            PendingIntent pi) {
        mSettingsManager.requestPermission(device, packageName, pi);
    }

    public void requestAccessoryPermission(UsbAccessory accessory, String packageName,
            PendingIntent pi) {
        mSettingsManager.requestPermission(accessory, packageName, pi);
    }

    public void grantDevicePermission(UsbDevice device, int uid) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mSettingsManager.grantDevicePermission(device, uid);
    }

    public void grantAccessoryPermission(UsbAccessory accessory, int uid) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mSettingsManager.grantAccessoryPermission(accessory, uid);
    }

    public boolean hasDefaults(String packageName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        return mSettingsManager.hasDefaults(packageName);
    }

    public void clearDefaults(String packageName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mSettingsManager.clearDefaults(packageName);
    }

    public void setCurrentFunction(String function, boolean makeDefault) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        if (mDeviceManager != null) {
            mDeviceManager.setCurrentFunctions(function, makeDefault);
        } else {
            throw new IllegalStateException("USB device mode not supported");
        }
    }

    public void setMassStorageBackingFile(String path) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        if (mDeviceManager != null) {
            mDeviceManager.setMassStorageBackingFile(path);
        } else {
            throw new IllegalStateException("USB device mode not supported");
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump UsbManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        pw.println("USB Manager State:");

        if (mDeviceManager != null) {
            mDeviceManager.dump(fd, pw);
        }
        if (mHostManager != null) {
            mHostManager.dump(fd, pw);
        }
        mSettingsManager.dump(fd, pw);
    }
}
