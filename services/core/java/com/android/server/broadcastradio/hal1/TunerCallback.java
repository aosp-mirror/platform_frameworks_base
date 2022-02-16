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
import android.annotation.Nullable;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioMetadata;
import android.hardware.radio.RadioTuner;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

class TunerCallback implements ITunerCallback {
    private static final String TAG = "BroadcastRadioService.TunerCallback";

    /**
     * This field is used by native code, do not access or modify.
     */
    private final long mNativeContext;

    @NonNull private final Tuner mTuner;
    @NonNull private final ITunerCallback mClientCallback;

    private final AtomicReference<ProgramList.Filter> mProgramListFilter = new AtomicReference<>();
    private boolean mInitialConfigurationDone = false;

    TunerCallback(@NonNull Tuner tuner, @NonNull ITunerCallback clientCallback, int halRev) {
        mTuner = tuner;
        mClientCallback = clientCallback;
        mNativeContext = nativeInit(tuner, halRev);
    }

    @Override
    protected void finalize() throws Throwable {
        nativeFinalize(mNativeContext);
        super.finalize();
    }

    private native long nativeInit(@NonNull Tuner tuner, int halRev);
    private native void nativeFinalize(long nativeContext);
    private native void nativeDetach(long nativeContext);

    public void detach() {
        nativeDetach(mNativeContext);
    }

    private interface RunnableThrowingRemoteException {
        void run() throws RemoteException;
    }

    private void dispatch(RunnableThrowingRemoteException func) {
        try {
            func.run();
        } catch (RemoteException e) {
            Slog.e(TAG, "client died", e);
        }
    }

    // called from native side
    private void handleHwFailure() {
        onError(RadioTuner.ERROR_HARDWARE_FAILURE);
        mTuner.close();
    }

    void startProgramListUpdates(@Nullable ProgramList.Filter filter) {
        if (filter == null) filter = new ProgramList.Filter();
        mProgramListFilter.set(filter);
        sendProgramListUpdate();
    }

    void stopProgramListUpdates() {
        mProgramListFilter.set(null);
    }

    boolean isInitialConfigurationDone() {
        return mInitialConfigurationDone;
    }

    @Override
    public void onError(int status) {
        dispatch(() -> mClientCallback.onError(status));
    }

    @Override
    public void onTuneFailed(int result, ProgramSelector selector) {
        Slog.e(TAG, "Not applicable for HAL 1.x");
    }

    @Override
    public void onConfigurationChanged(RadioManager.BandConfig config) {
        mInitialConfigurationDone = true;
        dispatch(() -> mClientCallback.onConfigurationChanged(config));
    }

    @Override
    public void onCurrentProgramInfoChanged(RadioManager.ProgramInfo info) {
        dispatch(() -> mClientCallback.onCurrentProgramInfoChanged(info));
    }

    @Override
    public void onTrafficAnnouncement(boolean active) {
        dispatch(() -> mClientCallback.onTrafficAnnouncement(active));
    }

    @Override
    public void onEmergencyAnnouncement(boolean active) {
        dispatch(() -> mClientCallback.onEmergencyAnnouncement(active));
    }

    @Override
    public void onAntennaState(boolean connected) {
        dispatch(() -> mClientCallback.onAntennaState(connected));
    }

    @Override
    public void onBackgroundScanAvailabilityChange(boolean isAvailable) {
        dispatch(() -> mClientCallback.onBackgroundScanAvailabilityChange(isAvailable));
    }

    @Override
    public void onBackgroundScanComplete() {
        dispatch(() -> mClientCallback.onBackgroundScanComplete());
    }

    @Override
    public void onProgramListChanged() {
        dispatch(() -> mClientCallback.onProgramListChanged());
        sendProgramListUpdate();
    }

    private void sendProgramListUpdate() {
        ProgramList.Filter filter = mProgramListFilter.get();
        if (filter == null) return;

        List<RadioManager.ProgramInfo> modified;
        try {
            modified = mTuner.getProgramList(filter.getVendorFilter());
        } catch (IllegalStateException ex) {
            Slog.d(TAG, "Program list not ready yet");
            return;
        }
        Set<RadioManager.ProgramInfo> modifiedSet = modified.stream().collect(Collectors.toSet());
        ProgramList.Chunk chunk = new ProgramList.Chunk(true, true, modifiedSet, null);
        dispatch(() -> mClientCallback.onProgramListUpdated(chunk));
    }

    @Override
    public void onProgramListUpdated(ProgramList.Chunk chunk) {
        dispatch(() -> mClientCallback.onProgramListUpdated(chunk));
    }

    @Override
    public void onParametersUpdated(Map parameters) {
        Slog.e(TAG, "Not applicable for HAL 1.x");
    }

    @Override
    public IBinder asBinder() {
        throw new RuntimeException("Not a binder");
    }
}
