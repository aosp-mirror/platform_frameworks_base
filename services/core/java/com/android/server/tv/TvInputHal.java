/*
 * Copyright 2014 The Android Open Source Project
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

import android.hardware.tv.input.V1_0.Constants;
import android.media.tv.TvInputHardwareInfo;
import android.media.tv.TvStreamConfig;
import android.os.Handler;
import android.os.Message;
import android.os.MessageQueue;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Surface;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Provides access to the low-level TV input hardware abstraction layer.
 */
final class TvInputHal implements Handler.Callback {
    private final static boolean DEBUG = false;
    private final static String TAG = TvInputHal.class.getSimpleName();

    public final static int SUCCESS = 0;
    public final static int ERROR_NO_INIT = -1;
    public final static int ERROR_STALE_CONFIG = -2;
    public final static int ERROR_UNKNOWN = -3;

    public static final int EVENT_DEVICE_AVAILABLE = Constants.EVENT_DEVICE_AVAILABLE;
    public static final int EVENT_DEVICE_UNAVAILABLE = Constants.EVENT_DEVICE_UNAVAILABLE;
    public static final int EVENT_STREAM_CONFIGURATION_CHANGED =
            Constants.EVENT_STREAM_CONFIGURATIONS_CHANGED;
    public static final int EVENT_FIRST_FRAME_CAPTURED = 4;

    public interface Callback {
        void onDeviceAvailable(TvInputHardwareInfo info, TvStreamConfig[] configs);
        void onDeviceUnavailable(int deviceId);
        void onStreamConfigurationChanged(int deviceId, TvStreamConfig[] configs);
        void onFirstFrameCaptured(int deviceId, int streamId);
    }

    private native long nativeOpen(MessageQueue queue);

    private static native int nativeAddOrUpdateStream(long ptr, int deviceId, int streamId,
            Surface surface);
    private static native int nativeRemoveStream(long ptr, int deviceId, int streamId);
    private static native TvStreamConfig[] nativeGetStreamConfigs(long ptr, int deviceId,
            int generation);
    private static native void nativeClose(long ptr);

    private final Object mLock = new Object();
    private long mPtr = 0;
    private final Callback mCallback;
    private final Handler mHandler;
    private final SparseIntArray mStreamConfigGenerations = new SparseIntArray();
    private final SparseArray<TvStreamConfig[]> mStreamConfigs = new SparseArray<>();

    public TvInputHal(Callback callback) {
        mCallback = callback;
        mHandler = new Handler(this);
    }

    public void init() {
        synchronized (mLock) {
            mPtr = nativeOpen(mHandler.getLooper().getQueue());
        }
    }

    public int addOrUpdateStream(int deviceId, Surface surface, TvStreamConfig streamConfig) {
        synchronized (mLock) {
            if (mPtr == 0) {
                return ERROR_NO_INIT;
            }
            int generation = mStreamConfigGenerations.get(deviceId, 0);
            if (generation != streamConfig.getGeneration()) {
                return ERROR_STALE_CONFIG;
            }
            if (nativeAddOrUpdateStream(mPtr, deviceId, streamConfig.getStreamId(), surface) == 0) {
                return SUCCESS;
            } else {
                return ERROR_UNKNOWN;
            }
        }
    }

    public int removeStream(int deviceId, TvStreamConfig streamConfig) {
        synchronized (mLock) {
            if (mPtr == 0) {
                return ERROR_NO_INIT;
            }
            int generation = mStreamConfigGenerations.get(deviceId, 0);
            if (generation != streamConfig.getGeneration()) {
                return ERROR_STALE_CONFIG;
            }
            if (nativeRemoveStream(mPtr, deviceId, streamConfig.getStreamId()) == 0) {
                return SUCCESS;
            } else {
                return ERROR_UNKNOWN;
            }
        }
    }

    public void close() {
        synchronized (mLock) {
            if (mPtr != 0L) {
                nativeClose(mPtr);
            }
        }
    }

    private void retrieveStreamConfigsLocked(int deviceId) {
        int generation = mStreamConfigGenerations.get(deviceId, 0) + 1;
        mStreamConfigs.put(deviceId, nativeGetStreamConfigs(mPtr, deviceId, generation));
        mStreamConfigGenerations.put(deviceId, generation);
    }

    // Called from native
    private void deviceAvailableFromNative(TvInputHardwareInfo info) {
        if (DEBUG) {
            Slog.d(TAG, "deviceAvailableFromNative: info = " + info);
        }
        mHandler.obtainMessage(EVENT_DEVICE_AVAILABLE, info).sendToTarget();
    }

    private void deviceUnavailableFromNative(int deviceId) {
        mHandler.obtainMessage(EVENT_DEVICE_UNAVAILABLE, deviceId, 0).sendToTarget();
    }

    private void streamConfigsChangedFromNative(int deviceId) {
        mHandler.obtainMessage(EVENT_STREAM_CONFIGURATION_CHANGED, deviceId, 0).sendToTarget();
    }

    private void firstFrameCapturedFromNative(int deviceId, int streamId) {
        mHandler.sendMessage(
                mHandler.obtainMessage(EVENT_STREAM_CONFIGURATION_CHANGED, deviceId, streamId));
    }

    // Handler.Callback implementation

    private final Queue<Message> mPendingMessageQueue = new LinkedList<>();

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_DEVICE_AVAILABLE: {
                TvStreamConfig[] configs;
                TvInputHardwareInfo info = (TvInputHardwareInfo)msg.obj;
                synchronized (mLock) {
                    retrieveStreamConfigsLocked(info.getDeviceId());
                    if (DEBUG) {
                        Slog.d(TAG, "EVENT_DEVICE_AVAILABLE: info = " + info);
                    }
                    configs = mStreamConfigs.get(info.getDeviceId());
                }
                mCallback.onDeviceAvailable(info, configs);
                break;
            }

            case EVENT_DEVICE_UNAVAILABLE: {
                int deviceId = msg.arg1;
                if (DEBUG) {
                    Slog.d(TAG, "EVENT_DEVICE_UNAVAILABLE: deviceId = " + deviceId);
                }
                mCallback.onDeviceUnavailable(deviceId);
                break;
            }

            case EVENT_STREAM_CONFIGURATION_CHANGED: {
                TvStreamConfig[] configs;
                int deviceId = msg.arg1;
                synchronized (mLock) {
                    if (DEBUG) {
                        Slog.d(TAG, "EVENT_STREAM_CONFIGURATION_CHANGED: deviceId = " + deviceId);
                    }
                    retrieveStreamConfigsLocked(deviceId);
                    configs = mStreamConfigs.get(deviceId);
                }
                mCallback.onStreamConfigurationChanged(deviceId, configs);
                break;
            }

            case EVENT_FIRST_FRAME_CAPTURED: {
                int deviceId = msg.arg1;
                int streamId = msg.arg2;
                mCallback.onFirstFrameCaptured(deviceId, streamId);
                break;
            }

            default:
                Slog.e(TAG, "Unknown event: " + msg);
                return false;
        }

        return true;
    }
}
