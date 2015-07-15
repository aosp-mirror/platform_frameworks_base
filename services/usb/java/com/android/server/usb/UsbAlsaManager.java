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
 * See the License for the specific language governing permissions an
 * limitations under the License.
 */

package com.android.server.usb;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.media.AudioSystem;
import android.media.IAudioService;
import android.media.midi.MidiDeviceInfo;
import android.os.FileObserver;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.alsa.AlsaCardsParser;
import com.android.internal.alsa.AlsaDevicesParser;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.audio.AudioService;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.ArrayList;

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
    private final AlsaDevicesParser mDevicesParser = new AlsaDevicesParser();

    // this is needed to map USB devices to ALSA Audio Devices, especially to remove an
    // ALSA device when we are notified that its associated USB device has been removed.

    private final HashMap<UsbDevice,UsbAudioDevice>
        mAudioDevices = new HashMap<UsbDevice,UsbAudioDevice>();

    private final HashMap<UsbDevice,UsbMidiDevice>
        mMidiDevices = new HashMap<UsbDevice,UsbMidiDevice>();

    private final HashMap<String,AlsaDevice>
        mAlsaDevices = new HashMap<String,AlsaDevice>();

    private UsbAudioDevice mAccessoryAudioDevice = null;

    // UsbMidiDevice for USB peripheral mode (gadget) device
    private UsbMidiDevice mPeripheralMidiDevice = null;

    private final class AlsaDevice {
        public static final int TYPE_UNKNOWN = 0;
        public static final int TYPE_PLAYBACK = 1;
        public static final int TYPE_CAPTURE = 2;
        public static final int TYPE_MIDI = 3;

        public int mCard;
        public int mDevice;
        public int mType;

        public AlsaDevice(int type, int card, int device) {
            mType = type;
            mCard = card;
            mDevice = device;
        }

        public boolean equals(Object obj) {
            if (! (obj instanceof AlsaDevice)) {
                return false;
            }
            AlsaDevice other = (AlsaDevice)obj;
            return (mType == other.mType && mCard == other.mCard && mDevice == other.mDevice);
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("AlsaDevice: [card: " + mCard);
            sb.append(", device: " + mDevice);
            sb.append(", type: " + mType);
            sb.append("]");
            return sb.toString();
        }
    }

    private final FileObserver mAlsaObserver = new FileObserver(ALSA_DIRECTORY,
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

        // initial scan
        mCardsParser.scan();
    }

    public void systemReady() {
        mAudioService = IAudioService.Stub.asInterface(
                        ServiceManager.getService(Context.AUDIO_SERVICE));

        mAlsaObserver.startWatching();

        // add existing alsa devices
        File[] files = new File(ALSA_DIRECTORY).listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                alsaFileAdded(files[i].getName());
            }
        }
    }

    // Notifies AudioService when a device is added or removed
    // audioDevice - the AudioDevice that was added or removed
    // enabled - if true, we're connecting a device (it's arrived), else disconnecting
    private void notifyDeviceState(UsbAudioDevice audioDevice, boolean enabled) {
        if (DEBUG) {
            Slog.d(TAG, "notifyDeviceState " + enabled + " " + audioDevice);
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
        int alsaCard = audioDevice.mCard;
        int alsaDevice = audioDevice.mDevice;
        if (alsaCard < 0 || alsaDevice < 0) {
            Slog.e(TAG, "Invalid alsa card or device alsaCard: " + alsaCard +
                        " alsaDevice: " + alsaDevice);
            return;
        }

        String address = AudioService.makeAlsaAddressString(alsaCard, alsaDevice);
        try {
            // Playback Device
            if (audioDevice.mHasPlayback) {
                int device = (audioDevice == mAccessoryAudioDevice ?
                        AudioSystem.DEVICE_OUT_USB_ACCESSORY :
                        AudioSystem.DEVICE_OUT_USB_DEVICE);
                if (DEBUG) {
                    Slog.i(TAG, "pre-call device:0x" + Integer.toHexString(device) +
                            " addr:" + address + " name:" + audioDevice.mDeviceName);
                }
                mAudioService.setWiredDeviceConnectionState(
                        device, state, address, audioDevice.mDeviceName, TAG);
            }

            // Capture Device
            if (audioDevice.mHasCapture) {
               int device = (audioDevice == mAccessoryAudioDevice ?
                        AudioSystem.DEVICE_IN_USB_ACCESSORY :
                        AudioSystem.DEVICE_IN_USB_DEVICE);
                mAudioService.setWiredDeviceConnectionState(
                        device, state, address, audioDevice.mDeviceName, TAG);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException in setWiredDeviceConnectionState");
        }
    }

    private AlsaDevice waitForAlsaDevice(int card, int device, int type) {
        AlsaDevice testDevice = new AlsaDevice(type, card, device);

        // This value was empirically determined.
        final int kWaitTime = 2500; // ms

        synchronized(mAlsaDevices) {
            long timeout = SystemClock.elapsedRealtime() + kWaitTime;
            do {
                if (mAlsaDevices.values().contains(testDevice)) {
                    return testDevice;
                }
                long waitTime = timeout - SystemClock.elapsedRealtime();
                if (waitTime > 0) {
                    try {
                        mAlsaDevices.wait(waitTime);
                    } catch (InterruptedException e) {
                        Slog.d(TAG, "usb: InterruptedException while waiting for ALSA file.");
                    }
                }
            } while (timeout > SystemClock.elapsedRealtime());
        }

        Slog.e(TAG, "waitForAlsaDevice failed for " + testDevice);
        return null;
    }

    private void alsaFileAdded(String name) {
        int type = AlsaDevice.TYPE_UNKNOWN;
        int card = -1, device = -1;

        if (name.startsWith("pcmC")) {
            if (name.endsWith("p")) {
                type = AlsaDevice.TYPE_PLAYBACK;
            } else if (name.endsWith("c")) {
                type = AlsaDevice.TYPE_CAPTURE;
            }
        } else if (name.startsWith("midiC")) {
            type = AlsaDevice.TYPE_MIDI;
        }

        if (type != AlsaDevice.TYPE_UNKNOWN) {
            try {
                int c_index = name.indexOf('C');
                int d_index = name.indexOf('D');
                int end = name.length();
                if (type == AlsaDevice.TYPE_PLAYBACK || type == AlsaDevice.TYPE_CAPTURE) {
                    // skip trailing 'p' or 'c'
                    end--;
                }
                card = Integer.parseInt(name.substring(c_index + 1, d_index));
                device = Integer.parseInt(name.substring(d_index + 1, end));
            } catch (Exception e) {
                Slog.e(TAG, "Could not parse ALSA file name " + name, e);
                return;
            }
            synchronized(mAlsaDevices) {
                if (mAlsaDevices.get(name) == null) {
                    AlsaDevice alsaDevice = new AlsaDevice(type, card, device);
                    Slog.d(TAG, "Adding ALSA device " + alsaDevice);
                    mAlsaDevices.put(name, alsaDevice);
                    mAlsaDevices.notifyAll();
                }
            }
        }
    }

    private void alsaFileRemoved(String path) {
        synchronized(mAlsaDevices) {
            AlsaDevice device = mAlsaDevices.remove(path);
            if (device != null) {
                Slog.d(TAG, "ALSA device removed: " + device);
            }
        }
    }

    /*
     * Select the default device of the specified card.
     */
    /* package */ UsbAudioDevice selectAudioCard(int card) {
        if (DEBUG) {
            Slog.d(TAG, "selectAudioCard() card:" + card);
        }
        if (!mCardsParser.isCardUsb(card)) {
            // Don't. AudioPolicyManager has logic for falling back to internal devices.
            return null;
        }

        mDevicesParser.scan();
        int device = mDevicesParser.getDefaultDeviceNum(card);

        boolean hasPlayback = mDevicesParser.hasPlaybackDevices(card);
        boolean hasCapture = mDevicesParser.hasCaptureDevices(card);
        int deviceClass =
            (mCardsParser.isCardUsb(card)
                ? UsbAudioDevice.kAudioDeviceClass_External
                : UsbAudioDevice.kAudioDeviceClass_Internal) |
            UsbAudioDevice.kAudioDeviceMeta_Alsa;

        // Playback device file needed/present?
        if (hasPlayback && (waitForAlsaDevice(card, device, AlsaDevice.TYPE_PLAYBACK) == null)) {
            return null;
        }

        // Capture device file needed/present?
        if (hasCapture && (waitForAlsaDevice(card, device, AlsaDevice.TYPE_CAPTURE) == null)) {
            return null;
        }

        if (DEBUG) {
            Slog.d(TAG, "usb: hasPlayback:" + hasPlayback + " hasCapture:" + hasCapture);
        }

        UsbAudioDevice audioDevice =
                new UsbAudioDevice(card, device, hasPlayback, hasCapture, deviceClass);
        AlsaCardsParser.AlsaCardRecord cardRecord = mCardsParser.getCardRecordFor(card);
        audioDevice.mDeviceName = cardRecord.mCardName;
        audioDevice.mDeviceDescription = cardRecord.mCardDescription;

        notifyDeviceState(audioDevice, true);

        return audioDevice;
    }

    /* package */ UsbAudioDevice selectDefaultDevice() {
        if (DEBUG) {
            Slog.d(TAG, "UsbAudioManager.selectDefaultDevice()");
        }
        mCardsParser.scan();
        return selectAudioCard(mCardsParser.getDefaultCard());
    }

    /* package */ void usbDeviceAdded(UsbDevice usbDevice) {
       if (DEBUG) {
          Slog.d(TAG, "deviceAdded(): " + usbDevice.getManufacturerName() +
                  "nm:" + usbDevice.getProductName());
        }

        // Is there an audio interface in there?
        boolean isAudioDevice = false;

        // FIXME - handle multiple configurations?
        int interfaceCount = usbDevice.getInterfaceCount();
        for (int ntrfaceIndex = 0; !isAudioDevice && ntrfaceIndex < interfaceCount;
                ntrfaceIndex++) {
            UsbInterface ntrface = usbDevice.getInterface(ntrfaceIndex);
            if (ntrface.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO) {
                isAudioDevice = true;
            }
        }
        if (!isAudioDevice) {
            return;
        }

        ArrayList<AlsaCardsParser.AlsaCardRecord> prevScanRecs = mCardsParser.getScanRecords();
        mCardsParser.scan();

        int addedCard = -1;
        ArrayList<AlsaCardsParser.AlsaCardRecord>
            newScanRecs = mCardsParser.getNewCardRecords(prevScanRecs);
        if (newScanRecs.size() > 0) {
            // This is where we select the just connected device
            // NOTE - to switch to prefering the first-connected device, just always
            // take the else clause below.
            addedCard = newScanRecs.get(0).mCardNum;
        } else {
            addedCard = mCardsParser.getDefaultUsbCard();
        }

        // If the default isn't a USB device, let the existing "select internal mechanism"
        // handle the selection.
        if (mCardsParser.isCardUsb(addedCard)) {
            UsbAudioDevice audioDevice = selectAudioCard(addedCard);
            if (audioDevice != null) {
                mAudioDevices.put(usbDevice, audioDevice);
            }

            // look for MIDI devices

            // Don't need to call mDevicesParser.scan() because selectAudioCard() does this above.
            // Uncomment this next line if that behavior changes in the fugure.
            // mDevicesParser.scan()

            boolean hasMidi = mDevicesParser.hasMIDIDevices(addedCard);
            if (hasMidi && mHasMidiFeature) {
                int device = mDevicesParser.getDefaultDeviceNum(addedCard);
                AlsaDevice alsaDevice = waitForAlsaDevice(addedCard, device, AlsaDevice.TYPE_MIDI);
                if (alsaDevice != null) {
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
                    properties.putInt(MidiDeviceInfo.PROPERTY_ALSA_CARD, alsaDevice.mCard);
                    properties.putInt(MidiDeviceInfo.PROPERTY_ALSA_DEVICE, alsaDevice.mDevice);
                    properties.putParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE, usbDevice);

                    UsbMidiDevice usbMidiDevice = UsbMidiDevice.create(mContext, properties,
                            alsaDevice.mCard, alsaDevice.mDevice);
                    if (usbMidiDevice != null) {
                        mMidiDevices.put(usbDevice, usbMidiDevice);
                    }
                }
            }
        }
    }

    /* package */ void usbDeviceRemoved(UsbDevice usbDevice) {
        if (DEBUG) {
          Slog.d(TAG, "deviceRemoved(): " + usbDevice.getManufacturerName() +
                  " " + usbDevice.getProductName());
        }

        UsbAudioDevice audioDevice = mAudioDevices.remove(usbDevice);
        if (audioDevice != null) {
            if (audioDevice.mHasPlayback || audioDevice.mHasPlayback) {
                notifyDeviceState(audioDevice, false);

                // if there any external devices left, select one of them
                selectDefaultDevice();
            }
        }
        UsbMidiDevice usbMidiDevice = mMidiDevices.remove(usbDevice);
        if (usbMidiDevice != null) {
            IoUtils.closeQuietly(usbMidiDevice);
        }
    }

   /* package */ void setAccessoryAudioState(boolean enabled, int card, int device) {
       if (DEBUG) {
            Slog.d(TAG, "setAccessoryAudioState " + enabled + " " + card + " " + device);
        }
        if (enabled) {
            mAccessoryAudioDevice = new UsbAudioDevice(card, device, true, false,
                    UsbAudioDevice.kAudioDeviceClass_External);
            notifyDeviceState(mAccessoryAudioDevice, true);
        } else if (mAccessoryAudioDevice != null) {
            notifyDeviceState(mAccessoryAudioDevice, false);
            mAccessoryAudioDevice = null;
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
    public ArrayList<UsbAudioDevice> getConnectedDevices() {
        ArrayList<UsbAudioDevice> devices = new ArrayList<UsbAudioDevice>(mAudioDevices.size());
        for (HashMap.Entry<UsbDevice,UsbAudioDevice> entry : mAudioDevices.entrySet()) {
            devices.add(entry.getValue());
        }
        return devices;
    }

    //
    // Logging
    //
    public void dump(IndentingPrintWriter pw) {
        pw.println("USB Audio Devices:");
        for (UsbDevice device : mAudioDevices.keySet()) {
            pw.println("  " + device.getDeviceName() + ": " + mAudioDevices.get(device));
        }
        pw.println("USB MIDI Devices:");
        for (UsbDevice device : mMidiDevices.keySet()) {
            pw.println("  " + device.getDeviceName() + ": " + mMidiDevices.get(device));
        }
    }

    public void logDevicesList(String title) {
      if (DEBUG) {
          for (HashMap.Entry<UsbDevice,UsbAudioDevice> entry : mAudioDevices.entrySet()) {
              Slog.i(TAG, "UsbDevice-------------------");
              Slog.i(TAG, "" + (entry != null ? entry.getKey() : "[none]"));
              Slog.i(TAG, "UsbAudioDevice--------------");
              Slog.i(TAG, "" + entry.getValue());
          }
      }
  }

  // This logs a more terse (and more readable) version of the devices list
  public void logDevices(String title) {
      if (DEBUG) {
          Slog.i(TAG, title);
          for (HashMap.Entry<UsbDevice,UsbAudioDevice> entry : mAudioDevices.entrySet()) {
              Slog.i(TAG, entry.getValue().toShortString());
          }
      }
  }
}
