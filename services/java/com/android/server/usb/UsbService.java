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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.usb;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.os.ParcelFileDescriptor;
import android.os.UEventObserver;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * UsbService monitors for changes to USB state.
 * This includes code for both USB host support (where the android device is the host)
 * as well as USB device support (android device is connected to a USB host).
 * Accessory mode is a special case of USB device mode, where the android device is
 * connected to a USB host that supports the android accessory protocol.
 */
public class UsbService extends IUsbManager.Stub {
    private static final String TAG = UsbService.class.getSimpleName();
    private static final boolean LOG = false;

    private static final String USB_CONNECTED_MATCH =
            "DEVPATH=/devices/virtual/switch/usb_connected";
    private static final String USB_CONFIGURATION_MATCH =
            "DEVPATH=/devices/virtual/switch/usb_configuration";
    private static final String USB_FUNCTIONS_MATCH =
            "DEVPATH=/devices/virtual/usb_composite/";
    private static final String USB_CONNECTED_PATH =
            "/sys/class/switch/usb_connected/state";
    private static final String USB_CONFIGURATION_PATH =
            "/sys/class/switch/usb_configuration/state";
    private static final String USB_COMPOSITE_CLASS_PATH =
            "/sys/class/usb_composite";

    private static final int MSG_UPDATE_STATE = 0;
    private static final int MSG_FUNCTION_ENABLED = 1;
    private static final int MSG_FUNCTION_DISABLED = 2;

    // Delay for debouncing USB disconnects.
    // We often get rapid connect/disconnect events when enabling USB functions,
    // which need debouncing.
    private static final int UPDATE_DELAY = 1000;

    // current connected and configuration state
    private int mConnected;
    private int mConfiguration;

    // last broadcasted connected and configuration state
    private int mLastConnected = -1;
    private int mLastConfiguration = -1;

    // lists of enabled and disabled USB functions (for USB device mode)
    private final ArrayList<String> mEnabledFunctions = new ArrayList<String>();
    private final ArrayList<String> mDisabledFunctions = new ArrayList<String>();

    private boolean mSystemReady;

    private UsbAccessory mCurrentAccessory;
    // USB functions that are enabled by default, to restore after exiting accessory mode
    private final ArrayList<String> mDefaultFunctions = new ArrayList<String>();

    private final Context mContext;
    private final Object mLock = new Object();
    private final UsbDeviceSettingsManager mDeviceManager;
    private final boolean mHasUsbAccessory;

    private final void readCurrentAccessoryLocked() {
        if (mHasUsbAccessory) {
            String[] strings = nativeGetAccessoryStrings();
            if (strings != null) {
                mCurrentAccessory = new UsbAccessory(strings);
                Log.d(TAG, "entering USB accessory mode: " + mCurrentAccessory);
                if (mSystemReady) {
                    mDeviceManager.accessoryAttached(mCurrentAccessory);
                }
            } else {
                Log.e(TAG, "nativeGetAccessoryStrings failed");
            }
        }
    }

    /*
     * Handles USB function enable/disable events (device mode)
     */
    private final void functionEnabledLocked(String function, boolean enabled) {
        if (enabled) {
            if (!mEnabledFunctions.contains(function)) {
                mEnabledFunctions.add(function);
            }
            mDisabledFunctions.remove(function);

            if (UsbManager.USB_FUNCTION_ACCESSORY.equals(function)) {
                readCurrentAccessoryLocked();
            }
        } else {
            if (!mDisabledFunctions.contains(function)) {
                mDisabledFunctions.add(function);
            }
            mEnabledFunctions.remove(function);
        }
    }

    /*
     * Listens for uevent messages from the kernel to monitor the USB state (device mode)
     */
    private final UEventObserver mUEventObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEventObserver.UEvent event) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Slog.v(TAG, "USB UEVENT: " + event.toString());
            }

            synchronized (mLock) {
                String name = event.get("SWITCH_NAME");
                String state = event.get("SWITCH_STATE");
                if (name != null && state != null) {
                    try {
                        int intState = Integer.parseInt(state);
                        if ("usb_connected".equals(name)) {
                            mConnected = intState;
                            // trigger an Intent broadcast
                            if (mSystemReady) {
                                // debounce disconnects to avoid problems bringing up USB tethering
                                update(mConnected == 0);
                            }
                        } else if ("usb_configuration".equals(name)) {
                            mConfiguration = intState;
                            // trigger an Intent broadcast
                            if (mSystemReady) {
                                update(mConnected == 0);
                            }
                        }
                    } catch (NumberFormatException e) {
                        Slog.e(TAG, "Could not parse switch state from event " + event);
                    }
                } else {
                    String function = event.get("FUNCTION");
                    String enabledStr = event.get("ENABLED");
                    if (function != null && enabledStr != null) {
                        // Note: we do not broadcast a change when a function is enabled or disabled.
                        // We just record the state change for the next broadcast.
                        int what = ("1".equals(enabledStr) ?
                                MSG_FUNCTION_ENABLED : MSG_FUNCTION_DISABLED);
                        Message msg = Message.obtain(mHandler, what);
                        msg.obj = function;
                        mHandler.sendMessage(msg);
                    }
                }
            }
        }
    };

   private final BroadcastReceiver mBootCompletedReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            // handle accessories attached at boot time
            synchronized (mLock) {
                if (mCurrentAccessory != null) {
                    mDeviceManager.accessoryAttached(mCurrentAccessory);
                }
            }
        }
    };

    public UsbService(Context context) {
        mContext = context;
        mDeviceManager = new UsbDeviceSettingsManager(context);
        PackageManager pm = mContext.getPackageManager();
        mHasUsbAccessory = pm.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY);

        synchronized (mLock) {
            init();  // set initial status

            // Watch for USB configuration changes
            if (mConfiguration >= 0) {
                mUEventObserver.startObserving(USB_CONNECTED_MATCH);
                mUEventObserver.startObserving(USB_CONFIGURATION_MATCH);
                mUEventObserver.startObserving(USB_FUNCTIONS_MATCH);
            }
        }
    }

    private final void init() {
        char[] buffer = new char[1024];
        boolean inAccessoryMode = false;

        // Read initial USB state (device mode)
        mConfiguration = -1;
        try {
            FileReader file = new FileReader(USB_CONNECTED_PATH);
            int len = file.read(buffer, 0, 1024);
            file.close();
            mConnected = Integer.valueOf((new String(buffer, 0, len)).trim());

            file = new FileReader(USB_CONFIGURATION_PATH);
            len = file.read(buffer, 0, 1024);
            file.close();
            mConfiguration = Integer.valueOf((new String(buffer, 0, len)).trim());

        } catch (FileNotFoundException e) {
            Slog.i(TAG, "This kernel does not have USB configuration switch support");
        } catch (Exception e) {
            Slog.e(TAG, "" , e);
        }
        if (mConfiguration < 0) {
            // This may happen in the emulator or devices without USB device mode support
            return;
        }

        // Read initial list of enabled and disabled functions (device mode)
        try {
            File[] files = new File(USB_COMPOSITE_CLASS_PATH).listFiles();
            for (int i = 0; i < files.length; i++) {
                File file = new File(files[i], "enable");
                FileReader reader = new FileReader(file);
                int len = reader.read(buffer, 0, 1024);
                reader.close();
                int value = Integer.valueOf((new String(buffer, 0, len)).trim());
                String functionName = files[i].getName();
                if (value == 1) {
                    mEnabledFunctions.add(functionName);
                if (UsbManager.USB_FUNCTION_ACCESSORY.equals(functionName)) {
                        // The USB accessory driver is on by default, but it might have been
                        // enabled before the USB service has initialized.
                        inAccessoryMode = true;
                    } else if (!UsbManager.USB_FUNCTION_ADB.equals(functionName)) {
                        // adb is enabled/disabled automatically by the adbd daemon,
                        // so don't treat it as a default function.
                        mDefaultFunctions.add(functionName);
                    }
                } else {
                    mDisabledFunctions.add(functionName);
                }
            }
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "This kernel does not have USB composite class support");
        } catch (Exception e) {
            Slog.e(TAG, "" , e);
        }

        // handle the case where an accessory switched the driver to accessory mode
        // before the framework finished booting
        if (inAccessoryMode) {
            readCurrentAccessoryLocked();

            // FIXME - if we booted in accessory mode, then we have no way to figure out
            // which functions are enabled by default.
            // For now, assume that MTP or mass storage are the only possibilities
            if (mDisabledFunctions.contains(UsbManager.USB_FUNCTION_MTP)) {
                mDefaultFunctions.add(UsbManager.USB_FUNCTION_MTP);
            } else if (mDisabledFunctions.contains(UsbManager.USB_FUNCTION_MASS_STORAGE)) {
                mDefaultFunctions.add(UsbManager.USB_FUNCTION_MASS_STORAGE);
            }
        }
    }

    public void systemReady() {
        synchronized (mLock) {
            update(false);
            if (mCurrentAccessory != null) {
                Log.d(TAG, "accessoryAttached at systemReady");
                // its still too early to handle accessories, so add a BOOT_COMPLETED receiver
                // to handle this later.
                mContext.registerReceiver(mBootCompletedReceiver,
                        new IntentFilter(Intent.ACTION_BOOT_COMPLETED));
            }
            mSystemReady = true;
        }
    }

    /*
     * Sends a message to update the USB connected and configured state (device mode).
     * If delayed is true, then we add a small delay in sending the message to debounce
     * the USB connection when enabling USB tethering.
     */
    private final void update(boolean delayed) {
        mHandler.removeMessages(MSG_UPDATE_STATE);
        mHandler.sendEmptyMessageDelayed(MSG_UPDATE_STATE, delayed ? UPDATE_DELAY : 0);
    }

    /* returns the currently attached USB accessory (device mode) */
    public UsbAccessory getCurrentAccessory() {
        return mCurrentAccessory;
    }

    /* opens the currently attached USB accessory (device mode) */
    public ParcelFileDescriptor openAccessory(UsbAccessory accessory) {
        synchronized (mLock) {
            if (mCurrentAccessory == null) {
                throw new IllegalArgumentException("no accessory attached");
            }
            if (!mCurrentAccessory.equals(accessory)) {
                Log.e(TAG, accessory.toString() + " does not match current accessory "
                        + mCurrentAccessory);
                throw new IllegalArgumentException("accessory not attached");
            }
            mDeviceManager.checkPermission(mCurrentAccessory);
            return nativeOpenAccessory();
        }
    }

    public void setAccessoryPackage(UsbAccessory accessory, String packageName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mDeviceManager.setAccessoryPackage(accessory, packageName);
    }

    public boolean hasAccessoryPermission(UsbAccessory accessory) {
        return mDeviceManager.hasPermission(accessory);
    }

    public void requestAccessoryPermission(UsbAccessory accessory, String packageName,
            PendingIntent pi) {
        mDeviceManager.requestPermission(accessory, packageName, pi);
    }

    public void grantAccessoryPermission(UsbAccessory accessory, int uid) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mDeviceManager.grantAccessoryPermission(accessory, uid);
    }

    public boolean hasDefaults(String packageName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        return mDeviceManager.hasDefaults(packageName);
    }

    public void clearDefaults(String packageName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mDeviceManager.clearDefaults(packageName);
    }

    /*
     * This handler is for deferred handling of events related to device mode and accessories.
     */
    private final Handler mHandler = new Handler() {
        private void addEnabledFunctionsLocked(Intent intent) {
            // include state of all USB functions in our extras
            for (int i = 0; i < mEnabledFunctions.size(); i++) {
                intent.putExtra(mEnabledFunctions.get(i), UsbManager.USB_FUNCTION_ENABLED);
            }
            for (int i = 0; i < mDisabledFunctions.size(); i++) {
                intent.putExtra(mDisabledFunctions.get(i), UsbManager.USB_FUNCTION_DISABLED);
            }
        }

        @Override
        public void handleMessage(Message msg) {
            synchronized (mLock) {
                switch (msg.what) {
                    case MSG_UPDATE_STATE:
                        if (mConnected != mLastConnected || mConfiguration != mLastConfiguration) {
                            if (mConnected == 0) {
                                if (UsbManager.isFunctionEnabled(
                                            UsbManager.USB_FUNCTION_ACCESSORY)) {
                                    // make sure accessory mode is off, and restore default functions
                                    Log.d(TAG, "exited USB accessory mode");
                                    if (!UsbManager.setFunctionEnabled
                                            (UsbManager.USB_FUNCTION_ACCESSORY, false)) {
                                        Log.e(TAG, "could not disable accessory function");
                                    }
                                    int count = mDefaultFunctions.size();
                                    for (int i = 0; i < count; i++) {
                                        String function = mDefaultFunctions.get(i);
                                        if (!UsbManager.setFunctionEnabled(function, true)) {
                                            Log.e(TAG, "could not reenable function " + function);
                                        }
                                    }

                                    if (mCurrentAccessory != null) {
                                        mDeviceManager.accessoryDetached(mCurrentAccessory);
                                        mCurrentAccessory = null;
                                    }
                                }
                            }

                            final ContentResolver cr = mContext.getContentResolver();
                            if (Settings.Secure.getInt(cr,
                                    Settings.Secure.DEVICE_PROVISIONED, 0) == 0) {
                                Slog.i(TAG, "Device not provisioned, skipping USB broadcast");
                                return;
                            }

                            mLastConnected = mConnected;
                            mLastConfiguration = mConfiguration;

                            // send a sticky broadcast containing current USB state
                            Intent intent = new Intent(UsbManager.ACTION_USB_STATE);
                            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                            intent.putExtra(UsbManager.USB_CONNECTED, mConnected != 0);
                            intent.putExtra(UsbManager.USB_CONFIGURATION, mConfiguration);
                            addEnabledFunctionsLocked(intent);
                            mContext.sendStickyBroadcast(intent);
                        }
                        break;
                    case MSG_FUNCTION_ENABLED:
                    case MSG_FUNCTION_DISABLED:
                        functionEnabledLocked((String)msg.obj, msg.what == MSG_FUNCTION_ENABLED);
                        break;
                }
            }
        }
    };

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump UsbManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        synchronized (mLock) {
            pw.println("USB Manager State:");

            pw.println("  USB Device State:");
            pw.print("    Enabled Functions: ");
            for (int i = 0; i < mEnabledFunctions.size(); i++) {
                pw.print(mEnabledFunctions.get(i) + " ");
            }
            pw.println("");
            pw.print("    Disabled Functions: ");
            for (int i = 0; i < mDisabledFunctions.size(); i++) {
                pw.print(mDisabledFunctions.get(i) + " ");
            }
            pw.println("");
            pw.print("    Default Functions: ");
            for (int i = 0; i < mDefaultFunctions.size(); i++) {
                pw.print(mDefaultFunctions.get(i) + " ");
            }
            pw.println("");
            pw.println("    mConnected: " + mConnected + ", mConfiguration: " + mConfiguration);
            pw.println("    mCurrentAccessory: " + mCurrentAccessory);

            mDeviceManager.dump(fd, pw);
        }
    }

    // accessory support
    private native String[] nativeGetAccessoryStrings();
    private native ParcelFileDescriptor nativeOpenAccessory();
}
