/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.tv;

import static android.media.tv.TvInputManager.INPUT_STATE_CONNECTED;
import static android.media.tv.TvInputManager.INPUT_STATE_DISCONNECTED;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiHotplugEvent;
import android.media.AudioDevicePort;
import android.media.AudioManager;
import android.media.AudioPatch;
import android.media.AudioPort;
import android.media.AudioPortConfig;
import android.media.tv.ITvInputHardware;
import android.media.tv.ITvInputHardwareCallback;
import android.media.tv.TvInputHardwareInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvStreamConfig;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.KeyEvent;
import android.view.Surface;

import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A helper class for TvInputManagerService to handle TV input hardware.
 *
 * This class does a basic connection management and forwarding calls to TvInputHal which eventually
 * calls to tv_input HAL module.
 *
 * @hide
 */
class TvInputHardwareManager
        implements TvInputHal.Callback, HdmiControlManager.HotplugEventListener {
    private static final String TAG = TvInputHardwareManager.class.getSimpleName();
    private final TvInputHal mHal = new TvInputHal(this);
    private final SparseArray<Connection> mConnections = new SparseArray<Connection>();
    private final List<TvInputHardwareInfo> mInfoList = new ArrayList<TvInputHardwareInfo>();
    private final Context mContext;
    private final TvInputManagerService.Client mClient;
    private final Set<Integer> mActiveHdmiSources = new HashSet<Integer>();
    private final AudioManager mAudioManager;
    private final SparseBooleanArray mHdmiStateMap = new SparseBooleanArray();
    // TODO: Should handle INACTIVE case.
    private final SparseArray<TvInputInfo> mTvInputInfoMap = new SparseArray<TvInputInfo>();

    // Calls to mClient should happen here.
    private final HandlerThread mHandlerThread = new HandlerThread(TAG);
    private final Handler mHandler;

    private final Object mLock = new Object();

    public TvInputHardwareManager(Context context, TvInputManagerService.Client client) {
        mContext = context;
        mClient = client;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mHal.init();

        mHandlerThread.start();
        mHandler = new ClientHandler(mHandlerThread.getLooper());
    }

    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            HdmiControlManager hdmiControlManager =
                    (HdmiControlManager) mContext.getSystemService(Context.HDMI_CONTROL_SERVICE);
            hdmiControlManager.addHotplugEventListener(this);
        }
    }

    @Override
    public void onDeviceAvailable(
            TvInputHardwareInfo info, TvStreamConfig[] configs) {
        synchronized (mLock) {
            Connection connection = new Connection(info);
            connection.updateConfigsLocked(configs);
            mConnections.put(info.getDeviceId(), connection);
            buildInfoListLocked();
            // TODO: notify if necessary
        }
    }

    private void buildInfoListLocked() {
        mInfoList.clear();
        for (int i = 0; i < mConnections.size(); ++i) {
            mInfoList.add(mConnections.valueAt(i).getHardwareInfoLocked());
        }
    }

    @Override
    public void onDeviceUnavailable(int deviceId) {
        synchronized (mLock) {
            Connection connection = mConnections.get(deviceId);
            if (connection == null) {
                Slog.e(TAG, "onDeviceUnavailable: Cannot find a connection with " + deviceId);
                return;
            }
            connection.resetLocked(null, null, null, null, null);
            mConnections.remove(deviceId);
            buildInfoListLocked();
            // TODO: notify if necessary
        }
    }

    @Override
    public void onStreamConfigurationChanged(int deviceId, TvStreamConfig[] configs) {
        synchronized (mLock) {
            Connection connection = mConnections.get(deviceId);
            if (connection == null) {
                Slog.e(TAG, "StreamConfigurationChanged: Cannot find a connection with "
                        + deviceId);
                return;
            }
            connection.updateConfigsLocked(configs);
            try {
                connection.getCallbackLocked().onStreamConfigChanged(configs);
            } catch (RemoteException e) {
                Slog.e(TAG, "onStreamConfigurationChanged: " + e);
            }
        }
    }

    public List<TvInputHardwareInfo> getHardwareList() {
        synchronized (mLock) {
            return mInfoList;
        }
    }

    private boolean checkUidChangedLocked(
            Connection connection, int callingUid, int resolvedUserId) {
        Integer connectionCallingUid = connection.getCallingUidLocked();
        Integer connectionResolvedUserId = connection.getResolvedUserIdLocked();
        if (connectionCallingUid == null || connectionResolvedUserId == null) {
            return true;
        }
        if (connectionCallingUid != callingUid || connectionResolvedUserId != resolvedUserId) {
            return true;
        }
        return false;
    }

    private int convertConnectedToState(boolean connected) {
        if (connected) {
            return INPUT_STATE_CONNECTED;
        } else {
            return INPUT_STATE_DISCONNECTED;
        }
    }

    public void registerTvInputInfo(TvInputInfo info, int deviceId) {
        if (info.getType() == TvInputInfo.TYPE_VIRTUAL) {
            throw new IllegalArgumentException("info (" + info + ") has virtual type.");
        }
        synchronized (mLock) {
            if (mTvInputInfoMap.indexOfKey(deviceId) >= 0) {
                Slog.w(TAG, "Trying to override previous registration: old = "
                        + mTvInputInfoMap.get(deviceId) + ":" + deviceId + ", new = "
                        + info + ":" + deviceId);
            }
            mTvInputInfoMap.put(deviceId, info);

            for (int i = 0; i < mHdmiStateMap.size(); ++i) {
                String inputId = findInputIdForHdmiPortLocked(mHdmiStateMap.keyAt(i));
                if (inputId != null && inputId.equals(info.getId())) {
                    mHandler.obtainMessage(ClientHandler.DO_SET_AVAILABLE,
                            convertConnectedToState(mHdmiStateMap.valueAt(i)), 0,
                            inputId).sendToTarget();
                }
            }
        }
    }

    /**
     * Create a TvInputHardware object with a specific deviceId. One service at a time can access
     * the object, and if more than one process attempts to create hardware with the same deviceId,
     * the latest service will get the object and all the other hardware are released. The
     * release is notified via ITvInputHardwareCallback.onReleased().
     */
    public ITvInputHardware acquireHardware(int deviceId, ITvInputHardwareCallback callback,
            TvInputInfo info, int callingUid, int resolvedUserId) {
        if (callback == null) {
            throw new NullPointerException();
        }
        synchronized (mLock) {
            Connection connection = mConnections.get(deviceId);
            if (connection == null) {
                Slog.e(TAG, "Invalid deviceId : " + deviceId);
                return null;
            }
            if (checkUidChangedLocked(connection, callingUid, resolvedUserId)) {
                TvInputHardwareImpl hardware =
                        new TvInputHardwareImpl(connection.getHardwareInfoLocked());
                try {
                    callback.asBinder().linkToDeath(connection, 0);
                } catch (RemoteException e) {
                    hardware.release();
                    return null;
                }
                connection.resetLocked(hardware, callback, info, callingUid, resolvedUserId);
            }
            return connection.getHardwareLocked();
        }
    }

    /**
     * Release the specified hardware.
     */
    public void releaseHardware(int deviceId, ITvInputHardware hardware, int callingUid,
            int resolvedUserId) {
        synchronized (mLock) {
            Connection connection = mConnections.get(deviceId);
            if (connection == null) {
                Slog.e(TAG, "Invalid deviceId : " + deviceId);
                return;
            }
            if (connection.getHardwareLocked() != hardware
                    || checkUidChangedLocked(connection, callingUid, resolvedUserId)) {
                return;
            }
            connection.resetLocked(null, null, null, null, null);
        }
    }

    private String findInputIdForHdmiPortLocked(int port) {
        for (TvInputHardwareInfo hardwareInfo : mInfoList) {
            if (hardwareInfo.getType() == TvInputHardwareInfo.TV_INPUT_TYPE_HDMI
                    && hardwareInfo.getHdmiPortId() == port) {
                TvInputInfo info = mTvInputInfoMap.get(hardwareInfo.getDeviceId());
                return (info == null) ? null : info.getId();
            }
        }
        return null;
    }

    // HdmiControlManager.HotplugEventListener implementation.

    @Override
    public void onReceived(HdmiHotplugEvent event) {
        String inputId = null;

        synchronized (mLock) {
            mHdmiStateMap.put(event.getPort(), event.isConnected());
            inputId = findInputIdForHdmiPortLocked(event.getPort());
            if (inputId == null) {
                return;
            }
            mHandler.obtainMessage(ClientHandler.DO_SET_AVAILABLE,
                    convertConnectedToState(event.isConnected()), 0, inputId).sendToTarget();
        }
    }

    private class Connection implements IBinder.DeathRecipient {
        private final TvInputHardwareInfo mHardwareInfo;
        private TvInputInfo mInfo;
        private TvInputHardwareImpl mHardware = null;
        private ITvInputHardwareCallback mCallback;
        private TvStreamConfig[] mConfigs = null;
        private Integer mCallingUid = null;
        private Integer mResolvedUserId = null;

        public Connection(TvInputHardwareInfo hardwareInfo) {
            mHardwareInfo = hardwareInfo;
        }

        // *Locked methods assume TvInputHardwareManager.mLock is held.

        public void resetLocked(TvInputHardwareImpl hardware, ITvInputHardwareCallback callback,
                TvInputInfo info, Integer callingUid, Integer resolvedUserId) {
            if (mHardware != null) {
                try {
                    mCallback.onReleased();
                } catch (RemoteException e) {
                    Slog.e(TAG, "Connection::resetHardware: " + e);
                }
                mHardware.release();
            }
            mHardware = hardware;
            mCallback = callback;
            mInfo = info;
            mCallingUid = callingUid;
            mResolvedUserId = resolvedUserId;

            if (mHardware != null && mCallback != null) {
                try {
                    mCallback.onStreamConfigChanged(getConfigsLocked());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Connection::resetHardware: " + e);
                }
            }
        }

        public void updateConfigsLocked(TvStreamConfig[] configs) {
            mConfigs = configs;
        }

        public TvInputHardwareInfo getHardwareInfoLocked() {
            return mHardwareInfo;
        }

        public TvInputInfo getInfoLocked() {
            return mInfo;
        }

        public ITvInputHardware getHardwareLocked() {
            return mHardware;
        }

        public ITvInputHardwareCallback getCallbackLocked() {
            return mCallback;
        }

        public TvStreamConfig[] getConfigsLocked() {
            return mConfigs;
        }

        public Integer getCallingUidLocked() {
            return mCallingUid;
        }

        public Integer getResolvedUserIdLocked() {
            return mResolvedUserId;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                resetLocked(null, null, null, null, null);
            }
        }
    }

    private class TvInputHardwareImpl extends ITvInputHardware.Stub {
        private final TvInputHardwareInfo mInfo;
        private boolean mReleased = false;
        private final Object mImplLock = new Object();

        private final AudioDevicePort mAudioSource;
        private final AudioDevicePort mAudioSink;
        private AudioPatch mAudioPatch = null;

        private TvStreamConfig mActiveConfig = null;

        public TvInputHardwareImpl(TvInputHardwareInfo info) {
            mInfo = info;
            AudioDevicePort audioSource = null;
            AudioDevicePort audioSink = null;
            if (mInfo.getAudioType() != AudioManager.DEVICE_NONE) {
                ArrayList<AudioPort> devicePorts = new ArrayList<AudioPort>();
                if (mAudioManager.listAudioDevicePorts(devicePorts) == AudioManager.SUCCESS) {
                    // Find source
                    for (AudioPort port : devicePorts) {
                        AudioDevicePort devicePort = (AudioDevicePort) port;
                        if (devicePort.type() == mInfo.getAudioType() &&
                                devicePort.address().equals(mInfo.getAudioAddress())) {
                            audioSource = devicePort;
                            break;
                        }
                    }
                    // Find sink
                    // TODO: App may want to specify sink device?
                    int sinkDevices = mAudioManager.getDevicesForStream(AudioManager.STREAM_MUSIC);
                    for (AudioPort port : devicePorts) {
                        AudioDevicePort devicePort = (AudioDevicePort) port;
                        if (devicePort.type() == sinkDevices) {
                            audioSink = devicePort;
                            break;
                        }
                    }
                }
            }
            mAudioSource = audioSource;
            mAudioSink = audioSink;
        }

        public void release() {
            synchronized (mImplLock) {
                if (mAudioPatch != null) {
                    mAudioManager.releaseAudioPatch(mAudioPatch);
                    mAudioPatch = null;
                }
                mReleased = true;
            }
        }

        // A TvInputHardwareImpl object holds only one active session. Therefore, if a client
        // attempts to call setSurface with different TvStreamConfig objects, the last call will
        // prevail.
        @Override
        public boolean setSurface(Surface surface, TvStreamConfig config)
                throws RemoteException {
            synchronized (mImplLock) {
                if (mReleased) {
                    throw new IllegalStateException("Device already released.");
                }
                if (surface != null && config == null) {
                    return false;
                }
                if (surface == null && mActiveConfig == null) {
                    return false;
                }
                if (mInfo.getType() == TvInputHal.TYPE_HDMI) {
                    if (surface != null) {
                        // Set "Active Source" for HDMI.
                        // TODO(hdmi): mHdmiClient.deviceSelect(...);
                        mActiveHdmiSources.add(mInfo.getDeviceId());
                    } else {
                        mActiveHdmiSources.remove(mInfo.getDeviceId());
                        if (mActiveHdmiSources.size() == 0) {
                            // Tell HDMI that no HDMI source is active
                            // TODO(hdmi): mHdmiClient.portSelect(null);
                        }
                    }
                }
                if (mAudioSource != null && mAudioSink != null) {
                    if (surface != null) {
                        AudioPortConfig sourceConfig = mAudioSource.activeConfig();
                        AudioPortConfig sinkConfig = mAudioSink.activeConfig();
                        AudioPatch[] audioPatchArray = new AudioPatch[] { mAudioPatch };
                        // TODO: build config if activeConfig() == null
                        mAudioManager.createAudioPatch(
                                audioPatchArray,
                                new AudioPortConfig[] { sourceConfig },
                                new AudioPortConfig[] { sinkConfig });
                        mAudioPatch = audioPatchArray[0];
                    } else {
                        mAudioManager.releaseAudioPatch(mAudioPatch);
                        mAudioPatch = null;
                    }
                }
                int result = TvInputHal.ERROR_UNKNOWN;
                if (surface == null) {
                    result = mHal.removeStream(mInfo.getDeviceId(), mActiveConfig);
                    mActiveConfig = null;
                } else {
                    if (config != mActiveConfig && mActiveConfig != null) {
                        result = mHal.removeStream(mInfo.getDeviceId(), mActiveConfig);
                        if (result != TvInputHal.SUCCESS) {
                            mActiveConfig = null;
                            return false;
                        }
                    }
                    result = mHal.addStream(mInfo.getDeviceId(), surface, config);
                    if (result == TvInputHal.SUCCESS) {
                        mActiveConfig = config;
                    }
                }
                return result == TvInputHal.SUCCESS;
            }
        }

        @Override
        public void setVolume(float volume) throws RemoteException {
            synchronized (mImplLock) {
                if (mReleased) {
                    throw new IllegalStateException("Device already released.");
                }
            }
            // TODO: Use AudioGain?
        }

        @Override
        public boolean dispatchKeyEventToHdmi(KeyEvent event) throws RemoteException {
            synchronized (mImplLock) {
                if (mReleased) {
                    throw new IllegalStateException("Device already released.");
                }
            }
            if (mInfo.getType() != TvInputHal.TYPE_HDMI) {
                return false;
            }
            // TODO(hdmi): mHdmiClient.sendKeyEvent(event);
            return false;
        }
    }

    private class ClientHandler extends Handler {
        private static final int DO_SET_AVAILABLE = 1;

        ClientHandler(Looper looper) {
            super(looper);
        }

        @Override
        public final void handleMessage(Message msg) {
            switch (msg.what) {
                case DO_SET_AVAILABLE: {
                    String inputId = (String) msg.obj;
                    int state = msg.arg1;
                    mClient.setState(inputId, state);
                    break;
                }
                default: {
                    Slog.w(TAG, "Unhandled message: " + msg);
                    break;
                }
            }
        }
    }
}
