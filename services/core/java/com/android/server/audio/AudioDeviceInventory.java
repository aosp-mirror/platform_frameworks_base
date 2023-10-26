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

import static android.media.AudioSystem.DEVICE_OUT_ALL_A2DP_SET;
import static android.media.AudioSystem.DEVICE_OUT_ALL_BLE_SET;
import static android.media.AudioSystem.isBluetoothDevice;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioDevicePort;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPort;
import android.media.AudioRoutesInfo;
import android.media.AudioSystem;
import android.media.IAudioRoutesObserver;
import android.media.ICapturePresetDevicesRoleDispatcher;
import android.media.IStrategyNonDefaultDevicesDispatcher;
import android.media.IStrategyPreferredDevicesDispatcher;
import android.media.MediaMetrics;
import android.media.MediaRecorder.AudioSource;
import android.media.audiopolicy.AudioProductStrategy;
import android.media.permission.ClearCallingIdentityContext;
import android.media.permission.SafeCloseable;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.utils.EventLogger;

import com.google.android.collect.Sets;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Class to manage the inventory of all connected devices.
 * This class is thread-safe.
 * (non final for mocking/spying)
 */
public class AudioDeviceInventory {

    private static final String TAG = "AS.AudioDeviceInventory";

    private static final String SETTING_DEVICE_SEPARATOR_CHAR = "|";
    private static final String SETTING_DEVICE_SEPARATOR = "\\|";

    // lock to synchronize all access to mConnectedDevices and mApmConnectedDevices
    private final Object mDevicesLock = new Object();

    //Audio Analytics ids.
    private static final String mMetricsId = "audio.device.";

    private final Object mDeviceInventoryLock = new Object();

    @GuardedBy("mDeviceInventoryLock")
    private final HashMap<Pair<Integer, String>, AdiDeviceState> mDeviceInventory = new HashMap<>();

    Collection<AdiDeviceState> getImmutableDeviceInventory() {
        synchronized (mDeviceInventoryLock) {
            return mDeviceInventory.values();
        }
    }

    /**
     * Adds a new AdiDeviceState or updates the spatial audio related properties of the matching
     * AdiDeviceState in the {@link AudioDeviceInventory#mDeviceInventory} list.
     * @param deviceState the device to update
     */
    void addOrUpdateDeviceSAStateInInventory(AdiDeviceState deviceState) {
        synchronized (mDeviceInventoryLock) {
            mDeviceInventory.merge(deviceState.getDeviceId(), deviceState, (oldState, newState) -> {
                oldState.setHasHeadTracker(newState.hasHeadTracker());
                oldState.setHeadTrackerEnabled(newState.isHeadTrackerEnabled());
                oldState.setSAEnabled(newState.isSAEnabled());
                return oldState;
            });
        }
    }

    /**
     * Adds a new entry in mDeviceInventory if the AudioDeviceAttributes passed is an sink
     * Bluetooth device and no corresponding entry already exists.
     * @param ada the device to add if needed
     */
    void addAudioDeviceInInventoryIfNeeded(AudioDeviceAttributes ada) {
        if (!AudioSystem.isBluetoothOutDevice(ada.getInternalType())) {
            return;
        }
        synchronized (mDeviceInventoryLock) {
            if (findDeviceStateForAudioDeviceAttributes(ada, ada.getType()) != null) {
                return;
            }
            AdiDeviceState ads = new AdiDeviceState(
                    ada.getType(), ada.getInternalType(), ada.getAddress());
            mDeviceInventory.put(ads.getDeviceId(), ads);
        }
        mDeviceBroker.persistAudioDeviceSettings();
    }

    /**
     * Adds a new AdiDeviceState or updates the audio device cateogory of the matching
     * AdiDeviceState in the {@link AudioDeviceInventory#mDeviceInventory} list.
     * @param deviceState the device to update
     */
    void addOrUpdateAudioDeviceCategoryInInventory(AdiDeviceState deviceState) {
        synchronized (mDeviceInventoryLock) {
            mDeviceInventory.merge(deviceState.getDeviceId(), deviceState, (oldState, newState) -> {
                oldState.setAudioDeviceCategory(newState.getAudioDeviceCategory());
                return oldState;
            });
        }
    }

    /**
     * Finds the BT device that matches the passed {@code address}. Currently, this method only
     * returns a valid device for A2DP and BLE devices.
     *
     * @param address MAC address of BT device
     * @param isBle true if the device is BLE, false for A2DP
     * @return the found {@link AdiDeviceState} or {@code null} otherwise.
     */
    @Nullable
    AdiDeviceState findBtDeviceStateForAddress(String address, boolean isBle) {
        synchronized (mDeviceInventoryLock) {
            final Set<Integer> deviceSet = isBle ? DEVICE_OUT_ALL_BLE_SET : DEVICE_OUT_ALL_A2DP_SET;
            for (Integer internalType : deviceSet) {
                AdiDeviceState deviceState = mDeviceInventory.get(
                        new Pair<>(internalType, address));
                if (deviceState != null) {
                    return deviceState;
                }
            }
        }
        return null;
    }

    /**
     * Finds the device state that matches the passed {@link AudioDeviceAttributes} and device
     * type. Note: currently this method only returns a valid device for A2DP and BLE devices.
     *
     * @param ada attributes of device to match
     * @param canonicalDeviceType external device type to match
     * @return the found {@link AdiDeviceState} matching a cached A2DP or BLE device or
     *         {@code null} otherwise.
     */
    @Nullable
    AdiDeviceState findDeviceStateForAudioDeviceAttributes(AudioDeviceAttributes ada,
            int canonicalDeviceType) {
        final boolean isWireless = isBluetoothDevice(ada.getInternalType());
        synchronized (mDeviceInventoryLock) {
            for (AdiDeviceState deviceState : mDeviceInventory.values()) {
                if (deviceState.getDeviceType() == canonicalDeviceType
                        && (!isWireless || ada.getAddress().equals(
                        deviceState.getDeviceAddress()))) {
                    return deviceState;
                }
            }
        }
        return null;
    }

    /** Clears all cached {@link AdiDeviceState}'s. */
    void clearDeviceInventory() {
        synchronized (mDeviceInventoryLock) {
            mDeviceInventory.clear();
        }
    }

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

    // List of non-default devices for strategies
    private final ArrayMap<Integer, List<AudioDeviceAttributes>> mNonDefaultDevices =
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

    // Monitoring of preferred device for strategies
    final RemoteCallbackList<IStrategyPreferredDevicesDispatcher> mPrefDevDispatchers =
            new RemoteCallbackList<IStrategyPreferredDevicesDispatcher>();

    // Monitoring of non-default device for strategies
    final RemoteCallbackList<IStrategyNonDefaultDevicesDispatcher> mNonDefDevDispatchers =
            new RemoteCallbackList<IStrategyNonDefaultDevicesDispatcher>();

    // Monitoring of devices for role and capture preset
    final RemoteCallbackList<ICapturePresetDevicesRoleDispatcher> mDevRoleCapturePresetDispatchers =
            new RemoteCallbackList<ICapturePresetDevicesRoleDispatcher>();

    final List<AudioProductStrategy> mStrategies;

    /*package*/ AudioDeviceInventory(@NonNull AudioDeviceBroker broker) {
        this(broker, AudioSystemAdapter.getDefaultAdapter());
    }

    //-----------------------------------------------------------
    /** for mocking only, allows to inject AudioSystem adapter */
    /*package*/ AudioDeviceInventory(@NonNull AudioSystemAdapter audioSystem) {
        this(null, audioSystem);
    }

    private AudioDeviceInventory(@Nullable AudioDeviceBroker broker,
                       @Nullable AudioSystemAdapter audioSystem) {
        mDeviceBroker = broker;
        mAudioSystem = audioSystem;
        mStrategies = AudioProductStrategy.getAudioProductStrategies();
        mBluetoothDualModeEnabled = SystemProperties.getBoolean(
                "persist.bluetooth.enable_dual_mode_audio", false);
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

        /** Disabled operating modes for this device. Use a negative logic so that by default
         * an empty list means all modes are allowed.
         * See BluetoothAdapter.AUDIO_MODE_DUPLEX and BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY */
        @NonNull ArraySet<String> mDisabledModes = new ArraySet(0);

        DeviceInfo(int deviceType, String deviceName, String deviceAddress,
                   int deviceCodecFormat, @Nullable UUID sensorUuid) {
            mDeviceType = deviceType;
            mDeviceName = deviceName == null ? "" : deviceName;
            mDeviceAddress = deviceAddress == null ? "" : deviceAddress;
            mDeviceCodecFormat = deviceCodecFormat;
            mSensorUuid = sensorUuid;
        }

        void setModeDisabled(String mode) {
            mDisabledModes.add(mode);
        }
        void setModeEnabled(String mode) {
            mDisabledModes.remove(mode);
        }
        boolean isModeEnabled(String mode) {
            return !mDisabledModes.contains(mode);
        }
        boolean isOutputOnlyModeEnabled() {
            return isModeEnabled(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY);
        }
        boolean isDuplexModeEnabled() {
            return isModeEnabled(BluetoothAdapter.AUDIO_MODE_DUPLEX);
        }

        DeviceInfo(int deviceType, String deviceName, String deviceAddress,
                   int deviceCodecFormat) {
            this(deviceType, deviceName, deviceAddress, deviceCodecFormat, null);
        }

        DeviceInfo(int deviceType, String deviceName, String deviceAddress) {
            this(deviceType, deviceName, deviceAddress, AudioSystem.AUDIO_FORMAT_DEFAULT);
        }

        @Override
        public String toString() {
            return "[DeviceInfo: type:0x" + Integer.toHexString(mDeviceType)
                    + " (" + AudioSystem.getDeviceName(mDeviceType)
                    + ") name:" + mDeviceName
                    + " addr:" + mDeviceAddress
                    + " codec: " + Integer.toHexString(mDeviceCodecFormat)
                    + " sensorUuid: " + Objects.toString(mSensorUuid)
                    + " disabled modes: " + mDisabledModes + "]";
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
        pw.println("\n" + prefix + "Non-default devices for strategy:");
        mNonDefaultDevices.forEach((strategy, device) -> {
            pw.println("  " + prefix + "strategy:" + strategy + " device:" + device); });
        pw.println("\n" + prefix + "Connected devices:");
        mConnectedDevices.forEach((key, deviceInfo) -> {
            pw.println("  " + prefix + deviceInfo.toString()); });
        pw.println("\n" + prefix + "APM Connected device (A2DP sink only):");
        mApmConnectedDevices.forEach((keyType, valueAddress) -> {
            pw.println("  " + prefix + " type:0x" + Integer.toHexString(keyType)
                    + " (" + AudioSystem.getDeviceName(keyType)
                    + ") addr:" + valueAddress); });
        pw.println("\n" + prefix + "Preferred devices for capture preset:");
        mPreferredDevicesForCapturePreset.forEach((capturePreset, devices) -> {
            pw.println("  " + prefix + "capturePreset:" + capturePreset
                    + " devices:" + devices); });
        pw.println("\n" + prefix + "Applied devices roles for strategies (from API):");
        mAppliedStrategyRoles.forEach((key, devices) -> {
            pw.println("  " + prefix + "strategy: " + key.first
                    +  " role:" + key.second + " devices:" + devices); });
        pw.println("\n" + prefix + "Applied devices roles for strategies (internal):");
        mAppliedStrategyRolesInt.forEach((key, devices) -> {
            pw.println("  " + prefix + "strategy: " + key.first
                    +  " role:" + key.second + " devices:" + devices); });
        pw.println("\n" + prefix + "Applied devices roles for presets (from API):");
        mAppliedPresetRoles.forEach((key, devices) -> {
            pw.println("  " + prefix + "preset: " + key.first
                    +  " role:" + key.second + " devices:" + devices); });
        pw.println("\n" + prefix + "Applied devices roles for presets (internal:");
        mAppliedPresetRolesInt.forEach((key, devices) -> {
            pw.println("  " + prefix + "preset: " + key.first
                    +  " role:" + key.second + " devices:" + devices); });
        pw.println("\ndevices:\n");
        synchronized (mDeviceInventoryLock) {
            for (AdiDeviceState device : mDeviceInventory.values()) {
                pw.println("\t" + device + "\n");
            }
        }
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
            mAppliedStrategyRolesInt.clear();
            mAppliedPresetRolesInt.clear();
            applyConnectedDevicesRoles_l();
        }
        reapplyExternalDevicesRoles();
    }

    /*package*/ void reapplyExternalDevicesRoles() {
        synchronized (mDevicesLock) {
            mAppliedStrategyRoles.clear();
            mAppliedPresetRoles.clear();
        }
        synchronized (mPreferredDevices) {
            mPreferredDevices.forEach((strategy, devices) -> {
                setPreferredDevicesForStrategy(strategy, devices);
            });
        }
        synchronized (mNonDefaultDevices) {
            mNonDefaultDevices.forEach((strategy, devices) -> {
                addDevicesRoleForStrategy(strategy, AudioSystem.DEVICE_ROLE_DISABLED,
                        devices, false /* internal */);
            });
        }
        synchronized (mPreferredDevicesForCapturePreset) {
            mPreferredDevicesForCapturePreset.forEach((capturePreset, devices) -> {
                setDevicesRoleForCapturePreset(
                        capturePreset, AudioSystem.DEVICE_ROLE_PREFERRED, devices);
            });
        }
    }

    // @GuardedBy("mDeviceBroker.mSetModeLock")
    @GuardedBy("AudioDeviceBroker.this.mDeviceStateLock")
    void onSetBtActiveDevice(@NonNull AudioDeviceBroker.BtDeviceInfo btInfo,
                             @AudioSystem.AudioFormatNativeEnumForBtCodec int codec,
                             int streamType) {
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

        AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent("BT connected:"
                        + btInfo + " codec=" + AudioSystem.audioFormatToString(codec)));

        new MediaMetrics.Item(mMetricsId + "onSetBtActiveDevice")
                .set(MediaMetrics.Property.STATUS, btInfo.mProfile)
                .set(MediaMetrics.Property.DEVICE,
                        AudioSystem.getDeviceName(btInfo.mAudioSystemDevice))
                .set(MediaMetrics.Property.ADDRESS, address)
                .set(MediaMetrics.Property.ENCODING,
                        AudioSystem.audioFormatToString(codec))
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
                        makeA2dpDeviceAvailable(btInfo, codec, "onSetBtActiveDevice");
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
                        makeLeAudioDeviceUnavailableNow(address, btInfo.mAudioSystemDevice);
                    } else if (switchToAvailable) {
                        makeLeAudioDeviceAvailable(btInfo, streamType, "onSetBtActiveDevice");
                    }
                    break;
                default: throw new IllegalArgumentException("Invalid profile "
                                 + BluetoothProfile.getProfileName(btInfo.mProfile));
            }
        }
    }


    @GuardedBy("AudioDeviceBroker.this.mDeviceStateLock")
    /*package*/ void onBluetoothDeviceConfigChange(
            @NonNull AudioDeviceBroker.BtDeviceInfo btInfo,
            @AudioSystem.AudioFormatNativeEnumForBtCodec int codec, int event) {
        MediaMetrics.Item mmi = new MediaMetrics.Item(mMetricsId
                + "onBluetoothDeviceConfigChange")
                .set(MediaMetrics.Property.EVENT, BtHelper.deviceEventToString(event));

        final BluetoothDevice btDevice = btInfo.mDevice;
        if (btDevice == null) {
            mmi.set(MediaMetrics.Property.EARLY_RETURN, "btDevice null").record();
            return;
        }
        if (AudioService.DEBUG_DEVICES) {
            Log.d(TAG, "onBluetoothDeviceConfigChange btDevice=" + btDevice);
        }
        int volume = btInfo.mVolume;

        String address = btDevice.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = "";
        }
        AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(
                "onBluetoothDeviceConfigChange addr=" + address
                    + " event=" + BtHelper.deviceEventToString(event)));

        synchronized (mDevicesLock) {
            if (mDeviceBroker.hasScheduledA2dpConnection(btDevice)) {
                AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(
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
                Log.e(TAG, "invalid null DeviceInfo in onBluetoothDeviceConfigChange");
                mmi.set(MediaMetrics.Property.EARLY_RETURN, "null DeviceInfo").record();
                return;
            }

            mmi.set(MediaMetrics.Property.ADDRESS, address)
                    .set(MediaMetrics.Property.ENCODING, AudioSystem.audioFormatToString(codec))
                    .set(MediaMetrics.Property.INDEX, volume)
                    .set(MediaMetrics.Property.NAME, di.mDeviceName);


            if (event == BtHelper.EVENT_DEVICE_CONFIG_CHANGE) {
                boolean a2dpCodecChange = false;
                if (btInfo.mProfile == BluetoothProfile.A2DP) {
                    if (di.mDeviceCodecFormat != codec) {
                        di.mDeviceCodecFormat = codec;
                        mConnectedDevices.replace(key, di);
                        a2dpCodecChange = true;
                    }
                    final int res = mAudioSystem.handleDeviceConfigChange(
                            btInfo.mAudioSystemDevice, address, BtHelper.getName(btDevice), codec);

                    if (res != AudioSystem.AUDIO_STATUS_OK) {
                        AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(
                                "APM handleDeviceConfigChange failed for A2DP device addr="
                                        + address + " codec="
                                        + AudioSystem.audioFormatToString(codec))
                                .printLog(TAG));

                        // force A2DP device disconnection in case of error so that AudioService
                        // state is consistent with audio policy manager state
                        setBluetoothActiveDevice(new AudioDeviceBroker.BtDeviceInfo(btInfo,
                                BluetoothProfile.STATE_DISCONNECTED));
                    } else {
                        AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(
                                "APM handleDeviceConfigChange success for A2DP device addr="
                                        + address
                                        + " codec=" + AudioSystem.audioFormatToString(codec))
                                .printLog(TAG));

                    }
                }
                if (!a2dpCodecChange) {
                    updateBluetoothPreferredModes_l(btDevice /*connectedDevice*/);
                }
            }
        }
        mmi.record();
    }

    /*package*/ void onMakeA2dpDeviceUnavailableNow(String address, int a2dpCodec) {
        synchronized (mDevicesLock) {
            makeA2dpDeviceUnavailableNow(address, a2dpCodec);
        }
    }

    /*package*/ void onMakeLeAudioDeviceUnavailableNow(String address, int device) {
        synchronized (mDevicesLock) {
            makeLeAudioDeviceUnavailableNow(address, device);
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

        AudioService.sDeviceLogger.enqueue(new AudioServiceEvents.WiredDevConnectEvent(wdcs));

        MediaMetrics.Item mmi = new MediaMetrics.Item(mMetricsId
                + "onSetWiredDeviceConnectionState")
                .set(MediaMetrics.Property.ADDRESS, wdcs.mAttributes.getAddress())
                .set(MediaMetrics.Property.DEVICE,
                        AudioSystem.getDeviceName(type))
                .set(MediaMetrics.Property.STATE,
                        wdcs.mState == AudioService.CONNECTION_STATE_DISCONNECTED
                                ? MediaMetrics.Value.DISCONNECTED : MediaMetrics.Value.CONNECTED);
        AudioDeviceInfo info = null;
        if (wdcs.mState == AudioService.CONNECTION_STATE_DISCONNECTED
                && AudioSystem.DEVICE_OUT_ALL_USB_SET.contains(
                        wdcs.mAttributes.getInternalType())) {
            for (AudioDeviceInfo deviceInfo : AudioManager.getDevicesStatic(
                    AudioManager.GET_DEVICES_OUTPUTS)) {
                if (deviceInfo.getInternalType() == wdcs.mAttributes.getInternalType()) {
                    info = deviceInfo;
                    break;
                }
            }
        }
        synchronized (mDevicesLock) {
            if ((wdcs.mState == AudioService.CONNECTION_STATE_DISCONNECTED)
                    && DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG_SET.contains(type)) {
                mDeviceBroker.setBluetoothA2dpOnInt(true, false /*fromA2dp*/,
                        "onSetWiredDeviceConnectionState state DISCONNECTED");
            }

            if (!handleDeviceConnection(wdcs.mAttributes,
                    wdcs.mState == AudioService.CONNECTION_STATE_CONNECTED, wdcs.mForTest, null)) {
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
            if (wdcs.mState == AudioService.CONNECTION_STATE_DISCONNECTED
                    && AudioSystem.DEVICE_OUT_ALL_USB_SET.contains(
                            wdcs.mAttributes.getInternalType())) {
                if (info != null) {
                    mDeviceBroker.dispatchPreferredMixerAttributesChangedCausedByDeviceRemoved(
                            info);
                } else {
                    Log.e(TAG, "Didn't find AudioDeviceInfo to notify preferred mixer "
                            + "attributes change for type=" + wdcs.mAttributes.getType());
                }
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
        List<AudioDeviceAttributes> nonDefaultDevices = mNonDefaultDevices.get(strategy);
        if (nonDefaultDevices != null) {
            nonDefaultDevices.removeAll(devices);

            if (nonDefaultDevices.isEmpty()) {
                mNonDefaultDevices.remove(strategy);
            } else {
                mNonDefaultDevices.put(strategy, nonDefaultDevices);
            }
            dispatchNonDefaultDevice(strategy, nonDefaultDevices);
        }

        dispatchPreferredDevice(strategy, devices);
    }

    /*package*/ void onSaveRemovePreferredDevices(int strategy) {
        mPreferredDevices.remove(strategy);
        dispatchPreferredDevice(strategy, new ArrayList<AudioDeviceAttributes>());
    }

    /*package*/ void onSaveSetDeviceAsNonDefault(int strategy,
                                                 @NonNull AudioDeviceAttributes device) {
        List<AudioDeviceAttributes> nonDefaultDevices = mNonDefaultDevices.get(strategy);
        if (nonDefaultDevices == null) {
            nonDefaultDevices = new ArrayList<>();
        }

        if (!nonDefaultDevices.contains(device)) {
            nonDefaultDevices.add(device);
        }

        mNonDefaultDevices.put(strategy, nonDefaultDevices);
        dispatchNonDefaultDevice(strategy, nonDefaultDevices);

        List<AudioDeviceAttributes> preferredDevices = mPreferredDevices.get(strategy);

        if (preferredDevices != null) {
            preferredDevices.remove(device);
            mPreferredDevices.put(strategy, preferredDevices);

            dispatchPreferredDevice(strategy, preferredDevices);
        }
    }

    /*package*/ void onSaveRemoveDeviceAsNonDefault(int strategy,
                                                    @NonNull AudioDeviceAttributes device) {
        List<AudioDeviceAttributes> nonDefaultDevices = mNonDefaultDevices.get(strategy);
        if (nonDefaultDevices != null) {
            nonDefaultDevices.remove(device);
            mNonDefaultDevices.put(strategy, nonDefaultDevices);
            dispatchNonDefaultDevice(strategy, nonDefaultDevices);
        }
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
    // preferred/non-default device(s)

    /*package*/ int setPreferredDevicesForStrategyAndSave(int strategy,
            @NonNull List<AudioDeviceAttributes> devices) {
        final int status = setPreferredDevicesForStrategy(strategy, devices);
        if (status == AudioSystem.SUCCESS) {
            mDeviceBroker.postSaveSetPreferredDevicesForStrategy(strategy, devices);
        }
        return status;
    }
    // Only used for external requests coming from an API
    /*package*/ int setPreferredDevicesForStrategy(int strategy,
            @NonNull List<AudioDeviceAttributes> devices) {
        int status = AudioSystem.ERROR;
        try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
            AudioService.sDeviceLogger.enqueue((new EventLogger.StringEvent(
                    "setPreferredDevicesForStrategy, strategy: " + strategy
                            + " devices: " + devices)).printLog(TAG));
            status = setDevicesRoleForStrategy(
                    strategy, AudioSystem.DEVICE_ROLE_PREFERRED, devices, false /* internal */);
        }
        return status;
    }
    // Only used for internal requests
    /*package*/ int setPreferredDevicesForStrategyInt(int strategy,
                                                  @NonNull List<AudioDeviceAttributes> devices) {

        return setDevicesRoleForStrategy(
                    strategy, AudioSystem.DEVICE_ROLE_PREFERRED, devices, true /* internal */);
    }

    /*package*/ int removePreferredDevicesForStrategyAndSave(int strategy) {
        final int status = removePreferredDevicesForStrategy(strategy);
        if (status == AudioSystem.SUCCESS) {
            mDeviceBroker.postSaveRemovePreferredDevicesForStrategy(strategy);
        }
        return status;
    }
    // Only used for external requests coming from an API
    /*package*/ int removePreferredDevicesForStrategy(int strategy) {
        int status = AudioSystem.ERROR;

        try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
            AudioService.sDeviceLogger.enqueue((new EventLogger.StringEvent(
                            "removePreferredDevicesForStrategy, strategy: "
                            + strategy)).printLog(TAG));

            status = clearDevicesRoleForStrategy(
                    strategy, AudioSystem.DEVICE_ROLE_PREFERRED, false /*internal */);
        }
        return status;
    }
    // Only used for internal requests
    /*package*/ int removePreferredDevicesForStrategyInt(int strategy) {
        return clearDevicesRoleForStrategy(
                    strategy, AudioSystem.DEVICE_ROLE_PREFERRED, true /*internal */);
    }

    /*package*/ int setDeviceAsNonDefaultForStrategyAndSave(int strategy,
            @NonNull AudioDeviceAttributes device) {
        int status = AudioSystem.ERROR;

        try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
            List<AudioDeviceAttributes> devices = new ArrayList<>();
            devices.add(device);

            AudioService.sDeviceLogger.enqueue((new EventLogger.StringEvent(
                            "setDeviceAsNonDefaultForStrategyAndSave, strategy: " + strategy
                            + " device: " + device)).printLog(TAG));
            status = addDevicesRoleForStrategy(
                    strategy, AudioSystem.DEVICE_ROLE_DISABLED, devices, false /* internal */);
        }

        if (status == AudioSystem.SUCCESS) {
            mDeviceBroker.postSaveSetDeviceAsNonDefaultForStrategy(strategy, device);
        }
        return status;
    }

    /*package*/ int removeDeviceAsNonDefaultForStrategyAndSave(int strategy,
            @NonNull AudioDeviceAttributes device) {
        int status = AudioSystem.ERROR;

        try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
            List<AudioDeviceAttributes> devices = new ArrayList<>();
            devices.add(device);

            AudioService.sDeviceLogger.enqueue((new EventLogger.StringEvent(
                            "removeDeviceAsNonDefaultForStrategyAndSave, strategy: "
                            + strategy + " devices: " + device)).printLog(TAG));

            status = removeDevicesRoleForStrategy(
                    strategy, AudioSystem.DEVICE_ROLE_DISABLED, devices, false /* internal */);
        }

        if (status == AudioSystem.SUCCESS) {
            mDeviceBroker.postSaveRemoveDeviceAsNonDefaultForStrategy(strategy, device);
        }
        return status;
    }


    /*package*/ void registerStrategyPreferredDevicesDispatcher(
            @NonNull IStrategyPreferredDevicesDispatcher dispatcher, boolean isPrivileged) {
        mPrefDevDispatchers.register(dispatcher, isPrivileged);
    }

    /*package*/ void unregisterStrategyPreferredDevicesDispatcher(
            @NonNull IStrategyPreferredDevicesDispatcher dispatcher) {
        mPrefDevDispatchers.unregister(dispatcher);
    }

    /*package*/ void registerStrategyNonDefaultDevicesDispatcher(
            @NonNull IStrategyNonDefaultDevicesDispatcher dispatcher, boolean isPrivileged) {
        mNonDefDevDispatchers.register(dispatcher, isPrivileged);
    }

    /*package*/ void unregisterStrategyNonDefaultDevicesDispatcher(
            @NonNull IStrategyNonDefaultDevicesDispatcher dispatcher) {
        mNonDefDevDispatchers.unregister(dispatcher);
    }

    /*package*/ int setPreferredDevicesForCapturePresetAndSave(
            int capturePreset, @NonNull List<AudioDeviceAttributes> devices) {
        final int status = setPreferredDevicesForCapturePreset(capturePreset, devices);
        if (status == AudioSystem.SUCCESS) {
            mDeviceBroker.postSaveSetPreferredDevicesForCapturePreset(capturePreset, devices);
        }
        return status;
    }

    // Only used for external requests coming from an API
    private int setPreferredDevicesForCapturePreset(
            int capturePreset, @NonNull List<AudioDeviceAttributes> devices) {
        int status = AudioSystem.ERROR;
        try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
            status = setDevicesRoleForCapturePreset(
                    capturePreset, AudioSystem.DEVICE_ROLE_PREFERRED, devices);
        }
        return status;
    }

    /*package*/ int clearPreferredDevicesForCapturePresetAndSave(int capturePreset) {
        final int status  = clearPreferredDevicesForCapturePreset(capturePreset);
        if (status == AudioSystem.SUCCESS) {
            mDeviceBroker.postSaveClearPreferredDevicesForCapturePreset(capturePreset);
        }
        return status;
    }

    // Only used for external requests coming from an API
    private int clearPreferredDevicesForCapturePreset(int capturePreset) {
        int status  = AudioSystem.ERROR;

        try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
            status = clearDevicesRoleForCapturePreset(
                    capturePreset, AudioSystem.DEVICE_ROLE_PREFERRED);
        }
        return status;
    }

    // Only used for internal requests
    private int addDevicesRoleForCapturePresetInt(int capturePreset, int role,
                                               @NonNull List<AudioDeviceAttributes> devices) {
        return addDevicesRole(mAppliedPresetRolesInt, (p, r, d) -> {
            return mAudioSystem.addDevicesRoleForCapturePreset(p, r, d);
        }, capturePreset, role, devices);
    }

    // Only used for internal requests
    private int removeDevicesRoleForCapturePresetInt(int capturePreset, int role,
                                                  @NonNull List<AudioDeviceAttributes> devices) {
        return removeDevicesRole(mAppliedPresetRolesInt, (p, r, d) -> {
            return mAudioSystem.removeDevicesRoleForCapturePreset(p, r, d);
        }, capturePreset, role, devices);
    }

    // Only used for external requests coming from an API
    private int setDevicesRoleForCapturePreset(int capturePreset, int role,
                                               @NonNull List<AudioDeviceAttributes> devices) {
        return setDevicesRole(mAppliedPresetRoles, (p, r, d) -> {
            return mAudioSystem.addDevicesRoleForCapturePreset(p, r, d);
        }, (p, r, d) -> {
                return mAudioSystem.clearDevicesRoleForCapturePreset(p, r);
            }, capturePreset, role, devices);
    }

    // Only used for external requests coming from an API
    private int clearDevicesRoleForCapturePreset(int capturePreset, int role) {
        return clearDevicesRole(mAppliedPresetRoles, (p, r, d) -> {
            return mAudioSystem.clearDevicesRoleForCapturePreset(p, r);
        }, capturePreset, role);
    }

    /*package*/ void registerCapturePresetDevicesRoleDispatcher(
            @NonNull ICapturePresetDevicesRoleDispatcher dispatcher, boolean isPrivileged) {
        mDevRoleCapturePresetDispatchers.register(dispatcher, isPrivileged);
    }

    /*package*/ void unregisterCapturePresetDevicesRoleDispatcher(
            @NonNull ICapturePresetDevicesRoleDispatcher dispatcher) {
        mDevRoleCapturePresetDispatchers.unregister(dispatcher);
    }

    private int addDevicesRoleForStrategy(int strategy, int role,
                                          @NonNull List<AudioDeviceAttributes> devices,
                                          boolean internal) {
        return addDevicesRole(internal ? mAppliedStrategyRolesInt : mAppliedStrategyRoles,
                (s, r, d) -> {
                    return mAudioSystem.setDevicesRoleForStrategy(s, r, d);
                }, strategy, role, devices);
    }

    private int removeDevicesRoleForStrategy(int strategy, int role,
                                      @NonNull List<AudioDeviceAttributes> devices,
                                             boolean internal) {
        return removeDevicesRole(internal ? mAppliedStrategyRolesInt : mAppliedStrategyRoles,
                (s, r, d) -> {
                    return mAudioSystem.removeDevicesRoleForStrategy(s, r, d);
                }, strategy, role, devices);
    }

    private int setDevicesRoleForStrategy(int strategy, int role,
                                          @NonNull List<AudioDeviceAttributes> devices,
                                          boolean internal) {
        return setDevicesRole(internal ? mAppliedStrategyRolesInt : mAppliedStrategyRoles,
                (s, r, d) -> {
                    return mAudioSystem.setDevicesRoleForStrategy(s, r, d);
                }, (s, r, d) -> {
                    return mAudioSystem.clearDevicesRoleForStrategy(s, r);
                }, strategy, role, devices);
    }

    private int clearDevicesRoleForStrategy(int strategy, int role, boolean internal) {
        return clearDevicesRole(internal ? mAppliedStrategyRolesInt : mAppliedStrategyRoles,
                (s, r, d) -> {
                    return mAudioSystem.clearDevicesRoleForStrategy(s, r);
                }, strategy, role);
    }

    //------------------------------------------------------------
    // Cache for applied roles for strategies and devices. The cache avoids reapplying the
    // same list of devices for a given role and strategy and the corresponding systematic
    // redundant work in audio policy manager and audio flinger.
    // The key is the pair <Strategy , Role> and the value is the current list of devices.
    // mAppliedStrategyRoles is for requests coming from an API.
    // mAppliedStrategyRolesInt is for internal requests. Entries are removed when the requested
    // device is disconnected.
    private final ArrayMap<Pair<Integer, Integer>, List<AudioDeviceAttributes>>
            mAppliedStrategyRoles = new ArrayMap<>();
    private final ArrayMap<Pair<Integer, Integer>, List<AudioDeviceAttributes>>
            mAppliedStrategyRolesInt = new ArrayMap<>();

    // Cache for applied roles for capture presets and devices. The cache avoids reapplying the
    // same list of devices for a given role and capture preset and the corresponding systematic
    // redundant work in audio policy manager and audio flinger.
    // The key is the pair <Preset , Role> and the value is the current list of devices.
    // mAppliedPresetRoles is for requests coming from an API.
    // mAppliedPresetRolesInt is for internal requests. Entries are removed when the requested
    // device is disconnected.
    private final ArrayMap<Pair<Integer, Integer>, List<AudioDeviceAttributes>>
            mAppliedPresetRoles = new ArrayMap<>();
    private final ArrayMap<Pair<Integer, Integer>, List<AudioDeviceAttributes>>
            mAppliedPresetRolesInt = new ArrayMap<>();

    interface AudioSystemInterface {
        int deviceRoleAction(int usecase, int role, @Nullable List<AudioDeviceAttributes> devices);
    }

    private int addDevicesRole(
            ArrayMap<Pair<Integer, Integer>, List<AudioDeviceAttributes>> rolesMap,
            AudioSystemInterface asi,
            int useCase, int role, @NonNull List<AudioDeviceAttributes> devices) {
        synchronized (rolesMap) {
            Pair<Integer, Integer> key = new Pair<>(useCase, role);
            List<AudioDeviceAttributes> roleDevices = new ArrayList<>();
            List<AudioDeviceAttributes> appliedDevices = new ArrayList<>();

            if (rolesMap.containsKey(key)) {
                roleDevices = rolesMap.get(key);
                for (AudioDeviceAttributes device : devices) {
                    if (!roleDevices.contains(device)) {
                        appliedDevices.add(device);
                    }
                }
            } else {
                appliedDevices.addAll(devices);
            }
            if (appliedDevices.isEmpty()) {
                return AudioSystem.SUCCESS;
            }
            final int status = asi.deviceRoleAction(useCase, role, appliedDevices);
            if (status == AudioSystem.SUCCESS) {
                roleDevices.addAll(appliedDevices);
                rolesMap.put(key, roleDevices);
            }
            return status;
        }
    }

    private int removeDevicesRole(
            ArrayMap<Pair<Integer, Integer>, List<AudioDeviceAttributes>> rolesMap,
            AudioSystemInterface asi,
            int useCase, int role, @NonNull List<AudioDeviceAttributes> devices) {
        synchronized (rolesMap) {
            Pair<Integer, Integer> key = new Pair<>(useCase, role);
            if (!rolesMap.containsKey(key)) {
                // trying to remove a role for a device that wasn't set
                return AudioSystem.BAD_VALUE;
            }
            List<AudioDeviceAttributes> roleDevices = rolesMap.get(key);
            List<AudioDeviceAttributes> appliedDevices = new ArrayList<>();
            for (AudioDeviceAttributes device : devices) {
                if (roleDevices.contains(device)) {
                    appliedDevices.add(device);
                }
            }
            if (appliedDevices.isEmpty()) {
                return AudioSystem.SUCCESS;
            }
            final int status = asi.deviceRoleAction(useCase, role, appliedDevices);
            if (status == AudioSystem.SUCCESS) {
                roleDevices.removeAll(appliedDevices);
                if (roleDevices.isEmpty()) {
                    rolesMap.remove(key);
                } else {
                    rolesMap.put(key, roleDevices);
                }
            }
            return status;
        }
    }

    private int setDevicesRole(
            ArrayMap<Pair<Integer, Integer>, List<AudioDeviceAttributes>> rolesMap,
            AudioSystemInterface addOp,
            AudioSystemInterface clearOp,
            int useCase, int role, @NonNull List<AudioDeviceAttributes> devices) {
        synchronized (rolesMap) {
            Pair<Integer, Integer> key = new Pair<>(useCase, role);
            List<AudioDeviceAttributes> roleDevices = new ArrayList<>();
            List<AudioDeviceAttributes> appliedDevices = new ArrayList<>();

            if (rolesMap.containsKey(key)) {
                roleDevices = rolesMap.get(key);
                boolean equal = false;
                if (roleDevices.size() == devices.size()) {
                    roleDevices.retainAll(devices);
                    equal = roleDevices.size() == devices.size();
                }
                if (!equal) {
                    clearOp.deviceRoleAction(useCase, role, null);
                    roleDevices.clear();
                    appliedDevices.addAll(devices);
                }
            } else {
                appliedDevices.addAll(devices);
            }
            if (appliedDevices.isEmpty()) {
                return AudioSystem.SUCCESS;
            }
            final int status = addOp.deviceRoleAction(useCase, role, appliedDevices);
            if (status == AudioSystem.SUCCESS) {
                roleDevices.addAll(appliedDevices);
                rolesMap.put(key, roleDevices);
            }
            return status;
        }
    }

    private int clearDevicesRole(
            ArrayMap<Pair<Integer, Integer>, List<AudioDeviceAttributes>> rolesMap,
            AudioSystemInterface asi, int useCase, int role) {
        synchronized (rolesMap) {
            Pair<Integer, Integer> key = new Pair<>(useCase, role);
            if (!rolesMap.containsKey(key)) {
                // trying to clear a role for a device that wasn't set
                return AudioSystem.BAD_VALUE;
            }
            final int status = asi.deviceRoleAction(useCase, role, null);
            if (status == AudioSystem.SUCCESS) {
                rolesMap.remove(key);
            }
            return status;
        }
    }

    @GuardedBy("mDevicesLock")
    private void purgeDevicesRoles_l() {
        purgeRoles(mAppliedStrategyRolesInt, (s, r, d) -> {
            return mAudioSystem.removeDevicesRoleForStrategy(s, r, d); });
        purgeRoles(mAppliedPresetRolesInt, (p, r, d) -> {
            return mAudioSystem.removeDevicesRoleForCapturePreset(p, r, d); });
        reapplyExternalDevicesRoles();
    }

    @GuardedBy("mDevicesLock")
    private void purgeRoles(
            ArrayMap<Pair<Integer, Integer>, List<AudioDeviceAttributes>> rolesMap,
            AudioSystemInterface asi) {
        synchronized (rolesMap) {
            AudioDeviceInfo[] connectedDevices = AudioManager.getDevicesStatic(
                    AudioManager.GET_DEVICES_ALL);

            Iterator<Entry<Pair<Integer, Integer>, List<AudioDeviceAttributes>>> itRole =
                    rolesMap.entrySet().iterator();

            while (itRole.hasNext()) {
                Entry<Pair<Integer, Integer>, List<AudioDeviceAttributes>> entry =
                        itRole.next();
                Pair<Integer, Integer> keyRole = entry.getKey();
                Iterator<AudioDeviceAttributes> itDev = rolesMap.get(keyRole).iterator();
                while (itDev.hasNext()) {
                    AudioDeviceAttributes ada = itDev.next();

                    AudioDeviceInfo device = Stream.of(connectedDevices)
                            .filter(d -> d.getInternalType() == ada.getInternalType())
                            .filter(d -> (!isBluetoothDevice(d.getInternalType())
                                            || (d.getAddress().equals(ada.getAddress()))))
                            .findFirst()
                            .orElse(null);

                    if (device == null) {
                        if (AudioService.DEBUG_DEVICES) {
                            Slog.i(TAG, "purgeRoles() removing device: " + ada.toString()
                                    + ", for strategy: " + keyRole.first
                                    + " and role: " + keyRole.second);
                        }
                        asi.deviceRoleAction(keyRole.first, keyRole.second, Arrays.asList(ada));
                        itDev.remove();
                    }
                }
                if (rolesMap.get(keyRole).isEmpty()) {
                    itRole.remove();
                }
            }
        }
    }

//-----------------------------------------------------------------------

    /**
     * Check if a device is in the list of connected devices
     * @param device the device whose connection state is queried
     * @return true if connected
     */
    @GuardedBy("AudioDeviceBroker.this.mDeviceStateLock")
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
     * @param btDevice the corresponding Bluetooth device when relevant.
     * @return false if an error was reported by AudioSystem
     */
    /*package*/ boolean handleDeviceConnection(@NonNull AudioDeviceAttributes attributes,
                                               boolean connect, boolean isForTesting,
                                               @Nullable BluetoothDevice btDevice) {
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
        boolean status = false;
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
                mConnectedDevices.put(deviceKey, new DeviceInfo(device, deviceName, address));
                mDeviceBroker.postAccessoryPlugMediaUnmute(device);
                status = true;
            } else if (!connect && isConnected) {
                mAudioSystem.setDeviceConnectionState(attributes,
                        AudioSystem.DEVICE_STATE_UNAVAILABLE, AudioSystem.AUDIO_FORMAT_DEFAULT);
                // always remove even if disconnection failed
                mConnectedDevices.remove(deviceKey);
                mDeviceBroker.postCheckCommunicationDeviceRemoval(attributes);
                status = true;
            }
            if (status) {
                if (AudioSystem.isBluetoothScoDevice(device)) {
                    updateBluetoothPreferredModes_l(connect ? btDevice : null /*connectedDevice*/);
                    if (!connect) {
                        purgeDevicesRoles_l();
                    } else {
                        addAudioDeviceInInventoryIfNeeded(attributes);
                    }
                }
                mmi.set(MediaMetrics.Property.STATE, MediaMetrics.Value.CONNECTED).record();
            } else {
                Log.w(TAG, "handleDeviceConnection() failed, deviceKey=" + deviceKey
                        + ", deviceSpec=" + di + ", connect=" + connect);
                mmi.set(MediaMetrics.Property.STATE, MediaMetrics.Value.DISCONNECTED).record();
            }
        }
        return status;
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
                    .set(MediaMetrics.Property.EVENT, "disconnectA2dp")
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
                    .set(MediaMetrics.Property.EVENT, "disconnectA2dpSink")
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
                    .set(MediaMetrics.Property.EVENT, "disconnectHearingAid")
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

    @GuardedBy("AudioDeviceBroker.this.mDeviceStateLock")
    /*package*/ void onBtProfileDisconnected(int profile) {
        switch (profile) {
            case BluetoothProfile.HEADSET:
                disconnectHeadset();
                break;
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
                    .set(MediaMetrics.Property.EVENT, "disconnectLeAudio")
                    .record();
            if (toRemove.size() > 0) {
                final int delay = checkSendBecomingNoisyIntentInt(device,
                        AudioService.CONNECTION_STATE_DISCONNECTED,
                        AudioSystem.DEVICE_NONE);
                toRemove.stream().forEach(deviceAddress ->
                        makeLeAudioDeviceUnavailableLater(deviceAddress, device, delay)
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

    @GuardedBy("AudioDeviceBroker.this.mDeviceStateLock")
    private void disconnectHeadset() {
        boolean disconnect = false;
        synchronized (mDevicesLock) {
            for (DeviceInfo di : mConnectedDevices.values()) {
                if (AudioSystem.isBluetoothScoDevice(di.mDeviceType)) {
                    // There is only one HFP active device and setting the active
                    // device to null will disconnect both in and out devices
                    disconnect = true;
                    break;
                }
            }
        }
        if (disconnect) {
            mDeviceBroker.onSetBtScoActiveDevice(null);
        }
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

    /**
     * Set a Bluetooth device to active.
     */
    @GuardedBy("AudioDeviceBroker.this.mDeviceStateLock")
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
                Log.i(TAG, "setBluetoothActiveDevice " + info.toString() + " delay(ms): " + delay);
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
    private void makeA2dpDeviceAvailable(AudioDeviceBroker.BtDeviceInfo btInfo,
                                         @AudioSystem.AudioFormatNativeEnumForBtCodec int codec,
                                         String eventSource) {
        final String address = btInfo.mDevice.getAddress();
        final String name = BtHelper.getName(btInfo.mDevice);

        // enable A2DP before notifying A2DP connection to avoid unnecessary processing in
        // audio policy manager
        mDeviceBroker.setBluetoothA2dpOnInt(true, true /*fromA2dp*/, eventSource);
        // at this point there could be another A2DP device already connected in APM, but it
        // doesn't matter as this new one will overwrite the previous one
        AudioDeviceAttributes ada = new AudioDeviceAttributes(
                AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, address, name);
        final int res = mAudioSystem.setDeviceConnectionState(ada,
                AudioSystem.DEVICE_STATE_AVAILABLE, codec);

        // TODO: log in MediaMetrics once distinction between connection failure and
        // double connection is made.
        if (res != AudioSystem.AUDIO_STATUS_OK) {
            AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(
                    "APM failed to make available A2DP device addr=" + address
                            + " error=" + res).printLog(TAG));
            // TODO: connection failed, stop here
            // TODO: return;
        } else {
            AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(
                    "A2DP device addr=" + address + " now available").printLog(TAG));
        }

        // Reset A2DP suspend state each time a new sink is connected
        mDeviceBroker.clearA2dpSuspended(true /* internalOnly */);

        // The convention for head tracking sensors associated with A2DP devices is to
        // use a UUID derived from the MAC address as follows:
        //   time_low = 0, time_mid = 0, time_hi = 0, clock_seq = 0, node = MAC Address
        UUID sensorUuid = UuidUtils.uuidFromAudioDeviceAttributes(ada);
        final DeviceInfo di = new DeviceInfo(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, name,
                address, codec, sensorUuid);
        final String diKey = di.getKey();
        mConnectedDevices.put(diKey, di);
        // on a connection always overwrite the device seen by AudioPolicy, see comment above when
        // calling AudioSystem
        mApmConnectedDevices.put(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, diKey);

        mDeviceBroker.postAccessoryPlugMediaUnmute(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP);
        setCurrentAudioRouteNameIfPossible(name, true /*fromA2dp*/);

        updateBluetoothPreferredModes_l(btInfo.mDevice /*connectedDevice*/);
        addAudioDeviceInInventoryIfNeeded(ada);
    }

    static final int[] CAPTURE_PRESETS = new int[] {AudioSource.MIC, AudioSource.CAMCORDER,
            AudioSource.VOICE_RECOGNITION, AudioSource.VOICE_COMMUNICATION,
            AudioSource.UNPROCESSED, AudioSource.VOICE_PERFORMANCE, AudioSource.HOTWORD};

    // reflects system property persist.bluetooth.enable_dual_mode_audio
    final boolean mBluetoothDualModeEnabled;
    /**
     * Goes over all connected Bluetooth devices and set the audio policy device role to DISABLED
     * or not according to their own and other devices modes.
     * The top priority is given to LE devices, then SCO ,then A2DP.
     */
    @GuardedBy("mDevicesLock")
    private void applyConnectedDevicesRoles_l() {
        if (!mBluetoothDualModeEnabled) {
            return;
        }
        DeviceInfo leOutDevice =
                getFirstConnectedDeviceOfTypes(AudioSystem.DEVICE_OUT_ALL_BLE_SET);
        DeviceInfo leInDevice =
                getFirstConnectedDeviceOfTypes(AudioSystem.DEVICE_IN_ALL_BLE_SET);
        DeviceInfo a2dpDevice =
                getFirstConnectedDeviceOfTypes(AudioSystem.DEVICE_OUT_ALL_A2DP_SET);
        DeviceInfo scoOutDevice =
                getFirstConnectedDeviceOfTypes(AudioSystem.DEVICE_OUT_ALL_SCO_SET);
        DeviceInfo scoInDevice =
                getFirstConnectedDeviceOfTypes(AudioSystem.DEVICE_IN_ALL_SCO_SET);
        boolean disableA2dp = (leOutDevice != null && leOutDevice.isOutputOnlyModeEnabled());
        boolean disableSco = (leOutDevice != null && leOutDevice.isDuplexModeEnabled())
                || (leInDevice != null && leInDevice.isDuplexModeEnabled());
        AudioDeviceAttributes communicationDevice =
                mDeviceBroker.mActiveCommunicationDevice == null
                        ? null : ((mDeviceBroker.isInCommunication()
                                    && mDeviceBroker.mActiveCommunicationDevice != null)
                            ? new AudioDeviceAttributes(mDeviceBroker.mActiveCommunicationDevice)
                            : null);

        if (AudioService.DEBUG_DEVICES) {
            Log.i(TAG, "applyConnectedDevicesRoles_l\n - leOutDevice: " + leOutDevice
                    + "\n - leInDevice: " + leInDevice
                    + "\n - a2dpDevice: " + a2dpDevice
                    + "\n - scoOutDevice: " + scoOutDevice
                    + "\n - scoInDevice: " + scoInDevice
                    + "\n - disableA2dp: " + disableA2dp
                    + ", disableSco: " + disableSco);
        }

        for (DeviceInfo di : mConnectedDevices.values()) {
            if (!isBluetoothDevice(di.mDeviceType)) {
                continue;
            }
            AudioDeviceAttributes ada =
                    new AudioDeviceAttributes(di.mDeviceType, di.mDeviceAddress, di.mDeviceName);
            if (AudioService.DEBUG_DEVICES) {
                Log.i(TAG, "  + checking Device: " + ada);
            }
            if (ada.equalTypeAddress(communicationDevice)) {
                continue;
            }

            if (AudioSystem.isBluetoothOutDevice(di.mDeviceType)) {
                for (AudioProductStrategy strategy : mStrategies) {
                    boolean disable = false;
                    if (strategy.getId() == mDeviceBroker.mCommunicationStrategyId) {
                        if (AudioSystem.isBluetoothScoDevice(di.mDeviceType)) {
                            disable = disableSco || !di.isDuplexModeEnabled();
                        } else if (AudioSystem.isBluetoothLeDevice(di.mDeviceType)) {
                            disable = !di.isDuplexModeEnabled();
                        }
                    } else {
                        if (AudioSystem.isBluetoothA2dpOutDevice(di.mDeviceType)) {
                            disable = disableA2dp || !di.isOutputOnlyModeEnabled();
                        } else if (AudioSystem.isBluetoothScoDevice(di.mDeviceType)) {
                            disable = disableSco || !di.isOutputOnlyModeEnabled();
                        } else if (AudioSystem.isBluetoothLeDevice(di.mDeviceType)) {
                            disable = !di.isOutputOnlyModeEnabled();
                        }
                    }
                    if (AudioService.DEBUG_DEVICES) {
                        Log.i(TAG, "     - strategy: " + strategy.getId()
                                + ", disable: " + disable);
                    }
                    if (disable) {
                        addDevicesRoleForStrategy(strategy.getId(),
                                AudioSystem.DEVICE_ROLE_DISABLED,
                                Arrays.asList(ada), true /* internal */);
                    } else {
                        removeDevicesRoleForStrategy(strategy.getId(),
                                AudioSystem.DEVICE_ROLE_DISABLED,
                                Arrays.asList(ada), true /* internal */);
                    }
                }
            }
            if (AudioSystem.isBluetoothInDevice(di.mDeviceType)) {
                for (int capturePreset : CAPTURE_PRESETS) {
                    boolean disable = false;
                    if (AudioSystem.isBluetoothScoDevice(di.mDeviceType)) {
                        disable = disableSco || !di.isDuplexModeEnabled();
                    } else if (AudioSystem.isBluetoothLeDevice(di.mDeviceType)) {
                        disable = !di.isDuplexModeEnabled();
                    }
                    if (AudioService.DEBUG_DEVICES) {
                        Log.i(TAG, "      - capturePreset: " + capturePreset
                                + ", disable: " + disable);
                    }
                    if (disable) {
                        addDevicesRoleForCapturePresetInt(capturePreset,
                                AudioSystem.DEVICE_ROLE_DISABLED, Arrays.asList(ada));
                    } else {
                        removeDevicesRoleForCapturePresetInt(capturePreset,
                                AudioSystem.DEVICE_ROLE_DISABLED, Arrays.asList(ada));
                    }
                }
            }
        }
    }

    /* package */ void applyConnectedDevicesRoles() {
        synchronized (mDevicesLock) {
            applyConnectedDevicesRoles_l();
        }
    }

    @GuardedBy("mDevicesLock")
    int checkProfileIsConnected(int profile) {
        switch (profile) {
            case BluetoothProfile.HEADSET:
                if (getFirstConnectedDeviceOfTypes(
                        AudioSystem.DEVICE_OUT_ALL_SCO_SET) != null
                        || getFirstConnectedDeviceOfTypes(
                                AudioSystem.DEVICE_IN_ALL_SCO_SET) != null) {
                    return profile;
                }
                break;
            case BluetoothProfile.A2DP:
                if (getFirstConnectedDeviceOfTypes(
                        AudioSystem.DEVICE_OUT_ALL_A2DP_SET) != null) {
                    return profile;
                }
                break;
            case BluetoothProfile.LE_AUDIO:
            case BluetoothProfile.LE_AUDIO_BROADCAST:
                if (getFirstConnectedDeviceOfTypes(
                        AudioSystem.DEVICE_OUT_ALL_BLE_SET) != null
                        || getFirstConnectedDeviceOfTypes(
                                AudioSystem.DEVICE_IN_ALL_BLE_SET) != null) {
                    return profile;
                }
                break;
            default:
                break;
        }
        return 0;
    }

    @GuardedBy("mDevicesLock")
    private void updateBluetoothPreferredModes_l(BluetoothDevice connectedDevice) {
        if (!mBluetoothDualModeEnabled) {
            return;
        }
        HashSet<String> processedAddresses = new HashSet<>(0);
        for (DeviceInfo di : mConnectedDevices.values()) {
            if (!isBluetoothDevice(di.mDeviceType)
                    || processedAddresses.contains(di.mDeviceAddress)) {
                continue;
            }
            Bundle preferredProfiles = BtHelper.getPreferredAudioProfiles(di.mDeviceAddress);
            if (AudioService.DEBUG_DEVICES) {
                Log.i(TAG, "updateBluetoothPreferredModes_l processing device address: "
                        + di.mDeviceAddress + ", preferredProfiles: " + preferredProfiles);
            }
            for (DeviceInfo di2 : mConnectedDevices.values()) {
                if (!isBluetoothDevice(di2.mDeviceType)
                        || !di.mDeviceAddress.equals(di2.mDeviceAddress)) {
                    continue;
                }
                int profile = BtHelper.getProfileFromType(di2.mDeviceType);
                if (profile == 0) {
                    continue;
                }
                int preferredProfile = checkProfileIsConnected(
                        preferredProfiles.getInt(BluetoothAdapter.AUDIO_MODE_DUPLEX));
                if (preferredProfile == profile || preferredProfile == 0) {
                    di2.setModeEnabled(BluetoothAdapter.AUDIO_MODE_DUPLEX);
                } else {
                    di2.setModeDisabled(BluetoothAdapter.AUDIO_MODE_DUPLEX);
                }
                preferredProfile = checkProfileIsConnected(
                        preferredProfiles.getInt(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY));
                if (preferredProfile == profile || preferredProfile == 0) {
                    di2.setModeEnabled(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY);
                } else {
                    di2.setModeDisabled(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY);
                }
            }
            processedAddresses.add(di.mDeviceAddress);
        }
        applyConnectedDevicesRoles_l();
        if (connectedDevice != null) {
            mDeviceBroker.postNotifyPreferredAudioProfileApplied(connectedDevice);
        }
    }

    @GuardedBy("mDevicesLock")
    private void makeA2dpDeviceUnavailableNow(String address, int codec) {
        MediaMetrics.Item mmi = new MediaMetrics.Item(mMetricsId + "a2dp." + address)
                .set(MediaMetrics.Property.ENCODING, AudioSystem.audioFormatToString(codec))
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
            AudioService.sDeviceLogger.enqueue((new EventLogger.StringEvent(
                    "A2DP device " + address + " made unavailable, was not used")).printLog(TAG));
            mmi.set(MediaMetrics.Property.EARLY_RETURN,
                    "A2DP device made unavailable, was not used")
                    .record();
            return;
        }

        // device to remove was visible by APM, update APM
        mDeviceBroker.clearAvrcpAbsoluteVolumeSupported();
        AudioDeviceAttributes ada = new AudioDeviceAttributes(
                AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, address);
        final int res = mAudioSystem.setDeviceConnectionState(ada,
                AudioSystem.DEVICE_STATE_UNAVAILABLE, codec);

        if (res != AudioSystem.AUDIO_STATUS_OK) {
            AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(
                    "APM failed to make unavailable A2DP device addr=" + address
                            + " error=" + res).printLog(TAG));
            // TODO:  failed to disconnect, stop here
            // TODO: return;
        } else {
            AudioService.sDeviceLogger.enqueue((new EventLogger.StringEvent(
                    "A2DP device addr=" + address + " made unavailable")).printLog(TAG));
        }
        mApmConnectedDevices.remove(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP);

        // Remove A2DP routes as well
        setCurrentAudioRouteNameIfPossible(null, true /*fromA2dp*/);
        mmi.record();
        updateBluetoothPreferredModes_l(null /*connectedDevice*/);
        purgeDevicesRoles_l();
        mDeviceBroker.postCheckCommunicationDeviceRemoval(ada);
    }

    @GuardedBy("mDevicesLock")
    private void makeA2dpDeviceUnavailableLater(String address, int delayMs) {
        // prevent any activity on the A2DP audio output to avoid unwanted
        // reconnection of the sink.
        mDeviceBroker.setA2dpSuspended(
                true /*enable*/, true /*internal*/, "makeA2dpDeviceUnavailableLater");
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
                new DeviceInfo(AudioSystem.DEVICE_IN_BLUETOOTH_A2DP, "", address));
    }

    @GuardedBy("mDevicesLock")
    private void makeA2dpSrcUnavailable(String address) {
        AudioDeviceAttributes ada = new AudioDeviceAttributes(
                AudioSystem.DEVICE_IN_BLUETOOTH_A2DP, address);
        mAudioSystem.setDeviceConnectionState(ada,
                AudioSystem.DEVICE_STATE_UNAVAILABLE,
                AudioSystem.AUDIO_FORMAT_DEFAULT);
        mConnectedDevices.remove(
                DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_IN_BLUETOOTH_A2DP, address));
        mDeviceBroker.postCheckCommunicationDeviceRemoval(ada);
    }

    @GuardedBy("mDevicesLock")
    private void makeHearingAidDeviceAvailable(
            String address, String name, int streamType, String eventSource) {
        final int hearingAidVolIndex = mDeviceBroker.getVssVolumeForDevice(streamType,
                AudioSystem.DEVICE_OUT_HEARING_AID);
        mDeviceBroker.postSetHearingAidVolumeIndex(hearingAidVolIndex, streamType);
        AudioDeviceAttributes ada = new AudioDeviceAttributes(
                AudioSystem.DEVICE_OUT_HEARING_AID, address, name);
        mAudioSystem.setDeviceConnectionState(ada,
                AudioSystem.DEVICE_STATE_AVAILABLE,
                AudioSystem.AUDIO_FORMAT_DEFAULT);
        mConnectedDevices.put(
                DeviceInfo.makeDeviceListKey(AudioSystem.DEVICE_OUT_HEARING_AID, address),
                new DeviceInfo(AudioSystem.DEVICE_OUT_HEARING_AID, name, address));
        mDeviceBroker.postAccessoryPlugMediaUnmute(AudioSystem.DEVICE_OUT_HEARING_AID);
        mDeviceBroker.postApplyVolumeOnDevice(streamType,
                AudioSystem.DEVICE_OUT_HEARING_AID, "makeHearingAidDeviceAvailable");
        setCurrentAudioRouteNameIfPossible(name, false /*fromA2dp*/);
        addAudioDeviceInInventoryIfNeeded(ada);
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
        AudioDeviceAttributes ada = new AudioDeviceAttributes(
                AudioSystem.DEVICE_OUT_HEARING_AID, address);
        mAudioSystem.setDeviceConnectionState(ada,
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
        mDeviceBroker.postCheckCommunicationDeviceRemoval(ada);
    }

    /**
     * Returns whether a device of type DEVICE_OUT_HEARING_AID is connected.
     * Visibility by APM plays no role
     * @return true if a DEVICE_OUT_HEARING_AID is connected, false otherwise.
     */
    boolean isHearingAidConnected() {
        return getFirstConnectedDeviceOfTypes(
                Sets.newHashSet(AudioSystem.DEVICE_OUT_HEARING_AID)) != null;
    }

    /**
     * Returns a DeviceInfo for the first connected device matching one of the supplied types
     */
    private DeviceInfo getFirstConnectedDeviceOfTypes(Set<Integer> internalTypes) {
        List<DeviceInfo> devices = getConnectedDevicesOfTypes(internalTypes);
        return devices.isEmpty() ? null : devices.get(0);
    }

    /**
     * Returns a list of connected devices matching one of the supplied types
     */
    private List<DeviceInfo> getConnectedDevicesOfTypes(Set<Integer> internalTypes) {
        ArrayList<DeviceInfo> devices = new ArrayList<>();
        synchronized (mDevicesLock) {
            for (DeviceInfo di : mConnectedDevices.values()) {
                if (internalTypes.contains(di.mDeviceType)) {
                    devices.add(di);
                }
            }
        }
        return devices;
    }

    /* package */ AudioDeviceAttributes getDeviceOfType(int type) {
        DeviceInfo di = getFirstConnectedDeviceOfTypes(Sets.newHashSet(type));
        return di == null ? null : new AudioDeviceAttributes(
                    di.mDeviceType, di.mDeviceAddress, di.mDeviceName);
    }

    @GuardedBy("mDevicesLock")
    private void makeLeAudioDeviceAvailable(
            AudioDeviceBroker.BtDeviceInfo btInfo, int streamType, String eventSource) {
        final int volumeIndex = btInfo.mVolume == -1 ? -1 : btInfo.mVolume * 10;
        final int device = btInfo.mAudioSystemDevice;

        if (device != AudioSystem.DEVICE_NONE) {
            final String address = btInfo.mDevice.getAddress();
            String name = BtHelper.getName(btInfo.mDevice);

            // The BT Stack does not provide a name for LE Broadcast devices
            if (device == AudioSystem.DEVICE_OUT_BLE_BROADCAST && name.equals("")) {
                name = "Broadcast";
            }

            /* Audio Policy sees Le Audio similar to A2DP. Let's make sure
             * AUDIO_POLICY_FORCE_NO_BT_A2DP is not set
             */
            mDeviceBroker.setBluetoothA2dpOnInt(true, false /*fromA2dp*/, eventSource);

            AudioDeviceAttributes ada = new AudioDeviceAttributes(device, address, name);
            final int res = AudioSystem.setDeviceConnectionState(ada,
                    AudioSystem.DEVICE_STATE_AVAILABLE,  AudioSystem.AUDIO_FORMAT_DEFAULT);
            if (res != AudioSystem.AUDIO_STATUS_OK) {
                AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(
                        "APM failed to make available LE Audio device addr=" + address
                                + " error=" + res).printLog(TAG));
                // TODO: connection failed, stop here
                // TODO: return;
            } else {
                AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(
                        "LE Audio device addr=" + address + " now available").printLog(TAG));
            }
            // Reset LEA suspend state each time a new sink is connected
            mDeviceBroker.clearLeAudioSuspended(true /* internalOnly */);

            UUID sensorUuid = UuidUtils.uuidFromAudioDeviceAttributes(ada);
            mConnectedDevices.put(DeviceInfo.makeDeviceListKey(device, address),
                    new DeviceInfo(device, name, address, AudioSystem.AUDIO_FORMAT_DEFAULT,
                            sensorUuid));
            mDeviceBroker.postAccessoryPlugMediaUnmute(device);
            setCurrentAudioRouteNameIfPossible(name, /*fromA2dp=*/false);
            addAudioDeviceInInventoryIfNeeded(ada);
        }

        if (streamType == AudioSystem.STREAM_DEFAULT) {
            // No need to update volume for input devices
            return;
        }

        final int leAudioVolIndex = (volumeIndex == -1)
                ? mDeviceBroker.getVssVolumeForDevice(streamType, device)
                : volumeIndex;
        final int maxIndex = mDeviceBroker.getMaxVssVolumeForStream(streamType);
        mDeviceBroker.postSetLeAudioVolumeIndex(leAudioVolIndex, maxIndex, streamType);
        mDeviceBroker.postApplyVolumeOnDevice(streamType, device, "makeLeAudioDeviceAvailable");

        updateBluetoothPreferredModes_l(btInfo.mDevice /*connectedDevice*/);
    }

    @GuardedBy("mDevicesLock")
    private void makeLeAudioDeviceUnavailableNow(String address, int device) {
        AudioDeviceAttributes ada = null;
        if (device != AudioSystem.DEVICE_NONE) {
            ada = new AudioDeviceAttributes(device, address);
            final int res = AudioSystem.setDeviceConnectionState(ada,
                    AudioSystem.DEVICE_STATE_UNAVAILABLE,
                    AudioSystem.AUDIO_FORMAT_DEFAULT);

            if (res != AudioSystem.AUDIO_STATUS_OK) {
                AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(
                        "APM failed to make unavailable LE Audio device addr=" + address
                                + " error=" + res).printLog(TAG));
                // TODO:  failed to disconnect, stop here
                // TODO: return;
            } else {
                AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(
                        "LE Audio device addr=" + address + " made unavailable").printLog(TAG));
            }
            mConnectedDevices.remove(DeviceInfo.makeDeviceListKey(device, address));
        }

        setCurrentAudioRouteNameIfPossible(null, false /*fromA2dp*/);
        updateBluetoothPreferredModes_l(null /*connectedDevice*/);
        purgeDevicesRoles_l();
        if (ada != null) {
            mDeviceBroker.postCheckCommunicationDeviceRemoval(ada);
        }
    }

    @GuardedBy("mDevicesLock")
    private void makeLeAudioDeviceUnavailableLater(String address, int device, int delayMs) {
        // prevent any activity on the LEA output to avoid unwanted
        // reconnection of the sink.
        mDeviceBroker.setLeAudioSuspended(
                true /*enable*/, true /*internal*/, "makeLeAudioDeviceUnavailableLater");
        // the device will be made unavailable later, so consider it disconnected right away
        mConnectedDevices.remove(DeviceInfo.makeDeviceListKey(device, address));
        // send the delayed message to make the device unavailable later
        mDeviceBroker.setLeAudioTimeout(address, device, delayMs);
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
                AudioService.sDeviceLogger.enqueue((new EventLogger.StringEvent(
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
                if (!((Boolean) mPrefDevDispatchers.getBroadcastCookie(i))) {
                    devices = mDeviceBroker.anonymizeAudioDeviceAttributesListUnchecked(devices);
                }
                mPrefDevDispatchers.getBroadcastItem(i).dispatchPrefDevicesChanged(
                        strategy, devices);
            } catch (RemoteException e) {
            }
        }
        mPrefDevDispatchers.finishBroadcast();
    }

    private void dispatchNonDefaultDevice(int strategy,
                                          @NonNull List<AudioDeviceAttributes> devices) {
        final int nbDispatchers = mNonDefDevDispatchers.beginBroadcast();
        for (int i = 0; i < nbDispatchers; i++) {
            try {
                if (!((Boolean) mNonDefDevDispatchers.getBroadcastCookie(i))) {
                    devices = mDeviceBroker.anonymizeAudioDeviceAttributesListUnchecked(devices);
                }
                mNonDefDevDispatchers.getBroadcastItem(i).dispatchNonDefDevicesChanged(
                        strategy, devices);
            } catch (RemoteException e) {
            }
        }
        mNonDefDevDispatchers.finishBroadcast();
    }

    private void dispatchDevicesRoleForCapturePreset(
            int capturePreset, int role, @NonNull List<AudioDeviceAttributes> devices) {
        final int nbDispatchers = mDevRoleCapturePresetDispatchers.beginBroadcast();
        for (int i = 0; i < nbDispatchers; ++i) {
            try {
                if (!((Boolean) mDevRoleCapturePresetDispatchers.getBroadcastCookie(i))) {
                    devices = mDeviceBroker.anonymizeAudioDeviceAttributesListUnchecked(devices);
                }
                mDevRoleCapturePresetDispatchers.getBroadcastItem(i).dispatchDevicesRoleChanged(
                        capturePreset, role, devices);
            } catch (RemoteException e) {
            }
        }
        mDevRoleCapturePresetDispatchers.finishBroadcast();
    }

    @Nullable UUID getDeviceSensorUuid(AudioDeviceAttributes device) {
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

    /*package*/ String getDeviceSettings() {
        int deviceCatalogSize = 0;
        synchronized (mDeviceInventoryLock) {
            deviceCatalogSize = mDeviceInventory.size();

            final StringBuilder settingsBuilder = new StringBuilder(
                            deviceCatalogSize * AdiDeviceState.getPeristedMaxSize());

            Iterator<AdiDeviceState> iterator = mDeviceInventory.values().iterator();
            if (iterator.hasNext()) {
                settingsBuilder.append(iterator.next().toPersistableString());
            }
            while (iterator.hasNext()) {
                settingsBuilder.append(SETTING_DEVICE_SEPARATOR_CHAR);
                settingsBuilder.append(iterator.next().toPersistableString());
            }
            return settingsBuilder.toString();
        }
    }

    /*package*/ void setDeviceSettings(String settings) {
        clearDeviceInventory();
        String[] devSettings = TextUtils.split(Objects.requireNonNull(settings),
                SETTING_DEVICE_SEPARATOR);
        // small list, not worth overhead of Arrays.stream(devSettings)
        for (String setting : devSettings) {
            AdiDeviceState devState = AdiDeviceState.fromPersistedString(setting);
            // Note if the device is not compatible with spatialization mode or the device
            // type is not canonical, it will be ignored in {@link SpatializerHelper}.
            if (devState != null) {
                addOrUpdateDeviceSAStateInInventory(devState);
                addOrUpdateAudioDeviceCategoryInInventory(devState);
            }
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
        for (DeviceInfo di : getConnectedDevicesOfTypes(
                Sets.newHashSet(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP))) {
            if (di.mDeviceAddress.equals(device.getAddress())) {
                return true;
            }
        }
        return false;
    }
}
