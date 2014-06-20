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
    private final static String TAG = TvInputHal.class.getSimpleName();

    public final static int SUCCESS = 0;
    public final static int ERROR_NO_INIT = -1;
    public final static int ERROR_STALE_CONFIG = -2;
    public final static int ERROR_UNKNOWN = -3;

    public static final int TYPE_HDMI = 1;
    public static final int TYPE_BUILT_IN_TUNER = 2;
    public static final int TYPE_PASSTHROUGH = 3;

    public static final int EVENT_OPEN = 0;
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

    private static native int nativeSetSurface(long ptr, int deviceId, int streamId,
            Surface surface);
    private static native TvStreamConfig[] nativeGetStreamConfigs(long ptr, int deviceId,
            int generation);
    private static native void nativeClose(long ptr);

    private volatile long mPtr = 0;
    private final Callback mCallback;
    private final HandlerThread mThread = new HandlerThread("TV input HAL event thread");
    private final Handler mHandler;
    private int mStreamConfigGeneration = 0;
    private TvStreamConfig[] mStreamConfigs;

    public TvInputHal(Callback callback) {
        mCallback = callback;
        mThread.start();
        mHandler = new Handler(mThread.getLooper(), this);
    }

    public void init() {
        mPtr = nativeOpen();
        mHandler.sendEmptyMessage(EVENT_OPEN);
    }

    public int setSurface(int deviceId, Surface surface, TvStreamConfig streamConfig) {
        long ptr = mPtr;
        if (ptr == 0) {
            return ERROR_NO_INIT;
        }
        if (mStreamConfigGeneration != streamConfig.getGeneration()) {
            return ERROR_STALE_CONFIG;
        }
        if (nativeSetSurface(ptr, deviceId, streamConfig.getStreamId(), surface) == 0) {
            return SUCCESS;
        } else {
            return ERROR_UNKNOWN;
        }
    }

    public void close() {
        long ptr = mPtr;
        if (ptr != 0l) {
            nativeClose(ptr);
            mThread.quitSafely();
        }
    }

    private synchronized void retrieveStreamConfigs(long ptr, int deviceId) {
        ++mStreamConfigGeneration;
        mStreamConfigs = nativeGetStreamConfigs(ptr, deviceId, mStreamConfigGeneration);
    }

    // Called from native
    private void deviceAvailableFromNative(TvInputHardwareInfo info) {
        mHandler.sendMessage(
                mHandler.obtainMessage(EVENT_DEVICE_AVAILABLE, info));
    }

    private void deviceUnavailableFromNative(int deviceId) {
        mHandler.sendMessage(
                mHandler.obtainMessage(EVENT_DEVICE_UNAVAILABLE, deviceId, 0));
    }

    private void streamConfigsChangedFromNative(int deviceId) {
        mHandler.sendMessage(
                mHandler.obtainMessage(EVENT_STREAM_CONFIGURATION_CHANGED, deviceId, 0));
    }

    // Handler.Callback implementation

    private Queue<Message> mPendingMessageQueue = new LinkedList<Message>();

    @Override
    public boolean handleMessage(Message msg) {
        long ptr = mPtr;
        if (ptr == 0) {
            mPendingMessageQueue.add(msg);
            return true;
        }
        while (!mPendingMessageQueue.isEmpty()) {
            handleMessageInternal(ptr, mPendingMessageQueue.remove());
        }
        handleMessageInternal(ptr, msg);
        return true;
    }

    private void handleMessageInternal(long ptr, Message msg) {
        switch (msg.what) {
            case EVENT_OPEN:
                // No-op
                break;

            case EVENT_DEVICE_AVAILABLE: {
                TvInputHardwareInfo info = (TvInputHardwareInfo)msg.obj;
                retrieveStreamConfigs(ptr, info.getDeviceId());
                mCallback.onDeviceAvailable(info, mStreamConfigs);
                break;
            }

            case EVENT_DEVICE_UNAVAILABLE: {
                int deviceId = msg.arg1;
                mCallback.onDeviceUnavailable(deviceId);
                break;
            }

            case EVENT_STREAM_CONFIGURATION_CHANGED: {
                int deviceId = msg.arg1;
                retrieveStreamConfigs(ptr, deviceId);
                mCallback.onStreamConfigurationChanged(deviceId, mStreamConfigs);
                break;
            }

            default:
                Slog.e(TAG, "Unknown event: " + msg);
                break;
        }
    }
}
