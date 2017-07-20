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

package android.hardware.radio;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

/**
 * Implements the ITunerCallback interface by forwarding calls to RadioTuner.Callback.
 */
class TunerCallbackAdapter extends ITunerCallback.Stub {
    private static final String TAG = "BroadcastRadio.TunerCallbackAdapter";

    @NonNull private final RadioTuner.Callback mCallback;
    @NonNull private final Handler mHandler;
    private final Object mLock = new Object();

    @Nullable private ITuner mTuner;
    boolean mPendingProgramInfoChanged = false;

    TunerCallbackAdapter(@NonNull RadioTuner.Callback callback, @Nullable Handler handler) {
        mCallback = callback;
        if (handler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        } else {
            mHandler = handler;
        }
    }

    public void attachTuner(@NonNull ITuner tuner) {
        synchronized (mLock) {
            if (mTuner != null) throw new IllegalStateException();
            mTuner = tuner;
            if (mPendingProgramInfoChanged) onProgramInfoChanged();
        }
    }

    @Override
    public void onError(int status) {
        mHandler.post(() -> mCallback.onError(status));
    }

    @Override
    public void onConfigurationChanged(RadioManager.BandConfig config) {
        mHandler.post(() -> mCallback.onConfigurationChanged(config));
    }

    @Override
    public void onProgramInfoChanged() {
        synchronized (mLock) {
            if (mTuner == null) {
                mPendingProgramInfoChanged = true;
                return;
            }
        }

        RadioManager.ProgramInfo info;
        try {
            info = mTuner.getProgramInformation();
        } catch (RemoteException e) {
            Log.e(TAG, "service died", e);
            return;
        }

        mHandler.post(() -> {
            mCallback.onProgramInfoChanged(info);

            RadioMetadata metadata = info.getMetadata();
            if (metadata != null) mCallback.onMetadataChanged(metadata);
        });
    }

    @Override
    public void onTrafficAnnouncement(boolean active) {
        mHandler.post(() -> mCallback.onTrafficAnnouncement(active));
    }

    @Override
    public void onEmergencyAnnouncement(boolean active) {
        mHandler.post(() -> mCallback.onEmergencyAnnouncement(active));
    }

    @Override
    public void onAntennaState(boolean connected) {
        mHandler.post(() -> mCallback.onAntennaState(connected));
    }

    @Override
    public void onBackgroundScanAvailabilityChange(boolean isAvailable) {
        mHandler.post(() -> mCallback.onBackgroundScanAvailabilityChange(isAvailable));
    }

    @Override
    public void onBackgroundScanComplete() {
        mHandler.post(() -> mCallback.onBackgroundScanComplete());
    }

    @Override
    public void onProgramListChanged() {
        mHandler.post(() -> mCallback.onProgramListChanged());
    }
}
