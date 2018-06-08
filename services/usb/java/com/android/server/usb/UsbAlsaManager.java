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
    private UsbAlsaDevice mSelectedDevice;

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
            Slog.d(TAG, "selectAlsaDevice " + alsaDevice);
        }

        if (mSelectedDevice != null) {
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
    }

    private synchronized void deselectAlsaDevice() {
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
            Slog.d(TAG, "UsbAudioManager.selectDefaultDevice()");
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
        boolean hasInput = parser.hasInput();
        boolean hasOutput = parser.hasOutput();
        if (DEBUG) {
            Slog.d(TAG, "hasInput: " + hasInput + " hasOutput:" + hasOutput);
        }
        if (hasInput || hasOutput) {
            boolean isInputHeadset = parser.isInputHeadset();
            boolean isOutputHeadset = parser.isOutputHeadset();

            if (mAudioService == null) {
                Slog.e(TAG, "no AudioService");
                return;
            }

            UsbAlsaDevice alsaDevice =
                    new UsbAlsaDevice(mAudioService, cardRec.getCardNum(), 0 /*device*/,
                                      deviceAddress, hasOutput, hasInput,
                                      isInputHeadset, isOutputHeadset);
            if (alsaDevice != null) {
                alsaDevice.setDeviceNameAndDescription(
                          cardRec.getCardName(), cardRec.getCardDescription());
                mAlsaDevices.add(0, alsaDevice);
                selectAlsaDevice(alsaDevice);
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
