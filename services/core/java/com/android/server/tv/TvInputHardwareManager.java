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
import static android.media.tv.TvInputManager.INPUT_STATE_CONNECTED_STANDBY;
import static android.media.tv.TvInputManager.INPUT_STATE_DISCONNECTED;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiHotplugEvent;
import android.hardware.hdmi.IHdmiControlService;
import android.hardware.hdmi.IHdmiDeviceEventListener;
import android.hardware.hdmi.IHdmiHotplugEventListener;
import android.hardware.hdmi.IHdmiSystemAudioModeChangeListener;
import android.media.AudioDevicePort;
import android.media.AudioFormat;
import android.media.AudioGain;
import android.media.AudioGainConfig;
import android.media.AudioManager;
import android.media.AudioPatch;
import android.media.AudioPort;
import android.media.AudioPortConfig;
import android.media.AudioSystem;
import android.media.tv.ITvInputHardware;
import android.media.tv.ITvInputHardwareCallback;
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
import android.view.Surface;

import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
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
    private final IHdmiHotplugEventListener mHdmiHotplugEventListener =
            new HdmiHotplugEventListener();
    private final IHdmiDeviceEventListener mHdmiDeviceEventListener = new HdmiDeviceEventListener();
    private final IHdmiSystemAudioModeChangeListener mHdmiSystemAudioModeChangeListener =
            new HdmiSystemAudioModeChangeListener();
    private final BroadcastReceiver mVolumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleVolumeChange(context, intent);
        }
    };
    private int mCurrentIndex = 0;
    private int mCurrentMaxIndex = 0;

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
            IHdmiControlService hdmiControlService = IHdmiControlService.Stub.asInterface(
                    ServiceManager.getService(Context.HDMI_CONTROL_SERVICE));
            if (hdmiControlService != null) {
                try {
                    hdmiControlService.addHotplugEventListener(mHdmiHotplugEventListener);
                    hdmiControlService.addDeviceEventListener(mHdmiDeviceEventListener);
                    hdmiControlService.addSystemAudioModeChangeListener(
                            mHdmiSystemAudioModeChangeListener);
                    mHdmiDeviceList.addAll(hdmiControlService.getInputDevices());
                } catch (RemoteException e) {
                    Slog.w(TAG, "Error registering listeners to HdmiControlService:", e);
                }
            } else {
                Slog.w(TAG, "HdmiControlService is not available");
            }
            final IntentFilter filter = new IntentFilter();
            filter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
            filter.addAction(AudioManager.STREAM_MUTE_CHANGED_ACTION);
            mContext.registerReceiver(mVolumeReceiver, filter);
            updateVolume();
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
            int previousConfigsLength = connection.getConfigsLengthLocked();
            connection.updateConfigsLocked(configs);
            String inputId = mHardwareInputIdMap.get(deviceId);
            if (inputId != null
                    && (previousConfigsLength == 0) != (connection.getConfigsLengthLocked() == 0)) {
                mHandler.obtainMessage(ListenerHandler.STATE_CHANGED,
                    connection.getInputStateLocked(), 0, inputId).sendToTarget();
            }
            ITvInputHardwareCallback callback = connection.getCallbackLocked();
            if (callback != null) {
                try {
                    callback.onStreamConfigChanged(configs);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onStreamConfigurationChanged", e);
                }
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
        return connectionCallingUid == null || connectionResolvedUserId == null
                || connectionCallingUid != callingUid || connectionResolvedUserId != resolvedUserId;
    }

    public void addHardwareInput(int deviceId, TvInputInfo info) {
        synchronized (mLock) {
            String oldInputId = mHardwareInputIdMap.get(deviceId);
            if (oldInputId != null) {
                Slog.w(TAG, "Trying to override previous registration: old = "
                        + mInputMap.get(oldInputId) + ":" + deviceId + ", new = "
                        + info + ":" + deviceId);
            }
            mHardwareInputIdMap.put(deviceId, info.getId());
            mInputMap.put(info.getId(), info);

            // Process pending state changes

            // For logical HDMI devices, they have information from HDMI CEC signals.
            for (int i = 0; i < mHdmiStateMap.size(); ++i) {
                TvInputHardwareInfo hardwareInfo =
                        findHardwareInfoForHdmiPortLocked(mHdmiStateMap.keyAt(i));
                if (hardwareInfo == null) {
                    continue;
                }
                String inputId = mHardwareInputIdMap.get(hardwareInfo.getDeviceId());
                if (inputId != null && inputId.equals(info.getId())) {
                    // No HDMI hotplug does not necessarily mean disconnected, as old devices may
                    // not report hotplug state correctly. Using INPUT_STATE_CONNECTED_STANDBY to
                    // denote unknown state.
                    int state = mHdmiStateMap.valueAt(i)
                            ? INPUT_STATE_CONNECTED
                            : INPUT_STATE_CONNECTED_STANDBY;
                    mHandler.obtainMessage(
                        ListenerHandler.STATE_CHANGED, state, 0, inputId).sendToTarget();
                    return;
                }
            }
            // For the rest of the devices, we can tell by the cable connection status.
            Connection connection = mConnections.get(deviceId);
            if (connection != null) {
                mHandler.obtainMessage(ListenerHandler.STATE_CHANGED,
                    connection.getInputStateLocked(), 0, info.getId()).sendToTarget();
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

    private static boolean intArrayContains(int[] array, int value) {
        for (int element : array) {
            if (element == value) return true;
        }
        return false;
    }

    public void addHdmiInput(int id, TvInputInfo info) {
        if (info.getType() != TvInputInfo.TYPE_HDMI) {
            throw new IllegalArgumentException("info (" + info + ") has non-HDMI type.");
        }
        synchronized (mLock) {
            String parentId = info.getParentId();
            int parentIndex = indexOfEqualValue(mHardwareInputIdMap, parentId);
            if (parentIndex < 0) {
                throw new IllegalArgumentException("info (" + info + ") has invalid parentId.");
            }
            String oldInputId = mHdmiInputIdMap.get(id);
            if (oldInputId != null) {
                Slog.w(TAG, "Trying to override previous registration: old = "
                        + mInputMap.get(oldInputId) + ":" + id + ", new = "
                        + info + ":" + id);
            }
            mHdmiInputIdMap.put(id, info.getId());
            mInputMap.put(info.getId(), info);
        }
    }

    public void removeHardwareInput(String inputId) {
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
        List<TvStreamConfig> configsList = new ArrayList<>();
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

    private void updateVolume() {
        mCurrentMaxIndex = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        mCurrentIndex = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    private void handleVolumeChange(Context context, Intent intent) {
        String action = intent.getAction();
        switch (action) {
            case AudioManager.VOLUME_CHANGED_ACTION: {
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                if (streamType != AudioManager.STREAM_MUSIC) {
                    return;
                }
                int index = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 0);
                if (index == mCurrentIndex) {
                    return;
                }
                mCurrentIndex = index;
                break;
            }
            case AudioManager.STREAM_MUTE_CHANGED_ACTION: {
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                if (streamType != AudioManager.STREAM_MUSIC) {
                    return;
                }
                // volume index will be updated at onMediaStreamVolumeChanged() through
                // updateVolume().
                break;
            }
            default:
                Slog.w(TAG, "Unrecognized intent: " + intent);
                return;
        }
        synchronized (mLock) {
            for (int i = 0; i < mConnections.size(); ++i) {
                TvInputHardwareImpl hardwareImpl = mConnections.valueAt(i).getHardwareImplLocked();
                if (hardwareImpl != null) {
                    hardwareImpl.onMediaStreamVolumeChanged();
                }
            }
        }
    }

    private float getMediaStreamVolume() {
        return (float) mCurrentIndex / (float) mCurrentMaxIndex;
    }

    public void dump(FileDescriptor fd, final PrintWriter writer, String[] args) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        synchronized (mLock) {
            pw.println("TvInputHardwareManager Info:");
            pw.increaseIndent();
            pw.println("mConnections: deviceId -> Connection");
            pw.increaseIndent();
            for (int i = 0; i < mConnections.size(); i++) {
                int deviceId = mConnections.keyAt(i);
                Connection mConnection = mConnections.valueAt(i);
                pw.println(deviceId + ": " + mConnection);

            }
            pw.decreaseIndent();

            pw.println("mHardwareList:");
            pw.increaseIndent();
            for (TvInputHardwareInfo tvInputHardwareInfo : mHardwareList) {
                pw.println(tvInputHardwareInfo);
            }
            pw.decreaseIndent();

            pw.println("mHdmiDeviceList:");
            pw.increaseIndent();
            for (HdmiDeviceInfo hdmiDeviceInfo : mHdmiDeviceList) {
                pw.println(hdmiDeviceInfo);
            }
            pw.decreaseIndent();

            pw.println("mHardwareInputIdMap: deviceId -> inputId");
            pw.increaseIndent();
            for (int i = 0 ; i < mHardwareInputIdMap.size(); i++) {
                int deviceId = mHardwareInputIdMap.keyAt(i);
                String inputId = mHardwareInputIdMap.valueAt(i);
                pw.println(deviceId + ": " + inputId);
            }
            pw.decreaseIndent();

            pw.println("mHdmiInputIdMap: id -> inputId");
            pw.increaseIndent();
            for (int i = 0; i < mHdmiInputIdMap.size(); i++) {
                int id = mHdmiInputIdMap.keyAt(i);
                String inputId = mHdmiInputIdMap.valueAt(i);
                pw.println(id + ": " + inputId);
            }
            pw.decreaseIndent();

            pw.println("mInputMap: inputId -> inputInfo");
            pw.increaseIndent();
            for(Map.Entry<String, TvInputInfo> entry : mInputMap.entrySet()) {
                pw.println(entry.getKey() + ": " + entry.getValue());
            }
            pw.decreaseIndent();
            pw.decreaseIndent();
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

        public String toString() {
            return "Connection{"
                    + " mHardwareInfo: " + mHardwareInfo
                    + ", mInfo: " + mInfo
                    + ", mCallback: " + mCallback
                    + ", mConfigs: " + Arrays.toString(mConfigs)
                    + ", mCallingUid: " + mCallingUid
                    + ", mResolvedUserId: " + mResolvedUserId
                    + " }";
        }

        private int getConfigsLengthLocked() {
            return mConfigs == null ? 0 : mConfigs.length;
        }

        private int getInputStateLocked() {
            int configsLength = getConfigsLengthLocked();
            if (configsLength > 0) {
                return INPUT_STATE_CONNECTED;
            }
            switch (mHardwareInfo.getCableConnectionStatus()) {
                case TvInputHardwareInfo.CABLE_CONNECTION_STATUS_CONNECTED:
                    return INPUT_STATE_CONNECTED;
                case TvInputHardwareInfo.CABLE_CONNECTION_STATUS_DISCONNECTED:
                    return INPUT_STATE_DISCONNECTED;
                case TvInputHardwareInfo.CABLE_CONNECTION_STATUS_UNKNOWN:
                default:
                    return INPUT_STATE_CONNECTED_STANDBY;
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
                    mAudioSink.clear();
                    if (mAudioPatch != null) {
                        mAudioManager.releaseAudioPatch(mAudioPatch);
                        mAudioPatch = null;
                    }
                }
            }
        };
        private int mOverrideAudioType = AudioManager.DEVICE_NONE;
        private String mOverrideAudioAddress = "";
        private AudioDevicePort mAudioSource;
        private List<AudioDevicePort> mAudioSink = new ArrayList<>();
        private AudioPatch mAudioPatch = null;
        // Set to an invalid value for a volume, so that current volume can be applied at the
        // first call to updateAudioConfigLocked().
        private float mCommittedVolume = -1f;
        private float mSourceVolume = 0.0f;

        private TvStreamConfig mActiveConfig = null;

        private int mDesiredSamplingRate = 0;
        private int mDesiredChannelMask = AudioFormat.CHANNEL_OUT_DEFAULT;
        private int mDesiredFormat = AudioFormat.ENCODING_DEFAULT;

        public TvInputHardwareImpl(TvInputHardwareInfo info) {
            mInfo = info;
            mAudioManager.registerAudioPortUpdateListener(mAudioListener);
            if (mInfo.getAudioType() != AudioManager.DEVICE_NONE) {
                mAudioSource = findAudioDevicePort(mInfo.getAudioType(), mInfo.getAudioAddress());
                findAudioSinkFromAudioPolicy(mAudioSink);
            }
        }

        private void findAudioSinkFromAudioPolicy(List<AudioDevicePort> sinks) {
            sinks.clear();
            ArrayList<AudioDevicePort> devicePorts = new ArrayList<>();
            if (mAudioManager.listAudioDevicePorts(devicePorts) != AudioManager.SUCCESS) {
                return;
            }
            int sinkDevice = mAudioManager.getDevicesForStream(AudioManager.STREAM_MUSIC);
            for (AudioDevicePort port : devicePorts) {
                if ((port.type() & sinkDevice) != 0 &&
                    (port.type() & AudioSystem.DEVICE_BIT_IN) == 0) {
                    sinks.add(port);
                }
            }
        }

        private AudioDevicePort findAudioDevicePort(int type, String address) {
            if (type == AudioManager.DEVICE_NONE) {
                return null;
            }
            ArrayList<AudioDevicePort> devicePorts = new ArrayList<>();
            if (mAudioManager.listAudioDevicePorts(devicePorts) != AudioManager.SUCCESS) {
                return null;
            }
            for (AudioDevicePort port : devicePorts) {
                if (port.type() == type && port.address().equals(address)) {
                    return port;
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

                int result = TvInputHal.SUCCESS;
                if (surface == null) {
                    // The value of config is ignored when surface == null.
                    if (mActiveConfig != null) {
                        result = mHal.removeStream(mInfo.getDeviceId(), mActiveConfig);
                        mActiveConfig = null;
                    } else {
                        // We already have no active stream.
                        return true;
                    }
                } else {
                    // It's impossible to set a non-null surface with a null config.
                    if (config == null) {
                        return false;
                    }
                    // Remove stream only if we have an existing active configuration.
                    if (mActiveConfig != null && !config.equals(mActiveConfig)) {
                        result = mHal.removeStream(mInfo.getDeviceId(), mActiveConfig);
                        if (result != TvInputHal.SUCCESS) {
                            mActiveConfig = null;
                        }
                    }
                    // Proceed only if all previous operations succeeded.
                    if (result == TvInputHal.SUCCESS) {
                        result = mHal.addOrUpdateStream(mInfo.getDeviceId(), surface, config);
                        if (result == TvInputHal.SUCCESS) {
                            mActiveConfig = config;
                        }
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

            if (mAudioSource == null || mAudioSink.isEmpty() || mActiveConfig == null) {
                if (mAudioPatch != null) {
                    mAudioManager.releaseAudioPatch(mAudioPatch);
                    mAudioPatch = null;
                }
                return;
            }

            updateVolume();
            float volume = mSourceVolume * getMediaStreamVolume();
            AudioGainConfig sourceGainConfig = null;
            if (mAudioSource.gains().length > 0 && volume != mCommittedVolume) {
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
                    if (volume < 1.0f) {
                        gainValue += sourceGain.stepValue() * (int) (volume * steps + 0.5);
                    } else {
                        gainValue = sourceGain.maxValue();
                    }
                    // size of gain values is 1 in MODE_JOINT
                    int[] gainValues = new int[] { gainValue };
                    sourceGainConfig = sourceGain.buildConfig(AudioGain.MODE_JOINT,
                            sourceGain.channelMask(), gainValues, 0);
                } else {
                    Slog.w(TAG, "No audio source gain with MODE_JOINT support exists.");
                }
            }

            AudioPortConfig sourceConfig = mAudioSource.activeConfig();
            List<AudioPortConfig> sinkConfigs = new ArrayList<>();
            AudioPatch[] audioPatchArray = new AudioPatch[] { mAudioPatch };
            boolean shouldRecreateAudioPatch = sourceUpdated || sinkUpdated;

            for (AudioDevicePort audioSink : mAudioSink) {
                AudioPortConfig sinkConfig = audioSink.activeConfig();
                int sinkSamplingRate = mDesiredSamplingRate;
                int sinkChannelMask = mDesiredChannelMask;
                int sinkFormat = mDesiredFormat;
                // If sinkConfig != null and values are set to default,
                // fill in the sinkConfig values.
                if (sinkConfig != null) {
                    if (sinkSamplingRate == 0) {
                        sinkSamplingRate = sinkConfig.samplingRate();
                    }
                    if (sinkChannelMask == AudioFormat.CHANNEL_OUT_DEFAULT) {
                        sinkChannelMask = sinkConfig.channelMask();
                    }
                    if (sinkFormat == AudioFormat.ENCODING_DEFAULT) {
                        sinkFormat = sinkConfig.format();
                    }
                }

                if (sinkConfig == null
                        || sinkConfig.samplingRate() != sinkSamplingRate
                        || sinkConfig.channelMask() != sinkChannelMask
                        || sinkConfig.format() != sinkFormat) {
                    // Check for compatibility and reset to default if necessary.
                    if (!intArrayContains(audioSink.samplingRates(), sinkSamplingRate)
                            && audioSink.samplingRates().length > 0) {
                        sinkSamplingRate = audioSink.samplingRates()[0];
                    }
                    if (!intArrayContains(audioSink.channelMasks(), sinkChannelMask)) {
                        sinkChannelMask = AudioFormat.CHANNEL_OUT_DEFAULT;
                    }
                    if (!intArrayContains(audioSink.formats(), sinkFormat)) {
                        sinkFormat = AudioFormat.ENCODING_DEFAULT;
                    }
                    sinkConfig = audioSink.buildConfig(sinkSamplingRate, sinkChannelMask,
                            sinkFormat, null);
                    shouldRecreateAudioPatch = true;
                }
                sinkConfigs.add(sinkConfig);
            }
            // sinkConfigs.size() == mAudioSink.size(), and mAudioSink is guaranteed to be
            // non-empty at the beginning of this method.
            AudioPortConfig sinkConfig = sinkConfigs.get(0);
            if (sourceConfig == null || sourceGainConfig != null) {
                int sourceSamplingRate = 0;
                if (intArrayContains(mAudioSource.samplingRates(), sinkConfig.samplingRate())) {
                    sourceSamplingRate = sinkConfig.samplingRate();
                } else if (mAudioSource.samplingRates().length > 0) {
                    // Use any sampling rate and hope audio patch can handle resampling...
                    sourceSamplingRate = mAudioSource.samplingRates()[0];
                }
                int sourceChannelMask = AudioFormat.CHANNEL_IN_DEFAULT;
                for (int inChannelMask : mAudioSource.channelMasks()) {
                    if (AudioFormat.channelCountFromOutChannelMask(sinkConfig.channelMask())
                            == AudioFormat.channelCountFromInChannelMask(inChannelMask)) {
                        sourceChannelMask = inChannelMask;
                        break;
                    }
                }
                int sourceFormat = AudioFormat.ENCODING_DEFAULT;
                if (intArrayContains(mAudioSource.formats(), sinkConfig.format())) {
                    sourceFormat = sinkConfig.format();
                }
                sourceConfig = mAudioSource.buildConfig(sourceSamplingRate, sourceChannelMask,
                        sourceFormat, sourceGainConfig);
                shouldRecreateAudioPatch = true;
            }
            if (shouldRecreateAudioPatch) {
                mCommittedVolume = volume;
                if (mAudioPatch != null) {
                    mAudioManager.releaseAudioPatch(mAudioPatch);
                }
                mAudioManager.createAudioPatch(
                        audioPatchArray,
                        new AudioPortConfig[] { sourceConfig },
                        sinkConfigs.toArray(new AudioPortConfig[sinkConfigs.size()]));
                mAudioPatch = audioPatchArray[0];
                if (sourceGainConfig != null) {
                    mAudioManager.setAudioPortGain(mAudioSource, sourceGainConfig);
                }
            }
        }

        @Override
        public void setStreamVolume(float volume) throws RemoteException {
            synchronized (mImplLock) {
                if (mReleased) {
                    throw new IllegalStateException("Device already released.");
                }
                mSourceVolume = volume;
                updateAudioConfigLocked();
            }
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

                int result = mHal.addOrUpdateStream(mInfo.getDeviceId(), surface, config);
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
            List<AudioDevicePort> previousSink = mAudioSink;
            mAudioSink = new ArrayList<>();
            if (mOverrideAudioType == AudioManager.DEVICE_NONE) {
                findAudioSinkFromAudioPolicy(mAudioSink);
            } else {
                AudioDevicePort audioSink =
                        findAudioDevicePort(mOverrideAudioType, mOverrideAudioAddress);
                if (audioSink != null) {
                    mAudioSink.add(audioSink);
                }
            }

            // Returns true if mAudioSink and previousSink differs.
            if (mAudioSink.size() != previousSink.size()) {
                return true;
            }
            previousSink.removeAll(mAudioSink);
            return !previousSink.isEmpty();
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

        public void onMediaStreamVolumeChanged() {
            synchronized (mImplLock) {
                updateAudioConfigLocked();
            }
        }
    }

    interface Listener {
        void onStateChanged(String inputId, int state);
        void onHardwareDeviceAdded(TvInputHardwareInfo info);
        void onHardwareDeviceRemoved(TvInputHardwareInfo info);
        void onHdmiDeviceAdded(HdmiDeviceInfo device);
        void onHdmiDeviceRemoved(HdmiDeviceInfo device);
        void onHdmiDeviceUpdated(String inputId, HdmiDeviceInfo device);
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
                    String inputId;
                    synchronized (mLock) {
                        inputId = mHdmiInputIdMap.get(info.getId());
                    }
                    if (inputId != null) {
                        mListener.onHdmiDeviceUpdated(inputId, info);
                    } else {
                        Slog.w(TAG, "Could not resolve input ID matching the device info; "
                                + "ignoring.");
                    }
                    break;
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
                // No HDMI hotplug does not necessarily mean disconnected, as old devices may
                // not report hotplug state correctly. Using INPUT_STATE_CONNECTED_STANDBY to
                // denote unknown state.
                int state = event.isConnected()
                        ? INPUT_STATE_CONNECTED
                        : INPUT_STATE_CONNECTED_STANDBY;
                mHandler.obtainMessage(
                    ListenerHandler.STATE_CHANGED, state, 0, inputId).sendToTarget();
            }
        }
    }

    private final class HdmiDeviceEventListener extends IHdmiDeviceEventListener.Stub {
        @Override
        public void onStatusChanged(HdmiDeviceInfo deviceInfo, int status) {
            if (!deviceInfo.isSourceType()) return;
            synchronized (mLock) {
                int messageType = 0;
                Object obj = null;
                switch (status) {
                    case HdmiControlManager.DEVICE_EVENT_ADD_DEVICE: {
                        if (findHdmiDeviceInfo(deviceInfo.getId()) == null) {
                            mHdmiDeviceList.add(deviceInfo);
                        } else {
                            Slog.w(TAG, "The list already contains " + deviceInfo + "; ignoring.");
                            return;
                        }
                        messageType = ListenerHandler.HDMI_DEVICE_ADDED;
                        obj = deviceInfo;
                        break;
                    }
                    case HdmiControlManager.DEVICE_EVENT_REMOVE_DEVICE: {
                        HdmiDeviceInfo originalDeviceInfo = findHdmiDeviceInfo(deviceInfo.getId());
                        if (!mHdmiDeviceList.remove(originalDeviceInfo)) {
                            Slog.w(TAG, "The list doesn't contain " + deviceInfo + "; ignoring.");
                            return;
                        }
                        messageType = ListenerHandler.HDMI_DEVICE_REMOVED;
                        obj = deviceInfo;
                        break;
                    }
                    case HdmiControlManager.DEVICE_EVENT_UPDATE_DEVICE: {
                        HdmiDeviceInfo originalDeviceInfo = findHdmiDeviceInfo(deviceInfo.getId());
                        if (!mHdmiDeviceList.remove(originalDeviceInfo)) {
                            Slog.w(TAG, "The list doesn't contain " + deviceInfo + "; ignoring.");
                            return;
                        }
                        mHdmiDeviceList.add(deviceInfo);
                        messageType = ListenerHandler.HDMI_DEVICE_UPDATED;
                        obj = deviceInfo;
                        break;
                    }
                }

                Message msg = mHandler.obtainMessage(messageType, 0, 0, obj);
                if (findHardwareInfoForHdmiPortLocked(deviceInfo.getPortId()) != null) {
                    msg.sendToTarget();
                } else {
                    mPendingHdmiDeviceEvents.add(msg);
                }
            }
        }

        private HdmiDeviceInfo findHdmiDeviceInfo(int id) {
            for (HdmiDeviceInfo info : mHdmiDeviceList) {
                if (info.getId() == id) {
                    return info;
                }
            }
            return null;
        }
    }

    private final class HdmiSystemAudioModeChangeListener extends
        IHdmiSystemAudioModeChangeListener.Stub {
        @Override
        public void onStatusChanged(boolean enabled) throws RemoteException {
            synchronized (mLock) {
                for (int i = 0; i < mConnections.size(); ++i) {
                    TvInputHardwareImpl impl = mConnections.valueAt(i).getHardwareImplLocked();
                    if (impl != null) {
                        impl.handleAudioSinkUpdated();
                    }
                }
            }
        }
    }
}
