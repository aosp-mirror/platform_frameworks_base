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
import android.media.IAudioService;
import android.media.midi.MidiDeviceInfo;
import android.os.Bundle;
import android.os.ServiceManager;
import android.provider.Settings;
import android.service.usb.UsbAlsaManagerProto;
import android.util.Slog;

import com.android.internal.alsa.AlsaCardsParser;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.usb.descriptors.UsbDescriptorParser;

import libcore.io.IoUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * UsbAlsaManager manages USB audio and MIDI devices.
 */
public final class UsbAlsaManager {
    private static final String TAG = UsbAlsaManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    // Flag to turn on/off multi-peripheral select mode
    // Set to true to have single-device-only mode
    private static final boolean mIsSingleMode = true;

    private static final String ALSA_DIRECTORY = "/dev/snd/";

    private final Context mContext;
    private IAudioService mAudioService;
    private final boolean mHasMidiFeature;

    private final AlsaCardsParser mCardsParser = new AlsaCardsParser();

    // this is needed to map USB devices to ALSA Audio Devices, especially to remove an
    // ALSA device when we are notified that its associated USB device has been removed.
    private final ArrayList<UsbAlsaDevice> mAlsaDevices = new ArrayList<UsbAlsaDevice>();
    private UsbAlsaDevice mSelectedDevice;

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
    private final HashMap<String, UsbMidiDevice>
            mMidiDevices = new HashMap<String, UsbMidiDevice>();

    // UsbMidiDevice for USB peripheral mode (gadget) device
    private UsbMidiDevice mPeripheralMidiDevice = null;

    /* package */ UsbAlsaManager(Context context) {
        mContext = context;
        mHasMidiFeature = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MIDI);
    }

    public void systemReady() {
        mAudioService = IAudioService.Stub.asInterface(
                        ServiceManager.getService(Context.AUDIO_SERVICE));
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

        // This must be where an existing USB audio device is deselected.... (I think)
        if (mIsSingleMode && mSelectedDevice != null) {
            deselectAlsaDevice();
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

        mSelectedDevice = alsaDevice;
        alsaDevice.start();
        if (DEBUG) {
            Slog.d(TAG, "selectAlsaDevice() - done.");
        }
    }

    private synchronized void deselectAlsaDevice() {
        if (DEBUG) {
            Slog.d(TAG, "deselectAlsaDevice() mSelectedDevice " + mSelectedDevice);
        }
        if (mSelectedDevice != null) {
            mSelectedDevice.stop();
            mSelectedDevice = null;
        }
    }

    private int getAlsaDeviceListIndexFor(String deviceAddress) {
        for (int index = 0; index < mAlsaDevices.size(); index++) {
            if (mAlsaDevices.get(index).getDeviceAddress().equals(deviceAddress)) {
                return index;
            }
        }
        return -1;
    }

    private UsbAlsaDevice removeAlsaDeviceFromList(String deviceAddress) {
        int index = getAlsaDeviceListIndexFor(deviceAddress);
        if (index > -1) {
            return mAlsaDevices.remove(index);
        } else {
            return null;
        }
    }

    /* package */ UsbAlsaDevice selectDefaultDevice() {
        if (DEBUG) {
            Slog.d(TAG, "selectDefaultDevice()");
        }

        if (mAlsaDevices.size() > 0) {
            UsbAlsaDevice alsaDevice = mAlsaDevices.get(0);
            if (DEBUG) {
                Slog.d(TAG, "  alsaDevice:" + alsaDevice);
            }
            if (alsaDevice != null) {
                selectAlsaDevice(alsaDevice);
            }
            return alsaDevice;
        } else {
            return null;
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
            return;
        }

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
            if (alsaDevice != null) {
                alsaDevice.setDeviceNameAndDescription(
                          cardRec.getCardName(), cardRec.getCardDescription());
                mAlsaDevices.add(0, alsaDevice);
                selectAlsaDevice(alsaDevice);
            }
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

            UsbMidiDevice usbMidiDevice = UsbMidiDevice.create(mContext, properties,
                    cardRec.getCardNum(), 0 /*device*/, numLegacyMidiInputs,
                    numLegacyMidiOutputs);
            if (usbMidiDevice != null) {
                mMidiDevices.put(deviceAddress, usbMidiDevice);
            }
        }
    }

    /* package */ synchronized void usbDeviceRemoved(String deviceAddress/*UsbDevice usbDevice*/) {
        if (DEBUG) {
            Slog.d(TAG, "deviceRemoved(" + deviceAddress + ")");
        }

        // Audio
        UsbAlsaDevice alsaDevice = removeAlsaDeviceFromList(deviceAddress);
        Slog.i(TAG, "USB Audio Device Removed: " + alsaDevice);
        if (alsaDevice != null && alsaDevice == mSelectedDevice) {
            deselectAlsaDevice();
            selectDefaultDevice(); // if there any external devices left, select one of them
        }

        // MIDI
        UsbMidiDevice usbMidiDevice = mMidiDevices.remove(deviceAddress);
        if (usbMidiDevice != null) {
            Slog.i(TAG, "USB MIDI Device Removed: " + deviceAddress);
            IoUtils.closeQuietly(usbMidiDevice);
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
            mPeripheralMidiDevice = UsbMidiDevice.create(mContext, properties, card, device,
                    1 /* numInputs */, 1 /* numOutputs */);
        } else if (!enabled && mPeripheralMidiDevice != null) {
            IoUtils.closeQuietly(mPeripheralMidiDevice);
            mPeripheralMidiDevice = null;
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
            // A UsbMidiDevice does not have a handle to the UsbDevice anymore
            mMidiDevices.get(deviceAddr).dump(deviceAddr, dump, "midi_devices",
                    UsbAlsaManagerProto.MIDI_DEVICES);
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
