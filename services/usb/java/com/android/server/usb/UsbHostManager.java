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

import static com.android.internal.usb.DumpUtils.writeDevice;
import static com.android.internal.util.dump.DumpUtils.writeComponentName;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.service.ServiceProtoEnums;
import android.service.usb.UsbConnectionRecordProto;
import android.service.usb.UsbHostManagerProto;
import android.service.usb.UsbIsHeadsetProto;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.usb.descriptors.UsbDescriptor;
import com.android.server.usb.descriptors.UsbDescriptorParser;
import com.android.server.usb.descriptors.UsbDeviceDescriptor;
import com.android.server.usb.descriptors.UsbInterfaceDescriptor;
import com.android.server.usb.descriptors.report.TextReportCanvas;
import com.android.server.usb.descriptors.tree.UsbDescriptorsTree;

import libcore.io.IoUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Random;

/**
 * UsbHostManager manages USB state in host mode.
 */
public class UsbHostManager {
    private static final String TAG = UsbHostManager.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final int LINUX_FOUNDATION_VID = 0x1d6b;

    private final Context mContext;

    // USB busses to exclude from USB host support
    private final String[] mHostDenyList;

    private final UsbAlsaManager mUsbAlsaManager;
    private final UsbPermissionManager mPermissionManager;

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
    private final ArrayMap<String, ConnectionRecord> mConnected = new ArrayMap<>();

    /**
     * List of connected MIDI devices. Key on deviceAddress.
     */
    private final HashMap<String, ArrayList<UsbDirectMidiDevice>>
            mMidiDevices = new HashMap<String, ArrayList<UsbDirectMidiDevice>>();
    private final HashSet<String> mMidiUniqueCodes = new HashSet<String>();
    private final Random mRandom = new Random();
    private final boolean mHasMidiFeature;

    /*
     * ConnectionRecord
     * Stores connection/disconnection data.
     */
    class ConnectionRecord {
        long mTimestamp;        // Same time-base as system log.
        String mDeviceAddress;

        static final int CONNECT = ServiceProtoEnums.USB_CONNECTION_RECORD_MODE_CONNECT; // 0
        static final int CONNECT_BADPARSE =
                ServiceProtoEnums.USB_CONNECTION_RECORD_MODE_CONNECT_BADPARSE; // 1
        static final int CONNECT_BADDEVICE =
                ServiceProtoEnums.USB_CONNECTION_RECORD_MODE_CONNECT_BADDEVICE; // 2
        static final int DISCONNECT =
                ServiceProtoEnums.USB_CONNECTION_RECORD_MODE_DISCONNECT; // -1

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

        void dump(@NonNull DualDumpOutputStream dump, String idName, long id) {
            long token = dump.start(idName, id);

            dump.write("device_address", UsbConnectionRecordProto.DEVICE_ADDRESS, mDeviceAddress);
            dump.write("mode", UsbConnectionRecordProto.MODE, mMode);
            dump.write("timestamp", UsbConnectionRecordProto.TIMESTAMP, mTimestamp);

            if (mMode != DISCONNECT) {
                UsbDescriptorParser parser = new UsbDescriptorParser(mDeviceAddress, mDescriptors);

                UsbDeviceDescriptor deviceDescriptor = parser.getDeviceDescriptor();

                dump.write("manufacturer", UsbConnectionRecordProto.MANUFACTURER,
                        deviceDescriptor.getVendorID());
                dump.write("product", UsbConnectionRecordProto.PRODUCT,
                        deviceDescriptor.getProductID());
                long isHeadSetToken = dump.start("is_headset", UsbConnectionRecordProto.IS_HEADSET);
                dump.write("in", UsbIsHeadsetProto.IN, parser.isInputHeadset());
                dump.write("out", UsbIsHeadsetProto.OUT, parser.isOutputHeadset());
                dump.end(isHeadSetToken);
            }

            dump.end(token);
        }

        void dumpShort(IndentingPrintWriter pw) {
            if (mMode != DISCONNECT) {
                pw.println(formatTime() + " Connect " + mDeviceAddress + " mode:" + mMode);
                UsbDescriptorParser parser = new UsbDescriptorParser(mDeviceAddress, mDescriptors);

                UsbDeviceDescriptor deviceDescriptor = parser.getDeviceDescriptor();

                pw.println("manfacturer:0x" + Integer.toHexString(deviceDescriptor.getVendorID())
                        + " product:" + Integer.toHexString(deviceDescriptor.getProductID()));
                pw.println("isHeadset[in: " + parser.isInputHeadset()
                        + " , out: " + parser.isOutputHeadset() + "], isDock: " + parser.isDock());
            } else {
                pw.println(formatTime() + " Disconnect " + mDeviceAddress);
            }
        }

        void dumpTree(IndentingPrintWriter pw) {
            if (mMode != DISCONNECT) {
                pw.println(formatTime() + " Connect " + mDeviceAddress + " mode:" + mMode);
                UsbDescriptorParser parser = new UsbDescriptorParser(mDeviceAddress, mDescriptors);
                StringBuilder stringBuilder = new StringBuilder();
                UsbDescriptorsTree descriptorTree = new UsbDescriptorsTree();
                descriptorTree.parse(parser);
                descriptorTree.report(new TextReportCanvas(parser, stringBuilder));
                stringBuilder.append("isHeadset[in: " + parser.isInputHeadset()
                        + " , out: " + parser.isOutputHeadset() + "], isDock: " + parser.isDock());
                pw.println(stringBuilder.toString());
            } else {
                pw.println(formatTime() + " Disconnect " + mDeviceAddress);
            }
        }

        void dumpList(IndentingPrintWriter pw) {
            if (mMode != DISCONNECT) {
                pw.println(formatTime() + " Connect " + mDeviceAddress + " mode:" + mMode);
                UsbDescriptorParser parser = new UsbDescriptorParser(mDeviceAddress, mDescriptors);
                StringBuilder stringBuilder = new StringBuilder();
                TextReportCanvas canvas = new TextReportCanvas(parser, stringBuilder);
                for (UsbDescriptor descriptor : parser.getDescriptors()) {
                    descriptor.report(canvas);
                }
                pw.println(stringBuilder.toString());
                pw.println("isHeadset[in: " + parser.isInputHeadset()
                        + " , out: " + parser.isOutputHeadset() + "], isDock: " + parser.isDock());
            } else {
                pw.println(formatTime() + " Disconnect " + mDeviceAddress);
            }
        }

        private static final int kDumpBytesPerLine = 16;

        void dumpRaw(IndentingPrintWriter pw) {
            if (mMode != DISCONNECT) {
                pw.println(formatTime() + " Connect " + mDeviceAddress + " mode:" + mMode);
                int length = mDescriptors.length;
                pw.println("Raw Descriptors " + length + " bytes");
                int dataOffset = 0;
                for (int line = 0; line < length / kDumpBytesPerLine; line++) {
                    StringBuilder sb = new StringBuilder();
                    for (int offset = 0; offset < kDumpBytesPerLine; offset++) {
                        sb.append("0x")
                            .append(String.format("0x%02X", mDescriptors[dataOffset++]))
                            .append(" ");
                    }
                    pw.println(sb.toString());
                }

                // remainder
                StringBuilder sb = new StringBuilder();
                while (dataOffset < length) {
                    sb.append("0x")
                        .append(String.format("0x%02X", mDescriptors[dataOffset++]))
                        .append(" ");
                }
                pw.println(sb.toString());
            } else {
                pw.println(formatTime() + " Disconnect " + mDeviceAddress);
            }
        }
    }

    /*
     * UsbHostManager
     */
    public UsbHostManager(Context context, UsbAlsaManager alsaManager,
            UsbPermissionManager permissionManager) {
        mContext = context;

        mHostDenyList = context.getResources().getStringArray(
                com.android.internal.R.array.config_usbHostDenylist);
        mUsbAlsaManager = alsaManager;
        mPermissionManager = permissionManager;
        String deviceConnectionHandler = context.getResources().getString(
                com.android.internal.R.string.config_UsbDeviceConnectionHandling_component);
        if (!TextUtils.isEmpty(deviceConnectionHandler)) {
            setUsbDeviceConnectionHandler(ComponentName.unflattenFromString(
                    deviceConnectionHandler));
        }
        mHasMidiFeature = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MIDI);
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

    private boolean isDenyListed(String deviceAddress) {
        int count = mHostDenyList.length;
        for (int i = 0; i < count; i++) {
            if (deviceAddress.startsWith(mHostDenyList[i])) {
                return true;
            }
        }
        return false;
    }

    /* returns true if the USB device should not be accessible by applications */
    private boolean isDenyListed(int clazz, int subClass) {
        // deny hubs
        if (clazz == UsbConstants.USB_CLASS_HUB) return true;

        // deny HID boot devices (mouse and keyboard)
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
        if (mode != ConnectionRecord.DISCONNECT) {
            mLastConnect = rec;
        }
        if (mode == ConnectionRecord.CONNECT) {
            mConnected.put(deviceAddress, rec);
        } else if (mode == ConnectionRecord.DISCONNECT) {
            mConnected.remove(deviceAddress);
        }
    }

    private void logUsbDevice(UsbDescriptorParser descriptorParser) {
        int vid = 0;
        int pid = 0;
        String mfg = "<unknown>";
        String product = "<unknown>";
        String version = "<unknown>";
        String serial = "<unknown>";

        UsbDeviceDescriptor deviceDescriptor = descriptorParser.getDeviceDescriptor();
        if (deviceDescriptor != null) {
            vid = deviceDescriptor.getVendorID();
            pid = deviceDescriptor.getProductID();
            mfg = deviceDescriptor.getMfgString(descriptorParser);
            product = deviceDescriptor.getProductString(descriptorParser);
            version = deviceDescriptor.getDeviceReleaseString();
            serial = deviceDescriptor.getSerialString(descriptorParser);
        }

        if (vid == LINUX_FOUNDATION_VID) {
            return;  // don't care about OS-constructed virtual USB devices.
        }
        boolean hasAudio = descriptorParser.hasAudioInterface();
        boolean hasHid = descriptorParser.hasHIDInterface();
        boolean hasStorage = descriptorParser.hasStorageInterface();

        String attachedString = "USB device attached: ";
        attachedString += String.format("vidpid %04x:%04x", vid, pid);
        attachedString += String.format(" mfg/product/ver/serial %s/%s/%s/%s",
                                        mfg, product, version, serial);
        attachedString += String.format(" hasAudio/HID/Storage: %b/%b/%b",
                                        hasAudio, hasHid, hasStorage);
        Slog.d(TAG, attachedString);
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

        if (isDenyListed(deviceAddress)) {
            if (DEBUG) {
                Slog.d(TAG, "device address is Deny listed");
            }
            return false;
        }

        if (isDenyListed(deviceClass, deviceSubclass)) {
            if (DEBUG) {
                Slog.d(TAG, "device class is deny listed");
            }
            return false;
        }

        UsbDescriptorParser parser = new UsbDescriptorParser(deviceAddress, descriptors);
        if (deviceClass == UsbConstants.USB_CLASS_PER_INTERFACE
                && !checkUsbInterfacesDenyListed(parser)) {
            return false;
        }

        // Potentially can block as it may read data from the USB device.
        logUsbDevice(parser);

        synchronized (mLock) {
            if (mDevices.get(deviceAddress) != null) {
                Slog.w(TAG, "device already on mDevices list: " + deviceAddress);
                //TODO If this is the same peripheral as is being connected, replace
                // it with the new connection.
                return false;
            }

            UsbDevice.Builder newDeviceBuilder = parser.toAndroidUsbDeviceBuilder();
            if (newDeviceBuilder == null) {
                Slog.e(TAG, "Couldn't create UsbDevice object.");
                // Tracking
                addConnectionRecord(deviceAddress, ConnectionRecord.CONNECT_BADDEVICE,
                        parser.getRawDescriptors());
            } else {
                UsbSerialReader serialNumberReader = new UsbSerialReader(mContext,
                        mPermissionManager, newDeviceBuilder.serialNumber);
                UsbDevice newDevice = newDeviceBuilder.build(serialNumberReader);
                serialNumberReader.setDevice(newDevice);

                mDevices.put(deviceAddress, newDevice);
                Slog.d(TAG, "Added device " + newDevice);

                // It is fine to call this only for the current user as all broadcasts are
                // sent to all profiles of the user and the dialogs should only show once.
                ComponentName usbDeviceConnectionHandler = getUsbDeviceConnectionHandler();
                if (usbDeviceConnectionHandler == null) {
                    getCurrentUserSettings().deviceAttached(newDevice);
                } else {
                    getCurrentUserSettings().deviceAttachedForFixedHandler(newDevice,
                            usbDeviceConnectionHandler);
                }

                mUsbAlsaManager.usbDeviceAdded(deviceAddress, newDevice, parser);

                if (mHasMidiFeature) {
                    // Use a 3 digit code to associate MIDI devices with one another.
                    // Each MIDI device already has mId for uniqueness. mId is generated
                    // sequentially. For clarity, this code is not generated sequentially.
                    String uniqueUsbDeviceIdentifier = generateNewUsbDeviceIdentifier();

                    ArrayList<UsbDirectMidiDevice> midiDevices =
                            new ArrayList<UsbDirectMidiDevice>();
                    if (parser.containsUniversalMidiDeviceEndpoint()) {
                        UsbDirectMidiDevice midiDevice = UsbDirectMidiDevice.create(mContext,
                                newDevice, parser, true, uniqueUsbDeviceIdentifier);
                        if (midiDevice != null) {
                            midiDevices.add(midiDevice);
                        } else {
                            Slog.e(TAG, "Universal Midi Device is null.");
                        }
                    }
                    if (parser.containsLegacyMidiDeviceEndpoint()) {
                        UsbDirectMidiDevice midiDevice = UsbDirectMidiDevice.create(mContext,
                                newDevice, parser, false, uniqueUsbDeviceIdentifier);
                        if (midiDevice != null) {
                            midiDevices.add(midiDevice);
                        } else {
                            Slog.e(TAG, "Legacy Midi Device is null.");
                        }
                    }

                    if (!midiDevices.isEmpty()) {
                        mMidiDevices.put(deviceAddress, midiDevices);
                    }
                }

                // Tracking
                addConnectionRecord(deviceAddress, ConnectionRecord.CONNECT,
                        parser.getRawDescriptors());

                // Stats collection
                FrameworkStatsLog.write(FrameworkStatsLog.USB_DEVICE_ATTACHED,
                        newDevice.getVendorId(), newDevice.getProductId(),
                        parser.hasAudioInterface(), parser.hasHIDInterface(),
                        parser.hasStorageInterface(),
                        FrameworkStatsLog.USB_DEVICE_ATTACHED__STATE__STATE_CONNECTED, 0);
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
        if (DEBUG) {
            Slog.d(TAG, "usbDeviceRemoved(" + deviceAddress + ") end");
        }

        synchronized (mLock) {
            UsbDevice device = mDevices.remove(deviceAddress);
            if (device != null) {
                Slog.d(TAG, "Removed device at " + deviceAddress + ": " + device.getProductName());
                mUsbAlsaManager.usbDeviceRemoved(deviceAddress);
                mPermissionManager.usbDeviceRemoved(device);

                // MIDI
                ArrayList<UsbDirectMidiDevice> midiDevices =
                        mMidiDevices.remove(deviceAddress);
                if (midiDevices != null) {
                    for (UsbDirectMidiDevice midiDevice : midiDevices) {
                        if (midiDevice != null) {
                            IoUtils.closeQuietly(midiDevice);
                        }
                    }
                    Slog.i(TAG, "USB MIDI Devices Removed: " + deviceAddress);
                }

                getCurrentUserSettings().usbDeviceRemoved(device);
                ConnectionRecord current = mConnected.get(deviceAddress);
                // Tracking
                addConnectionRecord(deviceAddress, ConnectionRecord.DISCONNECT, null);

                if (current != null) {
                    UsbDescriptorParser parser = new UsbDescriptorParser(deviceAddress,
                            current.mDescriptors);
                        // Stats collection
                    FrameworkStatsLog.write(FrameworkStatsLog.USB_DEVICE_ATTACHED,
                            device.getVendorId(), device.getProductId(), parser.hasAudioInterface(),
                            parser.hasHIDInterface(),  parser.hasStorageInterface(),
                            FrameworkStatsLog.USB_DEVICE_ATTACHED__STATE__STATE_DISCONNECTED,
                            System.currentTimeMillis() - current.mTimestamp);
                }
            } else {
                Slog.d(TAG, "Removed device at " + deviceAddress + " was already gone");
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

    /**
     *  Opens the specified USB device
     */
    public ParcelFileDescriptor openDevice(String deviceAddress,
            UsbUserPermissionManager permissions, String packageName, int pid, int uid) {
        synchronized (mLock) {
            if (isDenyListed(deviceAddress)) {
                throw new SecurityException("USB device is on a restricted bus");
            }
            UsbDevice device = mDevices.get(deviceAddress);
            if (device == null) {
                // if it is not in mDevices, it either does not exist or is denylisted
                throw new IllegalArgumentException(
                        "device " + deviceAddress + " does not exist or is restricted");
            }

            permissions.checkPermission(device, packageName, pid, uid);
            return nativeOpenDevice(deviceAddress);
        }
    }

    /**
     * Dump out various information about the state of USB device connections.
     */
    public void dump(DualDumpOutputStream dump, String idName, long id) {
        long token = dump.start(idName, id);

        synchronized (mHandlerLock) {
            if (mUsbDeviceConnectionHandler != null) {
                writeComponentName(dump, "default_usb_host_connection_handler",
                        UsbHostManagerProto.DEFAULT_USB_HOST_CONNECTION_HANDLER,
                        mUsbDeviceConnectionHandler);
            }
        }
        synchronized (mLock) {
            for (String name : mDevices.keySet()) {
                writeDevice(dump, "devices", UsbHostManagerProto.DEVICES, mDevices.get(name));
            }

            dump.write("num_connects", UsbHostManagerProto.NUM_CONNECTS, mNumConnects);

            for (ConnectionRecord rec : mConnections) {
                rec.dump(dump, "connections", UsbHostManagerProto.CONNECTIONS);
            }

            for (ArrayList<UsbDirectMidiDevice> directMidiDevices : mMidiDevices.values()) {
                for (UsbDirectMidiDevice directMidiDevice : directMidiDevices) {
                    directMidiDevice.dump(dump, "midi_devices", UsbHostManagerProto.MIDI_DEVICES);
                }
            }
        }

        dump.end(token);
    }

    /**
     * Dump various descriptor data.
     */
    public void dumpDescriptors(IndentingPrintWriter pw, String[] args) {
        if (mLastConnect != null) {
            pw.println("Last Connected USB Device:");
            if (args.length <= 1 || args[1].equals("-dump-short")) {
                mLastConnect.dumpShort(pw);
            } else if (args[1].equals("-dump-tree")) {
                mLastConnect.dumpTree(pw);
            } else if (args[1].equals("-dump-list")) {
                mLastConnect.dumpList(pw);
            }  else if (args[1].equals("-dump-raw")) {
                mLastConnect.dumpRaw(pw);
            }
        } else {
            pw.println("No USB Devices have been connected.");
        }
    }

    private boolean checkUsbInterfacesDenyListed(UsbDescriptorParser parser) {
        // Device class needs to be obtained through the device interface.  Ignore device only
        // if ALL interfaces are deny-listed.
        boolean shouldIgnoreDevice = false;
        for (UsbDescriptor descriptor: parser.getDescriptors()) {
            if (!(descriptor instanceof UsbInterfaceDescriptor)) {
                continue;
            }
            UsbInterfaceDescriptor iface = (UsbInterfaceDescriptor) descriptor;
            shouldIgnoreDevice = isDenyListed(iface.getUsbClass(), iface.getUsbSubclass());
            if (!shouldIgnoreDevice) {
                break;
            }
        }
        if (shouldIgnoreDevice) {
            if (DEBUG) {
                Slog.d(TAG, "usb interface class is deny listed");
            }
            return false;
        }
        return true;
    }

    // Generate a 3 digit code.
    private String generateNewUsbDeviceIdentifier() {
        String code;
        do {
            code = "";
            for (int i = 0; i < 3; i++) {
                code += mRandom.nextInt(10);
            }
        } while (mMidiUniqueCodes.contains(code));
        mMidiUniqueCodes.add(code);
        return code;
    }

    private native void monitorUsbHostBus();
    private native ParcelFileDescriptor nativeOpenDevice(String deviceAddress);
}
