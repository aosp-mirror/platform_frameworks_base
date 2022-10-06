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
import android.app.compat.CompatChanges;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRoutesInfo;
import android.media.AudioSystem;
import android.media.BluetoothProfileConnectionInfo;
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
import java.util.UUID;
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

    /** ID for Communication strategy retrieved form audio policy manager */
    private int mCommunicationStrategyId = -1;

    /** ID for Accessibility strategy retrieved form audio policy manager */
    private int mAccessibilityStrategyId = -1;


    /** Active communication device reported by audio policy manager */
    private AudioDeviceInfo mActiveCommunicationDevice;
    /** Last preferred device set for communication strategy */
    private AudioDeviceAttributes mPreferredCommunicationDevice;

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

    /** AudioModeInfo contains information on current audio mode owner
     * communicated by AudioService */
    /* package */ static final class AudioModeInfo {
        /** Current audio mode */
        final int mMode;
        /** PID of current audio mode owner */
        final int mPid;
        /** UID of current audio mode owner */
        final int mUid;

        AudioModeInfo(int mode, int pid, int uid) {
            mMode = mode;
            mPid = pid;
            mUid = uid;
        }

        @Override
        public String toString() {
            return "AudioModeInfo: mMode=" + AudioSystem.modeToString(mMode)
                    + ", mPid=" + mPid
                    + ", mUid=" + mUid;
        }
    };

    private AudioModeInfo mAudioModeOwner = new AudioModeInfo(AudioSystem.MODE_NORMAL, 0, 0);

    /**
     * Indicates that default communication device is chosen by routing rules in audio policy
     * manager and not forced by AudioDeviceBroker.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.S_V2)
    public static final long USE_SET_COMMUNICATION_DEVICE = 243827847L;

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

    private void initRoutingStrategyIds() {
        List<AudioProductStrategy> strategies = AudioProductStrategy.getAudioProductStrategies();
        mCommunicationStrategyId = -1;
        mAccessibilityStrategyId = -1;
        for (AudioProductStrategy strategy : strategies) {
            if (mCommunicationStrategyId == -1
                    && strategy.getAudioAttributesForLegacyStreamType(
                            AudioSystem.STREAM_VOICE_CALL) != null) {
                mCommunicationStrategyId = strategy.getId();
            }
            if (mAccessibilityStrategyId == -1
                    && strategy.getAudioAttributesForLegacyStreamType(
                            AudioSystem.STREAM_ACCESSIBILITY) != null) {
                mAccessibilityStrategyId = strategy.getId();
            }
        }
    }

    private void init() {
        setupMessaging(mContext);

        initRoutingStrategyIds();
        mPreferredCommunicationDevice = null;
        updateActiveCommunicationDevice();

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
                mAudioModeOwner = mAudioService.getAudioModeOwner();
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
                postSetCommunicationRouteForClient(new CommunicationClientInfo(
                        cb, pid, device, BtHelper.SCO_MODE_UNDEFINED, eventSource));
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
                postSetCommunicationRouteForClient(new CommunicationClientInfo(
                        cb, pid, deviceAttr, BtHelper.SCO_MODE_UNDEFINED, eventSource));
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
        if (isBtScoRequested && (!wasBtScoRequested || !isBluetoothScoActive())) {
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

        sendLMsgNoDelay(MSG_L_UPDATE_COMMUNICATION_ROUTE, SENDMSG_QUEUE, eventSource);
    }

    /**
     * Returns the communication client with the highest priority:
     * - 1) the client which is currently also controlling the audio mode
     * - 2) the first client in the stack if there is no audio mode owner
     * - 3) no client otherwise
     * @return CommunicationRouteClient the client driving the communication use case routing.
     */
    @GuardedBy("mDeviceStateLock")
    private CommunicationRouteClient topCommunicationRouteClient() {
        for (CommunicationRouteClient crc : mCommunicationRouteClients) {
            if (crc.getPid() == mAudioModeOwner.mPid) {
                return crc;
            }
        }
        if (!mCommunicationRouteClients.isEmpty() && mAudioModeOwner.mPid == 0) {
            return mCommunicationRouteClients.get(0);
        }
        return null;
    }

    /**
     * Returns the device currently requested for communication use case.
     * Use the device requested by the communication route client selected by
     * {@link #topCommunicationRouteClient()} if any or none otherwise.
     * @return AudioDeviceAttributes the requested device for communication.
     */
    @GuardedBy("mDeviceStateLock")
    private AudioDeviceAttributes requestedCommunicationDevice() {
        CommunicationRouteClient crc = topCommunicationRouteClient();
        AudioDeviceAttributes device = crc != null ? crc.getDevice() : null;
        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "requestedCommunicationDevice, device: "
                    + device + "mAudioModeOwner: " + mAudioModeOwner.toString());
        }
        return device;
    }

    /**
     * Returns the device currently requested for communication use case.
     * @return AudioDeviceInfo the requested device for communication.
     */
    /* package */ AudioDeviceInfo getCommunicationDevice() {
        synchronized (mDeviceStateLock) {
            updateActiveCommunicationDevice();
            return mActiveCommunicationDevice;
        }
    }

    /**
     * Updates currently active communication device (mActiveCommunicationDevice).
     */
    @GuardedBy("mDeviceStateLock")
    void updateActiveCommunicationDevice() {
        AudioDeviceAttributes device = preferredCommunicationDevice();
        if (device == null) {
            AudioAttributes attr =
                    AudioProductStrategy.getAudioAttributesForStrategyWithLegacyStreamType(
                            AudioSystem.STREAM_VOICE_CALL);
            List<AudioDeviceAttributes> devices = AudioSystem.getDevicesForAttributes(
                    attr, false /* forVolume */);
            if (devices.isEmpty()) {
                if (mAudioService.isPlatformVoice()) {
                    Log.w(TAG,
                            "updateActiveCommunicationDevice(): no device for phone strategy");
                }
                mActiveCommunicationDevice = null;
                return;
            }
            device = devices.get(0);
        }
        mActiveCommunicationDevice = AudioManager.getDeviceInfoFromTypeAndAddress(
                device.getType(), device.getAddress());
    }

    /**
     * Indicates if the device which type is passed as argument is currently resquested to be used
     * for communication.
     * @param deviceType the device type the query applies to.
     * @return true if this device type is requested for communication.
     */
    private boolean isDeviceRequestedForCommunication(int deviceType) {
        synchronized (mDeviceStateLock) {
            AudioDeviceAttributes device = requestedCommunicationDevice();
            return device != null && device.getType() == deviceType;
        }
    }

    /**
     * Indicates if the device which type is passed as argument is currently either resquested
     * to be used for communication or selected for an other reason (e.g bluetooth SCO audio
     * is active for SCO device).
     * @param deviceType the device type the query applies to.
     * @return true if this device type is requested for communication.
     */
    private boolean isDeviceOnForCommunication(int deviceType) {
        synchronized (mDeviceStateLock) {
            AudioDeviceAttributes device = preferredCommunicationDevice();
            return device != null && device.getType() == deviceType;
        }
    }

    /**
     * Indicates if the device which type is passed as argument is active for communication.
     * Active means not only currently used by audio policy manager for communication strategy
     * but also explicitly requested for use by communication strategy.
     * @param deviceType the device type the query applies to.
     * @return true if this device type is requested for communication.
     */
    private boolean isDeviceActiveForCommunication(int deviceType) {
        return mActiveCommunicationDevice != null
                && mActiveCommunicationDevice.getType() == deviceType
                && mPreferredCommunicationDevice != null
                && mPreferredCommunicationDevice.getType() == deviceType;
    }

    /**
     * Helper method on top of isDeviceRequestedForCommunication() indicating if
     * speakerphone ON is currently requested or not.
     * @return true if speakerphone ON requested, false otherwise.
     */
    private boolean isSpeakerphoneRequested() {
        return isDeviceRequestedForCommunication(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
    }

    /**
     * Indicates if preferred route selection for communication is speakerphone.
     * @return true if speakerphone is active, false otherwise.
     */
    /*package*/ boolean isSpeakerphoneOn() {
        return isDeviceOnForCommunication(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
    }

    private boolean isSpeakerphoneActive() {
        return isDeviceActiveForCommunication(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
    }

    /**
     * Helper method on top of isDeviceRequestedForCommunication() indicating if
     * Bluetooth SCO ON is currently requested or not.
     * @return true if Bluetooth SCO ON is requested, false otherwise.
     */
    /*package*/ boolean isBluetoothScoRequested() {
        return isDeviceRequestedForCommunication(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
    }

    /**
     * Indicates if preferred route selection for communication is Bluetooth SCO.
     * @return true if Bluetooth SCO is preferred , false otherwise.
     */
    /*package*/ boolean isBluetoothScoOn() {
        return isDeviceOnForCommunication(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
    }

    /*package*/ boolean isBluetoothScoActive() {
        return isDeviceActiveForCommunication(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
    }

    /*package*/ boolean isDeviceConnected(@NonNull AudioDeviceAttributes device) {
        synchronized (mDeviceStateLock) {
            return mDeviceInventory.isDeviceConnected(device);
        }
    }

    /*package*/ void setWiredDeviceConnectionState(AudioDeviceAttributes attributes,
            @AudioService.ConnectionState int state, String caller) {
        //TODO move logging here just like in setBluetooth* methods
        synchronized (mDeviceStateLock) {
            mDeviceInventory.setWiredDeviceConnectionState(attributes, state, caller);
        }
    }

    /*package*/ void setTestDeviceConnectionState(@NonNull AudioDeviceAttributes device,
            @AudioService.ConnectionState int state) {
        synchronized (mDeviceStateLock) {
            mDeviceInventory.setTestDeviceConnectionState(device, state);
        }
    }

    /*package*/ static final class BleVolumeInfo {
        final int mIndex;
        final int mMaxIndex;
        final int mStreamType;

        BleVolumeInfo(int index, int maxIndex, int streamType) {
            mIndex = index;
            mMaxIndex = maxIndex;
            mStreamType = streamType;
        }
    };

    /*package*/ static final class BtDeviceChangedData {
        final @Nullable BluetoothDevice mNewDevice;
        final @Nullable BluetoothDevice mPreviousDevice;
        final @NonNull BluetoothProfileConnectionInfo mInfo;
        final @NonNull String mEventSource;

        BtDeviceChangedData(@Nullable BluetoothDevice newDevice,
                @Nullable BluetoothDevice previousDevice,
                @NonNull BluetoothProfileConnectionInfo info, @NonNull String eventSource) {
            mNewDevice = newDevice;
            mPreviousDevice = previousDevice;
            mInfo = info;
            mEventSource = eventSource;
        }

        @Override
        public String toString() {
            return "BtDeviceChangedData profile=" + BluetoothProfile.getProfileName(
                    mInfo.getProfile())
                + ", switch device: [" + mPreviousDevice + "] -> [" + mNewDevice + "]";
        }
    }

    /*package*/ static final class BtDeviceInfo {
        final @NonNull BluetoothDevice mDevice;
        final @AudioService.BtProfileConnectionState int mState;
        final @AudioService.BtProfile int mProfile;
        final boolean mSupprNoisy;
        final int mVolume;
        final boolean mIsLeOutput;
        final @NonNull String mEventSource;
        final @AudioSystem.AudioFormatNativeEnumForBtCodec int mCodec;
        final int mAudioSystemDevice;
        final int mMusicDevice;

        BtDeviceInfo(@NonNull BtDeviceChangedData d, @NonNull BluetoothDevice device, int state,
                    int audioDevice, @AudioSystem.AudioFormatNativeEnumForBtCodec int codec) {
            mDevice = device;
            mState = state;
            mProfile = d.mInfo.getProfile();
            mSupprNoisy = d.mInfo.isSuppressNoisyIntent();
            mVolume = d.mInfo.getVolume();
            mIsLeOutput = d.mInfo.isLeOutput();
            mEventSource = d.mEventSource;
            mAudioSystemDevice = audioDevice;
            mMusicDevice = AudioSystem.DEVICE_NONE;
            mCodec = codec;
        }

        // constructor used by AudioDeviceBroker to search similar message
        BtDeviceInfo(@NonNull BluetoothDevice device, int profile) {
            mDevice = device;
            mProfile = profile;
            mEventSource = "";
            mMusicDevice = AudioSystem.DEVICE_NONE;
            mCodec = AudioSystem.AUDIO_FORMAT_DEFAULT;
            mAudioSystemDevice = 0;
            mState = 0;
            mSupprNoisy = false;
            mVolume = -1;
            mIsLeOutput = false;
        }

        // constructor used by AudioDeviceInventory when config change failed
        BtDeviceInfo(@NonNull BluetoothDevice device, int profile, int state, int musicDevice,
                    int audioSystemDevice) {
            mDevice = device;
            mProfile = profile;
            mEventSource = "";
            mMusicDevice = musicDevice;
            mCodec = AudioSystem.AUDIO_FORMAT_DEFAULT;
            mAudioSystemDevice = audioSystemDevice;
            mState = state;
            mSupprNoisy = false;
            mVolume = -1;
            mIsLeOutput = false;
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
            if (o instanceof BtDeviceInfo) {
                return mProfile == ((BtDeviceInfo) o).mProfile
                    && mDevice.equals(((BtDeviceInfo) o).mDevice);
            }
            return false;
        }
    }

    BtDeviceInfo createBtDeviceInfo(@NonNull BtDeviceChangedData d, @NonNull BluetoothDevice device,
                int state) {
        int audioDevice;
        int codec = AudioSystem.AUDIO_FORMAT_DEFAULT;
        switch (d.mInfo.getProfile()) {
            case BluetoothProfile.A2DP_SINK:
                audioDevice = AudioSystem.DEVICE_IN_BLUETOOTH_A2DP;
                break;
            case BluetoothProfile.A2DP:
                audioDevice = AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP;
                synchronized (mDeviceStateLock) {
                    codec = mBtHelper.getA2dpCodec(device);
                }
                break;
            case BluetoothProfile.HEARING_AID:
                audioDevice = AudioSystem.DEVICE_OUT_HEARING_AID;
                break;
            case BluetoothProfile.LE_AUDIO:
                if (d.mInfo.isLeOutput()) {
                    audioDevice = AudioSystem.DEVICE_OUT_BLE_HEADSET;
                } else {
                    audioDevice = AudioSystem.DEVICE_IN_BLE_HEADSET;
                }
                break;
            case BluetoothProfile.LE_AUDIO_BROADCAST:
                audioDevice = AudioSystem.DEVICE_OUT_BLE_BROADCAST;
                break;
            default: throw new IllegalArgumentException("Invalid profile " + d.mInfo.getProfile());
        }
        return new BtDeviceInfo(d, device, state, audioDevice, codec);
    }

    private void btMediaMetricRecord(@NonNull BluetoothDevice device, String state,
            @NonNull BtDeviceChangedData data) {
        final String name = TextUtils.emptyIfNull(device.getName());
        new MediaMetrics.Item(MediaMetrics.Name.AUDIO_DEVICE + MediaMetrics.SEPARATOR
                + "queueOnBluetoothActiveDeviceChanged")
            .set(MediaMetrics.Property.STATE, state)
            .set(MediaMetrics.Property.STATUS, data.mInfo.getProfile())
            .set(MediaMetrics.Property.NAME, name)
            .record();
    }

    /**
     * will block on mDeviceStateLock, which is held during an A2DP (dis) connection
     * not just a simple message post
     * @param info struct with the (dis)connection information
     */
    /*package*/ void queueOnBluetoothActiveDeviceChanged(@NonNull BtDeviceChangedData data) {
        if (data.mInfo.getProfile() == BluetoothProfile.A2DP && data.mPreviousDevice != null
                && data.mPreviousDevice.equals(data.mNewDevice)) {
            final String name = TextUtils.emptyIfNull(data.mNewDevice.getName());
            new MediaMetrics.Item(MediaMetrics.Name.AUDIO_DEVICE + MediaMetrics.SEPARATOR
                    + "queueOnBluetoothActiveDeviceChanged_update")
                    .set(MediaMetrics.Property.NAME, name)
                    .set(MediaMetrics.Property.STATUS, data.mInfo.getProfile())
                    .record();
            synchronized (mDeviceStateLock) {
                postBluetoothA2dpDeviceConfigChange(data.mNewDevice);
            }
        } else {
            synchronized (mDeviceStateLock) {
                if (data.mPreviousDevice != null) {
                    btMediaMetricRecord(data.mPreviousDevice, MediaMetrics.Value.DISCONNECTED,
                            data);
                    sendLMsgNoDelay(MSG_L_BT_ACTIVE_DEVICE_CHANGE_EXT, SENDMSG_QUEUE,
                            createBtDeviceInfo(data, data.mPreviousDevice,
                                    BluetoothProfile.STATE_DISCONNECTED));
                }
                if (data.mNewDevice != null) {
                    btMediaMetricRecord(data.mNewDevice, MediaMetrics.Value.CONNECTED, data);
                    sendLMsgNoDelay(MSG_L_BT_ACTIVE_DEVICE_CHANGE_EXT, SENDMSG_QUEUE,
                            createBtDeviceInfo(data, data.mNewDevice,
                                    BluetoothProfile.STATE_CONNECTED));
                }
            }
        }
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
            postUpdateCommunicationRouteClient(eventSource);
        }
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

     /*package*/ void postSetLeAudioVolumeIndex(int index, int maxIndex, int streamType) {
        BleVolumeInfo info = new BleVolumeInfo(index, maxIndex, streamType);
        sendLMsgNoDelay(MSG_II_SET_LE_AUDIO_OUT_VOLUME, SENDMSG_REPLACE, info);
    }

    /*package*/ void postSetModeOwner(int mode, int pid, int uid) {
        sendLMsgNoDelay(MSG_I_SET_MODE_OWNER, SENDMSG_REPLACE,
                new AudioModeInfo(mode, pid, uid));
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

                postSetCommunicationRouteForClient(new CommunicationClientInfo(
                        cb, pid, device, scoAudioMode, eventSource));
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
                postSetCommunicationRouteForClient(new CommunicationClientInfo(
                        cb, pid, null, BtHelper.SCO_MODE_UNDEFINED, eventSource));
            }
        }
    }

    /*package*/ int setPreferredDevicesForStrategySync(int strategy,
            @NonNull List<AudioDeviceAttributes> devices) {
        return mDeviceInventory.setPreferredDevicesForStrategySync(strategy, devices);
    }

    /*package*/ int removePreferredDevicesForStrategySync(int strategy) {
        return mDeviceInventory.removePreferredDevicesForStrategySync(strategy);
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
        int portId = (mActiveCommunicationDevice == null) ? 0
                : mActiveCommunicationDevice.getId();
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

    /*package*/ int getMaxVssVolumeForStream(int streamType) {
        return mAudioService.getMaxVssVolumeForStream(streamType);
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
    /*package*/ void postBluetoothActiveDevice(BtDeviceInfo info, int delay) {
        sendLMsg(MSG_L_SET_BT_ACTIVE_DEVICE, SENDMSG_QUEUE, info, delay);
    }

    /*package*/ void postSetWiredDeviceConnectionState(
            AudioDeviceInventory.WiredDeviceConnectionState connectionState, int delay) {
        sendLMsg(MSG_L_SET_WIRED_DEVICE_CONNECTION_STATE, SENDMSG_QUEUE, connectionState, delay);
    }

    /*package*/ void postBtProfileDisconnected(int profile) {
        sendIMsgNoDelay(MSG_I_BT_SERVICE_DISCONNECTED_PROFILE, SENDMSG_QUEUE, profile);
    }

    /*package*/ void postBtProfileConnected(int profile, BluetoothProfile proxy) {
        sendILMsgNoDelay(MSG_IL_BT_SERVICE_CONNECTED_PROFILE, SENDMSG_QUEUE, profile, proxy);
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

    /*package*/ void postUpdateCommunicationRouteClient(String eventSource) {
        sendLMsgNoDelay(MSG_L_UPDATE_COMMUNICATION_ROUTE_CLIENT, SENDMSG_QUEUE, eventSource);
    }

    /*package*/ void postSetCommunicationRouteForClient(CommunicationClientInfo info) {
        sendLMsgNoDelay(MSG_L_SET_COMMUNICATION_ROUTE_FOR_CLIENT, SENDMSG_QUEUE, info);
    }

    /*package*/ void postScoAudioStateChanged(int state) {
        sendIMsgNoDelay(MSG_I_SCO_AUDIO_STATE_CHANGED, SENDMSG_QUEUE, state);
    }

    /*package*/ static final class CommunicationClientInfo {
        final @NonNull IBinder mCb;
        final int mPid;
        final @NonNull AudioDeviceAttributes mDevice;
        final int mScoAudioMode;
        final @NonNull String mEventSource;

        CommunicationClientInfo(@NonNull IBinder cb, int pid, @NonNull AudioDeviceAttributes device,
                int scoAudioMode, @NonNull String eventSource) {
            mCb = cb;
            mPid = pid;
            mDevice = device;
            mScoAudioMode = scoAudioMode;
            mEventSource = eventSource;
        }

        // redefine equality op so we can match messages intended for this client
        @Override
        public boolean equals(Object o) {
            if (o == null) {
                return false;
            }
            if (this == o) {
                return true;
            }
            if (!(o instanceof CommunicationClientInfo)) {
                return false;
            }

            return mCb.equals(((CommunicationClientInfo) o).mCb)
                    && mPid == ((CommunicationClientInfo) o).mPid;
        }

        @Override
        public String toString() {
            return "CommunicationClientInfo mCb=" + mCb.toString()
                    +"mPid=" + mPid
                    +"mDevice=" + mDevice.toString()
                    +"mScoAudioMode=" + mScoAudioMode
                    +"mEventSource=" + mEventSource;
        }
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

    /*package*/ boolean handleDeviceConnection(AudioDeviceAttributes attributes, boolean connect) {
        synchronized (mDeviceStateLock) {
            return mDeviceInventory.handleDeviceConnection(attributes, connect, false /*for test*/);
        }
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

    // must be called synchronized on mConnectedDevices
    /*package*/ boolean hasScheduledA2dpConnection(BluetoothDevice btDevice) {
        final BtDeviceInfo devInfoToCheck = new BtDeviceInfo(btDevice, BluetoothProfile.A2DP);
        return mBrokerHandler.hasEqualMessages(MSG_L_SET_BT_ACTIVE_DEVICE, devInfoToCheck);
    }

    /*package*/ void setA2dpTimeout(String address, int a2dpCodec, int delayMs) {
        sendILMsg(MSG_IL_BTA2DP_TIMEOUT, SENDMSG_QUEUE, a2dpCodec, address, delayMs);
    }

    /*package*/ void setAvrcpAbsoluteVolumeSupported(boolean supported) {
        synchronized (mDeviceStateLock) {
            mBtHelper.setAvrcpAbsoluteVolumeSupported(supported);
        }
    }

    /*package*/ void clearAvrcpAbsoluteVolumeSupported() {
        setAvrcpAbsoluteVolumeSupported(false);
        mAudioService.setAvrcpAbsoluteVolumeSupported(false);
    }

    /*package*/ boolean getBluetoothA2dpEnabled() {
        synchronized (mDeviceStateLock) {
            return mBluetoothA2dpEnabled;
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

        pw.println("\n" + prefix + "Computed Preferred communication device: "
                +  preferredCommunicationDevice());
        pw.println("\n" + prefix + "Applied Preferred communication device: "
                +  mPreferredCommunicationDevice);
        pw.println(prefix + "Active communication device: "
                +  ((mActiveCommunicationDevice == null) ? "None"
                        : new AudioDeviceAttributes(mActiveCommunicationDevice)));

        pw.println(prefix + "mCommunicationStrategyId: "
                +  mCommunicationStrategyId);

        pw.println(prefix + "mAccessibilityStrategyId: "
                +  mAccessibilityStrategyId);

        pw.println("\n" + prefix + "mAudioModeOwner: " + mAudioModeOwner);

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
                            initRoutingStrategyIds();
                            updateActiveCommunicationDevice();
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
                case MSG_L_SET_BT_ACTIVE_DEVICE:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            BtDeviceInfo btInfo = (BtDeviceInfo) msg.obj;
                            mDeviceInventory.onSetBtActiveDevice(btInfo,
                                    (btInfo.mProfile
                                            != BluetoothProfile.LE_AUDIO || btInfo.mIsLeOutput)
                                            ? mAudioService.getBluetoothContextualVolumeStream()
                                            : AudioSystem.STREAM_DEFAULT);
                            if (btInfo.mProfile == BluetoothProfile.LE_AUDIO
                                    || btInfo.mProfile == BluetoothProfile.HEARING_AID) {
                                onUpdateCommunicationRouteClient("setBluetoothActiveDevice");
                            }
                        }
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
                    final BluetoothDevice btDevice = (BluetoothDevice) msg.obj;
                    synchronized (mDeviceStateLock) {
                        final int a2dpCodec = mBtHelper.getA2dpCodec(btDevice);
                        mDeviceInventory.onBluetoothA2dpDeviceConfigChange(
                                new BtHelper.BluetoothA2dpDeviceInfo(btDevice, -1, a2dpCodec),
                                        BtHelper.EVENT_DEVICE_CONFIG_CHANGE);
                    }
                    break;
                case MSG_BROADCAST_AUDIO_BECOMING_NOISY:
                    onSendBecomingNoisyIntent();
                    break;
                case MSG_II_SET_HEARING_AID_VOLUME:
                    synchronized (mDeviceStateLock) {
                        mBtHelper.setHearingAidVolume(msg.arg1, msg.arg2,
                                mDeviceInventory.isHearingAidConnected());
                    }
                    break;
                case MSG_II_SET_LE_AUDIO_OUT_VOLUME: {
                    final BleVolumeInfo info = (BleVolumeInfo) msg.obj;
                    synchronized (mDeviceStateLock) {
                        mBtHelper.setLeAudioVolume(info.mIndex, info.mMaxIndex, info.mStreamType);
                    }
                } break;
                case MSG_I_SET_AVRCP_ABSOLUTE_VOLUME:
                    synchronized (mDeviceStateLock) {
                        mBtHelper.setAvrcpAbsoluteVolumeIndex(msg.arg1);
                    }
                    break;
                case MSG_I_SET_MODE_OWNER:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            mAudioModeOwner = (AudioModeInfo) msg.obj;
                            if (mAudioModeOwner.mMode != AudioSystem.MODE_RINGTONE) {
                                onUpdateCommunicationRouteClient("setNewModeOwner");
                            }
                        }
                    }
                    break;

                case MSG_L_SET_COMMUNICATION_ROUTE_FOR_CLIENT:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            CommunicationClientInfo info = (CommunicationClientInfo) msg.obj;
                            setCommunicationRouteForClient(info.mCb, info.mPid, info.mDevice,
                                    info.mScoAudioMode, info.mEventSource);
                        }
                    }
                    break;

                case MSG_L_UPDATE_COMMUNICATION_ROUTE_CLIENT:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            onUpdateCommunicationRouteClient((String) msg.obj);
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

                case MSG_L_COMMUNICATION_ROUTE_CLIENT_DIED:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            onCommunicationRouteClientDied((CommunicationRouteClient) msg.obj);
                        }
                    }
                    break;

                case MSG_I_SCO_AUDIO_STATE_CHANGED:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            mBtHelper.onScoAudioStateChanged(msg.arg1);
                        }
                    }
                    break;

                case MSG_TOGGLE_HDMI:
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.onToggleHdmi();
                    }
                    break;
                case MSG_I_BT_SERVICE_DISCONNECTED_PROFILE:
                    if (msg.arg1 != BluetoothProfile.HEADSET) {
                        synchronized (mDeviceStateLock) {
                            mDeviceInventory.onBtProfileDisconnected(msg.arg1);
                        }
                    } else {
                        synchronized (mSetModeLock) {
                            synchronized (mDeviceStateLock) {
                                mBtHelper.disconnectHeadset();
                            }
                        }
                    }
                    break;
                case MSG_IL_BT_SERVICE_CONNECTED_PROFILE:
                    if (msg.arg1 != BluetoothProfile.HEADSET) {
                        synchronized (mDeviceStateLock) {
                            mBtHelper.onBtProfileConnected(msg.arg1, (BluetoothProfile) msg.obj);
                        }
                    } else {
                        synchronized (mSetModeLock) {
                            synchronized (mDeviceStateLock) {
                                mBtHelper.onHeadsetProfileConnected((BluetoothHeadset) msg.obj);
                            }
                        }
                    }
                    break;
                case MSG_L_BT_ACTIVE_DEVICE_CHANGE_EXT: {
                    final BtDeviceInfo info = (BtDeviceInfo) msg.obj;
                    if (info.mDevice == null) break;
                    AudioService.sDeviceLogger.log((new AudioEventLogger.StringEvent(
                            "msg: onBluetoothActiveDeviceChange "
                                    + " state=" + info.mState
                                    // only querying address as this is the only readily available
                                    // field on the device
                                    + " addr=" + info.mDevice.getAddress()
                                    + " prof=" + info.mProfile
                                    + " supprNoisy=" + info.mSupprNoisy
                                    + " src=" + info.mEventSource
                                    )).printLog(TAG));
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.setBluetoothActiveDevice(info);
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
    private static final int MSG_L_SET_BT_ACTIVE_DEVICE = 7;
    private static final int MSG_BT_HEADSET_CNCT_FAILED = 9;
    private static final int MSG_IL_BTA2DP_TIMEOUT = 10;

    // process change of A2DP device configuration, obj is BluetoothDevice
    private static final int MSG_L_A2DP_DEVICE_CONFIG_CHANGE = 11;

    private static final int MSG_BROADCAST_AUDIO_BECOMING_NOISY = 12;
    private static final int MSG_REPORT_NEW_ROUTES = 13;
    private static final int MSG_II_SET_HEARING_AID_VOLUME = 14;
    private static final int MSG_I_SET_AVRCP_ABSOLUTE_VOLUME = 15;
    private static final int MSG_I_SET_MODE_OWNER = 16;

    private static final int MSG_I_BT_SERVICE_DISCONNECTED_PROFILE = 22;
    private static final int MSG_IL_BT_SERVICE_CONNECTED_PROFILE = 23;

    // process external command to (dis)connect an A2DP device, obj is BtDeviceConnectionInfo
    private static final int MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT = 29;

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
    private static final int MSG_L_SET_COMMUNICATION_ROUTE_FOR_CLIENT = 42;
    private static final int MSG_L_UPDATE_COMMUNICATION_ROUTE_CLIENT = 43;
    private static final int MSG_I_SCO_AUDIO_STATE_CHANGED = 44;

    private static final int MSG_L_BT_ACTIVE_DEVICE_CHANGE_EXT = 45;
    //
    // process set volume for Le Audio, obj is BleVolumeInfo
    private static final int MSG_II_SET_LE_AUDIO_OUT_VOLUME = 46;

    private static boolean isMessageHandledUnderWakelock(int msgId) {
        switch(msgId) {
            case MSG_L_SET_WIRED_DEVICE_CONNECTION_STATE:
            case MSG_L_SET_BT_ACTIVE_DEVICE:
            case MSG_IL_BTA2DP_TIMEOUT:
            case MSG_L_A2DP_DEVICE_CONFIG_CHANGE:
            case MSG_TOGGLE_HDMI:
            case MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT:
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
                case MSG_L_SET_BT_ACTIVE_DEVICE:
                case MSG_L_SET_WIRED_DEVICE_CONNECTION_STATE:
                case MSG_IL_BTA2DP_TIMEOUT:
                case MSG_L_A2DP_DEVICE_CONFIG_CHANGE:
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
        MESSAGES_MUTE_MUSIC.add(MSG_L_SET_BT_ACTIVE_DEVICE);
        MESSAGES_MUTE_MUSIC.add(MSG_L_A2DP_DEVICE_CONFIG_CHANGE);
        MESSAGES_MUTE_MUSIC.add(MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT);
        MESSAGES_MUTE_MUSIC.add(MSG_IIL_SET_FORCE_BT_A2DP_USE);
    }

    private AtomicBoolean mMusicMuted = new AtomicBoolean(false);

    private static <T> boolean hasIntersection(Set<T> a, Set<T> b) {
        for (T e : a) {
            if (b.contains(e)) return true;
        }
        return false;
    }

    boolean messageMutesMusic(int message) {
        if (message == 0) {
            return false;
        }
        // Do not mute on bluetooth event if music is playing on a wired headset.
        if ((message == MSG_L_SET_BT_ACTIVE_DEVICE
                || message == MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT
                || message == MSG_L_A2DP_DEVICE_CONFIG_CHANGE)
                && AudioSystem.isStreamActive(AudioSystem.STREAM_MUSIC, 0)
                && hasIntersection(mDeviceInventory.DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG_SET,
                        mAudioService.getDeviceSetForStream(AudioSystem.STREAM_MUSIC))) {
            return false;
        }
        return true;
    }

    /** Mutes or unmutes music according to pending A2DP messages */
    private void checkMessagesMuteMusic(int message) {
        boolean mute = messageMutesMusic(message);
        if (!mute) {
            for (int msg : MESSAGES_MUTE_MUSIC) {
                if (mBrokerHandler.hasMessages(msg)) {
                    if (messageMutesMusic(msg)) {
                        mute = true;
                        break;
                    }
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
        Log.w(TAG, "Communication client died");
        setCommunicationRouteForClient(client.getBinder(), client.getPid(), null,
                BtHelper.SCO_MODE_UNDEFINED, "onCommunicationRouteClientDied");
    }

    /**
     * Determines which preferred device for phone strategy should be sent to audio policy manager
     * as a function of current SCO audio activation state and active communication route requests.
     * SCO audio state has the highest priority as it can result from external activation by
     * telephony service.
     * @return selected forced usage for communication.
     */
    @GuardedBy("mDeviceStateLock")
    @Nullable private AudioDeviceAttributes preferredCommunicationDevice() {
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
        if (device == null || device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
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
        AudioDeviceAttributes preferredCommunicationDevice = preferredCommunicationDevice();
        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "onUpdateCommunicationRoute, preferredCommunicationDevice: "
                    + preferredCommunicationDevice + " eventSource: " + eventSource);
        }
        AudioService.sDeviceLogger.log((new AudioEventLogger.StringEvent(
                "onUpdateCommunicationRoute, preferredCommunicationDevice: "
                + preferredCommunicationDevice + " eventSource: " + eventSource)));

        if (preferredCommunicationDevice == null
                || preferredCommunicationDevice.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            AudioSystem.setParameters("BT_SCO=off");
        } else {
            AudioSystem.setParameters("BT_SCO=on");
        }
        if (preferredCommunicationDevice == null) {
            AudioDeviceAttributes defaultDevice = getDefaultCommunicationDevice();
            if (defaultDevice != null) {
                setPreferredDevicesForStrategySync(
                        mCommunicationStrategyId, Arrays.asList(defaultDevice));
                setPreferredDevicesForStrategySync(
                        mAccessibilityStrategyId, Arrays.asList(defaultDevice));
            } else {
                removePreferredDevicesForStrategySync(mCommunicationStrategyId);
                removePreferredDevicesForStrategySync(mAccessibilityStrategyId);
            }
        } else {
            setPreferredDevicesForStrategySync(
                    mCommunicationStrategyId, Arrays.asList(preferredCommunicationDevice));
            setPreferredDevicesForStrategySync(
                    mAccessibilityStrategyId, Arrays.asList(preferredCommunicationDevice));
        }
        onUpdatePhoneStrategyDevice(preferredCommunicationDevice);
    }

    /**
     * Select new communication device from communication route client at the top of the stack
     * and restore communication route including restarting SCO audio if needed.
     */
    // @GuardedBy("mSetModeLock")
    @GuardedBy("mDeviceStateLock")
    private void onUpdateCommunicationRouteClient(String eventSource) {
        onUpdateCommunicationRoute(eventSource);
        CommunicationRouteClient crc = topCommunicationRouteClient();
        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "onUpdateCommunicationRouteClient, crc: "
                    + crc + " eventSource: " + eventSource);
        }
        if (crc != null) {
            setCommunicationRouteForClient(crc.getBinder(), crc.getPid(), crc.getDevice(),
                    BtHelper.SCO_MODE_UNDEFINED, eventSource);
        }
    }

    // @GuardedBy("mSetModeLock")
    @GuardedBy("mDeviceStateLock")
    private void onUpdatePhoneStrategyDevice(AudioDeviceAttributes device) {
        boolean wasSpeakerphoneActive = isSpeakerphoneActive();
        mPreferredCommunicationDevice = device;
        updateActiveCommunicationDevice();
        if (wasSpeakerphoneActive != isSpeakerphoneActive()) {
            try {
                mContext.sendBroadcastAsUser(
                        new Intent(AudioManager.ACTION_SPEAKERPHONE_STATE_CHANGED)
                                .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
                                          UserHandle.ALL);
            } catch (Exception e) {
                Log.w(TAG, "failed to broadcast ACTION_SPEAKERPHONE_STATE_CHANGED: " + e);
            }
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

    @GuardedBy("mDeviceStateLock")
    private boolean communnicationDeviceCompatOn() {
        return mAudioModeOwner.mMode == AudioSystem.MODE_IN_COMMUNICATION
                && !(CompatChanges.isChangeEnabled(
                        USE_SET_COMMUNICATION_DEVICE, mAudioModeOwner.mUid)
                     || mAudioModeOwner.mUid == android.os.Process.SYSTEM_UID);
    }

    @GuardedBy("mDeviceStateLock")
    AudioDeviceAttributes getDefaultCommunicationDevice() {
        // For system server (Telecom) and APKs targeting S and above, we let the audio
        // policy routing rules select the default communication device.
        // For older APKs, we force Hearing Aid or LE Audio headset when connected as
        // those APKs cannot select a LE Audio or Hearing Aid device explicitly.
        AudioDeviceAttributes device = null;
        if (communnicationDeviceCompatOn()) {
            // If both LE and Hearing Aid are active (thie should not happen),
            // priority to Hearing Aid.
            device = mDeviceInventory.getDeviceOfType(AudioSystem.DEVICE_OUT_HEARING_AID);
            if (device == null) {
                device = mDeviceInventory.getDeviceOfType(AudioSystem.DEVICE_OUT_BLE_HEADSET);
            }
        }
        return device;
    }

    @Nullable UUID getDeviceSensorUuid(AudioDeviceAttributes device) {
        synchronized (mDeviceStateLock) {
            return mDeviceInventory.getDeviceSensorUuid(device);
        }
    }
}
