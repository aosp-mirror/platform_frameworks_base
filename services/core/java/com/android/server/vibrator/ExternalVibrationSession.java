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
import android.content.Context;
import android.os.ExternalVibration;
import android.os.ExternalVibrationScale;
import android.os.IBinder;
import android.os.VibrationAttributes;
import android.os.vibrator.Flags;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FrameworkStatsLog;

/**
 * A vibration session holding a single {@link ExternalVibration} request.
 */
final class ExternalVibrationSession extends Vibration
        implements VibrationSession, IBinder.DeathRecipient {

    private final Object mLock = new Object();
    private final ExternalVibration mExternalVibration;
    private final ExternalVibrationScale mScale = new ExternalVibrationScale();

    @GuardedBy("mLock")
    @Nullable
    private Runnable mBinderDeathCallback;

    ExternalVibrationSession(ExternalVibration externalVibration) {
        super(new CallerInfo(
                externalVibration.getVibrationAttributes(), externalVibration.getUid(),
                // TODO(b/249785241): Find a way to link ExternalVibration to a VirtualDevice
                // instead of using DEVICE_ID_INVALID here and relying on the UID checks.
                Context.DEVICE_ID_INVALID, externalVibration.getPackage(), null));
        mExternalVibration = externalVibration;
    }

    public ExternalVibrationScale getScale() {
        return mScale;
    }

    @Override
    public long getCreateUptimeMillis() {
        return stats.getCreateUptimeMillis();
    }

    @Override
    public CallerInfo getCallerInfo() {
        return callerInfo;
    }

    @Override
    public IBinder getCallerToken() {
        return mExternalVibration.getToken();
    }

    @Override
    public VibrationSession.DebugInfo getDebugInfo() {
        return new Vibration.DebugInfoImpl(getStatus(), callerInfo,
                FrameworkStatsLog.VIBRATION_REPORTED__VIBRATION_TYPE__EXTERNAL, stats,
                /* playedEffect= */ null, /* originalEffect= */ null, mScale.scaleLevel,
                mScale.adaptiveHapticsScale);
    }

    @Override
    public boolean isRepeating() {
        // We don't currently know if the external vibration is repeating, so we just use a
        // heuristic based on the usage. Ideally this would be propagated in the ExternalVibration.
        int usage = mExternalVibration.getVibrationAttributes().getUsage();
        return usage == VibrationAttributes.USAGE_RINGTONE
                || usage == VibrationAttributes.USAGE_ALARM;
    }

    @Override
    public boolean wasEndRequested() {
        // End request is immediate, so just check if vibration has already ended.
        return hasEnded();
    }

    @Override
    public boolean linkToDeath(Runnable callback) {
        synchronized (mLock) {
            mBinderDeathCallback = callback;
        }
        mExternalVibration.linkToDeath(this);
        return true;
    }

    @Override
    public void unlinkToDeath() {
        mExternalVibration.unlinkToDeath(this);
        synchronized (mLock) {
            mBinderDeathCallback = null;
        }
    }

    @Override
    public void binderDied() {
        Runnable callback;
        synchronized (mLock) {
            callback = mBinderDeathCallback;
        }
        if (callback != null) {
            callback.run();
        }
    }

    @Override
    void end(EndInfo endInfo) {
        super.end(endInfo);
        if (stats.hasStarted()) {
            // External vibration doesn't have feedback from total time the vibrator was playing
            // with non-zero amplitude, so we use the duration between start and end times of
            // the vibration as the time the vibrator was ON, since the haptic channels are
            // open for this duration and can receive vibration waveform data.
            stats.reportVibratorOn(stats.getEndUptimeMillis() - stats.getStartUptimeMillis());
        }
    }

    @Override
    public void requestEnd(@NonNull Status status, @Nullable CallerInfo endedBy,
            boolean immediate) {
        // Notify external client that this vibration should stop sending data to the vibrator.
        mExternalVibration.mute();
        end(new EndInfo(status, endedBy));
    }

    @Override
    public void notifyVibratorCallback(int vibratorId, long vibrationId) {
        // ignored, external control does not expect callbacks from the vibrator
    }

    @Override
    public void notifySyncedVibratorsCallback(long vibrationId) {
        // ignored, external control does not expect callbacks from the vibrator manager
    }

    boolean isHoldingSameVibration(ExternalVibration vib) {
        return mExternalVibration.equals(vib);
    }

    void muteScale() {
        mScale.scaleLevel = ExternalVibrationScale.ScaleLevel.SCALE_MUTE;
        if (Flags.hapticsScaleV2Enabled()) {
            mScale.scaleFactor = 0;
        }
    }

    void scale(VibrationScaler scaler, int usage) {
        mScale.scaleLevel = scaler.getScaleLevel(usage);
        if (Flags.hapticsScaleV2Enabled()) {
            mScale.scaleFactor = scaler.getScaleFactor(usage);
        }
        mScale.adaptiveHapticsScale = scaler.getAdaptiveHapticsScale(usage);
        stats.reportAdaptiveScale(mScale.adaptiveHapticsScale);
    }
}
