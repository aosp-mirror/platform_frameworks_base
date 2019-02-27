/*
 * Copyright 2019 The Android Open Source Project
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
package com.android.server.audio;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.media.AudioDevicePort;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPort;
import android.media.AudioRoutesInfo;
import android.media.AudioSystem;
import android.media.IAudioRoutesObserver;
import android.os.Binder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;

/**
 * Class to manage the inventory of all connected devices.
 * This class is thread-safe.
 */
public final class AudioDeviceInventory {

    private static final String TAG = "AS.AudioDeviceInventory";

    // Actual list of connected devices
    // Key for map created from DeviceInfo.makeDeviceListKey()
    private final ArrayMap<String, DeviceInfo> mConnectedDevices = new ArrayMap<>();

    private final @NonNull AudioDeviceBroker mDeviceBroker;

    AudioDeviceInventory(@NonNull AudioDeviceBroker broker) {
        mDeviceBroker = broker;
    }

    // cache of the address of the last dock the device was connected to
    private String mDockAddress;

    // Monitoring of audio routes.  Protected by mAudioRoutes.
    final AudioRoutesInfo mCurAudioRoutes = new AudioRoutesInfo();
    final RemoteCallbackList<IAudioRoutesObserver> mRoutesObservers =
            new RemoteCallbackList<IAudioRoutesObserver>();

    //------------------------------------------------------------
    /**
     * Class to store info about connected devices.
     * Use makeDeviceListKey() to make a unique key for this list.
     */
    private static class DeviceInfo {
        final int mDeviceType;
        final String mDeviceName;
        final String mDeviceAddress;
        int mDeviceCodecFormat;

        DeviceInfo(int deviceType, String deviceName, String deviceAddress, int deviceCodecFormat) {
            mDeviceType = deviceType;
            mDeviceName = deviceName;
            mDeviceAddress = deviceAddress;
            mDeviceCodecFormat = deviceCodecFormat;
        }

        @Override
        public String toString() {
            return "[DeviceInfo: type:0x" + Integer.toHexString(mDeviceType)
                    + " name:" + mDeviceName
                    + " addr:" + mDeviceAddress
                    + " codec: " + Integer.toHexString(mDeviceCodecFormat) + "]";
        }

        /**
         * Generate a unique key for the mConnectedDevices List by composing the device "type"
         * and the "address" associated with a specific instance of that device type
         */
        private static String makeDeviceListKey(int device, String deviceAddress) {
            return "0x" + Integer.toHexString(device) + ":" + deviceAddress;
        }
    }

    /**
     * A class just for packaging up a set of connection parameters.
     */
    /*package*/ class WiredDeviceConnectionState {
        public final int mType;
        public final @AudioService.ConnectionState int mState;
        public final String mAddress;
        public final String mName;
        public final String mCaller;

        /*package*/ WiredDeviceConnectionState(int type, @AudioService.ConnectionState int state,
                                               String address, String name, String caller) {
            mType = type;
            mState = state;
            mAddress = address;
            mName = name;
            mCaller = caller;
        }
    }

    //------------------------------------------------------------
    // Message handling from AudioDeviceBroker

    /**
     * Restore previously connected devices. Use in case of audio server crash
     * (see AudioService.onAudioServerDied() method)
     */
    /*package*/ void onRestoreDevices() {
        synchronized (mConnectedDevices) {
            for (int i = 0; i < mConnectedDevices.size(); i++) {
                DeviceInfo di = mConnectedDevices.valueAt(i);
                AudioSystem.setDeviceConnectionState(
                        di.mDeviceType,
                        AudioSystem.DEVICE_STATE_AVAILABLE,
                        di.mDeviceAddress,
                        di.mDeviceName,
                        di.mDeviceCodecFormat);
            }
        }
    }

    /*package*/ void onSetA2dpSinkConnectionState(@NonNull BtHelper.BluetoothA2dpDeviceInfo btInfo,
            @AudioService.BtProfileConnectionState int state) {
        final BluetoothDevice btDevice = btInfo.getBtDevice();
        int a2dpVolume = btInfo.getVolume();
        if (AudioService.DEBUG_DEVICES) {
            Log.d(TAG, "onSetA2dpSinkConnectionState btDevice=" + btDevice + " state="
                    + state + " is dock=" + btDevice.isBluetoothDock() + " vol=" + a2dpVolume);
        }
        String address = btDevice.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = "";
        }
        AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(
                "A2DP sink connected: device addr=" + address + " state=" + state
                        + " vol=" + a2dpVolume));

        final int a2dpCodec = btInfo.getCodec();

        synchronized (mConnectedDevices) {
            final String key = DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                    btDevice.getAddress());
            final DeviceInfo di = mConnectedDevices.get(key);
            boolean isConnected = di != null;

            if (isConnected && state != BluetoothProfile.STATE_CONNECTED) {
                if (btDevice.isBluetoothDock()) {
                    if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        // introduction of a delay for transient disconnections of docks when
                        // power is rapidly turned off/on, this message will be canceled if
                        // we reconnect the dock under a preset delay
                        makeA2dpDeviceUnavailableLater(address,
                                AudioDeviceBroker.BTA2DP_DOCK_TIMEOUT_MS);
                        // the next time isConnected is evaluated, it will be false for the dock
                    }
                } else {
                    makeA2dpDeviceUnavailableNow(address, di.mDeviceCodecFormat);
                }
            } else if (!isConnected && state == BluetoothProfile.STATE_CONNECTED) {
                if (btDevice.isBluetoothDock()) {
                    // this could be a reconnection after a transient disconnection
                    mDeviceBroker.cancelA2dpDockTimeout();
                    mDockAddress = address;
                } else {
                    // this could be a connection of another A2DP device before the timeout of
                    // a dock: cancel the dock timeout, and make the dock unavailable now
                    if (mDeviceBroker.hasScheduledA2dpDockTimeout() && mDockAddress != null) {
                        mDeviceBroker.cancelA2dpDockTimeout();
                        makeA2dpDeviceUnavailableNow(mDockAddress,
                                AudioSystem.AUDIO_FORMAT_DEFAULT);
                    }
                }
                if (a2dpVolume != -1) {
                    AudioService.VolumeStreamState streamState =
                            mDeviceBroker.getStreamState(AudioSystem.STREAM_MUSIC);
                    // Convert index to internal representation in VolumeStreamState
                    a2dpVolume = a2dpVolume * 10;
                    streamState.setIndex(a2dpVolume, AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                            "onSetA2dpSinkConnectionState");
                    mDeviceBroker.setDeviceVolume(
                            streamState, AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP);
                }
                makeA2dpDeviceAvailable(address, btDevice.getName(),
                        "onSetA2dpSinkConnectionState", a2dpCodec);
            }
        }
    }

    /*package*/ void onSetA2dpSourceConnectionState(
            @NonNull BtHelper.BluetoothA2dpDeviceInfo btInfo, int state) {
        final BluetoothDevice btDevice = btInfo.getBtDevice();
        if (AudioService.DEBUG_DEVICES) {
            Log.d(TAG, "onSetA2dpSourceConnectionState btDevice=" + btDevice + " state="
                    + state);
        }
        String address = btDevice.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = "";
        }

        synchronized (mConnectedDevices) {
            final String key = DeviceInfo.makeDeviceListKey(
                    AudioSystem.DEVICE_IN_BLUETOOTH_A2DP, address);
            final DeviceInfo di = mConnectedDevices.get(key);
            boolean isConnected = di != null;

            if (isConnected && state != BluetoothProfile.STATE_CONNECTED) {
                makeA2dpSrcUnavailable(address);
            } else if (!isConnected && state == BluetoothProfile.STATE_CONNECTED) {
                makeA2dpSrcAvailable(address);
            }
        }
    }

    /*package*/ void onSetHearingAidConnectionState(BluetoothDevice btDevice,
                @AudioService.BtProfileConnectionState int state) {
        String address = btDevice.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = "";
        }
        AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(
                "onSetHearingAidConnectionState addr=" + address));

        synchronized (mConnectedDevices) {
            final String key = DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_OUT_HEARING_AID,
                    btDevice.getAddress());
            final DeviceInfo di = mConnectedDevices.get(key);
            boolean isConnected = di != null;

            if (isConnected && state != BluetoothProfile.STATE_CONNECTED) {
                makeHearingAidDeviceUnavailable(address);
            } else if (!isConnected && state == BluetoothProfile.STATE_CONNECTED) {
                makeHearingAidDeviceAvailable(address, btDevice.getName(),
                        "onSetHearingAidConnectionState");
            }
        }
    }

    /*package*/ void onBluetoothA2dpActiveDeviceChange(
            @NonNull BtHelper.BluetoothA2dpDeviceInfo btInfo, int event) {
        final BluetoothDevice btDevice = btInfo.getBtDevice();
        if (btDevice == null) {
            return;
        }
        if (AudioService.DEBUG_DEVICES) {
            Log.d(TAG, "onBluetoothA2dpActiveDeviceChange btDevice=" + btDevice);
        }
        int a2dpVolume = btInfo.getVolume();
        final int a2dpCodec = btInfo.getCodec();

        String address = btDevice.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = "";
        }
        AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(
                "onBluetoothA2dpActiveDeviceChange addr=" + address
                    + " event=" + BtHelper.a2dpDeviceEventToString(event)));

        synchronized (mConnectedDevices) {
            //TODO original CL is not consistent between BluetoothDevice and BluetoothA2dpDeviceInfo
            // for this type of message
            if (mDeviceBroker.hasScheduledA2dpSinkConnectionState(btDevice)) {
                AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(
                        "A2dp config change ignored"));
                return;
            }
            final String key = DeviceInfo.makeDeviceListKey(
                    AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, address);
            final DeviceInfo di = mConnectedDevices.get(key);
            if (di == null) {
                Log.e(TAG, "invalid null DeviceInfo in onBluetoothA2dpActiveDeviceChange");
                return;
            }

            if (event == BtHelper.EVENT_ACTIVE_DEVICE_CHANGE) {
                // Device is connected
                if (a2dpVolume != -1) {
                    final AudioService.VolumeStreamState streamState =
                            mDeviceBroker.getStreamState(AudioSystem.STREAM_MUSIC);
                    // Convert index to internal representation in VolumeStreamState
                    a2dpVolume = a2dpVolume * 10;
                    streamState.setIndex(a2dpVolume, AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                            "onBluetoothA2dpActiveDeviceChange");
                    mDeviceBroker.setDeviceVolume(
                            streamState, AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP);
                }
            } else if (event == BtHelper.EVENT_DEVICE_CONFIG_CHANGE) {
                if (di.mDeviceCodecFormat != a2dpCodec) {
                    di.mDeviceCodecFormat = a2dpCodec;
                    mConnectedDevices.replace(key, di);
                }
            }
            if (AudioSystem.handleDeviceConfigChange(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, address,
                    btDevice.getName(), a2dpCodec) != AudioSystem.AUDIO_STATUS_OK) {
                int musicDevice = mDeviceBroker.getDeviceForStream(AudioSystem.STREAM_MUSIC);
                // force A2DP device disconnection in case of error so that AudioService state is
                // consistent with audio policy manager state
                setBluetoothA2dpDeviceConnectionState(
                        btDevice, BluetoothA2dp.STATE_DISCONNECTED, BluetoothProfile.A2DP,
                        false /* suppressNoisyIntent */, musicDevice,
                        -1 /* a2dpVolume */);
            }
        }
    }

    /*package*/ void onMakeA2dpDeviceUnavailableNow(String address, int a2dpCodec) {
        synchronized (mConnectedDevices) {
            makeA2dpDeviceUnavailableNow(address, a2dpCodec);
        }
    }

    /*package*/ void onReportNewRoutes() {
        int n = mRoutesObservers.beginBroadcast();
        if (n > 0) {
            AudioRoutesInfo routes;
            synchronized (mCurAudioRoutes) {
                routes = new AudioRoutesInfo(mCurAudioRoutes);
            }
            while (n > 0) {
                n--;
                IAudioRoutesObserver obs = mRoutesObservers.getBroadcastItem(n);
                try {
                    obs.dispatchAudioRoutesChanged(routes);
                } catch (RemoteException e) { }
            }
        }
        mRoutesObservers.finishBroadcast();
        mDeviceBroker.observeDevicesForAllStreams();
    }

    private static final int DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG =
            AudioSystem.DEVICE_OUT_WIRED_HEADSET | AudioSystem.DEVICE_OUT_WIRED_HEADPHONE
                    | AudioSystem.DEVICE_OUT_LINE | AudioSystem.DEVICE_OUT_ALL_USB;

    /*package*/ void onSetWiredDeviceConnectionState(
                            AudioDeviceInventory.WiredDeviceConnectionState wdcs) {
        AudioService.sDeviceLogger.log(new AudioServiceEvents.WiredDevConnectEvent(wdcs));

        synchronized (mConnectedDevices) {
            if ((wdcs.mState == AudioService.CONNECTION_STATE_DISCONNECTED)
                    && ((wdcs.mType & DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG) != 0)) {
                mDeviceBroker.setBluetoothA2dpOnInt(true,
                        "onSetWiredDeviceConnectionState state DISCONNECTED");
            }

            if (!handleDeviceConnection(wdcs.mState == 1, wdcs.mType, wdcs.mAddress,
                    wdcs.mName)) {
                // change of connection state failed, bailout
                return;
            }
            if (wdcs.mState != AudioService.CONNECTION_STATE_DISCONNECTED) {
                if ((wdcs.mType & DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG) != 0) {
                    mDeviceBroker.setBluetoothA2dpOnInt(false,
                            "onSetWiredDeviceConnectionState state not DISCONNECTED");
                }
                mDeviceBroker.checkMusicActive(wdcs.mType, wdcs.mCaller);
            }
            mDeviceBroker.checkVolumeCecOnHdmiConnection(wdcs.mState, wdcs.mCaller);
            sendDeviceConnectionIntent(wdcs.mType, wdcs.mState, wdcs.mAddress, wdcs.mName);
            updateAudioRoutes(wdcs.mType, wdcs.mState);
        }
    }

    /*package*/ void onToggleHdmi() {
        synchronized (mConnectedDevices) {
            // Is HDMI connected?
            final String key = DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_OUT_HDMI, "");
            final DeviceInfo di = mConnectedDevices.get(key);
            if (di == null) {
                Log.e(TAG, "invalid null DeviceInfo in onToggleHdmi");
                return;
            }
            // Toggle HDMI to retrigger broadcast with proper formats.
            setWiredDeviceConnectionState(AudioSystem.DEVICE_OUT_HDMI,
                    AudioSystem.DEVICE_STATE_UNAVAILABLE, "", "",
                    "android"); // disconnect
            setWiredDeviceConnectionState(AudioSystem.DEVICE_OUT_HDMI,
                    AudioSystem.DEVICE_STATE_AVAILABLE, "", "",
                    "android"); // reconnect
        }
    }
    //------------------------------------------------------------
    //

    /**
     * Implements the communication with AudioSystem to (dis)connect a device in the native layers
     * @param connect true if connection
     * @param device the device type
     * @param address the address of the device
     * @param deviceName human-readable name of device
     * @return false if an error was reported by AudioSystem
     */
    /*package*/ boolean handleDeviceConnection(boolean connect, int device, String address,
            String deviceName) {
        if (AudioService.DEBUG_DEVICES) {
            Slog.i(TAG, "handleDeviceConnection(" + connect + " dev:"
                    + Integer.toHexString(device) + " address:" + address
                    + " name:" + deviceName + ")");
        }
        synchronized (mConnectedDevices) {
            final String deviceKey = DeviceInfo.makeDeviceListKey(device, address);
            if (AudioService.DEBUG_DEVICES) {
                Slog.i(TAG, "deviceKey:" + deviceKey);
            }
            DeviceInfo di = mConnectedDevices.get(deviceKey);
            boolean isConnected = di != null;
            if (AudioService.DEBUG_DEVICES) {
                Slog.i(TAG, "deviceInfo:" + di + " is(already)Connected:" + isConnected);
            }
            if (connect && !isConnected) {
                final int res = AudioSystem.setDeviceConnectionState(device,
                        AudioSystem.DEVICE_STATE_AVAILABLE, address, deviceName,
                        AudioSystem.AUDIO_FORMAT_DEFAULT);
                if (res != AudioSystem.AUDIO_STATUS_OK) {
                    Slog.e(TAG, "not connecting device 0x" + Integer.toHexString(device)
                            + " due to command error " + res);
                    return false;
                }
                mConnectedDevices.put(deviceKey, new DeviceInfo(
                        device, deviceName, address, AudioSystem.AUDIO_FORMAT_DEFAULT));
                mDeviceBroker.postAccessoryPlugMediaUnmute(device);
                return true;
            } else if (!connect && isConnected) {
                AudioSystem.setDeviceConnectionState(device,
                        AudioSystem.DEVICE_STATE_UNAVAILABLE, address, deviceName,
                        AudioSystem.AUDIO_FORMAT_DEFAULT);
                // always remove even if disconnection failed
                mConnectedDevices.remove(deviceKey);
                return true;
            }
            Log.w(TAG, "handleDeviceConnection() failed, deviceKey=" + deviceKey
                    + ", deviceSpec=" + di + ", connect=" + connect);
        }
        return false;
    }


    /*package*/ void disconnectA2dp() {
        synchronized (mConnectedDevices) {
            final ArraySet<String> toRemove = new ArraySet<>();
            // Disconnect ALL DEVICE_OUT_BLUETOOTH_A2DP devices
            mConnectedDevices.values().forEach(deviceInfo -> {
                if (deviceInfo.mDeviceType == AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP) {
                    toRemove.add(deviceInfo.mDeviceAddress);
                }
            });
            if (toRemove.size() > 0) {
                final int delay = checkSendBecomingNoisyIntentInt(
                        AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                        AudioService.CONNECTION_STATE_DISCONNECTED, AudioSystem.DEVICE_NONE);
                toRemove.stream().forEach(deviceAddress ->
                        makeA2dpDeviceUnavailableLater(deviceAddress, delay)
                );
            }
        }
    }

    /*package*/ void disconnectA2dpSink() {
        synchronized (mConnectedDevices) {
            final ArraySet<String> toRemove = new ArraySet<>();
            // Disconnect ALL DEVICE_IN_BLUETOOTH_A2DP devices
            mConnectedDevices.values().forEach(deviceInfo -> {
                if (deviceInfo.mDeviceType == AudioSystem.DEVICE_IN_BLUETOOTH_A2DP) {
                    toRemove.add(deviceInfo.mDeviceAddress);
                }
            });
            toRemove.stream().forEach(deviceAddress -> makeA2dpSrcUnavailable(deviceAddress));
        }
    }

    /*package*/ void disconnectHearingAid() {
        synchronized (mConnectedDevices) {
            final ArraySet<String> toRemove = new ArraySet<>();
            // Disconnect ALL DEVICE_OUT_HEARING_AID devices
            mConnectedDevices.values().forEach(deviceInfo -> {
                if (deviceInfo.mDeviceType == AudioSystem.DEVICE_OUT_HEARING_AID) {
                    toRemove.add(deviceInfo.mDeviceAddress);
                }
            });
            if (toRemove.size() > 0) {
                final int delay = checkSendBecomingNoisyIntentInt(
                        AudioSystem.DEVICE_OUT_HEARING_AID, 0, AudioSystem.DEVICE_NONE);
                toRemove.stream().forEach(deviceAddress ->
                        // TODO delay not used?
                        makeHearingAidDeviceUnavailable(deviceAddress /*, delay*/)
                );
            }
        }
    }

    // must be called before removing the device from mConnectedDevices
    // musicDevice argument is used when not AudioSystem.DEVICE_NONE instead of querying
    // from AudioSystem
    /*package*/ int checkSendBecomingNoisyIntent(int device,
            @AudioService.ConnectionState int state, int musicDevice) {
        synchronized (mConnectedDevices) {
            return checkSendBecomingNoisyIntentInt(device, state, musicDevice);
        }
    }

    /*package*/ AudioRoutesInfo startWatchingRoutes(IAudioRoutesObserver observer) {
        synchronized (mCurAudioRoutes) {
            AudioRoutesInfo routes = new AudioRoutesInfo(mCurAudioRoutes);
            mRoutesObservers.register(observer);
            return routes;
        }
    }

    /*package*/ AudioRoutesInfo getCurAudioRoutes() {
        return mCurAudioRoutes;
    }

    /*package*/ void setBluetoothA2dpDeviceConnectionState(
            @NonNull BluetoothDevice device, @AudioService.BtProfileConnectionState int state,
            int profile, boolean suppressNoisyIntent, int musicDevice, int a2dpVolume) {
        int delay;
        if (profile != BluetoothProfile.A2DP && profile != BluetoothProfile.A2DP_SINK) {
            throw new IllegalArgumentException("invalid profile " + profile);
        }
        synchronized (mConnectedDevices) {
            if (profile == BluetoothProfile.A2DP && !suppressNoisyIntent) {
                int intState = (state == BluetoothA2dp.STATE_CONNECTED) ? 1 : 0;
                delay = checkSendBecomingNoisyIntentInt(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                        intState, musicDevice);
            } else {
                delay = 0;
            }

            final int a2dpCodec = mDeviceBroker.getA2dpCodec(device);

            if (AudioService.DEBUG_DEVICES) {
                Log.i(TAG, "setBluetoothA2dpDeviceConnectionState device: " + device
                        + " state: " + state + " delay(ms): " + delay + "codec:" + a2dpCodec
                        + " suppressNoisyIntent: " + suppressNoisyIntent);
            }

            final BtHelper.BluetoothA2dpDeviceInfo a2dpDeviceInfo =
                    new BtHelper.BluetoothA2dpDeviceInfo(device, a2dpVolume, a2dpCodec);
            if (profile == BluetoothProfile.A2DP) {
                mDeviceBroker.postA2dpSinkConnection(state,
                        a2dpDeviceInfo,
                        delay);
            } else { //profile == BluetoothProfile.A2DP_SINK
                mDeviceBroker.postA2dpSourceConnection(state,
                        a2dpDeviceInfo,
                        delay);
            }
        }
    }

    /*package*/ void handleBluetoothA2dpActiveDeviceChangeExt(
            @NonNull BluetoothDevice device,
            @AudioService.BtProfileConnectionState int state, int profile,
            boolean suppressNoisyIntent, int a2dpVolume) {
        if (state == BluetoothProfile.STATE_DISCONNECTED) {
            mDeviceBroker.postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(
                           device, state, profile, suppressNoisyIntent, a2dpVolume);
            return;
        }
        // state == BluetoothProfile.STATE_CONNECTED
        synchronized (mConnectedDevices) {
            final String address = device.getAddress();
            final int a2dpCodec = mDeviceBroker.getA2dpCodec(device);
            final String deviceKey = DeviceInfo.makeDeviceListKey(
                        AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, address);
            DeviceInfo deviceInfo = mConnectedDevices.get(deviceKey);
            if (deviceInfo != null) {
                // Device config change for matching A2DP device
                mDeviceBroker.postBluetoothA2dpDeviceConfigChange(device);
                return;
            }
            for (int i = 0; i < mConnectedDevices.size(); i++) {
                deviceInfo = mConnectedDevices.valueAt(i);
                if (deviceInfo.mDeviceType != AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP) {
                    continue;
                }
                // A2DP device exists, handle active device change
                final String existingDevicekey = mConnectedDevices.keyAt(i);
                final String deviceName = device.getName();
                mConnectedDevices.remove(existingDevicekey);
                mConnectedDevices.put(deviceKey, new DeviceInfo(
                        AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, deviceName,
                        address, a2dpCodec));
                mDeviceBroker.postA2dpActiveDeviceChange(
                        new BtHelper.BluetoothA2dpDeviceInfo(
                            device, a2dpVolume, a2dpCodec));
                return;
            }
        }
        // New A2DP device connection
        mDeviceBroker.postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(
                           device, state, profile, suppressNoisyIntent, a2dpVolume);
    }

    /*package*/ int setWiredDeviceConnectionState(int type, @AudioService.ConnectionState int state,
                                                  String address, String name, String caller) {
        synchronized (mConnectedDevices) {
            int delay = checkSendBecomingNoisyIntentInt(type, state, AudioSystem.DEVICE_NONE);
            mDeviceBroker.postSetWiredDeviceConnectionState(
                    new WiredDeviceConnectionState(type, state, address, name, caller),
                    delay);
            return delay;
        }
    }

    /*package*/ int  setBluetoothHearingAidDeviceConnectionState(
            @NonNull BluetoothDevice device, @AudioService.BtProfileConnectionState int state,
            boolean suppressNoisyIntent, int musicDevice) {
        int delay;
        synchronized (mConnectedDevices) {
            if (!suppressNoisyIntent) {
                int intState = (state == BluetoothHearingAid.STATE_CONNECTED) ? 1 : 0;
                delay = checkSendBecomingNoisyIntentInt(AudioSystem.DEVICE_OUT_HEARING_AID,
                        intState, musicDevice);
            } else {
                delay = 0;
            }
            mDeviceBroker.postSetHearingAidConnectionState(state, device, delay);
            return delay;
        }
    }


    //-------------------------------------------------------------------
    // Internal utilities

    @GuardedBy("mConnectedDevices")
    private void makeA2dpDeviceAvailable(String address, String name, String eventSource,
            int a2dpCodec) {
        // enable A2DP before notifying A2DP connection to avoid unnecessary processing in
        // audio policy manager
        AudioService.VolumeStreamState streamState =
                mDeviceBroker.getStreamState(AudioSystem.STREAM_MUSIC);
        mDeviceBroker.setBluetoothA2dpOnInt(true, eventSource);
        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                AudioSystem.DEVICE_STATE_AVAILABLE, address, name, a2dpCodec);
        // Reset A2DP suspend state each time a new sink is connected
        AudioSystem.setParameters("A2dpSuspended=false");
        mConnectedDevices.put(
                DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, address),
                new DeviceInfo(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, name,
                        address, a2dpCodec));
        mDeviceBroker.postAccessoryPlugMediaUnmute(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP);
        setCurrentAudioRouteNameIfPossible(name);
    }

    @GuardedBy("mConnectedDevices")
    private void makeA2dpDeviceUnavailableNow(String address, int a2dpCodec) {
        if (address == null) {
            return;
        }
        mDeviceBroker.setAvrcpAbsoluteVolumeSupported(false);
        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                AudioSystem.DEVICE_STATE_UNAVAILABLE, address, "", a2dpCodec);
        mConnectedDevices.remove(
                DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, address));
        // Remove A2DP routes as well
        setCurrentAudioRouteNameIfPossible(null);
        if (mDockAddress == address) {
            mDockAddress = null;
        }
    }

    @GuardedBy("mConnectedDevices")
    private void makeA2dpDeviceUnavailableLater(String address, int delayMs) {
        // prevent any activity on the A2DP audio output to avoid unwanted
        // reconnection of the sink.
        AudioSystem.setParameters("A2dpSuspended=true");
        // retrieve DeviceInfo before removing device
        final String deviceKey =
                DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, address);
        final DeviceInfo deviceInfo = mConnectedDevices.get(deviceKey);
        final int a2dpCodec = deviceInfo != null ? deviceInfo.mDeviceCodecFormat :
                AudioSystem.AUDIO_FORMAT_DEFAULT;
        // the device will be made unavailable later, so consider it disconnected right away
        mConnectedDevices.remove(deviceKey);
        // send the delayed message to make the device unavailable later
        mDeviceBroker.setA2dpDockTimeout(address, a2dpCodec, delayMs);
    }


    @GuardedBy("mConnectedDevices")
    private void makeA2dpSrcAvailable(String address) {
        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_IN_BLUETOOTH_A2DP,
                AudioSystem.DEVICE_STATE_AVAILABLE, address, "",
                AudioSystem.AUDIO_FORMAT_DEFAULT);
        mConnectedDevices.put(
                DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_IN_BLUETOOTH_A2DP, address),
                new DeviceInfo(AudioSystem.DEVICE_IN_BLUETOOTH_A2DP, "",
                        address, AudioSystem.AUDIO_FORMAT_DEFAULT));
    }

    @GuardedBy("mConnectedDevices")
    private void makeA2dpSrcUnavailable(String address) {
        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_IN_BLUETOOTH_A2DP,
                AudioSystem.DEVICE_STATE_UNAVAILABLE, address, "",
                AudioSystem.AUDIO_FORMAT_DEFAULT);
        mConnectedDevices.remove(
                DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_IN_BLUETOOTH_A2DP, address));
    }

    @GuardedBy("mConnectedDevices")
    private void makeHearingAidDeviceAvailable(String address, String name, String eventSource) {
        final int hearingAidVolIndex = mDeviceBroker.getStreamState(AudioSystem.STREAM_MUSIC)
                .getIndex(AudioSystem.DEVICE_OUT_HEARING_AID);
        mDeviceBroker.postSetHearingAidVolumeIndex(hearingAidVolIndex, AudioSystem.STREAM_MUSIC);

        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_HEARING_AID,
                AudioSystem.DEVICE_STATE_AVAILABLE, address, name,
                AudioSystem.AUDIO_FORMAT_DEFAULT);
        mConnectedDevices.put(
                DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_OUT_HEARING_AID, address),
                new DeviceInfo(AudioSystem.DEVICE_OUT_HEARING_AID, name,
                        address, AudioSystem.AUDIO_FORMAT_DEFAULT));
        mDeviceBroker.postAccessoryPlugMediaUnmute(AudioSystem.DEVICE_OUT_HEARING_AID);
        mDeviceBroker.setDeviceVolume(
                mDeviceBroker.getStreamState(AudioSystem.STREAM_MUSIC),
                AudioSystem.DEVICE_OUT_HEARING_AID);
        setCurrentAudioRouteNameIfPossible(name);
    }

    @GuardedBy("mConnectedDevices")
    private void makeHearingAidDeviceUnavailable(String address) {
        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_HEARING_AID,
                AudioSystem.DEVICE_STATE_UNAVAILABLE, address, "",
                AudioSystem.AUDIO_FORMAT_DEFAULT);
        mConnectedDevices.remove(
                DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_OUT_HEARING_AID, address));
        // Remove Hearing Aid routes as well
        setCurrentAudioRouteNameIfPossible(null);
    }

    @GuardedBy("mConnectedDevices")
    private void setCurrentAudioRouteNameIfPossible(String name) {
        synchronized (mCurAudioRoutes) {
            if (TextUtils.equals(mCurAudioRoutes.bluetoothName, name)) {
                return;
            }
            if (name != null || !isCurrentDeviceConnected()) {
                mCurAudioRoutes.bluetoothName = name;
                mDeviceBroker.postReportNewRoutes();
            }
        }
    }

    @GuardedBy("mConnectedDevices")
    private boolean isCurrentDeviceConnected() {
        return mConnectedDevices.values().stream().anyMatch(deviceInfo ->
            TextUtils.equals(deviceInfo.mDeviceName, mCurAudioRoutes.bluetoothName));
    }

    // Devices which removal triggers intent ACTION_AUDIO_BECOMING_NOISY. The intent is only
    // sent if:
    // - none of these devices are connected anymore after one is disconnected AND
    // - the device being disconnected is actually used for music.
    // Access synchronized on mConnectedDevices
    private int mBecomingNoisyIntentDevices =
            AudioSystem.DEVICE_OUT_WIRED_HEADSET | AudioSystem.DEVICE_OUT_WIRED_HEADPHONE
                    | AudioSystem.DEVICE_OUT_ALL_A2DP | AudioSystem.DEVICE_OUT_HDMI
                    | AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET
                    | AudioSystem.DEVICE_OUT_DGTL_DOCK_HEADSET
                    | AudioSystem.DEVICE_OUT_ALL_USB | AudioSystem.DEVICE_OUT_LINE
                    | AudioSystem.DEVICE_OUT_HEARING_AID;

    // must be called before removing the device from mConnectedDevices
    // musicDevice argument is used when not AudioSystem.DEVICE_NONE instead of querying
    // from AudioSystem
    @GuardedBy("mConnectedDevices")
    private int checkSendBecomingNoisyIntentInt(int device,
            @AudioService.ConnectionState int state, int musicDevice) {
        if (state != AudioService.CONNECTION_STATE_DISCONNECTED) {
            return 0;
        }
        if ((device & mBecomingNoisyIntentDevices) == 0) {
            return 0;
        }
        int delay = 0;
        int devices = 0;
        for (int i = 0; i < mConnectedDevices.size(); i++) {
            int dev = mConnectedDevices.valueAt(i).mDeviceType;
            if (((dev & AudioSystem.DEVICE_BIT_IN) == 0)
                    && ((dev & mBecomingNoisyIntentDevices) != 0)) {
                devices |= dev;
            }
        }
        if (musicDevice == AudioSystem.DEVICE_NONE) {
            musicDevice = mDeviceBroker.getDeviceForStream(AudioSystem.STREAM_MUSIC);
        }
        // ignore condition on device being actually used for music when in communication
        // because music routing is altered in this case.
        // also checks whether media routing if affected by a dynamic policy
        if (((device == musicDevice) || mDeviceBroker.isInCommunication())
                && (device == devices) && !mDeviceBroker.hasMediaDynamicPolicy()) {
            mDeviceBroker.postBroadcastBecomingNoisy();
            delay = 1000;
        }

        return delay;
    }

    // Intent "extra" data keys.
    private static final String CONNECT_INTENT_KEY_PORT_NAME = "portName";
    private static final String CONNECT_INTENT_KEY_STATE = "state";
    private static final String CONNECT_INTENT_KEY_ADDRESS = "address";
    private static final String CONNECT_INTENT_KEY_HAS_PLAYBACK = "hasPlayback";
    private static final String CONNECT_INTENT_KEY_HAS_CAPTURE = "hasCapture";
    private static final String CONNECT_INTENT_KEY_HAS_MIDI = "hasMIDI";
    private static final String CONNECT_INTENT_KEY_DEVICE_CLASS = "class";

    private void sendDeviceConnectionIntent(int device, int state, String address,
                                            String deviceName) {
        if (AudioService.DEBUG_DEVICES) {
            Slog.i(TAG, "sendDeviceConnectionIntent(dev:0x" + Integer.toHexString(device)
                    + " state:0x" + Integer.toHexString(state) + " address:" + address
                    + " name:" + deviceName + ");");
        }
        Intent intent = new Intent();

        switch(device) {
            case AudioSystem.DEVICE_OUT_WIRED_HEADSET:
                intent.setAction(Intent.ACTION_HEADSET_PLUG);
                intent.putExtra("microphone", 1);
                break;
            case AudioSystem.DEVICE_OUT_WIRED_HEADPHONE:
            case AudioSystem.DEVICE_OUT_LINE:
                intent.setAction(Intent.ACTION_HEADSET_PLUG);
                intent.putExtra("microphone", 0);
                break;
            case AudioSystem.DEVICE_OUT_USB_HEADSET:
                intent.setAction(Intent.ACTION_HEADSET_PLUG);
                intent.putExtra("microphone",
                        AudioSystem.getDeviceConnectionState(AudioSystem.DEVICE_IN_USB_HEADSET, "")
                                == AudioSystem.DEVICE_STATE_AVAILABLE ? 1 : 0);
                break;
            case AudioSystem.DEVICE_IN_USB_HEADSET:
                if (AudioSystem.getDeviceConnectionState(AudioSystem.DEVICE_OUT_USB_HEADSET, "")
                        == AudioSystem.DEVICE_STATE_AVAILABLE) {
                    intent.setAction(Intent.ACTION_HEADSET_PLUG);
                    intent.putExtra("microphone", 1);
                } else {
                    // do not send ACTION_HEADSET_PLUG when only the input side is seen as changing
                    return;
                }
                break;
            case AudioSystem.DEVICE_OUT_HDMI:
            case AudioSystem.DEVICE_OUT_HDMI_ARC:
                configureHdmiPlugIntent(intent, state);
                break;
        }

        if (intent.getAction() == null) {
            return;
        }

        intent.putExtra(CONNECT_INTENT_KEY_STATE, state);
        intent.putExtra(CONNECT_INTENT_KEY_ADDRESS, address);
        intent.putExtra(CONNECT_INTENT_KEY_PORT_NAME, deviceName);

        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);

        final long ident = Binder.clearCallingIdentity();
        try {
            ActivityManager.broadcastStickyIntent(intent, UserHandle.USER_ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void updateAudioRoutes(int device, int state) {
        int connType = 0;

        switch (device) {
            case AudioSystem.DEVICE_OUT_WIRED_HEADSET:
                connType = AudioRoutesInfo.MAIN_HEADSET;
                break;
            case AudioSystem.DEVICE_OUT_WIRED_HEADPHONE:
            case AudioSystem.DEVICE_OUT_LINE:
                connType = AudioRoutesInfo.MAIN_HEADPHONES;
                break;
            case AudioSystem.DEVICE_OUT_HDMI:
            case AudioSystem.DEVICE_OUT_HDMI_ARC:
                connType = AudioRoutesInfo.MAIN_HDMI;
                break;
            case AudioSystem.DEVICE_OUT_USB_DEVICE:
            case AudioSystem.DEVICE_OUT_USB_HEADSET:
                connType = AudioRoutesInfo.MAIN_USB;
                break;
        }

        synchronized (mCurAudioRoutes) {
            if (connType == 0) {
                return;
            }
            int newConn = mCurAudioRoutes.mainType;
            if (state != 0) {
                newConn |= connType;
            } else {
                newConn &= ~connType;
            }
            if (newConn != mCurAudioRoutes.mainType) {
                mCurAudioRoutes.mainType = newConn;
                mDeviceBroker.postReportNewRoutes();
            }
        }
    }

    private void configureHdmiPlugIntent(Intent intent, @AudioService.ConnectionState int state) {
        intent.setAction(AudioManager.ACTION_HDMI_AUDIO_PLUG);
        intent.putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, state);
        if (state != AudioService.CONNECTION_STATE_CONNECTED) {
            return;
        }
        ArrayList<AudioPort> ports = new ArrayList<AudioPort>();
        int[] portGeneration = new int[1];
        int status = AudioSystem.listAudioPorts(ports, portGeneration);
        if (status != AudioManager.SUCCESS) {
            Log.e(TAG, "listAudioPorts error " + status + " in configureHdmiPlugIntent");
            return;
        }
        for (AudioPort port : ports) {
            if (!(port instanceof AudioDevicePort)) {
                continue;
            }
            final AudioDevicePort devicePort = (AudioDevicePort) port;
            if (devicePort.type() != AudioManager.DEVICE_OUT_HDMI
                    && devicePort.type() != AudioManager.DEVICE_OUT_HDMI_ARC) {
                continue;
            }
            // found an HDMI port: format the list of supported encodings
            int[] formats = AudioFormat.filterPublicFormats(devicePort.formats());
            if (formats.length > 0) {
                ArrayList<Integer> encodingList = new ArrayList(1);
                for (int format : formats) {
                    // a format in the list can be 0, skip it
                    if (format != AudioFormat.ENCODING_INVALID) {
                        encodingList.add(format);
                    }
                }
                final int[] encodingArray = encodingList.stream().mapToInt(i -> i).toArray();
                intent.putExtra(AudioManager.EXTRA_ENCODINGS, encodingArray);
            }
            // find the maximum supported number of channels
            int maxChannels = 0;
            for (int mask : devicePort.channelMasks()) {
                int channelCount = AudioFormat.channelCountFromOutChannelMask(mask);
                if (channelCount > maxChannels) {
                    maxChannels = channelCount;
                }
            }
            intent.putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, maxChannels);
        }
    }
}
