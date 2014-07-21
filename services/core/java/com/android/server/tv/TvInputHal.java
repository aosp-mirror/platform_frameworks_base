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

import android.media.tv.TvInputHardwareInfo;
import android.media.tv.TvStreamConfig;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.view.Surface;
import android.util.Slog;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Provides access to the low-level TV input hardware abstraction layer.
 */
final class TvInputHal implements Handler.Callback {
    // STOPSHIP: Turn debugging off
    private final static boolean DEBUG = true;
    private final static String TAG = TvInputHal.class.getSimpleName();

    public final static int SUCCESS = 0;
    public final static int ERROR_NO_INIT = -1;
    public final static int ERROR_STALE_CONFIG = -2;
    public final static int ERROR_UNKNOWN = -3;

    // Below should be in sync with hardware/libhardware/include/hardware/tv_input.h
    public static final int EVENT_DEVICE_AVAILABLE = 1;
    public static final int EVENT_DEVICE_UNAVAILABLE = 2;
    public static final int EVENT_STREAM_CONFIGURATION_CHANGED = 3;

    public interface Callback {
        public void onDeviceAvailable(
                TvInputHardwareInfo info, TvStreamConfig[] configs);
        public void onDeviceUnavailable(int deviceId);
        public void onStreamConfigurationChanged(int deviceId, TvStreamConfig[] configs);
    }

    private native long nativeOpen();

    private static native int nativeAddStream(long ptr, int deviceId, int streamId,
            Surface surface);
    private static native int nativeRemoveStream(long ptr, int deviceId, int streamId);
    private static native TvStreamConfig[] nativeGetStreamConfigs(long ptr, int deviceId,
            int generation);
    private static native void nativeClose(long ptr);

    private long mPtr = 0;
    private final Callback mCallback;
    private final Handler mHandler;
    private int mStreamConfigGeneration = 0;
    private TvStreamConfig[] mStreamConfigs;

    public TvInputHal(Callback callback) {
        mCallback = callback;
        mHandler = new Handler(this);
    }

    public synchronized void init() {
        mPtr = nativeOpen();
    }

    public synchronized int addStream(int deviceId, Surface surface, TvStreamConfig streamConfig) {
        if (mPtr == 0) {
            return ERROR_NO_INIT;
        }
        if (mStreamConfigGeneration != streamConfig.getGeneration()) {
            return ERROR_STALE_CONFIG;
        }
        if (nativeAddStream(mPtr, deviceId, streamConfig.getStreamId(), surface) == 0) {
            return SUCCESS;
        } else {
            return ERROR_UNKNOWN;
        }
    }

    public synchronized int removeStream(int deviceId, TvStreamConfig streamConfig) {
        if (mPtr == 0) {
            return ERROR_NO_INIT;
        }
        if (mStreamConfigGeneration != streamConfig.getGeneration()) {
            return ERROR_STALE_CONFIG;
        }
        if (nativeRemoveStream(mPtr, deviceId, streamConfig.getStreamId()) == 0) {
            return SUCCESS;
        } else {
            return ERROR_UNKNOWN;
        }
    }

    public synchronized void close() {
        if (mPtr != 0l) {
            nativeClose(mPtr);
        }
    }

    private synchronized void retrieveStreamConfigs(int deviceId) {
        ++mStreamConfigGeneration;
        mStreamConfigs = nativeGetStreamConfigs(mPtr, deviceId, mStreamConfigGeneration);
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

    // Handler.Callback implementation

    private Queue<Message> mPendingMessageQueue = new LinkedList<Message>();

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_DEVICE_AVAILABLE: {
                TvInputHardwareInfo info = (TvInputHardwareInfo)msg.obj;
                retrieveStreamConfigs(info.getDeviceId());
                if (DEBUG) {
                    Slog.d(TAG, "EVENT_DEVICE_AVAILABLE: info = " + info);
                }
                mCallback.onDeviceAvailable(info, mStreamConfigs);
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
                int deviceId = msg.arg1;
                if (DEBUG) {
                    Slog.d(TAG, "EVENT_STREAM_CONFIGURATION_CHANGED: deviceId = " + deviceId);
                }
                retrieveStreamConfigs(deviceId);
                mCallback.onStreamConfigurationChanged(deviceId, mStreamConfigs);
                break;
            }

            default:
                Slog.e(TAG, "Unknown event: " + msg);
                return false;
        }

        return true;
    }
}
