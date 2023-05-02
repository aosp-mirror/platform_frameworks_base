/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.usb.UsbDevice;
import android.media.AudioManager;
import android.media.IAudioService;
import android.media.midi.MidiDeviceInfo;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.service.usb.UsbAlsaManagerProto;
import android.util.Slog;

import com.android.internal.alsa.AlsaCardsParser;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.usb.descriptors.UsbDescriptorParser;

import libcore.io.IoUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Stack;

/**
 * UsbAlsaManager manages USB audio and MIDI devices.
 */
public final class UsbAlsaManager {
    private static final String TAG = UsbAlsaManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    // Flag to turn on/off multi-peripheral select mode
    // Set to true to have multi-devices mode
    private static final boolean IS_MULTI_MODE = SystemProperties.getBoolean(
            "ro.audio.multi_usb_mode", false /*def*/);

    private static final String ALSA_DIRECTORY = "/dev/snd/";

    private static final int ALSA_DEVICE_TYPE_UNKNOWN = 0;
    private static final int ALSA_DEVICE_TYPE_PLAYBACK = 1;
    private static final int ALSA_DEVICE_TYPE_CAPTURE = 2;
    private static final int ALSA_DEVICE_TYPE_MIDI = 3;

    private final Context mContext;
    private IAudioService mAudioService;
    private final boolean mHasMidiFeature;

    private final AlsaCardsParser mCardsParser = new AlsaCardsParser();

    // this is needed to map USB devices to ALSA Audio Devices, especially to remove an
    // ALSA device when we are notified that its associated USB device has been removed.
    private final ArrayList<UsbAlsaDevice> mAlsaDevices = new ArrayList<UsbAlsaDevice>();
    // A map from device type to attached devices. Given the audio framework only supports
    // single device connection per device type, only the last attached device will be
    // connected to audio framework. Once the last device is removed, previous device can
    // be connected to audio framework.
    private HashMap<Integer, Stack<UsbAlsaDevice>> mAttachedDevices = new HashMap<>();

    //
    // Device Denylist
    //
    // This exists due to problems with Sony game controllers which present as an audio device
    // even if no headset is connected and have no way to set the volume on the unit.
    // Handle this by simply declining to use them as an audio device.
    private static final int USB_VENDORID_SONY = 0x054C;
    private static final int USB_PRODUCTID_PS4CONTROLLER_ZCT1 = 0x05C4;
    private static final int USB_PRODUCTID_PS4CONTROLLER_ZCT2 = 0x09CC;

    private static final int USB_DENYLIST_OUTPUT = 0x0001;
    private static final int USB_DENYLIST_INPUT  = 0x0002;

    private static class DenyListEntry {
        final int mVendorId;
        final int mProductId;
        final int mFlags;

        DenyListEntry(int vendorId, int productId, int flags) {
            mVendorId = vendorId;
            mProductId = productId;
            mFlags = flags;
        }
    }

    static final List<DenyListEntry> sDeviceDenylist = Arrays.asList(
            new DenyListEntry(USB_VENDORID_SONY,
                    USB_PRODUCTID_PS4CONTROLLER_ZCT1,
                    USB_DENYLIST_OUTPUT),
            new DenyListEntry(USB_VENDORID_SONY,
                    USB_PRODUCTID_PS4CONTROLLER_ZCT2,
                    USB_DENYLIST_OUTPUT));

    private static boolean isDeviceDenylisted(int vendorId, int productId, int flags) {
        for (DenyListEntry entry : sDeviceDenylist) {
            if (entry.mVendorId == vendorId && entry.mProductId == productId) {
                // see if the type flag is set
                return (entry.mFlags & flags) != 0;
            }
        }

        return false;
    }

    /**
     * List of connected MIDI devices
     */
    private final HashMap<String, UsbAlsaMidiDevice>
            mMidiDevices = new HashMap<String, UsbAlsaMidiDevice>();

    // UsbAlsaMidiDevice for USB peripheral mode (gadget) device
    private UsbAlsaMidiDevice mPeripheralMidiDevice = null;

    private final HashSet<Integer> mAlsaCards = new HashSet<>();
    private final FileObserver mAlsaObserver = new FileObserver(new File(ALSA_DIRECTORY),
            FileObserver.CREATE | FileObserver.DELETE) {
        public void onEvent(int event, String path) {
            switch (event) {
                case FileObserver.CREATE:
                    alsaFileAdded(path);
                    break;
                case FileObserver.DELETE:
                    alsaFileRemoved(path);
                    break;
            }
        }
    };

    /* package */ UsbAlsaManager(Context context) {
        mContext = context;
        mHasMidiFeature = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MIDI);
    }

    public void systemReady() {
        mAudioService = IAudioService.Stub.asInterface(
                        ServiceManager.getService(Context.AUDIO_SERVICE));
        mAlsaObserver.startWatching();
    }

    /**
     * Select the AlsaDevice to be used for AudioService.
     * AlsaDevice.start() notifies AudioService of it's connected state.
     *
     * @param alsaDevice The selected UsbAlsaDevice for system USB audio.
     */
    private synchronized void selectAlsaDevice(UsbAlsaDevice alsaDevice) {
        if (DEBUG) {
            Slog.d(TAG, "selectAlsaDevice() " + alsaDevice);
        }

        // FIXME Does not yet handle the case where the setting is changed
        // after device connection.  Ideally we should handle the settings change
        // in SettingsObserver. Here we should log that a USB device is connected
        // and disconnected with its address (card , device) and force the
        // connection or disconnection when the setting changes.
        int isDisabled = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.USB_AUDIO_AUTOMATIC_ROUTING_DISABLED, 0);
        if (isDisabled != 0) {
            return;
        }

        alsaDevice.start();

        if (DEBUG) {
            Slog.d(TAG, "selectAlsaDevice() - done.");
        }
    }

    private synchronized void deselectAlsaDevice(UsbAlsaDevice selectedDevice) {
        if (DEBUG) {
            Slog.d(TAG, "deselectAlsaDevice() selectedDevice " + selectedDevice);
        }
        selectedDevice.stop();
    }

    private int getAlsaDeviceListIndexFor(String deviceAddress) {
        for (int index = 0; index < mAlsaDevices.size(); index++) {
            if (mAlsaDevices.get(index).getDeviceAddress().equals(deviceAddress)) {
                return index;
            }
        }
        return -1;
    }

    private void addDeviceToAttachedDevicesMap(int deviceType, UsbAlsaDevice device) {
        if (deviceType == AudioManager.DEVICE_NONE) {
            Slog.i(TAG, "Ignore caching device as the type is NONE, device=" + device);
            return;
        }
        Stack<UsbAlsaDevice> devices = mAttachedDevices.get(deviceType);
        if (devices == null) {
            mAttachedDevices.put(deviceType, new Stack<>());
            devices = mAttachedDevices.get(deviceType);
        }
        devices.push(device);
    }

    private void addAlsaDevice(UsbAlsaDevice device) {
        mAlsaDevices.add(0, device);
        addDeviceToAttachedDevicesMap(device.getInputDeviceType(), device);
        addDeviceToAttachedDevicesMap(device.getOutputDeviceType(), device);
    }

    private void removeDeviceFromAttachedDevicesMap(int deviceType, UsbAlsaDevice device) {
        Stack<UsbAlsaDevice> devices = mAttachedDevices.get(deviceType);
        if (devices == null) {
            return;
        }
        devices.remove(device);
        if (devices.isEmpty()) {
            mAttachedDevices.remove(deviceType);
        }
    }

    private UsbAlsaDevice removeAlsaDevice(String deviceAddress) {
        int index = getAlsaDeviceListIndexFor(deviceAddress);
        if (index > -1) {
            UsbAlsaDevice device = mAlsaDevices.remove(index);
            removeDeviceFromAttachedDevicesMap(device.getOutputDeviceType(), device);
            removeDeviceFromAttachedDevicesMap(device.getInputDeviceType(), device);
            return device;
        } else {
            return null;
        }
    }

    private UsbAlsaDevice selectDefaultDevice(int deviceType) {
        if (DEBUG) {
            Slog.d(TAG, "selectDefaultDevice():" + deviceType);
        }

        Stack<UsbAlsaDevice> devices = mAttachedDevices.get(deviceType);
        if (devices == null || devices.isEmpty()) {
            return null;
        }
        UsbAlsaDevice alsaDevice = devices.peek();
        Slog.d(TAG, "select default device:" + alsaDevice);
        if (AudioManager.isInputDevice(deviceType)) {
            alsaDevice.startInput();
        } else {
            alsaDevice.startOutput();
        }
        return alsaDevice;
    }

    private void deselectCurrentDevice(int deviceType) {
        if (DEBUG) {
            Slog.d(TAG, "deselectCurrentDevice():" + deviceType);
        }
        if (deviceType == AudioManager.DEVICE_NONE) {
            return;
        }

        Stack<UsbAlsaDevice> devices = mAttachedDevices.get(deviceType);
        if (devices == null || devices.isEmpty()) {
            return;
        }
        UsbAlsaDevice alsaDevice = devices.peek();
        Slog.d(TAG, "deselect current device:" + alsaDevice);
        if (AudioManager.isInputDevice(deviceType)) {
            alsaDevice.stopInput();
        } else {
            alsaDevice.stopOutput();
        }
    }

    /* package */ void usbDeviceAdded(String deviceAddress, UsbDevice usbDevice,
            UsbDescriptorParser parser) {
        if (DEBUG) {
            Slog.d(TAG, "usbDeviceAdded(): " + usbDevice.getManufacturerName()
                    + " nm:" + usbDevice.getProductName());
        }

        // Scan the Alsa File Space
        mCardsParser.scan();

        // Find the ALSA spec for this device address
        AlsaCardsParser.AlsaCardRecord cardRec =
                mCardsParser.findCardNumFor(deviceAddress);
        if (cardRec == null) {
            if (parser.hasAudioInterface()) {
                Slog.e(TAG, "usbDeviceAdded(): cannot find sound card for " + deviceAddress);
            }
            return;
        }

        waitForAlsaDevice(cardRec.getCardNum(), true /*isAdded*/);

        // Add it to the devices list
        boolean hasInput = parser.hasInput()
                && !isDeviceDenylisted(usbDevice.getVendorId(), usbDevice.getProductId(),
                        USB_DENYLIST_INPUT);
        boolean hasOutput = parser.hasOutput()
                && !isDeviceDenylisted(usbDevice.getVendorId(), usbDevice.getProductId(),
                        USB_DENYLIST_OUTPUT);
        if (DEBUG) {
            Slog.d(TAG, "hasInput: " + hasInput + " hasOutput:" + hasOutput);
        }
        if (hasInput || hasOutput) {
            boolean isInputHeadset = parser.isInputHeadset();
            boolean isOutputHeadset = parser.isOutputHeadset();
            boolean isDock = parser.isDock();

            if (mAudioService == null) {
                Slog.e(TAG, "no AudioService");
                return;
            }

            UsbAlsaDevice alsaDevice =
                    new UsbAlsaDevice(mAudioService, cardRec.getCardNum(), 0 /*device*/,
                                      deviceAddress, hasOutput, hasInput,
                                      isInputHeadset, isOutputHeadset, isDock);
            alsaDevice.setDeviceNameAndDescription(
                      cardRec.getCardName(), cardRec.getCardDescription());
            if (IS_MULTI_MODE) {
                deselectCurrentDevice(alsaDevice.getInputDeviceType());
                deselectCurrentDevice(alsaDevice.getOutputDeviceType());
            } else {
                // At single mode, the first device is the selected device.
                if (!mAlsaDevices.isEmpty()) {
                    deselectAlsaDevice(mAlsaDevices.get(0));
                }
            }
            addAlsaDevice(alsaDevice);
            selectAlsaDevice(alsaDevice);
        }

        addMidiDevice(deviceAddress, usbDevice, parser, cardRec);

        logDevices("deviceAdded()");

        if (DEBUG) {
            Slog.d(TAG, "deviceAdded() - done");
        }
    }

    private void addMidiDevice(String deviceAddress, UsbDevice usbDevice,
            UsbDescriptorParser parser, AlsaCardsParser.AlsaCardRecord cardRec) {
        boolean hasMidi = parser.hasMIDIInterface();
        // UsbHostManager will create UsbDirectMidiDevices instead if MIDI 2 is supported.
        boolean hasMidi2 = parser.containsUniversalMidiDeviceEndpoint();
        if (DEBUG) {
            Slog.d(TAG, "hasMidi: " + hasMidi + " mHasMidiFeature:" + mHasMidiFeature);
            Slog.d(TAG, "hasMidi2: " + hasMidi2);
        }
        if (mHasMidiFeature && hasMidi && !hasMidi2) {
            Bundle properties = new Bundle();
            String manufacturer = usbDevice.getManufacturerName();
            String product = usbDevice.getProductName();
            String version = usbDevice.getVersion();
            String name;
            if (manufacturer == null || manufacturer.isEmpty()) {
                name = product;
            } else if (product == null || product.isEmpty()) {
                name = manufacturer;
            } else {
                name = manufacturer + " " + product;
            }
            properties.putString(MidiDeviceInfo.PROPERTY_NAME, name);
            properties.putString(MidiDeviceInfo.PROPERTY_MANUFACTURER, manufacturer);
            properties.putString(MidiDeviceInfo.PROPERTY_PRODUCT, product);
            properties.putString(MidiDeviceInfo.PROPERTY_VERSION, version);
            properties.putString(MidiDeviceInfo.PROPERTY_SERIAL_NUMBER,
                    usbDevice.getSerialNumber());
            properties.putInt(MidiDeviceInfo.PROPERTY_ALSA_CARD, cardRec.getCardNum());
            properties.putInt(MidiDeviceInfo.PROPERTY_ALSA_DEVICE, 0 /*deviceNum*/);
            properties.putParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE, usbDevice);

            int numLegacyMidiInputs = parser.calculateNumLegacyMidiInputs();
            int numLegacyMidiOutputs = parser.calculateNumLegacyMidiOutputs();
            if (DEBUG) {
                Slog.d(TAG, "numLegacyMidiInputs: " + numLegacyMidiInputs);
                Slog.d(TAG, "numLegacyMidiOutputs:" + numLegacyMidiOutputs);
            }

            UsbAlsaMidiDevice midiDevice = UsbAlsaMidiDevice.create(mContext, properties,
                    cardRec.getCardNum(), 0 /*device*/, numLegacyMidiInputs,
                    numLegacyMidiOutputs);
            if (midiDevice != null) {
                mMidiDevices.put(deviceAddress, midiDevice);
            }
        }
    }

    /* package */ synchronized void usbDeviceRemoved(String deviceAddress/*UsbDevice usbDevice*/) {
        if (DEBUG) {
            Slog.d(TAG, "deviceRemoved(" + deviceAddress + ")");
        }

        // Audio
        UsbAlsaDevice alsaDevice = removeAlsaDevice(deviceAddress);
        Slog.i(TAG, "USB Audio Device Removed: " + alsaDevice);
        if (alsaDevice != null) {
            waitForAlsaDevice(alsaDevice.getCardNum(), false /*isAdded*/);
            deselectAlsaDevice(alsaDevice);
            if (IS_MULTI_MODE) {
                selectDefaultDevice(alsaDevice.getOutputDeviceType());
                selectDefaultDevice(alsaDevice.getInputDeviceType());
            } else {
                // If there are any external devices left, select the latest attached one
                if (!mAlsaDevices.isEmpty() && mAlsaDevices.get(0) != null) {
                    selectAlsaDevice(mAlsaDevices.get(0));
                }
            }
        }

        // MIDI
        UsbAlsaMidiDevice midiDevice = mMidiDevices.remove(deviceAddress);
        if (midiDevice != null) {
            Slog.i(TAG, "USB MIDI Device Removed: " + deviceAddress);
            IoUtils.closeQuietly(midiDevice);
        }

        logDevices("usbDeviceRemoved()");
    }

   /* package */ void setPeripheralMidiState(boolean enabled, int card, int device) {
        if (!mHasMidiFeature) {
            return;
        }

        if (enabled && mPeripheralMidiDevice == null) {
            Bundle properties = new Bundle();
            Resources r = mContext.getResources();
            properties.putString(MidiDeviceInfo.PROPERTY_NAME, r.getString(
                    com.android.internal.R.string.usb_midi_peripheral_name));
            properties.putString(MidiDeviceInfo.PROPERTY_MANUFACTURER, r.getString(
                    com.android.internal.R.string.usb_midi_peripheral_manufacturer_name));
            properties.putString(MidiDeviceInfo.PROPERTY_PRODUCT, r.getString(
                    com.android.internal.R.string.usb_midi_peripheral_product_name));
            properties.putInt(MidiDeviceInfo.PROPERTY_ALSA_CARD, card);
            properties.putInt(MidiDeviceInfo.PROPERTY_ALSA_DEVICE, device);
            mPeripheralMidiDevice = UsbAlsaMidiDevice.create(mContext, properties, card, device,
                    1 /* numInputs */, 1 /* numOutputs */);
        } else if (!enabled && mPeripheralMidiDevice != null) {
            IoUtils.closeQuietly(mPeripheralMidiDevice);
            mPeripheralMidiDevice = null;
        }
   }

    private boolean waitForAlsaDevice(int card, boolean isAdded) {
        if (DEBUG) {
            Slog.e(TAG, "waitForAlsaDevice(c:" + card + ")");
        }

        // This value was empirically determined.
        final int kWaitTimeMs = 2500;

        synchronized (mAlsaCards) {
            long timeoutMs = SystemClock.elapsedRealtime() + kWaitTimeMs;
            while ((isAdded ^ mAlsaCards.contains(card))
                    && timeoutMs > SystemClock.elapsedRealtime()) {
                long waitTimeMs = timeoutMs - SystemClock.elapsedRealtime();
                if (waitTimeMs > 0) {
                    try {
                        mAlsaCards.wait(waitTimeMs);
                    } catch (InterruptedException e) {
                        Slog.d(TAG, "usb: InterruptedException while waiting for ALSA file.");
                    }
                }
            }
            final boolean cardFound = mAlsaCards.contains(card);
            if ((isAdded ^ cardFound) && timeoutMs > SystemClock.elapsedRealtime()) {
                Slog.e(TAG, "waitForAlsaDevice(" + card + ") timeout");
            } else {
                Slog.i(TAG, "waitForAlsaDevice for device card=" + card + ", isAdded=" + isAdded
                        + ", found=" + cardFound);
            }
            return cardFound;
        }
    }

    private int getCardNumberFromAlsaFilePath(String path) {
        int type = ALSA_DEVICE_TYPE_UNKNOWN;
        if (path.startsWith("pcmC")) {
            if (path.endsWith("p")) {
                type = ALSA_DEVICE_TYPE_PLAYBACK;
            } else if (path.endsWith("c")) {
                type = ALSA_DEVICE_TYPE_CAPTURE;
            }
        } else if (path.startsWith("midiC")) {
            type = ALSA_DEVICE_TYPE_MIDI;
        }

        if (type == ALSA_DEVICE_TYPE_UNKNOWN) {
            Slog.i(TAG, "Unknown type file(" + path + ") added.");
            return -1;
        }
        try {
            int c_index = path.indexOf('C');
            int d_index = path.indexOf('D');
            return Integer.parseInt(path.substring(c_index + 1, d_index));
        } catch (Exception e) {
            Slog.e(TAG, "Could not parse ALSA file name " + path, e);
            return -1;
        }
    }

    private void alsaFileAdded(String path) {
        Slog.i(TAG, "alsaFileAdded(" + path + ")");
        final int card = getCardNumberFromAlsaFilePath(path);
        if (card == -1) {
            return;
        }
        synchronized (mAlsaCards) {
            if (!mAlsaCards.contains(card)) {
                Slog.d(TAG, "Adding ALSA device card=" + card);
                mAlsaCards.add(card);
                mAlsaCards.notifyAll();
            }
        }
    }

    private void alsaFileRemoved(String path) {
        final int card = getCardNumberFromAlsaFilePath(path);
        if (card == -1) {
            return;
        }
        synchronized (mAlsaCards) {
            mAlsaCards.remove(card);
        }
    }

    //
    // Devices List
    //
/*
    //import java.util.ArrayList;
    public ArrayList<UsbAudioDevice> getConnectedDevices() {
        ArrayList<UsbAudioDevice> devices = new ArrayList<UsbAudioDevice>(mAudioDevices.size());
        for (HashMap.Entry<UsbDevice,UsbAudioDevice> entry : mAudioDevices.entrySet()) {
            devices.add(entry.getValue());
        }
        return devices;
    }
*/

    /**
     * Dump the USB alsa state.
     */
    // invoked with "adb shell dumpsys usb"
    public void dump(DualDumpOutputStream dump, String idName, long id) {
        long token = dump.start(idName, id);

        dump.write("cards_parser", UsbAlsaManagerProto.CARDS_PARSER, mCardsParser.getScanStatus());

        for (UsbAlsaDevice usbAlsaDevice : mAlsaDevices) {
            usbAlsaDevice.dump(dump, "alsa_devices", UsbAlsaManagerProto.ALSA_DEVICES);
        }

        for (String deviceAddr : mMidiDevices.keySet()) {
            // A UsbAlsaMidiDevice does not have a handle to the UsbDevice anymore
            mMidiDevices.get(deviceAddr).dump(deviceAddr, dump, "alsa_midi_devices",
                    UsbAlsaManagerProto.ALSA_MIDI_DEVICES);
        }

        dump.end(token);
    }

    public void logDevicesList(String title) {
        if (DEBUG) {
            Slog.i(TAG, title + "----------------");
            for (UsbAlsaDevice alsaDevice : mAlsaDevices) {
                Slog.i(TAG, "  -->");
                Slog.i(TAG, "" + alsaDevice);
                Slog.i(TAG, "  <--");
            }
            Slog.i(TAG, "----------------");
        }
    }

    // This logs a more terse (and more readable) version of the devices list
    public void logDevices(String title) {
        if (DEBUG) {
            Slog.i(TAG, title + "----------------");
            for (UsbAlsaDevice alsaDevice : mAlsaDevices) {
                Slog.i(TAG, alsaDevice.toShortString());
            }
            Slog.i(TAG, "----------------");
        }
    }
}
