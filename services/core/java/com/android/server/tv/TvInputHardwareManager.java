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

import android.content.Context;
import android.media.tv.ITvInputHardware;
import android.media.tv.ITvInputHardwareCallback;
import android.media.tv.TvInputHardwareInfo;
import android.media.tv.TvStreamConfig;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.Surface;

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
class TvInputHardwareManager implements TvInputHal.Callback {
    private static final String TAG = TvInputHardwareManager.class.getSimpleName();
    private final TvInputHal mHal = new TvInputHal(this);
    private final SparseArray<Connection> mConnections = new SparseArray<Connection>();
    private final List<TvInputHardwareInfo> mInfoList = new ArrayList<TvInputHardwareInfo>();
    private final Context mContext;
    private final Set<Integer> mActiveHdmiSources = new HashSet<Integer>();

    private final Object mLock = new Object();

    public TvInputHardwareManager(Context context) {
        mContext = context;
        // TODO(hdmi): mHdmiManager = mContext.getSystemService(...);
        // TODO(hdmi): mHdmiClient = mHdmiManager.getTvClient();
        mHal.init();
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
            mInfoList.add(mConnections.valueAt(i).getInfoLocked());
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
            connection.resetLocked(null, null, null, null);
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

    /**
     * Create a TvInputHardware object with a specific deviceId. One service at a time can access
     * the object, and if more than one process attempts to create hardware with the same deviceId,
     * the latest service will get the object and all the other hardware are released. The
     * release is notified via ITvInputHardwareCallback.onReleased().
     */
    public ITvInputHardware acquireHardware(int deviceId, ITvInputHardwareCallback callback,
            int callingUid, int resolvedUserId) {
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
                TvInputHardwareImpl hardware = new TvInputHardwareImpl(connection.getInfoLocked());
                try {
                    callback.asBinder().linkToDeath(connection, 0);
                } catch (RemoteException e) {
                    hardware.release();
                    return null;
                }
                connection.resetLocked(hardware, callback, callingUid, resolvedUserId);
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
            connection.resetLocked(null, null, null, null);
        }
    }

    private class Connection implements IBinder.DeathRecipient {
        private final TvInputHardwareInfo mInfo;
        private TvInputHardwareImpl mHardware = null;
        private ITvInputHardwareCallback mCallback;
        private TvStreamConfig[] mConfigs = null;
        private Integer mCallingUid = null;
        private Integer mResolvedUserId = null;

        public Connection(TvInputHardwareInfo info) {
            mInfo = info;
        }

        // *Locked methods assume TvInputHardwareManager.mLock is held.

        public void resetLocked(TvInputHardwareImpl hardware,
                ITvInputHardwareCallback callback, Integer callingUid, Integer resolvedUserId) {
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

        public TvInputHardwareInfo getInfoLocked() {
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
                resetLocked(null, null, null, null);
            }
        }
    }

    private class TvInputHardwareImpl extends ITvInputHardware.Stub {
        private final TvInputHardwareInfo mInfo;
        private boolean mReleased = false;
        private final Object mImplLock = new Object();

        public TvInputHardwareImpl(TvInputHardwareInfo info) {
            mInfo = info;
        }

        public void release() {
            synchronized (mImplLock) {
                mReleased = true;
            }
        }

        @Override
        public boolean setSurface(Surface surface, TvStreamConfig config)
                throws RemoteException {
            synchronized (mImplLock) {
                if (mReleased) {
                    throw new IllegalStateException("Device already released.");
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
                return mHal.setSurface(mInfo.getDeviceId(), surface, config) == TvInputHal.SUCCESS;
            }
        }

        @Override
        public void setVolume(float volume) throws RemoteException {
            synchronized (mImplLock) {
                if (mReleased) {
                    throw new IllegalStateException("Device already released.");
                }
            }
            // TODO
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
}
