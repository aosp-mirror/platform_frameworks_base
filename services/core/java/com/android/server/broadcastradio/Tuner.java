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

package com.android.server.broadcastradio;

import android.annotation.NonNull;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import java.util.List;
import java.util.Map;

class Tuner extends ITuner.Stub {
    private static final String TAG = "BroadcastRadioService.Tuner";

    /**
     * This field is used by native code, do not access or modify.
     */
    private final long mNativeContext;

    private final Object mLock = new Object();
    @NonNull private final TunerCallback mTunerCallback;
    @NonNull private final ITunerCallback mClientCallback;
    @NonNull private final IBinder.DeathRecipient mDeathRecipient;

    private boolean mIsClosed = false;
    private boolean mIsMuted = false;
    private int mRegion;  // TODO(b/62710330): find better solution to handle regions
    private final boolean mWithAudio;

    Tuner(@NonNull ITunerCallback clientCallback, int halRev,
            int region, boolean withAudio, int band) {
        mClientCallback = clientCallback;
        mTunerCallback = new TunerCallback(this, clientCallback, halRev);
        mRegion = region;
        mWithAudio = withAudio;
        mNativeContext = nativeInit(halRev, withAudio, band);
        mDeathRecipient = this::close;
        try {
            mClientCallback.asBinder().linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException ex) {
            close();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        nativeFinalize(mNativeContext);
        super.finalize();
    }

    private native long nativeInit(int halRev, boolean withAudio, int band);
    private native void nativeFinalize(long nativeContext);
    private native void nativeClose(long nativeContext);

    private native void nativeSetConfiguration(long nativeContext,
            @NonNull RadioManager.BandConfig config);
    private native RadioManager.BandConfig nativeGetConfiguration(long nativeContext, int region);

    private native void nativeSetMuted(long nativeContext, boolean mute);

    private native void nativeStep(long nativeContext, boolean directionDown, boolean skipSubChannel);
    private native void nativeScan(long nativeContext, boolean directionDown, boolean skipSubChannel);
    private native void nativeTune(long nativeContext, @NonNull ProgramSelector selector);
    private native void nativeCancel(long nativeContext);

    private native void nativeCancelAnnouncement(long nativeContext);

    private native RadioManager.ProgramInfo nativeGetProgramInformation(long nativeContext);
    private native boolean nativeStartBackgroundScan(long nativeContext);
    private native List<RadioManager.ProgramInfo> nativeGetProgramList(long nativeContext,
            Map<String, String> vendorFilter);

    private native byte[] nativeGetImage(long nativeContext, int id);

    private native boolean nativeIsAnalogForced(long nativeContext);
    private native void nativeSetAnalogForced(long nativeContext, boolean isForced);

    private native boolean nativeIsAntennaConnected(long nativeContext);

    @Override
    public void close() {
        synchronized (mLock) {
            if (mIsClosed) return;
            mIsClosed = true;
            mTunerCallback.detach();
            mClientCallback.asBinder().unlinkToDeath(mDeathRecipient, 0);
            nativeClose(mNativeContext);
        }
    }

    @Override
    public boolean isClosed() {
        return mIsClosed;
    }

    private void checkNotClosedLocked() {
        if (mIsClosed) {
            throw new IllegalStateException("Tuner is closed, no further operations are allowed");
        }
    }

    @Override
    public void setConfiguration(RadioManager.BandConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("The argument must not be a null pointer");
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            nativeSetConfiguration(mNativeContext, config);
            mRegion = config.getRegion();
        }
    }

    @Override
    public RadioManager.BandConfig getConfiguration() {
        synchronized (mLock) {
            checkNotClosedLocked();
            return nativeGetConfiguration(mNativeContext, mRegion);
        }
    }

    @Override
    public void setMuted(boolean mute) {
        if (!mWithAudio) {
            throw new IllegalStateException("Can't operate on mute - no audio requested");
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            if (mIsMuted == mute) return;
            mIsMuted = mute;

            nativeSetMuted(mNativeContext, mute);
        }
    }

    @Override
    public boolean isMuted() {
        if (!mWithAudio) {
            Slog.w(TAG, "Tuner did not request audio, pretending it was muted");
            return true;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            return mIsMuted;
        }
    }

    @Override
    public void step(boolean directionDown, boolean skipSubChannel) {
        synchronized (mLock) {
            checkNotClosedLocked();
            nativeStep(mNativeContext, directionDown, skipSubChannel);
        }
    }

    @Override
    public void scan(boolean directionDown, boolean skipSubChannel) {
        synchronized (mLock) {
            checkNotClosedLocked();
            nativeScan(mNativeContext, directionDown, skipSubChannel);
        }
    }

    @Override
    public void tune(ProgramSelector selector) {
        if (selector == null) {
            throw new IllegalArgumentException("The argument must not be a null pointer");
        }
        Slog.i(TAG, "Tuning to " + selector);
        synchronized (mLock) {
            checkNotClosedLocked();
            nativeTune(mNativeContext, selector);
        }
    }

    @Override
    public void cancel() {
        synchronized (mLock) {
            checkNotClosedLocked();
            nativeCancel(mNativeContext);
        }
    }

    @Override
    public void cancelAnnouncement() {
        synchronized (mLock) {
            checkNotClosedLocked();
            nativeCancelAnnouncement(mNativeContext);
        }
    }

    @Override
    public RadioManager.ProgramInfo getProgramInformation() {
        synchronized (mLock) {
            checkNotClosedLocked();
            return nativeGetProgramInformation(mNativeContext);
        }
    }

    @Override
    public Bitmap getImage(int id) {
        if (id == 0) {
            throw new IllegalArgumentException("Image ID is missing");
        }

        byte[] rawImage;
        synchronized (mLock) {
            rawImage = nativeGetImage(mNativeContext, id);
        }
        if (rawImage == null || rawImage.length == 0) {
            return null;
        }

        return BitmapFactory.decodeByteArray(rawImage, 0, rawImage.length);
    }

    @Override
    public boolean startBackgroundScan() {
        synchronized (mLock) {
            checkNotClosedLocked();
            return nativeStartBackgroundScan(mNativeContext);
        }
    }

    @Override
    public List<RadioManager.ProgramInfo> getProgramList(Map vendorFilter) {
        Map<String, String> sFilter = vendorFilter;
        synchronized (mLock) {
            checkNotClosedLocked();
            List<RadioManager.ProgramInfo> list = nativeGetProgramList(mNativeContext, sFilter);
            if (list == null) {
                throw new IllegalStateException("Program list is not ready");
            }
            return list;
        }
    }

    @Override
    public boolean isAnalogForced() {
        synchronized (mLock) {
            checkNotClosedLocked();
            return nativeIsAnalogForced(mNativeContext);
        }
    }

    @Override
    public void setAnalogForced(boolean isForced) {
        synchronized (mLock) {
            checkNotClosedLocked();
            nativeSetAnalogForced(mNativeContext, isForced);
        }
    }

    @Override
    public boolean isAntennaConnected() {
        synchronized (mLock) {
            checkNotClosedLocked();
            return nativeIsAntennaConnected(mNativeContext);
        }
    }
}
