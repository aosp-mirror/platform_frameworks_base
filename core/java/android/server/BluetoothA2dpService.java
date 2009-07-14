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
 * TODO: Move this to services.jar
 * and make the contructor package private again.
 * @hide
 */

package android.server;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothError;
import android.bluetooth.BluetoothIntent;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothA2dp;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class BluetoothA2dpService extends IBluetoothA2dp.Stub {
    private static final String TAG = "BluetoothA2dpService";
    private static final boolean DBG = true;

    public static final String BLUETOOTH_A2DP_SERVICE = "bluetooth_a2dp";

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private static final String A2DP_SINK_ADDRESS = "a2dp_sink_address";
    private static final String BLUETOOTH_ENABLED = "bluetooth_enabled";

    private static final int MESSAGE_CONNECT_TO = 1;

    private static final String PROPERTY_STATE = "State";

    private static final String SINK_STATE_DISCONNECTED = "disconnected";
    private static final String SINK_STATE_CONNECTING = "connecting";
    private static final String SINK_STATE_CONNECTED = "connected";
    private static final String SINK_STATE_PLAYING = "playing";

    private static int mSinkCount;


    private final Context mContext;
    private final IntentFilter mIntentFilter;
    private HashMap<String, Integer> mAudioDevices;
    private final AudioManager mAudioManager;
    private final BluetoothDeviceService mBluetoothService;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String address = intent.getStringExtra(BluetoothIntent.ADDRESS);
            if (action.equals(BluetoothIntent.BLUETOOTH_STATE_CHANGED_ACTION)) {
                int state = intent.getIntExtra(BluetoothIntent.BLUETOOTH_STATE,
                                               BluetoothError.ERROR);
                switch (state) {
                case BluetoothDevice.BLUETOOTH_STATE_ON:
                    onBluetoothEnable();
                    break;
                case BluetoothDevice.BLUETOOTH_STATE_TURNING_OFF:
                    onBluetoothDisable();
                    break;
                }
            } else if (action.equals(BluetoothIntent.BOND_STATE_CHANGED_ACTION)) {
                int bondState = intent.getIntExtra(BluetoothIntent.BOND_STATE,
                                                   BluetoothError.ERROR);
                switch(bondState) {
                case BluetoothDevice.BOND_BONDED:
                    setSinkPriority(address, BluetoothA2dp.PRIORITY_AUTO);
                    break;
                case BluetoothDevice.BOND_BONDING:
                case BluetoothDevice.BOND_NOT_BONDED:
                    setSinkPriority(address, BluetoothA2dp.PRIORITY_OFF);
                    break;
                }
            } else if (action.equals(BluetoothIntent.REMOTE_DEVICE_CONNECTED_ACTION)) {
                if (getSinkPriority(address) > BluetoothA2dp.PRIORITY_OFF &&
                        isSinkDevice(address)) {
                    // This device is a preferred sink. Make an A2DP connection
                    // after a delay. We delay to avoid connection collisions,
                    // and to give other profiles such as HFP a chance to
                    // connect first.
                    Message msg = Message.obtain(mHandler, MESSAGE_CONNECT_TO, address);
                    mHandler.sendMessageDelayed(msg, 6000);
                }
            }
        }
    };

    public BluetoothA2dpService(Context context, BluetoothDeviceService bluetoothService) {
        mContext = context;

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        mBluetoothService = bluetoothService;
        if (mBluetoothService == null) {
            throw new RuntimeException("Platform does not support Bluetooth");
        }

        if (!initNative()) {
            throw new RuntimeException("Could not init BluetoothA2dpService");
        }

        mIntentFilter = new IntentFilter(BluetoothIntent.BLUETOOTH_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(BluetoothIntent.BOND_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(BluetoothIntent.REMOTE_DEVICE_CONNECTED_ACTION);
        mContext.registerReceiver(mReceiver, mIntentFilter);

        mAudioDevices = new HashMap<String, Integer>();

        if (mBluetoothService.isEnabled())
            onBluetoothEnable();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            cleanupNative();
        } finally {
            super.finalize();
        }
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_CONNECT_TO:
                String address = (String)msg.obj;
                // check bluetooth is still on, device is still preferred, and
                // nothing is currently connected
                if (mBluetoothService.isEnabled() &&
                        getSinkPriority(address) > BluetoothA2dp.PRIORITY_OFF &&
                        lookupSinksMatchingStates(new int[] {
                            BluetoothA2dp.STATE_CONNECTING,
                            BluetoothA2dp.STATE_CONNECTED,
                            BluetoothA2dp.STATE_PLAYING,
                            BluetoothA2dp.STATE_DISCONNECTING}).size() == 0) {
                    log("Auto-connecting A2DP to sink " + address);
                    connectSink(address);
                }
                break;
            }
        }
    };

    private int convertBluezSinkStringtoState(String value) {
        if (value.equalsIgnoreCase("disconnected"))
            return BluetoothA2dp.STATE_DISCONNECTED;
        if (value.equalsIgnoreCase("connecting"))
            return BluetoothA2dp.STATE_CONNECTING;
        if (value.equalsIgnoreCase("connected"))
            return BluetoothA2dp.STATE_CONNECTED;
        if (value.equalsIgnoreCase("playing"))
            return BluetoothA2dp.STATE_PLAYING;
        return -1;
    }

    private boolean isSinkDevice(String address) {
        String uuids[] = mBluetoothService.getRemoteUuids(address);
        UUID uuid;
        for (String deviceUuid: uuids) {
            uuid = UUID.fromString(deviceUuid);
            if (BluetoothUuid.isAudioSink(uuid)) {
                return true;
            }
        }
        return false;
    }

    private synchronized boolean addAudioSink (String address) {
        String path = mBluetoothService.getObjectPathFromAddress(address);
        String propValues[] = (String []) getSinkPropertiesNative(path);
        if (propValues == null) {
            Log.e(TAG, "Error while getting AudioSink properties for device: " + address);
            return false;
        }
        Integer state = null;
        // Properties are name-value pairs
        for (int i = 0; i < propValues.length; i+=2) {
            if (propValues[i].equals(PROPERTY_STATE)) {
                state = new Integer(convertBluezSinkStringtoState(propValues[i+1]));
                break;
            }
        }
        mAudioDevices.put(address, state);
        handleSinkStateChange(address, BluetoothA2dp.STATE_DISCONNECTED, state);
        return true;
    }

    private synchronized void onBluetoothEnable() {
        String devices = mBluetoothService.getProperty("Devices");
        mSinkCount = 0;
        if (devices != null) {
            String [] paths = devices.split(",");
            for (String path: paths) {
                String address = mBluetoothService.getAddressFromObjectPath(path);
                String []uuids = mBluetoothService.getRemoteUuids(address);
                if (uuids != null)
                    for (String uuid: uuids) {
                        UUID remoteUuid = UUID.fromString(uuid);
                        if (BluetoothUuid.isAudioSink(remoteUuid) ||
                            BluetoothUuid.isAudioSource(remoteUuid) ||
                            BluetoothUuid.isAdvAudioDist(remoteUuid)) {
                            addAudioSink(address);
                            break;
                        }
                    }
            }
        }
        mAudioManager.setParameter(BLUETOOTH_ENABLED, "true");
    }

    private synchronized void onBluetoothDisable() {
        if (!mAudioDevices.isEmpty()) {
            String [] addresses = new String[mAudioDevices.size()];
            addresses = mAudioDevices.keySet().toArray(addresses);
            for (String address : addresses) {
                int state = getSinkState(address);
                switch (state) {
                    case BluetoothA2dp.STATE_CONNECTING:
                    case BluetoothA2dp.STATE_CONNECTED:
                    case BluetoothA2dp.STATE_PLAYING:
                        disconnectSinkNative(mBluetoothService.getObjectPathFromAddress(address));
                        handleSinkStateChange(address,state, BluetoothA2dp.STATE_DISCONNECTED);
                        break;
                    case BluetoothA2dp.STATE_DISCONNECTING:
                        handleSinkStateChange(address, BluetoothA2dp.STATE_DISCONNECTING,
                                                BluetoothA2dp.STATE_DISCONNECTED);
                        break;
                }
            }
            mAudioDevices.clear();
        }
        mAudioManager.setBluetoothA2dpOn(false);
        mAudioManager.setParameter(BLUETOOTH_ENABLED, "false");
    }

    public synchronized int connectSink(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (DBG) log("connectSink(" + address + ")");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return BluetoothError.ERROR;
        }

        // ignore if there are any active sinks
        if (lookupSinksMatchingStates(new int[] {
                BluetoothA2dp.STATE_CONNECTING,
                BluetoothA2dp.STATE_CONNECTED,
                BluetoothA2dp.STATE_PLAYING,
                BluetoothA2dp.STATE_DISCONNECTING}).size() != 0) {
            return BluetoothError.ERROR;
        }

        if (mAudioDevices.get(address) == null && !addAudioSink(address))
            return BluetoothError.ERROR;

        int state = mAudioDevices.get(address);

        switch (state) {
        case BluetoothA2dp.STATE_CONNECTED:
        case BluetoothA2dp.STATE_PLAYING:
        case BluetoothA2dp.STATE_DISCONNECTING:
            return BluetoothError.ERROR;
        case BluetoothA2dp.STATE_CONNECTING:
            return BluetoothError.SUCCESS;
        }

        String path = mBluetoothService.getObjectPathFromAddress(address);
        if (path == null)
            return BluetoothError.ERROR;

        // State is DISCONNECTED
        if (!connectSinkNative(path)) {
            return BluetoothError.ERROR;
        }
        return BluetoothError.SUCCESS;
    }

    public synchronized int disconnectSink(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (DBG) log("disconnectSink(" + address + ")");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return BluetoothError.ERROR;
        }
        String path = mBluetoothService.getObjectPathFromAddress(address);
        if (path == null) {
            return BluetoothError.ERROR;
        }

        switch (getSinkState(address)) {
        case BluetoothA2dp.STATE_DISCONNECTED:
            return BluetoothError.ERROR;
        case BluetoothA2dp.STATE_DISCONNECTING:
            return BluetoothError.SUCCESS;
        }

        // State is CONNECTING or CONNECTED or PLAYING
        if (!disconnectSinkNative(path)) {
            return BluetoothError.ERROR;
        } else {
            return BluetoothError.SUCCESS;
        }
    }

    public synchronized List<String> listConnectedSinks() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return lookupSinksMatchingStates(new int[] {BluetoothA2dp.STATE_CONNECTED,
                                                    BluetoothA2dp.STATE_PLAYING});
    }

    public synchronized int getSinkState(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return BluetoothError.ERROR;
        }
        Integer state = mAudioDevices.get(address);
        if (state == null)
            return BluetoothA2dp.STATE_DISCONNECTED;
        return state;
    }

    public synchronized int getSinkPriority(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return BluetoothError.ERROR;
        }
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.getBluetoothA2dpSinkPriorityKey(address),
                BluetoothA2dp.PRIORITY_OFF);
    }

    public synchronized int setSinkPriority(String address, int priority) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return BluetoothError.ERROR;
        }
        return Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.getBluetoothA2dpSinkPriorityKey(address), priority) ?
                BluetoothError.SUCCESS : BluetoothError.ERROR;
    }

    private synchronized void onSinkPropertyChanged(String path, String []propValues) {
        String name = propValues[0];
        String address = mBluetoothService.getAddressFromObjectPath(path);
        if (address == null) {
            Log.e(TAG, "onSinkPropertyChanged: Address of the remote device in null");
            return;
        }

        if (mAudioDevices.get(address) == null) {
            // Ignore this state change, since it means we have got it after
            // bluetooth has been disabled.
            return;
        }
        if (name.equals(PROPERTY_STATE)) {
            int state = convertBluezSinkStringtoState(propValues[1]);
            int prevState = mAudioDevices.get(address);
            handleSinkStateChange(address, prevState, state);
        }
    }

    private void handleSinkStateChange(String address, int prevState, int state) {
        if (state != prevState) {
            if (state == BluetoothA2dp.STATE_DISCONNECTED ||
                    state == BluetoothA2dp.STATE_DISCONNECTING) {
                if (prevState == BluetoothA2dp.STATE_CONNECTED ||
                        prevState == BluetoothA2dp.STATE_PLAYING) {
                   // disconnecting or disconnected
                   Intent intent = new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
                   mContext.sendBroadcast(intent);
                }
                if (--mSinkCount == 0)
                    mAudioManager.setBluetoothA2dpOn(false);
            } else if (state == BluetoothA2dp.STATE_CONNECTED) {
                mSinkCount ++;
            }
            mAudioDevices.put(address, state);

            Intent intent = new Intent(BluetoothA2dp.SINK_STATE_CHANGED_ACTION);
            intent.putExtra(BluetoothIntent.ADDRESS, address);
            intent.putExtra(BluetoothA2dp.SINK_PREVIOUS_STATE, prevState);
            intent.putExtra(BluetoothA2dp.SINK_STATE, state);
            mContext.sendBroadcast(intent, BLUETOOTH_PERM);

            if (DBG) log("A2DP state : address: " + address + " State:" + prevState + "->" + state);

            if (state == BluetoothA2dp.STATE_CONNECTED) {
                mAudioManager.setParameter(A2DP_SINK_ADDRESS, address);
                mAudioManager.setBluetoothA2dpOn(true);
            }
        }
    }

    private synchronized List<String> lookupSinksMatchingStates(int[] states) {
        List<String> sinks = new ArrayList<String>();
        if (mAudioDevices.isEmpty()) {
            return sinks;
        }
        for (String path: mAudioDevices.keySet()) {
            int sinkState = getSinkState(path);
            for (int state : states) {
                if (state == sinkState) {
                    sinks.add(path);
                    break;
                }
            }
        }
        return sinks;
    }


    @Override
    protected synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mAudioDevices.isEmpty()) return;
        pw.println("Cached audio devices:");
        for (String address : mAudioDevices.keySet()) {
            int state = mAudioDevices.get(address);
            pw.println(address + " " + BluetoothA2dp.stateToString(state));
        }
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    private native boolean initNative();
    private native void cleanupNative();
    private synchronized native boolean connectSinkNative(String path);
    private synchronized native boolean disconnectSinkNative(String path);
    private synchronized native Object []getSinkPropertiesNative(String path);
}
