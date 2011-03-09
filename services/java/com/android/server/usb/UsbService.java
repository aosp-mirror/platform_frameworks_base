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
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
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

    // contains all connected USB devices (for USB host mode)
    private final HashMap<String,UsbDevice> mDevices = new HashMap<String,UsbDevice>();

    // USB busses to exclude from USB host support
    private final String[] mHostBlacklist;

    private boolean mSystemReady;

    private UsbAccessory mCurrentAccessory;
    // USB functions that are enabled by default, to restore after exiting accessory mode
    private final ArrayList<String> mDefaultFunctions = new ArrayList<String>();

    private final Context mContext;
    private final Object mLock = new Object();
    private final UsbDeviceSettingsManager mDeviceManager;
    private final boolean mHasUsbHost;
    private final boolean mHasUsbAccessory;

    /*
     * Handles USB function enable/disable events (device mode)
     */
    private final void functionEnabledLocked(String function, boolean enabled) {
        boolean enteringAccessoryMode =
            (mHasUsbAccessory && enabled && UsbManager.USB_FUNCTION_ACCESSORY.equals(function));

        if (enabled) {
            if (!mEnabledFunctions.contains(function)) {
                mEnabledFunctions.add(function);
            }
            mDisabledFunctions.remove(function);
        } else {
            if (!mDisabledFunctions.contains(function)) {
                mDisabledFunctions.add(function);
            }
            mEnabledFunctions.remove(function);
        }

        if (enteringAccessoryMode) {
            String[] strings = nativeGetAccessoryStrings();
            if (strings != null) {
                mCurrentAccessory = new UsbAccessory(strings);
                Log.d(TAG, "entering USB accessory mode: " + mCurrentAccessory);
                mDeviceManager.accessoryAttached(mCurrentAccessory);
            } else {
                Log.e(TAG, "nativeGetAccessoryStrings failed");
            }
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

    public UsbService(Context context) {
        mContext = context;
        mDeviceManager = new UsbDeviceSettingsManager(context);
        PackageManager pm = mContext.getPackageManager();
        mHasUsbHost = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST);
        mHasUsbAccessory = pm.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY);

        mHostBlacklist = context.getResources().getStringArray(
                com.android.internal.R.array.config_usbHostBlacklist);

        init();  // set initial status

        if (mConfiguration >= 0) {
            mUEventObserver.startObserving(USB_CONNECTED_MATCH);
            mUEventObserver.startObserving(USB_CONFIGURATION_MATCH);
            mUEventObserver.startObserving(USB_FUNCTIONS_MATCH);
        }
    }

    private final void init() {
        char[] buffer = new char[1024];

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
        if (mConfiguration < 0)
            return;

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
                    // adb is enabled/disabled automatically by the adbd daemon,
                    // so don't treat it as a default function
                    if (!UsbManager.USB_FUNCTION_ADB.equals(functionName)) {
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
    }

    private boolean isBlackListed(String deviceName) {
        int count = mHostBlacklist.length;
        for (int i = 0; i < count; i++) {
            if (deviceName.startsWith(mHostBlacklist[i])) {
                return true;
            }
        }
        return false;
    }

    /* returns true if the USB device should not be accessible by applications (host mode) */
    private boolean isBlackListed(int clazz, int subClass, int protocol) {
        // blacklist hubs
        if (clazz == UsbConstants.USB_CLASS_HUB) return true;

        // blacklist HID boot devices (mouse and keyboard)
        if (clazz == UsbConstants.USB_CLASS_HID &&
                subClass == UsbConstants.USB_INTERFACE_SUBCLASS_BOOT) {
            return true;
        }

        return false;
    }

    /* Called from JNI in monitorUsbHostBus() to report new USB devices (host mode) */
    private void usbDeviceAdded(String deviceName, int vendorID, int productID,
            int deviceClass, int deviceSubclass, int deviceProtocol,
            /* array of quintuples containing id, class, subclass, protocol
               and number of endpoints for each interface */
            int[] interfaceValues,
           /* array of quadruples containing address, attributes, max packet size
              and interval for each endpoint */
            int[] endpointValues) {

        if (isBlackListed(deviceName) ||
                isBlackListed(deviceClass, deviceSubclass, deviceProtocol)) {
            return;
        }

        synchronized (mLock) {
            if (mDevices.get(deviceName) != null) {
                Log.w(TAG, "device already on mDevices list: " + deviceName);
                return;
            }

            int numInterfaces = interfaceValues.length / 5;
            Parcelable[] interfaces = new UsbInterface[numInterfaces];
            try {
                // repackage interfaceValues as an array of UsbInterface
                int intf, endp, ival = 0, eval = 0;
                for (intf = 0; intf < numInterfaces; intf++) {
                    int interfaceId = interfaceValues[ival++];
                    int interfaceClass = interfaceValues[ival++];
                    int interfaceSubclass = interfaceValues[ival++];
                    int interfaceProtocol = interfaceValues[ival++];
                    int numEndpoints = interfaceValues[ival++];

                    Parcelable[] endpoints = new UsbEndpoint[numEndpoints];
                    for (endp = 0; endp < numEndpoints; endp++) {
                        int address = endpointValues[eval++];
                        int attributes = endpointValues[eval++];
                        int maxPacketSize = endpointValues[eval++];
                        int interval = endpointValues[eval++];
                        endpoints[endp] = new UsbEndpoint(address, attributes,
                                maxPacketSize, interval);
                    }

                    // don't allow if any interfaces are blacklisted
                    if (isBlackListed(interfaceClass, interfaceSubclass, interfaceProtocol)) {
                        return;
                    }
                    interfaces[intf] = new UsbInterface(interfaceId, interfaceClass,
                            interfaceSubclass, interfaceProtocol, endpoints);
                }
            } catch (Exception e) {
                // beware of index out of bound exceptions, which might happen if
                // a device does not set bNumEndpoints correctly
                Log.e(TAG, "error parsing USB descriptors", e);
                return;
            }

            UsbDevice device = new UsbDevice(deviceName, vendorID, productID,
                    deviceClass, deviceSubclass, deviceProtocol, interfaces);
            mDevices.put(deviceName, device);
            mDeviceManager.deviceAttached(device);
        }
    }

    /* Called from JNI in monitorUsbHostBus to report USB device removal (host mode) */
    private void usbDeviceRemoved(String deviceName) {
        synchronized (mLock) {
            UsbDevice device = mDevices.remove(deviceName);
            if (device != null) {
                mDeviceManager.deviceDetached(device);
            }
        }
    }

    private void initHostSupport() {
        // Create a thread to call into native code to wait for USB host events.
        // This thread will call us back on usbDeviceAdded and usbDeviceRemoved.
        Runnable runnable = new Runnable() {
            public void run() {
                monitorUsbHostBus();
            }
        };
        new Thread(null, runnable, "UsbService host thread").start();
    }

    public void systemReady() {
        synchronized (mLock) {
            if (mHasUsbHost) {
                // start monitoring for connected USB devices
                initHostSupport();
            }

            update(false);
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

    /* Returns a list of all currently attached USB devices (host mdoe) */
    public void getDeviceList(Bundle devices) {
        synchronized (mLock) {
            for (String name : mDevices.keySet()) {
                devices.putParcelable(name, mDevices.get(name));
            }
        }
    }

    /* Opens the specified USB device (host mode) */
    public ParcelFileDescriptor openDevice(String deviceName) {
        synchronized (mLock) {
            if (isBlackListed(deviceName)) {
                throw new SecurityException("USB device is on a restricted bus");
            }
            UsbDevice device = mDevices.get(deviceName);
            if (device == null) {
                // if it is not in mDevices, it either does not exist or is blacklisted
                throw new IllegalArgumentException(
                        "device " + deviceName + " does not exist or is restricted");
            }
            mDeviceManager.checkPermission(device);
            return nativeOpenDevice(deviceName);
        }
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

    public void setDevicePackage(UsbDevice device, String packageName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mDeviceManager.setDevicePackage(device, packageName);
    }

    public void setAccessoryPackage(UsbAccessory accessory, String packageName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mDeviceManager.setAccessoryPackage(accessory, packageName);
    }

    public boolean hasDevicePermission(UsbDevice device) {
        return mDeviceManager.hasPermission(device);
    }

    public boolean hasAccessoryPermission(UsbAccessory accessory) {
        return mDeviceManager.hasPermission(accessory);
    }

    public void requestDevicePermission(UsbDevice device, String packageName,
            PendingIntent pi) {
        mDeviceManager.requestPermission(device, packageName, pi);
    }

    public void requestAccessoryPermission(UsbAccessory accessory, String packageName,
            PendingIntent pi) {
        mDeviceManager.requestPermission(accessory, packageName, pi);
    }

    public void grantDevicePermission(UsbDevice device, int uid) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USB, null);
        mDeviceManager.grantDevicePermission(device, uid);
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
                                // make sure accessory mode is off, and restore default functions
                                if (UsbManager.setFunctionEnabled(
                                        UsbManager.USB_FUNCTION_ACCESSORY, false)) {
                                    Log.d(TAG, "exited USB accessory mode");

                                    int count = mDefaultFunctions.size();
                                    for (int i = 0; i < count; i++) {
                                        String function = mDefaultFunctions.get(i);
                                        if (UsbManager.setFunctionEnabled(function, true)) {
                                            Log.e(TAG, "could not reenable function " + function);
                                        }
                                    }
                                }

                                if (mCurrentAccessory != null) {
                                    mDeviceManager.accessoryDetached(mCurrentAccessory);
                                    mCurrentAccessory = null;
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

            pw.println("  USB Host State:");
            for (String name : mDevices.keySet()) {
                pw.println("    " + name + ": " + mDevices.get(name));
            }
            mDeviceManager.dump(fd, pw);
        }
    }

    // host support
    private native void monitorUsbHostBus();
    private native ParcelFileDescriptor nativeOpenDevice(String deviceName);
    // accessory support
    private native String[] nativeGetAccessoryStrings();
    private native ParcelFileDescriptor nativeOpenAccessory();
}
