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

import android.os.Handler;
import android.os.HandlerThread;
import android.tv.TvInputHardwareInfo;
import android.tv.TvStreamConfig;
import android.view.Surface;

/**
 * Provides access to the low-level TV input hardware abstraction layer.
 */
final class TvInputHal {
    public final static int SUCCESS = 0;
    public final static int ERROR_NO_INIT = -1;
    public final static int ERROR_STALE_CONFIG = -2;
    public final static int ERROR_UNKNOWN = -3;

    public static final int TYPE_HDMI = 1;
    public static final int TYPE_BUILT_IN_TUNER = 2;
    public static final int TYPE_PASSTHROUGH = 3;

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

    private long mPtr = 0l;
    private final Callback mCallback;
    private final HandlerThread mThread = new HandlerThread("TV input HAL event thread");
    private final Handler mHandler;
    private int mStreamConfigGeneration = 0;
    private TvStreamConfig[] mStreamConfigs;

    public TvInputHal(Callback callback) {
        mCallback = callback;
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
    }

    public void init() {
        mPtr = nativeOpen();
    }

    public int setSurface(int deviceId, Surface surface, TvStreamConfig streamConfig) {
        if (mPtr == 0) {
            return ERROR_NO_INIT;
        }
        if (mStreamConfigGeneration != streamConfig.getGeneration()) {
            return ERROR_STALE_CONFIG;
        }
        if (nativeSetSurface(mPtr, deviceId, streamConfig.getStreamId(), surface) == 0) {
            return SUCCESS;
        } else {
            return ERROR_UNKNOWN;
        }
    }

    public void close() {
        if (mPtr != 0l) {
            nativeClose(mPtr);
            mThread.quitSafely();
        }
    }

    private synchronized void retrieveStreamConfigs(int deviceId) {
        ++mStreamConfigGeneration;
        mStreamConfigs = nativeGetStreamConfigs(mPtr, deviceId, mStreamConfigGeneration);
    }

    // Called from native
    private void deviceAvailableFromNative(int deviceId, int type) {
        final TvInputHardwareInfo info = new TvInputHardwareInfo(deviceId, type);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                retrieveStreamConfigs(info.getDeviceId());
                mCallback.onDeviceAvailable(info, mStreamConfigs);
            }
        });
    }

    private void deviceUnavailableFromNative(int deviceId) {
        final int id = deviceId;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mCallback.onDeviceUnavailable(id);
            }
        });
    }

    private void streamConfigsChangedFromNative(int deviceId) {
        final int id = deviceId;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                retrieveStreamConfigs(id);
                mCallback.onStreamConfigurationChanged(id, mStreamConfigs);
            }
        });
    }
}
