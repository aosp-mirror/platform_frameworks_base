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
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.media.AudioDeviceAttributes;
import android.media.AudioDevicePort;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPort;
import android.media.AudioRoutesInfo;
import android.media.AudioSystem;
import android.media.IAudioRoutesObserver;
import android.media.ICapturePresetDevicesRoleDispatcher;
import android.media.IStrategyPreferredDevicesDispatcher;
import android.media.MediaMetrics;
import android.os.Binder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Class to manage the inventory of all connected devices.
 * This class is thread-safe.
 * (non final for mocking/spying)
 */
public class AudioDeviceInventory {

    private static final String TAG = "AS.AudioDeviceInventory";

    // lock to synchronize all access to mConnectedDevices and mApmConnectedDevices
    private final Object mDevicesLock = new Object();

    //Audio Analytics ids.
    private static final String mMetricsId = "audio.device.";

    // List of connected devices
    // Key for map created from DeviceInfo.makeDeviceListKey()
    @GuardedBy("mDevicesLock")
    private final LinkedHashMap<String, DeviceInfo> mConnectedDevices = new LinkedHashMap<>() {
        @Override
        public DeviceInfo put(String key, DeviceInfo value) {
            final DeviceInfo result = super.put(key, value);
            record("put", true /* connected */, key, value);
            return result;
        }

        @Override
        public DeviceInfo putIfAbsent(String key, DeviceInfo value) {
            final DeviceInfo result = super.putIfAbsent(key, value);
            if (result == null) {
                record("putIfAbsent", true /* connected */, key, value);
            }
            return result;
        }

        @Override
        public DeviceInfo remove(Object key) {
            final DeviceInfo result = super.remove(key);
            if (result != null) {
                record("remove", false /* connected */, (String) key, result);
            }
            return result;
        }

        @Override
        public boolean remove(Object key, Object value) {
            final boolean result = super.remove(key, value);
            if (result) {
                record("remove", false /* connected */, (String) key, (DeviceInfo) value);
            }
            return result;
        }

        // Not overridden
        // clear
        // compute
        // computeIfAbsent
        // computeIfPresent
        // merge
        // putAll
        // replace
        // replaceAll
        private void record(String event, boolean connected, String key, DeviceInfo value) {
            // DeviceInfo - int mDeviceType;
            // DeviceInfo - int mDeviceCodecFormat;
            new MediaMetrics.Item(MediaMetrics.Name.AUDIO_DEVICE
                    + MediaMetrics.SEPARATOR + AudioSystem.getDeviceName(value.mDeviceType))
                    .set(MediaMetrics.Property.ADDRESS, value.mDeviceAddress)
                    .set(MediaMetrics.Property.EVENT, event)
                    .set(MediaMetrics.Property.NAME, value.mDeviceName)
                    .set(MediaMetrics.Property.STATE, connected
                            ? MediaMetrics.Value.CONNECTED : MediaMetrics.Value.DISCONNECTED)
                    .record();
        }
    };

    // List of devices actually connected to AudioPolicy (through AudioSystem), only one
    // by device type, which is used as the key, value is the DeviceInfo generated key.
    // For the moment only for A2DP sink devices.
    // TODO: extend to all device types
    @GuardedBy("mDevicesLock")
    private final ArrayMap<Integer, String> mApmConnectedDevices = new ArrayMap<>();

    // List of preferred devices for strategies
    private final ArrayMap<Integer, List<AudioDeviceAttributes>> mPreferredDevices =
            new ArrayMap<>();

    // List of preferred devices of capture preset
    private final ArrayMap<Integer, List<AudioDeviceAttributes>> mPreferredDevicesForCapturePreset =
            new ArrayMap<>();

    // the wrapper for AudioSystem static methods, allows us to spy AudioSystem
    private final @NonNull AudioSystemAdapter mAudioSystem;

    private @NonNull AudioDeviceBroker mDeviceBroker;

    // Monitoring of audio routes.  Protected by mAudioRoutes.
    final AudioRoutesInfo mCurAudioRoutes = new AudioRoutesInfo();
    final RemoteCallbackList<IAudioRoutesObserver> mRoutesObservers =
            new RemoteCallbackList<IAudioRoutesObserver>();

    // Monitoring of strategy-preferred device
    final RemoteCallbackList<IStrategyPreferredDevicesDispatcher> mPrefDevDispatchers =
            new RemoteCallbackList<IStrategyPreferredDevicesDispatcher>();

    // Monitoring of devices for role and capture preset
    final RemoteCallbackList<ICapturePresetDevicesRoleDispatcher> mDevRoleCapturePresetDispatchers =
            new RemoteCallbackList<ICapturePresetDevicesRoleDispatcher>();

    /*package*/ AudioDeviceInventory(@NonNull AudioDeviceBroker broker) {
        mDeviceBroker = broker;
        mAudioSystem = AudioSystemAdapter.getDefaultAdapter();
    }

    //-----------------------------------------------------------
    /** for mocking only, allows to inject AudioSystem adapter */
    /*package*/ AudioDeviceInventory(@NonNull AudioSystemAdapter audioSystem) {
        mDeviceBroker = null;
        mAudioSystem = audioSystem;
    }

    /*package*/ void setDeviceBroker(@NonNull AudioDeviceBroker broker) {
        mDeviceBroker = broker;
    }

    //------------------------------------------------------------
    /**
     * Class to store info about connected devices.
     * Use makeDeviceListKey() to make a unique key for this list.
     */
    private static class DeviceInfo {
        final int mDeviceType;
        final @NonNull String mDeviceName;
        final @NonNull String mDeviceAddress;
        int mDeviceCodecFormat;
        final UUID mSensorUuid;

        DeviceInfo(int deviceType, String deviceName, String deviceAddress,
                   int deviceCodecFormat, UUID sensorUuid) {
            mDeviceType = deviceType;
            mDeviceName = deviceName == null ? "" : deviceName;
            mDeviceAddress = deviceAddress == null ? "" : deviceAddress;
            mDeviceCodecFormat = deviceCodecFormat;
            mSensorUuid = sensorUuid;
        }

        DeviceInfo(int deviceType, String deviceName, String deviceAddress,
                   int deviceCodecFormat) {
            this(deviceType, deviceName, deviceAddress, deviceCodecFormat, null);
        }

        @Override
        public String toString() {
            return "[DeviceInfo: type:0x" + Integer.toHexString(mDeviceType)
                    + " (" + AudioSystem.getDeviceName(mDeviceType)
                    + ") name:" + mDeviceName
                    + " addr:" + mDeviceAddress
                    + " codec: " + Integer.toHexString(mDeviceCodecFormat)
                    + " sensorUuid: " + Objects.toString(mSensorUuid) + "]";
        }

        @NonNull String getKey() {
            return makeDeviceListKey(mDeviceType, mDeviceAddress);
        }

        /**
         * Generate a unique key for the mConnectedDevices List by composing the device "type"
         * and the "address" associated with a specific instance of that device type
         */
        @NonNull private static String makeDeviceListKey(int device, String deviceAddress) {
            return "0x" + Integer.toHexString(device) + ":" + deviceAddress;
        }
    }

    /**
     * A class just for packaging up a set of connection parameters.
     */
    /*package*/ class WiredDeviceConnectionState {
        public final AudioDeviceAttributes mAttributes;
        public final @AudioService.ConnectionState int mState;
        public final String mCaller;
        public boolean mForTest = false;

        /*package*/ WiredDeviceConnectionState(AudioDeviceAttributes attributes,
                @AudioService.ConnectionState int state, String caller) {
            mAttributes = attributes;
            mState = state;
            mCaller = caller;
        }
    }

    //------------------------------------------------------------
    /*package*/ void dump(PrintWriter pw, String prefix) {
        pw.println("\n" + prefix + "BECOMING_NOISY_INTENT_DEVICES_SET=");
        BECOMING_NOISY_INTENT_DEVICES_SET.forEach(device -> {
            pw.print(" 0x" +  Integer.toHexString(device)); });
        pw.println("\n" + prefix + "Preferred devices for strategy:");
        mPreferredDevices.forEach((strategy, device) -> {
            pw.println("  " + prefix + "strategy:" + strategy + " device:" + device); });
        pw.println("\n" + prefix + "Connected devices:");
        mConnectedDevices.forEach((key, deviceInfo) -> {
            pw.println("  " + prefix + deviceInfo.toString()); });
        pw.println("\n" + prefix + "APM Connected device (A2DP sink only):");
        mApmConnectedDevices.forEach((keyType, valueAddress) -> {
            pw.println("  " + prefix + " type:0x" + Integer.toHexString(keyType)
                    + " (" + AudioSystem.getDeviceName(keyType)
                    + ") addr:" + valueAddress); });
        mPreferredDevicesForCapturePreset.forEach((capturePreset, devices) -> {
            pw.println("  " + prefix + "capturePreset:" + capturePreset
                    + " devices:" + devices); });
    }

    //------------------------------------------------------------
    // Message handling from AudioDeviceBroker

    /**
     * Restore previously connected devices. Use in case of audio server crash
     * (see AudioService.onAudioServerDied() method)
     */
    // Always executed on AudioDeviceBroker message queue
    /*package*/ void onRestoreDevices() {
        synchronized (mDevicesLock) {
            //TODO iterate on mApmConnectedDevices instead once it handles all device types
            for (DeviceInfo di : mConnectedDevices.values()) {
                mAudioSystem.setDeviceConnectionState(new AudioDeviceAttributes(di.mDeviceType,
                        di.mDeviceAddress,
                        di.mDeviceName),
                        AudioSystem.DEVICE_STATE_AVAILABLE,
                        di.mDeviceCodecFormat);
            }
        }
        synchronized (mPreferredDevices) {
            mPreferredDevices.forEach((strategy, devices) -> {
                mAudioSystem.setDevicesRoleForStrategy(
                        strategy, AudioSystem.DEVICE_ROLE_PREFERRED, devices); });
        }
        synchronized (mPreferredDevicesForCapturePreset) {
            // TODO: call audiosystem to restore
        }
    }

    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    void onSetBtActiveDevice(@NonNull AudioDeviceBroker.BtDeviceInfo btInfo, int streamType) {
        if (AudioService.DEBUG_DEVICES) {
            Log.d(TAG, "onSetBtActiveDevice"
                    + " btDevice=" + btInfo.mDevice
                    + " profile=" + BluetoothProfile.getProfileName(btInfo.mProfile)
                    + " state=" + BluetoothProfile.getConnectionStateName(btInfo.mState));
        }
        String address = btInfo.mDevice.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = "";
        }

        AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent("BT connected:"
                        + " addr=" + address
                        + " profile=" + btInfo.mProfile
                        + " state=" + btInfo.mState
                        + " codec=" + AudioSystem.audioFormatToString(btInfo.mCodec)));

        new MediaMetrics.Item(mMetricsId + "onSetBtActiveDevice")
                .set(MediaMetrics.Property.STATUS, btInfo.mProfile)
                .set(MediaMetrics.Property.DEVICE,
                        AudioSystem.getDeviceName(btInfo.mAudioSystemDevice))
                .set(MediaMetrics.Property.ADDRESS, address)
                .set(MediaMetrics.Property.ENCODING,
                        AudioSystem.audioFormatToString(btInfo.mCodec))
                .set(MediaMetrics.Property.EVENT, "onSetBtActiveDevice")
                .set(MediaMetrics.Property.STREAM_TYPE,
                        AudioSystem.streamToString(streamType))
                .set(MediaMetrics.Property.STATE,
                        btInfo.mState == BluetoothProfile.STATE_CONNECTED
                        ? MediaMetrics.Value.CONNECTED : MediaMetrics.Value.DISCONNECTED)
                .record();

        synchronized (mDevicesLock) {
            final String key = DeviceInfo.makeDeviceListKey(btInfo.mAudioSystemDevice, address);
            final DeviceInfo di = mConnectedDevices.get(key);

            final boolean isConnected = di != null;

            final boolean switchToUnavailable = isConnected
                    && btInfo.mState != BluetoothProfile.STATE_CONNECTED;
            final boolean switchToAvailable = !isConnected
                    && btInfo.mState == BluetoothProfile.STATE_CONNECTED;

            switch (btInfo.mProfile) {
                case BluetoothProfile.A2DP_SINK:
                    if (switchToUnavailable) {
                        makeA2dpSrcUnavailable(address);
                    } else if (switchToAvailable) {
                        makeA2dpSrcAvailable(address);
                    }
                    break;
                case BluetoothProfile.A2DP:
                    if (switchToUnavailable) {
                        makeA2dpDeviceUnavailableNow(address, di.mDeviceCodecFormat);
                    } else if (switchToAvailable) {
                        // device is not already connected
                        if (btInfo.mVolume != -1) {
                            mDeviceBroker.postSetVolumeIndexOnDevice(AudioSystem.STREAM_MUSIC,
                                    // convert index to internal representation in VolumeStreamState
                                    btInfo.mVolume * 10, btInfo.mAudioSystemDevice,
                                    "onSetBtActiveDevice");
                        }
                        makeA2dpDeviceAvailable(address, BtHelper.getName(btInfo.mDevice),
                                "onSetBtActiveDevice", btInfo.mCodec);
                    }
                    break;
                case BluetoothProfile.HEARING_AID:
                    if (switchToUnavailable) {
                        makeHearingAidDeviceUnavailable(address);
                    } else if (switchToAvailable) {
                        makeHearingAidDeviceAvailable(address, BtHelper.getName(btInfo.mDevice),
                                streamType, "onSetBtActiveDevice");
                    }
                    break;
                case BluetoothProfile.LE_AUDIO:
                case BluetoothProfile.LE_AUDIO_BROADCAST:
                    if (switchToUnavailable) {
                        makeLeAudioDeviceUnavailable(address, btInfo.mAudioSystemDevice);
                    } else if (switchToAvailable) {
                        makeLeAudioDeviceAvailable(address, BtHelper.getName(btInfo.mDevice),
                                streamType, btInfo.mAudioSystemDevice, "onSetBtActiveDevice");
                    }
                    break;
                default: throw new IllegalArgumentException("Invalid profile "
                                 + BluetoothProfile.getProfileName(btInfo.mProfile));
            }
        }
    }


    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
        /*package*/ void onBluetoothA2dpDeviceConfigChange(
            @NonNull BtHelper.BluetoothA2dpDeviceInfo btInfo, int event) {
        MediaMetrics.Item mmi = new MediaMetrics.Item(mMetricsId
                + "onBluetoothA2dpDeviceConfigChange")
                .set(MediaMetrics.Property.EVENT, BtHelper.a2dpDeviceEventToString(event));

        final BluetoothDevice btDevice = btInfo.getBtDevice();
        if (btDevice == null) {
            mmi.set(MediaMetrics.Property.EARLY_RETURN, "btDevice null").record();
            return;
        }
        if (AudioService.DEBUG_DEVICES) {
            Log.d(TAG, "onBluetoothA2dpDeviceConfigChange btDevice=" + btDevice);
        }
        int a2dpVolume = btInfo.getVolume();
        @AudioSystem.AudioFormatNativeEnumForBtCodec final int a2dpCodec = btInfo.getCodec();

        String address = btDevice.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = "";
        }
        AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(
                "onBluetoothA2dpDeviceConfigChange addr=" + address
                    + " event=" + BtHelper.a2dpDeviceEventToString(event)));

        synchronized (mDevicesLock) {
            if (mDeviceBroker.hasScheduledA2dpConnection(btDevice)) {
                AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(
                        "A2dp config change ignored (scheduled connection change)")
                        .printLog(TAG));
                mmi.set(MediaMetrics.Property.EARLY_RETURN, "A2dp config change ignored")
                        .record();
                return;
            }
            final String key = DeviceInfo.makeDeviceListKey(
                    AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, address);
            final DeviceInfo di = mConnectedDevices.get(key);
            if (di == null) {
                Log.e(TAG, "invalid null DeviceInfo in onBluetoothA2dpDeviceConfigChange");
                mmi.set(MediaMetrics.Property.EARLY_RETURN, "null DeviceInfo").record();
                return;
            }

            mmi.set(MediaMetrics.Property.ADDRESS, address)
                    .set(MediaMetrics.Property.ENCODING,
                            AudioSystem.audioFormatToString(a2dpCodec))
                    .set(MediaMetrics.Property.INDEX, a2dpVolume)
                    .set(MediaMetrics.Property.NAME, di.mDeviceName);

            if (event == BtHelper.EVENT_ACTIVE_DEVICE_CHANGE) {
                // Device is connected
                if (a2dpVolume != -1) {
                    mDeviceBroker.postSetVolumeIndexOnDevice(AudioSystem.STREAM_MUSIC,
                            // convert index to internal representation in VolumeStreamState
                            a2dpVolume * 10,
                            AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                            "onBluetoothA2dpDeviceConfigChange");
                }
            } else if (event == BtHelper.EVENT_DEVICE_CONFIG_CHANGE) {
                if (di.mDeviceCodecFormat != a2dpCodec) {
                    di.mDeviceCodecFormat = a2dpCodec;
                    mConnectedDevices.replace(key, di);
                }
            }
            final int res = mAudioSystem.handleDeviceConfigChange(
                    AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, address,
                    BtHelper.getName(btDevice), a2dpCodec);

            if (res != AudioSystem.AUDIO_STATUS_OK) {
                AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(
                        "APM handleDeviceConfigChange failed for A2DP device addr=" + address
                                + " codec=" + AudioSystem.audioFormatToString(a2dpCodec))
                        .printLog(TAG));

                int musicDevice = mDeviceBroker.getDeviceForStream(AudioSystem.STREAM_MUSIC);
                // force A2DP device disconnection in case of error so that AudioService state is
                // consistent with audio policy manager state
                setBluetoothActiveDevice(new AudioDeviceBroker.BtDeviceInfo(btDevice,
                                BluetoothProfile.A2DP, BluetoothProfile.STATE_DISCONNECTED,
                                musicDevice, AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP));
            } else {
                AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(
                        "APM handleDeviceConfigChange success for A2DP device addr=" + address
                                + " codec=" + AudioSystem.audioFormatToString(a2dpCodec))
                        .printLog(TAG));
            }
        }
        mmi.record();
    }

    /*package*/ void onMakeA2dpDeviceUnavailableNow(String address, int a2dpCodec) {
        synchronized (mDevicesLock) {
            makeA2dpDeviceUnavailableNow(address, a2dpCodec);
        }
    }

    /*package*/ void onReportNewRoutes() {
        int n = mRoutesObservers.beginBroadcast();
        if (n > 0) {
            new MediaMetrics.Item(mMetricsId + "onReportNewRoutes")
                    .set(MediaMetrics.Property.OBSERVERS, n)
                    .record();
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
        mDeviceBroker.postObserveDevicesForAllStreams();
    }

    /* package */ static final Set<Integer> DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG_SET;
    static {
        DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG_SET = new HashSet<>();
        DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG_SET.add(AudioSystem.DEVICE_OUT_WIRED_HEADSET);
        DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG_SET.add(AudioSystem.DEVICE_OUT_WIRED_HEADPHONE);
        DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG_SET.add(AudioSystem.DEVICE_OUT_LINE);
        DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG_SET.addAll(AudioSystem.DEVICE_OUT_ALL_USB_SET);
    }

    /*package*/ void onSetWiredDeviceConnectionState(
                            AudioDeviceInventory.WiredDeviceConnectionState wdcs) {
        int type = wdcs.mAttributes.getInternalType();

        AudioService.sDeviceLogger.log(new AudioServiceEvents.WiredDevConnectEvent(wdcs));

        MediaMetrics.Item mmi = new MediaMetrics.Item(mMetricsId
                + "onSetWiredDeviceConnectionState")
                .set(MediaMetrics.Property.ADDRESS, wdcs.mAttributes.getAddress())
                .set(MediaMetrics.Property.DEVICE,
                        AudioSystem.getDeviceName(type))
                .set(MediaMetrics.Property.STATE,
                        wdcs.mState == AudioService.CONNECTION_STATE_DISCONNECTED
                                ? MediaMetrics.Value.DISCONNECTED : MediaMetrics.Value.CONNECTED);
        synchronized (mDevicesLock) {
            if ((wdcs.mState == AudioService.CONNECTION_STATE_DISCONNECTED)
                    && DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG_SET.contains(type)) {
                mDeviceBroker.setBluetoothA2dpOnInt(true, false /*fromA2dp*/,
                        "onSetWiredDeviceConnectionState state DISCONNECTED");
            }

            if (!handleDeviceConnection(wdcs.mAttributes,
                    wdcs.mState == AudioService.CONNECTION_STATE_CONNECTED, wdcs.mForTest)) {
                // change of connection state failed, bailout
                mmi.set(MediaMetrics.Property.EARLY_RETURN, "change of connection state failed")
                        .record();
                return;
            }
            if (wdcs.mState != AudioService.CONNECTION_STATE_DISCONNECTED) {
                if (DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG_SET.contains(type)) {
                    mDeviceBroker.setBluetoothA2dpOnInt(false, false /*fromA2dp*/,
                            "onSetWiredDeviceConnectionState state not DISCONNECTED");
                }
                mDeviceBroker.checkMusicActive(type, wdcs.mCaller);
            }
            if (type == AudioSystem.DEVICE_OUT_HDMI) {
                mDeviceBroker.checkVolumeCecOnHdmiConnection(wdcs.mState, wdcs.mCaller);
            }
            sendDeviceConnectionIntent(type, wdcs.mState,
                    wdcs.mAttributes.getAddress(), wdcs.mAttributes.getName());
            updateAudioRoutes(type, wdcs.mState);
        }
        mmi.record();
    }

    /*package*/ void onToggleHdmi() {
        MediaMetrics.Item mmi = new MediaMetrics.Item(mMetricsId + "onToggleHdmi")
                .set(MediaMetrics.Property.DEVICE,
                        AudioSystem.getDeviceName(AudioSystem.DEVICE_OUT_HDMI));
        synchronized (mDevicesLock) {
            // Is HDMI connected?
            final String key = DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_OUT_HDMI, "");
            final DeviceInfo di = mConnectedDevices.get(key);
            if (di == null) {
                Log.e(TAG, "invalid null DeviceInfo in onToggleHdmi");
                mmi.set(MediaMetrics.Property.EARLY_RETURN, "invalid null DeviceInfo").record();
                return;
            }
            // Toggle HDMI to retrigger broadcast with proper formats.
            setWiredDeviceConnectionState(
                    new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_HDMI, ""),
                    AudioSystem.DEVICE_STATE_UNAVAILABLE, "android"); // disconnect
            setWiredDeviceConnectionState(
                    new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_HDMI, ""),
                    AudioSystem.DEVICE_STATE_AVAILABLE, "android"); // reconnect
        }
        mmi.record();
    }

    /*package*/ void onSaveSetPreferredDevices(int strategy,
                                               @NonNull List<AudioDeviceAttributes> devices) {
        mPreferredDevices.put(strategy, devices);
        dispatchPreferredDevice(strategy, devices);
    }

    /*package*/ void onSaveRemovePreferredDevices(int strategy) {
        mPreferredDevices.remove(strategy);
        dispatchPreferredDevice(strategy, new ArrayList<AudioDeviceAttributes>());
    }

    /*package*/ void onSaveSetPreferredDevicesForCapturePreset(
            int capturePreset, @NonNull List<AudioDeviceAttributes> devices) {
        mPreferredDevicesForCapturePreset.put(capturePreset, devices);
        dispatchDevicesRoleForCapturePreset(
                capturePreset, AudioSystem.DEVICE_ROLE_PREFERRED, devices);
    }

    /*package*/ void onSaveClearPreferredDevicesForCapturePreset(int capturePreset) {
        mPreferredDevicesForCapturePreset.remove(capturePreset);
        dispatchDevicesRoleForCapturePreset(
                capturePreset, AudioSystem.DEVICE_ROLE_PREFERRED,
                new ArrayList<AudioDeviceAttributes>());
    }

    //------------------------------------------------------------
    // preferred device(s)

    /*package*/ int setPreferredDevicesForStrategySync(int strategy,
            @NonNull List<AudioDeviceAttributes> devices) {
        final long identity = Binder.clearCallingIdentity();

        AudioService.sDeviceLogger.log((new AudioEventLogger.StringEvent(
                                "setPreferredDevicesForStrategySync, strategy: " + strategy
                                + " devices: " + devices)).printLog(TAG));
        final int status = mAudioSystem.setDevicesRoleForStrategy(
                strategy, AudioSystem.DEVICE_ROLE_PREFERRED, devices);
        Binder.restoreCallingIdentity(identity);

        if (status == AudioSystem.SUCCESS) {
            mDeviceBroker.postSaveSetPreferredDevicesForStrategy(strategy, devices);
        }
        return status;
    }

    /*package*/ int removePreferredDevicesForStrategySync(int strategy) {
        final long identity = Binder.clearCallingIdentity();

        AudioService.sDeviceLogger.log((new AudioEventLogger.StringEvent(
                "removePreferredDevicesForStrategySync, strategy: "
                + strategy)).printLog(TAG));

        final int status = mAudioSystem.removeDevicesRoleForStrategy(
                strategy, AudioSystem.DEVICE_ROLE_PREFERRED);
        Binder.restoreCallingIdentity(identity);

        if (status == AudioSystem.SUCCESS) {
            mDeviceBroker.postSaveRemovePreferredDevicesForStrategy(strategy);
        }
        return status;
    }

    /*package*/ void registerStrategyPreferredDevicesDispatcher(
            @NonNull IStrategyPreferredDevicesDispatcher dispatcher) {
        mPrefDevDispatchers.register(dispatcher);
    }

    /*package*/ void unregisterStrategyPreferredDevicesDispatcher(
            @NonNull IStrategyPreferredDevicesDispatcher dispatcher) {
        mPrefDevDispatchers.unregister(dispatcher);
    }

    /*package*/ int setPreferredDevicesForCapturePresetSync(
            int capturePreset, @NonNull List<AudioDeviceAttributes> devices) {
        final long identity = Binder.clearCallingIdentity();
        final int status = mAudioSystem.setDevicesRoleForCapturePreset(
                capturePreset, AudioSystem.DEVICE_ROLE_PREFERRED, devices);
        Binder.restoreCallingIdentity(identity);

        if (status == AudioSystem.SUCCESS) {
            mDeviceBroker.postSaveSetPreferredDevicesForCapturePreset(capturePreset, devices);
        }
        return status;
    }

    /*package*/ int clearPreferredDevicesForCapturePresetSync(int capturePreset) {
        final long identity = Binder.clearCallingIdentity();
        final int status = mAudioSystem.clearDevicesRoleForCapturePreset(
                capturePreset, AudioSystem.DEVICE_ROLE_PREFERRED);
        Binder.restoreCallingIdentity(identity);

        if (status == AudioSystem.SUCCESS) {
            mDeviceBroker.postSaveClearPreferredDevicesForCapturePreset(capturePreset);
        }
        return status;
    }

    /*package*/ void registerCapturePresetDevicesRoleDispatcher(
            @NonNull ICapturePresetDevicesRoleDispatcher dispatcher) {
        mDevRoleCapturePresetDispatchers.register(dispatcher);
    }

    /*package*/ void unregisterCapturePresetDevicesRoleDispatcher(
            @NonNull ICapturePresetDevicesRoleDispatcher dispatcher) {
        mDevRoleCapturePresetDispatchers.unregister(dispatcher);
    }

    //-----------------------------------------------------------------------

    /**
     * Check if a device is in the list of connected devices
     * @param device the device whose connection state is queried
     * @return true if connected
     */
    // called with AudioDeviceBroker.mDeviceStateLock lock held
    public boolean isDeviceConnected(@NonNull AudioDeviceAttributes device) {
        final String key = DeviceInfo.makeDeviceListKey(device.getInternalType(),
                device.getAddress());
        synchronized (mDevicesLock) {
            return (mConnectedDevices.get(key) != null);
        }
    }

    /**
     * Implements the communication with AudioSystem to (dis)connect a device in the native layers
     * @param attributes the attributes of the device
     * @param connect true if connection
     * @param isForTesting if true, not calling AudioSystem for the connection as this is
     *                    just for testing
     * @return false if an error was reported by AudioSystem
     */
    /*package*/ boolean handleDeviceConnection(AudioDeviceAttributes attributes, boolean connect,
            boolean isForTesting) {
        int device = attributes.getInternalType();
        String address = attributes.getAddress();
        String deviceName = attributes.getName();
        if (AudioService.DEBUG_DEVICES) {
            Slog.i(TAG, "handleDeviceConnection(" + connect + " dev:"
                    + Integer.toHexString(device) + " address:" + address
                    + " name:" + deviceName + ")");
        }
        MediaMetrics.Item mmi = new MediaMetrics.Item(mMetricsId + "handleDeviceConnection")
                .set(MediaMetrics.Property.ADDRESS, address)
                .set(MediaMetrics.Property.DEVICE, AudioSystem.getDeviceName(device))
                .set(MediaMetrics.Property.MODE, connect
                        ? MediaMetrics.Value.CONNECT : MediaMetrics.Value.DISCONNECT)
                .set(MediaMetrics.Property.NAME, deviceName);
        synchronized (mDevicesLock) {
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
                final int res;
                if (isForTesting) {
                    res = AudioSystem.AUDIO_STATUS_OK;
                } else {
                    res = mAudioSystem.setDeviceConnectionState(attributes,
                            AudioSystem.DEVICE_STATE_AVAILABLE, AudioSystem.AUDIO_FORMAT_DEFAULT);
                }
                if (res != AudioSystem.AUDIO_STATUS_OK) {
                    final String reason = "not connecting device 0x" + Integer.toHexString(device)
                            + " due to command error " + res;
                    Slog.e(TAG, reason);
                    mmi.set(MediaMetrics.Property.EARLY_RETURN, reason)
                            .set(MediaMetrics.Property.STATE, MediaMetrics.Value.DISCONNECTED)
                            .record();
                    return false;
                }
                mConnectedDevices.put(deviceKey, new DeviceInfo(
                        device, deviceName, address, AudioSystem.AUDIO_FORMAT_DEFAULT));
                mDeviceBroker.postAccessoryPlugMediaUnmute(device);
                mmi.set(MediaMetrics.Property.STATE, MediaMetrics.Value.CONNECTED).record();
                return true;
            } else if (!connect && isConnected) {
                mAudioSystem.setDeviceConnectionState(attributes,
                        AudioSystem.DEVICE_STATE_UNAVAILABLE, AudioSystem.AUDIO_FORMAT_DEFAULT);
                // always remove even if disconnection failed
                mConnectedDevices.remove(deviceKey);
                mmi.set(MediaMetrics.Property.STATE, MediaMetrics.Value.CONNECTED).record();
                return true;
            }
            Log.w(TAG, "handleDeviceConnection() failed, deviceKey=" + deviceKey
                    + ", deviceSpec=" + di + ", connect=" + connect);
        }
        mmi.set(MediaMetrics.Property.STATE, MediaMetrics.Value.DISCONNECTED).record();
        return false;
    }


    private void disconnectA2dp() {
        synchronized (mDevicesLock) {
            final ArraySet<String> toRemove = new ArraySet<>();
            // Disconnect ALL DEVICE_OUT_BLUETOOTH_A2DP devices
            mConnectedDevices.values().forEach(deviceInfo -> {
                if (deviceInfo.mDeviceType == AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP) {
                    toRemove.add(deviceInfo.mDeviceAddress);
                }
            });
            new MediaMetrics.Item(mMetricsId + "disconnectA2dp")
                    .record();
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

    private void disconnectA2dpSink() {
        synchronized (mDevicesLock) {
            final ArraySet<String> toRemove = new ArraySet<>();
            // Disconnect ALL DEVICE_IN_BLUETOOTH_A2DP devices
            mConnectedDevices.values().forEach(deviceInfo -> {
                if (deviceInfo.mDeviceType == AudioSystem.DEVICE_IN_BLUETOOTH_A2DP) {
                    toRemove.add(deviceInfo.mDeviceAddress);
                }
            });
            new MediaMetrics.Item(mMetricsId + "disconnectA2dpSink")
                    .record();
            toRemove.stream().forEach(deviceAddress -> makeA2dpSrcUnavailable(deviceAddress));
        }
    }

    private void disconnectHearingAid() {
        synchronized (mDevicesLock) {
            final ArraySet<String> toRemove = new ArraySet<>();
            // Disconnect ALL DEVICE_OUT_HEARING_AID devices
            mConnectedDevices.values().forEach(deviceInfo -> {
                if (deviceInfo.mDeviceType == AudioSystem.DEVICE_OUT_HEARING_AID) {
                    toRemove.add(deviceInfo.mDeviceAddress);
                }
            });
            new MediaMetrics.Item(mMetricsId + "disconnectHearingAid")
                    .record();
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

    /*package*/ synchronized void onBtProfileDisconnected(int profile) {
        switch (profile) {
            case BluetoothProfile.A2DP:
                disconnectA2dp();
                break;
            case BluetoothProfile.A2DP_SINK:
                disconnectA2dpSink();
                break;
            case BluetoothProfile.HEARING_AID:
                disconnectHearingAid();
                break;
            case BluetoothProfile.LE_AUDIO:
                disconnectLeAudioUnicast();
                break;
            case BluetoothProfile.LE_AUDIO_BROADCAST:
                disconnectLeAudioBroadcast();
                break;
            default:
                // Not a valid profile to disconnect
                Log.e(TAG, "onBtProfileDisconnected: Not a valid profile to disconnect "
                        + BluetoothProfile.getProfileName(profile));
                break;
        }
    }

     /*package*/ void disconnectLeAudio(int device) {
        if (device != AudioSystem.DEVICE_OUT_BLE_HEADSET
                && device != AudioSystem.DEVICE_OUT_BLE_BROADCAST) {
            Log.e(TAG, "disconnectLeAudio: Can't disconnect not LE Audio device " + device);
            return;
        }

        synchronized (mDevicesLock) {
            final ArraySet<String> toRemove = new ArraySet<>();
            // Disconnect ALL DEVICE_OUT_BLE_HEADSET or DEVICE_OUT_BLE_BROADCAST devices
            mConnectedDevices.values().forEach(deviceInfo -> {
                if (deviceInfo.mDeviceType == device) {
                    toRemove.add(deviceInfo.mDeviceAddress);
                }
            });
            new MediaMetrics.Item(mMetricsId + "disconnectLeAudio")
                    .record();
            if (toRemove.size() > 0) {
                final int delay = checkSendBecomingNoisyIntentInt(device, 0,
                        AudioSystem.DEVICE_NONE);
                toRemove.stream().forEach(deviceAddress ->
                        makeLeAudioDeviceUnavailable(deviceAddress, device)
                );
            }
        }
    }

    /*package*/ void disconnectLeAudioUnicast() {
        disconnectLeAudio(AudioSystem.DEVICE_OUT_BLE_HEADSET);
    }

    /*package*/ void disconnectLeAudioBroadcast() {
        disconnectLeAudio(AudioSystem.DEVICE_OUT_BLE_BROADCAST);
    }

    // must be called before removing the device from mConnectedDevices
    // musicDevice argument is used when not AudioSystem.DEVICE_NONE instead of querying
    // from AudioSystem
    /*package*/ int checkSendBecomingNoisyIntent(int device,
            @AudioService.ConnectionState int state, int musicDevice) {
        synchronized (mDevicesLock) {
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

    // only public for mocking/spying
    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    @VisibleForTesting
    public int setBluetoothActiveDevice(@NonNull AudioDeviceBroker.BtDeviceInfo info) {
        int delay;
        synchronized (mDevicesLock) {
            if (!info.mSupprNoisy
                    && (((info.mProfile == BluetoothProfile.LE_AUDIO
                        || info.mProfile == BluetoothProfile.LE_AUDIO_BROADCAST)
                        && info.mIsLeOutput)
                        || info.mProfile == BluetoothProfile.HEARING_AID
                        || info.mProfile == BluetoothProfile.A2DP)) {
                @AudioService.ConnectionState int asState =
                        (info.mState == BluetoothProfile.STATE_CONNECTED)
                                ? AudioService.CONNECTION_STATE_CONNECTED
                                : AudioService.CONNECTION_STATE_DISCONNECTED;
                delay = checkSendBecomingNoisyIntentInt(info.mAudioSystemDevice, asState,
                        info.mMusicDevice);
            } else {
                delay = 0;
            }

            if (AudioService.DEBUG_DEVICES) {
                Log.i(TAG, "setBluetoothActiveDevice device: " + info.mDevice
                        + " profile: " + BluetoothProfile.getProfileName(info.mProfile)
                        + " state: " + BluetoothProfile.getConnectionStateName(info.mState)
                        + " delay(ms): " + delay
                        + " codec:" + Integer.toHexString(info.mCodec)
                        + " suppressNoisyIntent: " + info.mSupprNoisy);
            }
            mDeviceBroker.postBluetoothActiveDevice(info, delay);
            if (info.mProfile == BluetoothProfile.HEARING_AID
                    && info.mState == BluetoothProfile.STATE_CONNECTED) {
                mDeviceBroker.setForceUse_Async(AudioSystem.FOR_MEDIA, AudioSystem.FORCE_NONE,
                                "HEARING_AID set to CONNECTED");
            }
        }
        return delay;
    }

    /*package*/ int setWiredDeviceConnectionState(AudioDeviceAttributes attributes,
            @AudioService.ConnectionState int state, String caller) {
        synchronized (mDevicesLock) {
            int delay = checkSendBecomingNoisyIntentInt(
                    attributes.getInternalType(), state, AudioSystem.DEVICE_NONE);
            mDeviceBroker.postSetWiredDeviceConnectionState(
                    new WiredDeviceConnectionState(attributes, state, caller), delay);
            return delay;
        }
    }

    /*package*/ void setTestDeviceConnectionState(@NonNull AudioDeviceAttributes device,
            @AudioService.ConnectionState int state) {
        final WiredDeviceConnectionState connection = new WiredDeviceConnectionState(
                device, state, "com.android.server.audio");
        connection.mForTest = true;
        onSetWiredDeviceConnectionState(connection);
    }

    //-------------------------------------------------------------------
    // Internal utilities

    @GuardedBy("mDevicesLock")
    private void makeA2dpDeviceAvailable(String address, String name, String eventSource,
            int a2dpCodec) {
        // enable A2DP before notifying A2DP connection to avoid unnecessary processing in
        // audio policy manager
        mDeviceBroker.setBluetoothA2dpOnInt(true, true /*fromA2dp*/, eventSource);
        // at this point there could be another A2DP device already connected in APM, but it
        // doesn't matter as this new one will overwrite the previous one
        final int res = mAudioSystem.setDeviceConnectionState(new AudioDeviceAttributes(
                AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, address, name),
                AudioSystem.DEVICE_STATE_AVAILABLE, a2dpCodec);

        // TODO: log in MediaMetrics once distinction between connection failure and
        // double connection is made.
        if (res != AudioSystem.AUDIO_STATUS_OK) {
            AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(
                    "APM failed to make available A2DP device addr=" + address
                            + " error=" + res).printLog(TAG));
            // TODO: connection failed, stop here
            // TODO: return;
        } else {
            AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(
                    "A2DP device addr=" + address + " now available").printLog(TAG));
        }

        // Reset A2DP suspend state each time a new sink is connected
        mAudioSystem.setParameters("A2dpSuspended=false");

        // The convention for head tracking sensors associated with A2DP devices is to
        // use a UUID derived from the MAC address as follows:
        //   time_low = 0, time_mid = 0, time_hi = 0, clock_seq = 0, node = MAC Address
        UUID sensorUuid = UuidUtils.uuidFromAudioDeviceAttributes(
                new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, address));
        final DeviceInfo di = new DeviceInfo(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, name,
                address, a2dpCodec, sensorUuid);
        final String diKey = di.getKey();
        mConnectedDevices.put(diKey, di);
        // on a connection always overwrite the device seen by AudioPolicy, see comment above when
        // calling AudioSystem
        mApmConnectedDevices.put(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, diKey);

        mDeviceBroker.postAccessoryPlugMediaUnmute(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP);
        setCurrentAudioRouteNameIfPossible(name, true /*fromA2dp*/);
    }

    @GuardedBy("mDevicesLock")
    private void makeA2dpDeviceUnavailableNow(String address, int a2dpCodec) {
        MediaMetrics.Item mmi = new MediaMetrics.Item(mMetricsId + "a2dp." + address)
                .set(MediaMetrics.Property.ENCODING, AudioSystem.audioFormatToString(a2dpCodec))
                .set(MediaMetrics.Property.EVENT, "makeA2dpDeviceUnavailableNow");

        if (address == null) {
            mmi.set(MediaMetrics.Property.EARLY_RETURN, "address null").record();
            return;
        }
        final String deviceToRemoveKey =
                DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, address);

        mConnectedDevices.remove(deviceToRemoveKey);
        if (!deviceToRemoveKey
                .equals(mApmConnectedDevices.get(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP))) {
            // removing A2DP device not currently used by AudioPolicy, log but don't act on it
            AudioService.sDeviceLogger.log((new AudioEventLogger.StringEvent(
                    "A2DP device " + address + " made unavailable, was not used")).printLog(TAG));
            mmi.set(MediaMetrics.Property.EARLY_RETURN,
                    "A2DP device made unavailable, was not used")
                    .record();
            return;
        }

        // device to remove was visible by APM, update APM
        mDeviceBroker.clearAvrcpAbsoluteVolumeSupported();
        final int res = mAudioSystem.setDeviceConnectionState(new AudioDeviceAttributes(
                AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, address),
                AudioSystem.DEVICE_STATE_UNAVAILABLE, a2dpCodec);

        if (res != AudioSystem.AUDIO_STATUS_OK) {
            AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(
                    "APM failed to make unavailable A2DP device addr=" + address
                            + " error=" + res).printLog(TAG));
            // TODO:  failed to disconnect, stop here
            // TODO: return;
        } else {
            AudioService.sDeviceLogger.log((new AudioEventLogger.StringEvent(
                    "A2DP device addr=" + address + " made unavailable")).printLog(TAG));
        }
        mApmConnectedDevices.remove(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP);
        // Remove A2DP routes as well
        setCurrentAudioRouteNameIfPossible(null, true /*fromA2dp*/);
        mmi.record();
    }

    @GuardedBy("mDevicesLock")
    private void makeA2dpDeviceUnavailableLater(String address, int delayMs) {
        // prevent any activity on the A2DP audio output to avoid unwanted
        // reconnection of the sink.
        mAudioSystem.setParameters("A2dpSuspended=true");
        // retrieve DeviceInfo before removing device
        final String deviceKey =
                DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, address);
        final DeviceInfo deviceInfo = mConnectedDevices.get(deviceKey);
        final int a2dpCodec = deviceInfo != null ? deviceInfo.mDeviceCodecFormat :
                AudioSystem.AUDIO_FORMAT_DEFAULT;
        // the device will be made unavailable later, so consider it disconnected right away
        mConnectedDevices.remove(deviceKey);
        // send the delayed message to make the device unavailable later
        mDeviceBroker.setA2dpTimeout(address, a2dpCodec, delayMs);
    }


    @GuardedBy("mDevicesLock")
    private void makeA2dpSrcAvailable(String address) {
        mAudioSystem.setDeviceConnectionState(new AudioDeviceAttributes(
                AudioSystem.DEVICE_IN_BLUETOOTH_A2DP, address),
                AudioSystem.DEVICE_STATE_AVAILABLE,
                AudioSystem.AUDIO_FORMAT_DEFAULT);
        mConnectedDevices.put(
                DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_IN_BLUETOOTH_A2DP, address),
                new DeviceInfo(AudioSystem.DEVICE_IN_BLUETOOTH_A2DP, "",
                        address, AudioSystem.AUDIO_FORMAT_DEFAULT));
    }

    @GuardedBy("mDevicesLock")
    private void makeA2dpSrcUnavailable(String address) {
        mAudioSystem.setDeviceConnectionState(new AudioDeviceAttributes(
                AudioSystem.DEVICE_IN_BLUETOOTH_A2DP, address),
                AudioSystem.DEVICE_STATE_UNAVAILABLE,
                AudioSystem.AUDIO_FORMAT_DEFAULT);
        mConnectedDevices.remove(
                DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_IN_BLUETOOTH_A2DP, address));
    }

    @GuardedBy("mDevicesLock")
    private void makeHearingAidDeviceAvailable(
            String address, String name, int streamType, String eventSource) {
        final int hearingAidVolIndex = mDeviceBroker.getVssVolumeForDevice(streamType,
                AudioSystem.DEVICE_OUT_HEARING_AID);
        mDeviceBroker.postSetHearingAidVolumeIndex(hearingAidVolIndex, streamType);

        mAudioSystem.setDeviceConnectionState(new AudioDeviceAttributes(
                AudioSystem.DEVICE_OUT_HEARING_AID, address, name),
                AudioSystem.DEVICE_STATE_AVAILABLE,
                AudioSystem.AUDIO_FORMAT_DEFAULT);
        mConnectedDevices.put(
                DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_OUT_HEARING_AID, address),
                new DeviceInfo(AudioSystem.DEVICE_OUT_HEARING_AID, name,
                        address, AudioSystem.AUDIO_FORMAT_DEFAULT));
        mDeviceBroker.postAccessoryPlugMediaUnmute(AudioSystem.DEVICE_OUT_HEARING_AID);
        mDeviceBroker.postApplyVolumeOnDevice(streamType,
                AudioSystem.DEVICE_OUT_HEARING_AID, "makeHearingAidDeviceAvailable");
        setCurrentAudioRouteNameIfPossible(name, false /*fromA2dp*/);
        new MediaMetrics.Item(mMetricsId + "makeHearingAidDeviceAvailable")
                .set(MediaMetrics.Property.ADDRESS, address != null ? address : "")
                .set(MediaMetrics.Property.DEVICE,
                        AudioSystem.getDeviceName(AudioSystem.DEVICE_OUT_HEARING_AID))
                .set(MediaMetrics.Property.NAME, name)
                .set(MediaMetrics.Property.STREAM_TYPE,
                        AudioSystem.streamToString(streamType))
                .record();
    }

    @GuardedBy("mDevicesLock")
    private void makeHearingAidDeviceUnavailable(String address) {
        mAudioSystem.setDeviceConnectionState(new AudioDeviceAttributes(
                AudioSystem.DEVICE_OUT_HEARING_AID, address),
                AudioSystem.DEVICE_STATE_UNAVAILABLE,
                AudioSystem.AUDIO_FORMAT_DEFAULT);
        mConnectedDevices.remove(
                DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_OUT_HEARING_AID, address));
        // Remove Hearing Aid routes as well
        setCurrentAudioRouteNameIfPossible(null, false /*fromA2dp*/);
        new MediaMetrics.Item(mMetricsId + "makeHearingAidDeviceUnavailable")
                .set(MediaMetrics.Property.ADDRESS, address != null ? address : "")
                .set(MediaMetrics.Property.DEVICE,
                        AudioSystem.getDeviceName(AudioSystem.DEVICE_OUT_HEARING_AID))
                .record();
    }

    @GuardedBy("mDevicesLock")
    private void makeLeAudioDeviceAvailable(String address, String name, int streamType, int device,
            String eventSource) {
        if (device != AudioSystem.DEVICE_NONE) {
            /* Audio Policy sees Le Audio similar to A2DP. Let's make sure
             * AUDIO_POLICY_FORCE_NO_BT_A2DP is not set
             */
            mDeviceBroker.setBluetoothA2dpOnInt(true, false /*fromA2dp*/, eventSource);

            AudioSystem.setDeviceConnectionState(new AudioDeviceAttributes(device, address, name),
                    AudioSystem.DEVICE_STATE_AVAILABLE,
                    AudioSystem.AUDIO_FORMAT_DEFAULT);
            mConnectedDevices.put(DeviceInfo.makeDeviceListKey(device, address),
                    new DeviceInfo(device, name, address, AudioSystem.AUDIO_FORMAT_DEFAULT));
            mDeviceBroker.postAccessoryPlugMediaUnmute(device);
            setCurrentAudioRouteNameIfPossible(name, /*fromA2dp=*/false);
        }

        if (streamType == AudioSystem.STREAM_DEFAULT) {
            // No need to update volume for input devices
            return;
        }

        final int leAudioVolIndex = mDeviceBroker.getVssVolumeForDevice(streamType, device);
        final int maxIndex = mDeviceBroker.getMaxVssVolumeForStream(streamType);
        mDeviceBroker.postSetLeAudioVolumeIndex(leAudioVolIndex, maxIndex, streamType);
        mDeviceBroker.postApplyVolumeOnDevice(streamType, device, "makeLeAudioDeviceAvailable");
    }

    @GuardedBy("mDevicesLock")
    private void makeLeAudioDeviceUnavailable(String address, int device) {
        if (device != AudioSystem.DEVICE_NONE) {
            AudioSystem.setDeviceConnectionState(new AudioDeviceAttributes(device, address),
                    AudioSystem.DEVICE_STATE_UNAVAILABLE,
                    AudioSystem.AUDIO_FORMAT_DEFAULT);
            mConnectedDevices.remove(DeviceInfo.makeDeviceListKey(device, address));
        }

        setCurrentAudioRouteNameIfPossible(null, false /*fromA2dp*/);
    }

    @GuardedBy("mDevicesLock")
    private void setCurrentAudioRouteNameIfPossible(String name, boolean fromA2dp) {
        synchronized (mCurAudioRoutes) {
            if (TextUtils.equals(mCurAudioRoutes.bluetoothName, name)) {
                return;
            }
            if (name != null || !isCurrentDeviceConnected()) {
                mCurAudioRoutes.bluetoothName = name;
                mDeviceBroker.postReportNewRoutes(fromA2dp);
            }
        }
    }

    @GuardedBy("mDevicesLock")
    private boolean isCurrentDeviceConnected() {
        return mConnectedDevices.values().stream().anyMatch(deviceInfo ->
            TextUtils.equals(deviceInfo.mDeviceName, mCurAudioRoutes.bluetoothName));
    }

    // Devices which removal triggers intent ACTION_AUDIO_BECOMING_NOISY. The intent is only
    // sent if:
    // - none of these devices are connected anymore after one is disconnected AND
    // - the device being disconnected is actually used for music.
    // Access synchronized on mConnectedDevices
    private static final Set<Integer> BECOMING_NOISY_INTENT_DEVICES_SET;
    static {
        BECOMING_NOISY_INTENT_DEVICES_SET = new HashSet<>();
        BECOMING_NOISY_INTENT_DEVICES_SET.add(AudioSystem.DEVICE_OUT_WIRED_HEADSET);
        BECOMING_NOISY_INTENT_DEVICES_SET.add(AudioSystem.DEVICE_OUT_WIRED_HEADPHONE);
        BECOMING_NOISY_INTENT_DEVICES_SET.add(AudioSystem.DEVICE_OUT_HDMI);
        BECOMING_NOISY_INTENT_DEVICES_SET.add(AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET);
        BECOMING_NOISY_INTENT_DEVICES_SET.add(AudioSystem.DEVICE_OUT_LINE);
        BECOMING_NOISY_INTENT_DEVICES_SET.add(AudioSystem.DEVICE_OUT_HEARING_AID);
        BECOMING_NOISY_INTENT_DEVICES_SET.add(AudioSystem.DEVICE_OUT_BLE_HEADSET);
        BECOMING_NOISY_INTENT_DEVICES_SET.add(AudioSystem.DEVICE_OUT_BLE_BROADCAST);
        BECOMING_NOISY_INTENT_DEVICES_SET.addAll(AudioSystem.DEVICE_OUT_ALL_A2DP_SET);
        BECOMING_NOISY_INTENT_DEVICES_SET.addAll(AudioSystem.DEVICE_OUT_ALL_USB_SET);
        BECOMING_NOISY_INTENT_DEVICES_SET.addAll(AudioSystem.DEVICE_OUT_ALL_BLE_SET);
    }

    // must be called before removing the device from mConnectedDevices
    // musicDevice argument is used when not AudioSystem.DEVICE_NONE instead of querying
    // from AudioSystem
    @GuardedBy("mDevicesLock")
    private int checkSendBecomingNoisyIntentInt(int device,
            @AudioService.ConnectionState int state, int musicDevice) {
        MediaMetrics.Item mmi = new MediaMetrics.Item(mMetricsId
                + "checkSendBecomingNoisyIntentInt")
                .set(MediaMetrics.Property.DEVICE, AudioSystem.getDeviceName(device))
                .set(MediaMetrics.Property.STATE,
                        state == AudioService.CONNECTION_STATE_CONNECTED
                                ? MediaMetrics.Value.CONNECTED : MediaMetrics.Value.DISCONNECTED);
        if (state != AudioService.CONNECTION_STATE_DISCONNECTED) {
            Log.i(TAG, "not sending NOISY: state=" + state);
            mmi.set(MediaMetrics.Property.DELAY_MS, 0).record(); // OK to return
            return 0;
        }
        if (!BECOMING_NOISY_INTENT_DEVICES_SET.contains(device)) {
            Log.i(TAG, "not sending NOISY: device=0x" + Integer.toHexString(device)
                    + " not in set " + BECOMING_NOISY_INTENT_DEVICES_SET);
            mmi.set(MediaMetrics.Property.DELAY_MS, 0).record(); // OK to return
            return 0;
        }
        int delay = 0;
        Set<Integer> devices = new HashSet<>();
        for (DeviceInfo di : mConnectedDevices.values()) {
            if (((di.mDeviceType & AudioSystem.DEVICE_BIT_IN) == 0)
                    && BECOMING_NOISY_INTENT_DEVICES_SET.contains(di.mDeviceType)) {
                devices.add(di.mDeviceType);
                Log.i(TAG, "NOISY: adding 0x" + Integer.toHexString(di.mDeviceType));
            }
        }
        if (musicDevice == AudioSystem.DEVICE_NONE) {
            musicDevice = mDeviceBroker.getDeviceForStream(AudioSystem.STREAM_MUSIC);
            Log.i(TAG, "NOISY: musicDevice changing from NONE to 0x"
                    + Integer.toHexString(musicDevice));
        }

        // always ignore condition on device being actually used for music when in communication
        // because music routing is altered in this case.
        // also checks whether media routing if affected by a dynamic policy or mirroring
        final boolean inCommunication = mDeviceBroker.isInCommunication();
        final boolean singleAudioDeviceType = AudioSystem.isSingleAudioDeviceType(devices, device);
        final boolean hasMediaDynamicPolicy = mDeviceBroker.hasMediaDynamicPolicy();
        if (((device == musicDevice) || inCommunication)
                && singleAudioDeviceType
                && !hasMediaDynamicPolicy
                && (musicDevice != AudioSystem.DEVICE_OUT_REMOTE_SUBMIX)) {
            if (!mAudioSystem.isStreamActive(AudioSystem.STREAM_MUSIC, 0 /*not looking in past*/)
                    && !mDeviceBroker.hasAudioFocusUsers()) {
                // no media playback, not a "becoming noisy" situation, otherwise it could cause
                // the pausing of some apps that are playing remotely
                AudioService.sDeviceLogger.log((new AudioEventLogger.StringEvent(
                        "dropping ACTION_AUDIO_BECOMING_NOISY")).printLog(TAG));
                mmi.set(MediaMetrics.Property.DELAY_MS, 0).record(); // OK to return
                return 0;
            }
            mDeviceBroker.postBroadcastBecomingNoisy();
            delay = AudioService.BECOMING_NOISY_DELAY_MS;
        } else {
            Log.i(TAG, "not sending NOISY: device:0x" + Integer.toHexString(device)
                    + " musicDevice:0x" + Integer.toHexString(musicDevice)
                    + " inComm:" + inCommunication
                    + " mediaPolicy:" + hasMediaDynamicPolicy
                    + " singleDevice:" + singleAudioDeviceType);
        }

        mmi.set(MediaMetrics.Property.DELAY_MS, delay).record();
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
            case AudioSystem.DEVICE_OUT_HDMI_EARC:
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
            mDeviceBroker.broadcastStickyIntentToCurrentProfileGroup(intent);
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
            case AudioSystem.DEVICE_OUT_HDMI_EARC:
                connType = AudioRoutesInfo.MAIN_HDMI;
                break;
            case AudioSystem.DEVICE_OUT_USB_DEVICE:
            case AudioSystem.DEVICE_OUT_USB_HEADSET:
                connType = AudioRoutesInfo.MAIN_USB;
                break;
            case AudioSystem.DEVICE_OUT_DGTL_DOCK_HEADSET:
                connType = AudioRoutesInfo.MAIN_DOCK_SPEAKERS;
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
                mDeviceBroker.postReportNewRoutes(false /*fromA2dp*/);
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
                    && devicePort.type() != AudioManager.DEVICE_OUT_HDMI_ARC
                    && devicePort.type() != AudioManager.DEVICE_OUT_HDMI_EARC) {
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

    private void dispatchPreferredDevice(int strategy,
                                         @NonNull List<AudioDeviceAttributes> devices) {
        final int nbDispatchers = mPrefDevDispatchers.beginBroadcast();
        for (int i = 0; i < nbDispatchers; i++) {
            try {
                mPrefDevDispatchers.getBroadcastItem(i).dispatchPrefDevicesChanged(
                        strategy, devices);
            } catch (RemoteException e) {
            }
        }
        mPrefDevDispatchers.finishBroadcast();
    }

    private void dispatchDevicesRoleForCapturePreset(
            int capturePreset, int role, @NonNull List<AudioDeviceAttributes> devices) {
        final int nbDispatchers = mDevRoleCapturePresetDispatchers.beginBroadcast();
        for (int i = 0; i < nbDispatchers; ++i) {
            try {
                mDevRoleCapturePresetDispatchers.getBroadcastItem(i).dispatchDevicesRoleChanged(
                        capturePreset, role, devices);
            } catch (RemoteException e) {
            }
        }
        mDevRoleCapturePresetDispatchers.finishBroadcast();
    }

    UUID getDeviceSensorUuid(AudioDeviceAttributes device) {
        final String key = DeviceInfo.makeDeviceListKey(device.getInternalType(),
                device.getAddress());
        synchronized (mDevicesLock) {
            DeviceInfo di = mConnectedDevices.get(key);
            if (di == null) {
                return null;
            }
            return di.mSensorUuid;
        }
    }
    //----------------------------------------------------------
    // For tests only

    /**
     * Check if device is in the list of connected devices
     * @param device
     * @return true if connected
     */
    @VisibleForTesting
    public boolean isA2dpDeviceConnected(@NonNull BluetoothDevice device) {
        final String key = DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                device.getAddress());
        synchronized (mDevicesLock) {
            return (mConnectedDevices.get(key) != null);
        }
    }
}
