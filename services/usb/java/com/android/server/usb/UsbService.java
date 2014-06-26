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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.SystemService;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * UsbService manages all USB related state, including both host and device support.
 * Host related events and calls are delegated to UsbHostManager, and device related
 * support is delegated to UsbDeviceManager.
 */
public class UsbService extends IUsbManager.Stub {

    public static class Lifecycle extends SystemService {
        private UsbService mUsbService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mUsbService = new UsbService(getContext());
            publishBinderService(Context.USB_SERVICE, mUsbService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mUsbService.systemReady();
            }
        }
    }

    private static final String TAG = "UsbService";

    private final Context mContext;

    private UsbDeviceManager mDeviceManager;
    private UsbHostManager mHostManager;

    private final Object mLock = new Object();

    /** Map from {@link UserHandle} to {@link UsbSettingsManager} */
    @GuardedBy("mLock")
    private final SparseArray<UsbSettingsManager>
            mSettingsByUser = new SparseArray<UsbSettingsManager>();

    private UsbSettingsManager getSettingsForUser(int userId) {
        synchronized (mLock) {
            UsbSettingsManager settings = mSettingsByUser.get(userId);
            if (settings == null) {
                settings = new UsbSettingsManager(mContext, new UserHandle(userId));
                mSettingsByUser.put(userId, settings);
            }
            return settings;
        }
    }

    public UsbService(Context context) {
        mContext = context;

        final PackageManager pm = mContext.getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            mHostManager = new UsbHostManager(context);
        }
        if (new File("/sys/class/android_usb").exists()) {
            mDeviceManager = new UsbDeviceManager(context);
        }

        setCurrentUser(UserHandle.USER_OWNER);

        final IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_SWITCHED);
        userFilter.addAction(Intent.ACTION_USER_STOPPED);
        mContext.registerReceiver(mUserReceiver, userFilter, null, null);
    }

    private BroadcastReceiver mUserReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
            final String action = intent.getAction();
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                setCurrentUser(userId);
            } else if (Intent.ACTION_USER_STOPPED.equals(action)) {
                synchronized (mLock) {
                    mSettingsByUser.remove(userId);
                }
            }
        }
    };

    private void setCurrentUser(int userId) {
        final UsbSettingsManager userSettings = getSettingsForUser(userId);
        if (mHostManager != null) {
            mHostManager.setCurrentSettings(userSettings);
        }
        if (mDeviceManager != null) {
            mDeviceManager.setCurrentSettings(userSettings);
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
    @Override
    public void getDeviceList(Bundle devices) {
        if (mHostManager != null) {
            mHostManager.getDeviceList(devices);
        }
    }

    /* Opens the specified USB device (host mode) */
    @Override
    public ParcelFileDescriptor openDevice(String deviceName) {
        if (mHostManager != null) {
            return mHostManager.openDevice(deviceName);
        } else {
            return null;
        }
    }

    /* returns the currently attached USB accessory (device mode) */
    @Override
    public UsbAccessory getCurrentAccessory() {
        if (mDeviceManager != null) {
            return mDeviceManager.getCurrentAccessory();
        } else {
            return null;
        }
    }

    /* opens the currently attached USB accessory (device mode) */
    @Override
    public ParcelFileDescriptor openAccessory(UsbAccessory accessory) {
        if (mDeviceManager != null) {
            return mDeviceManager.openAccessory(accessory);
        } else {
            return null;
        }
    }

    @Override
    public void setDevicePackage(UsbDevice device, String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        getSettingsForUser(userId).setDevicePackage(device, packageName);
    }

    @Override
    public void setAccessoryPackage(UsbAccessory accessory, String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        getSettingsForUser(userId).setAccessoryPackage(accessory, packageName);
    }

    @Override
    public boolean hasDevicePermission(UsbDevice device) {
        final int userId = UserHandle.getCallingUserId();
        return getSettingsForUser(userId).hasPermission(device);
    }

    @Override
    public boolean hasAccessoryPermission(UsbAccessory accessory) {
        final int userId = UserHandle.getCallingUserId();
        return getSettingsForUser(userId).hasPermission(accessory);
    }

    @Override
    public void requestDevicePermission(UsbDevice device, String packageName, PendingIntent pi) {
        final int userId = UserHandle.getCallingUserId();
        getSettingsForUser(userId).requestPermission(device, packageName, pi);
    }

    @Override
    public void requestAccessoryPermission(
            UsbAccessory accessory, String packageName, PendingIntent pi) {
        final int userId = UserHandle.getCallingUserId();
        getSettingsForUser(userId).requestPermission(accessory, packageName, pi);
    }

    @Override
    public void grantDevicePermission(UsbDevice device, int uid) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        final int userId = UserHandle.getUserId(uid);
        getSettingsForUser(userId).grantDevicePermission(device, uid);
    }

    @Override
    public void grantAccessoryPermission(UsbAccessory accessory, int uid) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        final int userId = UserHandle.getUserId(uid);
        getSettingsForUser(userId).grantAccessoryPermission(accessory, uid);
    }

    @Override
    public boolean hasDefaults(String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        return getSettingsForUser(userId).hasDefaults(packageName);
    }

    @Override
    public void clearDefaults(String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        getSettingsForUser(userId).clearDefaults(packageName);
    }

    @Override
    public void setCurrentFunction(String function, boolean makeDefault) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);

        // If attempt to change USB function while file transfer is restricted, ensure that
        // the current function is set to "none", and return.
        UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        if (userManager.hasUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER)) {
            if (mDeviceManager != null) mDeviceManager.setCurrentFunctions("none", false);
            return;
        }

        if (mDeviceManager != null) {
            mDeviceManager.setCurrentFunctions(function, makeDefault);
        } else {
            throw new IllegalStateException("USB device mode not supported");
        }
    }

    @Override
    public void setMassStorageBackingFile(String path) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        if (mDeviceManager != null) {
            mDeviceManager.setMassStorageBackingFile(path);
        } else {
            throw new IllegalStateException("USB device mode not supported");
        }
    }

    @Override
    public void allowUsbDebugging(boolean alwaysAllow, String publicKey) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mDeviceManager.allowUsbDebugging(alwaysAllow, publicKey);
    }

    @Override
    public void denyUsbDebugging() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mDeviceManager.denyUsbDebugging();
    }

    @Override
    public void clearUsbDebuggingKeys() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mDeviceManager.clearUsbDebuggingKeys();
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");

        pw.println("USB Manager State:");
        if (mDeviceManager != null) {
            mDeviceManager.dump(fd, pw);
        }
        if (mHostManager != null) {
            mHostManager.dump(fd, pw);
        }

        synchronized (mLock) {
            for (int i = 0; i < mSettingsByUser.size(); i++) {
                final int userId = mSettingsByUser.keyAt(i);
                final UsbSettingsManager settings = mSettingsByUser.valueAt(i);
                pw.increaseIndent();
                pw.println("Settings for user " + userId + ":");
                settings.dump(fd, pw);
                pw.decreaseIndent();
            }
        }
        pw.decreaseIndent();
    }
}
