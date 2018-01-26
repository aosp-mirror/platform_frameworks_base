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
import android.media.AudioSystem;
import android.media.IAudioService;
import android.media.midi.MidiDeviceInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.service.usb.UsbAlsaManagerProto;
import android.util.Slog;

import com.android.internal.alsa.AlsaCardsParser;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.audio.AudioService;
import com.android.server.usb.descriptors.UsbDescriptorParser;

import libcore.io.IoUtils;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * UsbAlsaManager manages USB audio and MIDI devices.
 */
public final class UsbAlsaManager {
    private static final String TAG = UsbAlsaManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String ALSA_DIRECTORY = "/dev/snd/";

    private final Context mContext;
    private IAudioService mAudioService;
    private final boolean mHasMidiFeature;

    private final AlsaCardsParser mCardsParser = new AlsaCardsParser();

    // this is needed to map USB devices to ALSA Audio Devices, especially to remove an
    // ALSA device when we are notified that its associated USB device has been removed.
    private final ArrayList<UsbAlsaDevice> mAlsaDevices = new ArrayList<UsbAlsaDevice>();

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

    // Notifies AudioService when a device is added or removed
    // audioDevice - the AudioDevice that was added or removed
    // enabled - if true, we're connecting a device (it's arrived), else disconnecting
    private void notifyDeviceState(UsbAlsaDevice alsaDevice, boolean enabled) {
        if (DEBUG) {
            Slog.d(TAG, "notifyDeviceState " + enabled + " " + alsaDevice);
        }

        if (mAudioService == null) {
            Slog.e(TAG, "no AudioService");
            return;
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

        int state = (enabled ? 1 : 0);
        int cardNum = alsaDevice.getCardNum();
        int deviceNum = alsaDevice.getDeviceNum();
        if (cardNum < 0 || deviceNum < 0) {
            Slog.e(TAG, "Invalid alsa card or device alsaCard: " + cardNum
                        + " alsaDevice: " + deviceNum);
            return;
        }

        String address = AudioService.makeAlsaAddressString(cardNum, deviceNum);
        try {
            // Playback Device
            if (alsaDevice.hasPlayback()) {
                int device = alsaDevice.isOutputHeadset()
                        ? AudioSystem.DEVICE_OUT_USB_HEADSET
                        : AudioSystem.DEVICE_OUT_USB_DEVICE;
                if (DEBUG) {
                    Slog.i(TAG, "pre-call device:0x" + Integer.toHexString(device) +
                            " addr:" + address + " name:" + alsaDevice.getDeviceName());
                }
                mAudioService.setWiredDeviceConnectionState(
                        device, state, address, alsaDevice.getDeviceName(), TAG);
            }

            // Capture Device
            if (alsaDevice.hasCapture()) {
                int device = alsaDevice.isInputHeadset()
                        ? AudioSystem.DEVICE_IN_USB_HEADSET
                        : AudioSystem.DEVICE_IN_USB_DEVICE;
                mAudioService.setWiredDeviceConnectionState(
                        device, state, address, alsaDevice.getDeviceName(), TAG);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException in setWiredDeviceConnectionState");
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
            Slog.d(TAG, "UsbAudioManager.selectDefaultDevice()");
        }

        if (mAlsaDevices.size() > 0) {
            UsbAlsaDevice alsaDevice = mAlsaDevices.get(0);
            if (DEBUG) {
                Slog.d(TAG, "  alsaDevice:" + alsaDevice);
            }
            if (alsaDevice != null) {
                notifyDeviceState(alsaDevice, true /*enabled*/);
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
        boolean hasInput = parser.hasInput();
        boolean hasOutput = parser.hasOutput();
        if (DEBUG) {
            Slog.d(TAG, "hasInput: " + hasInput + " hasOutput:" + hasOutput);
        }
        if (hasInput || hasOutput) {
            boolean isInputHeadset = parser.isInputHeadset();
            boolean isOutputHeadset = parser.isOutputHeadset();
            UsbAlsaDevice alsaDevice =
                    new UsbAlsaDevice(cardRec.getCardNum(), 0 /*device*/, deviceAddress,
                            hasOutput, hasInput, isInputHeadset, isOutputHeadset);
            alsaDevice.setDeviceNameAndDescription(
                    cardRec.getCardName(), cardRec.getCardDescription());
            mAlsaDevices.add(0, alsaDevice);

            // Select it
            if (alsaDevice != null) {
                notifyDeviceState(alsaDevice, true /*enabled*/);
            }
        }

        // look for MIDI devices
        boolean hasMidi = parser.hasMIDIInterface();
        if (DEBUG) {
            Slog.d(TAG, "hasMidi: " + hasMidi + " mHasMidiFeature:" + mHasMidiFeature);
        }
        if (hasMidi && mHasMidiFeature) {
            int device = 0;
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

            UsbMidiDevice usbMidiDevice = UsbMidiDevice.create(mContext, properties,
                    cardRec.getCardNum(), 0 /*device*/);
            if (usbMidiDevice != null) {
                mMidiDevices.put(deviceAddress, usbMidiDevice);
            }
        }

        if (DEBUG) {
            Slog.d(TAG, "deviceAdded() - done");
        }
    }

    /* package */ void usbDeviceRemoved(String deviceAddress/*UsbDevice usbDevice*/) {
        if (DEBUG) {
            Slog.d(TAG, "deviceRemoved(" + deviceAddress + ")");
        }

        // Audio
        UsbAlsaDevice alsaDevice = removeAlsaDeviceFromList(deviceAddress);
        Slog.i(TAG, "USB Audio Device Removed: " + alsaDevice);
        if (alsaDevice != null) {
            if (alsaDevice.hasPlayback() || alsaDevice.hasCapture()) {
                notifyDeviceState(alsaDevice, false /*enabled*/);

                // if there any external devices left, select one of them
                selectDefaultDevice();
            }
        }

        // MIDI
        UsbMidiDevice usbMidiDevice = mMidiDevices.remove(deviceAddress);
        if (usbMidiDevice != null) {
            Slog.i(TAG, "USB MIDI Device Removed: " + usbMidiDevice);
            IoUtils.closeQuietly(usbMidiDevice);
        }
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
            mPeripheralMidiDevice = UsbMidiDevice.create(mContext, properties, card, device);
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

/*
    public void logDevicesList(String title) {
      if (DEBUG) {
          for (HashMap.Entry<UsbDevice,UsbAlsaDevice> entry : mAudioDevices.entrySet()) {
              Slog.i(TAG, "UsbDevice-------------------");
              Slog.i(TAG, "" + (entry != null ? entry.getKey() : "[none]"));
              Slog.i(TAG, "UsbAlsaDevice--------------");
              Slog.i(TAG, "" + entry.getValue());
          }
      }
    }
*/

    // This logs a more terse (and more readable) version of the devices list
/*
    public void logDevices(String title) {
      if (DEBUG) {
          Slog.i(TAG, title);
          for (HashMap.Entry<UsbDevice,UsbAlsaDevice> entry : mAudioDevices.entrySet()) {
              Slog.i(TAG, entry.getValue().toShortString());
          }
      }
    }
*/

}
