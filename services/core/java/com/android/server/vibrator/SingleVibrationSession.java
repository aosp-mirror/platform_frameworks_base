/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.vibrator;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.CombinedVibration;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.NoSuchElementException;

/**
 * A vibration session holding a single {@link CombinedVibration} request, performed by a
 * {@link VibrationStepConductor}.
 */
final class SingleVibrationSession implements VibrationSession, IBinder.DeathRecipient {
    private static final String TAG = "SingleVibrationSession";

    private final Object mLock = new Object();
    private final long mSessionId = VibrationSession.nextSessionId();
    private final IBinder mCallerToken;
    private final HalVibration mVibration;

    @GuardedBy("mLock")
    private VibrationStepConductor mConductor;

    SingleVibrationSession(@NonNull IBinder callerToken, @NonNull CallerInfo callerInfo,
            @NonNull CombinedVibration vibration) {
        mCallerToken = callerToken;
        mVibration = new HalVibration(callerInfo, vibration);
    }

    public void setVibrationConductor(@Nullable VibrationStepConductor conductor) {
        synchronized (mLock) {
            mConductor = conductor;
        }
    }

    public HalVibration getVibration() {
        return mVibration;
    }

    @Override
    public long getSessionId() {
        return mSessionId;
    }

    @Override
    public long getCreateUptimeMillis() {
        return mVibration.stats.getCreateUptimeMillis();
    }

    @Override
    public boolean isRepeating() {
        return mVibration.getEffectToPlay().getDuration() == Long.MAX_VALUE;
    }

    @Override
    public CallerInfo getCallerInfo() {
        return mVibration.callerInfo;
    }

    @Override
    public IBinder getCallerToken() {
        return mCallerToken;
    }

    @Override
    public DebugInfo getDebugInfo() {
        return mVibration.getDebugInfo();
    }

    @Override
    public boolean wasEndRequested() {
        if (mVibration.hasEnded()) {
            return true;
        }
        synchronized (mLock) {
            return mConductor != null && mConductor.wasNotifiedToCancel();
        }
    }

    @Override
    public void binderDied() {
        Slog.d(TAG, "Binder died, cancelling vibration...");
        requestEnd(Status.CANCELLED_BINDER_DIED, /* endedBy= */ null, /* immediate= */ false);
    }

    @Override
    public boolean linkToDeath() {
        try {
            mCallerToken.linkToDeath(this, 0);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error linking vibration to token death", e);
            return false;
        }
        return true;
    }

    @Override
    public void unlinkToDeath() {
        try {
            mCallerToken.unlinkToDeath(this, 0);
        } catch (NoSuchElementException e) {
            Slog.wtf(TAG, "Failed to unlink vibration to token death", e);
        }
    }

    @Override
    public void requestEnd(@NonNull Status status, @Nullable CallerInfo endedBy,
            boolean immediate) {
        synchronized (mLock) {
            if (mConductor != null) {
                mConductor.notifyCancelled(new Vibration.EndInfo(status, endedBy), immediate);
            } else {
                mVibration.end(new Vibration.EndInfo(status, endedBy));
            }
        }
    }

    @Override
    public void notifyVibratorCallback(int vibratorId, long vibrationId) {
        if (vibrationId != mVibration.id) {
            return;
        }
        synchronized (mLock) {
            if (mConductor != null) {
                mConductor.notifyVibratorComplete(vibratorId);
            }
        }
    }

    @Override
    public void notifySyncedVibratorsCallback(long vibrationId) {
        if (vibrationId != mVibration.id) {
            return;
        }
        synchronized (mLock) {
            if (mConductor != null) {
                mConductor.notifySyncedVibrationComplete();
            }
        }
    }

    @Override
    public void notifySessionCallback() {
        // ignored, external control does not expect callbacks from the vibrator manager for session
    }

    @Override
    public String toString() {
        return "SingleVibrationSession{"
                + "sessionId= " + mSessionId
                + ", callerToken= " + mCallerToken
                + ", vibration=" + mVibration
                + '}';
    }
}
