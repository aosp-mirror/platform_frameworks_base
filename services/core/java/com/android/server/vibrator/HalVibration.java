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
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.Flags;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.SparseArray;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.function.IntFunction;

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

    /** Reported scale values applied to the vibration effects. */
    private int mScaleLevel;
    private float mAdaptiveScale;

    HalVibration(@NonNull VibrationSession.CallerInfo callerInfo,
            @NonNull CombinedVibration effect) {
        super(callerInfo);
        mOriginalEffect = effect;
        mEffectToPlay = effect;
        mScaleLevel = VibrationScaler.SCALE_NONE;
        mAdaptiveScale = VibrationScaler.ADAPTIVE_SCALE_NONE;
    }

    @Override
    public void end(EndInfo endInfo) {
        super.end(endInfo);
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
     * Add a fallback {@link VibrationEffect} to be played for each predefined effect id, which
     * might be necessary for replacement in realtime.
     */
    public void fillFallbacks(IntFunction<VibrationEffect> fallbackProvider) {
        fillFallbacksForEffect(mEffectToPlay, fallbackProvider);
    }

    /**
     * Scales the {@link #getEffectToPlay()} and each fallback effect based on the vibration usage.
     */
    public void scaleEffects(VibrationScaler scaler) {
        int vibrationUsage = callerInfo.attrs.getUsage();

        // Save scale values for debugging purposes.
        mScaleLevel = scaler.getScaleLevel(vibrationUsage);
        mAdaptiveScale = scaler.getAdaptiveHapticsScale(vibrationUsage);
        stats.reportAdaptiveScale(mAdaptiveScale);

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
    public boolean adaptToDevice(CombinedVibration.VibratorAdapter deviceAdapter) {
        CombinedVibration adaptedEffect = mEffectToPlay.adapt(deviceAdapter);
        if (adaptedEffect == null) {
            return false;
        }

        if (!mEffectToPlay.equals(adaptedEffect)) {
            mEffectToPlay = adaptedEffect;
        }
        // No need to update fallback effects, they are already configured per device.

        return true;
    }

    /** Return the effect that should be played by this vibration. */
    public CombinedVibration getEffectToPlay() {
        return mEffectToPlay;
    }

    @Override
    public VibrationSession.DebugInfo getDebugInfo() {
        // Clear the original effect if it's the same as the effect that was played, for simplicity
        CombinedVibration originalEffect =
                Objects.equals(mOriginalEffect, mEffectToPlay) ? null : mOriginalEffect;
        return new Vibration.DebugInfoImpl(getStatus(), callerInfo,
                VibrationStats.StatsInfo.findVibrationType(mEffectToPlay), stats, mEffectToPlay,
                originalEffect, mScaleLevel, mAdaptiveScale);
    }

    /** Returns true if this vibration can pipeline with the specified one. */
    public boolean canPipelineWith(HalVibration vib,
            @Nullable SparseArray<VibratorInfo> vibratorInfos, int durationThresholdMs) {
        long effectDuration = Flags.vibrationPipelineEnabled() && (vibratorInfos != null)
                ? mEffectToPlay.getDuration(vibratorInfos)
                : mEffectToPlay.getDuration();
        if (effectDuration == Long.MAX_VALUE) {
            // Repeating vibrations can't pipeline with following vibrations, because the cancel()
            // call to stop the repetition will cancel a pending vibration too. This can be changed
            // if we have a use-case, requiring changes to how pipelined vibrations are cancelled.
            return false;
        }
        if (Flags.vibrationPipelineEnabled()
                && (effectDuration > 0) && (effectDuration < durationThresholdMs)) {
            // Duration is known and it's less than the pipeline threshold, so allow it.
            // No need to check UID, as we want to avoid cancelling any short effect and let the
            // vibrator hardware gracefully finish the vibration.
            return true;
        }
        // Check the same app is requesting multiple vibrations with the pipeline flag,
        // independently of the effect durations.
        return callerInfo.uid == vib.callerInfo.uid
                && callerInfo.attrs.isFlagSet(VibrationAttributes.FLAG_PIPELINED_EFFECT)
                && vib.callerInfo.attrs.isFlagSet(VibrationAttributes.FLAG_PIPELINED_EFFECT);
    }

    private void fillFallbacksForEffect(CombinedVibration effect,
            IntFunction<VibrationEffect> fallbackProvider) {
        if (effect instanceof CombinedVibration.Mono) {
            fillFallbacksForEffect(((CombinedVibration.Mono) effect).getEffect(), fallbackProvider);
        } else if (effect instanceof CombinedVibration.Stereo) {
            SparseArray<VibrationEffect> effects =
                    ((CombinedVibration.Stereo) effect).getEffects();
            for (int i = 0; i < effects.size(); i++) {
                fillFallbacksForEffect(effects.valueAt(i), fallbackProvider);
            }
        } else if (effect instanceof CombinedVibration.Sequential) {
            List<CombinedVibration> effects =
                    ((CombinedVibration.Sequential) effect).getEffects();
            for (int i = 0; i < effects.size(); i++) {
                fillFallbacksForEffect(effects.get(i), fallbackProvider);
            }
        }
    }

    private void fillFallbacksForEffect(VibrationEffect effect,
            IntFunction<VibrationEffect> fallbackProvider) {
        if (!(effect instanceof VibrationEffect.Composed composed)) {
            return;
        }
        int segmentCount = composed.getSegments().size();
        for (int i = 0; i < segmentCount; i++) {
            VibrationEffectSegment segment = composed.getSegments().get(i);
            if ((segment instanceof PrebakedSegment prebaked) && prebaked.shouldFallback()) {
                VibrationEffect fallback = fallbackProvider.apply(prebaked.getEffectId());
                if (fallback != null) {
                    mFallbacks.put(prebaked.getEffectId(), fallback);
                }
            }
        }
    }
}
