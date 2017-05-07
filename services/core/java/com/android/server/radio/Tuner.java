/**
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.radio;

import android.annotation.NonNull;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.RadioManager;
import android.util.Slog;

class Tuner extends ITuner.Stub {
    // TODO(b/36863239): rename to RadioService.Tuner when native service goes away
    private static final String TAG = "RadioServiceJava.Tuner";

    /**
     * This field is used by native code, do not access or modify.
     */
    private final long mNativeContext;

    private final Object mLock = new Object();
    private boolean mIsMuted = false;
    private int mRegion;  // TODO(b/36863239): find better solution to manage regions
    private final boolean mWithAudio;

    Tuner(@NonNull ITunerCallback clientCallback, int halRev, int region, boolean withAudio) {
        mRegion = region;
        mWithAudio = withAudio;
        mNativeContext = nativeInit(clientCallback, halRev);
    }

    @Override
    protected void finalize() throws Throwable {
        nativeFinalize(mNativeContext);
        super.finalize();
    }

    private native long nativeInit(@NonNull ITunerCallback clientCallback, int halRev);
    private native void nativeFinalize(long nativeContext);
    private native void nativeClose(long nativeContext);

    private native void nativeSetConfiguration(long nativeContext,
            @NonNull RadioManager.BandConfig config);
    private native RadioManager.BandConfig nativeGetConfiguration(long nativeContext, int region);

    private native void nativeStep(long nativeContext, boolean directionDown, boolean skipSubChannel);
    private native void nativeScan(long nativeContext, boolean directionDown, boolean skipSubChannel);
    private native void nativeTune(long nativeContext, int channel, int subChannel);
    private native void nativeCancel(long nativeContext);

    @Override
    public void close() {
        synchronized (mLock) {
            nativeClose(mNativeContext);
        }
    }

    @Override
    public void setConfiguration(RadioManager.BandConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("The argument must not be a null pointer");
        }
        synchronized (mLock) {
            nativeSetConfiguration(mNativeContext, config);
            mRegion = config.getRegion();
        }
    }

    @Override
    public RadioManager.BandConfig getConfiguration() {
        synchronized (mLock) {
            return nativeGetConfiguration(mNativeContext, mRegion);
        }
    }

    @Override
    public int getProgramInformation(RadioManager.ProgramInfo[] infoOut) {
        if (infoOut == null || infoOut.length != 1) {
            throw new IllegalArgumentException("The argument must be an array of length 1");
        }
        Slog.d(TAG, "getProgramInformation()");
        return RadioManager.STATUS_INVALID_OPERATION;
    }

    @Override
    public void setMuted(boolean mute) {
        if (!mWithAudio) {
            throw new IllegalStateException("Can't operate on mute - no audio requested");
        }
        synchronized (mLock) {
            if (mIsMuted == mute) return;
            mIsMuted = mute;

            // TODO(b/34348946): notifify audio policy manager of media activity on radio audio
            // device. This task is pulled directly from previous implementation of native service.
        }
    }

    @Override
    public boolean isMuted() {
        if (!mWithAudio) {
            Slog.w(TAG, "Tuner did not request audio, pretending it was muted");
            return true;
        }
        synchronized (mLock) {
            return mIsMuted;
        }
    }

    @Override
    public void step(boolean directionDown, boolean skipSubChannel) {
        synchronized (mLock) {
            nativeStep(mNativeContext, directionDown, skipSubChannel);
        }
    }

    @Override
    public void scan(boolean directionDown, boolean skipSubChannel) {
        synchronized (mLock) {
            nativeScan(mNativeContext, directionDown, skipSubChannel);
        }
    }

    @Override
    public void tune(int channel, int subChannel) {
        synchronized (mLock) {
            nativeTune(mNativeContext, channel, subChannel);
        }
    }

    @Override
    public void cancel() {
        synchronized (mLock) {
            nativeCancel(mNativeContext);
        }
    }
}
