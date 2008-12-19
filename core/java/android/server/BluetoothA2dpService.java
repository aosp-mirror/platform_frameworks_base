/*
 * Copyright (C) 2008 The Android Open Source Project
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

/**
 * TODO: Move this to
 * java/services/com/android/server/BluetoothA2dpService.java
 * and make the contructor package private again.
 * @hide
 */

package android.server;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothError;
import android.bluetooth.BluetoothIntent;
import android.bluetooth.IBluetoothA2dp;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Binder;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Iterator;

public class BluetoothA2dpService extends IBluetoothA2dp.Stub {
    private static final String TAG = "BluetoothDeviceService";
    private static final boolean DBG = true;

    public static final String BLUETOOTH_A2DP_SERVICE = "bluetooth_a2dp";

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private final Context mContext;
    private final IntentFilter mIntentFilter;
    private HashMap<String, SinkState> mAudioDevices;
    private final AudioManager mAudioManager;

    private class SinkState {
        public String address;
        public int state;
        public SinkState(String a, int s) {address = a; state = s;}
    }

    public BluetoothA2dpService(Context context) {
        mContext = context;

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        BluetoothDevice device =
                (BluetoothDevice)mContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (device == null) {
            throw new RuntimeException("Platform does not support Bluetooth");
        }

        if (!initNative()) {
            throw new RuntimeException("Could not init BluetoothA2dpService");
        }

        mIntentFilter = new IntentFilter(BluetoothIntent.ENABLED_ACTION);
        mIntentFilter.addAction(BluetoothIntent.DISABLED_ACTION);
        mContext.registerReceiver(mReceiver, mIntentFilter);

        if (device.isEnabled()) {
            onBluetoothEnable();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            cleanupNative();
        } finally {
            super.finalize();
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(BluetoothIntent.ENABLED_ACTION)) {
                onBluetoothEnable();
            } else if (action.equals(BluetoothIntent.DISABLED_ACTION)) {
                onBluetoothDisable();
            }
        }
    };

    private synchronized void onBluetoothEnable() {
        mAudioDevices = new HashMap<String, SinkState>();
        String[] paths = (String[])listHeadsetsNative();
        if (paths != null) {
            for (String path : paths) {
                mAudioDevices.put(path, new SinkState(getAddressNative(path),
                        isSinkConnectedNative(path) ? BluetoothA2dp.STATE_CONNECTED :
                                                      BluetoothA2dp.STATE_DISCONNECTED));
            }
        }
    }

    private synchronized void onBluetoothDisable() {
        mAudioDevices = null;
    }

    public synchronized int connectSink(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (DBG) log("connectSink(" + address + ")");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return BluetoothError.ERROR;
        }
        if (mAudioDevices == null) {
            return BluetoothError.ERROR;
        }
        String path = lookupPath(address);
        if (path == null) {
            path = createHeadsetNative(address);
            if (DBG) log("new bluez sink: " + address + " (" + path + ")");
        }
        if (path == null) {
            return BluetoothError.ERROR;
        }
        if (!connectSinkNative(path)) {
            return BluetoothError.ERROR;
        } else {
            updateState(path, BluetoothA2dp.STATE_CONNECTING);
            return BluetoothError.SUCCESS;
        }
    }

    public synchronized int disconnectSink(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (DBG) log("disconnectSink(" + address + ")");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return BluetoothError.ERROR;
        }
        if (mAudioDevices == null) {
            return BluetoothError.ERROR;
        }
        String path = lookupPath(address);
        if (path == null) {
            return BluetoothError.ERROR;
        }
        if (!disconnectSinkNative(path)) {
            return BluetoothError.ERROR;
        } else {
            updateState(path, BluetoothA2dp.STATE_DISCONNECTING);
            return BluetoothError.SUCCESS;
        }
    }

    public synchronized List<String> listConnectedSinks() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        List<String> connectedSinks = new ArrayList<String>();
        if (mAudioDevices == null) {
            return connectedSinks;
        }
        for (SinkState sink : mAudioDevices.values()) {
            if (sink.state == BluetoothA2dp.STATE_CONNECTED ||
                sink.state == BluetoothA2dp.STATE_PLAYING) {
                connectedSinks.add(sink.address);
            }
        }
        return connectedSinks;
    }

    public synchronized int getSinkState(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return BluetoothError.ERROR;
        }
        if (mAudioDevices == null) {
            return BluetoothA2dp.STATE_DISCONNECTED;
        }
        for (SinkState sink : mAudioDevices.values()) {
            if (address.equals(sink.address)) {
                return sink.state;
            }
        }
        return BluetoothA2dp.STATE_DISCONNECTED;
    }

    public synchronized void onHeadsetCreated(String path) {
        updateState(path, BluetoothA2dp.STATE_DISCONNECTED);
    }

    public synchronized void onHeadsetRemoved(String path) {
        if (mAudioDevices == null) return;
        mAudioDevices.remove(path);
    }

    public synchronized void onSinkConnected(String path) {
        if (mAudioDevices == null) return;
        // bluez 3.36 quietly disconnects the previous sink when a new sink
        // is connected, so we need to mark all previously connected sinks as
        // disconnected
        for (String oldPath : mAudioDevices.keySet()) {
            if (path.equals(oldPath)) {
                continue;
            }
            int state = mAudioDevices.get(oldPath).state;
            if (state == BluetoothA2dp.STATE_CONNECTED || state == BluetoothA2dp.STATE_PLAYING) {
                updateState(path, BluetoothA2dp.STATE_DISCONNECTED);
            }
        }

        mAudioManager.setBluetoothA2dpOn(true);
        updateState(path, BluetoothA2dp.STATE_CONNECTED);
    }

    public synchronized void onSinkDisconnected(String path) {
        mAudioManager.setBluetoothA2dpOn(false);
        updateState(path, BluetoothA2dp.STATE_DISCONNECTED);
    }

    public synchronized void onSinkPlaying(String path) {
        updateState(path, BluetoothA2dp.STATE_PLAYING);
    }

    public synchronized void onSinkStopped(String path) {
        updateState(path, BluetoothA2dp.STATE_CONNECTED);
    }

    private synchronized final String lookupAddress(String path) {
        if (mAudioDevices == null) return null;
        String address = mAudioDevices.get(path).address;
        if (address == null) Log.e(TAG, "Can't find address for " + path);
        return address;
    }

    private synchronized final String lookupPath(String address) {
        if (mAudioDevices == null) return null;

        for (String path : mAudioDevices.keySet()) {
            if (address.equals(mAudioDevices.get(path).address)) {
                return path;
            }
        }
        return null;
    }

    private synchronized void updateState(String path, int state) {
        if (mAudioDevices == null) return;

        SinkState s = mAudioDevices.get(path);
        int prevState;
        String address;
        if (s == null) {
            address = getAddressNative(path);
            mAudioDevices.put(path, new SinkState(address, state));
            prevState = BluetoothA2dp.STATE_DISCONNECTED;
        } else {
            address = lookupAddress(path);
            prevState = s.state;
            s.state = state;
        }

        if (DBG) log("state " + address + " (" + path + ") " + prevState + "->" + state);

        Intent intent = new Intent(BluetoothA2dp.SINK_STATE_CHANGED_ACTION);
        intent.putExtra(BluetoothIntent.ADDRESS, address);
        intent.putExtra(BluetoothA2dp.SINK_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothA2dp.SINK_STATE, state);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }

    @Override
    protected synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mAudioDevices == null) return;
        pw.println("Cached audio devices:");
        for (String path : mAudioDevices.keySet()) {
            SinkState sink = mAudioDevices.get(path);
            pw.println(path + " " + sink.address + " " + BluetoothA2dp.stateToString(sink.state));
        }
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    private native boolean initNative();
    private native void cleanupNative();
    private synchronized native String[] listHeadsetsNative();
    private synchronized native String createHeadsetNative(String address);
    private synchronized native boolean removeHeadsetNative(String path);
    private synchronized native String getAddressNative(String path);
    private synchronized native boolean connectSinkNative(String path);
    private synchronized native boolean disconnectSinkNative(String path);
    private synchronized native boolean isSinkConnectedNative(String path);

}
