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
import android.content.Intent;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiHotplugEvent;
import android.hardware.hdmi.IHdmiControlService;
import android.hardware.hdmi.IHdmiDeviceEventListener;
import android.hardware.hdmi.IHdmiHotplugEventListener;
import android.hardware.hdmi.IHdmiInputChangeListener;
import android.hardware.hdmi.IHdmiSystemAudioModeChangeListener;
import android.media.AudioDevicePort;
import android.media.AudioFormat;
import android.media.AudioGain;
import android.media.AudioGainConfig;
import android.media.AudioManager;
import android.media.AudioPatch;
import android.media.AudioPort;
import android.media.AudioPortConfig;
import android.media.tv.ITvInputHardware;
import android.media.tv.ITvInputHardwareCallback;
import android.media.tv.TvContract;
import android.media.tv.TvInputHardwareInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvStreamConfig;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.KeyEvent;
import android.view.Surface;

import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * A helper class for TvInputManagerService to handle TV input hardware.
 *
 * This class does a basic connection management and forwarding calls to TvInputHal which eventually
 * calls to tv_input HAL module.
 *
 * @hide
 */
class TvInputHardwareManager implements TvInputHal.Callback {
    private static final String TAG = TvInputHardwareManager.class.getSimpleName();

    private final Context mContext;
    private final Listener mListener;
    private final TvInputHal mHal = new TvInputHal(this);
    private final SparseArray<Connection> mConnections = new SparseArray<>();
    private final List<TvInputHardwareInfo> mHardwareList = new ArrayList<>();
    private final List<HdmiDeviceInfo> mHdmiDeviceList = new LinkedList<>();
    /* A map from a device ID to the matching TV input ID. */
    private final SparseArray<String> mHardwareInputIdMap = new SparseArray<>();
    /* A map from a HDMI logical address to the matching TV input ID. */
    private final SparseArray<String> mHdmiInputIdMap = new SparseArray<>();
    private final Map<String, TvInputInfo> mInputMap = new ArrayMap<>();

    private final AudioManager mAudioManager;
    private IHdmiControlService mHdmiControlService;
    private final IHdmiHotplugEventListener mHdmiHotplugEventListener =
            new HdmiHotplugEventListener();
    private final IHdmiDeviceEventListener mHdmiDeviceEventListener = new HdmiDeviceEventListener();
    private final IHdmiSystemAudioModeChangeListener mHdmiSystemAudioModeChangeListener =
            new HdmiSystemAudioModeChangeListener();

    // TODO: Should handle STANDBY case.
    private final SparseBooleanArray mHdmiStateMap = new SparseBooleanArray();
    private final List<Message> mPendingHdmiDeviceEvents = new LinkedList<>();

    // Calls to mListener should happen here.
    private final Handler mHandler = new ListenerHandler();

    private final Object mLock = new Object();

    public TvInputHardwareManager(Context context, Listener listener) {
        mContext = context;
        mListener = listener;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mHal.init();
    }

    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mHdmiControlService = IHdmiControlService.Stub.asInterface(ServiceManager.getService(
                    Context.HDMI_CONTROL_SERVICE));
            if (mHdmiControlService != null) {
                try {
                    mHdmiControlService.addHotplugEventListener(mHdmiHotplugEventListener);
                    mHdmiControlService.addDeviceEventListener(mHdmiDeviceEventListener);
                    mHdmiControlService.addSystemAudioModeChangeListener(
                            mHdmiSystemAudioModeChangeListener);
                    mHdmiDeviceList.addAll(mHdmiControlService.getInputDevices());
                } catch (RemoteException e) {
                    Slog.w(TAG, "Error registering listeners to HdmiControlService:", e);
                }
            }
        }
    }

    @Override
    public void onDeviceAvailable(TvInputHardwareInfo info, TvStreamConfig[] configs) {
        synchronized (mLock) {
            Connection connection = new Connection(info);
            connection.updateConfigsLocked(configs);
            mConnections.put(info.getDeviceId(), connection);
            buildHardwareListLocked();
            mHandler.obtainMessage(
                    ListenerHandler.HARDWARE_DEVICE_ADDED, 0, 0, info).sendToTarget();
            if (info.getType() == TvInputHardwareInfo.TV_INPUT_TYPE_HDMI) {
                processPendingHdmiDeviceEventsLocked();
            }
        }
    }

    private void buildHardwareListLocked() {
        mHardwareList.clear();
        for (int i = 0; i < mConnections.size(); ++i) {
            mHardwareList.add(mConnections.valueAt(i).getHardwareInfoLocked());
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
            buildHardwareListLocked();
            TvInputHardwareInfo info = connection.getHardwareInfoLocked();
            if (info.getType() == TvInputHardwareInfo.TV_INPUT_TYPE_HDMI) {
                // Remove HDMI devices linked with this hardware.
                for (Iterator<HdmiDeviceInfo> it = mHdmiDeviceList.iterator(); it.hasNext();) {
                    HdmiDeviceInfo deviceInfo = it.next();
                    if (deviceInfo.getPortId() == info.getHdmiPortId()) {
                        mHandler.obtainMessage(ListenerHandler.HDMI_DEVICE_REMOVED, 0, 0,
                                deviceInfo).sendToTarget();
                        it.remove();
                    }
                }
            }
            mHandler.obtainMessage(
                    ListenerHandler.HARDWARE_DEVICE_REMOVED, 0, 0, info).sendToTarget();
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
                Slog.e(TAG, "error in onStreamConfigurationChanged", e);
            }
        }
    }

    @Override
    public void onFirstFrameCaptured(int deviceId, int streamId) {
        synchronized (mLock) {
            Connection connection = mConnections.get(deviceId);
            if (connection == null) {
                Slog.e(TAG, "FirstFrameCaptured: Cannot find a connection with "
                        + deviceId);
                return;
            }
            Runnable runnable = connection.getOnFirstFrameCapturedLocked();
            if (runnable != null) {
                runnable.run();
                connection.setOnFirstFrameCapturedLocked(null);
            }
        }
    }

    public List<TvInputHardwareInfo> getHardwareList() {
        synchronized (mLock) {
            return Collections.unmodifiableList(mHardwareList);
        }
    }

    public List<HdmiDeviceInfo> getHdmiDeviceList() {
        synchronized (mLock) {
            return Collections.unmodifiableList(mHdmiDeviceList);
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

    public void addHardwareTvInput(int deviceId, TvInputInfo info) {
        synchronized (mLock) {
            String oldInputId = mHardwareInputIdMap.get(deviceId);
            if (oldInputId != null) {
                Slog.w(TAG, "Trying to override previous registration: old = "
                        + mInputMap.get(oldInputId) + ":" + deviceId + ", new = "
                        + info + ":" + deviceId);
            }
            mHardwareInputIdMap.put(deviceId, info.getId());
            mInputMap.put(info.getId(), info);

            for (int i = 0; i < mHdmiStateMap.size(); ++i) {
                TvInputHardwareInfo hardwareInfo =
                        findHardwareInfoForHdmiPortLocked(mHdmiStateMap.keyAt(i));
                if (hardwareInfo == null) {
                    continue;
                }
                String inputId = mHardwareInputIdMap.get(hardwareInfo.getDeviceId());
                if (inputId != null && inputId.equals(info.getId())) {
                    mHandler.obtainMessage(ListenerHandler.STATE_CHANGED,
                            convertConnectedToState(mHdmiStateMap.valueAt(i)), 0,
                            inputId).sendToTarget();
                }
            }
        }
    }

    private static <T> int indexOfEqualValue(SparseArray<T> map, T value) {
        for (int i = 0; i < map.size(); ++i) {
            if (map.valueAt(i).equals(value)) {
                return i;
            }
        }
        return -1;
    }

    public void addHdmiTvInput(int logicalAddress, TvInputInfo info) {
        if (info.getType() != TvInputInfo.TYPE_HDMI) {
            throw new IllegalArgumentException("info (" + info + ") has non-HDMI type.");
        }
        synchronized (mLock) {
            String parentId = info.getParentId();
            int parentIndex = indexOfEqualValue(mHardwareInputIdMap, parentId);
            if (parentIndex < 0) {
                throw new IllegalArgumentException("info (" + info + ") has invalid parentId.");
            }
            String oldInputId = mHdmiInputIdMap.get(logicalAddress);
            if (oldInputId != null) {
                Slog.w(TAG, "Trying to override previous registration: old = "
                        + mInputMap.get(oldInputId) + ":" + logicalAddress + ", new = "
                        + info + ":" + logicalAddress);
            }
            mHdmiInputIdMap.put(logicalAddress, info.getId());
            mInputMap.put(info.getId(), info);
        }
    }

    public void removeTvInput(String inputId) {
        synchronized (mLock) {
            mInputMap.remove(inputId);
            int hardwareIndex = indexOfEqualValue(mHardwareInputIdMap, inputId);
            if (hardwareIndex >= 0) {
                mHardwareInputIdMap.removeAt(hardwareIndex);
            }
            int deviceIndex = indexOfEqualValue(mHdmiInputIdMap, inputId);
            if (deviceIndex >= 0) {
                mHdmiInputIdMap.removeAt(deviceIndex);
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

    private TvInputHardwareInfo findHardwareInfoForHdmiPortLocked(int port) {
        for (TvInputHardwareInfo hardwareInfo : mHardwareList) {
            if (hardwareInfo.getType() == TvInputHardwareInfo.TV_INPUT_TYPE_HDMI
                    && hardwareInfo.getHdmiPortId() == port) {
                return hardwareInfo;
            }
        }
        return null;
    }

    private int findDeviceIdForInputIdLocked(String inputId) {
        for (int i = 0; i < mConnections.size(); ++i) {
            Connection connection = mConnections.get(i);
            if (connection.getInfoLocked().getId().equals(inputId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Get the list of TvStreamConfig which is buffered mode.
     */
    public List<TvStreamConfig> getAvailableTvStreamConfigList(String inputId, int callingUid,
            int resolvedUserId) {
        List<TvStreamConfig> configsList = new ArrayList<TvStreamConfig>();
        synchronized (mLock) {
            int deviceId = findDeviceIdForInputIdLocked(inputId);
            if (deviceId < 0) {
                Slog.e(TAG, "Invalid inputId : " + inputId);
                return configsList;
            }
            Connection connection = mConnections.get(deviceId);
            for (TvStreamConfig config : connection.getConfigsLocked()) {
                if (config.getType() == TvStreamConfig.STREAM_TYPE_BUFFER_PRODUCER) {
                    configsList.add(config);
                }
            }
        }
        return configsList;
    }

    /**
     * Take a snapshot of the given TV input into the provided Surface.
     */
    public boolean captureFrame(String inputId, Surface surface, final TvStreamConfig config,
            int callingUid, int resolvedUserId) {
        synchronized (mLock) {
            int deviceId = findDeviceIdForInputIdLocked(inputId);
            if (deviceId < 0) {
                Slog.e(TAG, "Invalid inputId : " + inputId);
                return false;
            }
            Connection connection = mConnections.get(deviceId);
            final TvInputHardwareImpl hardwareImpl = connection.getHardwareImplLocked();
            if (hardwareImpl != null) {
                // Stop previous capture.
                Runnable runnable = connection.getOnFirstFrameCapturedLocked();
                if (runnable != null) {
                    runnable.run();
                    connection.setOnFirstFrameCapturedLocked(null);
                }

                boolean result = hardwareImpl.startCapture(surface, config);
                if (result) {
                    connection.setOnFirstFrameCapturedLocked(new Runnable() {
                        @Override
                        public void run() {
                            hardwareImpl.stopCapture(config);
                        }
                    });
                }
                return result;
            }
        }
        return false;
    }

    private void processPendingHdmiDeviceEventsLocked() {
        for (Iterator<Message> it = mPendingHdmiDeviceEvents.iterator(); it.hasNext(); ) {
            Message msg = it.next();
            HdmiDeviceInfo deviceInfo = (HdmiDeviceInfo) msg.obj;
            TvInputHardwareInfo hardwareInfo =
                    findHardwareInfoForHdmiPortLocked(deviceInfo.getPortId());
            if (hardwareInfo != null) {
                msg.sendToTarget();
                it.remove();
            }
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
        private Runnable mOnFirstFrameCaptured;

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
                    Slog.e(TAG, "error in Connection::resetLocked", e);
                }
                mHardware.release();
            }
            mHardware = hardware;
            mCallback = callback;
            mInfo = info;
            mCallingUid = callingUid;
            mResolvedUserId = resolvedUserId;
            mOnFirstFrameCaptured = null;

            if (mHardware != null && mCallback != null) {
                try {
                    mCallback.onStreamConfigChanged(getConfigsLocked());
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in Connection::resetLocked", e);
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

        public TvInputHardwareImpl getHardwareImplLocked() {
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

        public void setOnFirstFrameCapturedLocked(Runnable runnable) {
            mOnFirstFrameCaptured = runnable;
        }

        public Runnable getOnFirstFrameCapturedLocked() {
            return mOnFirstFrameCaptured;
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

        private final AudioManager.OnAudioPortUpdateListener mAudioListener =
                new AudioManager.OnAudioPortUpdateListener() {
            @Override
            public void onAudioPortListUpdate(AudioPort[] portList) {
                synchronized (mImplLock) {
                    updateAudioConfigLocked();
                }
            }

            @Override
            public void onAudioPatchListUpdate(AudioPatch[] patchList) {
                // No-op
            }

            @Override
            public void onServiceDied() {
                synchronized (mImplLock) {
                    mAudioSource = null;
                    mAudioSink = null;
                    mAudioPatch = null;
                }
            }
        };
        private int mOverrideAudioType = AudioManager.DEVICE_NONE;
        private String mOverrideAudioAddress = "";
        private AudioDevicePort mAudioSource;
        private AudioDevicePort mAudioSink;
        private AudioPatch mAudioPatch = null;
        private float mCommittedVolume = 0.0f;
        private float mVolume = 0.0f;

        private TvStreamConfig mActiveConfig = null;

        private int mDesiredSamplingRate = 0;
        private int mDesiredChannelMask = AudioFormat.CHANNEL_OUT_DEFAULT;
        private int mDesiredFormat = AudioFormat.ENCODING_DEFAULT;

        public TvInputHardwareImpl(TvInputHardwareInfo info) {
            mInfo = info;
            mAudioManager.registerAudioPortUpdateListener(mAudioListener);
            if (mInfo.getAudioType() != AudioManager.DEVICE_NONE) {
                mAudioSource = findAudioDevicePort(mInfo.getAudioType(), mInfo.getAudioAddress());
                mAudioSink = findAudioSinkFromAudioPolicy();
            }
        }

        private AudioDevicePort findAudioSinkFromAudioPolicy() {
            ArrayList<AudioPort> devicePorts = new ArrayList<AudioPort>();
            if (mAudioManager.listAudioDevicePorts(devicePorts) == AudioManager.SUCCESS) {
                int sinkDevice = mAudioManager.getDevicesForStream(AudioManager.STREAM_MUSIC);
                for (AudioPort port : devicePorts) {
                    AudioDevicePort devicePort = (AudioDevicePort) port;
                    if ((devicePort.type() & sinkDevice) != 0) {
                        return devicePort;
                    }
                }
            }
            return null;
        }

        private AudioDevicePort findAudioDevicePort(int type, String address) {
            if (type == AudioManager.DEVICE_NONE) {
                return null;
            }
            ArrayList<AudioPort> devicePorts = new ArrayList<AudioPort>();
            if (mAudioManager.listAudioDevicePorts(devicePorts) != AudioManager.SUCCESS) {
                return null;
            }
            for (AudioPort port : devicePorts) {
                AudioDevicePort devicePort = (AudioDevicePort) port;
                if (devicePort.type() == type && devicePort.address().equals(address)) {
                    return devicePort;
                }
            }
            return null;
        }

        public void release() {
            synchronized (mImplLock) {
                mAudioManager.unregisterAudioPortUpdateListener(mAudioListener);
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
                updateAudioConfigLocked();
                return result == TvInputHal.SUCCESS;
            }
        }

        /**
         * Update audio configuration (source, sink, patch) all up to current state.
         */
        private void updateAudioConfigLocked() {
            boolean sinkUpdated = updateAudioSinkLocked();
            boolean sourceUpdated = updateAudioSourceLocked();
            // We can't do updated = updateAudioSinkLocked() || updateAudioSourceLocked() here
            // because Java won't evaluate the latter if the former is true.

            if (mAudioSource == null || mAudioSink == null || mActiveConfig == null) {
                if (mAudioPatch != null) {
                    mAudioManager.releaseAudioPatch(mAudioPatch);
                    mAudioPatch = null;
                }
                return;
            }

            AudioGainConfig sourceGainConfig = null;
            if (mAudioSource.gains().length > 0 && mVolume != mCommittedVolume) {
                AudioGain sourceGain = null;
                for (AudioGain gain : mAudioSource.gains()) {
                    if ((gain.mode() & AudioGain.MODE_JOINT) != 0) {
                        sourceGain = gain;
                        break;
                    }
                }
                // NOTE: we only change the source gain in MODE_JOINT here.
                if (sourceGain != null) {
                    int steps = (sourceGain.maxValue() - sourceGain.minValue())
                            / sourceGain.stepValue();
                    int gainValue = sourceGain.minValue();
                    if (mVolume < 1.0f) {
                        gainValue += sourceGain.stepValue() * (int) (mVolume * steps + 0.5);
                    } else {
                        gainValue = sourceGain.maxValue();
                    }
                    int numChannels = 0;
                    for (int mask = sourceGain.channelMask(); mask > 0; mask >>= 1) {
                        numChannels += (mask & 1);
                    }
                    int[] gainValues = new int[numChannels];
                    Arrays.fill(gainValues, gainValue);
                    sourceGainConfig = sourceGain.buildConfig(AudioGain.MODE_JOINT,
                            sourceGain.channelMask(), gainValues, 0);
                } else {
                    Slog.w(TAG, "No audio source gain with MODE_JOINT support exists.");
                }
            }

            AudioPortConfig sourceConfig = mAudioSource.activeConfig();
            AudioPortConfig sinkConfig = mAudioSink.activeConfig();
            AudioPatch[] audioPatchArray = new AudioPatch[] { mAudioPatch };
            boolean shouldRecreateAudioPatch = sourceUpdated || sinkUpdated;
            if (sinkConfig == null
                    || (mDesiredSamplingRate != 0
                            && sinkConfig.samplingRate() != mDesiredSamplingRate)
                    || (mDesiredChannelMask != AudioFormat.CHANNEL_OUT_DEFAULT
                            && sinkConfig.channelMask() != mDesiredChannelMask)
                    || (mDesiredFormat != AudioFormat.ENCODING_DEFAULT
                            && sinkConfig.format() != mDesiredFormat)) {
                sinkConfig = mAudioSource.buildConfig(mDesiredSamplingRate, mDesiredChannelMask,
                        mDesiredFormat, null);
                shouldRecreateAudioPatch = true;
            }
            if (sourceConfig == null || sourceGainConfig != null) {
                sourceConfig = mAudioSource.buildConfig(sinkConfig.samplingRate(),
                        sinkConfig.channelMask(), sinkConfig.format(), sourceGainConfig);
                shouldRecreateAudioPatch = true;
            }
            if (shouldRecreateAudioPatch) {
                mCommittedVolume = mVolume;
                mAudioManager.createAudioPatch(
                        audioPatchArray,
                        new AudioPortConfig[] { sourceConfig },
                        new AudioPortConfig[] { sinkConfig });
                mAudioPatch = audioPatchArray[0];
            }
        }

        @Override
        public void setStreamVolume(float volume) throws RemoteException {
            synchronized (mImplLock) {
                if (mReleased) {
                    throw new IllegalStateException("Device already released.");
                }
                mVolume = volume;
                updateAudioConfigLocked();
            }
        }

        @Override
        public boolean dispatchKeyEventToHdmi(KeyEvent event) throws RemoteException {
            synchronized (mImplLock) {
                if (mReleased) {
                    throw new IllegalStateException("Device already released.");
                }
            }
            if (mInfo.getType() != TvInputHardwareInfo.TV_INPUT_TYPE_HDMI) {
                return false;
            }
            // TODO(hdmi): mHdmiClient.sendKeyEvent(event);
            return false;
        }

        private boolean startCapture(Surface surface, TvStreamConfig config) {
            synchronized (mImplLock) {
                if (mReleased) {
                    return false;
                }
                if (surface == null || config == null) {
                    return false;
                }
                if (config.getType() != TvStreamConfig.STREAM_TYPE_BUFFER_PRODUCER) {
                    return false;
                }

                int result = mHal.addStream(mInfo.getDeviceId(), surface, config);
                return result == TvInputHal.SUCCESS;
            }
        }

        private boolean stopCapture(TvStreamConfig config) {
            synchronized (mImplLock) {
                if (mReleased) {
                    return false;
                }
                if (config == null) {
                    return false;
                }

                int result = mHal.removeStream(mInfo.getDeviceId(), config);
                return result == TvInputHal.SUCCESS;
            }
        }

        private boolean updateAudioSourceLocked() {
            if (mInfo.getAudioType() == AudioManager.DEVICE_NONE) {
                return false;
            }
            AudioDevicePort previousSource = mAudioSource;
            mAudioSource = findAudioDevicePort(mInfo.getAudioType(), mInfo.getAudioAddress());
            return mAudioSource == null ? (previousSource != null)
                    : !mAudioSource.equals(previousSource);
        }

        private boolean updateAudioSinkLocked() {
            if (mInfo.getAudioType() == AudioManager.DEVICE_NONE) {
                return false;
            }
            AudioDevicePort previousSink = mAudioSink;
            if (mOverrideAudioType == AudioManager.DEVICE_NONE) {
                mAudioSink = findAudioSinkFromAudioPolicy();
            } else {
                AudioDevicePort audioSink =
                        findAudioDevicePort(mOverrideAudioType, mOverrideAudioAddress);
                if (audioSink != null) {
                    mAudioSink = audioSink;
                }
            }
            return mAudioSink == null ? (previousSink != null) : !mAudioSink.equals(previousSink);
        }

        private void handleAudioSinkUpdated() {
            synchronized (mImplLock) {
                updateAudioConfigLocked();
            }
        }

        @Override
        public void overrideAudioSink(int audioType, String audioAddress, int samplingRate,
                int channelMask, int format) {
            synchronized (mImplLock) {
                mOverrideAudioType = audioType;
                mOverrideAudioAddress = audioAddress;

                mDesiredSamplingRate = samplingRate;
                mDesiredChannelMask = channelMask;
                mDesiredFormat = format;

                updateAudioConfigLocked();
            }
        }
    }

    interface Listener {
        public void onStateChanged(String inputId, int state);
        public void onHardwareDeviceAdded(TvInputHardwareInfo info);
        public void onHardwareDeviceRemoved(TvInputHardwareInfo info);
        public void onHdmiDeviceAdded(HdmiDeviceInfo device);
        public void onHdmiDeviceRemoved(HdmiDeviceInfo device);
        public void onHdmiDeviceUpdated(HdmiDeviceInfo device);
    }

    private class ListenerHandler extends Handler {
        private static final int STATE_CHANGED = 1;
        private static final int HARDWARE_DEVICE_ADDED = 2;
        private static final int HARDWARE_DEVICE_REMOVED = 3;
        private static final int HDMI_DEVICE_ADDED = 4;
        private static final int HDMI_DEVICE_REMOVED = 5;
        private static final int HDMI_DEVICE_UPDATED = 6;

        @Override
        public final void handleMessage(Message msg) {
            switch (msg.what) {
                case STATE_CHANGED: {
                    String inputId = (String) msg.obj;
                    int state = msg.arg1;
                    mListener.onStateChanged(inputId, state);
                    break;
                }
                case HARDWARE_DEVICE_ADDED: {
                    TvInputHardwareInfo info = (TvInputHardwareInfo) msg.obj;
                    mListener.onHardwareDeviceAdded(info);
                    break;
                }
                case HARDWARE_DEVICE_REMOVED: {
                    TvInputHardwareInfo info = (TvInputHardwareInfo) msg.obj;
                    mListener.onHardwareDeviceRemoved(info);
                    break;
                }
                case HDMI_DEVICE_ADDED: {
                    HdmiDeviceInfo info = (HdmiDeviceInfo) msg.obj;
                    mListener.onHdmiDeviceAdded(info);
                    break;
                }
                case HDMI_DEVICE_REMOVED: {
                    HdmiDeviceInfo info = (HdmiDeviceInfo) msg.obj;
                    mListener.onHdmiDeviceRemoved(info);
                    break;
                }
                case HDMI_DEVICE_UPDATED: {
                    HdmiDeviceInfo info = (HdmiDeviceInfo) msg.obj;
                    mListener.onHdmiDeviceUpdated(info);
                }
                default: {
                    Slog.w(TAG, "Unhandled message: " + msg);
                    break;
                }
            }
        }
    }

    // Listener implementations for HdmiControlService

    private final class HdmiHotplugEventListener extends IHdmiHotplugEventListener.Stub {
        @Override
        public void onReceived(HdmiHotplugEvent event) {
            synchronized (mLock) {
                mHdmiStateMap.put(event.getPort(), event.isConnected());
                TvInputHardwareInfo hardwareInfo =
                        findHardwareInfoForHdmiPortLocked(event.getPort());
                if (hardwareInfo == null) {
                    return;
                }
                String inputId = mHardwareInputIdMap.get(hardwareInfo.getDeviceId());
                if (inputId == null) {
                    return;
                }
                mHandler.obtainMessage(ListenerHandler.STATE_CHANGED,
                        convertConnectedToState(event.isConnected()), 0, inputId).sendToTarget();
            }
        }
    }

    private final class HdmiDeviceEventListener extends IHdmiDeviceEventListener.Stub {
        @Override
        public void onStatusChanged(HdmiDeviceInfo deviceInfo, int status) {
            synchronized (mLock) {
                int messageType = 0;
                switch (status) {
                    case HdmiControlManager.DEVICE_EVENT_ADD_DEVICE: {
                        if (!mHdmiDeviceList.contains(deviceInfo)) {
                            mHdmiDeviceList.add(deviceInfo);
                        } else {
                            Slog.w(TAG, "The list already contains " + deviceInfo + "; ignoring.");
                            return;
                        }
                        messageType = ListenerHandler.HDMI_DEVICE_ADDED;
                        break;
                    }
                    case HdmiControlManager.DEVICE_EVENT_REMOVE_DEVICE: {
                        if (!mHdmiDeviceList.remove(deviceInfo)) {
                            Slog.w(TAG, "The list doesn't contain " + deviceInfo + "; ignoring.");
                            return;
                        }
                        messageType = ListenerHandler.HDMI_DEVICE_REMOVED;
                        break;
                    }
                    case HdmiControlManager.DEVICE_EVENT_UPDATE_DEVICE: {
                        if (!mHdmiDeviceList.remove(deviceInfo)) {
                            Slog.w(TAG, "The list doesn't contain " + deviceInfo + "; ignoring.");
                            return;
                        }
                        mHdmiDeviceList.add(deviceInfo);
                        messageType = ListenerHandler.HDMI_DEVICE_UPDATED;
                        break;
                    }
                }

                Message msg = mHandler.obtainMessage(messageType, 0, 0, deviceInfo);
                if (findHardwareInfoForHdmiPortLocked(deviceInfo.getPortId()) != null) {
                    msg.sendToTarget();
                } else {
                    mPendingHdmiDeviceEvents.add(msg);
                }
            }
        }
    }

    private final class HdmiSystemAudioModeChangeListener extends
        IHdmiSystemAudioModeChangeListener.Stub {
        @Override
        public void onStatusChanged(boolean enabled) throws RemoteException {
            synchronized (mLock) {
                for (int i = 0; i < mConnections.size(); ++i) {
                    TvInputHardwareImpl impl = mConnections.valueAt(i).getHardwareImplLocked();
                    impl.handleAudioSinkUpdated();
                }
            }
        }
    }
}
