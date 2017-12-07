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
 * See the License for the specific language governing permissions an
 * limitations under the License.
 */

package com.android.server.usb;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.usb.descriptors.UsbDescriptorParser;
import com.android.server.usb.descriptors.UsbDeviceDescriptor;
import com.android.server.usb.descriptors.report.TextReportCanvas;
import com.android.server.usb.descriptors.tree.UsbDescriptorsTree;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;

/**
 * UsbHostManager manages USB state in host mode.
 */
public class UsbHostManager {
    private static final String TAG = UsbHostManager.class.getSimpleName();
    private static final boolean DEBUG = true;

    private final Context mContext;

    // USB busses to exclude from USB host support
    private final String[] mHostBlacklist;

    private final UsbAlsaManager mUsbAlsaManager;
    private final UsbSettingsManager mSettingsManager;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    // contains all connected USB devices
    private final HashMap<String, UsbDevice> mDevices = new HashMap<>();

    private Object mSettingsLock = new Object();
    @GuardedBy("mSettingsLock")
    private UsbProfileGroupSettingsManager mCurrentSettings;

    private Object mHandlerLock = new Object();
    @GuardedBy("mHandlerLock")
    private ComponentName mUsbDeviceConnectionHandler;

    /*
     * Member used for tracking connections & disconnections
     */
    static final SimpleDateFormat sFormat = new SimpleDateFormat("MM-dd HH:mm:ss:SSS");
    private static final int MAX_CONNECT_RECORDS = 32;
    private int mNumConnects;    // TOTAL # of connect/disconnect
    private final LinkedList<ConnectionRecord> mConnections = new LinkedList<ConnectionRecord>();
    private ConnectionRecord mLastConnect;

    /*
     * ConnectionRecord
     * Stores connection/disconnection data.
     */
    class ConnectionRecord {
        long mTimestamp;        // Same time-base as system log.
        String mDeviceAddress;

        static final int CONNECT = 0;
        static final int DISCONNECT = 1;
        final int mMode;
        final byte[] mDescriptors;

        ConnectionRecord(String deviceAddress, int mode, byte[] descriptors) {
            mTimestamp = System.currentTimeMillis();
            mDeviceAddress = deviceAddress;
            mMode = mode;
            mDescriptors = descriptors;
        }

        private String formatTime() {
            return (new StringBuilder(sFormat.format(new Date(mTimestamp)))).toString();
        }

        void dumpShort(IndentingPrintWriter pw) {
            if (mMode == CONNECT) {
                pw.println(formatTime() + " Connect " + mDeviceAddress);
                UsbDescriptorParser parser = new UsbDescriptorParser(mDeviceAddress, mDescriptors);

                UsbDeviceDescriptor deviceDescriptor = parser.getDeviceDescriptor();

                pw.println("manfacturer:0x" + Integer.toHexString(deviceDescriptor.getVendorID())
                        + " product:" + Integer.toHexString(deviceDescriptor.getProductID()));
                pw.println("isHeadset[in: " + parser.isInputHeadset()
                        + " , out: " + parser.isOutputHeadset() + "]");
            } else {
                pw.println(formatTime() + " Disconnect " + mDeviceAddress);
            }
        }

        void dumpLong(IndentingPrintWriter pw) {
            if (mMode == CONNECT) {
                pw.println(formatTime() + " Connect " + mDeviceAddress);
                UsbDescriptorParser parser = new UsbDescriptorParser(mDeviceAddress, mDescriptors);
                StringBuilder stringBuilder = new StringBuilder();
                UsbDescriptorsTree descriptorTree = new UsbDescriptorsTree();
                descriptorTree.parse(parser);
                descriptorTree.report(new TextReportCanvas(parser, stringBuilder));

                stringBuilder.append("isHeadset[in: " + parser.isInputHeadset()
                        + " , out: " + parser.isOutputHeadset() + "]");
                pw.println(stringBuilder.toString());
            } else {
                pw.println(formatTime() + " Disconnect " + mDeviceAddress);
            }
        }
    }

    /*
     * UsbHostManager
     */
    public UsbHostManager(Context context, UsbAlsaManager alsaManager,
            UsbSettingsManager settingsManager) {
        mContext = context;

        mHostBlacklist = context.getResources().getStringArray(
                com.android.internal.R.array.config_usbHostBlacklist);
        mUsbAlsaManager = alsaManager;
        mSettingsManager = settingsManager;
        String deviceConnectionHandler = context.getResources().getString(
                com.android.internal.R.string.config_UsbDeviceConnectionHandling_component);
        if (!TextUtils.isEmpty(deviceConnectionHandler)) {
            setUsbDeviceConnectionHandler(ComponentName.unflattenFromString(
                    deviceConnectionHandler));
        }
    }

    public void setCurrentUserSettings(UsbProfileGroupSettingsManager settings) {
        synchronized (mSettingsLock) {
            mCurrentSettings = settings;
        }
    }

    private UsbProfileGroupSettingsManager getCurrentUserSettings() {
        synchronized (mSettingsLock) {
            return mCurrentSettings;
        }
    }

    public void setUsbDeviceConnectionHandler(@Nullable ComponentName usbDeviceConnectionHandler) {
        synchronized (mHandlerLock) {
            mUsbDeviceConnectionHandler = usbDeviceConnectionHandler;
        }
    }

    private @Nullable ComponentName getUsbDeviceConnectionHandler() {
        synchronized (mHandlerLock) {
            return mUsbDeviceConnectionHandler;
        }
    }

    private boolean isBlackListed(String deviceAddress) {
        int count = mHostBlacklist.length;
        for (int i = 0; i < count; i++) {
            if (deviceAddress.startsWith(mHostBlacklist[i])) {
                return true;
            }
        }
        return false;
    }

    /* returns true if the USB device should not be accessible by applications */
    private boolean isBlackListed(int clazz, int subClass) {
        // blacklist hubs
        if (clazz == UsbConstants.USB_CLASS_HUB) return true;

        // blacklist HID boot devices (mouse and keyboard)
        return clazz == UsbConstants.USB_CLASS_HID
                && subClass == UsbConstants.USB_INTERFACE_SUBCLASS_BOOT;

    }

    private void addConnectionRecord(String deviceAddress, int mode, byte[] rawDescriptors) {
        mNumConnects++;
        while (mConnections.size() >= MAX_CONNECT_RECORDS) {
            mConnections.removeFirst();
        }
        ConnectionRecord rec =
                new ConnectionRecord(deviceAddress, mode, rawDescriptors);
        mConnections.add(rec);
        if (mode == ConnectionRecord.CONNECT) {
            mLastConnect = rec;
        }
    }

    /* Called from JNI in monitorUsbHostBus() to report new USB devices
       Returns true if successful, i.e. the USB Audio device descriptors are
       correctly parsed and the unique device is added to the audio device list.
     */
    @SuppressWarnings("unused")
    private boolean usbDeviceAdded(String deviceAddress, int deviceClass, int deviceSubclass,
            byte[] descriptors) {
        if (DEBUG) {
            Slog.d(TAG, "usbDeviceAdded(" + deviceAddress + ") - start");
        }

        // check class/subclass first as it is more likely to be blacklisted
        if (isBlackListed(deviceClass, deviceSubclass) || isBlackListed(deviceAddress)) {
            if (DEBUG) {
                Slog.d(TAG, "device is black listed");
            }
            return false;
        }

        synchronized (mLock) {
            if (mDevices.get(deviceAddress) != null) {
                Slog.w(TAG, "device already on mDevices list: " + deviceAddress);
                //TODO If this is the same peripheral as is being connected, replace
                // it with the new connection.
                return false;
            }

            UsbDescriptorParser parser = new UsbDescriptorParser(deviceAddress);
            if (parser.parseDescriptors(descriptors)) {

                UsbDevice newDevice = parser.toAndroidUsbDevice();
                mDevices.put(deviceAddress, newDevice);

                // It is fine to call this only for the current user as all broadcasts are sent to
                // all profiles of the user and the dialogs should only show once.
                ComponentName usbDeviceConnectionHandler = getUsbDeviceConnectionHandler();
                if (usbDeviceConnectionHandler == null) {
                    getCurrentUserSettings().deviceAttached(newDevice);
                } else {
                    getCurrentUserSettings().deviceAttachedForFixedHandler(newDevice,
                            usbDeviceConnectionHandler);
                }

                // Headset?
                boolean isInputHeadset = parser.isInputHeadset();
                boolean isOutputHeadset = parser.isOutputHeadset();
                Slog.i(TAG, "---- isHeadset[in: " + isInputHeadset
                        + " , out: " + isOutputHeadset + "]");

                mUsbAlsaManager.usbDeviceAdded(newDevice, isInputHeadset, isOutputHeadset);

                // Tracking
                addConnectionRecord(deviceAddress, ConnectionRecord.CONNECT,
                        parser.getRawDescriptors());
            } else {
                Slog.e(TAG, "Error parsing USB device descriptors for " + deviceAddress);
                return false;
            }
        }

        if (DEBUG) {
            Slog.d(TAG, "beginUsbDeviceAdded(" + deviceAddress + ") end");
        }

        return true;
    }

    /* Called from JNI in monitorUsbHostBus to report USB device removal */
    @SuppressWarnings("unused")
    private void usbDeviceRemoved(String deviceAddress) {
        synchronized (mLock) {
            UsbDevice device = mDevices.remove(deviceAddress);
            if (device != null) {
                mUsbAlsaManager.usbDeviceRemoved(device);
                mSettingsManager.usbDeviceRemoved(device);
                getCurrentUserSettings().usbDeviceRemoved(device);

                // Tracking
                addConnectionRecord(deviceAddress, ConnectionRecord.DISCONNECT, null);
            }
        }
    }

    public void systemReady() {
        synchronized (mLock) {
            // Create a thread to call into native code to wait for USB host events.
            // This thread will call us back on usbDeviceAdded and usbDeviceRemoved.
            Runnable runnable = this::monitorUsbHostBus;
            new Thread(null, runnable, "UsbService host thread").start();
        }
    }

    /* Returns a list of all currently attached USB devices */
    public void getDeviceList(Bundle devices) {
        synchronized (mLock) {
            for (String name : mDevices.keySet()) {
                devices.putParcelable(name, mDevices.get(name));
            }
        }
    }

    /* Opens the specified USB device */
    public ParcelFileDescriptor openDevice(String deviceAddress, UsbUserSettingsManager settings,
            String packageName, int uid) {
        synchronized (mLock) {
            if (isBlackListed(deviceAddress)) {
                throw new SecurityException("USB device is on a restricted bus");
            }
            UsbDevice device = mDevices.get(deviceAddress);
            if (device == null) {
                // if it is not in mDevices, it either does not exist or is blacklisted
                throw new IllegalArgumentException(
                        "device " + deviceAddress + " does not exist or is restricted");
            }

            settings.checkPermission(device, packageName, uid);
            return nativeOpenDevice(deviceAddress);
        }
    }

    public void dump(IndentingPrintWriter pw) {
        pw.println("USB Host State:");
        synchronized (mHandlerLock) {
            if (mUsbDeviceConnectionHandler != null) {
                pw.println("Default USB Host Connection handler: " + mUsbDeviceConnectionHandler);
            }
        }
        synchronized (mLock) {
            for (String name : mDevices.keySet()) {
                pw.println("  " + name + ": " + mDevices.get(name));
            }

            pw.println("" + mNumConnects + " total connects/disconnects");
            pw.println("Last " + mConnections.size() + " connections/disconnections");
            for (ConnectionRecord rec : mConnections) {
                rec.dumpShort(pw);
            }

            if (mLastConnect != null) {
                pw.println("Last Connected USB Device:");
                mLastConnect.dumpLong(pw);
            }
        }

        mUsbAlsaManager.dump(pw);
    }

    private native void monitorUsbHostBus();
    private native ParcelFileDescriptor nativeOpenDevice(String deviceAddress);
}
