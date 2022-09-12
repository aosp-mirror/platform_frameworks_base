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
import android.annotation.Nullable;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.media.AudioDeviceAttributes;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.BluetoothProfileConnectionInfo;
import android.os.Binder;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * @hide
 * Class to encapsulate all communication with Bluetooth services
 */
public class BtHelper {

    private static final String TAG = "AS.BtHelper";

    private static final int SOURCE_CODEC_TYPE_OPUS = 6; // TODO remove in U

    private final @NonNull AudioDeviceBroker mDeviceBroker;

    BtHelper(@NonNull AudioDeviceBroker broker) {
        mDeviceBroker = broker;
    }

    // BluetoothHeadset API to control SCO connection
    private @Nullable BluetoothHeadset mBluetoothHeadset;

    // Bluetooth headset device
    private @Nullable BluetoothDevice mBluetoothHeadsetDevice;

    private @Nullable BluetoothHearingAid mHearingAid;

    private @Nullable BluetoothLeAudio mLeAudio;

    // Reference to BluetoothA2dp to query for AbsoluteVolume.
    private @Nullable BluetoothA2dp mA2dp;

    // If absolute volume is supported in AVRCP device
    private boolean mAvrcpAbsVolSupported = false;

    // Current connection state indicated by bluetooth headset
    private int mScoConnectionState;

    // Indicate if SCO audio connection is currently active and if the initiator is
    // audio service (internal) or bluetooth headset (external)
    private int mScoAudioState;

    // Indicates the mode used for SCO audio connection. The mode is virtual call if the request
    // originated from an app targeting an API version before JB MR2 and raw audio after that.
    private int mScoAudioMode;

    // SCO audio state is not active
    private static final int SCO_STATE_INACTIVE = 0;
    // SCO audio activation request waiting for headset service to connect
    private static final int SCO_STATE_ACTIVATE_REQ = 1;
    // SCO audio state is active due to an action in BT handsfree (either voice recognition or
    // in call audio)
    private static final int SCO_STATE_ACTIVE_EXTERNAL = 2;
    // SCO audio state is active or starting due to a request from AudioManager API
    private static final int SCO_STATE_ACTIVE_INTERNAL = 3;
    // SCO audio deactivation request waiting for headset service to connect
    private static final int SCO_STATE_DEACTIVATE_REQ = 4;
    // SCO audio deactivation in progress, waiting for Bluetooth audio intent
    private static final int SCO_STATE_DEACTIVATING = 5;

    // SCO audio mode is undefined
    /*package*/  static final int SCO_MODE_UNDEFINED = -1;
    // SCO audio mode is virtual voice call (BluetoothHeadset.startScoUsingVirtualVoiceCall())
    /*package*/  static final int SCO_MODE_VIRTUAL_CALL = 0;
    // SCO audio mode is Voice Recognition (BluetoothHeadset.startVoiceRecognition())
    private static final int SCO_MODE_VR = 2;
    // max valid SCO audio mode values
    private static final int SCO_MODE_MAX = 2;

    private static final int BT_HEARING_AID_GAIN_MIN = -128;
    private static final int BT_LE_AUDIO_MIN_VOL = 0;
    private static final int BT_LE_AUDIO_MAX_VOL = 255;

    /**
     * Returns a string representation of the scoAudioMode.
     */
    public static String scoAudioModeToString(int scoAudioMode) {
        switch (scoAudioMode) {
            case SCO_MODE_UNDEFINED:
                return "SCO_MODE_UNDEFINED";
            case SCO_MODE_VIRTUAL_CALL:
                return "SCO_MODE_VIRTUAL_CALL";
            case SCO_MODE_VR:
                return "SCO_MODE_VR";
            default:
                return "SCO_MODE_(" + scoAudioMode + ")";
        }
    }

    /**
     * Returns a string representation of the scoAudioState.
     */
    public static String scoAudioStateToString(int scoAudioState) {
        switch (scoAudioState) {
            case SCO_STATE_INACTIVE:
                return "SCO_STATE_INACTIVE";
            case SCO_STATE_ACTIVATE_REQ:
                return "SCO_STATE_ACTIVATE_REQ";
            case SCO_STATE_ACTIVE_EXTERNAL:
                return "SCO_STATE_ACTIVE_EXTERNAL";
            case SCO_STATE_ACTIVE_INTERNAL:
                return "SCO_STATE_ACTIVE_INTERNAL";
            case SCO_STATE_DEACTIVATING:
                return "SCO_STATE_DEACTIVATING";
            default:
                return "SCO_STATE_(" + scoAudioState + ")";
        }
    }

    //----------------------------------------------------------------------
    /*package*/ static class BluetoothA2dpDeviceInfo {
        private final @NonNull BluetoothDevice mBtDevice;
        private final int mVolume;
        private final @AudioSystem.AudioFormatNativeEnumForBtCodec int mCodec;

        BluetoothA2dpDeviceInfo(@NonNull BluetoothDevice btDevice) {
            this(btDevice, -1, AudioSystem.AUDIO_FORMAT_DEFAULT);
        }

        BluetoothA2dpDeviceInfo(@NonNull BluetoothDevice btDevice, int volume, int codec) {
            mBtDevice = btDevice;
            mVolume = volume;
            mCodec = codec;
        }

        public @NonNull BluetoothDevice getBtDevice() {
            return mBtDevice;
        }

        public int getVolume() {
            return mVolume;
        }

        public @AudioSystem.AudioFormatNativeEnumForBtCodec int getCodec() {
            return mCodec;
        }

        // redefine equality op so we can match messages intended for this device
        @Override
        public boolean equals(Object o) {
            if (o == null) {
                return false;
            }
            if (this == o) {
                return true;
            }
            if (o instanceof BluetoothA2dpDeviceInfo) {
                return mBtDevice.equals(((BluetoothA2dpDeviceInfo) o).getBtDevice());
            }
            return false;
        }


    }

    // A2DP device events
    /*package*/ static final int EVENT_DEVICE_CONFIG_CHANGE = 0;
    /*package*/ static final int EVENT_ACTIVE_DEVICE_CHANGE = 1;

    /*package*/ static String a2dpDeviceEventToString(int event) {
        switch (event) {
            case EVENT_DEVICE_CONFIG_CHANGE: return "DEVICE_CONFIG_CHANGE";
            case EVENT_ACTIVE_DEVICE_CHANGE: return "ACTIVE_DEVICE_CHANGE";
            default:
                return new String("invalid event:" + event);
        }
    }

    /*package*/ @NonNull static String getName(@NonNull BluetoothDevice device) {
        final String deviceName = device.getName();
        if (deviceName == null) {
            return "";
        }
        return deviceName;
    }

    //----------------------------------------------------------------------
    // Interface for AudioDeviceBroker

    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized void onSystemReady() {
        mScoConnectionState = android.media.AudioManager.SCO_AUDIO_STATE_ERROR;
        resetBluetoothSco();
        getBluetoothHeadset();

        //FIXME: this is to maintain compatibility with deprecated intent
        // AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED. Remove when appropriate.
        Intent newIntent = new Intent(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED);
        newIntent.putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
        sendStickyBroadcastToAll(newIntent);

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            adapter.getProfileProxy(mDeviceBroker.getContext(),
                    mBluetoothProfileServiceListener, BluetoothProfile.A2DP);
            adapter.getProfileProxy(mDeviceBroker.getContext(),
                    mBluetoothProfileServiceListener, BluetoothProfile.HEARING_AID);
            adapter.getProfileProxy(mDeviceBroker.getContext(),
                    mBluetoothProfileServiceListener, BluetoothProfile.LE_AUDIO);
        }
    }

    /*package*/ synchronized void onAudioServerDiedRestoreA2dp() {
        final int forMed = mDeviceBroker.getBluetoothA2dpEnabled()
                ? AudioSystem.FORCE_NONE : AudioSystem.FORCE_NO_BT_A2DP;
        mDeviceBroker.setForceUse_Async(AudioSystem.FOR_MEDIA, forMed, "onAudioServerDied()");
    }

    /*package*/ synchronized boolean isAvrcpAbsoluteVolumeSupported() {
        return (mA2dp != null && mAvrcpAbsVolSupported);
    }

    /*package*/ synchronized void setAvrcpAbsoluteVolumeSupported(boolean supported) {
        mAvrcpAbsVolSupported = supported;
        Log.i(TAG, "setAvrcpAbsoluteVolumeSupported supported=" + supported);
    }

    /*package*/ synchronized void setAvrcpAbsoluteVolumeIndex(int index) {
        if (mA2dp == null) {
            if (AudioService.DEBUG_VOL) {
                AudioService.sVolumeLogger.log(new AudioEventLogger.StringEvent(
                        "setAvrcpAbsoluteVolumeIndex: bailing due to null mA2dp").printLog(TAG));
                return;
            }
        }
        if (!mAvrcpAbsVolSupported) {
            AudioService.sVolumeLogger.log(new AudioEventLogger.StringEvent(
                    "setAvrcpAbsoluteVolumeIndex: abs vol not supported ").printLog(TAG));
            return;
        }
        if (AudioService.DEBUG_VOL) {
            Log.i(TAG, "setAvrcpAbsoluteVolumeIndex index=" + index);
        }
        AudioService.sVolumeLogger.log(new AudioServiceEvents.VolumeEvent(
                AudioServiceEvents.VolumeEvent.VOL_SET_AVRCP_VOL, index));
        mA2dp.setAvrcpAbsoluteVolume(index);
    }

    /*package*/ synchronized @AudioSystem.AudioFormatNativeEnumForBtCodec int getA2dpCodec(
            @NonNull BluetoothDevice device) {
        if (mA2dp == null) {
            return AudioSystem.AUDIO_FORMAT_DEFAULT;
        }
        final BluetoothCodecStatus btCodecStatus = mA2dp.getCodecStatus(device);
        if (btCodecStatus == null) {
            return AudioSystem.AUDIO_FORMAT_DEFAULT;
        }
        final BluetoothCodecConfig btCodecConfig = btCodecStatus.getCodecConfig();
        if (btCodecConfig == null) {
            return AudioSystem.AUDIO_FORMAT_DEFAULT;
        }
        return AudioSystem.bluetoothCodecToAudioFormat(btCodecConfig.getCodecType());
    }

    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized void receiveBtEvent(Intent intent) {
        final String action = intent.getAction();

        Log.i(TAG, "receiveBtEvent action: " + action + " mScoAudioState: " + mScoAudioState);
        if (action.equals(BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED)) {
            BluetoothDevice btDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            setBtScoActiveDevice(btDevice);
        } else if (action.equals(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)) {
            int btState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            Log.i(TAG,"receiveBtEvent ACTION_AUDIO_STATE_CHANGED: "+btState);
            mDeviceBroker.postScoAudioStateChanged(btState);
        }
    }

    /**
     * Exclusively called from AudioDeviceBroker when handling MSG_I_SCO_AUDIO_STATE_CHANGED
     * as part of the serialization of the communication route selection
     */
    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    void onScoAudioStateChanged(int state) {
        boolean broadcast = false;
        int scoAudioState = AudioManager.SCO_AUDIO_STATE_ERROR;
        switch (state) {
            case BluetoothHeadset.STATE_AUDIO_CONNECTED:
                scoAudioState = AudioManager.SCO_AUDIO_STATE_CONNECTED;
                if (mScoAudioState != SCO_STATE_ACTIVE_INTERNAL
                        && mScoAudioState != SCO_STATE_DEACTIVATE_REQ) {
                    mScoAudioState = SCO_STATE_ACTIVE_EXTERNAL;
                } else if (mDeviceBroker.isBluetoothScoRequested()) {
                    // broadcast intent if the connection was initated by AudioService
                    broadcast = true;
                }
                mDeviceBroker.setBluetoothScoOn(true, "BtHelper.receiveBtEvent");
                break;
            case BluetoothHeadset.STATE_AUDIO_DISCONNECTED:
                mDeviceBroker.setBluetoothScoOn(false, "BtHelper.receiveBtEvent");
                scoAudioState = AudioManager.SCO_AUDIO_STATE_DISCONNECTED;
                // There are two cases where we want to immediately reconnect audio:
                // 1) If a new start request was received while disconnecting: this was
                // notified by requestScoState() setting state to SCO_STATE_ACTIVATE_REQ.
                // 2) If audio was connected then disconnected via Bluetooth APIs and
                // we still have pending activation requests by apps: this is indicated by
                // state SCO_STATE_ACTIVE_EXTERNAL and BT SCO is requested.
                if (mScoAudioState == SCO_STATE_ACTIVATE_REQ) {
                    if (mBluetoothHeadset != null && mBluetoothHeadsetDevice != null
                            && connectBluetoothScoAudioHelper(mBluetoothHeadset,
                            mBluetoothHeadsetDevice, mScoAudioMode)) {
                        mScoAudioState = SCO_STATE_ACTIVE_INTERNAL;
                        scoAudioState = AudioManager.SCO_AUDIO_STATE_CONNECTING;
                        broadcast = true;
                        break;
                    }
                }
                if (mScoAudioState != SCO_STATE_ACTIVE_EXTERNAL) {
                    broadcast = true;
                }
                mScoAudioState = SCO_STATE_INACTIVE;
                break;
            case BluetoothHeadset.STATE_AUDIO_CONNECTING:
                if (mScoAudioState != SCO_STATE_ACTIVE_INTERNAL
                        && mScoAudioState != SCO_STATE_DEACTIVATE_REQ) {
                    mScoAudioState = SCO_STATE_ACTIVE_EXTERNAL;
                }
                break;
            default:
                break;
        }
        if(broadcast) {
            broadcastScoConnectionState(scoAudioState);
            //FIXME: this is to maintain compatibility with deprecated intent
            // AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED. Remove when appropriate.
            Intent newIntent = new Intent(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED);
            newIntent.putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, scoAudioState);
            sendStickyBroadcastToAll(newIntent);
        }

    }
    /**
     *
     * @return false if SCO isn't connected
     */
    /*package*/ synchronized boolean isBluetoothScoOn() {
        if (mBluetoothHeadset == null || mBluetoothHeadsetDevice == null) {
            return false;
        }
        return mBluetoothHeadset.getAudioState(mBluetoothHeadsetDevice)
                == BluetoothHeadset.STATE_AUDIO_CONNECTED;
    }

    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized boolean startBluetoothSco(int scoAudioMode,
                @NonNull String eventSource) {
        AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(eventSource));
        return requestScoState(BluetoothHeadset.STATE_AUDIO_CONNECTED, scoAudioMode);
    }

    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized boolean stopBluetoothSco(@NonNull String eventSource) {
        AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(eventSource));
        return requestScoState(BluetoothHeadset.STATE_AUDIO_DISCONNECTED, SCO_MODE_VIRTUAL_CALL);
    }

    /*package*/ synchronized void setLeAudioVolume(int index, int maxIndex, int streamType) {
        if (mLeAudio == null) {
            if (AudioService.DEBUG_VOL) {
                Log.i(TAG, "setLeAudioVolume: null mLeAudio");
            }
            return;
        }
        /* leaudio expect volume value in range 0 to 255 */
        int volume = (int) Math.round((double) index * BT_LE_AUDIO_MAX_VOL / maxIndex);

        if (AudioService.DEBUG_VOL) {
            Log.i(TAG, "setLeAudioVolume: calling mLeAudio.setVolume idx="
                    + index + " volume=" + volume);
        }
        AudioService.sVolumeLogger.log(new AudioServiceEvents.VolumeEvent(
                AudioServiceEvents.VolumeEvent.VOL_SET_LE_AUDIO_VOL, index, maxIndex));
        mLeAudio.setVolume(volume);
    }

    /*package*/ synchronized void setHearingAidVolume(int index, int streamType) {
        if (mHearingAid == null) {
            if (AudioService.DEBUG_VOL) {
                Log.i(TAG, "setHearingAidVolume: null mHearingAid");
            }
            return;
        }
        //hearing aid expect volume value in range -128dB to 0dB
        int gainDB = (int) AudioSystem.getStreamVolumeDB(streamType, index / 10,
                AudioSystem.DEVICE_OUT_HEARING_AID);
        if (gainDB < BT_HEARING_AID_GAIN_MIN) {
            gainDB = BT_HEARING_AID_GAIN_MIN;
        }
        if (AudioService.DEBUG_VOL) {
            Log.i(TAG, "setHearingAidVolume: calling mHearingAid.setVolume idx="
                    + index + " gain=" + gainDB);
        }
        AudioService.sVolumeLogger.log(new AudioServiceEvents.VolumeEvent(
                AudioServiceEvents.VolumeEvent.VOL_SET_HEARING_AID_VOL, index, gainDB));
        mHearingAid.setVolume(gainDB);
    }

    /*package*/ synchronized void onBroadcastScoConnectionState(int state) {
        if (state == mScoConnectionState) {
            return;
        }
        Intent newIntent = new Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        newIntent.putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, state);
        newIntent.putExtra(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE,
                mScoConnectionState);
        sendStickyBroadcastToAll(newIntent);
        mScoConnectionState = state;
    }

    /*package*/ synchronized void disconnectAllBluetoothProfiles() {
        mDeviceBroker.postBtProfileDisconnected(BluetoothProfile.A2DP);
        mDeviceBroker.postBtProfileDisconnected(BluetoothProfile.A2DP_SINK);
        mDeviceBroker.postBtProfileDisconnected(BluetoothProfile.HEADSET);
        mDeviceBroker.postBtProfileDisconnected(BluetoothProfile.HEARING_AID);
        mDeviceBroker.postBtProfileDisconnected(BluetoothProfile.LE_AUDIO);
        mDeviceBroker.postBtProfileDisconnected(BluetoothProfile.LE_AUDIO_BROADCAST);
    }

    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized void resetBluetoothSco() {
        mScoAudioState = SCO_STATE_INACTIVE;
        broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
        AudioSystem.setParameters("A2dpSuspended=false");
        mDeviceBroker.setBluetoothScoOn(false, "resetBluetoothSco");
    }

    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized void disconnectHeadset() {
        setBtScoActiveDevice(null);
        mBluetoothHeadset = null;
    }

    /*package*/ synchronized void onBtProfileConnected(int profile, BluetoothProfile proxy) {
        if (profile == BluetoothProfile.HEADSET) {
            onHeadsetProfileConnected((BluetoothHeadset) proxy);
            return;
        }
        if (profile == BluetoothProfile.A2DP) {
            mA2dp = (BluetoothA2dp) proxy;
        } else if (profile == BluetoothProfile.HEARING_AID) {
            mHearingAid = (BluetoothHearingAid) proxy;
        } else if (profile == BluetoothProfile.LE_AUDIO) {
            mLeAudio = (BluetoothLeAudio) proxy;
        }
        final List<BluetoothDevice> deviceList = proxy.getConnectedDevices();
        if (deviceList.isEmpty()) {
            return;
        }
        final BluetoothDevice btDevice = deviceList.get(0);
        if (proxy.getConnectionState(btDevice) == BluetoothProfile.STATE_CONNECTED) {
            mDeviceBroker.queueOnBluetoothActiveDeviceChanged(
                    new AudioDeviceBroker.BtDeviceChangedData(btDevice, null,
                        new BluetoothProfileConnectionInfo(profile),
                        "mBluetoothProfileServiceListener"));
        } else {
            mDeviceBroker.queueOnBluetoothActiveDeviceChanged(
                    new AudioDeviceBroker.BtDeviceChangedData(null, btDevice,
                        new BluetoothProfileConnectionInfo(profile),
                        "mBluetoothProfileServiceListener"));
        }
    }

    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized void onHeadsetProfileConnected(BluetoothHeadset headset) {
        // Discard timeout message
        mDeviceBroker.handleCancelFailureToConnectToBtHeadsetService();
        mBluetoothHeadset = headset;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        List<BluetoothDevice> activeDevices = Collections.emptyList();
        if (adapter != null) {
            activeDevices = adapter.getActiveDevices(BluetoothProfile.HEADSET);
        }
        setBtScoActiveDevice((activeDevices.size() > 0) ? activeDevices.get(0) : null);
        // Refresh SCO audio state
        checkScoAudioState();
        if (mScoAudioState != SCO_STATE_ACTIVATE_REQ
                && mScoAudioState != SCO_STATE_DEACTIVATE_REQ) {
            return;
        }
        boolean status = false;
        if (mBluetoothHeadset != null && mBluetoothHeadsetDevice != null) {
            switch (mScoAudioState) {
                case SCO_STATE_ACTIVATE_REQ:
                    status = connectBluetoothScoAudioHelper(
                            mBluetoothHeadset,
                            mBluetoothHeadsetDevice, mScoAudioMode);
                    if (status) {
                        mScoAudioState = SCO_STATE_ACTIVE_INTERNAL;
                    }
                    break;
                case SCO_STATE_DEACTIVATE_REQ:
                    status = disconnectBluetoothScoAudioHelper(
                            mBluetoothHeadset,
                            mBluetoothHeadsetDevice, mScoAudioMode);
                    if (status) {
                        mScoAudioState = SCO_STATE_DEACTIVATING;
                    }
                    break;
            }
        }
        if (!status) {
            mScoAudioState = SCO_STATE_INACTIVE;
            broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
        }
    }

    //----------------------------------------------------------------------
    private void broadcastScoConnectionState(int state) {
        mDeviceBroker.postBroadcastScoConnectionState(state);
    }

    @Nullable AudioDeviceAttributes getHeadsetAudioDevice() {
        if (mBluetoothHeadsetDevice == null) {
            return null;
        }
        return btHeadsetDeviceToAudioDevice(mBluetoothHeadsetDevice);
    }

    private AudioDeviceAttributes btHeadsetDeviceToAudioDevice(BluetoothDevice btDevice) {
        if (btDevice == null) {
            return new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_BLUETOOTH_SCO, "");
        }
        String address = btDevice.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = "";
        }
        BluetoothClass btClass = btDevice.getBluetoothClass();
        int nativeType = AudioSystem.DEVICE_OUT_BLUETOOTH_SCO;
        if (btClass != null) {
            switch (btClass.getDeviceClass()) {
                case BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET:
                case BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE:
                    nativeType = AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_HEADSET;
                    break;
                case BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO:
                    nativeType = AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_CARKIT;
                    break;
            }
        }
        if (AudioService.DEBUG_DEVICES) {
            Log.i(TAG, "btHeadsetDeviceToAudioDevice btDevice: " + btDevice
                    + " btClass: " + (btClass == null ? "Unknown" : btClass)
                    + " nativeType: " + nativeType + " address: " + address);
        }
        return new AudioDeviceAttributes(nativeType, address);
    }

    private boolean handleBtScoActiveDeviceChange(BluetoothDevice btDevice, boolean isActive) {
        if (btDevice == null) {
            return true;
        }
        int inDevice = AudioSystem.DEVICE_IN_BLUETOOTH_SCO_HEADSET;
        AudioDeviceAttributes audioDevice =  btHeadsetDeviceToAudioDevice(btDevice);
        String btDeviceName =  getName(btDevice);
        boolean result = false;
        if (isActive) {
            result |= mDeviceBroker.handleDeviceConnection(new AudioDeviceAttributes(
                    audioDevice.getInternalType(), audioDevice.getAddress(), btDeviceName),
                    isActive);
        } else {
            int[] outDeviceTypes = {
                    AudioSystem.DEVICE_OUT_BLUETOOTH_SCO,
                    AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_HEADSET,
                    AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_CARKIT
            };
            for (int outDeviceType : outDeviceTypes) {
                result |= mDeviceBroker.handleDeviceConnection(new AudioDeviceAttributes(
                        outDeviceType, audioDevice.getAddress(), btDeviceName),
                        isActive);
            }
        }
        // handleDeviceConnection() && result to make sure the method get executed
        result = mDeviceBroker.handleDeviceConnection(new AudioDeviceAttributes(
                        inDevice, audioDevice.getAddress(), btDeviceName),
                isActive) && result;
        return result;
    }

    // Return `(null)` if given BluetoothDevice is null. Otherwise, return the anonymized address.
    private String getAnonymizedAddress(BluetoothDevice btDevice) {
        return btDevice == null ? "(null)" : btDevice.getAnonymizedAddress();
    }

    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    //@GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    @GuardedBy("BtHelper.this")
    private void setBtScoActiveDevice(BluetoothDevice btDevice) {
        Log.i(TAG, "setBtScoActiveDevice: " + getAnonymizedAddress(mBluetoothHeadsetDevice)
                + " -> " + getAnonymizedAddress(btDevice));
        final BluetoothDevice previousActiveDevice = mBluetoothHeadsetDevice;
        if (Objects.equals(btDevice, previousActiveDevice)) {
            return;
        }
        if (!handleBtScoActiveDeviceChange(previousActiveDevice, false)) {
            Log.w(TAG, "setBtScoActiveDevice() failed to remove previous device "
                    + getAnonymizedAddress(previousActiveDevice));
        }
        if (!handleBtScoActiveDeviceChange(btDevice, true)) {
            Log.e(TAG, "setBtScoActiveDevice() failed to add new device "
                    + getAnonymizedAddress(btDevice));
            // set mBluetoothHeadsetDevice to null when failing to add new device
            btDevice = null;
        }
        mBluetoothHeadsetDevice = btDevice;
        if (mBluetoothHeadsetDevice == null) {
            resetBluetoothSco();
        }
    }

    // NOTE this listener is NOT called from AudioDeviceBroker event thread, only call async
    //      methods inside listener.
    private BluetoothProfile.ServiceListener mBluetoothProfileServiceListener =
            new BluetoothProfile.ServiceListener() {
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    switch(profile) {
                        case BluetoothProfile.A2DP:
                        case BluetoothProfile.A2DP_SINK:
                        case BluetoothProfile.HEADSET:
                        case BluetoothProfile.HEARING_AID:
                        case BluetoothProfile.LE_AUDIO:
                            AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(
                                    "BT profile service: connecting "
                                    + BluetoothProfile.getProfileName(profile) + " profile"));
                            mDeviceBroker.postBtProfileConnected(profile, proxy);
                            break;

                        default:
                            break;
                    }
                }
                public void onServiceDisconnected(int profile) {

                    switch (profile) {
                        case BluetoothProfile.A2DP:
                        case BluetoothProfile.A2DP_SINK:
                        case BluetoothProfile.HEADSET:
                        case BluetoothProfile.HEARING_AID:
                        case BluetoothProfile.LE_AUDIO:
                        case BluetoothProfile.LE_AUDIO_BROADCAST:
                            mDeviceBroker.postBtProfileDisconnected(profile);
                            break;

                        default:
                            break;
                    }
                }
            };

    //----------------------------------------------------------------------

    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    //@GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    @GuardedBy("BtHelper.this")
    private boolean requestScoState(int state, int scoAudioMode) {
        checkScoAudioState();
        if (state == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
            // Make sure that the state transitions to CONNECTING even if we cannot initiate
            // the connection.
            broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_CONNECTING);
            switch (mScoAudioState) {
                case SCO_STATE_INACTIVE:
                    mScoAudioMode = scoAudioMode;
                    if (scoAudioMode == SCO_MODE_UNDEFINED) {
                        mScoAudioMode = SCO_MODE_VIRTUAL_CALL;
                        if (mBluetoothHeadsetDevice != null) {
                            mScoAudioMode = Settings.Global.getInt(
                                    mDeviceBroker.getContentResolver(),
                                    "bluetooth_sco_channel_"
                                            + mBluetoothHeadsetDevice.getAddress(),
                                    SCO_MODE_VIRTUAL_CALL);
                            if (mScoAudioMode > SCO_MODE_MAX || mScoAudioMode < 0) {
                                mScoAudioMode = SCO_MODE_VIRTUAL_CALL;
                            }
                        }
                    }
                    if (mBluetoothHeadset == null) {
                        if (getBluetoothHeadset()) {
                            mScoAudioState = SCO_STATE_ACTIVATE_REQ;
                        } else {
                            Log.w(TAG, "requestScoState: getBluetoothHeadset failed during"
                                    + " connection, mScoAudioMode=" + mScoAudioMode);
                            broadcastScoConnectionState(
                                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                            return false;
                        }
                        break;
                    }
                    if (mBluetoothHeadsetDevice == null) {
                        Log.w(TAG, "requestScoState: no active device while connecting,"
                                + " mScoAudioMode=" + mScoAudioMode);
                        broadcastScoConnectionState(
                                AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                        return false;
                    }
                    if (connectBluetoothScoAudioHelper(mBluetoothHeadset,
                            mBluetoothHeadsetDevice, mScoAudioMode)) {
                        mScoAudioState = SCO_STATE_ACTIVE_INTERNAL;
                    } else {
                        Log.w(TAG, "requestScoState: connect to "
                                + getAnonymizedAddress(mBluetoothHeadsetDevice)
                                + " failed, mScoAudioMode=" + mScoAudioMode);
                        broadcastScoConnectionState(
                                AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                        return false;
                    }
                    break;
                case SCO_STATE_DEACTIVATING:
                    mScoAudioState = SCO_STATE_ACTIVATE_REQ;
                    break;
                case SCO_STATE_DEACTIVATE_REQ:
                    mScoAudioState = SCO_STATE_ACTIVE_INTERNAL;
                    broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_CONNECTED);
                    break;
                case SCO_STATE_ACTIVE_INTERNAL:
                    Log.w(TAG, "requestScoState: already in ACTIVE mode, simply return");
                    break;
                case SCO_STATE_ACTIVE_EXTERNAL:
                    /* Confirm SCO Audio connection to requesting app as it is already connected
                     * externally (i.e. through SCO APIs by Telecom service).
                     * Once SCO Audio is disconnected by the external owner, we will reconnect it
                     * automatically on behalf of the requesting app and the state will move to
                     * SCO_STATE_ACTIVE_INTERNAL.
                     */
                    broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_CONNECTED);
                    break;
                default:
                    Log.w(TAG, "requestScoState: failed to connect in state "
                            + mScoAudioState + ", scoAudioMode=" + scoAudioMode);
                    broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                    return false;
            }
        } else if (state == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
            switch (mScoAudioState) {
                case SCO_STATE_ACTIVE_INTERNAL:
                    if (mBluetoothHeadset == null) {
                        if (getBluetoothHeadset()) {
                            mScoAudioState = SCO_STATE_DEACTIVATE_REQ;
                        } else {
                            Log.w(TAG, "requestScoState: getBluetoothHeadset failed during"
                                    + " disconnection, mScoAudioMode=" + mScoAudioMode);
                            mScoAudioState = SCO_STATE_INACTIVE;
                            broadcastScoConnectionState(
                                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                            return false;
                        }
                        break;
                    }
                    if (mBluetoothHeadsetDevice == null) {
                        mScoAudioState = SCO_STATE_INACTIVE;
                        broadcastScoConnectionState(
                                AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                        break;
                    }
                    if (disconnectBluetoothScoAudioHelper(mBluetoothHeadset,
                            mBluetoothHeadsetDevice, mScoAudioMode)) {
                        mScoAudioState = SCO_STATE_DEACTIVATING;
                    } else {
                        mScoAudioState = SCO_STATE_INACTIVE;
                        broadcastScoConnectionState(
                                AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                    }
                    break;
                case SCO_STATE_ACTIVATE_REQ:
                    mScoAudioState = SCO_STATE_INACTIVE;
                    broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                    break;
                default:
                    Log.w(TAG, "requestScoState: failed to disconnect in state "
                            + mScoAudioState + ", scoAudioMode=" + scoAudioMode);
                    broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                    return false;
            }
        }
        return true;
    }

    //-----------------------------------------------------
    // Utilities
    private void sendStickyBroadcastToAll(Intent intent) {
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        final long ident = Binder.clearCallingIdentity();
        try {
            mDeviceBroker.getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private static boolean disconnectBluetoothScoAudioHelper(BluetoothHeadset bluetoothHeadset,
            BluetoothDevice device, int scoAudioMode) {
        switch (scoAudioMode) {
            case SCO_MODE_VIRTUAL_CALL:
                return bluetoothHeadset.stopScoUsingVirtualVoiceCall();
            case SCO_MODE_VR:
                return bluetoothHeadset.stopVoiceRecognition(device);
            default:
                return false;
        }
    }

    private static boolean connectBluetoothScoAudioHelper(BluetoothHeadset bluetoothHeadset,
            BluetoothDevice device, int scoAudioMode) {
        switch (scoAudioMode) {
            case SCO_MODE_VIRTUAL_CALL:
                return bluetoothHeadset.startScoUsingVirtualVoiceCall();
            case SCO_MODE_VR:
                return bluetoothHeadset.startVoiceRecognition(device);
            default:
                return false;
        }
    }

    private void checkScoAudioState() {
        if (mBluetoothHeadset != null
                && mBluetoothHeadsetDevice != null
                && mScoAudioState == SCO_STATE_INACTIVE
                && mBluetoothHeadset.getAudioState(mBluetoothHeadsetDevice)
                != BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
            mScoAudioState = SCO_STATE_ACTIVE_EXTERNAL;
        }
    }

    private boolean getBluetoothHeadset() {
        boolean result = false;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            result = adapter.getProfileProxy(mDeviceBroker.getContext(),
                    mBluetoothProfileServiceListener, BluetoothProfile.HEADSET);
        }
        // If we could not get a bluetooth headset proxy, send a failure message
        // without delay to reset the SCO audio state and clear SCO clients.
        // If we could get a proxy, send a delayed failure message that will reset our state
        // in case we don't receive onServiceConnected().
        mDeviceBroker.handleFailureToConnectToBtHeadsetService(
                result ? AudioDeviceBroker.BT_HEADSET_CNCT_TIMEOUT_MS : 0);
        return result;
    }

    /**
     * Returns the String equivalent of the btCodecType.
     *
     * This uses an "ENCODING_" prefix for consistency with Audio;
     * we could alternately use the "SOURCE_CODEC_TYPE_" prefix from Bluetooth.
     */
    public static String bluetoothCodecToEncodingString(int btCodecType) {
        switch (btCodecType) {
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC:
                return "ENCODING_SBC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC:
                return "ENCODING_AAC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX:
                return "ENCODING_APTX";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD:
                return "ENCODING_APTX_HD";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC:
                return "ENCODING_LDAC";
            case SOURCE_CODEC_TYPE_OPUS: // TODO update in U
                return "ENCODING_OPUS";
            default:
                return "ENCODING_BT_CODEC_TYPE(" + btCodecType + ")";
        }
    }

    /**
     * Returns the string equivalent for the btDeviceClass class.
     */
    public static String btDeviceClassToString(int btDeviceClass) {
        switch (btDeviceClass) {
            case BluetoothClass.Device.AUDIO_VIDEO_UNCATEGORIZED:
                return "AUDIO_VIDEO_UNCATEGORIZED";
            case BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET:
                return "AUDIO_VIDEO_WEARABLE_HEADSET";
            case BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE:
                return "AUDIO_VIDEO_HANDSFREE";
            case 0x040C:
                return "AUDIO_VIDEO_RESERVED_0x040C"; // uncommon
            case BluetoothClass.Device.AUDIO_VIDEO_MICROPHONE:
                return "AUDIO_VIDEO_MICROPHONE";
            case BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER:
                return "AUDIO_VIDEO_LOUDSPEAKER";
            case BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES:
                return "AUDIO_VIDEO_HEADPHONES";
            case BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO:
                return "AUDIO_VIDEO_PORTABLE_AUDIO";
            case BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO:
                return "AUDIO_VIDEO_CAR_AUDIO";
            case BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX:
                return "AUDIO_VIDEO_SET_TOP_BOX";
            case BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO:
                return "AUDIO_VIDEO_HIFI_AUDIO";
            case BluetoothClass.Device.AUDIO_VIDEO_VCR:
                return "AUDIO_VIDEO_VCR";
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CAMERA:
                return "AUDIO_VIDEO_VIDEO_CAMERA";
            case BluetoothClass.Device.AUDIO_VIDEO_CAMCORDER:
                return "AUDIO_VIDEO_CAMCORDER";
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR:
                return "AUDIO_VIDEO_VIDEO_MONITOR";
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER:
                return "AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER";
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CONFERENCING:
                return "AUDIO_VIDEO_VIDEO_CONFERENCING";
            case 0x0444:
                return "AUDIO_VIDEO_RESERVED_0x0444"; // uncommon
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_GAMING_TOY:
                return "AUDIO_VIDEO_VIDEO_GAMING_TOY";
            default: // other device classes printed as a hex string.
                return TextUtils.formatSimple("0x%04x", btDeviceClass);
        }
    }

    //------------------------------------------------------------
    /*package*/ void dump(PrintWriter pw, String prefix) {
        pw.println("\n" + prefix + "mBluetoothHeadset: " + mBluetoothHeadset);
        pw.println(prefix + "mBluetoothHeadsetDevice: " + mBluetoothHeadsetDevice);
        if (mBluetoothHeadsetDevice != null) {
            final BluetoothClass bluetoothClass = mBluetoothHeadsetDevice.getBluetoothClass();
            if (bluetoothClass != null) {
                pw.println(prefix + "mBluetoothHeadsetDevice.DeviceClass: "
                        + btDeviceClassToString(bluetoothClass.getDeviceClass()));
            }
        }
        pw.println(prefix + "mScoAudioState: " + scoAudioStateToString(mScoAudioState));
        pw.println(prefix + "mScoAudioMode: " + scoAudioModeToString(mScoAudioMode));
        pw.println("\n" + prefix + "mHearingAid: " + mHearingAid);
        pw.println("\n" + prefix + "mLeAudio: " + mLeAudio);
        pw.println(prefix + "mA2dp: " + mA2dp);
        pw.println(prefix + "mAvrcpAbsVolSupported: " + mAvrcpAbsVolSupported);
    }

}
