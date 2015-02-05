/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.media;

import android.content.Context;
import android.util.Slog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import android.content.Context;

/** @hide
 * API candidate
 */
public class AudioDevicesManager {
    private static String TAG = "AudioDevicesManager";
    private static boolean DEBUG = true;

    private AudioManager mAudioManager = null;
    private OnAmPortUpdateListener mPortListener = null;

    /*
     * Enum/Selection API
     */
    public AudioDevicesManager(Context context) {
        mAudioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        mPortListener = new OnAmPortUpdateListener();
        mAudioManager.registerAudioPortUpdateListener(mPortListener);
    }

    /** @hide
     * API candidate
     */
    //TODO Merge this class into android.media.AudioDevice
    public class AudioDeviceInfo {
        private AudioDevicePort mPort = null;

        /** @hide */
        /* package */ AudioDeviceInfo(AudioDevicePort port) {
            mPort = port;
        }

        public int getId() { return mPort.handle().id(); }

        public String getName() { return mPort.name(); }

        public int getType() {
            return mPort.type();
        }

        public String getAddress() {
            return mPort.address();
        }

        public int getRole() { return mPort.role(); }

        public int[] getSampleRates() { return mPort.samplingRates(); }

        public int[] getChannelMasks() { return mPort.channelMasks(); }

        public int[] getChannelCounts() {
            int[] masks = getChannelMasks();
            int[] counts = new int[masks.length];
            for (int mask_index = 0; mask_index < masks.length; mask_index++) {
                counts[mask_index] = getRole() == AudioPort.ROLE_SINK
                        ? AudioFormat.channelCountFromOutChannelMask(masks[mask_index])
                        : AudioFormat.channelCountFromInChannelMask(masks[mask_index]);
            }
            return counts;
        }

        /* The format IDs are in AudioFormat.java */
        public int[] getFormats() { return mPort.formats(); }

        public String toString() { return "" + getId() + " - " + getName(); }
    }

    /** @hide */
    public static final int LIST_DEVICES_OUTPUTS   = 0x0001;
    /** @hide */
    public static final int LIST_DEVICES_INPUTS    = 0x0002;
    /** @hide */
    public static final int LIST_DEVICES_BUILTIN   = 0x0004;
    /** @hide */
    public static final int LIST_DEVICES_USB       = 0x0008;
    // TODO implement the semantics for these.
    /** @hide */
    public static final int LIST_DEVICES_WIRED     = 0x0010;
    /** @hide */
    public static final int LIST_DEVICES_UNWIRED   = 0x0020;

    /** @hide */
    public static final int LIST_DEVICES_ALL = LIST_DEVICES_OUTPUTS | LIST_DEVICES_INPUTS;

    private boolean checkFlags(AudioDevicePort port, int flags) {
        // Inputs / Outputs
        boolean passed =
                port.role() == AudioPort.ROLE_SINK && (flags & LIST_DEVICES_OUTPUTS) != 0 ||
                port.role() == AudioPort.ROLE_SOURCE && (flags & LIST_DEVICES_INPUTS) != 0;

        // USB
        if (passed && (flags & LIST_DEVICES_USB) != 0) {
            int role = port.role();
            int type = port.type();
            Slog.i(TAG, "  role:" + role + " type:0x" + Integer.toHexString(type));
            passed =
                (role == AudioPort.ROLE_SINK && (type & AudioSystem.DEVICE_OUT_ALL_USB) != 0) ||
                (role == AudioPort.ROLE_SOURCE && (type & AudioSystem.DEVICE_IN_ALL_USB) != 0);
        }

        return passed;
    }

    /** @hide */
    public ArrayList<AudioDeviceInfo> listDevices(int flags) {
        Slog.i(TAG, "AudioManager.listDevices(" + Integer.toHexString(flags) + ")");

        //FIXME - Use ArrayList<AudioDevicePort> when mAudioManager.listAudioDevicePorts() is fixed.
        ArrayList<AudioPort> ports = new ArrayList<AudioPort>();
        int status = mAudioManager.listAudioDevicePorts(ports);

        Slog.i(TAG, "  status:" + status + " numPorts:" + ports.size());

        ArrayList<AudioDeviceInfo> deviceList = new ArrayList<AudioDeviceInfo>();

        if (status == AudioManager.SUCCESS) {
            deviceList = new ArrayList<AudioDeviceInfo>();
             for (AudioPort port : ports) {
                if (/*port instanceof AudioDevicePort &&*/ checkFlags((AudioDevicePort)port, flags)) {
                    deviceList.add(new AudioDeviceInfo((AudioDevicePort)port));
                }
            }
        }
        return deviceList;
    }

    private ArrayList<OnAudioDeviceConnectionListener> mDeviceConnectionListeners =
            new ArrayList<OnAudioDeviceConnectionListener>();

    private HashMap<Integer, AudioPort> mCurrentPortlist =
            new HashMap<Integer, AudioPort>();

    private ArrayList<AudioDeviceInfo> calcAddedDevices(AudioPort[] portList) {
        ArrayList<AudioDeviceInfo> addedDevices = new  ArrayList<AudioDeviceInfo>();
        synchronized(mCurrentPortlist) {
            for(int portIndex = 0; portIndex < portList.length; portIndex++) {
                if (portList[portIndex] instanceof AudioDevicePort) {
                    if (!mCurrentPortlist.containsKey(portList[portIndex].handle().id())) {
                        addedDevices.add(new AudioDeviceInfo((AudioDevicePort)portList[portIndex]));
                    }
                }
            }
        }
        return addedDevices;
    }

    private boolean hasPortId(AudioPort[] portList, int id) {
        for(int portIndex = 0; portIndex < portList.length; portIndex++) {
            if (portList[portIndex].handle().id() == id) {
                return true;
            }
        }
        return false;
    }

    private ArrayList<AudioDeviceInfo> calcRemovedDevices(AudioPort[] portList) {
        ArrayList<AudioDeviceInfo> removedDevices = new  ArrayList<AudioDeviceInfo>();

        synchronized (mCurrentPortlist) {
            Iterator it = mCurrentPortlist.entrySet().iterator();
            while (it.hasNext()) {
                HashMap.Entry pairs = (HashMap.Entry)it.next();
                if (pairs.getValue() instanceof AudioDevicePort) {
                    if (!hasPortId(portList, ((Integer)pairs.getKey()).intValue())) {
                        removedDevices.add(new AudioDeviceInfo((AudioDevicePort)pairs.getValue()));
                    }
                }
            }
        }
        return removedDevices;
    }

    private void buildCurrentDevicesList(AudioPort[] portList) {
        synchronized (mCurrentPortlist) {
            mCurrentPortlist.clear();
            for (int portIndex = 0; portIndex < portList.length; portIndex++) {
                if (portList[portIndex] instanceof AudioDevicePort) {
                    mCurrentPortlist.put(portList[portIndex].handle().id(),
                                         (AudioDevicePort)portList[portIndex]);
                }
            }
        }
    }

    /** @hide */
    public void addDeviceConnectionListener(OnAudioDeviceConnectionListener listener) {
        synchronized (mDeviceConnectionListeners) {
            mDeviceConnectionListeners.add(listener);
        }
    }

    /** @hide */
    public void removeDeviceConnectionListener(OnAudioDeviceConnectionListener listener) {
        synchronized (mDeviceConnectionListeners) {
            mDeviceConnectionListeners.remove(listener);
        }
    }

    /**
     * @hide
     */
    private class OnAmPortUpdateListener implements AudioManager.OnAudioPortUpdateListener {
        static final String TAG = "OnAmPortUpdateListener";
        public void onAudioPortListUpdate(AudioPort[] portList) {
            Slog.i(TAG, "onAudioPortListUpdate() " + portList.length + " ports.");
            ArrayList<AudioDeviceInfo> addedDevices = calcAddedDevices(portList);
            ArrayList<AudioDeviceInfo> removedDevices = calcRemovedDevices(portList);

            ArrayList<OnAudioDeviceConnectionListener> listeners = null;
            synchronized (mDeviceConnectionListeners) {
                listeners =
                        new ArrayList<OnAudioDeviceConnectionListener>(mDeviceConnectionListeners);
            }

            // Connect
            if (addedDevices.size() != 0) {
                for (OnAudioDeviceConnectionListener listener : listeners) {
                    listener.onConnect(addedDevices);
                }
            }

            // Disconnect?
            if (removedDevices.size() != 0) {
                for (OnAudioDeviceConnectionListener listener : listeners) {
                    listener.onDisconnect(removedDevices);
                }
            }

            buildCurrentDevicesList(portList);
        }

        /**
         * Callback method called upon audio patch list update.
         * @param patchList the updated list of audio patches
         */
        public void onAudioPatchListUpdate(AudioPatch[] patchList) {
            Slog.i(TAG, "onAudioPatchListUpdate() " + patchList.length + " patches.");
        }

        /**
         * Callback method called when the mediaserver dies
         */
        public void onServiceDied() {
            Slog.i(TAG, "onServiceDied()");
        }
    }
}
