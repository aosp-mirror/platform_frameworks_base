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
import android.bluetooth.BluetoothProfile;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.AudioRoutesInfo;
import android.media.AudioSystem;
import android.media.IAudioRoutesObserver;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;

/** @hide */
/*package*/ final class AudioDeviceBroker {

    private static final String TAG = "AudioDeviceBroker";

    private static final long BROKER_WAKELOCK_TIMEOUT_MS = 5000; //5s

    /*package*/ static final  int BTA2DP_DOCK_TIMEOUT_MS = 8000;
    // Timeout for connection to bluetooth headset service
    /*package*/ static final int BT_HEADSET_CNCT_TIMEOUT_MS = 3000;

    private final @NonNull AudioService mAudioService;
    private final @NonNull Context mContext;

    /** Forced device usage for communications sent to AudioSystem */
    private int mForcedUseForComm;
    /**
     * Externally reported force device usage state returned by getters: always consistent
     * with requests by setters */
    private int mForcedUseForCommExt;

    // Manages all connected devices, only ever accessed on the message loop
    //### or make it synchronized
    private final AudioDeviceInventory mDeviceInventory;
    // Manages notifications to BT service
    private final BtHelper mBtHelper;


    //-------------------------------------------------------------------
    private static final Object sLastDeviceConnectionMsgTimeLock = new Object();
    private static long sLastDeviceConnectMsgTime = 0;

    private final Object mBluetoothA2dpEnabledLock = new Object();
    // Request to override default use of A2DP for media.
    @GuardedBy("mBluetoothA2dpEnabledLock")
    private boolean mBluetoothA2dpEnabled;

    // lock always taken synchronized on mConnectedDevices
    /*package*/  final Object mA2dpAvrcpLock = new Object();
    // lock always taken synchronized on mConnectedDevices
    /*package*/  final Object mHearingAidLock = new Object();

    // lock always taken when accessing AudioService.mSetModeDeathHandlers
    /*package*/ final Object mSetModeLock = new Object();

    //-------------------------------------------------------------------
    /*package*/ AudioDeviceBroker(@NonNull Context context, @NonNull AudioService service) {
        mContext = context;
        mAudioService = service;
        setupMessaging(context);
        mBtHelper = new BtHelper(this);
        mDeviceInventory = new AudioDeviceInventory(this);

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
        mBtHelper.onSystemReady();
    }

    /*package*/ void onAudioServerDied() {
        // Restore forced usage for communications and record
        onSetForceUse(AudioSystem.FOR_COMMUNICATION, mForcedUseForComm, "onAudioServerDied");
        onSetForceUse(AudioSystem.FOR_RECORD, mForcedUseForComm, "onAudioServerDied");
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
        mBtHelper.disconnectAllBluetoothProfiles();
    }

    /**
     * Handle BluetoothHeadset intents where the action is one of
     *   {@link BluetoothHeadset#ACTION_ACTIVE_DEVICE_CHANGED} or
     *   {@link BluetoothHeadset#ACTION_AUDIO_STATE_CHANGED}.
     * @param intent
     */
    /*package*/ void receiveBtEvent(@NonNull Intent intent) {
        mBtHelper.receiveBtEvent(intent);
    }

    /*package*/ void setBluetoothA2dpOn_Async(boolean on, String source) {
        synchronized (mBluetoothA2dpEnabledLock) {
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

    /*package*/ void setSpeakerphoneOn(boolean on, String eventSource) {
        if (on) {
            if (mForcedUseForComm == AudioSystem.FORCE_BT_SCO) {
                setForceUse_Async(AudioSystem.FOR_RECORD, AudioSystem.FORCE_NONE, eventSource);
            }
            mForcedUseForComm = AudioSystem.FORCE_SPEAKER;
        } else if (mForcedUseForComm == AudioSystem.FORCE_SPEAKER) {
            mForcedUseForComm = AudioSystem.FORCE_NONE;
        }

        mForcedUseForCommExt = mForcedUseForComm;
        setForceUse_Async(AudioSystem.FOR_COMMUNICATION, mForcedUseForComm, eventSource);
    }

    /*package*/ boolean isSpeakerphoneOn() {
        return (mForcedUseForCommExt == AudioSystem.FORCE_SPEAKER);
    }

    /*package*/ void setWiredDeviceConnectionState(int type,
            @AudioService.ConnectionState int state, String address, String name,
            String caller) {
        //TODO move logging here just like in setBluetooth* methods
        mDeviceInventory.setWiredDeviceConnectionState(type, state, address, name, caller);
    }

    /*package*/ int setBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(
            @NonNull BluetoothDevice device, @AudioService.BtProfileConnectionState int state,
            int profile, boolean suppressNoisyIntent, int a2dpVolume) {
        AudioService.sDeviceLogger.log((new AudioEventLogger.StringEvent(
                "setBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent state=" + state
                        // only querying address as this is the only readily available field
                        // on the device
                        + " addr=" + device.getAddress()
                        + " prof=" + profile + " supprNoisy=" + suppressNoisyIntent
                        + " vol=" + a2dpVolume)).printLog(TAG));
        if (mBrokerHandler.hasMessages(MSG_IL_SET_A2DP_SINK_CONNECTION_STATE,
                new BtHelper.BluetoothA2dpDeviceInfo(device))) {
            AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(
                    "A2DP connection state ignored"));
            return 0;
        }
        return mDeviceInventory.setBluetoothA2dpDeviceConnectionState(
                device, state, profile, suppressNoisyIntent, AudioSystem.DEVICE_NONE, a2dpVolume);
    }

    /*package*/ int handleBluetoothA2dpActiveDeviceChange(
            @NonNull BluetoothDevice device,
            @AudioService.BtProfileConnectionState int state, int profile,
            boolean suppressNoisyIntent, int a2dpVolume) {
        return mDeviceInventory.handleBluetoothA2dpActiveDeviceChange(device, state, profile,
                suppressNoisyIntent, a2dpVolume);
    }

    /*package*/ int setBluetoothHearingAidDeviceConnectionState(
            @NonNull BluetoothDevice device, @AudioService.BtProfileConnectionState int state,
            boolean suppressNoisyIntent, int musicDevice, @NonNull String eventSource) {
        AudioService.sDeviceLogger.log((new AudioEventLogger.StringEvent(
                "setHearingAidDeviceConnectionState state=" + state
                        + " addr=" + device.getAddress()
                        + " supprNoisy=" + suppressNoisyIntent
                        + " src=" + eventSource)).printLog(TAG));
        return mDeviceInventory.setBluetoothHearingAidDeviceConnectionState(
                device, state, suppressNoisyIntent, musicDevice);
    }

    // never called by system components
    /*package*/ void setBluetoothScoOnByApp(boolean on) {
        mForcedUseForCommExt = on ? AudioSystem.FORCE_BT_SCO : AudioSystem.FORCE_NONE;
    }

    /*package*/ boolean isBluetoothScoOnForApp() {
        return mForcedUseForCommExt == AudioSystem.FORCE_BT_SCO;
    }

    /*package*/ void setBluetoothScoOn(boolean on, String eventSource) {
        //Log.i(TAG, "setBluetoothScoOnInt: " + on + " " + eventSource);
        if (on) {
            // do not accept SCO ON if SCO audio is not connected
            if (!mBtHelper.isBluetoothScoOn()) {
                mForcedUseForCommExt = AudioSystem.FORCE_BT_SCO;
                return;
            }
            mForcedUseForComm = AudioSystem.FORCE_BT_SCO;
        } else if (mForcedUseForComm == AudioSystem.FORCE_BT_SCO) {
            mForcedUseForComm = AudioSystem.FORCE_NONE;
        }
        mForcedUseForCommExt = mForcedUseForComm;
        AudioSystem.setParameters("BT_SCO=" + (on ? "on" : "off"));
        sendIILMsgNoDelay(MSG_IIL_SET_FORCE_USE, SENDMSG_QUEUE,
                AudioSystem.FOR_COMMUNICATION, mForcedUseForComm, eventSource);
        sendIILMsgNoDelay(MSG_IIL_SET_FORCE_USE, SENDMSG_QUEUE,
                AudioSystem.FOR_RECORD, mForcedUseForComm, eventSource);
        // Un-mute ringtone stream volume
        mAudioService.setUpdateRingerModeServiceInt();
    }

    /*package*/ AudioRoutesInfo startWatchingRoutes(IAudioRoutesObserver observer) {
        return mDeviceInventory.startWatchingRoutes(observer);
    }

    /*package*/ AudioRoutesInfo getCurAudioRoutes() {
        return mDeviceInventory.getCurAudioRoutes();
    }

    /*package*/ boolean isAvrcpAbsoluteVolumeSupported() {
        synchronized (mA2dpAvrcpLock) {
            return mBtHelper.isAvrcpAbsoluteVolumeSupported();
        }
    }

    /*package*/ boolean isBluetoothA2dpOn() {
        synchronized (mBluetoothA2dpEnabledLock) {
            return mBluetoothA2dpEnabled;
        }
    }

    /*package*/ void postSetAvrcpAbsoluteVolumeIndex(int index) {
        sendIMsgNoDelay(MSG_I_SET_AVRCP_ABSOLUTE_VOLUME, SENDMSG_REPLACE, index);
    }

    /*package*/ void postSetHearingAidVolumeIndex(int index, int streamType) {
        sendIIMsgNoDelay(MSG_II_SET_HEARING_AID_VOLUME, SENDMSG_REPLACE, index, streamType);
    }

    /*package*/ void postDisconnectBluetoothSco(int exceptPid) {
        sendIMsgNoDelay(MSG_I_DISCONNECT_BT_SCO, SENDMSG_REPLACE, exceptPid);
    }

    /*package*/ void postBluetoothA2dpDeviceConfigChange(@NonNull BluetoothDevice device) {
        sendLMsgNoDelay(MSG_L_A2DP_DEVICE_CONFIG_CHANGE, SENDMSG_QUEUE, device);
    }

    /*package*/ void startBluetoothScoForClient_Sync(IBinder cb, int scoAudioMode,
                @NonNull String eventSource) {
        mBtHelper.startBluetoothScoForClient(cb, scoAudioMode, eventSource);
    }

    /*package*/ void stopBluetoothScoForClient_Sync(IBinder cb, @NonNull String eventSource) {
        mBtHelper.stopBluetoothScoForClient(cb, eventSource);
    }

    //---------------------------------------------------------------------
    // Communication with (to) AudioService
    //TODO check whether the AudioService methods are candidates to move here
    /*package*/ void postAccessoryPlugMediaUnmute(int device) {
        mAudioService.postAccessoryPlugMediaUnmute(device);
    }

    /*package*/ AudioService.VolumeStreamState getStreamState(int streamType) {
        return mAudioService.getStreamState(streamType);
    }

    /*package*/ ArrayList<AudioService.SetModeDeathHandler> getSetModeDeathHandlers() {
        return mAudioService.mSetModeDeathHandlers;
    }

    /*package*/ int getDeviceForStream(int streamType) {
        return mAudioService.getDeviceForStream(streamType);
    }

    /*package*/ void setDeviceVolume(AudioService.VolumeStreamState streamState, int device) {
        mAudioService.setDeviceVolume(streamState, device);
    }

    /*packages*/ void observeDevicesForAllStreams() {
        mAudioService.observeDevicesForAllStreams();
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

    /*package*/ void checkVolumeCecOnHdmiConnection(int state, String caller) {
        mAudioService.checkVolumeCecOnHdmiConnection(state, caller);
    }

    //---------------------------------------------------------------------
    // Message handling on behalf of helper classes
    /*package*/ void broadcastScoConnectionState(int state) {
        sendIMsgNoDelay(MSG_I_BROADCAST_BT_CONNECTION_STATE, SENDMSG_QUEUE, state);
    }

    /*package*/ void broadcastBecomingNoisy() {
        sendMsgNoDelay(MSG_BROADCAST_AUDIO_BECOMING_NOISY, SENDMSG_REPLACE);
    }

    //###TODO unify with handleSetA2dpSinkConnectionState
    /*package*/ void postA2dpSinkConnection(int state,
            @NonNull BtHelper.BluetoothA2dpDeviceInfo btDeviceInfo, int delay) {
        sendILMsg(MSG_IL_SET_A2DP_SINK_CONNECTION_STATE, SENDMSG_QUEUE,
                state, btDeviceInfo, delay);
    }

    //###TODO unify with handleSetA2dpSourceConnectionState
    /*package*/ void postA2dpSourceConnection(int state,
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

    //---------------------------------------------------------------------
    // Method forwarding between the helper classes (BtHelper, AudioDeviceInventory)
    // only call from a "handle"* method or "on"* method

    // Handles request to override default use of A2DP for media.
    //@GuardedBy("mConnectedDevices")
    /*package*/ void setBluetoothA2dpOnInt(boolean on, String source) {
        // for logging only
        final String eventSource = new StringBuilder("setBluetoothA2dpOn(").append(on)
                .append(") from u/pid:").append(Binder.getCallingUid()).append("/")
                .append(Binder.getCallingPid()).append(" src:").append(source).toString();

        synchronized (mBluetoothA2dpEnabledLock) {
            mBluetoothA2dpEnabled = on;
            mBrokerHandler.removeMessages(MSG_IIL_SET_FORCE_BT_A2DP_USE);
            onSetForceUse(
                    AudioSystem.FOR_MEDIA,
                    mBluetoothA2dpEnabled ? AudioSystem.FORCE_NONE : AudioSystem.FORCE_NO_BT_A2DP,
                    eventSource);
        }
    }

    /*package*/ boolean handleDeviceConnection(boolean connect, int device, String address,
                                                       String deviceName) {
        return mDeviceInventory.handleDeviceConnection(connect, device, address, deviceName);
    }

    /*package*/ void handleDisconnectA2dp() {
        mDeviceInventory.disconnectA2dp();
    }
    /*package*/ void handleDisconnectA2dpSink() {
        mDeviceInventory.disconnectA2dpSink();
    }

    /*package*/ void handleSetA2dpSinkConnectionState(@BluetoothProfile.BtProfileState int state,
                @NonNull BtHelper.BluetoothA2dpDeviceInfo btDeviceInfo) {
        final int intState = (state == BluetoothA2dp.STATE_CONNECTED) ? 1 : 0;
        //### DOESN'T HONOR SYNC ON DEVICES -> make a synchronized version?
        // might be ok here because called on BT thread? + sync happening in
        //  checkSendBecomingNoisyIntent
        final int delay = mDeviceInventory.checkSendBecomingNoisyIntent(
                AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, intState,
                AudioSystem.DEVICE_NONE);
        final String addr = btDeviceInfo == null ? "null" : btDeviceInfo.getBtDevice().getAddress();

        if (AudioService.DEBUG_DEVICES) {
            Log.d(TAG, "handleSetA2dpSinkConnectionState btDevice= " + btDeviceInfo
                    + " state= " + state
                    + " is dock: " + btDeviceInfo.getBtDevice().isBluetoothDock());
        }
        sendILMsg(MSG_IL_SET_A2DP_SINK_CONNECTION_STATE, SENDMSG_QUEUE,
                state, btDeviceInfo, delay);
    }

    /*package*/ void handleDisconnectHearingAid() {
        mDeviceInventory.disconnectHearingAid();
    }

    /*package*/ void handleSetA2dpSourceConnectionState(@BluetoothProfile.BtProfileState int state,
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

    /*package*/ void postReportNewRoutes() {
        sendMsgNoDelay(MSG_REPORT_NEW_ROUTES, SENDMSG_NOOP);
    }

    /*package*/ void cancelA2dpDockTimeout() {
        mBrokerHandler.removeMessages(MSG_IL_BTA2DP_DOCK_TIMEOUT);
    }

    /*package*/ void postA2dpActiveDeviceChange(BtHelper.BluetoothA2dpDeviceInfo btDeviceInfo) {
        sendLMsgNoDelay(MSG_L_A2DP_ACTIVE_DEVICE_CHANGE, SENDMSG_QUEUE, btDeviceInfo);
    }

    //###
    // must be called synchronized on mConnectedDevices
    /*package*/ boolean hasScheduledA2dpDockTimeout() {
        return mBrokerHandler.hasMessages(MSG_IL_BTA2DP_DOCK_TIMEOUT);
    }

    //###
    // must be called synchronized on mConnectedDevices
    /*package*/  boolean hasScheduledA2dpSinkConnectionState(BluetoothDevice btDevice) {
        return mBrokerHandler.hasMessages(MSG_IL_SET_A2DP_SINK_CONNECTION_STATE,
                new BtHelper.BluetoothA2dpDeviceInfo(btDevice));
    }

    /*package*/ void setA2dpDockTimeout(String address, int a2dpCodec, int delayMs) {
        sendILMsg(MSG_IL_BTA2DP_DOCK_TIMEOUT, SENDMSG_QUEUE, a2dpCodec, address, delayMs);
    }

    /*package*/ void setAvrcpAbsoluteVolumeSupported(boolean supported) {
        synchronized (mA2dpAvrcpLock) {
            mBtHelper.setAvrcpAbsoluteVolumeSupported(supported);
        }
    }

    /*package*/ boolean getBluetoothA2dpEnabled() {
        synchronized (mBluetoothA2dpEnabledLock) {
            return mBluetoothA2dpEnabled;
        }
    }

    /*package*/ int getA2dpCodec(@NonNull BluetoothDevice device) {
        synchronized (mA2dpAvrcpLock) {
            return mBtHelper.getA2dpCodec(device);
        }
    }

    //---------------------------------------------------------------------
    // Internal handling of messages
    // These methods are ALL synchronous, in response to message handling in BrokerHandler
    // Blocking in any of those will block the message queue

    private void onSetForceUse(int useCase, int config, String eventSource) {
        if (useCase == AudioSystem.FOR_MEDIA) {
            postReportNewRoutes();
        }
        AudioService.sForceUseLogger.log(
                new AudioServiceEvents.ForceUseEvent(useCase, config, eventSource));
        AudioSystem.setForceUse(useCase, config);
    }

    private void onSendBecomingNoisyIntent() {
        AudioService.sDeviceLogger.log((new AudioEventLogger.StringEvent(
                "broadcast ACTION_AUDIO_BECOMING_NOISY")).printLog(TAG));
        sendBroadcastToAll(new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
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
                    mDeviceInventory.onRestoreDevices();
                    synchronized (mBluetoothA2dpEnabledLock) {
                        mBtHelper.onAudioServerDiedRestoreA2dp();
                    }
                    break;
                case MSG_L_SET_WIRED_DEVICE_CONNECTION_STATE:
                    mDeviceInventory.onSetWiredDeviceConnectionState(
                            (AudioDeviceInventory.WiredDeviceConnectionState) msg.obj);
                    break;
                case MSG_I_BROADCAST_BT_CONNECTION_STATE:
                    mBtHelper.onBroadcastScoConnectionState(msg.arg1);
                    break;
                case MSG_IIL_SET_FORCE_USE: // intented fall-through
                case MSG_IIL_SET_FORCE_BT_A2DP_USE:
                    onSetForceUse(msg.arg1, msg.arg2, (String) msg.obj);
                    break;
                case MSG_REPORT_NEW_ROUTES:
                    mDeviceInventory.onReportNewRoutes();
                    break;
                case MSG_IL_SET_A2DP_SINK_CONNECTION_STATE:
                    mDeviceInventory.onSetA2dpSinkConnectionState(
                            (BtHelper.BluetoothA2dpDeviceInfo) msg.obj, msg.arg1);
                    break;
                case MSG_IL_SET_A2DP_SOURCE_CONNECTION_STATE:
                    mDeviceInventory.onSetA2dpSourceConnectionState(
                            (BtHelper.BluetoothA2dpDeviceInfo) msg.obj, msg.arg1);
                    break;
                case MSG_IL_SET_HEARING_AID_CONNECTION_STATE:
                    mDeviceInventory.onSetHearingAidConnectionState(
                            (BluetoothDevice) msg.obj, msg.arg1);
                    break;
                case MSG_BT_HEADSET_CNCT_FAILED:
                    mBtHelper.resetBluetoothSco();
                    break;
                case MSG_IL_BTA2DP_DOCK_TIMEOUT:
                    // msg.obj  == address of BTA2DP device
                    mDeviceInventory.onMakeA2dpDeviceUnavailableNow((String) msg.obj, msg.arg1);
                    break;
                case MSG_L_A2DP_DEVICE_CONFIG_CHANGE:
                    final int a2dpCodec;
                    final BluetoothDevice btDevice = (BluetoothDevice) msg.obj;
                    synchronized (mA2dpAvrcpLock) {
                        a2dpCodec = mBtHelper.getA2dpCodec(btDevice);
                    }
                    mDeviceInventory.onBluetoothA2dpDeviceConfigChange(
                            new BtHelper.BluetoothA2dpDeviceInfo(btDevice, -1, a2dpCodec));
                    break;
                case MSG_BROADCAST_AUDIO_BECOMING_NOISY:
                    onSendBecomingNoisyIntent();
                    break;
                case MSG_II_SET_HEARING_AID_VOLUME:
                    mBtHelper.setHearingAidVolume(msg.arg1, msg.arg2);
                    break;
                case MSG_I_SET_AVRCP_ABSOLUTE_VOLUME:
                    mBtHelper.setAvrcpAbsoluteVolumeIndex(msg.arg1);
                    break;
                case MSG_I_DISCONNECT_BT_SCO:
                    mBtHelper.disconnectBluetoothSco(msg.arg1);
                    break;
                case MSG_TOGGLE_HDMI:
                    mDeviceInventory.onToggleHdmi();
                    break;
                case MSG_L_A2DP_ACTIVE_DEVICE_CHANGE:
                    mDeviceInventory.onBluetoothA2dpActiveDeviceChange(
                            (BtHelper.BluetoothA2dpDeviceInfo) msg.obj);
                    break;
                default:
                    Log.wtf(TAG, "Invalid message " + msg.what);
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
    private static final int MSG_IL_SET_A2DP_SINK_CONNECTION_STATE = 6;
    private static final int MSG_IL_SET_A2DP_SOURCE_CONNECTION_STATE = 7;
    private static final int MSG_IL_SET_HEARING_AID_CONNECTION_STATE = 8;
    private static final int MSG_BT_HEADSET_CNCT_FAILED = 9;
    private static final int MSG_IL_BTA2DP_DOCK_TIMEOUT = 10;
    private static final int MSG_L_A2DP_DEVICE_CONFIG_CHANGE = 11;
    private static final int MSG_BROADCAST_AUDIO_BECOMING_NOISY = 12;
    private static final int MSG_REPORT_NEW_ROUTES = 13;
    private static final int MSG_II_SET_HEARING_AID_VOLUME = 14;
    private static final int MSG_I_SET_AVRCP_ABSOLUTE_VOLUME = 15;
    private static final int MSG_I_DISCONNECT_BT_SCO = 16;
    private static final int MSG_TOGGLE_HDMI = 17;
    private static final int MSG_L_A2DP_ACTIVE_DEVICE_CHANGE = 18;


    private static boolean isMessageHandledUnderWakelock(int msgId) {
        switch(msgId) {
            case MSG_L_SET_WIRED_DEVICE_CONNECTION_STATE:
            case MSG_IL_SET_A2DP_SINK_CONNECTION_STATE:
            case MSG_IL_SET_A2DP_SOURCE_CONNECTION_STATE:
            case MSG_IL_SET_HEARING_AID_CONNECTION_STATE:
            case MSG_IL_BTA2DP_DOCK_TIMEOUT:
            case MSG_L_A2DP_DEVICE_CONFIG_CHANGE:
            case MSG_TOGGLE_HDMI:
            case MSG_L_A2DP_ACTIVE_DEVICE_CHANGE:
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

        synchronized (sLastDeviceConnectionMsgTimeLock) {
            long time = SystemClock.uptimeMillis() + delay;

            switch (msg) {
                case MSG_IL_SET_A2DP_SOURCE_CONNECTION_STATE:
                case MSG_IL_SET_A2DP_SINK_CONNECTION_STATE:
                case MSG_IL_SET_HEARING_AID_CONNECTION_STATE:
                case MSG_L_SET_WIRED_DEVICE_CONNECTION_STATE:
                case MSG_IL_BTA2DP_DOCK_TIMEOUT:
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

    //-------------------------------------------------------------
    // internal utilities
    private void sendBroadcastToAll(Intent intent) {
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
