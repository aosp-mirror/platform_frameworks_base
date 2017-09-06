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
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.usb.descriptors.UsbDescriptorParser;
import com.android.server.usb.descriptors.report.TextReportCanvas;
import com.android.server.usb.descriptors.tree.UsbDescriptorsTree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

/**
 * UsbHostManager manages USB state in host mode.
 */
public class UsbHostManager {
    private static final String TAG = UsbHostManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final Context mContext;

    // contains all connected USB devices
    private final HashMap<String, UsbDevice> mDevices = new HashMap<>();

    // USB busses to exclude from USB host support
    private final String[] mHostBlacklist;

    private final Object mLock = new Object();

    private UsbDevice mNewDevice;
    private UsbConfiguration mNewConfiguration;
    private UsbInterface mNewInterface;
    private ArrayList<UsbConfiguration> mNewConfigurations;
    private ArrayList<UsbInterface> mNewInterfaces;
    private ArrayList<UsbEndpoint> mNewEndpoints;

    private final UsbAlsaManager mUsbAlsaManager;
    private final UsbSettingsManager mSettingsManager;

    @GuardedBy("mLock")
    private UsbProfileGroupSettingsManager mCurrentSettings;

    @GuardedBy("mLock")
    private ComponentName mUsbDeviceConnectionHandler;

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
        synchronized (mLock) {
            mCurrentSettings = settings;
        }
    }

    private UsbProfileGroupSettingsManager getCurrentUserSettings() {
        synchronized (mLock) {
            return mCurrentSettings;
        }
    }

    public void setUsbDeviceConnectionHandler(@Nullable ComponentName usbDeviceConnectionHandler) {
        synchronized (mLock) {
            mUsbDeviceConnectionHandler = usbDeviceConnectionHandler;
        }
    }

    private @Nullable ComponentName getUsbDeviceConnectionHandler() {
        synchronized (mLock) {
            return mUsbDeviceConnectionHandler;
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

    /* returns true if the USB device should not be accessible by applications */
    private boolean isBlackListed(int clazz, int subClass) {
        // blacklist hubs
        if (clazz == UsbConstants.USB_CLASS_HUB) return true;

        // blacklist HID boot devices (mouse and keyboard)
        return clazz == UsbConstants.USB_CLASS_HID
                && subClass == UsbConstants.USB_INTERFACE_SUBCLASS_BOOT;

    }

    /* Called from JNI in monitorUsbHostBus() to report new USB devices
       Returns true if successful, in which case the JNI code will continue adding configurations,
       interfaces and endpoints, and finally call endUsbDeviceAdded after all descriptors
       have been processed
     */
    @SuppressWarnings("unused")
    private boolean beginUsbDeviceAdded(String deviceName, int vendorID, int productID,
            int deviceClass, int deviceSubclass, int deviceProtocol,
            String manufacturerName, String productName, int version, String serialNumber) {

        if (DEBUG) {
            Slog.d(TAG, "usb:UsbHostManager.beginUsbDeviceAdded(" + deviceName + ")");
            // Audio Class Codes:
            // Audio: 0x01
            // Audio Subclass Codes:
            // undefined: 0x00
            // audio control: 0x01
            // audio streaming: 0x02
            // midi streaming: 0x03

            // some useful debugging info
            Slog.d(TAG, "usb: nm:" + deviceName + " vnd:" + vendorID + " prd:" + productID + " cls:"
                    + deviceClass + " sub:" + deviceSubclass + " proto:" + deviceProtocol);
        }

        // OK this is non-obvious, but true. One can't tell if the device being attached is even
        // potentially an audio device without parsing the interface descriptors, so punt on any
        // such test until endUsbDeviceAdded() when we have that info.

        if (isBlackListed(deviceName) ||
                isBlackListed(deviceClass, deviceSubclass)) {
            return false;
        }

        synchronized (mLock) {
            if (mDevices.get(deviceName) != null) {
                Slog.w(TAG, "device already on mDevices list: " + deviceName);
                return false;
            }

            if (mNewDevice != null) {
                Slog.e(TAG, "mNewDevice is not null in endUsbDeviceAdded");
                return false;
            }

            // Create version string in "%.%" format
            String versionString = Integer.toString(version >> 8) + "." + (version & 0xFF);

            mNewDevice = new UsbDevice(deviceName, vendorID, productID,
                    deviceClass, deviceSubclass, deviceProtocol,
                    manufacturerName, productName, versionString, serialNumber);

            mNewConfigurations = new ArrayList<>();
            mNewInterfaces = new ArrayList<>();
            mNewEndpoints = new ArrayList<>();
        }

        return true;
    }

    /* Called from JNI in monitorUsbHostBus() to report new USB configuration for the device
       currently being added.  Returns true if successful, false in case of error.
     */
    @SuppressWarnings("unused")
    private void addUsbConfiguration(int id, String name, int attributes, int maxPower) {
        if (mNewConfiguration != null) {
            mNewConfiguration.setInterfaces(
                    mNewInterfaces.toArray(new UsbInterface[mNewInterfaces.size()]));
            mNewInterfaces.clear();
        }

        mNewConfiguration = new UsbConfiguration(id, name, attributes, maxPower);
        mNewConfigurations.add(mNewConfiguration);
    }

    /* Called from JNI in monitorUsbHostBus() to report new USB interface for the device
       currently being added.  Returns true if successful, false in case of error.
     */
    @SuppressWarnings("unused")
    private void addUsbInterface(int id, String name, int altSetting,
            int Class, int subClass, int protocol) {
        if (mNewInterface != null) {
            mNewInterface.setEndpoints(
                    mNewEndpoints.toArray(new UsbEndpoint[mNewEndpoints.size()]));
            mNewEndpoints.clear();
        }

        mNewInterface = new UsbInterface(id, altSetting, name, Class, subClass, protocol);
        mNewInterfaces.add(mNewInterface);
    }

    /* Called from JNI in monitorUsbHostBus() to report new USB endpoint for the device
       currently being added.  Returns true if successful, false in case of error.
     */
    @SuppressWarnings("unused")
    private void addUsbEndpoint(int address, int attributes, int maxPacketSize, int interval) {
        mNewEndpoints.add(new UsbEndpoint(address, attributes, maxPacketSize, interval));
    }

    /* Called from JNI in monitorUsbHostBus() to finish adding a new device */
    @SuppressWarnings("unused")
    private void endUsbDeviceAdded() {
        if (DEBUG) {
            Slog.d(TAG, "usb:UsbHostManager.endUsbDeviceAdded()");
        }
        if (mNewInterface != null) {
            mNewInterface.setEndpoints(
                    mNewEndpoints.toArray(new UsbEndpoint[mNewEndpoints.size()]));
        }
        if (mNewConfiguration != null) {
            mNewConfiguration.setInterfaces(
                    mNewInterfaces.toArray(new UsbInterface[mNewInterfaces.size()]));
        }


        synchronized (mLock) {
            if (mNewDevice != null) {
                mNewDevice.setConfigurations(
                        mNewConfigurations.toArray(
                                new UsbConfiguration[mNewConfigurations.size()]));
                mDevices.put(mNewDevice.getDeviceName(), mNewDevice);
                Slog.d(TAG, "Added device " + mNewDevice);

                // It is fine to call this only for the current user as all broadcasts are sent to
                // all profiles of the user and the dialogs should only show once.
                ComponentName usbDeviceConnectionHandler = getUsbDeviceConnectionHandler();
                if (usbDeviceConnectionHandler == null) {
                    getCurrentUserSettings().deviceAttached(mNewDevice);
                } else {
                    getCurrentUserSettings().deviceAttachedForFixedHandler(mNewDevice,
                            usbDeviceConnectionHandler);
                }
                // deviceName is something like: "/dev/bus/usb/001/001"
                UsbDescriptorParser parser = new UsbDescriptorParser();
                boolean isInputHeadset = false;
                boolean isOutputHeadset = false;
                if (parser.parseDevice(mNewDevice.getDeviceName())) {
                    isInputHeadset = parser.isInputHeadset();
                    isOutputHeadset = parser.isOutputHeadset();
                    Slog.i(TAG, "---- isHeadset[in: " + isInputHeadset
                            + " , out: " + isOutputHeadset + "]");
                }
                mUsbAlsaManager.usbDeviceAdded(mNewDevice,
                        isInputHeadset, isOutputHeadset);
            } else {
                Slog.e(TAG, "mNewDevice is null in endUsbDeviceAdded");
            }
            mNewDevice = null;
            mNewConfigurations = null;
            mNewInterfaces = null;
            mNewEndpoints = null;
            mNewConfiguration = null;
            mNewInterface = null;
        }
    }

    /* Called from JNI in monitorUsbHostBus to report USB device removal */
    @SuppressWarnings("unused")
    private void usbDeviceRemoved(String deviceName) {
        synchronized (mLock) {
            UsbDevice device = mDevices.remove(deviceName);
            if (device != null) {
                mUsbAlsaManager.usbDeviceRemoved(device);
                mSettingsManager.usbDeviceRemoved(device);
                getCurrentUserSettings().usbDeviceRemoved(device);
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
    public ParcelFileDescriptor openDevice(String deviceName, UsbUserSettingsManager settings) {
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
            settings.checkPermission(device);
            return nativeOpenDevice(deviceName);
        }
    }

    public void dump(IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.println("USB Host State:");
            for (String name : mDevices.keySet()) {
                pw.println("  " + name + ": " + mDevices.get(name));
            }
            if (mUsbDeviceConnectionHandler != null) {
                pw.println("Default USB Host Connection handler: " + mUsbDeviceConnectionHandler);
            }

            Collection<UsbDevice> devices = mDevices.values();
            if (devices.size() != 0) {
                pw.println("USB Peripheral Descriptors");
                for (UsbDevice device : devices) {
                    StringBuilder stringBuilder = new StringBuilder();

                    UsbDescriptorParser parser = new UsbDescriptorParser();
                    if (parser.parseDevice(device.getDeviceName())) {
                        UsbDescriptorsTree descriptorTree = new UsbDescriptorsTree();
                        descriptorTree.parse(parser);

                        UsbManager usbManager =
                                (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
                        UsbDeviceConnection connection = usbManager.openDevice(device);

                        descriptorTree.report(new TextReportCanvas(connection, stringBuilder));
                        connection.close();

                        stringBuilder.append("isHeadset[in: " + parser.isInputHeadset()
                                + " , out: " + parser.isOutputHeadset() + "]");
                    } else {
                        stringBuilder.append("Error Parsing USB Descriptors");
                    }
                    pw.println(stringBuilder.toString());
                }
            }
        }
    }

    private native void monitorUsbHostBus();
    private native ParcelFileDescriptor nativeOpenDevice(String deviceName);
}
