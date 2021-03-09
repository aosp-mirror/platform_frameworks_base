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
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRoutesInfo;
import android.media.AudioSystem;
import android.media.IAudioRoutesObserver;
import android.media.ICapturePresetDevicesRoleDispatcher;
import android.media.ICommunicationDeviceDispatcher;
import android.media.IStrategyPreferredDevicesDispatcher;
import android.media.MediaMetrics;
import android.media.audiopolicy.AudioProductStrategy;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.PrintWriterPrinter;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
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
    private AudioDeviceAttributes mPreferredCommunicationDevice;
    private int mCommunicationStrategyId = -1;

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

    private void initCommunicationStrategyId() {
        List<AudioProductStrategy> strategies = AudioProductStrategy.getAudioProductStrategies();
        for (AudioProductStrategy strategy : strategies) {
            if (strategy.getAudioAttributesForLegacyStreamType(AudioSystem.STREAM_VOICE_CALL)
                    != null) {
                mCommunicationStrategyId = strategy.getId();
                return;
            }
        }
        mCommunicationStrategyId = -1;
    }

    private void init() {
        setupMessaging(mContext);

        mPreferredCommunicationDevice = null;
        initCommunicationStrategyId();

        mSystemServer.registerUserStartedReceiver(mContext);
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
     */
    /*package*/ void setSpeakerphoneOn(IBinder cb, int pid, boolean on, String eventSource) {

        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "setSpeakerphoneOn, on: " + on + " pid: " + pid);
        }

        synchronized (mSetModeLock) {
            synchronized (mDeviceStateLock) {
                AudioDeviceAttributes device = null;
                if (on) {
                    device = new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_SPEAKER, "");
                } else {
                    CommunicationRouteClient client = getCommunicationRouteClientForPid(pid);
                    if (client == null || !client.requestsSpeakerphone()) {
                        return;
                    }
                }
                setCommunicationRouteForClient(
                        cb, pid, device, BtHelper.SCO_MODE_UNDEFINED, eventSource);
            }
        }
    }

    /**
     * Select device for use for communication use cases.
     * @param cb Client binder for death detection
     * @param pid Client pid
     * @param device Device selected or null to unselect.
     * @param eventSource for logging purposes
     */
    /*package*/ boolean setCommunicationDevice(
            IBinder cb, int pid, AudioDeviceInfo device, String eventSource) {

        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "setCommunicationDevice, device: " + device + ", pid: " + pid);
        }

        synchronized (mSetModeLock) {
            synchronized (mDeviceStateLock) {
                AudioDeviceAttributes deviceAttr = null;
                if (device != null) {
                    deviceAttr = new AudioDeviceAttributes(device);
                } else {
                    CommunicationRouteClient client = getCommunicationRouteClientForPid(pid);
                    if (client == null) {
                        return false;
                    }
                }
                setCommunicationRouteForClient(
                        cb, pid, deviceAttr, BtHelper.SCO_MODE_UNDEFINED, eventSource);
            }
        }
        return true;
    }

    @GuardedBy("mDeviceStateLock")
    /*package*/ void setCommunicationRouteForClient(
                            IBinder cb, int pid, AudioDeviceAttributes device,
                            int scoAudioMode, String eventSource) {

        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "setCommunicationRouteForClient: device: " + device);
        }
        AudioService.sDeviceLogger.log((new AudioEventLogger.StringEvent(
                                        "setCommunicationRouteForClient for pid: " + pid
                                        + " device: " + device
                                        + " from API: " + eventSource)).printLog(TAG));

        final boolean wasBtScoRequested = isBluetoothScoRequested();
        final boolean wasSpeakerphoneRequested = isSpeakerphoneRequested();
        CommunicationRouteClient client;


        // Save previous client route in case of failure to start BT SCO audio
        AudioDeviceAttributes prevClientDevice = null;
        client = getCommunicationRouteClientForPid(pid);
        if (client != null) {
            prevClientDevice = client.getDevice();
        }

        if (device != null) {
            client = addCommunicationRouteClient(cb, pid, device);
            if (client == null) {
                Log.w(TAG, "setCommunicationRouteForClient: could not add client for pid: "
                        + pid + " and device: " + device);
            }
        } else {
            client = removeCommunicationRouteClient(cb, true);
        }
        if (client == null) {
            return;
        }

        boolean isBtScoRequested = isBluetoothScoRequested();
        if (isBtScoRequested && !wasBtScoRequested) {
            if (!mBtHelper.startBluetoothSco(scoAudioMode, eventSource)) {
                Log.w(TAG, "setCommunicationRouteForClient: failure to start BT SCO for pid: "
                        + pid);
                // clean up or restore previous client selection
                if (prevClientDevice != null) {
                    addCommunicationRouteClient(cb, pid, prevClientDevice);
                } else {
                    removeCommunicationRouteClient(cb, true);
                }
                postBroadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
            }
        } else if (!isBtScoRequested && wasBtScoRequested) {
            mBtHelper.stopBluetoothSco(eventSource);
        }

        if (wasSpeakerphoneRequested != isSpeakerphoneRequested()) {
            try {
                mContext.sendBroadcastAsUser(
                        new Intent(AudioManager.ACTION_SPEAKERPHONE_STATE_CHANGED)
                                .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY), UserHandle.ALL);
            } catch (Exception e) {
                Log.w(TAG, "failed to broadcast ACTION_SPEAKERPHONE_STATE_CHANGED: " + e);
            }
        }

        sendLMsgNoDelay(MSG_L_UPDATE_COMMUNICATION_ROUTE, SENDMSG_QUEUE, eventSource);
    }

    /**
     * Returns the device currently requested for communication use case.
     * If the current audio mode owner is in the communication route client list,
     * use this preference.
     * Otherwise use first client's preference (first client corresponds to latest request).
     * null is returned if no client is in the list.
     * @return AudioDeviceAttributes the requested device for communication.
     */

    @GuardedBy("mDeviceStateLock")
    private AudioDeviceAttributes requestedCommunicationDevice() {
        AudioDeviceAttributes device = null;
        for (CommunicationRouteClient cl : mCommunicationRouteClients) {
            if (cl.getPid() == mModeOwnerPid) {
                device = cl.getDevice();
            }
        }
        if (!mCommunicationRouteClients.isEmpty() && mModeOwnerPid == 0) {
            device = mCommunicationRouteClients.get(0).getDevice();
        }

        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "requestedCommunicationDevice, device: "
                    + device + " mode owner pid: " + mModeOwnerPid);
        }
        return device;
    }

    /**
     * Returns the device currently requested for communication use case.
     * @return AudioDeviceInfo the requested device for communication.
     */
    AudioDeviceInfo getCommunicationDevice() {
        synchronized (mDeviceStateLock) {
            AudioDeviceAttributes device = requestedCommunicationDevice();
            if (device == null) {
                AudioAttributes attr =
                        AudioProductStrategy.getAudioAttributesForStrategyWithLegacyStreamType(
                                AudioSystem.STREAM_VOICE_CALL);
                List<AudioDeviceAttributes> devices = AudioSystem.getDevicesForAttributes(attr);
                if (devices.isEmpty()) {
                    return null;
                }
                device = devices.get(0);
            }
            return AudioManager.getDeviceInfoFromTypeAndAddress(
                    device.getType(), device.getAddress());
        }
    }

    /**
     * Helper method on top of requestedCommunicationDevice() indicating if
     * speakerphone ON is currently requested or not.
     * @return true if speakerphone ON requested, false otherwise.
     */

    private boolean isSpeakerphoneRequested() {
        synchronized (mDeviceStateLock) {
            AudioDeviceAttributes device = requestedCommunicationDevice();
            return device != null
                    && device.getType()
                        == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        }
    }

    /**
     * Indicates if active route selection for communication is speakerphone.
     * @return true if speakerphone is active, false otherwise.
     */
    /*package*/ boolean isSpeakerphoneOn() {
        AudioDeviceAttributes device = getPreferredDeviceForComm();
        if (device == null) {
            return false;
        }
        return device.getInternalType() == AudioSystem.DEVICE_OUT_SPEAKER;
    }

    /**
     * Helper method on top of requestedCommunicationDevice() indicating if
     * Bluetooth SCO ON is currently requested or not.
     * @return true if Bluetooth SCO ON is requested, false otherwise.
     */
    /*package*/ boolean isBluetoothScoRequested() {
        synchronized (mDeviceStateLock) {
            AudioDeviceAttributes device = requestedCommunicationDevice();
            return device != null
                    && device.getType()
                        == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
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


    /**
     * Current Bluetooth SCO audio active state indicated by BtHelper via setBluetoothScoOn().
     */
    private boolean mBluetoothScoOn;

    /*package*/ void setBluetoothScoOn(boolean on, String eventSource) {
        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "setBluetoothScoOn: " + on + " " + eventSource);
        }
        synchronized (mDeviceStateLock) {
            mBluetoothScoOn = on;
            sendLMsgNoDelay(MSG_L_UPDATE_COMMUNICATION_ROUTE, SENDMSG_QUEUE, eventSource);
        }
    }

    /**
     * Indicates if active route selection for communication is Bluetooth SCO.
     * @return true if Bluetooth SCO is active , false otherwise.
     */
    /*package*/ boolean isBluetoothScoOn() {
        AudioDeviceAttributes device = getPreferredDeviceForComm();
        if (device == null) {
            return false;
        }
        return AudioSystem.DEVICE_OUT_ALL_SCO_SET.contains(device.getInternalType());
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

    /*package*/ void startBluetoothScoForClient(IBinder cb, int pid, int scoAudioMode,
                @NonNull String eventSource) {

        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "startBluetoothScoForClient_Sync, pid: " + pid);
        }

        synchronized (mSetModeLock) {
            synchronized (mDeviceStateLock) {
                AudioDeviceAttributes device =
                        new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_BLUETOOTH_SCO, "");
                setCommunicationRouteForClient(cb, pid, device, scoAudioMode, eventSource);
            }
        }
    }

    /*package*/ void stopBluetoothScoForClient(
                        IBinder cb, int pid, @NonNull String eventSource) {

        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "stopBluetoothScoForClient_Sync, pid: " + pid);
        }

        synchronized (mSetModeLock) {
            synchronized (mDeviceStateLock) {
                CommunicationRouteClient client = getCommunicationRouteClientForPid(pid);
                if (client == null || !client.requestsBluetoothSco()) {
                    return;
                }
                setCommunicationRouteForClient(
                        cb, pid, null, BtHelper.SCO_MODE_UNDEFINED, eventSource);
            }
        }
    }

    /*package*/ int setPreferredDevicesForStrategySync(int strategy,
            @NonNull List<AudioDeviceAttributes> devices) {
        return mDeviceInventory.setPreferredDevicesForStrategySync(strategy, devices);
    }

    /*package*/ void postSetPreferredDevicesForStrategy(int strategy,
            @NonNull List<AudioDeviceAttributes> devices) {
        sendILMsgNoDelay(MSG_IL_SET_PREF_DEVICES_FOR_STRATEGY, SENDMSG_REPLACE, strategy, devices);
    }

    /*package*/ int removePreferredDevicesForStrategySync(int strategy) {
        return mDeviceInventory.removePreferredDevicesForStrategySync(strategy);
    }

    /*package*/ void postRemovePreferredDevicesForStrategy(int strategy) {
        sendIMsgNoDelay(MSG_I_REMOVE_PREF_DEVICES_FOR_STRATEGY, SENDMSG_REPLACE, strategy);
    }

    /*package*/ void registerStrategyPreferredDevicesDispatcher(
            @NonNull IStrategyPreferredDevicesDispatcher dispatcher) {
        mDeviceInventory.registerStrategyPreferredDevicesDispatcher(dispatcher);
    }

    /*package*/ void unregisterStrategyPreferredDevicesDispatcher(
            @NonNull IStrategyPreferredDevicesDispatcher dispatcher) {
        mDeviceInventory.unregisterStrategyPreferredDevicesDispatcher(dispatcher);
    }

    /*package*/ int setPreferredDevicesForCapturePresetSync(int capturePreset,
            @NonNull List<AudioDeviceAttributes> devices) {
        return mDeviceInventory.setPreferredDevicesForCapturePresetSync(capturePreset, devices);
    }

    /*package*/ int clearPreferredDevicesForCapturePresetSync(int capturePreset) {
        return mDeviceInventory.clearPreferredDevicesForCapturePresetSync(capturePreset);
    }

    /*package*/ void registerCapturePresetDevicesRoleDispatcher(
            @NonNull ICapturePresetDevicesRoleDispatcher dispatcher) {
        mDeviceInventory.registerCapturePresetDevicesRoleDispatcher(dispatcher);
    }

    /*package*/ void unregisterCapturePresetDevicesRoleDispatcher(
            @NonNull ICapturePresetDevicesRoleDispatcher dispatcher) {
        mDeviceInventory.unregisterCapturePresetDevicesRoleDispatcher(dispatcher);
    }

    /*package*/ void registerCommunicationDeviceDispatcher(
            @NonNull ICommunicationDeviceDispatcher dispatcher) {
        mCommDevDispatchers.register(dispatcher);
    }

    /*package*/ void unregisterCommunicationDeviceDispatcher(
            @NonNull ICommunicationDeviceDispatcher dispatcher) {
        mCommDevDispatchers.unregister(dispatcher);
    }

    // Monitoring of communication device
    final RemoteCallbackList<ICommunicationDeviceDispatcher> mCommDevDispatchers =
            new RemoteCallbackList<ICommunicationDeviceDispatcher>();

    // portId of the device currently selected for communication: avoids broadcasting changes
    // when same communication route is applied
    @GuardedBy("mDeviceStateLock")
    int mCurCommunicationPortId = -1;

    @GuardedBy("mDeviceStateLock")
    private void dispatchCommunicationDevice() {
        AudioDeviceInfo device = getCommunicationDevice();
        int portId = (getCommunicationDevice() == null) ? 0 : device.getId();
        if (portId == mCurCommunicationPortId) {
            return;
        }
        mCurCommunicationPortId = portId;

        final int nbDispatchers = mCommDevDispatchers.beginBroadcast();
        for (int i = 0; i < nbDispatchers; i++) {
            try {
                mCommDevDispatchers.getBroadcastItem(i)
                        .dispatchCommunicationDeviceChanged(portId);
            } catch (RemoteException e) {
            }
        }
        mCommDevDispatchers.finishBroadcast();
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

    /*package*/ void postCommunicationRouteClientDied(CommunicationRouteClient client) {
        sendLMsgNoDelay(MSG_L_COMMUNICATION_ROUTE_CLIENT_DIED, SENDMSG_QUEUE, client);
    }

    /*package*/ void postSaveSetPreferredDevicesForStrategy(int strategy,
                                                            List<AudioDeviceAttributes> devices)
    {
        sendILMsgNoDelay(MSG_IL_SAVE_PREF_DEVICES_FOR_STRATEGY, SENDMSG_QUEUE, strategy, devices);
    }

    /*package*/ void postSaveRemovePreferredDevicesForStrategy(int strategy) {
        sendIMsgNoDelay(MSG_I_SAVE_REMOVE_PREF_DEVICES_FOR_STRATEGY, SENDMSG_QUEUE, strategy);
    }

    /*package*/ void postSaveSetPreferredDevicesForCapturePreset(
            int capturePreset, List<AudioDeviceAttributes> devices) {
        sendILMsgNoDelay(
                MSG_IL_SAVE_PREF_DEVICES_FOR_CAPTURE_PRESET, SENDMSG_QUEUE, capturePreset, devices);
    }

    /*package*/ void postSaveClearPreferredDevicesForCapturePreset(int capturePreset) {
        sendIMsgNoDelay(
                MSG_I_SAVE_CLEAR_PREF_DEVICES_FOR_CAPTURE_PRESET, SENDMSG_QUEUE, capturePreset);
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

    /*package*/ void broadcastStickyIntentToCurrentProfileGroup(Intent intent) {
        mSystemServer.broadcastStickyIntentToCurrentProfileGroup(intent);
    }

    /*package*/ void dump(PrintWriter pw, String prefix) {
        if (mBrokerHandler != null) {
            pw.println(prefix + "Message handler (watch for unhandled messages):");
            mBrokerHandler.dump(new PrintWriterPrinter(pw), prefix + "  ");
        } else {
            pw.println("Message handler is null");
        }

        mDeviceInventory.dump(pw, prefix);

        pw.println("\n" + prefix + "Communication route clients:");
        mCommunicationRouteClients.forEach((cl) -> {
            pw.println("  " + prefix + "pid: " + cl.getPid() + " device: "
                        + cl.getDevice() + " cb: " + cl.getBinder()); });

        pw.println("\n" + prefix + "mPreferredCommunicationDevice: "
                +  mPreferredCommunicationDevice);
        pw.println(prefix + "Selected Communication Device: "
                +  ((getCommunicationDevice() == null) ? "None"
                        : new AudioDeviceAttributes(getCommunicationDevice())));
        pw.println(prefix + "mCommunicationStrategyId: "
                +  mCommunicationStrategyId);

        pw.println("\n" + prefix + "mModeOwnerPid: " + mModeOwnerPid);

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

        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "onSetForceUse(useCase<" + useCase + ">, config<" + config + ">, fromA2dp<"
                    + fromA2dp + ">, eventSource<" + eventSource + ">)");
        }
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
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            initCommunicationStrategyId();
                            mDeviceInventory.onRestoreDevices();
                            mBtHelper.onAudioServerDiedRestoreA2dp();
                            onUpdateCommunicationRoute("MSG_RESTORE_DEVICES");
                        }
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
                                    onUpdateCommunicationRoute("setNewModeOwner");
                                }
                            }
                        }
                    }
                    break;
                case MSG_L_COMMUNICATION_ROUTE_CLIENT_DIED:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            onCommunicationRouteClientDied((CommunicationRouteClient) msg.obj);
                        }
                    }
                    break;
                case MSG_L_UPDATE_COMMUNICATION_ROUTE:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            onUpdateCommunicationRoute((String) msg.obj);
                        }
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
                case MSG_IL_SAVE_PREF_DEVICES_FOR_STRATEGY: {
                    final int strategy = msg.arg1;
                    final List<AudioDeviceAttributes> devices =
                            (List<AudioDeviceAttributes>) msg.obj;
                    mDeviceInventory.onSaveSetPreferredDevices(strategy, devices);
                } break;
                case MSG_I_SAVE_REMOVE_PREF_DEVICES_FOR_STRATEGY: {
                    final int strategy = msg.arg1;
                    mDeviceInventory.onSaveRemovePreferredDevices(strategy);
                } break;
                case MSG_IL_SET_PREF_DEVICES_FOR_STRATEGY: {
                    final int strategy = msg.arg1;
                    final List<AudioDeviceAttributes> devices =
                            (List<AudioDeviceAttributes>) msg.obj;
                    setPreferredDevicesForStrategySync(strategy, devices);

                } break;
                case MSG_I_REMOVE_PREF_DEVICES_FOR_STRATEGY: {
                    final int strategy = msg.arg1;
                    removePreferredDevicesForStrategySync(strategy);
                } break;
                case MSG_CHECK_MUTE_MUSIC:
                    checkMessagesMuteMusic(0);
                    break;
                case MSG_IL_SAVE_PREF_DEVICES_FOR_CAPTURE_PRESET: {
                    final int capturePreset = msg.arg1;
                    final List<AudioDeviceAttributes> devices =
                            (List<AudioDeviceAttributes>) msg.obj;
                    mDeviceInventory.onSaveSetPreferredDevicesForCapturePreset(
                            capturePreset, devices);
                } break;
                case MSG_I_SAVE_CLEAR_PREF_DEVICES_FOR_CAPTURE_PRESET: {
                    final int capturePreset = msg.arg1;
                    mDeviceInventory.onSaveClearPreferredDevicesForCapturePreset(capturePreset);
                } break;
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

    private static final int MSG_IL_SAVE_PREF_DEVICES_FOR_STRATEGY = 32;
    private static final int MSG_I_SAVE_REMOVE_PREF_DEVICES_FOR_STRATEGY = 33;

    private static final int MSG_L_COMMUNICATION_ROUTE_CLIENT_DIED = 34;
    private static final int MSG_CHECK_MUTE_MUSIC = 35;
    private static final int MSG_REPORT_NEW_ROUTES_A2DP = 36;

    private static final int MSG_IL_SAVE_PREF_DEVICES_FOR_CAPTURE_PRESET = 37;
    private static final int MSG_I_SAVE_CLEAR_PREF_DEVICES_FOR_CAPTURE_PRESET = 38;

    private static final int MSG_L_UPDATE_COMMUNICATION_ROUTE = 39;
    private static final int MSG_IL_SET_PREF_DEVICES_FOR_STRATEGY = 40;
    private static final int MSG_I_REMOVE_PREF_DEVICES_FOR_STRATEGY = 41;

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

    // List of applications requesting a specific route for communication.
    @GuardedBy("mDeviceStateLock")
    private final @NonNull LinkedList<CommunicationRouteClient> mCommunicationRouteClients =
            new LinkedList<CommunicationRouteClient>();

    private class CommunicationRouteClient implements IBinder.DeathRecipient {
        private final IBinder mCb;
        private final int mPid;
        private AudioDeviceAttributes mDevice;

        CommunicationRouteClient(IBinder cb, int pid, AudioDeviceAttributes device) {
            mCb = cb;
            mPid = pid;
            mDevice = device;
        }

        public boolean registerDeathRecipient() {
            boolean status = false;
            try {
                mCb.linkToDeath(this, 0);
                status = true;
            } catch (RemoteException e) {
                Log.w(TAG, "CommunicationRouteClient could not link to " + mCb + " binder death");
            }
            return status;
        }

        public void unregisterDeathRecipient() {
            try {
                mCb.unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                Log.w(TAG, "CommunicationRouteClient could not not unregistered to binder");
            }
        }

        @Override
        public void binderDied() {
            postCommunicationRouteClientDied(this);
        }

        IBinder getBinder() {
            return mCb;
        }

        int getPid() {
            return mPid;
        }

        AudioDeviceAttributes getDevice() {
            return mDevice;
        }

        boolean requestsBluetoothSco() {
            return mDevice != null
                    && mDevice.getType()
                        == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
        }

        boolean requestsSpeakerphone() {
            return mDevice != null
                    && mDevice.getType()
                        == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        }
    }

    // @GuardedBy("mSetModeLock")
    @GuardedBy("mDeviceStateLock")
    private void onCommunicationRouteClientDied(CommunicationRouteClient client) {
        if (client == null) {
            return;
        }
        Log.w(TAG, "Speaker client died");
        if (removeCommunicationRouteClient(client.getBinder(), false)
                != null) {
            onUpdateCommunicationRoute("onCommunicationRouteClientDied");
        }
    }

    /**
     * Determines which forced usage for communication should be sent to audio policy manager
     * as a function of current SCO audio activation state and active communication route requests.
     * SCO audio state has the highest priority as it can result from external activation by
     * telephony service.
     * @return selected forced usage for communication.
     */
    @GuardedBy("mDeviceStateLock")
    @Nullable private AudioDeviceAttributes getPreferredDeviceForComm() {
        boolean btSCoOn = mBluetoothScoOn && mBtHelper.isBluetoothScoOn();
        if (btSCoOn) {
            // Use the SCO device known to BtHelper so that it matches exactly
            // what has been communicated to audio policy manager. The device
            // returned by requestedCommunicationDevice() can be a dummy SCO device if legacy
            // APIs are used to start SCO audio.
            AudioDeviceAttributes device = mBtHelper.getHeadsetAudioDevice();
            if (device != null) {
                return device;
            }
        }
        AudioDeviceAttributes device = requestedCommunicationDevice();
        if (device == null
                || AudioSystem.DEVICE_OUT_ALL_SCO_SET.contains(device.getInternalType())) {
            // Do not indicate BT SCO selection if SCO is requested but SCO is not ON
            return null;
        }
        return device;
    }

    /**
     * Configures audio policy manager and audio HAL according to active communication route.
     * Always called from message Handler.
     */
    // @GuardedBy("mSetModeLock")
    @GuardedBy("mDeviceStateLock")
    private void onUpdateCommunicationRoute(String eventSource) {
        mPreferredCommunicationDevice = getPreferredDeviceForComm();
        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "onUpdateCommunicationRoute, mPreferredCommunicationDevice: "
                    + mPreferredCommunicationDevice + " eventSource: " + eventSource);
        }

        if (mPreferredCommunicationDevice == null
                || !AudioSystem.DEVICE_OUT_ALL_SCO_SET.contains(
                        mPreferredCommunicationDevice.getInternalType())) {
            AudioSystem.setParameters("BT_SCO=off");
        } else {
            AudioSystem.setParameters("BT_SCO=on");
        }
        if (mPreferredCommunicationDevice == null) {
            postRemovePreferredDevicesForStrategy(mCommunicationStrategyId);
        } else {
            postSetPreferredDevicesForStrategy(
                    mCommunicationStrategyId, Arrays.asList(mPreferredCommunicationDevice));
        }
        mAudioService.postUpdateRingerModeServiceInt();
        dispatchCommunicationDevice();
    }

    private CommunicationRouteClient removeCommunicationRouteClient(
                    IBinder cb, boolean unregister) {
        for (CommunicationRouteClient cl : mCommunicationRouteClients) {
            if (cl.getBinder() == cb) {
                if (unregister) {
                    cl.unregisterDeathRecipient();
                }
                mCommunicationRouteClients.remove(cl);
                return cl;
            }
        }
        return null;
    }

    @GuardedBy("mDeviceStateLock")
    private CommunicationRouteClient addCommunicationRouteClient(
                    IBinder cb, int pid, AudioDeviceAttributes device) {
        // always insert new request at first position
        removeCommunicationRouteClient(cb, true);
        CommunicationRouteClient client = new CommunicationRouteClient(cb, pid, device);
        if (client.registerDeathRecipient()) {
            mCommunicationRouteClients.add(0, client);
            return client;
        }
        return null;
    }

    @GuardedBy("mDeviceStateLock")
    private CommunicationRouteClient getCommunicationRouteClientForPid(int pid) {
        for (CommunicationRouteClient cl : mCommunicationRouteClients) {
            if (cl.getPid() == pid) {
                return cl;
            }
        }
        return null;
    }
}
