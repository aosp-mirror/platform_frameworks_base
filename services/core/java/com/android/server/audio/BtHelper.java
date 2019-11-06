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
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * @hide
 * Class to encapsulate all communication with Bluetooth services
 */
public class BtHelper {

    private static final String TAG = "AS.BtHelper";

    private final @NonNull AudioDeviceBroker mDeviceBroker;

    BtHelper(@NonNull AudioDeviceBroker broker) {
        mDeviceBroker = broker;
    }

    // List of clients having issued a SCO start request
    private final @NonNull ArrayList<ScoClient> mScoClients = new ArrayList<ScoClient>();

    // BluetoothHeadset API to control SCO connection
    private @Nullable BluetoothHeadset mBluetoothHeadset;

    // Bluetooth headset device
    private @Nullable BluetoothDevice mBluetoothHeadsetDevice;

    private @Nullable BluetoothHearingAid mHearingAid;

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
    // SCO audio mode is raw audio (BluetoothHeadset.connectAudio())
    private  static final int SCO_MODE_RAW = 1;
    // SCO audio mode is Voice Recognition (BluetoothHeadset.startVoiceRecognition())
    private  static final int SCO_MODE_VR = 2;
    // max valid SCO audio mode values
    private static final int SCO_MODE_MAX = 2;

    private static final int BT_HEARING_AID_GAIN_MIN = -128;

    //----------------------------------------------------------------------
    /*package*/ static class BluetoothA2dpDeviceInfo {
        private final @NonNull BluetoothDevice mBtDevice;
        private final int mVolume;
        private final int mCodec;

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

        public int getCodec() {
            return mCodec;
        }

        // redefine equality op so we can match messages intended for this device
        @Override
        public boolean equals(Object o) {
            return mBtDevice.equals(o);
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

    /*package*/ synchronized int getA2dpCodec(@NonNull BluetoothDevice device) {
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
        return mapBluetoothCodecToAudioFormat(btCodecConfig.getCodecType());
    }

    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized void receiveBtEvent(Intent intent) {
        final String action = intent.getAction();
        if (action.equals(BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED)) {
            BluetoothDevice btDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            setBtScoActiveDevice(btDevice);
        } else if (action.equals(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)) {
            boolean broadcast = false;
            int scoAudioState = AudioManager.SCO_AUDIO_STATE_ERROR;
            int btState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            // broadcast intent if the connection was initated by AudioService
            if (!mScoClients.isEmpty()
                    && (mScoAudioState == SCO_STATE_ACTIVE_INTERNAL
                    || mScoAudioState == SCO_STATE_ACTIVATE_REQ
                    || mScoAudioState == SCO_STATE_DEACTIVATE_REQ
                    || mScoAudioState == SCO_STATE_DEACTIVATING)) {
                broadcast = true;
            }
            switch (btState) {
                case BluetoothHeadset.STATE_AUDIO_CONNECTED:
                    scoAudioState = AudioManager.SCO_AUDIO_STATE_CONNECTED;
                    if (mScoAudioState != SCO_STATE_ACTIVE_INTERNAL
                            && mScoAudioState != SCO_STATE_DEACTIVATE_REQ) {
                        mScoAudioState = SCO_STATE_ACTIVE_EXTERNAL;
                    }
                    mDeviceBroker.setBluetoothScoOn(true, "BtHelper.receiveBtEvent");
                    break;
                case BluetoothHeadset.STATE_AUDIO_DISCONNECTED:
                    mDeviceBroker.setBluetoothScoOn(false, "BtHelper.receiveBtEvent");
                    scoAudioState = AudioManager.SCO_AUDIO_STATE_DISCONNECTED;
                    // startBluetoothSco called after stopBluetoothSco
                    if (mScoAudioState == SCO_STATE_ACTIVATE_REQ) {
                        if (mBluetoothHeadset != null && mBluetoothHeadsetDevice != null
                                && connectBluetoothScoAudioHelper(mBluetoothHeadset,
                                mBluetoothHeadsetDevice, mScoAudioMode)) {
                            mScoAudioState = SCO_STATE_ACTIVE_INTERNAL;
                            broadcast = false;
                            break;
                        }
                    }
                    // Tear down SCO if disconnected from external
                    clearAllScoClients(0, mScoAudioState == SCO_STATE_ACTIVE_INTERNAL);
                    mScoAudioState = SCO_STATE_INACTIVE;
                    break;
                case BluetoothHeadset.STATE_AUDIO_CONNECTING:
                    if (mScoAudioState != SCO_STATE_ACTIVE_INTERNAL
                            && mScoAudioState != SCO_STATE_DEACTIVATE_REQ) {
                        mScoAudioState = SCO_STATE_ACTIVE_EXTERNAL;
                    }
                    break;
                default:
                    // do not broadcast CONNECTING or invalid state
                    broadcast = false;
                    break;
            }
            if (broadcast) {
                broadcastScoConnectionState(scoAudioState);
                //FIXME: this is to maintain compatibility with deprecated intent
                // AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED. Remove when appropriate.
                Intent newIntent = new Intent(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED);
                newIntent.putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, scoAudioState);
                sendStickyBroadcastToAll(newIntent);
            }
        }
    }

    /**
     *
     * @return false if SCO isn't connected
     */
    /*package*/ synchronized boolean isBluetoothScoOn() {
        if ((mBluetoothHeadset != null)
                && (mBluetoothHeadset.getAudioState(mBluetoothHeadsetDevice)
                != BluetoothHeadset.STATE_AUDIO_CONNECTED)) {
            Log.w(TAG, "isBluetoothScoOn(true) returning false because "
                    + mBluetoothHeadsetDevice + " is not in audio connected mode");
            return false;
        }
        return true;
    }

    /**
     * Disconnect all SCO connections started by {@link AudioManager} except those started by
     * {@param exceptPid}
     *
     * @param exceptPid pid whose SCO connections through {@link AudioManager} should be kept
     */
    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized void disconnectBluetoothSco(int exceptPid) {
        checkScoAudioState();
        if (mScoAudioState == SCO_STATE_ACTIVE_EXTERNAL) {
            return;
        }
        clearAllScoClients(exceptPid, true);
    }

    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized void startBluetoothScoForClient(IBinder cb, int scoAudioMode,
                @NonNull String eventSource) {
        ScoClient client = getScoClient(cb, true);
        // The calling identity must be cleared before calling ScoClient.incCount().
        // inCount() calls requestScoState() which in turn can call BluetoothHeadset APIs
        // and this must be done on behalf of system server to make sure permissions are granted.
        // The caller identity must be cleared after getScoClient() because it is needed if a new
        // client is created.
        final long ident = Binder.clearCallingIdentity();
        try {
            eventSource += " client count before=" + client.getCount();
            AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(eventSource));
            client.incCount(scoAudioMode);
        } catch (NullPointerException e) {
            Log.e(TAG, "Null ScoClient", e);
        }
        Binder.restoreCallingIdentity(ident);
    }

    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized void stopBluetoothScoForClient(IBinder cb,
            @NonNull String eventSource) {
        ScoClient client = getScoClient(cb, false);
        // The calling identity must be cleared before calling ScoClient.decCount().
        // decCount() calls requestScoState() which in turn can call BluetoothHeadset APIs
        // and this must be done on behalf of system server to make sure permissions are granted.
        final long ident = Binder.clearCallingIdentity();
        if (client != null) {
            eventSource += " client count before=" + client.getCount();
            AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(eventSource));
            client.decCount();
        }
        Binder.restoreCallingIdentity(ident);
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
        mDeviceBroker.postDisconnectA2dp();
        mDeviceBroker.postDisconnectA2dpSink();
        mDeviceBroker.postDisconnectHeadset();
        mDeviceBroker.postDisconnectHearingAid();
    }

    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized void resetBluetoothSco() {
        clearAllScoClients(0, false);
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

    /*package*/ synchronized void onA2dpProfileConnected(BluetoothA2dp a2dp) {
        mA2dp = a2dp;
        final List<BluetoothDevice> deviceList = mA2dp.getConnectedDevices();
        if (deviceList.isEmpty()) {
            return;
        }
        final BluetoothDevice btDevice = deviceList.get(0);
        // the device is guaranteed CONNECTED
        mDeviceBroker.postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(btDevice,
                BluetoothA2dp.STATE_CONNECTED, BluetoothProfile.A2DP_SINK, true, -1);
    }

    /*package*/ synchronized void onA2dpSinkProfileConnected(BluetoothProfile profile) {
        final List<BluetoothDevice> deviceList = profile.getConnectedDevices();
        if (deviceList.isEmpty()) {
            return;
        }
        final BluetoothDevice btDevice = deviceList.get(0);
        final @BluetoothProfile.BtProfileState int state =
                profile.getConnectionState(btDevice);
        mDeviceBroker.postSetA2dpSourceConnectionState(
                state, new BluetoothA2dpDeviceInfo(btDevice));
    }

    /*package*/ synchronized void onHearingAidProfileConnected(BluetoothHearingAid hearingAid) {
        mHearingAid = hearingAid;
        final List<BluetoothDevice> deviceList = mHearingAid.getConnectedDevices();
        if (deviceList.isEmpty()) {
            return;
        }
        final BluetoothDevice btDevice = deviceList.get(0);
        final @BluetoothProfile.BtProfileState int state =
                mHearingAid.getConnectionState(btDevice);
        mDeviceBroker.postBluetoothHearingAidDeviceConnectionState(
                btDevice, state,
                /*suppressNoisyIntent*/ false,
                /*musicDevice*/ android.media.AudioSystem.DEVICE_NONE,
                /*eventSource*/ "mBluetoothProfileServiceListener");
    }

    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized void onHeadsetProfileConnected(BluetoothHeadset headset) {
        // Discard timeout message
        mDeviceBroker.handleCancelFailureToConnectToBtHeadsetService();
        mBluetoothHeadset = headset;
        setBtScoActiveDevice(mBluetoothHeadset.getActiveDevice());
        // Refresh SCO audio state
        checkScoAudioState();
        if (mScoAudioState != SCO_STATE_ACTIVATE_REQ
                && mScoAudioState != SCO_STATE_DEACTIVATE_REQ) {
            return;
        }
        boolean status = false;
        if (mBluetoothHeadsetDevice != null) {
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

    private boolean handleBtScoActiveDeviceChange(BluetoothDevice btDevice, boolean isActive) {
        if (btDevice == null) {
            return true;
        }
        String address = btDevice.getAddress();
        BluetoothClass btClass = btDevice.getBluetoothClass();
        int inDevice = AudioSystem.DEVICE_IN_BLUETOOTH_SCO_HEADSET;
        int[] outDeviceTypes = {
                AudioSystem.DEVICE_OUT_BLUETOOTH_SCO,
                AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_HEADSET,
                AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_CARKIT
        };
        if (btClass != null) {
            switch (btClass.getDeviceClass()) {
                case BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET:
                case BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE:
                    outDeviceTypes = new int[] { AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_HEADSET };
                    break;
                case BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO:
                    outDeviceTypes = new int[] { AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_CARKIT };
                    break;
            }
        }
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = "";
        }
        String btDeviceName =  getName(btDevice);
        boolean result = false;
        if (isActive) {
            result |= mDeviceBroker.handleDeviceConnection(
                    isActive, outDeviceTypes[0], address, btDeviceName);
        } else {
            for (int outDeviceType : outDeviceTypes) {
                result |= mDeviceBroker.handleDeviceConnection(
                        isActive, outDeviceType, address, btDeviceName);
            }
        }
        // handleDeviceConnection() && result to make sure the method get executed
        result = mDeviceBroker.handleDeviceConnection(
                isActive, inDevice, address, btDeviceName) && result;
        return result;
    }

    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    //@GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    @GuardedBy("BtHelper.this")
    private void setBtScoActiveDevice(BluetoothDevice btDevice) {
        Log.i(TAG, "setBtScoActiveDevice: " + mBluetoothHeadsetDevice + " -> " + btDevice);
        final BluetoothDevice previousActiveDevice = mBluetoothHeadsetDevice;
        if (Objects.equals(btDevice, previousActiveDevice)) {
            return;
        }
        if (!handleBtScoActiveDeviceChange(previousActiveDevice, false)) {
            Log.w(TAG, "setBtScoActiveDevice() failed to remove previous device "
                    + previousActiveDevice);
        }
        if (!handleBtScoActiveDeviceChange(btDevice, true)) {
            Log.e(TAG, "setBtScoActiveDevice() failed to add new device " + btDevice);
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
                            AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(
                                    "BT profile service: connecting A2DP profile"));
                            mDeviceBroker.postBtA2dpProfileConnected((BluetoothA2dp) proxy);
                            break;

                        case BluetoothProfile.A2DP_SINK:
                            AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(
                                    "BT profile service: connecting A2DP_SINK profile"));
                            mDeviceBroker.postBtA2dpSinkProfileConnected(proxy);
                            break;

                        case BluetoothProfile.HEADSET:
                            AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(
                                    "BT profile service: connecting HEADSET profile"));
                            mDeviceBroker.postBtHeasetProfileConnected((BluetoothHeadset) proxy);
                            break;

                        case BluetoothProfile.HEARING_AID:
                            AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(
                                    "BT profile service: connecting HEARING_AID profile"));
                            mDeviceBroker.postBtHearingAidProfileConnected(
                                    (BluetoothHearingAid) proxy);
                            break;
                        default:
                            break;
                    }
                }
                public void onServiceDisconnected(int profile) {

                    switch (profile) {
                        case BluetoothProfile.A2DP:
                            mDeviceBroker.postDisconnectA2dp();
                            break;

                        case BluetoothProfile.A2DP_SINK:
                            mDeviceBroker.postDisconnectA2dpSink();
                            break;

                        case BluetoothProfile.HEADSET:
                            mDeviceBroker.postDisconnectHeadset();
                            break;

                        case BluetoothProfile.HEARING_AID:
                            mDeviceBroker.postDisconnectHearingAid();
                            break;

                        default:
                            break;
                    }
                }
            };

    //----------------------------------------------------------------------
    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized void scoClientDied(Object obj) {
        final ScoClient client = (ScoClient) obj;
        Log.w(TAG, "SCO client died");
        int index = mScoClients.indexOf(client);
        if (index < 0) {
            Log.w(TAG, "unregistered SCO client died");
        } else {
            client.clearCount(true);
            mScoClients.remove(client);
        }
    }

    private class ScoClient implements IBinder.DeathRecipient {
        private IBinder mCb; // To be notified of client's death
        private int mCreatorPid;
        private int mStartcount; // number of SCO connections started by this client

        ScoClient(IBinder cb) {
            mCb = cb;
            mCreatorPid = Binder.getCallingPid();
            mStartcount = 0;
        }

        @Override
        public void binderDied() {
            // process this from DeviceBroker's message queue to take the right locks since
            // this event can impact SCO mode and requires querying audio mode stack
            mDeviceBroker.postScoClientDied(this);
        }

        // @GuardedBy("AudioDeviceBroker.mSetModeLock")
        // @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
        @GuardedBy("BtHelper.this")
        void incCount(int scoAudioMode) {
            if (!requestScoState(BluetoothHeadset.STATE_AUDIO_CONNECTED, scoAudioMode)) {
                Log.e(TAG, "Request sco connected with scoAudioMode("
                        + scoAudioMode + ") failed");
                return;
            }
            if (mStartcount == 0) {
                try {
                    mCb.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    // client has already died!
                    Log.w(TAG, "ScoClient  incCount() could not link to "
                            + mCb + " binder death");
                }
            }
            mStartcount++;
        }

        // @GuardedBy("AudioDeviceBroker.mSetModeLock")
        // @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
        @GuardedBy("BtHelper.this")
        void decCount() {
            if (mStartcount == 0) {
                Log.w(TAG, "ScoClient.decCount() already 0");
            } else {
                mStartcount--;
                if (mStartcount == 0) {
                    try {
                        mCb.unlinkToDeath(this, 0);
                    } catch (NoSuchElementException e) {
                        Log.w(TAG, "decCount() going to 0 but not registered to binder");
                    }
                }
                if (!requestScoState(BluetoothHeadset.STATE_AUDIO_DISCONNECTED, 0)) {
                    Log.w(TAG, "Request sco disconnected with scoAudioMode(0) failed");
                }
            }
        }

        // @GuardedBy("AudioDeviceBroker.mSetModeLock")
        // @GuardedBy("AudioDeviceBroker.mDeviceStateLock")
        @GuardedBy("BtHelper.this")
        void clearCount(boolean stopSco) {
            if (mStartcount != 0) {
                try {
                    mCb.unlinkToDeath(this, 0);
                } catch (NoSuchElementException e) {
                    Log.w(TAG, "clearCount() mStartcount: "
                            + mStartcount + " != 0 but not registered to binder");
                }
            }
            mStartcount = 0;
            if (stopSco) {
                requestScoState(BluetoothHeadset.STATE_AUDIO_DISCONNECTED, 0);
            }
        }

        int getCount() {
            return mStartcount;
        }

        IBinder getBinder() {
            return mCb;
        }

        int getPid() {
            return mCreatorPid;
        }

        private int totalCount() {
            int count = 0;
            for (ScoClient mScoClient : mScoClients) {
                count += mScoClient.getCount();
            }
            return count;
        }

        // @GuardedBy("AudioDeviceBroker.mSetModeLock")
        //@GuardedBy("AudioDeviceBroker.mDeviceStateLock")
        @GuardedBy("BtHelper.this")
        private boolean requestScoState(int state, int scoAudioMode) {
            checkScoAudioState();
            int clientCount = totalCount();
            if (clientCount != 0) {
                Log.i(TAG, "requestScoState: state=" + state + ", scoAudioMode=" + scoAudioMode
                        + ", clientCount=" + clientCount);
                return true;
            }
            if (state == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
                // Make sure that the state transitions to CONNECTING even if we cannot initiate
                // the connection.
                broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_CONNECTING);
                // Accept SCO audio activation only in NORMAL audio mode or if the mode is
                // currently controlled by the same client process.
                final int modeOwnerPid =  mDeviceBroker.getModeOwnerPid();
                if (modeOwnerPid != 0 && (modeOwnerPid != mCreatorPid)) {
                    Log.w(TAG, "requestScoState: audio mode is not NORMAL and modeOwnerPid "
                            + modeOwnerPid + " != creatorPid " + mCreatorPid);
                    broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                    return false;
                }
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
                            Log.w(TAG, "requestScoState: connect to " + mBluetoothHeadsetDevice
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
            case SCO_MODE_RAW:
                return bluetoothHeadset.disconnectAudio();
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
            case SCO_MODE_RAW:
                return bluetoothHeadset.connectAudio();
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


    private ScoClient getScoClient(IBinder cb, boolean create) {
        for (ScoClient existingClient : mScoClients) {
            if (existingClient.getBinder() == cb) {
                return existingClient;
            }
        }
        if (create) {
            ScoClient newClient = new ScoClient(cb);
            mScoClients.add(newClient);
            return newClient;
        }
        return null;
    }

    // @GuardedBy("AudioDeviceBroker.mSetModeLock")
    //@GuardedBy("AudioDeviceBroker.mDeviceStateLock")
    @GuardedBy("BtHelper.this")
    private void clearAllScoClients(int exceptPid, boolean stopSco) {
        ScoClient savedClient = null;
        for (ScoClient cl : mScoClients) {
            if (cl.getPid() != exceptPid) {
                cl.clearCount(stopSco);
            } else {
                savedClient = cl;
            }
        }
        mScoClients.clear();
        if (savedClient != null) {
            mScoClients.add(savedClient);
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

    private int mapBluetoothCodecToAudioFormat(int btCodecType) {
        switch (btCodecType) {
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC:
                return AudioSystem.AUDIO_FORMAT_SBC;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC:
                return AudioSystem.AUDIO_FORMAT_AAC;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX:
                return AudioSystem.AUDIO_FORMAT_APTX;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD:
                return AudioSystem.AUDIO_FORMAT_APTX_HD;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC:
                return AudioSystem.AUDIO_FORMAT_LDAC;
            default:
                return AudioSystem.AUDIO_FORMAT_DEFAULT;
        }
    }
}
