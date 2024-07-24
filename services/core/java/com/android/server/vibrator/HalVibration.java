/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.util.SparseArray;

import com.android.internal.util.FrameworkStatsLog;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * Represents a vibration defined by a {@link CombinedVibration} that will be performed by
 * the IVibrator HAL.
 */
final class HalVibration extends Vibration {

    public final SparseArray<VibrationEffect> mFallbacks = new SparseArray<>();

    /** A {@link CountDownLatch} to enable waiting for completion. */
    private final CountDownLatch mCompletionLatch = new CountDownLatch(1);

    /** The original effect that was requested, for debugging purposes. */
    @NonNull
    private final CombinedVibration mOriginalEffect;

    /**
     * The scaled and adapted effect to be played. This should only be updated from a single thread,
     * but can be read from different ones for debugging purposes.
     */
    @NonNull
    private volatile CombinedVibration mEffectToPlay;

    /** Vibration status. */
    private Vibration.Status mStatus;

    /** Reported scale values applied to the vibration effects. */
    private int mScaleLevel;
    private float mAdaptiveScale;

    HalVibration(@NonNull IBinder token, @NonNull CombinedVibration effect,
            @NonNull CallerInfo callerInfo) {
        super(token, callerInfo);
        mOriginalEffect = effect;
        mEffectToPlay = effect;
        mStatus = Vibration.Status.RUNNING;
        mScaleLevel = VibrationScaler.SCALE_NONE;
        mAdaptiveScale = VibrationScaler.ADAPTIVE_SCALE_NONE;
    }

    /**
     * Set the {@link Status} of this vibration and reports the current system time as this
     * vibration end time, for debugging purposes.
     *
     * <p>This method will only accept given value if the current status is {@link
     * Status#RUNNING}.
     */
    public void end(EndInfo info) {
        if (hasEnded()) {
            // Vibration already ended, keep first ending status set and ignore this one.
            return;
        }
        mStatus = info.status;
        stats.reportEnded(info.endedBy);
        mCompletionLatch.countDown();
    }

    /** Waits indefinitely until another thread calls {@link #end} on this vibration. */
    public void waitForEnd() throws InterruptedException {
        mCompletionLatch.await();
    }

    /**
     * Return the effect to be played when given prebaked effect id is not supported by the
     * vibrator.
     */
    @Nullable
    public VibrationEffect getFallback(int effectId) {
        return mFallbacks.get(effectId);
    }

    /**
     * Add a fallback {@link VibrationEffect} to be played when given effect id is not supported,
     * which might be necessary for replacement in realtime.
     */
    public void addFallback(int effectId, VibrationEffect effect) {
        mFallbacks.put(effectId, effect);
    }

    /**
     * Resolves the default vibration amplitude of {@link #getEffectToPlay()} and each fallback.
     *
     * @param defaultAmplitude An integer in [1,255] representing the device default amplitude to
     *                        replace the {@link VibrationEffect#DEFAULT_AMPLITUDE}.
     */
    public void resolveEffects(int defaultAmplitude) {
        CombinedVibration newEffect =
                mEffectToPlay.transform(VibrationEffect::resolve, defaultAmplitude);
        if (!Objects.equals(mEffectToPlay, newEffect)) {
            mEffectToPlay = newEffect;
        }
        for (int i = 0; i < mFallbacks.size(); i++) {
            mFallbacks.setValueAt(i, mFallbacks.valueAt(i).resolve(defaultAmplitude));
        }
    }

    /**
     * Scales the {@link #getEffectToPlay()} and each fallback effect based on the vibration usage.
     */
    public void scaleEffects(VibrationScaler scaler) {
        int vibrationUsage = callerInfo.attrs.getUsage();

        // Save scale values for debugging purposes.
        mScaleLevel = scaler.getScaleLevel(vibrationUsage);
        mAdaptiveScale = scaler.getAdaptiveHapticsScale(vibrationUsage);

        // Scale all VibrationEffect instances in given CombinedVibration.
        CombinedVibration newEffect = mEffectToPlay.transform(scaler::scale, vibrationUsage);
        if (!Objects.equals(mEffectToPlay, newEffect)) {
            mEffectToPlay = newEffect;
        }

        // Scale all fallback VibrationEffect instances that can be used by VibrationThread.
        for (int i = 0; i < mFallbacks.size(); i++) {
            mFallbacks.setValueAt(i, scaler.scale(mFallbacks.valueAt(i), vibrationUsage));
        }
    }

    /**
     * Adapts the {@link #getEffectToPlay()} to the device using given vibrator adapter.
     *
     * @param deviceAdapter A {@link CombinedVibration.VibratorAdapter} that transforms vibration
     *                      effects to device vibrators based on its capabilities.
     */
    public void adaptToDevice(CombinedVibration.VibratorAdapter deviceAdapter) {
        CombinedVibration newEffect = mEffectToPlay.adapt(deviceAdapter);
        if (!Objects.equals(mEffectToPlay, newEffect)) {
            mEffectToPlay = newEffect;
        }
        // No need to update fallback effects, they are already configured per device.
    }

    /** Return true is current status is different from {@link Status#RUNNING}. */
    public boolean hasEnded() {
        return mStatus != Status.RUNNING;
    }

    @Override
    public boolean isRepeating() {
        return mOriginalEffect.getDuration() == Long.MAX_VALUE;
    }

    /** Return the effect that should be played by this vibration. */
    public CombinedVibration getEffectToPlay() {
        return mEffectToPlay;
    }

    /** Return {@link Vibration.DebugInfo} with read-only debug information about this vibration. */
    public Vibration.DebugInfo getDebugInfo() {
        // Clear the original effect if it's the same as the effect that was played, for simplicity
        CombinedVibration originalEffect =
                Objects.equals(mOriginalEffect, mEffectToPlay) ? null : mOriginalEffect;
        return new Vibration.DebugInfo(mStatus, stats, mEffectToPlay, originalEffect,
                mScaleLevel, mAdaptiveScale, callerInfo);
    }

    /** Return {@link VibrationStats.StatsInfo} with read-only metrics about this vibration. */
    public VibrationStats.StatsInfo getStatsInfo(long completionUptimeMillis) {
        int vibrationType = isRepeating()
                ? FrameworkStatsLog.VIBRATION_REPORTED__VIBRATION_TYPE__REPEATED
                : FrameworkStatsLog.VIBRATION_REPORTED__VIBRATION_TYPE__SINGLE;
        return new VibrationStats.StatsInfo(
                callerInfo.uid, vibrationType, callerInfo.attrs.getUsage(), mStatus,
                stats, completionUptimeMillis);
    }

    /**
     * Returns true if this vibration can pipeline with the specified one.
     *
     * <p>Note that currently, repeating vibrations can't pipeline with following vibrations,
     * because the cancel() call to stop the repetition will cancel a pending vibration too. This
     * can be changed if we have a use-case to reason around behavior for. It may also be nice to
     * pipeline very short vibrations together, regardless of the flag.
     */
    public boolean canPipelineWith(HalVibration vib) {
        return callerInfo.uid == vib.callerInfo.uid && callerInfo.attrs.isFlagSet(
                VibrationAttributes.FLAG_PIPELINED_EFFECT)
                && vib.callerInfo.attrs.isFlagSet(VibrationAttributes.FLAG_PIPELINED_EFFECT)
                && !isRepeating();
    }
}
