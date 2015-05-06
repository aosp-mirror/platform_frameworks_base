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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.ArrayMap;
import android.util.Pair;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.HashMap;
import java.util.Iterator;

/**
 * AudioDevicesManager implements the Android Media Audio device enumeration and notification
 * functionality.  This functionality is in two comlementary parts.
 * <ol>
 * <li>{@link AudioDevicesManager#listDevices(int)} gets the list of current audio devices
 * </li>
 * <li>{@link AudioDevicesManager#addOnAudioDeviceConnectionListener(OnAudioDeviceConnectionListener, android.os.Handler)}
 *  provides a mechanism for applications to be informed of audio device connect/disconnect events.
 * </li>
 * </ol>
 */
public class AudioDevicesManager {

    private static String TAG = "AudioDevicesManager";

    private static boolean DEBUG = false;

    private AudioManager mAudioManager = null;

    private OnAmPortUpdateListener mPortListener = null;

    /**
     * The message sent to apps when the contents of the device list changes if they provide
     * a {#link Handler} object to addOnAudioDeviceConnectionListener().
     */
    private final static int MSG_DEVICES_LIST_CHANGE = 0;

    private ArrayMap<OnAudioDeviceConnectionListener, NativeEventHandlerDelegate>
        mDeviceConnectionListeners =
            new ArrayMap<OnAudioDeviceConnectionListener, NativeEventHandlerDelegate>();

    /**
     * @hide
     * The AudioDevicesManager class is used to enumerate the physical audio devices connected
     * to the system.  See also {@link AudioDeviceInfo}.
     */
    public AudioDevicesManager(Context context) {
        mAudioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        mPortListener = new OnAmPortUpdateListener();
        mAudioManager.registerAudioPortUpdateListener(mPortListener);
    }

    /**
     * Specifies to the {@link AudioDevicesManager#listDevices(int)} method to include
     * source (i.e. input) audio devices.
     */
    public static final int LIST_DEVICES_INPUTS    = 0x0001;

    /**
     * Specifies to the {@link AudioDevicesManager#listDevices(int)} method to include
     * sink (i.e. output) audio devices.
     */
    public static final int LIST_DEVICES_OUTPUTS   = 0x0002;

    /**
     * Specifies to the {@link AudioDevicesManager#listDevices(int)} method to include both
     * source and sink devices.
     */
    public static final int LIST_DEVICES_ALL = LIST_DEVICES_OUTPUTS | LIST_DEVICES_INPUTS;

    /**
     * Determines if a given AudioDevicePort meets the specified filter criteria.
     * @param port  The port to test.
     * @param flags A set of bitflags specifying the criteria to test.
     * @see {@link LIST_DEVICES_OUTPUTS} and {@link LIST_DEVICES_INPUTS}
     **/
    private static boolean checkFlags(AudioDevicePort port, int flags) {
        return port.role() == AudioPort.ROLE_SINK && (flags & LIST_DEVICES_OUTPUTS) != 0 ||
               port.role() == AudioPort.ROLE_SOURCE && (flags & LIST_DEVICES_INPUTS) != 0;
    }

    /**
     * Generates a list of AudioDeviceInfo objects corresponding to the audio devices currently
     * connected to the system and meeting the criteria specified in the <code>flags</code>
     * parameter.
     * @param flags A set of bitflags specifying the criteria to test.
     * @see {@link LIST_DEVICES_OUTPUTS}, {@link LIST_DEVICES_INPUTS} and {@link LIST_DEVICES_ALL}.
     * @return A (possibly zero-length) array of AudioDeviceInfo objects.
     */
    public AudioDeviceInfo[] listDevices(int flags) {
        return listDevicesStatic(flags);
    }

    /**
     * Generates a list of AudioDeviceInfo objects corresponding to the audio devices currently
     * connected to the system and meeting the criteria specified in the <code>flags</code>
     * parameter.
     * @param flags A set of bitflags specifying the criteria to test.
     * @see {@link LIST_DEVICES_OUTPUTS}, {@link LIST_DEVICES_INPUTS} and {@link LIST_DEVICES_ALL}.
     * @return A (possibly zero-length) array of AudioDeviceInfo objects.
     * @hide
     */
    public static AudioDeviceInfo[] listDevicesStatic(int flags) {
        ArrayList<AudioDevicePort> ports = new ArrayList<AudioDevicePort>();
        int status = AudioManager.listAudioDevicePorts(ports);
        if (status != AudioManager.SUCCESS) {
            // fail and bail!
            return new AudioDeviceInfo[0];
        }

        // figure out how many AudioDeviceInfo we need space for
        int numRecs = 0;
        for (AudioDevicePort port : ports) {
            if (checkFlags(port, flags)) {
                numRecs++;
            }
        }

        // Now load them up
        AudioDeviceInfo[] deviceList = new AudioDeviceInfo[numRecs];
        int slot = 0;
        for (AudioDevicePort port : ports) {
            if (checkFlags(port, flags)) {
                deviceList[slot++] = new AudioDeviceInfo(port);
            }
        }

        return deviceList;
    }

    /**
     * Adds an {@link OnAudioDeviceConnectionListener} to receive notifications of changes
     * to the set of connected audio devices.
     */
    public void addOnAudioDeviceConnectionListener(OnAudioDeviceConnectionListener listener,
            android.os.Handler handler) {
        if (listener != null && !mDeviceConnectionListeners.containsKey(listener)) {
            synchronized (mDeviceConnectionListeners) {
                mDeviceConnectionListeners.put(
                    listener, new NativeEventHandlerDelegate(listener, handler));
            }
        }
    }

    /**
     * Removes an {@link OnAudioDeviceConnectionListener} which has been previously registered
     * to receive notifications of changes to the set of connected audio devices.
     */
    public void removeOnAudioDeviceConnectionListener(OnAudioDeviceConnectionListener listener) {
        synchronized (mDeviceConnectionListeners) {
            if (mDeviceConnectionListeners.containsKey(listener)) {
                mDeviceConnectionListeners.remove(listener);
            }
        }
    }

    /**
     * Sends device list change notification to all listeners.
     */
    private void broadcastDeviceListChange() {
        Collection<NativeEventHandlerDelegate> values;
        synchronized (mDeviceConnectionListeners) {
            values = mDeviceConnectionListeners.values();
        }
        for(NativeEventHandlerDelegate delegate : values) {
            Handler handler = delegate.getHandler();
            if (handler != null) {
                handler.sendEmptyMessage(MSG_DEVICES_LIST_CHANGE);
            }
        }
    }

    /**
     * Handles Port list update notifications from the AudioManager
     */
    private class OnAmPortUpdateListener implements AudioManager.OnAudioPortUpdateListener {
        static final String TAG = "OnAmPortUpdateListener";
        public void onAudioPortListUpdate(AudioPort[] portList) {
            broadcastDeviceListChange();
        }

        /**
         * Callback method called upon audio patch list update.
         * @param patchList the updated list of audio patches
         */
        public void onAudioPatchListUpdate(AudioPatch[] patchList) {
            if (DEBUG) {
                Slog.d(TAG, "onAudioPatchListUpdate() " + patchList.length + " patches.");
            }
        }

        /**
         * Callback method called when the mediaserver dies
         */
        public void onServiceDied() {
            if (DEBUG) {
                Slog.i(TAG, "onServiceDied()");
            }

            broadcastDeviceListChange();
        }
    }

    //---------------------------------------------------------
    // Inner classes
    //--------------------
    /**
     * Helper class to handle the forwarding of native events to the appropriate listener
     * (potentially) handled in a different thread.
     */
    private class NativeEventHandlerDelegate {
        private final Handler mHandler;

        NativeEventHandlerDelegate(final OnAudioDeviceConnectionListener listener,
                                   Handler handler) {
            // find the looper for our new event handler
            Looper looper;
            if (handler != null) {
                looper = handler.getLooper();
            } else {
                // no given handler, use the looper the addListener call was called in
                looper = Looper.getMainLooper();
            }

            // construct the event handler with this looper
            if (looper != null) {
                // implement the event handler delegate
                mHandler = new Handler(looper) {
                    @Override
                    public void handleMessage(Message msg) {
                        switch(msg.what) {
                        case MSG_DEVICES_LIST_CHANGE:
                            // call the OnAudioDeviceConnectionListener
                            if (listener != null) {
                                listener.onAudioDeviceConnection();
                            }
                            break;
                        default:
                            Slog.e(TAG, "Unknown native event type: " + msg.what);
                            break;
                        }
                    }
                };
            } else {
                mHandler = null;
            }
        }

        Handler getHandler() {
            return mHandler;
        }
    }
}
