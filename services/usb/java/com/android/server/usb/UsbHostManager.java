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

import android.alsa.AlsaCardsParser;
import android.alsa.AlsaDevicesParser;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * UsbHostManager manages USB state in host mode.
 */
public class UsbHostManager {
    private static final String TAG = UsbHostManager.class.getSimpleName();
    private static final boolean DEBUG_AUDIO = false;

    // contains all connected USB devices
    private final HashMap<String, UsbDevice> mDevices = new HashMap<String, UsbDevice>();

    // USB busses to exclude from USB host support
    private final String[] mHostBlacklist;

    private final Context mContext;
    private final Object mLock = new Object();

    private UsbDevice mNewDevice;
    private UsbConfiguration mNewConfiguration;
    private UsbInterface mNewInterface;
    private ArrayList<UsbConfiguration> mNewConfigurations;
    private ArrayList<UsbInterface> mNewInterfaces;
    private ArrayList<UsbEndpoint> mNewEndpoints;

    // Attributes of any connected USB audio device.
    //TODO(pmclean) When we extend to multiple, USB Audio devices, we will need to get
    // more clever about this.
    private int mConnectedUsbCard = -1;
    private int mConnectedUsbDeviceNum = -1;
    private boolean mConnectedHasPlayback = false;
    private boolean mConnectedHasCapture = false;
    private boolean mConnectedHasMIDI = false;

    @GuardedBy("mLock")
    private UsbSettingsManager mCurrentSettings;

    public UsbHostManager(Context context) {
        mContext = context;
        mHostBlacklist = context.getResources().getStringArray(
                com.android.internal.R.array.config_usbHostBlacklist);
    }

    public void setCurrentSettings(UsbSettingsManager settings) {
        synchronized (mLock) {
            mCurrentSettings = settings;
        }
    }

    private UsbSettingsManager getCurrentSettings() {
        synchronized (mLock) {
            return mCurrentSettings;
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

    // Broadcasts the arrival/departure of a USB audio interface
    // card - the ALSA card number of the physical interface
    // device - the ALSA device number of the physical interface
    // enabled - if true, we're connecting a device (it's arrived), else disconnecting
    private void sendDeviceNotification(int card, int device, boolean enabled,
            boolean hasPlayback, boolean hasCapture, boolean hasMIDI) {
        // send a sticky broadcast containing current USB state
        Intent intent = new Intent(Intent.ACTION_USB_AUDIO_DEVICE_PLUG);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        intent.putExtra("state", enabled ? 1 : 0);
        intent.putExtra("card", card);
        intent.putExtra("device", device);
        intent.putExtra("hasPlayback", hasPlayback);
        intent.putExtra("hasCapture", hasCapture);
        intent.putExtra("hasMIDI", hasMIDI);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private boolean waitForAlsaFile(int card, int device, boolean capture) {
        // These values were empirically determined.
        final int kNumRetries = 5;
        final int kSleepTime = 500; // ms
        String alsaDevPath = "/dev/snd/pcmC" + card + "D" + device + (capture ? "c" : "p");
        File alsaDevFile = new File(alsaDevPath);
        boolean exists = false;
        for (int retry = 0; !exists && retry < kNumRetries; retry++) {
            exists = alsaDevFile.exists();
            if (!exists) {
                try {
                    Thread.sleep(kSleepTime);
                } catch (IllegalThreadStateException ex) {
                    Slog.d(TAG, "usb: IllegalThreadStateException while waiting for ALSA file.");
                } catch (java.lang.InterruptedException ex) {
                    Slog.d(TAG, "usb: InterruptedException while waiting for ALSA file.");
                }
            }
        }

        return exists;
    }

    /* Called from JNI in monitorUsbHostBus() to report new USB devices
       Returns true if successful, in which case the JNI code will continue adding configurations,
       interfaces and endpoints, and finally call endUsbDeviceAdded after all descriptors
       have been processed
     */
    private boolean beginUsbDeviceAdded(String deviceName, int vendorID, int productID,
            int deviceClass, int deviceSubclass, int deviceProtocol,
            String manufacturerName, String productName, String serialNumber) {

        if (DEBUG_AUDIO) {
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
                isBlackListed(deviceClass, deviceSubclass, deviceProtocol)) {
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

            mNewDevice = new UsbDevice(deviceName, vendorID, productID,
                    deviceClass, deviceSubclass, deviceProtocol,
                    manufacturerName, productName, serialNumber);

            mNewConfigurations = new ArrayList<UsbConfiguration>();
            mNewInterfaces = new ArrayList<UsbInterface>();
            mNewEndpoints = new ArrayList<UsbEndpoint>();
        }

        return true;
    }

    /* Called from JNI in monitorUsbHostBus() to report new USB configuration for the device
       currently being added.  Returns true if successful, false in case of error.
     */
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
    private void addUsbEndpoint(int address, int attributes, int maxPacketSize, int interval) {
        mNewEndpoints.add(new UsbEndpoint(address, attributes, maxPacketSize, interval));
    }

    /* Called from JNI in monitorUsbHostBus() to finish adding a new device */
    private void endUsbDeviceAdded() {
        if (DEBUG_AUDIO) {
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

        // Is there an audio interface in there?
        final int kUsbClassId_Audio = 0x01;
        boolean isAudioDevice = false;
        for (int ntrfaceIndex = 0; !isAudioDevice && ntrfaceIndex < mNewInterfaces.size();
                ntrfaceIndex++) {
            UsbInterface ntrface = mNewInterfaces.get(ntrfaceIndex);
            if (ntrface.getInterfaceClass() == kUsbClassId_Audio) {
                isAudioDevice = true;
            }
        }

        synchronized (mLock) {
            if (mNewDevice != null) {
                mNewDevice.setConfigurations(
                        mNewConfigurations.toArray(new UsbConfiguration[mNewConfigurations.size()]));
                mDevices.put(mNewDevice.getDeviceName(), mNewDevice);
                Slog.d(TAG, "Added device " + mNewDevice);
                getCurrentSettings().deviceAttached(mNewDevice);
            } else {
                Slog.e(TAG, "mNewDevice is null in endUsbDeviceAdded");
            }
            mNewDevice = null;
            mNewConfigurations = null;
            mNewInterfaces = null;
            mNewEndpoints = null;
        }

        if (!isAudioDevice) {
            return; // bail
        }

        //TODO(pmclean) The "Parser" objects inspect files in "/proc/asound" which we presume is
        // present, unlike the waitForAlsaFile() which waits on a file in /dev/snd. It is not
        // clear why this works, or that it can be relied on going forward.  Needs further
        // research.
        AlsaCardsParser cardsParser = new AlsaCardsParser();
        cardsParser.scan();
        // cardsParser.Log();

        // But we need to parse the device to determine its capabilities.
        AlsaDevicesParser devicesParser = new AlsaDevicesParser();
        devicesParser.scan();
        // devicesParser.Log();

        // The protocol for now will be to select the last-connected (highest-numbered)
        // Alsa Card.
        mConnectedUsbCard = cardsParser.getNumCardRecords() - 1;
        mConnectedUsbDeviceNum = 0;

        mConnectedHasPlayback = devicesParser.hasPlaybackDevices(mConnectedUsbCard);
        mConnectedHasCapture = devicesParser.hasCaptureDevices(mConnectedUsbCard);
        mConnectedHasMIDI = devicesParser.hasMIDIDevices(mConnectedUsbCard);

        // Playback device file needed/present?
        if (mConnectedHasPlayback &&
            !waitForAlsaFile(mConnectedUsbCard, mConnectedUsbDeviceNum, false)) {
            return;
        }

        // Capture device file needed/present?
        if (mConnectedHasCapture &&
            !waitForAlsaFile(mConnectedUsbCard, mConnectedUsbDeviceNum, true)) {
            return;
        }

        if (DEBUG_AUDIO) {
            Slog.d(TAG,
                    "usb: hasPlayback:" + mConnectedHasPlayback + " hasCapture:" + mConnectedHasCapture);
        }

        sendDeviceNotification(mConnectedUsbCard,
                mConnectedUsbDeviceNum,
                true,
                mConnectedHasPlayback,
                mConnectedHasCapture,
                mConnectedHasMIDI);
    }

    /* Called from JNI in monitorUsbHostBus to report USB device removal */
    private void usbDeviceRemoved(String deviceName) {
        if (DEBUG_AUDIO) {
          Slog.d(TAG, "usb:UsbHostManager.usbDeviceRemoved() nm:" + deviceName);
        }

        if (mConnectedUsbCard != -1 && mConnectedUsbDeviceNum != -1) {
            sendDeviceNotification(mConnectedUsbCard,
                    mConnectedUsbDeviceNum,
                    false,
                    mConnectedHasPlayback,
                    mConnectedHasCapture,
                    mConnectedHasMIDI);
            mConnectedUsbCard = -1;
            mConnectedUsbDeviceNum = -1;
            mConnectedHasPlayback = false;
            mConnectedHasCapture = false;
            mConnectedHasMIDI = false;
        }

        synchronized (mLock) {
            UsbDevice device = mDevices.remove(deviceName);
            if (device != null) {
                getCurrentSettings().deviceDetached(device);
            }
        }
    }

    public void systemReady() {
        synchronized (mLock) {
            // Create a thread to call into native code to wait for USB host events.
            // This thread will call us back on usbDeviceAdded and usbDeviceRemoved.
            Runnable runnable = new Runnable() {
                public void run() {
                    monitorUsbHostBus();
                }
            };
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
            getCurrentSettings().checkPermission(device);
            return nativeOpenDevice(deviceName);
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw) {
        synchronized (mLock) {
            pw.println("  USB Host State:");
            for (String name : mDevices.keySet()) {
                pw.println("    " + name + ": " + mDevices.get(name));
            }
        }
    }

    private native void monitorUsbHostBus();
    private native ParcelFileDescriptor nativeOpenDevice(String deviceName);
}
