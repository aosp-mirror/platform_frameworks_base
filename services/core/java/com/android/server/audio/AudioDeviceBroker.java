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
import android.media.AudioManager.AudioDeviceCategory;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;
import android.media.AudioRoutesInfo;
import android.media.AudioSystem;
import android.media.BluetoothProfileConnectionInfo;
import android.media.IAudioRoutesObserver;
import android.media.ICapturePresetDevicesRoleDispatcher;
import android.media.ICommunicationDeviceDispatcher;
import android.media.IStrategyNonDefaultDevicesDispatcher;
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
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.PrintWriterPrinter;

import com.android.internal.annotations.GuardedBy;
import com.android.server.utils.EventLogger;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * @hide
 * (non final for mocking/spying)
 */
public class AudioDeviceBroker {

    private static final String TAG = "AS.AudioDeviceBroker";

    private static final long BROKER_WAKELOCK_TIMEOUT_MS = 5000; //5s

    /*package*/ static final  int BTA2DP_DOCK_TIMEOUT_MS = 8000;
    // Timeout for connection to bluetooth headset service
    /*package*/ static final int BT_HEADSET_CNCT_TIMEOUT_MS = 3000;

    // Delay before checking it music should be unmuted after processing an A2DP message
    private static final int BTA2DP_MUTE_CHECK_DELAY_MS = 100;

    private final @NonNull AudioService mAudioService;
    private final @NonNull Context mContext;
    private final @NonNull AudioSystemAdapter mAudioSystem;

    /** ID for Communication strategy retrieved form audio policy manager */
    /*package*/  int mCommunicationStrategyId = -1;

    /** ID for Accessibility strategy retrieved form audio policy manager */
    private int mAccessibilityStrategyId = -1;


    /** Active communication device reported by audio policy manager */
    /*package*/ AudioDeviceInfo mActiveCommunicationDevice;
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
    /*package*/ AudioDeviceBroker(@NonNull Context context, @NonNull AudioService service,
            @NonNull AudioSystemAdapter audioSystem) {
        mContext = context;
        mAudioService = service;
        mBtHelper = new BtHelper(this, context);
        mDeviceInventory = new AudioDeviceInventory(this);
        mSystemServer = SystemServerAdapter.getDefaultAdapter(mContext);
        mAudioSystem = audioSystem;

        init();
    }

    /** for test purposes only, inject AudioDeviceInventory and adapter for operations running
     *  in system_server */
    AudioDeviceBroker(@NonNull Context context, @NonNull AudioService service,
                      @NonNull AudioDeviceInventory mockDeviceInventory,
                      @NonNull SystemServerAdapter mockSystemServer,
                      @NonNull AudioSystemAdapter audioSystem) {
        mContext = context;
        mAudioService = service;
        mBtHelper = new BtHelper(this, context);
        mDeviceInventory = mockDeviceInventory;
        mSystemServer = mockSystemServer;
        mAudioSystem = audioSystem;

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

        initAudioHalBluetoothState();
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

    /**
     * Handle BluetoothHeadset intents where the action is one of
     *   {@link BluetoothHeadset#ACTION_ACTIVE_DEVICE_CHANGED} or
     *   {@link BluetoothHeadset#ACTION_AUDIO_STATE_CHANGED}.
     * @param intent
     */
    private void onReceiveBtEvent(@NonNull Intent intent) {
        mBtHelper.onReceiveBtEvent(intent);
    }

    @GuardedBy("mDeviceStateLock")
    /*package*/ void onSetBtScoActiveDevice(BluetoothDevice btDevice) {
        mBtHelper.onSetBtScoActiveDevice(btDevice);
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
    /*package*/ void setSpeakerphoneOn(
            IBinder cb, int uid, boolean on, boolean isPrivileged, String eventSource) {

        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "setSpeakerphoneOn, on: " + on + " uid: " + uid);
        }
        postSetCommunicationDeviceForClient(new CommunicationDeviceInfo(
                cb, uid, new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_SPEAKER, ""),
                on, BtHelper.SCO_MODE_UNDEFINED, eventSource, isPrivileged));
    }

    /**
     * Select device for use for communication use cases.
     * @param cb Client binder for death detection
     * @param uid Client uid
     * @param device Device selected or null to unselect.
     * @param eventSource for logging purposes
     */

    private static final long SET_COMMUNICATION_DEVICE_TIMEOUT_MS = 3000;

    /** synchronization for setCommunicationDevice() and getCommunicationDevice */
    private Object mCommunicationDeviceLock = new Object();
    @GuardedBy("mCommunicationDeviceLock")
    private int mCommunicationDeviceUpdateCount = 0;

    /*package*/ boolean setCommunicationDevice(IBinder cb, int uid, AudioDeviceInfo device,
                                               boolean isPrivileged, String eventSource) {

        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "setCommunicationDevice, device: " + device + ", uid: " + uid);
        }

        synchronized (mDeviceStateLock) {
            if (device == null) {
                CommunicationRouteClient client = getCommunicationRouteClientForUid(uid);
                if (client == null) {
                    return false;
                }
            }
        }
        synchronized (mCommunicationDeviceLock) {
            mCommunicationDeviceUpdateCount++;
            AudioDeviceAttributes deviceAttr =
                    (device != null) ? new AudioDeviceAttributes(device) : null;
            CommunicationDeviceInfo deviceInfo = new CommunicationDeviceInfo(cb, uid, deviceAttr,
                    device != null, BtHelper.SCO_MODE_UNDEFINED, eventSource, isPrivileged);
            postSetCommunicationDeviceForClient(deviceInfo);
        }
        return true;
    }

    /**
     * Sets or resets the communication device for matching client. If no client matches and the
     * request is to reset for a given device (deviceInfo.mOn == false), the method is a noop.
     * @param deviceInfo information on the device and requester {@link #CommunicationDeviceInfo}
     * @return true if the communication device is set or reset
     */
    @GuardedBy("mDeviceStateLock")
    /*package*/ void onSetCommunicationDeviceForClient(CommunicationDeviceInfo deviceInfo) {
        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "onSetCommunicationDeviceForClient: " + deviceInfo);
        }
        if (!deviceInfo.mOn) {
            CommunicationRouteClient client = getCommunicationRouteClientForUid(deviceInfo.mUid);
            if (client == null || (deviceInfo.mDevice != null
                    && !deviceInfo.mDevice.equals(client.getDevice()))) {
                return;
            }
        }

        AudioDeviceAttributes device = deviceInfo.mOn ? deviceInfo.mDevice : null;
        setCommunicationRouteForClient(deviceInfo.mCb, deviceInfo.mUid, device,
                deviceInfo.mScoAudioMode, deviceInfo.mIsPrivileged, deviceInfo.mEventSource);
    }

    @GuardedBy("mDeviceStateLock")
    /*package*/ void setCommunicationRouteForClient(
                            IBinder cb, int uid, AudioDeviceAttributes device,
                            int scoAudioMode, boolean isPrivileged, String eventSource) {

        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "setCommunicationRouteForClient: device: " + device
                    + ", eventSource: " + eventSource);
        }
        AudioService.sDeviceLogger.enqueue((new EventLogger.StringEvent(
                                        "setCommunicationRouteForClient for uid: " + uid
                                        + " device: " + device + " isPrivileged: " + isPrivileged
                                        + " from API: " + eventSource)).printLog(TAG));

        final boolean wasBtScoRequested = isBluetoothScoRequested();
        CommunicationRouteClient client;

        // Save previous client route in case of failure to start BT SCO audio
        AudioDeviceAttributes prevClientDevice = null;
        boolean prevPrivileged = false;
        client = getCommunicationRouteClientForUid(uid);
        if (client != null) {
            prevClientDevice = client.getDevice();
            prevPrivileged = client.isPrivileged();
        }

        if (device != null) {
            client = addCommunicationRouteClient(cb, uid, device, isPrivileged);
            if (client == null) {
                Log.w(TAG, "setCommunicationRouteForClient: could not add client for uid: "
                        + uid + " and device: " + device);
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
                Log.w(TAG, "setCommunicationRouteForClient: failure to start BT SCO for uid: "
                        + uid);
                // clean up or restore previous client selection
                if (prevClientDevice != null) {
                    addCommunicationRouteClient(cb, uid, prevClientDevice, prevPrivileged);
                } else {
                    removeCommunicationRouteClient(cb, true);
                }
                postBroadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
            }
        } else if (!isBtScoRequested && wasBtScoRequested) {
            mBtHelper.stopBluetoothSco(eventSource);
        }

        // In BT classic for communication, the device changes from a2dp to sco device, but for
        // LE Audio it stays the same and we must trigger the proper stream volume alignment, if
        // LE Audio communication device is activated after the audio system has already switched to
        // MODE_IN_CALL mode.
        if (isBluetoothLeAudioRequested() && device != null) {
            final int streamType = mAudioService.getBluetoothContextualVolumeStream();
            final int leAudioVolIndex = getVssVolumeForDevice(streamType, device.getInternalType());
            final int leAudioMaxVolIndex = getMaxVssVolumeForStream(streamType);
            if (AudioService.DEBUG_COMM_RTE) {
                Log.v(TAG, "setCommunicationRouteForClient restoring LE Audio device volume lvl.");
            }
            postSetLeAudioVolumeIndex(leAudioVolIndex, leAudioMaxVolIndex, streamType);
        }

        updateCommunicationRoute(eventSource);
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
            if (crc.getUid() == mAudioModeOwner.mUid) {
                return crc;
            }
        }
        if (!mCommunicationRouteClients.isEmpty() && mAudioModeOwner.mPid == 0
                && mCommunicationRouteClients.get(0).isActive()) {
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
            Log.v(TAG, "requestedCommunicationDevice: "
                    + device + " mAudioModeOwner: " + mAudioModeOwner.toString());
        }
        return device;
    }

    private static final int[] VALID_COMMUNICATION_DEVICE_TYPES = {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_AUX_LINE
    };

    /*package */ static boolean isValidCommunicationDevice(@NonNull AudioDeviceInfo device) {
        Objects.requireNonNull(device, "device must not be null");
        return device.isSink() && isValidCommunicationDeviceType(device.getType());
    }

    private static boolean isValidCommunicationDeviceType(int deviceType) {
        for (int type : VALID_COMMUNICATION_DEVICE_TYPES) {
            if (deviceType == type) {
                return true;
            }
        }
        return false;
    }

    /*package */
    void postCheckCommunicationDeviceRemoval(@NonNull AudioDeviceAttributes device) {
        if (!isValidCommunicationDeviceType(
                AudioDeviceInfo.convertInternalDeviceToDeviceType(device.getInternalType()))) {
            return;
        }
        sendLMsgNoDelay(MSG_L_CHECK_COMMUNICATION_DEVICE_REMOVAL, SENDMSG_QUEUE, device);
    }

    @GuardedBy("mDeviceStateLock")
    void onCheckCommunicationDeviceRemoval(@NonNull AudioDeviceAttributes device) {
        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "onCheckCommunicationDeviceRemoval device: " + device.toString());
        }
        for (CommunicationRouteClient crc : mCommunicationRouteClients) {
            if (device.equals(crc.getDevice())) {
                if (AudioService.DEBUG_COMM_RTE) {
                    Log.v(TAG, "onCheckCommunicationDeviceRemoval removing client: "
                            + crc.toString());
                }
                // Cancelling the route for this client will remove it from the stack and update
                // the communication route.
                CommunicationDeviceInfo deviceInfo = new CommunicationDeviceInfo(
                        crc.getBinder(), crc.getUid(), device, false,
                        BtHelper.SCO_MODE_UNDEFINED, "onCheckCommunicationDeviceRemoval",
                        crc.isPrivileged());
                postSetCommunicationDeviceForClient(deviceInfo);
            }
        }
    }

    // check playback or record activity after 6 seconds for UIDs
    private static final int CHECK_CLIENT_STATE_DELAY_MS = 6000;

    /*package */
    void postCheckCommunicationRouteClientState(int uid, boolean wasActive, int delay) {
        CommunicationRouteClient client = getCommunicationRouteClientForUid(uid);
        if (client != null) {
            sendMsgForCheckClientState(MSG_CHECK_COMMUNICATION_ROUTE_CLIENT_STATE,
                                        SENDMSG_REPLACE,
                                        uid,
                                        wasActive ? 1 : 0,
                                        client,
                                        delay);
        }
    }

    @GuardedBy("mDeviceStateLock")
    void onCheckCommunicationRouteClientState(int uid, boolean wasActive) {
        CommunicationRouteClient client = getCommunicationRouteClientForUid(uid);
        if (client == null) {
            return;
        }
        updateCommunicationRouteClientState(client, wasActive);
    }

    @GuardedBy("mDeviceStateLock")
    /*package*/ void updateCommunicationRouteClientState(
                            CommunicationRouteClient client, boolean wasActive) {
        boolean wasBtScoRequested = isBluetoothScoRequested();
        client.setPlaybackActive(mAudioService.isPlaybackActiveForUid(client.getUid()));
        client.setRecordingActive(mAudioService.isRecordingActiveForUid(client.getUid()));
        if (wasActive != client.isActive()) {
            postUpdateCommunicationRouteClient(
                    wasBtScoRequested, "updateCommunicationRouteClientState");
        }
    }

    @GuardedBy("mDeviceStateLock")
    /*package*/ void setForceCommunicationClientStateAndDelayedCheck(
                            CommunicationRouteClient client,
                            boolean forcePlaybackActive,
                            boolean forceRecordingActive) {
        if (client == null) {
            return;
        }
        if (forcePlaybackActive) {
            client.setPlaybackActive(true);
        }
        if (forceRecordingActive) {
            client.setRecordingActive(true);
        }
        postCheckCommunicationRouteClientState(
                client.getUid(), client.isActive(), CHECK_CLIENT_STATE_DELAY_MS);
    }

    /* package */ static List<AudioDeviceInfo> getAvailableCommunicationDevices() {
        ArrayList<AudioDeviceInfo> commDevices = new ArrayList<>();
        AudioDeviceInfo[] allDevices =
                AudioManager.getDevicesStatic(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo device : allDevices) {
            if (isValidCommunicationDevice(device)) {
                commDevices.add(device);
            }
        }
        return commDevices;
    }

    private @Nullable AudioDeviceInfo getCommunicationDeviceOfType(int type) {
        return getAvailableCommunicationDevices().stream().filter(d -> d.getType() == type)
                .findFirst().orElse(null);
    }

    /**
     * Returns the device currently requested for communication use case.
     * @return AudioDeviceInfo the requested device for communication.
     */
    /* package */ AudioDeviceInfo getCommunicationDevice() {
        synchronized (mCommunicationDeviceLock) {
            final long start = System.currentTimeMillis();
            long elapsed = 0;
            while (mCommunicationDeviceUpdateCount > 0) {
                try {
                    mCommunicationDeviceLock.wait(
                            SET_COMMUNICATION_DEVICE_TIMEOUT_MS - elapsed);
                } catch (InterruptedException e) {
                    Log.w(TAG, "Interrupted while waiting for communication device update.");
                }
                elapsed = System.currentTimeMillis() - start;
                if (elapsed >= SET_COMMUNICATION_DEVICE_TIMEOUT_MS) {
                    Log.e(TAG, "Timeout waiting for communication device update.");
                    break;
                }
            }
        }
        synchronized (mDeviceStateLock) {
            return getCommunicationDeviceInt();
        }
    }

    @GuardedBy("mDeviceStateLock")
    private AudioDeviceInfo  getCommunicationDeviceInt() {
        updateActiveCommunicationDevice();
        AudioDeviceInfo device = mActiveCommunicationDevice;
        // make sure we return a valid communication device (i.e. a device that is allowed by
        // setCommunicationDevice()) for consistency.
        if (device != null) {
            // a digital dock is used instead of the speaker in speakerphone mode and should
            // be reflected as such
            if (device.getType() == AudioDeviceInfo.TYPE_DOCK) {
                device = getCommunicationDeviceOfType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
            }
        }
        // Try to default to earpiece when current communication device is not valid. This can
        // happen for instance if no call is active. If no earpiece device is available take the
        // first valid communication device
        if (device == null || !AudioDeviceBroker.isValidCommunicationDevice(device)) {
            device = getCommunicationDeviceOfType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
            if (device == null) {
                List<AudioDeviceInfo> commDevices = getAvailableCommunicationDevices();
                if (!commDevices.isEmpty()) {
                    device = commDevices.get(0);
                }
            }
        }
        return device;
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
            List<AudioDeviceAttributes> devices = mAudioSystem.getDevicesForAttributes(
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
     * Helper method on top of isDeviceRequestedForCommunication() indicating if
     * Bluetooth LE Audio communication device is currently requested or not.
     * @return true if Bluetooth LE Audio device is requested, false otherwise.
     */
    /*package*/ boolean isBluetoothLeAudioRequested() {
        return isDeviceRequestedForCommunication(AudioDeviceInfo.TYPE_BLE_HEADSET)
                || isDeviceRequestedForCommunication(AudioDeviceInfo.TYPE_BLE_SPEAKER);
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
        }

        // constructor used by AudioDeviceBroker to search similar message
        BtDeviceInfo(@NonNull BluetoothDevice device, int profile) {
            mDevice = device;
            mProfile = profile;
            mEventSource = "";
            mMusicDevice = AudioSystem.DEVICE_NONE;
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
            mAudioSystemDevice = audioSystemDevice;
            mState = state;
            mSupprNoisy = false;
            mVolume = -1;
            mIsLeOutput = false;
        }

        BtDeviceInfo(@NonNull BtDeviceInfo src, int state) {
            mDevice = src.mDevice;
            mState = state;
            mProfile = src.mProfile;
            mSupprNoisy = src.mSupprNoisy;
            mVolume = src.mVolume;
            mIsLeOutput = src.mIsLeOutput;
            mEventSource = src.mEventSource;
            mAudioSystemDevice = src.mAudioSystemDevice;
            mMusicDevice = src.mMusicDevice;
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

        @Override
        public String toString() {
            return "BtDeviceInfo: device=" + mDevice.toString()
                            + " state=" + mState
                            + " prof=" + mProfile
                            + " supprNoisy=" + mSupprNoisy
                            + " volume=" + mVolume
                            + " isLeOutput=" + mIsLeOutput
                            + " eventSource=" + mEventSource
                            + " audioSystemDevice=" + mAudioSystemDevice
                            + " musicDevice=" + mMusicDevice;
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
        if (data.mPreviousDevice != null
                && data.mPreviousDevice.equals(data.mNewDevice)) {
            final String name = TextUtils.emptyIfNull(data.mNewDevice.getName());
            new MediaMetrics.Item(MediaMetrics.Name.AUDIO_DEVICE + MediaMetrics.SEPARATOR
                    + "queueOnBluetoothActiveDeviceChanged_update")
                    .set(MediaMetrics.Property.NAME, name)
                    .set(MediaMetrics.Property.STATUS, data.mInfo.getProfile())
                    .record();
            synchronized (mDeviceStateLock) {
                postBluetoothDeviceConfigChange(createBtDeviceInfo(data, data.mNewDevice,
                        BluetoothProfile.STATE_CONNECTED));
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

    // Lock protecting state variable related to Bluetooth audio state
    private final Object mBluetoothAudioStateLock = new Object();

    // Current Bluetooth SCO audio active state indicated by BtHelper via setBluetoothScoOn().
    @GuardedBy("mBluetoothAudioStateLock")
    private boolean mBluetoothScoOn;
    // value of BT_SCO parameter currently applied to audio HAL.
    @GuardedBy("mBluetoothAudioStateLock")
    private boolean mBluetoothScoOnApplied;

    // A2DP suspend state requested by AudioManager.setA2dpSuspended() API.
    @GuardedBy("mBluetoothAudioStateLock")
    private boolean mBluetoothA2dpSuspendedExt;
    // A2DP suspend state requested by AudioDeviceInventory.
    @GuardedBy("mBluetoothAudioStateLock")
    private boolean mBluetoothA2dpSuspendedInt;
    // value of BT_A2dpSuspendedSCO parameter currently applied to audio HAL.

    @GuardedBy("mBluetoothAudioStateLock")
    private boolean mBluetoothA2dpSuspendedApplied;

    // LE Audio suspend state requested by AudioManager.setLeAudioSuspended() API.
    @GuardedBy("mBluetoothAudioStateLock")
    private boolean mBluetoothLeSuspendedExt;
    // LE Audio suspend state requested by AudioDeviceInventory.
    @GuardedBy("mBluetoothAudioStateLock")
    private boolean mBluetoothLeSuspendedInt;
    // value of LeAudioSuspended parameter currently applied to audio HAL.
    @GuardedBy("mBluetoothAudioStateLock")
    private boolean mBluetoothLeSuspendedApplied;

    private void initAudioHalBluetoothState() {
        synchronized (mBluetoothAudioStateLock) {
            mBluetoothScoOnApplied = false;
            mBluetoothA2dpSuspendedApplied = false;
            mBluetoothLeSuspendedApplied = false;
            reapplyAudioHalBluetoothState();
        }
    }

    @GuardedBy("mBluetoothAudioStateLock")
    private void updateAudioHalBluetoothState() {
        if (mBluetoothScoOn != mBluetoothScoOnApplied) {
            if (AudioService.DEBUG_COMM_RTE) {
                Log.v(TAG, "updateAudioHalBluetoothState() mBluetoothScoOn: "
                        + mBluetoothScoOn + ", mBluetoothScoOnApplied: " + mBluetoothScoOnApplied);
            }
            if (mBluetoothScoOn) {
                if (!mBluetoothA2dpSuspendedApplied) {
                    AudioSystem.setParameters("A2dpSuspended=true");
                    mBluetoothA2dpSuspendedApplied = true;
                }
                if (!mBluetoothLeSuspendedApplied) {
                    AudioSystem.setParameters("LeAudioSuspended=true");
                    mBluetoothLeSuspendedApplied = true;
                }
                AudioSystem.setParameters("BT_SCO=on");
            } else {
                AudioSystem.setParameters("BT_SCO=off");
            }
            mBluetoothScoOnApplied = mBluetoothScoOn;
        }
        if (!mBluetoothScoOnApplied) {
            if ((mBluetoothA2dpSuspendedExt || mBluetoothA2dpSuspendedInt)
                    != mBluetoothA2dpSuspendedApplied) {
                if (AudioService.DEBUG_COMM_RTE) {
                    Log.v(TAG, "updateAudioHalBluetoothState() mBluetoothA2dpSuspendedExt: "
                            + mBluetoothA2dpSuspendedExt
                            + ", mBluetoothA2dpSuspendedInt: " + mBluetoothA2dpSuspendedInt
                            + ", mBluetoothA2dpSuspendedApplied: "
                            + mBluetoothA2dpSuspendedApplied);
                }
                mBluetoothA2dpSuspendedApplied =
                        mBluetoothA2dpSuspendedExt || mBluetoothA2dpSuspendedInt;
                if (mBluetoothA2dpSuspendedApplied) {
                    AudioSystem.setParameters("A2dpSuspended=true");
                } else {
                    AudioSystem.setParameters("A2dpSuspended=false");
                }
            }
            if ((mBluetoothLeSuspendedExt || mBluetoothLeSuspendedInt)
                    != mBluetoothLeSuspendedApplied) {
                if (AudioService.DEBUG_COMM_RTE) {
                    Log.v(TAG, "updateAudioHalBluetoothState() mBluetoothLeSuspendedExt: "
                            + mBluetoothLeSuspendedExt
                            + ", mBluetoothLeSuspendedInt: " + mBluetoothLeSuspendedInt
                            + ", mBluetoothLeSuspendedApplied: " + mBluetoothLeSuspendedApplied);
                }
                mBluetoothLeSuspendedApplied =
                        mBluetoothLeSuspendedExt || mBluetoothLeSuspendedInt;
                if (mBluetoothLeSuspendedApplied) {
                    AudioSystem.setParameters("LeAudioSuspended=true");
                } else {
                    AudioSystem.setParameters("LeAudioSuspended=false");
                }
            }
        }
    }

    @GuardedBy("mBluetoothAudioStateLock")
    private void reapplyAudioHalBluetoothState() {
        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "reapplyAudioHalBluetoothState() mBluetoothScoOnApplied: "
                    + mBluetoothScoOnApplied + ", mBluetoothA2dpSuspendedApplied: "
                    + mBluetoothA2dpSuspendedApplied + ", mBluetoothLeSuspendedApplied: "
                    + mBluetoothLeSuspendedApplied);
        }
        // Note: the order of parameters is important.
        if (mBluetoothScoOnApplied) {
            AudioSystem.setParameters("A2dpSuspended=true");
            AudioSystem.setParameters("LeAudioSuspended=true");
            AudioSystem.setParameters("BT_SCO=on");
        } else {
            AudioSystem.setParameters("BT_SCO=off");
            if (mBluetoothA2dpSuspendedApplied) {
                AudioSystem.setParameters("A2dpSuspended=true");
            } else {
                AudioSystem.setParameters("A2dpSuspended=false");
            }
            if (mBluetoothLeSuspendedApplied) {
                AudioSystem.setParameters("LeAudioSuspended=true");
            } else {
                AudioSystem.setParameters("LeAudioSuspended=false");
            }
        }
    }

    /*package*/ void setBluetoothScoOn(boolean on, String eventSource) {
        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "setBluetoothScoOn: " + on + " " + eventSource);
        }
        synchronized (mBluetoothAudioStateLock) {
            mBluetoothScoOn = on;
            updateAudioHalBluetoothState();
            postUpdateCommunicationRouteClient(isBluetoothScoRequested(), eventSource);
        }
    }

    /*package*/ void setA2dpSuspended(boolean enable, boolean internal, String eventSource) {
        synchronized (mBluetoothAudioStateLock) {
            if (AudioService.DEBUG_COMM_RTE) {
                Log.v(TAG, "setA2dpSuspended source: " + eventSource + ", enable: "
                        + enable + ", internal: " + internal
                        + ", mBluetoothA2dpSuspendedInt: " + mBluetoothA2dpSuspendedInt
                        + ", mBluetoothA2dpSuspendedExt: " + mBluetoothA2dpSuspendedExt);
            }
            if (internal) {
                mBluetoothA2dpSuspendedInt = enable;
            } else {
                mBluetoothA2dpSuspendedExt = enable;
            }
            updateAudioHalBluetoothState();
        }
    }

    /*package*/ void clearA2dpSuspended(boolean internalOnly) {
        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "clearA2dpSuspended, internalOnly: " + internalOnly);
        }
        synchronized (mBluetoothAudioStateLock) {
            mBluetoothA2dpSuspendedInt = false;
            if (!internalOnly) {
                mBluetoothA2dpSuspendedExt = false;
            }
            updateAudioHalBluetoothState();
        }
    }

    /*package*/ void setLeAudioSuspended(boolean enable, boolean internal, String eventSource) {
        synchronized (mBluetoothAudioStateLock) {
            if (AudioService.DEBUG_COMM_RTE) {
                Log.v(TAG, "setLeAudioSuspended source: " + eventSource + ", enable: "
                        + enable + ", internal: " + internal
                        + ", mBluetoothLeSuspendedInt: " + mBluetoothA2dpSuspendedInt
                        + ", mBluetoothLeSuspendedExt: " + mBluetoothA2dpSuspendedExt);
            }
            if (internal) {
                mBluetoothLeSuspendedInt = enable;
            } else {
                mBluetoothLeSuspendedExt = enable;
            }
            updateAudioHalBluetoothState();
        }
    }

    /*package*/ void clearLeAudioSuspended(boolean internalOnly) {
        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "clearLeAudioSuspended, internalOnly: " + internalOnly);
        }
        synchronized (mBluetoothAudioStateLock) {
            mBluetoothLeSuspendedInt = false;
            if (!internalOnly) {
                mBluetoothLeSuspendedExt = false;
            }
            updateAudioHalBluetoothState();
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

    /*package*/ void postBluetoothDeviceConfigChange(@NonNull BtDeviceInfo info) {
        sendLMsgNoDelay(MSG_L_BLUETOOTH_DEVICE_CONFIG_CHANGE, SENDMSG_QUEUE, info);
    }

    /*package*/ void startBluetoothScoForClient(IBinder cb, int uid, int scoAudioMode,
                                                boolean isPrivileged, @NonNull String eventSource) {

        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "startBluetoothScoForClient, uid: " + uid);
        }
        postSetCommunicationDeviceForClient(new CommunicationDeviceInfo(
                cb, uid, new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_BLUETOOTH_SCO, ""),
                true, scoAudioMode, eventSource, isPrivileged));
    }

    /*package*/ void stopBluetoothScoForClient(
                        IBinder cb, int uid, boolean isPrivileged, @NonNull String eventSource) {

        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "stopBluetoothScoForClient, uid: " + uid);
        }
        postSetCommunicationDeviceForClient(new CommunicationDeviceInfo(
                cb, uid, new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_BLUETOOTH_SCO, ""),
                false, BtHelper.SCO_MODE_UNDEFINED, eventSource, isPrivileged));
    }

    /*package*/ int setPreferredDevicesForStrategySync(int strategy,
            @NonNull List<AudioDeviceAttributes> devices) {
        return mDeviceInventory.setPreferredDevicesForStrategyAndSave(strategy, devices);
    }

    /*package*/ int removePreferredDevicesForStrategySync(int strategy) {
        return mDeviceInventory.removePreferredDevicesForStrategyAndSave(strategy);
    }

    /*package*/ int setDeviceAsNonDefaultForStrategySync(int strategy,
            @NonNull AudioDeviceAttributes device) {
        return mDeviceInventory.setDeviceAsNonDefaultForStrategyAndSave(strategy, device);
    }

    /*package*/ int removeDeviceAsNonDefaultForStrategySync(int strategy,
            @NonNull AudioDeviceAttributes device) {
        return mDeviceInventory.removeDeviceAsNonDefaultForStrategyAndSave(strategy, device);
    }

    /*package*/ void registerStrategyPreferredDevicesDispatcher(
            @NonNull IStrategyPreferredDevicesDispatcher dispatcher, boolean isPrivileged) {
        mDeviceInventory.registerStrategyPreferredDevicesDispatcher(dispatcher, isPrivileged);
    }

    /*package*/ void unregisterStrategyPreferredDevicesDispatcher(
            @NonNull IStrategyPreferredDevicesDispatcher dispatcher) {
        mDeviceInventory.unregisterStrategyPreferredDevicesDispatcher(dispatcher);
    }

    /*package*/ void registerStrategyNonDefaultDevicesDispatcher(
            @NonNull IStrategyNonDefaultDevicesDispatcher dispatcher, boolean isPrivileged) {
        mDeviceInventory.registerStrategyNonDefaultDevicesDispatcher(dispatcher, isPrivileged);
    }

    /*package*/ void unregisterStrategyNonDefaultDevicesDispatcher(
            @NonNull IStrategyNonDefaultDevicesDispatcher dispatcher) {
        mDeviceInventory.unregisterStrategyNonDefaultDevicesDispatcher(dispatcher);
    }

    /*package*/ int setPreferredDevicesForCapturePresetSync(int capturePreset,
            @NonNull List<AudioDeviceAttributes> devices) {
        return mDeviceInventory.setPreferredDevicesForCapturePresetAndSave(capturePreset, devices);
    }

    /*package*/ int clearPreferredDevicesForCapturePresetSync(int capturePreset) {
        return mDeviceInventory.clearPreferredDevicesForCapturePresetAndSave(capturePreset);
    }

    /*package*/ void registerCapturePresetDevicesRoleDispatcher(
            @NonNull ICapturePresetDevicesRoleDispatcher dispatcher, boolean isPrivileged) {
        mDeviceInventory.registerCapturePresetDevicesRoleDispatcher(dispatcher, isPrivileged);
    }

    /*package*/ void unregisterCapturePresetDevicesRoleDispatcher(
            @NonNull ICapturePresetDevicesRoleDispatcher dispatcher) {
        mDeviceInventory.unregisterCapturePresetDevicesRoleDispatcher(dispatcher);
    }

    /* package */ List<AudioDeviceAttributes> anonymizeAudioDeviceAttributesListUnchecked(
            List<AudioDeviceAttributes> devices) {
        return mAudioService.anonymizeAudioDeviceAttributesListUnchecked(devices);
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
        AudioDeviceInfo device = getCommunicationDeviceInt();
        int portId = device != null ? device.getId() : 0;
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

    /*package*/ void postInitSpatializerHeadTrackingSensors() {
        mAudioService.postInitSpatializerHeadTrackingSensors();
    }

    //---------------------------------------------------------------------
    // Message handling on behalf of helper classes.
    // Each of these methods posts a message to mBrokerHandler message queue.
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

    /*package*/ void postSaveSetDeviceAsNonDefaultForStrategy(
            int strategy, AudioDeviceAttributes device) {
        sendILMsgNoDelay(MSG_IL_SAVE_NDEF_DEVICE_FOR_STRATEGY, SENDMSG_QUEUE, strategy, device);
    }

    /*package*/ void postSaveRemoveDeviceAsNonDefaultForStrategy(
            int strategy, AudioDeviceAttributes device) {
        sendILMsgNoDelay(
                MSG_IL_SAVE_REMOVE_NDEF_DEVICE_FOR_STRATEGY, SENDMSG_QUEUE, strategy, device);
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

    /*package*/ void postUpdateCommunicationRouteClient(
            boolean wasBtScoRequested, String eventSource) {
        sendILMsgNoDelay(MSG_IL_UPDATE_COMMUNICATION_ROUTE_CLIENT, SENDMSG_QUEUE,
                wasBtScoRequested ? 1 : 0, eventSource);
    }

    /*package*/ void postSetCommunicationDeviceForClient(CommunicationDeviceInfo info) {
        sendLMsgNoDelay(MSG_L_SET_COMMUNICATION_DEVICE_FOR_CLIENT, SENDMSG_QUEUE, info);
    }

    /*package*/ void postNotifyPreferredAudioProfileApplied(BluetoothDevice btDevice) {
        sendLMsgNoDelay(MSG_L_NOTIFY_PREFERRED_AUDIOPROFILE_APPLIED, SENDMSG_QUEUE, btDevice);
    }

    /*package*/ void postReceiveBtEvent(Intent intent) {
        sendLMsgNoDelay(MSG_L_RECEIVED_BT_EVENT, SENDMSG_QUEUE, intent);
    }

    /*package*/ void postUpdateLeAudioGroupAddresses(int groupId) {
        sendIMsgNoDelay(
                MSG_I_UPDATE_LE_AUDIO_GROUP_ADDRESSES, SENDMSG_QUEUE, groupId);
    }

    /*package*/ void postSynchronizeAdiDevicesInInventory(AdiDeviceState deviceState) {
        sendLMsgNoDelay(MSG_L_SYNCHRONIZE_ADI_DEVICES_IN_INVENTORY, SENDMSG_QUEUE, deviceState);
    }

    /*package*/ void postUpdatedAdiDeviceState(AdiDeviceState deviceState) {
        sendLMsgNoDelay(MSG_L_UPDATED_ADI_DEVICE_STATE, SENDMSG_QUEUE, deviceState);
    }

    /*package*/ static final class CommunicationDeviceInfo {
        final @NonNull IBinder mCb; // Identifies the requesting client for death handler
        final int mUid; // Requester UID
        final @Nullable AudioDeviceAttributes mDevice; // Device being set or reset.
        final boolean mOn; // true if setting, false if resetting
        final int mScoAudioMode; // only used for SCO: requested audio mode
        final boolean mIsPrivileged; // true if the client app has MODIFY_PHONE_STATE permission
        final @NonNull String mEventSource; // caller identifier for logging

        CommunicationDeviceInfo(@NonNull IBinder cb, int uid,
                @Nullable AudioDeviceAttributes device, boolean on, int scoAudioMode,
                @NonNull String eventSource, boolean isPrivileged) {
            mCb = cb;
            mUid = uid;
            mDevice = device;
            mOn = on;
            mScoAudioMode = scoAudioMode;
            mIsPrivileged = isPrivileged;
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
            if (!(o instanceof CommunicationDeviceInfo)) {
                return false;
            }

            return mCb.equals(((CommunicationDeviceInfo) o).mCb)
                    && mUid == ((CommunicationDeviceInfo) o).mUid;
        }

        @Override
        public String toString() {
            return "CommunicationDeviceInfo mCb=" + mCb.toString()
                    + " mUid=" + mUid
                    + " mDevice=[" + (mDevice != null ? mDevice.toString() : "null") + "]"
                    + " mOn=" + mOn
                    + " mScoAudioMode=" + mScoAudioMode
                    + " mIsPrivileged=" + mIsPrivileged
                    + " mEventSource=" + mEventSource;
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

    /*package*/ boolean handleDeviceConnection(@NonNull AudioDeviceAttributes attributes,
                                boolean connect, @Nullable BluetoothDevice btDevice) {
        synchronized (mDeviceStateLock) {
            return mDeviceInventory.handleDeviceConnection(
                    attributes, connect, false /*for test*/, btDevice);
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

    /*package*/ void setLeAudioTimeout(String address, int device, int codec, int delayMs) {
        sendIILMsg(MSG_IIL_BTLEAUDIO_TIMEOUT, SENDMSG_QUEUE, device, codec, address, delayMs);
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

    /*package*/ int getLeAudioDeviceGroupId(BluetoothDevice device) {
        return mBtHelper.getLeAudioDeviceGroupId(device);
    }

    /*package*/ List<Pair<String, String>> getLeAudioGroupAddresses(int groupId) {
        return mBtHelper.getLeAudioGroupAddresses(groupId);
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
            pw.println("  " + prefix + cl.toString()); });

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
        AudioService.sForceUseLogger.enqueue(
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
        mAudioSystem.setForceUse(useCase, config);
    }

    private void onSendBecomingNoisyIntent() {
        AudioService.sDeviceLogger.enqueue((new EventLogger.StringEvent(
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
                            synchronized (mBluetoothAudioStateLock) {
                                reapplyAudioHalBluetoothState();
                            }
                            mBtHelper.onAudioServerDiedRestoreA2dp();
                            updateCommunicationRoute("MSG_RESTORE_DEVICES");
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
                            final BtDeviceInfo btInfo = (BtDeviceInfo) msg.obj;
                            if (btInfo.mState == BluetoothProfile.STATE_CONNECTED
                                    && !mBtHelper.isProfilePoxyConnected(btInfo.mProfile)) {
                                AudioService.sDeviceLogger.enqueue((new EventLogger.StringEvent(
                                        "msg: MSG_L_SET_BT_ACTIVE_DEVICE "
                                            + "received with null profile proxy: "
                                            + btInfo)).printLog(TAG));
                            } else {
                                @AudioSystem.AudioFormatNativeEnumForBtCodec final int codec =
                                        mBtHelper.getCodecWithFallback(btInfo.mDevice,
                                                btInfo.mProfile, btInfo.mIsLeOutput,
                                                "MSG_L_SET_BT_ACTIVE_DEVICE");
                                mDeviceInventory.onSetBtActiveDevice(btInfo, codec,
                                        (btInfo.mProfile
                                                != BluetoothProfile.LE_AUDIO || btInfo.mIsLeOutput)
                                                ? mAudioService.getBluetoothContextualVolumeStream()
                                                : AudioSystem.STREAM_DEFAULT);
                                if (btInfo.mProfile == BluetoothProfile.LE_AUDIO
                                        || btInfo.mProfile == BluetoothProfile.HEARING_AID) {
                                    onUpdateCommunicationRouteClient(isBluetoothScoRequested(),
                                            "setBluetoothActiveDevice");
                                }
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
                        mDeviceInventory.onMakeA2dpDeviceUnavailableNow(
                                (String) msg.obj, msg.arg1);
                    }
                    break;
                case MSG_IIL_BTLEAUDIO_TIMEOUT:
                    // msg.obj  == address of LE Audio device
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.onMakeLeAudioDeviceUnavailableNow(
                                (String) msg.obj, msg.arg1, msg.arg2);
                    }
                    break;
                case MSG_L_BLUETOOTH_DEVICE_CONFIG_CHANGE: {
                    final BtDeviceInfo btInfo = (BtDeviceInfo) msg.obj;
                    synchronized (mDeviceStateLock) {
                        @AudioSystem.AudioFormatNativeEnumForBtCodec final int codec =
                                mBtHelper.getCodecWithFallback(btInfo.mDevice,
                                        btInfo.mProfile, btInfo.mIsLeOutput,
                                        "MSG_L_BLUETOOTH_DEVICE_CONFIG_CHANGE");
                        mDeviceInventory.onBluetoothDeviceConfigChange(
                                btInfo, codec, BtHelper.EVENT_DEVICE_CONFIG_CHANGE);
                    }
                } break;
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
                            boolean wasBtScoRequested = isBluetoothScoRequested();
                            mAudioModeOwner = (AudioModeInfo) msg.obj;
                            if (mAudioModeOwner.mMode != AudioSystem.MODE_RINGTONE) {
                                onUpdateCommunicationRouteClient(
                                        wasBtScoRequested, "setNewModeOwner");
                            }
                        }
                    }
                    break;

                case MSG_L_SET_COMMUNICATION_DEVICE_FOR_CLIENT:
                    CommunicationDeviceInfo deviceInfo = (CommunicationDeviceInfo) msg.obj;
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            onSetCommunicationDeviceForClient(deviceInfo);
                        }
                    }
                    synchronized (mCommunicationDeviceLock) {
                        if (mCommunicationDeviceUpdateCount > 0) {
                            mCommunicationDeviceUpdateCount--;
                        } else {
                            Log.e(TAG, "mCommunicationDeviceUpdateCount already 0 in"
                                    + " MSG_L_SET_COMMUNICATION_DEVICE_FOR_CLIENT");
                        }
                        mCommunicationDeviceLock.notify();
                    }
                    break;

                case MSG_IL_UPDATE_COMMUNICATION_ROUTE_CLIENT:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            onUpdateCommunicationRouteClient(msg.arg1 == 1, (String) msg.obj);
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

                case MSG_L_RECEIVED_BT_EVENT:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            onReceiveBtEvent((Intent) msg.obj);
                        }
                    }
                    break;

                case MSG_TOGGLE_HDMI:
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.onToggleHdmi();
                    }
                    break;
                case MSG_I_BT_SERVICE_DISCONNECTED_PROFILE:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            mBtHelper.onBtProfileDisconnected(msg.arg1);
                            mDeviceInventory.onBtProfileDisconnected(msg.arg1);
                        }
                    }
                    break;
                case MSG_IL_BT_SERVICE_CONNECTED_PROFILE:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            mBtHelper.onBtProfileConnected(msg.arg1, (BluetoothProfile) msg.obj);
                        }
                    }
                    break;
                case MSG_L_BT_ACTIVE_DEVICE_CHANGE_EXT: {
                    final BtDeviceInfo btInfo = (BtDeviceInfo) msg.obj;
                    if (btInfo.mDevice == null) break;
                    AudioService.sDeviceLogger.enqueue((new EventLogger.StringEvent(
                            "msg: MSG_L_BT_ACTIVE_DEVICE_CHANGE_EXT " + btInfo)).printLog(TAG));
                    synchronized (mDeviceStateLock) {
                        mDeviceInventory.setBluetoothActiveDevice(btInfo);
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
                case MSG_IL_SAVE_NDEF_DEVICE_FOR_STRATEGY: {
                    final int strategy = msg.arg1;
                    final AudioDeviceAttributes device = (AudioDeviceAttributes) msg.obj;
                    mDeviceInventory.onSaveSetDeviceAsNonDefault(strategy, device);
                } break;
                case MSG_IL_SAVE_REMOVE_NDEF_DEVICE_FOR_STRATEGY: {
                    final int strategy = msg.arg1;
                    final AudioDeviceAttributes device = (AudioDeviceAttributes) msg.obj;
                    mDeviceInventory.onSaveRemoveDeviceAsNonDefault(strategy, device);
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
                case MSG_L_NOTIFY_PREFERRED_AUDIOPROFILE_APPLIED: {
                    final BluetoothDevice btDevice = (BluetoothDevice) msg.obj;
                    BtHelper.onNotifyPreferredAudioProfileApplied(btDevice);
                } break;
                case MSG_L_CHECK_COMMUNICATION_DEVICE_REMOVAL: {
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            onCheckCommunicationDeviceRemoval((AudioDeviceAttributes) msg.obj);
                        }
                    }
                } break;
                case MSG_PERSIST_AUDIO_DEVICE_SETTINGS:
                    onPersistAudioDeviceSettings();
                    break;

                case MSG_CHECK_COMMUNICATION_ROUTE_CLIENT_STATE: {
                    synchronized (mDeviceStateLock) {
                        onCheckCommunicationRouteClientState(msg.arg1, msg.arg2 == 1);
                    }
                } break;

                case MSG_I_UPDATE_LE_AUDIO_GROUP_ADDRESSES:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            mDeviceInventory.onUpdateLeAudioGroupAddresses(msg.arg1);
                        }
                    } break;

                case MSG_L_SYNCHRONIZE_ADI_DEVICES_IN_INVENTORY:
                    synchronized (mSetModeLock) {
                        synchronized (mDeviceStateLock) {
                            mDeviceInventory.onSynchronizeAdiDevicesInInventory(
                                    (AdiDeviceState) msg.obj);
                        }
                    } break;

                case MSG_L_UPDATED_ADI_DEVICE_STATE:
                    mAudioService.onUpdatedAdiDeviceState((AdiDeviceState) msg.obj);
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
    private static final int MSG_L_SET_BT_ACTIVE_DEVICE = 7;
    private static final int MSG_BT_HEADSET_CNCT_FAILED = 9;
    private static final int MSG_IL_BTA2DP_TIMEOUT = 10;

    // process change of A2DP device configuration, obj is BluetoothDevice
    private static final int MSG_L_BLUETOOTH_DEVICE_CONFIG_CHANGE = 11;

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

    private static final int MSG_L_SET_COMMUNICATION_DEVICE_FOR_CLIENT = 42;
    private static final int MSG_IL_UPDATE_COMMUNICATION_ROUTE_CLIENT = 43;

    private static final int MSG_L_BT_ACTIVE_DEVICE_CHANGE_EXT = 45;
    //
    // process set volume for Le Audio, obj is BleVolumeInfo
    private static final int MSG_II_SET_LE_AUDIO_OUT_VOLUME = 46;

    private static final int MSG_IL_SAVE_NDEF_DEVICE_FOR_STRATEGY = 47;
    private static final int MSG_IL_SAVE_REMOVE_NDEF_DEVICE_FOR_STRATEGY = 48;
    private static final int MSG_IIL_BTLEAUDIO_TIMEOUT = 49;

    private static final int MSG_L_NOTIFY_PREFERRED_AUDIOPROFILE_APPLIED = 52;
    private static final int MSG_L_CHECK_COMMUNICATION_DEVICE_REMOVAL = 53;

    private static final int MSG_PERSIST_AUDIO_DEVICE_SETTINGS = 54;

    private static final int MSG_L_RECEIVED_BT_EVENT = 55;

    private static final int MSG_CHECK_COMMUNICATION_ROUTE_CLIENT_STATE = 56;
    private static final int MSG_I_UPDATE_LE_AUDIO_GROUP_ADDRESSES = 57;
    private static final int MSG_L_SYNCHRONIZE_ADI_DEVICES_IN_INVENTORY = 58;
    private static final int MSG_L_UPDATED_ADI_DEVICE_STATE = 59;



    private static boolean isMessageHandledUnderWakelock(int msgId) {
        switch(msgId) {
            case MSG_L_SET_WIRED_DEVICE_CONNECTION_STATE:
            case MSG_L_SET_BT_ACTIVE_DEVICE:
            case MSG_IL_BTA2DP_TIMEOUT:
            case MSG_IIL_BTLEAUDIO_TIMEOUT:
            case MSG_L_BLUETOOTH_DEVICE_CONFIG_CHANGE:
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
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
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
                case MSG_IIL_BTLEAUDIO_TIMEOUT:
                case MSG_L_BLUETOOTH_DEVICE_CONFIG_CHANGE:
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

    private void removeMsgForCheckClientState(int uid) {
        CommunicationRouteClient crc = getCommunicationRouteClientForUid(uid);
        if (crc != null) {
            mBrokerHandler.removeEqualMessages(MSG_CHECK_COMMUNICATION_ROUTE_CLIENT_STATE, crc);
        }
    }

    private void sendMsgForCheckClientState(int msg, int existingMsgPolicy,
                                            int arg1, int arg2, Object obj, int delay) {
        if ((existingMsgPolicy == SENDMSG_REPLACE) && (obj != null)) {
            mBrokerHandler.removeEqualMessages(msg, obj);
        }

        long time = SystemClock.uptimeMillis() + delay;
        mBrokerHandler.sendMessageAtTime(mBrokerHandler.obtainMessage(msg, arg1, arg2, obj), time);
    }

    /** List of messages for which music is muted while processing is pending */
    private static final Set<Integer> MESSAGES_MUTE_MUSIC;
    static {
        MESSAGES_MUTE_MUSIC = new HashSet<>();
        MESSAGES_MUTE_MUSIC.add(MSG_L_SET_BT_ACTIVE_DEVICE);
        MESSAGES_MUTE_MUSIC.add(MSG_L_BLUETOOTH_DEVICE_CONFIG_CHANGE);
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
                || message == MSG_L_BLUETOOTH_DEVICE_CONFIG_CHANGE)
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
        private final int mUid;
        private final boolean mIsPrivileged;
        private AudioDeviceAttributes mDevice;
        private boolean mPlaybackActive;
        private boolean mRecordingActive;

        CommunicationRouteClient(IBinder cb, int uid, AudioDeviceAttributes device,
                                 boolean isPrivileged) {
            mCb = cb;
            mUid = uid;
            mDevice = device;
            mIsPrivileged = isPrivileged;
            mPlaybackActive = mAudioService.isPlaybackActiveForUid(uid);
            mRecordingActive = mAudioService.isRecordingActiveForUid(uid);
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
                Log.w(TAG, "CommunicationRouteClient could not unlink to " + mCb + " binder death");
            }
        }

        @Override
        public void binderDied() {
            postCommunicationRouteClientDied(this);
        }

        IBinder getBinder() {
            return mCb;
        }

        int getUid() {
            return mUid;
        }

        boolean isPrivileged() {
            return mIsPrivileged;
        }

        AudioDeviceAttributes getDevice() {
            return mDevice;
        }

        public void setPlaybackActive(boolean active) {
            mPlaybackActive = active;
        }

        public void setRecordingActive(boolean active) {
            mRecordingActive = active;
        }

        public boolean isActive() {
            return mIsPrivileged || mRecordingActive || mPlaybackActive;
        }

        @Override
        public String toString() {
            return "[CommunicationRouteClient: mUid: " + mUid
                    + " mDevice: " + mDevice.toString()
                    + " mIsPrivileged: " + mIsPrivileged
                    + " mPlaybackActive: " + mPlaybackActive
                    + " mRecordingActive: " + mRecordingActive + "]";
        }
    }

    // @GuardedBy("mSetModeLock")
    @GuardedBy("mDeviceStateLock")
    private void onCommunicationRouteClientDied(CommunicationRouteClient client) {
        if (client == null) {
            return;
        }
        Log.w(TAG, "Communication client died");
        setCommunicationRouteForClient(client.getBinder(), client.getUid(), null,
                BtHelper.SCO_MODE_UNDEFINED, client.isPrivileged(),
                "onCommunicationRouteClientDied");
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
        boolean btSCoOn = mBtHelper.isBluetoothScoOn();
        synchronized (mBluetoothAudioStateLock) {
            btSCoOn = btSCoOn && mBluetoothScoOn;
        }

        if (btSCoOn) {
            // Use the SCO device known to BtHelper so that it matches exactly
            // what has been communicated to audio policy manager. The device
            // returned by requestedCommunicationDevice() can be a placeholder SCO device if legacy
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
    private void updateCommunicationRoute(String eventSource) {
        AudioDeviceAttributes preferredCommunicationDevice = preferredCommunicationDevice();
        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "updateCommunicationRoute, preferredCommunicationDevice: "
                    + preferredCommunicationDevice + " eventSource: " + eventSource);
        }
        AudioService.sDeviceLogger.enqueue((new EventLogger.StringEvent(
                "updateCommunicationRoute, preferredCommunicationDevice: "
                + preferredCommunicationDevice + " eventSource: " + eventSource)));

        if (preferredCommunicationDevice == null) {
            AudioDeviceAttributes defaultDevice = getDefaultCommunicationDevice();
            if (defaultDevice != null) {
                mDeviceInventory.setPreferredDevicesForStrategyInt(
                        mCommunicationStrategyId, Arrays.asList(defaultDevice));
                mDeviceInventory.setPreferredDevicesForStrategyInt(
                        mAccessibilityStrategyId, Arrays.asList(defaultDevice));
            } else {
                mDeviceInventory.removePreferredDevicesForStrategyInt(mCommunicationStrategyId);
                mDeviceInventory.removePreferredDevicesForStrategyInt(mAccessibilityStrategyId);
            }
            mDeviceInventory.applyConnectedDevicesRoles();
            mDeviceInventory.reapplyExternalDevicesRoles();
        } else {
            mDeviceInventory.setPreferredDevicesForStrategyInt(
                    mCommunicationStrategyId, Arrays.asList(preferredCommunicationDevice));
            mDeviceInventory.setPreferredDevicesForStrategyInt(
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
    private void onUpdateCommunicationRouteClient(boolean wasBtScoRequested, String eventSource) {
        CommunicationRouteClient crc = topCommunicationRouteClient();
        if (AudioService.DEBUG_COMM_RTE) {
            Log.v(TAG, "onUpdateCommunicationRouteClient, crc: " + crc
                    + " wasBtScoRequested: " + wasBtScoRequested + " eventSource: " + eventSource);
        }
        if (crc != null) {
            setCommunicationRouteForClient(crc.getBinder(), crc.getUid(), crc.getDevice(),
                    BtHelper.SCO_MODE_UNDEFINED, crc.isPrivileged(), eventSource);
        } else {
            if (!isBluetoothScoRequested() && wasBtScoRequested) {
                mBtHelper.stopBluetoothSco(eventSource);
            }
            updateCommunicationRoute(eventSource);
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

    @GuardedBy("mDeviceStateLock")
    private CommunicationRouteClient removeCommunicationRouteClient(
                    IBinder cb, boolean unregister) {
        for (CommunicationRouteClient cl : mCommunicationRouteClients) {
            if (cl.getBinder() == cb) {
                if (unregister) {
                    cl.unregisterDeathRecipient();
                }
                removeMsgForCheckClientState(cl.getUid());
                mCommunicationRouteClients.remove(cl);
                return cl;
            }
        }
        return null;
    }

    @GuardedBy("mDeviceStateLock")
    private CommunicationRouteClient addCommunicationRouteClient(IBinder cb, int uid,
                AudioDeviceAttributes device, boolean isPrivileged) {
        // always insert new request at first position
        removeCommunicationRouteClient(cb, true);
        CommunicationRouteClient client =
                new CommunicationRouteClient(cb, uid, device, isPrivileged);
        if (client.registerDeathRecipient()) {
            mCommunicationRouteClients.add(0, client);
            if (!client.isActive()) {
                // initialize the inactive client's state as active and check it after 6 seconds
                setForceCommunicationClientStateAndDelayedCheck(
                        client,
                        !mAudioService.isPlaybackActiveForUid(client.getUid()),
                        !mAudioService.isRecordingActiveForUid(client.getUid()));
            }
            return client;
        }
        return null;
    }

    @GuardedBy("mDeviceStateLock")
    private CommunicationRouteClient getCommunicationRouteClientForUid(int uid) {
        for (CommunicationRouteClient cl : mCommunicationRouteClients) {
            if (cl.getUid() == uid) {
                return cl;
            }
        }
        return null;
    }

    @GuardedBy("mDeviceStateLock")
    // LE Audio: For system server (Telecom) and APKs targeting S and above, we let the audio
    // policy routing rules select the default communication device.
    // For older APKs, we force LE Audio headset when connected as those APKs cannot select a LE
    // Audiodevice explicitly.
    private boolean communnicationDeviceLeAudioCompatOn() {
        return mAudioModeOwner.mMode == AudioSystem.MODE_IN_COMMUNICATION
                && !(CompatChanges.isChangeEnabled(
                        USE_SET_COMMUNICATION_DEVICE, mAudioModeOwner.mUid)
                     || mAudioModeOwner.mUid == android.os.Process.SYSTEM_UID);
    }

    @GuardedBy("mDeviceStateLock")
    // Hearing Aid: For system server (Telecom) and IN_CALL mode we let the audio
    // policy routing rules select the default communication device.
    // For 3p apps and IN_COMMUNICATION mode we force Hearing aid when connected to maintain
    // backwards compatibility
    private boolean communnicationDeviceHaCompatOn() {
        return mAudioModeOwner.mMode == AudioSystem.MODE_IN_COMMUNICATION
                && !(mAudioModeOwner.mUid == android.os.Process.SYSTEM_UID);
    }

    @GuardedBy("mDeviceStateLock")
    AudioDeviceAttributes getDefaultCommunicationDevice() {
        AudioDeviceAttributes device = null;
        // If both LE and Hearing Aid are active (thie should not happen),
        // priority to Hearing Aid.
        if (communnicationDeviceHaCompatOn()) {
            device = mDeviceInventory.getDeviceOfType(AudioSystem.DEVICE_OUT_HEARING_AID);
        }
        if (device == null && communnicationDeviceLeAudioCompatOn()) {
            device = mDeviceInventory.getDeviceOfType(AudioSystem.DEVICE_OUT_BLE_HEADSET);
        }
        return device;
    }

    void updateCommunicationRouteClientsActivity(
            List<AudioPlaybackConfiguration> playbackConfigs,
            List<AudioRecordingConfiguration> recordConfigs) {
        synchronized (mSetModeLock) {
            synchronized (mDeviceStateLock) {
                for (CommunicationRouteClient crc : mCommunicationRouteClients) {
                    boolean wasActive = crc.isActive();
                    boolean updateClientState = false;
                    if (playbackConfigs != null) {
                        crc.setPlaybackActive(false);
                        for (AudioPlaybackConfiguration config : playbackConfigs) {
                            if (config.getClientUid() == crc.getUid()
                                    && config.isActive()) {
                                crc.setPlaybackActive(true);
                                updateClientState = true;
                                break;
                            }
                        }
                    }
                    if (recordConfigs != null) {
                        crc.setRecordingActive(false);
                        for (AudioRecordingConfiguration config : recordConfigs) {
                            if (config.getClientUid() == crc.getUid()
                                    && !config.isClientSilenced()) {
                                crc.setRecordingActive(true);
                                updateClientState = true;
                                break;
                            }
                        }
                    }
                    if (updateClientState) {
                        removeMsgForCheckClientState(crc.getUid());
                        updateCommunicationRouteClientState(crc, wasActive);
                    } else {
                        if (wasActive) {
                            setForceCommunicationClientStateAndDelayedCheck(
                                    crc,
                                    playbackConfigs != null /* forcePlaybackActive */,
                                    recordConfigs != null /* forceRecordingActive */);
                        }
                    }
                }
            }
        }
    }

    List<String> getDeviceIdentityAddresses(AudioDeviceAttributes device) {
        synchronized (mDeviceStateLock) {
            return mDeviceInventory.getDeviceIdentityAddresses(device);
        }
    }

    void dispatchPreferredMixerAttributesChangedCausedByDeviceRemoved(AudioDeviceInfo info) {
        // Currently, only media usage will be allowed to set preferred mixer attributes
        mAudioService.dispatchPreferredMixerAttributesChanged(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).build(),
                info.getId(),
                null /*mixerAttributes*/);
    }

    /**
     * post a message to persist the audio device settings.
     * Message is delayed by 1s on purpose in case of successive changes in quick succession (at
     * init time for instance)
     * Note this method is made public to work around a Mockito bug where it needs to be public
     * in order to be mocked by a test a the same package
     * (see https://code.google.com/archive/p/mockito/issues/127)
     */
    public void postPersistAudioDeviceSettings() {
        sendMsg(MSG_PERSIST_AUDIO_DEVICE_SETTINGS, SENDMSG_REPLACE, /*delay*/ 1000);
    }

    void onPersistAudioDeviceSettings() {
        final String deviceSettings = mDeviceInventory.getDeviceSettings();
        Log.v(TAG, "onPersistAudioDeviceSettings AdiDeviceState: " + deviceSettings);
        String currentSettings = readDeviceSettings();
        if (deviceSettings.equals(currentSettings)) {
            return;
        }
        final SettingsAdapter settingsAdapter = mAudioService.getSettings();
        try {
            boolean res = settingsAdapter.putSecureStringForUser(mAudioService.getContentResolver(),
                    Settings.Secure.AUDIO_DEVICE_INVENTORY,
                    deviceSettings, UserHandle.USER_CURRENT);
            if (!res) {
                Log.e(TAG, "error saving AdiDeviceState: " + deviceSettings);
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "error saving AdiDeviceState: " + deviceSettings, e);
        }
    }

    private String readDeviceSettings() {
        final SettingsAdapter settingsAdapter = mAudioService.getSettings();
        final ContentResolver contentResolver = mAudioService.getContentResolver();
        return settingsAdapter.getSecureStringForUser(contentResolver,
                Settings.Secure.AUDIO_DEVICE_INVENTORY, UserHandle.USER_CURRENT);
    }

    void onReadAudioDeviceSettings() {
        final SettingsAdapter settingsAdapter = mAudioService.getSettings();
        final ContentResolver contentResolver = mAudioService.getContentResolver();
        String settings = readDeviceSettings();
        if (settings == null) {
            Log.i(TAG, "reading AdiDeviceState from legacy key"
                    + Settings.Secure.SPATIAL_AUDIO_ENABLED);
            // legacy string format for key SPATIAL_AUDIO_ENABLED has the same order of fields like
            // the strings for key AUDIO_DEVICE_INVENTORY. This will ensure to construct valid
            // device settings when calling {@link #setDeviceSettings()}
            settings = settingsAdapter.getSecureStringForUser(contentResolver,
                    Settings.Secure.SPATIAL_AUDIO_ENABLED, UserHandle.USER_CURRENT);
            if (settings == null) {
                Log.i(TAG, "no AdiDeviceState stored with legacy key");
            } else if (!settings.equals("")) {
                // Delete old key value and update the new key
                if (!settingsAdapter.putSecureStringForUser(contentResolver,
                        Settings.Secure.SPATIAL_AUDIO_ENABLED,
                        /*value=*/"",
                        UserHandle.USER_CURRENT)) {
                    Log.w(TAG, "cannot erase the legacy AdiDeviceState with key "
                            + Settings.Secure.SPATIAL_AUDIO_ENABLED);
                }
                if (!settingsAdapter.putSecureStringForUser(contentResolver,
                        Settings.Secure.AUDIO_DEVICE_INVENTORY,
                        settings,
                        UserHandle.USER_CURRENT)) {
                    Log.e(TAG, "error updating the new AdiDeviceState with key "
                            + Settings.Secure.AUDIO_DEVICE_INVENTORY);
                }
            }
        }

        if (settings != null && !settings.equals("")) {
            setDeviceSettings(settings);
        }
    }

    void setDeviceSettings(String settings) {
        mDeviceInventory.setDeviceSettings(settings);
    }

    /** Test only method. */
    String getDeviceSettings() {
        return mDeviceInventory.getDeviceSettings();
    }

    Collection<AdiDeviceState> getImmutableDeviceInventory() {
        return mDeviceInventory.getImmutableDeviceInventory();
    }

    void addOrUpdateDeviceSAStateInInventory(AdiDeviceState deviceState) {
        mDeviceInventory.addOrUpdateDeviceSAStateInInventory(deviceState);
    }

    void addOrUpdateBtAudioDeviceCategoryInInventory(AdiDeviceState deviceState) {
        mDeviceInventory.addOrUpdateAudioDeviceCategoryInInventory(deviceState);
    }

    @Nullable
    AdiDeviceState findDeviceStateForAudioDeviceAttributes(AudioDeviceAttributes ada,
            int canonicalType) {
        return mDeviceInventory.findDeviceStateForAudioDeviceAttributes(ada, canonicalType);
    }

    @Nullable
    AdiDeviceState findBtDeviceStateForAddress(String address, int deviceType) {
        return mDeviceInventory.findBtDeviceStateForAddress(address, deviceType);
    }

    void addAudioDeviceWithCategoryInInventoryIfNeeded(@NonNull String address,
            @AudioDeviceCategory int btAudioDeviceCategory) {
        mDeviceInventory.addAudioDeviceWithCategoryInInventoryIfNeeded(address,
                btAudioDeviceCategory);
    }

    @AudioDeviceCategory
    int getAndUpdateBtAdiDeviceStateCategoryForAddress(@NonNull String address) {
        return mDeviceInventory.getAndUpdateBtAdiDeviceStateCategoryForAddress(address);
    }

    boolean isBluetoothAudioDeviceCategoryFixed(@NonNull String address) {
        return mDeviceInventory.isBluetoothAudioDeviceCategoryFixed(address);
    }

    //------------------------------------------------
    // for testing purposes only
    void clearDeviceInventory() {
        mDeviceInventory.clearDeviceInventory();
    }
}
