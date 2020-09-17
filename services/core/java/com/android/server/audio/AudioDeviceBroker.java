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
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceAttributes;
import android.media.AudioRoutesInfo;
import android.media.AudioSystem;
import android.media.IAudioRoutesObserver;
import android.media.IStrategyPreferredDeviceDispatcher;
import android.media.MediaMetrics;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.PrintWriterPrinter;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;


/** @hide */
/*package*/ final class AudioDeviceBroker {

    private static final String TAG = "AS.AudioDeviceBroker";

    private static final long BROKER_WAKELOCK_TIMEOUT_MS = 5000; //5s

    /*package*/ static final  int BTA2DP_DOCK_TIMEOUT_MS = 8000;
    // Timeout for connection to bluetooth headset service
    /*package*/ static final int BT_HEADSET_CNCT_TIMEOUT_MS = 3000;

    // Delay before checking it music should be unmuted after processing an A2DP message
    private static final int BTA2DP_MUTE_CHECK_DELAY_MS = 100;

    private final @NonNull AudioService mAudioService;
    private final @NonNull Context mContext;

    /** Forced device usage for communications sent to AudioSystem */
    private int mForcedUseForComm;
    /**
     * Externally reported force device usage state returned by getters: always consistent
     * with requests by setters */
    private int mForcedUseForCommExt;

    // Manages all connected devices, only ever accessed on the message loop
    private final AudioDeviceInventory mDeviceInventory;
    // Manages notifications to BT service
    private final BtHelper mBtHelper;
    // Adapter for system_server-reserved operations
    private final SystemServerAdapter mSystemServer;


    //-------------------------------------------------------------------
    // we use a different lock than mDeviceStateLock so as not to create
    // lock contention between enqueueing a message and handling them
    private static final Object sLastDeviceConnectionMsgTimeLock = new Object();
    @GuardedBy("sLastDeviceConnectionMsgTimeLock")
    private static long sLastDeviceConnectMsgTime = 0;

    // General lock to be taken whenever the state of the audio devices is to be checked or changed
    private final Object mDeviceStateLock = new Object();

    // Request to override default use of A2DP for media.
    @GuardedBy("mDeviceStateLock")
    private boolean mBluetoothA2dpEnabled;

    // lock always taken when accessing AudioService.mSetModeDeathHandlers
    // TODO do not "share" the lock between AudioService and BtHelpr, see b/123769055
    /*package*/ final Object mSetModeLock = new Object();

    /** PID of current audio mode owner communicated by AudioService */
    private int mModeOwnerPid = 0;

    //-------------------------------------------------------------------
    /*package*/ AudioDeviceBroker(@NonNull Context context, @NonNull AudioService service) {
        mContext = context;
        mAudioService = service;
        mBtHelper = new BtHelper(this);
        mDeviceInventory = new AudioDeviceInventory(this);
        mSystemServer = SystemServerAdapter.getDefaultAdapter(mContext);

        init();
    }

    /** for test purposes only, inject AudioDeviceInventory and adapter for operations running
     *  in system_server */
    AudioDeviceBroker(@NonNull Context context, @NonNull AudioService service,
                      @NonNull AudioDeviceInventory mockDeviceInventory,
                      @NonNull SystemServerAdapter mockSystemServer) {
        mContext = context;
        mAudioService = service;
        mBtHelper = new BtHelper(this);
        mDeviceInventory = mockDeviceInventory;
        mSystemServer = mockSystemServer;

        init();
    }

    private void init() {
        setupMessaging(mContext);

        mForcedUseForComm = AudioSystem.FORCE_NONE;
        mForcedUseForCommExt = mForcedUseForComm;
    }

    /*package*/ Context getContext() {
        return mContext;
    }

    //---------------------------------------------------------------------
    // Communication from AudioService
    // All methods are asynchronous and never block
    // All permission checks are done in AudioService, all incoming calls are considered "safe"
    // All post* methods are asynchronous

    /*package*/ void onSystemReady() {
        synchronized (mSetModeLock) {
            synchronized (mDeviceStateLock) {
                mModeOwnerPid = mAudioService.getModeOwnerPid();
                mBtHelper.onSystemReady();
            }
        }
    }

    /*package*/ void onAudioServerDied() {
        // Restore forced usage for communications and record
        synchronized (mDeviceStateLock) {
            AudioSystem.setParameters(
                    "BT_SCO=" + (mForcedUseForComm == AudioSystem.FORCE_BT_SCO ? "on" : "off"));
            onSetForceUse(AudioSystem.FOR_COMMUNICATION, mForcedUseForComm,
                          false /*fromA2dp*/, "onAudioServerDied");
            onSetForceUse(AudioSystem.FOR_RECORD, mForcedUseForComm,
                          false /*fromA2dp*/, "onAudioServerDied");
        }
        // restore devices
        sendMsgNoDelay(MSG_RESTORE_DEVICES, SENDMSG_REPLACE);
    }

    /*package*/ void setForceUse_Async(int useCase, int config, String eventSource) {
        sendIILMsgNoDelay(MSG_IIL_SET_FORCE_USE, SENDMSG_QUEUE,
                useCase, config, eventSource);
    }

    /*package*/ void toggleHdmiIfConnected_Async() {
        sendMsgNoDelay(MSG_TOGGLE_HDMI, SENDMSG_QUEUE);
    }

    /*package*/ void disconnectAllBluetoothProfiles() {
        synchronized (mDeviceStateLock) {
            mBtHelper.disconnectAllBluetoothProfiles();
        }
    }

    /**
     * Handle BluetoothHeadset intents where the action is one of
     *   {@link BluetoothHeadset#ACTION_ACTIVE_DEVICE_CHANGED} or
     *   {@link BluetoothHeadset#ACTION_AUDIO_STATE_CHANGED}.
     * @param intent
     */
    /*package*/ void receiveBtEvent(@NonNull Intent intent) {
        synchronized (mSetModeLock) {
            synchronized (mDeviceStateLock) {
                mBtHelper.receiveBtEvent(intent);
            }
        }
    }

    /*package*/ void setBluetoothA2dpOn_Async(boolean on, String source) {
        synchronized (mDeviceStateLock) {
            if (mBluetoothA2dpEnabled == on) {
                return;
            }
            mBluetoothA2dpEnabled = on;
            mBrokerHandler.removeMessages(MSG_IIL_SET_FORCE_BT_A2DP_USE);
            sendIILMsgNoDelay(MSG_IIL_SET_FORCE_BT_A2DP_USE, SENDMSG_QUEUE,
                    AudioSystem.FOR_MEDIA,
                    mBluetoothA2dpEnabled ? AudioSystem.FORCE_NONE : AudioSystem.FORCE_NO_BT_A2DP,
                    source);
        }
    }

    /**
     * Turns speakerphone on/off
     * @param on
     * @param eventSource for logging purposes
     * @return true if speakerphone state changed
     */
    /*package*/ boolean setSpeakerphoneOn(IBinder cb, int pid, boolean on, String eventSource) {
        synchronized (mDeviceStateLock) {
            if (!addSpeakerphoneClient(cb, pid, on)) {
                return false;
            }
            if (on) {
                // Cancel BT SCO ON request by this same client: speakerphone and BT SCO routes
                // are mutually exclusive.
                // See symmetrical operation for startBluetoothScoForClient_Sync().
                mBtHelper.stopBluetoothScoForPid(pid);
            }
            final boolean wasOn = isSpeakerphoneOn();
            updateSpeakerphoneOn(eventSource);
            return (wasOn != isSpeakerphoneOn());
        }
    }

    /**
     * Turns speakerphone off for a given pid and update speakerphone state.
     * @param pid
     */
    @GuardedBy("mDeviceStateLock")
    private void setSpeakerphoneOffForPid(int pid) {
        SpeakerphoneClient client = getSpeakerphoneClientForPid(pid);
        if (client == null) {
            return;
        }
        client.unregisterDeathRecipient();
        mSpeakerphoneClients.remove(client);
        final String eventSource = new StringBuilder("setSpeakerphoneOffForPid(")
                .append(pid).append(")").toString();
        updateSpeakerphoneOn(eventSource);
    }

    @GuardedBy("mDeviceStateLock")
    private void updateSpeakerphoneOn(String eventSource) {
        if (isSpeakerphoneOnRequested()) {
            if (mForcedUseForComm == AudioSystem.FORCE_BT_SCO) {
                setForceUse_Async(AudioSystem.FOR_RECORD, AudioSystem.FORCE_NONE, eventSource);
            }
            mForcedUseForComm = AudioSystem.FORCE_SPEAKER;
        } else if (mForcedUseForComm == AudioSystem.FORCE_SPEAKER) {
            if (mBtHelper.isBluetoothScoOn()) {
                mForcedUseForComm = AudioSystem.FORCE_BT_SCO;
                setForceUse_Async(
                        AudioSystem.FOR_RECORD, AudioSystem.FORCE_BT_SCO, eventSource);
            } else {
                mForcedUseForComm = AudioSystem.FORCE_NONE;
            }
        }
        mForcedUseForCommExt = mForcedUseForComm;
        setForceUse_Async(AudioSystem.FOR_COMMUNICATION, mForcedUseForComm, eventSource);
    }

    /**
     * Returns if speakerphone is requested ON or OFF.
     * If the current audio mode owner is in the speakerphone client list, use this preference.
     * Otherwise use first client's preference (first client corresponds to latest request).
     * Speakerphone is requested OFF if no client is in the list.
     * @return true if speakerphone is requested ON, false otherwise
     */
    @GuardedBy("mDeviceStateLock")
    private boolean isSpeakerphoneOnRequested() {
        if (mSpeakerphoneClients.isEmpty()) {
            return false;
        }
        for (SpeakerphoneClient cl : mSpeakerphoneClients) {
            if (cl.getPid() == mModeOwnerPid) {
                return cl.isOn();
            }
        }
        return mSpeakerphoneClients.get(0).isOn();
    }

    /*package*/ boolean isSpeakerphoneOn() {
        synchronized (mDeviceStateLock) {
            return (mForcedUseForCommExt == AudioSystem.FORCE_SPEAKER);
        }
    }

    /*package*/ void setWiredDeviceConnectionState(int type,
            @AudioService.ConnectionState int state, String address, String name,
            String caller) {
        //TODO move logging here just like in setBluetooth* methods
        synchronized (mDeviceStateLock) {
            mDeviceInventory.setWiredDeviceConnectionState(type, state, address, name, caller);
        }
    }

    private static final class BtDeviceConnectionInfo {
        final @NonNull BluetoothDevice mDevice;
        final @AudioService.BtProfileConnectionState int mState;
        final int mProfile;
        final boolean mSupprNoisy;
        final int mVolume;

        BtDeviceConnectionInfo(@NonNull BluetoothDevice device,
                @AudioService.BtProfileConnectionState int state,
                int profile, boolean suppressNoisyIntent, int vol) {
            mDevice = device;
            mState = state;
            mProfile = profile;
            mSupprNoisy = suppressNoisyIntent;
            mVolume = vol;
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
            if (o instanceof BtDeviceConnectionInfo) {
                return mDevice.equals(((BtDeviceConnectionInfo) o).mDevice);
            }
            return false;
        }

        @Override
        public String toString() {
            return "BtDeviceConnectionInfo dev=" + mDevice.toString();
        }
    }


    /*package*/ void postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(
            @NonNull BluetoothDevice device, @AudioService.BtProfileConnectionState int state,
            int profile, boolean suppressNoisyIntent, int a2dpVolume) {
        final BtDeviceConnectionInfo info = new BtDeviceConnectionInfo(device, state, profile,
                suppressNoisyIntent, a2dpVolume);

        final String name = TextUtils.emptyIfNull(device.getName());
        new MediaMetrics.Item(MediaMetrics.Name.AUDIO_DEVICE + MediaMetrics.SEPARATOR
                + "postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent")
                .set(MediaMetrics.Property.STATE, state == BluetoothProfile.STATE_CONNECTED
                        ? MediaMetrics.Value.CONNECTED : MediaMetrics.Value.DISCONNECTED)
                .set(MediaMetrics.Property.INDEX, a2dpVolume)
                .set(MediaMetrics.Property.NAME, name)
                .record();

        // operations of removing and posting messages related to A2DP device state change must be
        // mutually exclusive
        synchronized (mDeviceStateLock) {
            // when receiving a request to change the connection state of a device, this last
            // request is the source of truth, so cancel all previous requests that are already in
            // the handler
            removeScheduledA2dpEvents(device);

            sendLMsgNoDelay(
                    state == BluetoothProfile.STATE_CONNECTED
                            ? MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT_CONNECTION
                            : MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT_DISCONNECTION,
                    SENDMSG_QUEUE, info);
        }
    }

    /** remove all previously scheduled connection and state change events for the given device */
    @GuardedBy("mDeviceStateLock")
    private void removeScheduledA2dpEvents(@NonNull BluetoothDevice device) {
        mBrokerHandler.removeEqualMessages(MSG_L_A2DP_DEVICE_CONFIG_CHANGE, device);

        final BtDeviceConnectionInfo connectionInfoToRemove = new BtDeviceConnectionInfo(device,
                // the next parameters of the constructor will be ignored when finding the message
                // to remove as the equality of the message's object is tested on the device itself
                // (see BtDeviceConnectionInfo.equals() method override)
                BluetoothProfile.STATE_CONNECTED, 0, false, -1);
        mBrokerHandler.removeEqualMessages(MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT_DISCONNECTION,
                connectionInfoToRemove);
        mBrokerHandler.removeEqualMessages(MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT_CONNECTION,
                connectionInfoToRemove);

        final BtHelper.BluetoothA2dpDeviceInfo devInfoToRemove =
                new BtHelper.BluetoothA2dpDeviceInfo(device);
        mBrokerHandler.removeEqualMessages(MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_CONNECTED,
                devInfoToRemove);
        mBrokerHandler.removeEqualMessages(MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_DISCONNECTED,
                devInfoToRemove);
        mBrokerHandler.removeEqualMessages(MSG_L_A2DP_ACTIVE_DEVICE_CHANGE,
                devInfoToRemove);
    }

    private static final class HearingAidDeviceConnectionInfo {
        final @NonNull BluetoothDevice mDevice;
        final @AudioService.BtProfileConnectionState int mState;
        final boolean mSupprNoisy;
        final int mMusicDevice;
        final @NonNull String mEventSource;

        HearingAidDeviceConnectionInfo(@NonNull BluetoothDevice device,
                @AudioService.BtProfileConnectionState int state,
                boolean suppressNoisyIntent, int musicDevice, @NonNull String eventSource) {
            mDevice = device;
            mState = state;
            mSupprNoisy = suppressNoisyIntent;
            mMusicDevice = musicDevice;
            mEventSource = eventSource;
        }
    }

    /*package*/ void postBluetoothHearingAidDeviceConnectionState(
            @NonNull BluetoothDevice device, @AudioService.BtProfileConnectionState int state,
            boolean suppressNoisyIntent, int musicDevice, @NonNull String eventSource) {
        final HearingAidDeviceConnectionInfo info = new HearingAidDeviceConnectionInfo(
                device, state, suppressNoisyIntent, musicDevice, eventSource);
        sendLMsgNoDelay(MSG_L_HEARING_AID_DEVICE_CONNECTION_CHANGE_EXT, SENDMSG_QUEUE, info);
    }

    // never called by system components
    /*package*/ void setBluetoothScoOnByApp(boolean on) {
        synchronized (mDeviceStateLock) {
            mForcedUseForCommExt = on ? AudioSystem.FORCE_BT_SCO : AudioSystem.FORCE_NONE;
        }
    }

    /*package*/ boolean isBluetoothScoOnForApp() {
        synchronized (mDeviceStateLock) {
            return mForcedUseForCommExt == AudioSystem.FORCE_BT_SCO;
        }
    }

    /*package*/ void setBluetoothScoOn(boolean on, String eventSource) {
        //Log.i(TAG, "setBluetoothScoOn: " + on + " " + eventSource);
        synchronized (mDeviceStateLock) {
            if (on) {
                // do not accept SCO ON if SCO audio is not connected
                if (!mBtHelper.isBluetoothScoOn()) {
                    mForcedUseForCommExt = AudioSystem.FORCE_BT_SCO;
                    return;
                }
                mForcedUseForComm = AudioSystem.FORCE_BT_SCO;
            } else if (mForcedUseForComm == AudioSystem.FORCE_BT_SCO) {
                mForcedUseForComm = isSpeakerphoneOnRequested()
                        ? AudioSystem.FORCE_SPEAKER : AudioSystem.FORCE_NONE;
            }
            mForcedUseForCommExt = mForcedUseForComm;
            AudioSystem.setParameters("BT_SCO=" + (on ? "on" : "off"));
            sendIILMsgNoDelay(MSG_IIL_SET_FORCE_USE, SENDMSG_QUEUE,
                    AudioSystem.FOR_COMMUNICATION, mForcedUseForComm, eventSource);
            sendIILMsgNoDelay(MSG_IIL_SET_FORCE_USE, SENDMSG_QUEUE,
                    AudioSystem.FOR_RECORD, mForcedUseForComm, eventSource);
        }
        // Un-mute ringtone stream volume
        mAudioService.postUpdateRingerModeServiceInt();
    }

    /*package*/ AudioRoutesInfo startWatchingRoutes(IAudioRoutesObserver observer) {
        synchronized (mDeviceStateLock) {
            return mDeviceInventory.startWatchingRoutes(observer);
        }
    }

    /*package*/ AudioRoutesInfo getCurAudioRoutes() {
        synchronized (mDeviceStateLock) {
            return mDeviceInventory.getCurAudioRoutes();
        }
    }

    /*package*/ boolean isAvrcpAbsoluteVolumeSupported() {
        synchronized (mDeviceStateLock) {
            return mBtHelper.isAvrcpAbsoluteVolumeSupported();
        }
    }

    /*package*/ boolean isBluetoothA2dpOn() {
        synchronized (mDeviceStateLock) {
            return mBluetoothA2dpEnabled;
        }
    }

    /*package*/ void postSetAvrcpAbsoluteVolumeIndex(int index) {
        sendIMsgNoDelay(MSG_I_SET_AVRCP_ABSOLUTE_VOLUME, SENDMSG_REPLACE, index);
    }

    /*package*/ void postSetHearingAidVolumeIndex(int index, int streamType) {
        sendIIMsgNoDelay(MSG_II_SET_HEARING_AID_VOLUME, SENDMSG_REPLACE, index, streamType);
    }

    /*package*/ void postSetModeOwnerPid(int pid, int mode) {
        sendIIMsgNoDelay(MSG_I_SET_MODE_OWNER_PID, SENDMSG_REPLACE, pid, mode);
    }

    /*package*/ void postBluetoothA2dpDeviceConfigChange(@NonNull BluetoothDevice device) {
        sendLMsgNoDelay(MSG_L_A2DP_DEVICE_CONFIG_CHANGE, SENDMSG_QUEUE, device);
    }

    @GuardedBy("mSetModeLock")
    /*package*/ void startBluetoothScoForClient_Sync(IBinder cb, int scoAudioMode,
                @NonNull String eventSource) {
        synchronized (mDeviceStateLock) {
            // Cancel speakerphone ON request by this same client: speakerphone and BT SCO routes
            // are mutually exclusive.
            // See symmetrical operation for setSpeakerphoneOn(true).
            setSpeakerphoneOffForPid(Binder.getCallingPid());
            mBtHelper.startBluetoothScoForClient(cb, scoAudioMode, eventSource);
        }
    }

    @GuardedBy("mSetModeLock")
    /*package*/ void stopBluetoothScoForClient_Sync(IBinder cb, @NonNull String eventSource) {
        synchronized (mDeviceStateLock) {
            mBtHelper.stopBluetoothScoForClient(cb, eventSource);
        }
    }

    /*package*/ int setPreferredDeviceForStrategySync(int strategy,
                                                      @NonNull AudioDeviceAttributes device) {
        return mDeviceInventory.setPreferredDeviceForStrategySync(strategy, device);
    }

    /*package*/ int removePreferredDeviceForStrategySync(int strategy) {
        return mDeviceInventory.removePreferredDeviceForStrategySync(strategy);
    }

    /*package*/ void registerStrategyPreferredDeviceDispatcher(
            @NonNull IStrategyPreferredDeviceDispatcher dispatcher) {
        mDeviceInventory.registerStrategyPreferredDeviceDispatcher(dispatcher);
    }

    /*package*/ void unregisterStrategyPreferredDeviceDispatcher(
            @NonNull IStrategyPreferredDeviceDispatcher dispatcher) {
        mDeviceInventory.unregisterStrategyPreferredDeviceDispatcher(dispatcher);
    }

    //---------------------------------------------------------------------
    // Communication with (to) AudioService
    //TODO check whether the AudioService methods are candidates to move here
    /*package*/ void postAccessoryPlugMediaUnmute(int device) {
        mAudioService.postAccessoryPlugMediaUnmute(device);
    }

    /*package*/ int getVssVolumeForDevice(int streamType, int device) {
        return mAudioService.getVssVolumeForDevice(streamType, device);
    }

    /*package*/ int getModeOwnerPid() {
        return mModeOwnerPid;
    }

    /*package*/ int getDeviceForStream(int streamType) {
        return mAudioService.getDeviceForStream(streamType);
    }

    /*package*/ void postApplyVolumeOnDevice(int streamType, int device, String caller) {
        mAudioService.postApplyVolumeOnDevice(streamType, device, caller);
    }

    /*package*/ void postSetVolumeIndexOnDevice(int streamType, int vssVolIndex, int device,
                                                String caller) {
        mAudioService.postSetVolumeIndexOnDevice(streamType, vssVolIndex, device, caller);
    }

    /*packages*/ void postObserveDevicesForAllStreams() {
        mAudioService.postObserveDevicesForAllStreams();
    }

    /*package*/ boolean isInCommunication() {
        return mAudioService.isInCommunication();
    }

    /*package*/ boolean hasMediaDynamicPolicy() {
        return mAudioService.hasMediaDynamicPolicy();
    }

    /*package*/ ContentResolver getContentResolver() {
        return mAudioService.getContentResolver();
    }

    /*package*/ void checkMusicActive(int deviceType, String caller) {
        mAudioService.checkMusicActive(deviceType, caller);
    }

    /*package*/ void checkVolumeCecOnHdmiConnection(
            @AudioService.ConnectionState  int state, String caller) {
        mAudioService.postCheckVolumeCecOnHdmiConnection(state, caller);
    }

    /*package*/ boolean hasAudioFocusUsers() {
        return mAudioService.hasAudioFocusUsers();
    }

    //---------------------------------------------------------------------
    // Message handling on behalf of helper classes
    /*package*/ void postBroadcastScoConnectionState(int state) {
        sendIMsgNoDelay(MSG_I_BROADCAST_BT_CONNECTION_STATE, SENDMSG_QUEUE, state);
    }

    /*package*/ void postBroadcastBecomingNoisy() {
        sendMsgNoDelay(MSG_BROADCAST_AUDIO_BECOMING_NOISY, SENDMSG_REPLACE);
    }

    @GuardedBy("mDeviceStateLock")
    /*package*/ void postA2dpSinkConnection(@AudioService.BtProfileConnectionState int state,
            @NonNull BtHelper.BluetoothA2dpDeviceInfo btDeviceInfo, int delay) {
        sendILMsg(state == BluetoothA2dp.STATE_CONNECTED
                        ? MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_CONNECTED
                        : MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_DISCONNECTED,
                SENDMSG_QUEUE,
                state, btDeviceInfo, delay);
    }

    /*package*/ void postA2dpSourceConnection(@AudioService.BtProfileConnectionState int state,
            @NonNull BtHelper.BluetoothA2dpDeviceInfo btDeviceInfo, int delay) {
        sendILMsg(MSG_IL_SET_A2DP_SOURCE_CONNECTION_STATE, SENDMSG_QUEUE,
                state, btDeviceInfo, delay);
    }

    /*package*/ void postSetWiredDeviceConnectionState(
            AudioDeviceInventory.WiredDeviceConnectionState connectionState, int delay) {
        sendLMsg(MSG_L_SET_WIRED_DEVICE_CONNECTION_STATE, SENDMSG_QUEUE, connectionState, delay);
    }

    /*package*/ void postSetHearingAidConnectionState(
            @AudioService.BtProfileConnectionState int state,
            @NonNull BluetoothDevice device, int delay) {
        sendILMsg(MSG_IL_SET_HEARING_AID_CONNECTION_STATE, SENDMSG_QUEUE,
                state,
                device,
                delay);
    }

    /*package*/ void postDisconnectA2dp() {
        sendMsgNoDelay(MSG_DISCONNECT_A2DP, SENDMSG_QUEUE);
    }

    /*package*/ void postDisconnectA2dpSink() {
        sendMsgNoDelay(MSG_DISCONNECT_A2DP_SINK, SENDMSG_QUEUE);
    }

    /*package*/ void postDisconnectHearingAid() {
        sendMsgNoDelay(MSG_DISCONNECT_BT_HEARING_AID, SENDMSG_QUEUE);
    }

    /*package*/ void postDisconnectHeadset() {
        sendMsgNoDelay(MSG_DISCONNECT_BT_HEADSET, SENDMSG_QUEUE);
    }

    /*package*/ void postBtA2dpProfileConnected(BluetoothA2dp a2dpProfile) {
        sendLMsgNoDelay(MSG_L_BT_SERVICE_CONNECTED_PROFILE_A2DP, SENDMSG_QUEUE, a2dpProfile);
    }

    /*package*/ void postBtA2dpSinkProfileConnected(BluetoothProfile profile) {
        sendLMsgNoDelay(MSG_L_BT_SERVICE_CONNECTED_PROFILE_A2DP_SINK, SENDMSG_QUEUE, profile);
    }

    /*package*/ void postBtHeasetProfileConnected(BluetoothHeadset headsetProfile) {
        sendLMsgNoDelay(MSG_L_BT_SERVICE_CONNECTED_PROFILE_HEADSET, SENDMSG_QUEUE, headsetProfile);
    }

    /*package*/ void postBtHearingAidProfileConnected(BluetoothHearingAid hearingAidProfile) {
        sendLMsgNoDelay(MSG_L_BT_SERVICE_CONNECTED_PROFILE_HEARING_AID, SENDMSG_QUEUE,
                hearingAidProfile);
    }

    /*package*/ void postScoClientDied(Object obj) {
        sendLMsgNoDelay(MSG_L_SCOCLIENT_DIED, SENDMSG_QUEUE, obj);
    }

    /*package*/ void postSpeakerphoneClientDied(Object obj) {
        sendLMsgNoDelay(MSG_L_SPEAKERPHONE_CLIENT_DIED, SENDMSG_QUEUE, obj);
    }

    /*package*/ void postSaveSetPreferredDeviceForStrategy(int strategy,
                                                           AudioDeviceAttributes device)
    {
        sendILMsgNoDelay(MSG_IL_SAVE_PREF_DEVICE_FOR_STRATEGY, SENDMSG_QUEUE, strategy, device);
    }

    /*package*/ void postSaveRemovePreferredDeviceForStrategy(int strategy) {
        sendIMsgNoDelay(MSG_I_SAVE_REMOVE_PREF_DEVICE_FOR_STRATEGY, SENDMSG_QUEUE, strategy);
    }

    //---------------------------------------------------------------------
    // Method forwarding between the helper classes (BtHelper, AudioDeviceInventory)
    // only call from a "handle"* method or "on"* method

    // Handles request to override default use of A2DP for media.
    //@GuardedBy("mConnectedDevices")
    /*package*/ void setBluetoothA2dpOnInt(boolean on, boolean fromA2dp, String source) {
        // for logging only
        final String eventSource = new StringBuilder("setBluetoothA2dpOn(").append(on)
                .append(") from u/pid:").append(Binder.getCallingUid()).append("/")
                .append(Binder.getCallingPid()).append(" src:").append(source).toString();

        synchronized (mDeviceStateLock) {
            mBluetoothA2dpEnabled = on;
            mBrokerHandler.removeMessages(MSG_IIL_SET_FORCE_BT_A2DP_USE);
            onSetForceUse(
                    AudioSystem.FOR_MEDIA,
                    mBluetoothA2dpEnabled ? AudioSystem.FORCE_NONE : AudioSystem.FORCE_NO_BT_A2DP,
                    fromA2dp,
                    eventSource);
        }
    }

    /*package*/ boolean handleDeviceConnection(boolean connect, int device, String address,
                                                       String deviceName) {
        synchronized (mDeviceStateLock) {
            return mDeviceInventory.handleDeviceConnection(connect, device, address, deviceName);
        }
    }

    /*package*/ void postSetA2dpSourceConnectionState(@BluetoothProfile.BtProfileState int state,
            @NonNull BtHelper.BluetoothA2dpDeviceInfo btDeviceInfo) {
        final int intState = (state == BluetoothA2dp.STATE_CONNECTED) ? 1 : 0;
        sendILMsgNoDelay(MSG_IL_SET_A2DP_SOURCE_CONNECTION_STATE, SENDMSG_QUEUE, state,
                btDeviceInfo);
    }

    /*package*/ void handleFailureToConnectToBtHeadsetService(int delay) {
        sendMsg(MSG_BT_HEADSET_CNCT_FAILED, SENDMSG_REPLACE, delay);
    }

    /*package*/ void handleCancelFailureToConnectToBtHeadsetService() {
        mBrokerHandler.removeMessages(MSG_BT_HEADSET_CNCT_FAILED);
    }

    /*package*/ void postReportNewRoutes(boolean fromA2dp) {
        sendMsgNoDelay(fromA2dp ? MSG_REPORT_NEW_ROUTES_A2DP : MSG_REPORT_NEW_ROUTES, SENDMSG_NOOP);
    }

    /*package*/ void postA2dpActiveDeviceChange(
                    @NonNull BtHelper.BluetoothA2dpDeviceInfo btDeviceInfo) {
        sendLMsgNoDelay(MSG_L_A2DP_ACTIVE_DEVICE_CHANGE, SENDMSG_QUEUE, btDeviceInfo);
    }

    // must be called synchronized on mConnectedDevices
    /*package*/ boolean hasScheduledA2dpSinkConnectionState(BluetoothDevice btDevice) {
        final BtHelper.BluetoothA2dpDeviceInfo devInfoToCheck =
                new BtHelper.BluetoothA2dpDeviceInfo(btDevice);
        return (mBrokerHandler.hasEqualMessages(
                    MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_CONNECTED, devInfoToCheck)
            || mBrokerHandler.hasEqualMessages(
                    MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_DISCONNECTED, devInfoToCheck));
    }

    /*package*/ void setA2dpTimeout(String address, int a2dpCodec, int delayMs) {
        sendILMsg(MSG_IL_BTA2DP_TIMEOUT, SENDMSG_QUEUE, a2dpCodec, address, delayMs);
    }

    /*package*/ void setAvrcpAbsoluteVolumeSupported(boolean supported) {
        synchronized (mDeviceStateLock) {
            mBtHelper.setAvrcpAbsoluteVolumeSupported(supported);
        }
    }

    /*package*/ boolean getBluetoothA2dpEnabled() {
        synchronized (mDeviceStateLock) {
            return mBluetoothA2dpEnabled;
        }
    }

    /*package*/ int getA2dpCodec(@NonNull BluetoothDevice device) {
        synchronized (mDeviceStateLock) {
            return mBtHelper.getA2dpCodec(device);
        }
    }

    /*package*/ void dump(PrintWriter pw, String prefix) {
        if (mBrokerHandler != null) {
            pw.println(prefix + "Message handler (watch for unhandled messages):");
            mBrokerHandler.dump(new PrintWriterPrinter(pw), prefix + "  ");
        } else {
            pw.println("Message handler is null");
        }

        mDeviceInventory.dump(pw, prefix);

        pw.println("\n" + prefix + "mForcedUseForComm: "
                +  AudioSystem.forceUseConfigToString(mForcedUseForComm));
        pw.println(prefix + "mForcedUseForCommExt: "
                + AudioSystem.forceUseConfigToString(mForcedUseForCommExt));
        pw.println(prefix + "mModeOwnerPid: " + mModeOwnerPid);
        pw.println(prefix + "Speakerphone clients:");
        mSpeakerphoneClients.forEach((cl) -> {
            pw.println("  " + prefix + "pid: " + cl.getPid() + " on: "
                        + cl.isOn() + " cb: " + cl.getBinder()); });

        mBtHelper.dump(pw, prefix);
    }

    //---------------------------------------------------------------------
    // Internal handling of messages
    // These methods are ALL synchronous, in response to message handling in BrokerHandler
    // Blocking in any of those will block the message queue

    private void onSetForceUse(int useCase, int config, boolean fromA2dp, String eventSource) {
        if (useCase == AudioSystem.FOR_MEDIA) {
            postReportNewRoutes(fromA2dp);
        }
        AudioService.sForceUseLogger.log(
                new AudioServiceEvents.ForceUseEvent(useCase, config, eventSource));
        new MediaMetrics.Item(MediaMetrics.Name.AUDIO_FORCE_USE + MediaMetrics.SEPARATOR
                + AudioSystem.forceUseUsageToString(useCase))
                .set(MediaMetrics.Property.EVENT, "onSetForceUse")
                .set(MediaMetrics.Property.FORCE_USE_DUE_TO, eventSource)
                .set(MediaMetrics.Property.FORCE_USE_MODE,
                        AudioSystem.forceUseConfigToString(config))
                .record();
        AudioSystem.setForceUse(useCase, config);
    }

    private void onSendBecomingNoisyIntent() {
        AudioService.sDeviceLogger.log((new AudioEventLogger.StringEvent(
                "broadcast ACTION_AUDIO_BECOMING_NOISY")).printLog(TAG));
        mSystemServer.sendDeviceBecomingNoisyIntent();
    }

    //---------------------------------------------------------------------
    // Message handling
    private BrokerHandler mBrokerHandler;
    private BrokerThread mBrokerThread;
    private PowerManager.WakeLock mBrokerEventWakeLock;

    private void setupMessaging(Context ctxt) {
        final PowerManager pm = (PowerManager) ctxt.getSystemService(Context.POWER_SERVICE);
        mBrokerEventWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "handleAudioDeviceEvent");
        mBrokerThread = new BrokerThread();
        mBrokerThread.start();
        waitForBrokerHandlerCreation();
    }

    private void waitForBrokerHandlerCreation() {
        synchronized (this) {
            while (mBrokerHandler == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interruption while waiting on BrokerHandler");
                }
            }
        }
    }

    /** Class that handles the device broker's message queue */
    private class BrokerThread extends Thread {
        BrokerThread() {
            super("AudioDeviceBroker");
        }

        @Override
        public void run() {
            // Set this thread up so the handler will work on it
            Looper.prepare();

            synchronized (AudioDeviceBroker.this) {
                mBrokerHandler = new BrokerHandler();

                // Notify that the handler has been created
                AudioDeviceBroker.this.notify();
            }

            Looper.loop();
        }
    }

    /** Class that handles the message queue */
    private class BrokerHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_RESTORE_DEVICES:
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.onRestoreDevices();
                        mBtHelper.onAudioServerDiedRestoreA2dp();
                    }
                    break;
                case MSG_L_SET_WIRED_DEVICE_CONNECTION_STATE:
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.onSetWiredDeviceConnectionState(
                                (AudioDeviceInventory.WiredDeviceConnectionState) msg.obj);
                    }
                    break;
                case MSG_I_BROADCAST_BT_CONNECTION_STATE:
                    synchronized (mDeviceStateLock) {
                        mBtHelper.onBroadcastScoConnectionState(msg.arg1);
                    }
                    break;
                case MSG_IIL_SET_FORCE_USE: // intended fall-through
                case MSG_IIL_SET_FORCE_BT_A2DP_USE:
                    onSetForceUse(msg.arg1, msg.arg2,
                                  (msg.what == MSG_IIL_SET_FORCE_BT_A2DP_USE), (String) msg.obj);
                    break;
                case MSG_REPORT_NEW_ROUTES:
                case MSG_REPORT_NEW_ROUTES_A2DP:
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.onReportNewRoutes();
                    }
                    break;
                case MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_CONNECTED:
                case MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_DISCONNECTED:
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.onSetA2dpSinkConnectionState(
                                (BtHelper.BluetoothA2dpDeviceInfo) msg.obj, msg.arg1);
                    }
                    break;
                case MSG_IL_SET_A2DP_SOURCE_CONNECTION_STATE:
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.onSetA2dpSourceConnectionState(
                                (BtHelper.BluetoothA2dpDeviceInfo) msg.obj, msg.arg1);
                    }
                    break;
                case MSG_IL_SET_HEARING_AID_CONNECTION_STATE:
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.onSetHearingAidConnectionState(
                                (BluetoothDevice) msg.obj, msg.arg1,
                                mAudioService.getHearingAidStreamType());
                    }
                    break;
                case MSG_BT_HEADSET_CNCT_FAILED:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            mBtHelper.resetBluetoothSco();
                        }
                    }
                    break;
                case MSG_IL_BTA2DP_TIMEOUT:
                    // msg.obj  == address of BTA2DP device
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.onMakeA2dpDeviceUnavailableNow((String) msg.obj, msg.arg1);
                    }
                    break;
                case MSG_L_A2DP_DEVICE_CONFIG_CHANGE:
                    final int a2dpCodec;
                    final BluetoothDevice btDevice = (BluetoothDevice) msg.obj;
                    synchronized (mDeviceStateLock) {
                        a2dpCodec = mBtHelper.getA2dpCodec(btDevice);
                        // TODO: name of method being called on AudioDeviceInventory is currently
                        //       misleading (config change vs active device change), to be
                        //       reconciliated once the BT side has been updated.
                        mDeviceInventory.onBluetoothA2dpActiveDeviceChange(
                                new BtHelper.BluetoothA2dpDeviceInfo(btDevice, -1, a2dpCodec),
                                        BtHelper.EVENT_DEVICE_CONFIG_CHANGE);
                    }
                    break;
                case MSG_BROADCAST_AUDIO_BECOMING_NOISY:
                    onSendBecomingNoisyIntent();
                    break;
                case MSG_II_SET_HEARING_AID_VOLUME:
                    synchronized (mDeviceStateLock) {
                        mBtHelper.setHearingAidVolume(msg.arg1, msg.arg2);
                    }
                    break;
                case MSG_I_SET_AVRCP_ABSOLUTE_VOLUME:
                    synchronized (mDeviceStateLock) {
                        mBtHelper.setAvrcpAbsoluteVolumeIndex(msg.arg1);
                    }
                    break;
                case MSG_I_SET_MODE_OWNER_PID:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            if (mModeOwnerPid != msg.arg1) {
                                mModeOwnerPid = msg.arg1;
                                if (msg.arg2 != AudioSystem.MODE_RINGTONE) {
                                    updateSpeakerphoneOn("setNewModeOwner");
                                }
                                if (mModeOwnerPid != 0) {
                                    mBtHelper.disconnectBluetoothSco(mModeOwnerPid);
                                }
                            }
                        }
                    }
                    break;
                case MSG_L_SCOCLIENT_DIED:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            mBtHelper.scoClientDied(msg.obj);
                        }
                    }
                    break;
                case MSG_L_SPEAKERPHONE_CLIENT_DIED:
                    synchronized (mDeviceStateLock) {
                        speakerphoneClientDied(msg.obj);
                    }
                    break;
                case MSG_TOGGLE_HDMI:
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.onToggleHdmi();
                    }
                    break;
                case MSG_L_A2DP_ACTIVE_DEVICE_CHANGE:
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.onBluetoothA2dpActiveDeviceChange(
                                (BtHelper.BluetoothA2dpDeviceInfo) msg.obj,
                                 BtHelper.EVENT_ACTIVE_DEVICE_CHANGE);
                    }
                    break;
                case MSG_DISCONNECT_A2DP:
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.disconnectA2dp();
                    }
                    break;
                case MSG_DISCONNECT_A2DP_SINK:
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.disconnectA2dpSink();
                    }
                    break;
                case MSG_DISCONNECT_BT_HEARING_AID:
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.disconnectHearingAid();
                    }
                    break;
                case MSG_DISCONNECT_BT_HEADSET:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            mBtHelper.disconnectHeadset();
                        }
                    }
                    break;
                case MSG_L_BT_SERVICE_CONNECTED_PROFILE_A2DP:
                    synchronized (mDeviceStateLock) {
                        mBtHelper.onA2dpProfileConnected((BluetoothA2dp) msg.obj);
                    }
                    break;
                case MSG_L_BT_SERVICE_CONNECTED_PROFILE_A2DP_SINK:
                    synchronized (mDeviceStateLock) {
                        mBtHelper.onA2dpSinkProfileConnected((BluetoothProfile) msg.obj);
                    }
                    break;
                case MSG_L_BT_SERVICE_CONNECTED_PROFILE_HEARING_AID:
                    synchronized (mDeviceStateLock) {
                        mBtHelper.onHearingAidProfileConnected((BluetoothHearingAid) msg.obj);
                    }
                    break;
                case MSG_L_BT_SERVICE_CONNECTED_PROFILE_HEADSET:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            mBtHelper.onHeadsetProfileConnected((BluetoothHeadset) msg.obj);
                        }
                    }
                    break;
                case MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT_CONNECTION:
                case MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT_DISCONNECTION: {
                    final BtDeviceConnectionInfo info = (BtDeviceConnectionInfo) msg.obj;
                    AudioService.sDeviceLogger.log((new AudioEventLogger.StringEvent(
                            "msg: setBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent "
                                    + " state=" + info.mState
                                    // only querying address as this is the only readily available
                                    // field on the device
                                    + " addr=" + info.mDevice.getAddress()
                                    + " prof=" + info.mProfile + " supprNoisy=" + info.mSupprNoisy
                                    + " vol=" + info.mVolume)).printLog(TAG));
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.setBluetoothA2dpDeviceConnectionState(
                                info.mDevice, info.mState, info.mProfile, info.mSupprNoisy,
                                AudioSystem.DEVICE_NONE, info.mVolume);
                    }
                } break;
                case MSG_L_HEARING_AID_DEVICE_CONNECTION_CHANGE_EXT: {
                    final HearingAidDeviceConnectionInfo info =
                            (HearingAidDeviceConnectionInfo) msg.obj;
                    AudioService.sDeviceLogger.log((new AudioEventLogger.StringEvent(
                            "msg: setHearingAidDeviceConnectionState state=" + info.mState
                                    + " addr=" + info.mDevice.getAddress()
                                    + " supprNoisy=" + info.mSupprNoisy
                                    + " src=" + info.mEventSource)).printLog(TAG));
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.setBluetoothHearingAidDeviceConnectionState(
                                info.mDevice, info.mState, info.mSupprNoisy, info.mMusicDevice);
                    }
                } break;
                case MSG_IL_SAVE_PREF_DEVICE_FOR_STRATEGY: {
                    final int strategy = msg.arg1;
                    final AudioDeviceAttributes device = (AudioDeviceAttributes) msg.obj;
                    mDeviceInventory.onSaveSetPreferredDevice(strategy, device);
                } break;
                case MSG_I_SAVE_REMOVE_PREF_DEVICE_FOR_STRATEGY: {
                    final int strategy = msg.arg1;
                    mDeviceInventory.onSaveRemovePreferredDevice(strategy);
                } break;
                case MSG_CHECK_MUTE_MUSIC:
                    checkMessagesMuteMusic(0);
                    break;
                default:
                    Log.wtf(TAG, "Invalid message " + msg.what);
            }

            // Give some time to Bluetooth service to post a connection message
            // in case of active device switch
            if (MESSAGES_MUTE_MUSIC.contains(msg.what)) {
                sendMsg(MSG_CHECK_MUTE_MUSIC, SENDMSG_REPLACE, BTA2DP_MUTE_CHECK_DELAY_MS);
            }

            if (isMessageHandledUnderWakelock(msg.what)) {
                try {
                    mBrokerEventWakeLock.release();
                } catch (Exception e) {
                    Log.e(TAG, "Exception releasing wakelock", e);
                }
            }
        }
    }

    // List of all messages. If a message has be handled under wakelock, add it to
    //    the isMessageHandledUnderWakelock(int) method
    // Naming of msg indicates arguments, using JNI argument grammar
    // (e.g. II indicates two int args, IL indicates int and Obj arg)
    private static final int MSG_RESTORE_DEVICES = 1;
    private static final int MSG_L_SET_WIRED_DEVICE_CONNECTION_STATE = 2;
    private static final int MSG_I_BROADCAST_BT_CONNECTION_STATE = 3;
    private static final int MSG_IIL_SET_FORCE_USE = 4;
    private static final int MSG_IIL_SET_FORCE_BT_A2DP_USE = 5;
    private static final int MSG_TOGGLE_HDMI = 6;
    private static final int MSG_IL_SET_A2DP_SOURCE_CONNECTION_STATE = 7;
    private static final int MSG_IL_SET_HEARING_AID_CONNECTION_STATE = 8;
    private static final int MSG_BT_HEADSET_CNCT_FAILED = 9;
    private static final int MSG_IL_BTA2DP_TIMEOUT = 10;

    // process change of A2DP device configuration, obj is BluetoothDevice
    private static final int MSG_L_A2DP_DEVICE_CONFIG_CHANGE = 11;

    private static final int MSG_BROADCAST_AUDIO_BECOMING_NOISY = 12;
    private static final int MSG_REPORT_NEW_ROUTES = 13;
    private static final int MSG_II_SET_HEARING_AID_VOLUME = 14;
    private static final int MSG_I_SET_AVRCP_ABSOLUTE_VOLUME = 15;
    private static final int MSG_I_SET_MODE_OWNER_PID = 16;

    // process active A2DP device change, obj is BtHelper.BluetoothA2dpDeviceInfo
    private static final int MSG_L_A2DP_ACTIVE_DEVICE_CHANGE = 18;

    private static final int MSG_DISCONNECT_A2DP = 19;
    private static final int MSG_DISCONNECT_A2DP_SINK = 20;
    private static final int MSG_DISCONNECT_BT_HEARING_AID = 21;
    private static final int MSG_DISCONNECT_BT_HEADSET = 22;
    private static final int MSG_L_BT_SERVICE_CONNECTED_PROFILE_A2DP = 23;
    private static final int MSG_L_BT_SERVICE_CONNECTED_PROFILE_A2DP_SINK = 24;
    private static final int MSG_L_BT_SERVICE_CONNECTED_PROFILE_HEARING_AID = 25;
    private static final int MSG_L_BT_SERVICE_CONNECTED_PROFILE_HEADSET = 26;

    // process change of state, obj is BtHelper.BluetoothA2dpDeviceInfo
    private static final int MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_CONNECTED = 27;
    private static final int MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_DISCONNECTED = 28;

    // process external command to (dis)connect an A2DP device, obj is BtDeviceConnectionInfo
    private static final int MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT_CONNECTION = 29;
    private static final int MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT_DISCONNECTION = 30;

    // process external command to (dis)connect a hearing aid device
    private static final int MSG_L_HEARING_AID_DEVICE_CONNECTION_CHANGE_EXT = 31;

    // a ScoClient died in BtHelper
    private static final int MSG_L_SCOCLIENT_DIED = 32;
    private static final int MSG_IL_SAVE_PREF_DEVICE_FOR_STRATEGY = 33;
    private static final int MSG_I_SAVE_REMOVE_PREF_DEVICE_FOR_STRATEGY = 34;

    private static final int MSG_L_SPEAKERPHONE_CLIENT_DIED = 35;
    private static final int MSG_CHECK_MUTE_MUSIC = 36;
    private static final int MSG_REPORT_NEW_ROUTES_A2DP = 37;


    private static boolean isMessageHandledUnderWakelock(int msgId) {
        switch(msgId) {
            case MSG_L_SET_WIRED_DEVICE_CONNECTION_STATE:
            case MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_CONNECTED:
            case MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_DISCONNECTED:
            case MSG_IL_SET_A2DP_SOURCE_CONNECTION_STATE:
            case MSG_IL_SET_HEARING_AID_CONNECTION_STATE:
            case MSG_IL_BTA2DP_TIMEOUT:
            case MSG_L_A2DP_DEVICE_CONFIG_CHANGE:
            case MSG_TOGGLE_HDMI:
            case MSG_L_A2DP_ACTIVE_DEVICE_CHANGE:
            case MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT_CONNECTION:
            case MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT_DISCONNECTION:
            case MSG_L_HEARING_AID_DEVICE_CONNECTION_CHANGE_EXT:
            case MSG_CHECK_MUTE_MUSIC:
                return true;
            default:
                return false;
        }
    }

    // Message helper methods

    // sendMsg() flags
    /** If the msg is already queued, replace it with this one. */
    private static final int SENDMSG_REPLACE = 0;
    /** If the msg is already queued, ignore this one and leave the old. */
    private static final int SENDMSG_NOOP = 1;
    /** If the msg is already queued, queue this one and leave the old. */
    private static final int SENDMSG_QUEUE = 2;

    private void sendMsg(int msg, int existingMsgPolicy, int delay) {
        sendIILMsg(msg, existingMsgPolicy, 0, 0, null, delay);
    }

    private void sendILMsg(int msg, int existingMsgPolicy, int arg, Object obj, int delay) {
        sendIILMsg(msg, existingMsgPolicy, arg, 0, obj, delay);
    }

    private void sendLMsg(int msg, int existingMsgPolicy, Object obj, int delay) {
        sendIILMsg(msg, existingMsgPolicy, 0, 0, obj, delay);
    }

    private void sendIMsg(int msg, int existingMsgPolicy, int arg, int delay) {
        sendIILMsg(msg, existingMsgPolicy, arg, 0, null, delay);
    }

    private void sendMsgNoDelay(int msg, int existingMsgPolicy) {
        sendIILMsg(msg, existingMsgPolicy, 0, 0, null, 0);
    }

    private void sendIMsgNoDelay(int msg, int existingMsgPolicy, int arg) {
        sendIILMsg(msg, existingMsgPolicy, arg, 0, null, 0);
    }

    private void sendIIMsgNoDelay(int msg, int existingMsgPolicy, int arg1, int arg2) {
        sendIILMsg(msg, existingMsgPolicy, arg1, arg2, null, 0);
    }

    private void sendILMsgNoDelay(int msg, int existingMsgPolicy, int arg, Object obj) {
        sendIILMsg(msg, existingMsgPolicy, arg, 0, obj, 0);
    }

    private void sendLMsgNoDelay(int msg, int existingMsgPolicy, Object obj) {
        sendIILMsg(msg, existingMsgPolicy, 0, 0, obj, 0);
    }

    private void sendIILMsgNoDelay(int msg, int existingMsgPolicy, int arg1, int arg2, Object obj) {
        sendIILMsg(msg, existingMsgPolicy, arg1, arg2, obj, 0);
    }

    private void sendIILMsg(int msg, int existingMsgPolicy, int arg1, int arg2, Object obj,
                            int delay) {
        if (existingMsgPolicy == SENDMSG_REPLACE) {
            mBrokerHandler.removeMessages(msg);
        } else if (existingMsgPolicy == SENDMSG_NOOP && mBrokerHandler.hasMessages(msg)) {
            return;
        }

        if (isMessageHandledUnderWakelock(msg)) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mBrokerEventWakeLock.acquire(BROKER_WAKELOCK_TIMEOUT_MS);
            } catch (Exception e) {
                Log.e(TAG, "Exception acquiring wakelock", e);
            }
            Binder.restoreCallingIdentity(identity);
        }

        if (MESSAGES_MUTE_MUSIC.contains(msg)) {
            checkMessagesMuteMusic(msg);
        }

        synchronized (sLastDeviceConnectionMsgTimeLock) {
            long time = SystemClock.uptimeMillis() + delay;

            switch (msg) {
                case MSG_IL_SET_A2DP_SOURCE_CONNECTION_STATE:
                case MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_CONNECTED:
                case MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_DISCONNECTED:
                case MSG_IL_SET_HEARING_AID_CONNECTION_STATE:
                case MSG_L_SET_WIRED_DEVICE_CONNECTION_STATE:
                case MSG_IL_BTA2DP_TIMEOUT:
                case MSG_L_A2DP_DEVICE_CONFIG_CHANGE:
                case MSG_L_A2DP_ACTIVE_DEVICE_CHANGE:
                    if (sLastDeviceConnectMsgTime >= time) {
                        // add a little delay to make sure messages are ordered as expected
                        time = sLastDeviceConnectMsgTime + 30;
                    }
                    sLastDeviceConnectMsgTime = time;
                    break;
                default:
                    break;
            }
            mBrokerHandler.sendMessageAtTime(mBrokerHandler.obtainMessage(msg, arg1, arg2, obj),
                    time);
        }
    }

    /** List of messages for which music is muted while processing is pending */
    private static final Set<Integer> MESSAGES_MUTE_MUSIC;
    static {
        MESSAGES_MUTE_MUSIC = new HashSet<>();
        MESSAGES_MUTE_MUSIC.add(MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_CONNECTED);
        MESSAGES_MUTE_MUSIC.add(MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_DISCONNECTED);
        MESSAGES_MUTE_MUSIC.add(MSG_L_A2DP_DEVICE_CONFIG_CHANGE);
        MESSAGES_MUTE_MUSIC.add(MSG_L_A2DP_ACTIVE_DEVICE_CHANGE);
        MESSAGES_MUTE_MUSIC.add(MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT_CONNECTION);
        MESSAGES_MUTE_MUSIC.add(MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT_DISCONNECTION);
        MESSAGES_MUTE_MUSIC.add(MSG_IIL_SET_FORCE_BT_A2DP_USE);
        MESSAGES_MUTE_MUSIC.add(MSG_REPORT_NEW_ROUTES_A2DP);
    }

    private AtomicBoolean mMusicMuted = new AtomicBoolean(false);

    /** Mutes or unmutes music according to pending A2DP messages */
    private void checkMessagesMuteMusic(int message) {
        boolean mute = message != 0;
        if (!mute) {
            for (int msg : MESSAGES_MUTE_MUSIC) {
                if (mBrokerHandler.hasMessages(msg)) {
                    mute = true;
                    break;
                }
            }
        }

        if (mute != mMusicMuted.getAndSet(mute)) {
            mAudioService.setMusicMute(mute);
        }
    }

    private class SpeakerphoneClient implements IBinder.DeathRecipient {
        private final IBinder mCb;
        private final int mPid;
        private final boolean mOn;
        SpeakerphoneClient(IBinder cb, int pid, boolean on) {
            mCb = cb;
            mPid = pid;
            mOn = on;
        }

        public boolean registerDeathRecipient() {
            boolean status = false;
            try {
                mCb.linkToDeath(this, 0);
                status = true;
            } catch (RemoteException e) {
                Log.w(TAG, "SpeakerphoneClient could not link to " + mCb + " binder death");
            }
            return status;
        }

        public void unregisterDeathRecipient() {
            try {
                mCb.unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                Log.w(TAG, "SpeakerphoneClient could not not unregistered to binder");
            }
        }

        @Override
        public void binderDied() {
            postSpeakerphoneClientDied(this);
        }

        IBinder getBinder() {
            return mCb;
        }

        int getPid() {
            return mPid;
        }

        boolean isOn() {
            return mOn;
        }
    }

    @GuardedBy("mDeviceStateLock")
    private void speakerphoneClientDied(Object obj) {
        if (obj == null) {
            return;
        }
        Log.w(TAG, "Speaker client died");
        if (removeSpeakerphoneClient(((SpeakerphoneClient) obj).getBinder(), false) != null) {
            updateSpeakerphoneOn("speakerphoneClientDied");
        }
    }

    private SpeakerphoneClient removeSpeakerphoneClient(IBinder cb, boolean unregister) {
        for (SpeakerphoneClient cl : mSpeakerphoneClients) {
            if (cl.getBinder() == cb) {
                if (unregister) {
                    cl.unregisterDeathRecipient();
                }
                mSpeakerphoneClients.remove(cl);
                return cl;
            }
        }
        return null;
    }

    @GuardedBy("mDeviceStateLock")
    private boolean addSpeakerphoneClient(IBinder cb, int pid, boolean on) {
        // always insert new request at first position
        removeSpeakerphoneClient(cb, true);
        SpeakerphoneClient client = new SpeakerphoneClient(cb, pid, on);
        if (client.registerDeathRecipient()) {
            mSpeakerphoneClients.add(0, client);
            return true;
        }
        return false;
    }

    @GuardedBy("mDeviceStateLock")
    private SpeakerphoneClient getSpeakerphoneClientForPid(int pid) {
        for (SpeakerphoneClient cl : mSpeakerphoneClients) {
            if (cl.getPid() == pid) {
                return cl;
            }
        }
        return null;
    }

    // List of clients requesting speakerPhone ON
    @GuardedBy("mDeviceStateLock")
    private final @NonNull ArrayList<SpeakerphoneClient> mSpeakerphoneClients =
            new ArrayList<SpeakerphoneClient>();

}
