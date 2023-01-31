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

import android.annotation.Nullable;
import android.os.CombinedVibration;
import android.os.IBinder;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.util.SparseArray;

import com.android.internal.util.FrameworkStatsLog;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/**
 * Represents a vibration defined by a {@link CombinedVibration} that will be performed by
 * the IVibrator HAL.
 */
final class HalVibration extends Vibration {

    public final VibrationAttributes attrs;
    public final long id;
    public final int uid;
    public final int displayId;
    public final String opPkg;
    public final String reason;
    public final IBinder token;
    public final SparseArray<VibrationEffect> mFallbacks = new SparseArray<>();

    /** The actual effect to be played. */
    @Nullable
    private CombinedVibration mEffect;

    /**
     * The original effect that was requested. Typically these two things differ because the effect
     * was scaled based on the users vibration intensity settings.
     */
    @Nullable
    private CombinedVibration mOriginalEffect;

    /** Vibration status. */
    private Vibration.Status mStatus;

    /** Vibration runtime stats. */
    private final VibrationStats mStats = new VibrationStats();

    /** A {@link CountDownLatch} to enable waiting for completion. */
    private final CountDownLatch mCompletionLatch = new CountDownLatch(1);

    HalVibration(IBinder token, int id, CombinedVibration effect,
            VibrationAttributes attrs, int uid, int displayId, String opPkg, String reason) {
        this.token = token;
        this.mEffect = effect;
        this.id = id;
        this.attrs = attrs;
        this.uid = uid;
        this.displayId = displayId;
        this.opPkg = opPkg;
        this.reason = reason;
        mStatus = Vibration.Status.RUNNING;
    }

    VibrationStats stats() {
        return mStats;
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
        mStats.reportEnded(info.endedByUid, info.endedByUsage);
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
     * Applied update function to the current effect held by this vibration, and to each fallback
     * effect added.
     */
    public void updateEffects(Function<VibrationEffect, VibrationEffect> updateFn) {
        CombinedVibration newEffect = transformCombinedEffect(mEffect, updateFn);
        if (!newEffect.equals(mEffect)) {
            if (mOriginalEffect == null) {
                mOriginalEffect = mEffect;
            }
            mEffect = newEffect;
        }
        for (int i = 0; i < mFallbacks.size(); i++) {
            mFallbacks.setValueAt(i, updateFn.apply(mFallbacks.valueAt(i)));
        }
    }

    /**
     * Creates a new {@link CombinedVibration} by applying the given transformation function
     * to each {@link VibrationEffect}.
     */
    private static CombinedVibration transformCombinedEffect(
            CombinedVibration combinedEffect, Function<VibrationEffect, VibrationEffect> fn) {
        if (combinedEffect instanceof CombinedVibration.Mono) {
            VibrationEffect effect = ((CombinedVibration.Mono) combinedEffect).getEffect();
            return CombinedVibration.createParallel(fn.apply(effect));
        } else if (combinedEffect instanceof CombinedVibration.Stereo) {
            SparseArray<VibrationEffect> effects =
                    ((CombinedVibration.Stereo) combinedEffect).getEffects();
            CombinedVibration.ParallelCombination combination =
                    CombinedVibration.startParallel();
            for (int i = 0; i < effects.size(); i++) {
                combination.addVibrator(effects.keyAt(i), fn.apply(effects.valueAt(i)));
            }
            return combination.combine();
        } else if (combinedEffect instanceof CombinedVibration.Sequential) {
            List<CombinedVibration> effects =
                    ((CombinedVibration.Sequential) combinedEffect).getEffects();
            CombinedVibration.SequentialCombination combination =
                    CombinedVibration.startSequential();
            for (CombinedVibration effect : effects) {
                combination.addNext(transformCombinedEffect(effect, fn));
            }
            return combination.combine();
        } else {
            // Unknown combination, return same effect.
            return combinedEffect;
        }
    }

    /** Return true is current status is different from {@link Status#RUNNING}. */
    public boolean hasEnded() {
        return mStatus != Status.RUNNING;
    }

    /** Return true is effect is a repeating vibration. */
    public boolean isRepeating() {
        return mEffect.getDuration() == Long.MAX_VALUE;
    }

    /** Return the effect that should be played by this vibration. */
    @Nullable
    public CombinedVibration getEffect() {
        return mEffect;
    }

    /**
     * Return {@link Vibration.DebugInfo} with read-only debug information about this vibration.
     */
    public Vibration.DebugInfo getDebugInfo() {
        return new Vibration.DebugInfo(mStatus, mStats, mEffect, mOriginalEffect, /* scale= */ 0,
                attrs, uid, displayId, opPkg, reason);
    }

    /** Return {@link VibrationStats.StatsInfo} with read-only metrics about this vibration. */
    public VibrationStats.StatsInfo getStatsInfo(long completionUptimeMillis) {
        int vibrationType = isRepeating()
                ? FrameworkStatsLog.VIBRATION_REPORTED__VIBRATION_TYPE__REPEATED
                : FrameworkStatsLog.VIBRATION_REPORTED__VIBRATION_TYPE__SINGLE;
        return new VibrationStats.StatsInfo(
                uid, vibrationType, attrs.getUsage(), mStatus, mStats, completionUptimeMillis);
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
        return uid == vib.uid && attrs.isFlagSet(VibrationAttributes.FLAG_PIPELINED_EFFECT)
                && vib.attrs.isFlagSet(VibrationAttributes.FLAG_PIPELINED_EFFECT)
                && !isRepeating();
    }
}
