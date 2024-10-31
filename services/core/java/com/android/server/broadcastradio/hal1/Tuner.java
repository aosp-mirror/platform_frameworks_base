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

package com.android.server.broadcastradio.hal1;

import android.annotation.NonNull;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.server.broadcastradio.RadioServiceUserController;
import com.android.server.broadcastradio.RadioServiceUserControllerImpl;
import com.android.server.utils.Slogf;

import java.util.List;
import java.util.Map;

class Tuner extends ITuner.Stub {

    private static final String TAG = "BcRadio1Srv.Tuner";

    /**
     * This field is used by native code, do not access or modify.
     */
    private final long mNativeContext;

    private final Object mLock = new Object();
    private final TunerCallback mTunerCallback;
    private final ITunerCallback mClientCallback;
    private final IBinder.DeathRecipient mDeathRecipient;

    private boolean mIsClosed = false;
    private boolean mIsMuted = false;
    private int mRegion;
    private final boolean mWithAudio;
    private final RadioServiceUserController mUserController = new RadioServiceUserControllerImpl();

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

    private native void nativeStep(long nativeContext, boolean directionDown, boolean skipSubChannel);
    private native void nativeScan(long nativeContext, boolean directionDown, boolean skipSubChannel);
    private native void nativeTune(long nativeContext, @NonNull ProgramSelector selector);
    private native void nativeCancel(long nativeContext);

    private native void nativeCancelAnnouncement(long nativeContext);

    private native boolean nativeStartBackgroundScan(long nativeContext);
    private native List<RadioManager.ProgramInfo> nativeGetProgramList(long nativeContext,
            Map<String, String> vendorFilter);

    private native byte[] nativeGetImage(long nativeContext, int id);

    private native boolean nativeIsAnalogForced(long nativeContext);
    private native void nativeSetAnalogForced(long nativeContext, boolean isForced);

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

    private boolean checkConfiguredLocked() {
        if (mTunerCallback.isInitialConfigurationDone()) return true;
        Slogf.w(TAG, "Initial configuration is still pending, skipping the operation");
        return false;
    }

    @Override
    public void setConfiguration(RadioManager.BandConfig config) {
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot set configuration for HAL 1.x client from non-current user");
            return;
        }
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
            Slogf.w(TAG, "Mute via RadioService is not implemented - please handle it via app");
        }
    }

    @Override
    public boolean isMuted() {
        if (!mWithAudio) {
            Slogf.w(TAG, "Tuner did not request audio, pretending it was muted");
            return true;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            return mIsMuted;
        }
    }

    @Override
    public void step(boolean directionDown, boolean skipSubChannel) {
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot step on HAL 1.x client from non-current user");
            return;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            if (!checkConfiguredLocked()) return;
            nativeStep(mNativeContext, directionDown, skipSubChannel);
        }
    }

    @Override
    public void seek(boolean directionDown, boolean skipSubChannel) {
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot seek on HAL 1.x client from non-current user");
            return;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            if (!checkConfiguredLocked()) return;
            nativeScan(mNativeContext, directionDown, skipSubChannel);
        }
    }

    @Override
    public void tune(ProgramSelector selector) {
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot tune on HAL 1.x client from non-current user");
            return;
        }
        if (selector == null) {
            throw new IllegalArgumentException("The argument must not be a null pointer");
        }
        Slogf.i(TAG, "Tuning to " + selector);
        synchronized (mLock) {
            checkNotClosedLocked();
            if (!checkConfiguredLocked()) return;
            nativeTune(mNativeContext, selector);
        }
    }

    @Override
    public void cancel() {
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot cancel on HAL 1.x client from non-current user");
            return;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            nativeCancel(mNativeContext);
        }
    }

    @Override
    public void cancelAnnouncement() {
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot cancel announcement on HAL 1.x client from non-current user");
            return;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            nativeCancelAnnouncement(mNativeContext);
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
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG,
                    "Cannot start background scan on HAL 1.x client from non-current user");
            return false;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            return nativeStartBackgroundScan(mNativeContext);
        }
    }

    List<RadioManager.ProgramInfo> getProgramList(Map vendorFilter) {
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
    public void startProgramListUpdates(ProgramList.Filter filter) {
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG,
                    "Cannot start program list updates on HAL 1.x client from non-current user");
            return;
        }
        mTunerCallback.startProgramListUpdates(filter);
    }

    @Override
    public void stopProgramListUpdates() {
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG,
                    "Cannot stop program list updates on HAL 1.x client from non-current user");
            return;
        }
        mTunerCallback.stopProgramListUpdates();
    }

    @Override
    public boolean isConfigFlagSupported(int flag) {
        return flag == RadioManager.CONFIG_FORCE_ANALOG;
    }

    @Override
    public boolean isConfigFlagSet(int flag) {
        if (flag == RadioManager.CONFIG_FORCE_ANALOG) {
            synchronized (mLock) {
                checkNotClosedLocked();
                return nativeIsAnalogForced(mNativeContext);
            }
        }
        throw new UnsupportedOperationException("Not supported by HAL 1.x");
    }

    @Override
    public void setConfigFlag(int flag, boolean value) {
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot set config flag for HAL 1.x client from non-current user");
            return;
        }
        if (flag == RadioManager.CONFIG_FORCE_ANALOG) {
            synchronized (mLock) {
                checkNotClosedLocked();
                nativeSetAnalogForced(mNativeContext, value);
                return;
            }
        }
        throw new UnsupportedOperationException("Not supported by HAL 1.x");
    }

    @Override
    public Map<String, String> setParameters(Map<String, String> parameters) {
        throw new UnsupportedOperationException("Not supported by HAL 1.x");
    }

    @Override
    public Map<String, String> getParameters(List<String> keys) {
        throw new UnsupportedOperationException("Not supported by HAL 1.x");
    }
}
