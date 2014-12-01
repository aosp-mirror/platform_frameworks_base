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

import android.alsa.AlsaCardsParser;
import android.alsa.AlsaDevicesParser;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.media.AudioManager;
import android.os.FileObserver;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;

/**
 * UsbAudioManager manages USB audio devices.
 */
public class UsbAudioManager {
    private static final String TAG = UsbAudioManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String ALSA_DIRECTORY = "/dev/snd/";

    private final Context mContext;

    private final class AudioDevice {
        public int mCard;
        public int mDevice;
        public boolean mHasPlayback;
        public boolean mHasCapture;
        public boolean mHasMIDI;

        public AudioDevice(int card, int device,
                boolean hasPlayback, boolean hasCapture, boolean hasMidi) {
            mCard = card;
            mDevice = device;
            mHasPlayback = hasPlayback;
            mHasCapture = hasCapture;
            mHasMIDI = hasMidi;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("AudioDevice: [card: " + mCard);
            sb.append(", device: " + mDevice);
            sb.append(", hasPlayback: " + mHasPlayback);
            sb.append(", hasCapture: " + mHasCapture);
            sb.append(", hasMidi: " + mHasMIDI);
            sb.append("]");
            return sb.toString();
        }
    }

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

    private final HashMap<UsbDevice,AudioDevice> mAudioDevices
            = new HashMap<UsbDevice,AudioDevice>();

    private final HashMap<String,AlsaDevice> mAlsaDevices
            = new HashMap<String,AlsaDevice>();

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

    /* package */ UsbAudioManager(Context context) {
        mContext = context;
    }

    public void systemReady() {
        mAlsaObserver.startWatching();

        // add existing alsa devices
        File[] files = new File(ALSA_DIRECTORY).listFiles();
        for (int i = 0; i < files.length; i++) {
            alsaFileAdded(files[i].getName());
        }
    }

    // Broadcasts the arrival/departure of a USB audio interface
    // audioDevice - the AudioDevice that was added or removed
    // enabled - if true, we're connecting a device (it's arrived), else disconnecting
    private void sendDeviceNotification(AudioDevice audioDevice, boolean enabled) {
        // send a sticky broadcast containing current USB state
        Intent intent = new Intent(AudioManager.ACTION_USB_AUDIO_DEVICE_PLUG);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        intent.putExtra("state", enabled ? 1 : 0);
        intent.putExtra("card", audioDevice.mCard);
        intent.putExtra("device", audioDevice.mDevice);
        intent.putExtra("hasPlayback", audioDevice.mHasPlayback);
        intent.putExtra("hasCapture", audioDevice.mHasCapture);
        intent.putExtra("hasMIDI", audioDevice.mHasMIDI);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
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

    /* package */ void deviceAdded(UsbDevice usbDevice) {
       if (DEBUG) {
          Slog.d(TAG, "deviceAdded(): " + usbDevice);
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

        //TODO(pmclean) The "Parser" objects inspect files in "/proc/asound" which we presume is
        // present, unlike the waitForAlsaDevice() which waits on a file in /dev/snd. It is not
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
        int card = cardsParser.getNumCardRecords() - 1;
        int device = 0;

        boolean hasPlayback = devicesParser.hasPlaybackDevices(card);
        boolean hasCapture = devicesParser.hasCaptureDevices(card);
        boolean hasMidi = devicesParser.hasMIDIDevices(card);

        // Playback device file needed/present?
        if (hasPlayback &&
            waitForAlsaDevice(card, device, AlsaDevice.TYPE_PLAYBACK) == null) {
            return;
        }

        // Capture device file needed/present?
        if (hasCapture &&
            waitForAlsaDevice(card, device, AlsaDevice.TYPE_CAPTURE) == null) {
            return;
        }

        if (DEBUG) {
            Slog.d(TAG,
                    "usb: hasPlayback:" + hasPlayback +
                    " hasCapture:" + hasCapture +
                    " hasMidi:" + hasMidi);
        }

        AudioDevice audioDevice = new AudioDevice(card, device, hasPlayback, hasCapture, hasMidi);
        mAudioDevices.put(usbDevice, audioDevice);
        sendDeviceNotification(audioDevice, true);
    }

    /* package */ void deviceRemoved(UsbDevice device) {
       if (DEBUG) {
          Slog.d(TAG, "deviceRemoved(): " + device);
        }

        AudioDevice audioDevice = mAudioDevices.remove(device);
        if (audioDevice != null) {
            if (audioDevice.mHasPlayback || audioDevice.mHasPlayback) {
                sendDeviceNotification(audioDevice, false);
            }
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw) {
        pw.println("  USB AudioDevices:");
        for (UsbDevice device : mAudioDevices.keySet()) {
            pw.println("    " + device.getDeviceName() + ": " + mAudioDevices.get(device));
        }
    }
}
