/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.hardware.vibrator.IVibratorManager;
import android.os.CombinedVibrationEffect;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.WorkSource;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.FrameworkStatsLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

/** Plays a {@link Vibration} in dedicated thread. */
final class VibrationThread extends Thread implements IBinder.DeathRecipient {
    private static final String TAG = "VibrationThread";
    private static final boolean DEBUG = false;

    /**
     * Extra timeout added to the end of each vibration step to ensure it finishes even when
     * vibrator callbacks are lost.
     */
    private static final long CALLBACKS_EXTRA_TIMEOUT = 100;

    /** Fixed large duration used to note repeating vibrations to {@link IBatteryStats}. */
    private static final long BATTERY_STATS_REPEATING_VIBRATION_DURATION = 5_000;

    private static final List<Step> EMPTY_STEP_LIST = new ArrayList<>();

    /** Callbacks for playing a {@link Vibration}. */
    interface VibrationCallbacks {

        /**
         * Callback triggered before starting a synchronized vibration step. This will be called
         * with {@code requiredCapabilities = 0} if no synchronization is required.
         *
         * @param requiredCapabilities The required syncing capabilities for this preparation step.
         *                             Expects a combination of values from
         *                             IVibratorManager.CAP_PREPARE_* and
         *                             IVibratorManager.CAP_MIXED_TRIGGER_*.
         * @param vibratorIds          The id of the vibrators to be prepared.
         */
        boolean prepareSyncedVibration(long requiredCapabilities, int[] vibratorIds);

        /** Callback triggered after synchronized vibrations were prepared. */
        boolean triggerSyncedVibration(long vibrationId);

        /** Callback triggered to cancel a prepared synced vibration. */
        void cancelSyncedVibration();

        /** Callback triggered when vibration thread is complete. */
        void onVibrationEnded(long vibrationId, Vibration.Status status);
    }

    private final Object mLock = new Object();
    private final WorkSource mWorkSource = new WorkSource();
    private final PowerManager.WakeLock mWakeLock;
    private final IBatteryStats mBatteryStatsService;
    private final VibrationEffectModifier<VibratorInfo> mDeviceEffectAdapter =
            new DeviceVibrationEffectAdapter();
    private final Vibration mVibration;
    private final VibrationCallbacks mCallbacks;
    private final SparseArray<VibratorController> mVibrators = new SparseArray<>();
    private final StepQueue mStepQueue = new StepQueue();

    private volatile boolean mForceStop;

    VibrationThread(Vibration vib, SparseArray<VibratorController> availableVibrators,
            PowerManager.WakeLock wakeLock, IBatteryStats batteryStatsService,
            VibrationCallbacks callbacks) {
        mVibration = vib;
        mCallbacks = callbacks;
        mWakeLock = wakeLock;
        mWorkSource.set(vib.uid);
        mWakeLock.setWorkSource(mWorkSource);
        mBatteryStatsService = batteryStatsService;

        CombinedVibrationEffect effect = vib.getEffect();
        for (int i = 0; i < availableVibrators.size(); i++) {
            if (effect.hasVibrator(availableVibrators.keyAt(i))) {
                mVibrators.put(availableVibrators.keyAt(i), availableVibrators.valueAt(i));
            }
        }
    }

    Vibration getVibration() {
        return mVibration;
    }

    @VisibleForTesting
    SparseArray<VibratorController> getVibrators() {
        return mVibrators;
    }

    @Override
    public void binderDied() {
        if (DEBUG) {
            Slog.d(TAG, "Binder died, cancelling vibration...");
        }
        cancel();
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        mWakeLock.acquire();
        try {
            mVibration.token.linkToDeath(this, 0);
            Vibration.Status status = playVibration();
            mCallbacks.onVibrationEnded(mVibration.id, status);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error linking vibration to token death", e);
        } finally {
            mVibration.token.unlinkToDeath(this, 0);
            mWakeLock.release();
        }
    }

    /** Cancel current vibration and shuts down the thread gracefully. */
    public void cancel() {
        mForceStop = true;
        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Vibration cancelled");
            }
            mLock.notify();
        }
    }

    /** Notify current vibration that a synced step has completed. */
    public void syncedVibrationComplete() {
        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Synced vibration complete reported by vibrator manager");
            }
            for (int i = 0; i < mVibrators.size(); i++) {
                mStepQueue.consumeOnVibratorComplete(mVibrators.keyAt(i));
            }
            mLock.notify();
        }
    }

    /** Notify current vibration that a step has completed on given vibrator. */
    public void vibratorComplete(int vibratorId) {
        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Vibration complete reported by vibrator " + vibratorId);
            }
            mStepQueue.consumeOnVibratorComplete(vibratorId);
            mLock.notify();
        }
    }

    private Vibration.Status playVibration() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "playVibration");
        try {
            CombinedVibrationEffect.Sequential effect = toSequential(mVibration.getEffect());
            int stepsPlayed = 0;

            synchronized (mLock) {
                mStepQueue.offer(new StartVibrateStep(effect));
                Step topOfQueue;

                while ((topOfQueue = mStepQueue.peek()) != null) {
                    long waitTime = topOfQueue.calculateWaitTime();
                    if (waitTime <= 0) {
                        stepsPlayed += mStepQueue.consume();
                    } else {
                        try {
                            mLock.wait(waitTime);
                        } catch (InterruptedException e) { }
                    }
                    if (mForceStop) {
                        mStepQueue.cancel();
                        return Vibration.Status.CANCELLED;
                    }
                }
            }

            // Some effects might be ignored because the specified vibrator don't exist or doesn't
            // support the effect. We only report ignored here if nothing was played besides the
            // StartVibrateStep (which means every attempt to turn on the vibrator was ignored).
            return stepsPlayed > effect.getEffects().size()
                    ? Vibration.Status.FINISHED : Vibration.Status.IGNORED_UNSUPPORTED;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private void noteVibratorOn(long duration) {
        try {
            if (duration <= 0) {
                return;
            }
            if (duration == Long.MAX_VALUE) {
                // Repeating duration has started. Report a fixed duration here, noteVibratorOff
                // should be called when this is cancelled.
                duration = BATTERY_STATS_REPEATING_VIBRATION_DURATION;
            }
            mBatteryStatsService.noteVibratorOn(mVibration.uid, duration);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.VIBRATOR_STATE_CHANGED,
                    mVibration.uid, null, FrameworkStatsLog.VIBRATOR_STATE_CHANGED__STATE__ON,
                    duration);
        } catch (RemoteException e) {
        }
    }

    private void noteVibratorOff() {
        try {
            mBatteryStatsService.noteVibratorOff(mVibration.uid);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.VIBRATOR_STATE_CHANGED,
                    mVibration.uid, null, FrameworkStatsLog.VIBRATOR_STATE_CHANGED__STATE__OFF,
                    /* duration= */ 0);
        } catch (RemoteException e) {
        }
    }

    /**
     * Get the duration the vibrator will be on for given {@code waveform}, starting at {@code
     * startIndex} until the next time it's vibrating amplitude is zero.
     */
    private static long getVibratorOnDuration(VibrationEffect.Composed effect, int startIndex) {
        List<VibrationEffectSegment> segments = effect.getSegments();
        int segmentCount = segments.size();
        int repeatIndex = effect.getRepeatIndex();
        int i = startIndex;
        long timing = 0;
        while (i < segmentCount) {
            if (!(segments.get(i) instanceof StepSegment)) {
                break;
            }
            StepSegment stepSegment = (StepSegment) segments.get(i);
            if (stepSegment.getAmplitude() == 0) {
                break;
            }
            timing += stepSegment.getDuration();
            i++;
            if (i == segmentCount && repeatIndex >= 0) {
                i = repeatIndex;
                // prevent infinite loop
                repeatIndex = -1;
            }
            if (i == startIndex) {
                return 1000;
            }
        }
        return timing;
    }

    private static CombinedVibrationEffect.Sequential toSequential(CombinedVibrationEffect effect) {
        if (effect instanceof CombinedVibrationEffect.Sequential) {
            return (CombinedVibrationEffect.Sequential) effect;
        }
        return (CombinedVibrationEffect.Sequential) CombinedVibrationEffect.startSequential()
                .addNext(effect)
                .combine();
    }

    /** Queue for {@link Step Steps}, sorted by their start time. */
    private final class StepQueue {
        @GuardedBy("mLock")
        private final PriorityQueue<Step> mNextSteps = new PriorityQueue<>();

        @GuardedBy("mLock")
        public void offer(@NonNull Step step) {
            mNextSteps.offer(step);
        }

        @GuardedBy("mLock")
        @Nullable
        public Step peek() {
            return mNextSteps.peek();
        }

        /**
         * Play and remove the step at the top of this queue, and also adds the next steps
         * generated to be played next.
         *
         * @return the number of steps played
         */
        @GuardedBy("mLock")
        public int consume() {
            Step nextStep = mNextSteps.poll();
            if (nextStep != null) {
                mNextSteps.addAll(nextStep.play());
                return 1;
            }
            return 0;
        }

        /**
         * Play and remove the step in this queue that should be anticipated by the vibrator
         * completion callback.
         *
         * <p>This assumes only one of the next steps is waiting on this given vibrator, so the
         * first step found is played by this method, in no particular order.
         */
        @GuardedBy("mLock")
        public void consumeOnVibratorComplete(int vibratorId) {
            Iterator<Step> it = mNextSteps.iterator();
            List<Step> nextSteps = EMPTY_STEP_LIST;
            while (it.hasNext()) {
                Step step = it.next();
                if (step.shouldPlayWhenVibratorComplete(vibratorId)) {
                    it.remove();
                    nextSteps = step.play();
                    break;
                }
            }
            mNextSteps.addAll(nextSteps);
        }

        /**
         * Cancel the current queue, clearing all remaining steps.
         *
         * <p>This will remove and trigger {@link Step#cancel()} in all steps, in order.
         */
        @GuardedBy("mLock")
        public void cancel() {
            Step step;
            while ((step = mNextSteps.poll()) != null) {
                step.cancel();
            }
        }
    }

    /**
     * Represent a single step for playing a vibration.
     *
     * <p>Every step has a start time, which can be used to apply delays between steps while
     * executing them in sequence.
     */
    private abstract class Step implements Comparable<Step> {
        public final long startTime;

        Step(long startTime) {
            this.startTime = startTime;
        }

        /** Play this step, returning a (possibly empty) list of next steps. */
        @NonNull
        public abstract List<Step> play();

        /** Cancel this pending step. */
        public void cancel() {
        }

        /**
         * Return true to play this step right after a vibrator has notified vibration completed,
         * used to anticipate steps waiting on vibrator callbacks with a timeout.
         */
        public boolean shouldPlayWhenVibratorComplete(int vibratorId) {
            return false;
        }

        /** Returns the time in millis to wait before playing this step. */
        public long calculateWaitTime() {
            if (startTime == Long.MAX_VALUE) {
                // This step don't have a predefined start time, it's just marked to be executed
                // after all other steps have finished.
                return 0;
            }
            return Math.max(0, startTime - SystemClock.uptimeMillis());
        }

        @Override
        public int compareTo(Step o) {
            return Long.compare(startTime, o.startTime);
        }
    }

    /**
     * Starts a sync vibration.
     *
     * <p>If this step has successfully started playing a vibration on any vibrator, it will always
     * add a {@link FinishVibrateStep} to the queue, to be played after all vibrators have finished
     * all their individual steps.
     *
     * <o>If this step does not start any vibrator, it will add a {@link StartVibrateStep} if the
     * sequential effect isn't finished yet.
     */
    private final class StartVibrateStep extends Step {
        public final CombinedVibrationEffect.Sequential sequentialEffect;
        public final int currentIndex;

        StartVibrateStep(CombinedVibrationEffect.Sequential effect) {
            this(SystemClock.uptimeMillis() + effect.getDelays().get(0), effect, /* index= */ 0);
        }

        StartVibrateStep(long startTime, CombinedVibrationEffect.Sequential effect, int index) {
            super(startTime);
            sequentialEffect = effect;
            currentIndex = index;
        }

        @Override
        public List<Step> play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "StartVibrateStep");
            List<Step> nextSteps = new ArrayList<>();
            long duration = -1;
            try {
                if (DEBUG) {
                    Slog.d(TAG, "StartVibrateStep for effect #" + currentIndex);
                }
                CombinedVibrationEffect effect = sequentialEffect.getEffects().get(currentIndex);
                DeviceEffectMap effectMapping = createEffectToVibratorMapping(effect);
                if (effectMapping == null) {
                    // Unable to map effects to vibrators, ignore this step.
                    return nextSteps;
                }

                duration = startVibrating(effectMapping, nextSteps);
                noteVibratorOn(duration);
            } finally {
                // If this step triggered any vibrator then add a finish step to wait for all
                // active vibrators to finish their individual steps before going to the next.
                Step nextStep = duration > 0 ? new FinishVibrateStep(this) : nextStep();
                if (nextStep != null) {
                    nextSteps.add(nextStep);
                }
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
            return nextSteps;
        }

        /**
         * Create the next {@link StartVibrateStep} to play this sequential effect, starting at the
         * time this method is called, or null if sequence is complete.
         */
        @Nullable
        private Step nextStep() {
            int nextIndex = currentIndex + 1;
            if (nextIndex >= sequentialEffect.getEffects().size()) {
                return null;
            }
            long nextEffectDelay = sequentialEffect.getDelays().get(nextIndex);
            long nextStartTime = SystemClock.uptimeMillis() + nextEffectDelay;
            return new StartVibrateStep(nextStartTime, sequentialEffect, nextIndex);
        }

        /** Create a mapping of individual {@link VibrationEffect} to available vibrators. */
        @Nullable
        private DeviceEffectMap createEffectToVibratorMapping(
                CombinedVibrationEffect effect) {
            if (effect instanceof CombinedVibrationEffect.Mono) {
                return new DeviceEffectMap((CombinedVibrationEffect.Mono) effect);
            }
            if (effect instanceof CombinedVibrationEffect.Stereo) {
                return new DeviceEffectMap((CombinedVibrationEffect.Stereo) effect);
            }
            return null;
        }

        /**
         * Starts playing effects on designated vibrators, in sync.
         *
         * @param effectMapping The {@link CombinedVibrationEffect} mapped to this device vibrators
         * @param nextSteps     An output list to accumulate the future {@link Step Steps} created
         *                      by this method, typically one for each vibrator that has
         *                      successfully started vibrating on this step.
         * @return The duration, in millis, of the {@link CombinedVibrationEffect}. Repeating
         * waveforms return {@link Long#MAX_VALUE}. Zero or negative values indicate the vibrators
         * have ignored all effects.
         */
        private long startVibrating(DeviceEffectMap effectMapping, List<Step> nextSteps) {
            int vibratorCount = effectMapping.size();
            if (vibratorCount == 0) {
                // No effect was mapped to any available vibrator.
                return 0;
            }

            VibratorOnStep[] steps = new VibratorOnStep[vibratorCount];
            long vibrationStartTime = SystemClock.uptimeMillis();
            for (int i = 0; i < vibratorCount; i++) {
                steps[i] = new VibratorOnStep(vibrationStartTime,
                        mVibrators.get(effectMapping.vibratorIdAt(i)), effectMapping.effectAt(i));
            }

            if (steps.length == 1) {
                // No need to prepare and trigger sync effects on a single vibrator.
                return startVibrating(steps[0], nextSteps);
            }

            // This synchronization of vibrators should be executed one at a time, even if we are
            // vibrating different sets of vibrators in parallel. The manager can only prepareSynced
            // one set of vibrators at a time.
            synchronized (mLock) {
                boolean hasPrepared = false;
                boolean hasTriggered = false;
                try {
                    hasPrepared = mCallbacks.prepareSyncedVibration(
                            effectMapping.getRequiredSyncCapabilities(),
                            effectMapping.getVibratorIds());

                    long duration = 0;
                    for (VibratorOnStep step : steps) {
                        duration = Math.max(duration, startVibrating(step, nextSteps));
                    }

                    // Check if sync was prepared and if any step was accepted by a vibrator,
                    // otherwise there is nothing to trigger here.
                    if (hasPrepared && duration > 0) {
                        hasTriggered = mCallbacks.triggerSyncedVibration(mVibration.id);
                    }
                    return duration;
                } finally {
                    if (hasPrepared && !hasTriggered) {
                        // Trigger has failed or all steps were ignored by the vibrators.
                        mCallbacks.cancelSyncedVibration();
                        return 0;
                    }
                }
            }
        }

        private long startVibrating(VibratorOnStep step, List<Step> nextSteps) {
            nextSteps.addAll(step.play());
            return step.getDuration();
        }
    }

    /**
     * Finish a sync vibration started by a {@link StartVibrateStep}.
     *
     * <p>This only plays after all active vibrators steps have finished, and adds a {@link
     * StartVibrateStep} to the queue if the sequential effect isn't finished yet.
     */
    private final class FinishVibrateStep extends Step {
        public final StartVibrateStep startedStep;

        FinishVibrateStep(StartVibrateStep startedStep) {
            super(Long.MAX_VALUE); // No predefined startTime, just wait for all steps in the queue.
            this.startedStep = startedStep;
        }

        @Override
        public List<Step> play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "FinishVibrateStep");
            try {
                if (DEBUG) {
                    Slog.d(TAG, "FinishVibrateStep for effect #" + startedStep.currentIndex);
                }
                noteVibratorOff();
                Step nextStep = startedStep.nextStep();
                return nextStep == null ? EMPTY_STEP_LIST : Arrays.asList(nextStep);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        @Override
        public void cancel() {
            noteVibratorOff();
        }
    }

    /**
     * Represent a step turn the vibrator on.
     *
     * <p>No other calls to the vibrator is made from this step, so this can be played in between
     * calls to 'prepare' and 'trigger' for synchronized vibrations.
     */
    private final class VibratorOnStep extends Step {
        public final VibratorController controller;
        public final VibrationEffect effect;
        private long mDuration;

        VibratorOnStep(long startTime, VibratorController controller, VibrationEffect effect) {
            super(startTime);
            this.controller = controller;
            this.effect = effect;
        }

        /**
         * Return the duration, in millis, of this effect. Repeating waveforms return {@link
         * Long#MAX_VALUE}. Zero or negative values indicate the vibrator has ignored this effect.
         */
        public long getDuration() {
            return mDuration;
        }

        @Override
        public List<Step> play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "VibratorOnStep");
            try {
                if (DEBUG) {
                    Slog.d(TAG, "Turning on vibrator " + controller.getVibratorInfo().getId());
                }
                List<Step> nextSteps = new ArrayList<>();
                mDuration = startVibrating(effect, nextSteps);
                return nextSteps;
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        private long startVibrating(VibrationEffect effect, List<Step> nextSteps) {
            // TODO(b/167947076): split this into 4 different step implementations:
            // VibratorPerformStep, VibratorComposePrimitiveStep, VibratorComposePwleStep and
            // VibratorAmplitudeStep.
            // Make sure each step carries over the full VibrationEffect and an incremental segment
            // index, and triggers a final VibratorOffStep once all segments are done.
            VibrationEffect.Composed composed = (VibrationEffect.Composed) effect;
            VibrationEffectSegment firstSegment = composed.getSegments().get(0);
            final long duration;
            final long now = SystemClock.uptimeMillis();
            if (firstSegment instanceof StepSegment) {
                // Return the full duration of this waveform effect.
                duration = effect.getDuration();
                long onDuration = getVibratorOnDuration(composed, 0);
                if (onDuration > 0) {
                    // Do NOT set amplitude here. This might be called between prepareSynced and
                    // triggerSynced, so the vibrator is not actually turned on here.
                    // The next steps will handle the amplitudes after the vibrator has turned on.
                    controller.on(onDuration, mVibration.id);
                }
                long offTime = onDuration > 0 ? now + onDuration + CALLBACKS_EXTRA_TIMEOUT : now;
                nextSteps.add(new VibratorAmplitudeStep(now, controller, composed, offTime));
            } else if (firstSegment instanceof PrebakedSegment) {
                PrebakedSegment prebaked = (PrebakedSegment) firstSegment;
                VibrationEffect fallback = mVibration.getFallback(prebaked.getEffectId());
                duration = controller.on(prebaked, mVibration.id);
                if (duration > 0) {
                    nextSteps.add(new VibratorOffStep(now + duration + CALLBACKS_EXTRA_TIMEOUT,
                            controller));
                } else if (prebaked.shouldFallback() && fallback != null) {
                    return startVibrating(fallback, nextSteps);
                }
            } else if (firstSegment instanceof PrimitiveSegment) {
                int segmentCount = composed.getSegments().size();
                PrimitiveSegment[] primitives = new PrimitiveSegment[segmentCount];
                for (int i = 0; i < segmentCount; i++) {
                    VibrationEffectSegment segment = composed.getSegments().get(i);
                    if (segment instanceof PrimitiveSegment) {
                        primitives[i] = (PrimitiveSegment) segment;
                    } else {
                        primitives[i] = new PrimitiveSegment(
                                VibrationEffect.Composition.PRIMITIVE_NOOP,
                                /* scale= */ 1, /* delay= */ 0);
                    }
                }
                duration = controller.on(primitives, mVibration.id);
                if (duration > 0) {
                    nextSteps.add(new VibratorOffStep(now + duration + CALLBACKS_EXTRA_TIMEOUT,
                            controller));
                }
            } else if (firstSegment instanceof RampSegment) {
                int segmentCount = composed.getSegments().size();
                RampSegment[] primitives = new RampSegment[segmentCount];
                for (int i = 0; i < segmentCount; i++) {
                    VibrationEffectSegment segment = composed.getSegments().get(i);
                    if (segment instanceof RampSegment) {
                        primitives[i] = (RampSegment) segment;
                    } else if (segment instanceof StepSegment) {
                        StepSegment stepSegment = (StepSegment) segment;
                        primitives[i] = new RampSegment(
                                stepSegment.getAmplitude(), stepSegment.getAmplitude(),
                                stepSegment.getFrequency(), stepSegment.getFrequency(),
                                (int) stepSegment.getDuration());
                    } else {
                        primitives[i] = new RampSegment(0, 0, 0, 0, 0);
                    }
                }
                duration = controller.on(primitives, mVibration.id);
                if (duration > 0) {
                    nextSteps.add(new VibratorOffStep(now + duration + CALLBACKS_EXTRA_TIMEOUT,
                            controller));
                }
            } else {
                duration = 0;
            }
            return duration;
        }
    }

    /**
     * Represents a step to turn the vibrator off.
     *
     * <p>This runs after a timeout on the expected time the vibrator should have finished playing,
     * and can anticipated by vibrator complete callbacks.
     */
    private final class VibratorOffStep extends Step {
        public final VibratorController controller;

        VibratorOffStep(long startTime, VibratorController controller) {
            super(startTime);
            this.controller = controller;
        }

        @Override
        public boolean shouldPlayWhenVibratorComplete(int vibratorId) {
            return controller.getVibratorInfo().getId() == vibratorId;
        }

        @Override
        public List<Step> play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "VibratorOffStep");
            try {
                stopVibrating();
                return EMPTY_STEP_LIST;
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        @Override
        public void cancel() {
            stopVibrating();
        }

        private void stopVibrating() {
            if (DEBUG) {
                Slog.d(TAG, "Turning off vibrator " + controller.getVibratorInfo().getId());
            }
            controller.off();
        }
    }

    /** Represents a step to change the amplitude of the vibrator. */
    private final class VibratorAmplitudeStep extends Step {
        public final VibratorController controller;
        public final VibrationEffect.Composed effect;
        public final int currentIndex;

        private long mNextVibratorStopTime;

        VibratorAmplitudeStep(long startTime, VibratorController controller,
                VibrationEffect.Composed effect, long expectedVibratorStopTime) {
            this(startTime, controller, effect, /* index= */ 0, expectedVibratorStopTime);
        }

        VibratorAmplitudeStep(long startTime, VibratorController controller,
                VibrationEffect.Composed effect, int index, long expectedVibratorStopTime) {
            super(startTime);
            this.controller = controller;
            this.effect = effect;
            this.currentIndex = index;
            mNextVibratorStopTime = expectedVibratorStopTime;
        }

        @Override
        public boolean shouldPlayWhenVibratorComplete(int vibratorId) {
            if (controller.getVibratorInfo().getId() == vibratorId) {
                mNextVibratorStopTime = SystemClock.uptimeMillis();
            }
            return false;
        }

        @Override
        public List<Step> play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "VibratorAmplitudeStep");
            try {
                if (DEBUG) {
                    long latency = SystemClock.uptimeMillis() - startTime;
                    Slog.d(TAG, "Running amplitude step with " + latency + "ms latency.");
                }
                VibrationEffectSegment segment = effect.getSegments().get(currentIndex);
                if (!(segment instanceof StepSegment)) {
                    return nextSteps();
                }
                StepSegment stepSegment = (StepSegment) segment;
                if (stepSegment.getDuration() == 0) {
                    // Skip waveform entries with zero timing.
                    return nextSteps();
                }
                float amplitude = stepSegment.getAmplitude();
                if (amplitude == 0) {
                    stopVibrating();
                    return nextSteps();
                }
                if (startTime >= mNextVibratorStopTime) {
                    // Vibrator has stopped. Turn vibrator back on for the duration of another
                    // cycle before setting the amplitude.
                    long onDuration = getVibratorOnDuration(effect, currentIndex);
                    if (onDuration > 0) {
                        startVibrating(onDuration);
                        mNextVibratorStopTime =
                                SystemClock.uptimeMillis() + onDuration + CALLBACKS_EXTRA_TIMEOUT;
                    }
                }
                changeAmplitude(amplitude);
                return nextSteps();
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        @Override
        public void cancel() {
            stopVibrating();
        }

        private void stopVibrating() {
            if (DEBUG) {
                Slog.d(TAG, "Turning off vibrator " + controller.getVibratorInfo().getId());
            }
            controller.off();
            mNextVibratorStopTime = SystemClock.uptimeMillis();
        }

        private void startVibrating(long duration) {
            if (DEBUG) {
                Slog.d(TAG, "Turning on vibrator " + controller.getVibratorInfo().getId() + " for "
                        + duration + "ms");
            }
            controller.on(duration, mVibration.id);
        }

        private void changeAmplitude(float amplitude) {
            if (DEBUG) {
                Slog.d(TAG, "Amplitude changed on vibrator " + controller.getVibratorInfo().getId()
                        + " to " + amplitude);
            }
            controller.setAmplitude(amplitude);
        }

        @NonNull
        private List<Step> nextSteps() {
            long nextStartTime = startTime + effect.getSegments().get(currentIndex).getDuration();
            int nextIndex = currentIndex + 1;
            if (nextIndex >= effect.getSegments().size()) {
                nextIndex = effect.getRepeatIndex();
            }
            Step nextStep = nextIndex < 0
                    ? new VibratorOffStep(nextStartTime, controller)
                    : new VibratorAmplitudeStep(nextStartTime, controller, effect, nextIndex,
                            mNextVibratorStopTime);
            return Arrays.asList(nextStep);
        }
    }

    /**
     * Map a {@link CombinedVibrationEffect} to the vibrators available on the device.
     *
     * <p>This contains the logic to find the capabilities required from {@link IVibratorManager} to
     * play all of the effects in sync.
     */
    private final class DeviceEffectMap {
        private final SparseArray<VibrationEffect> mVibratorEffects;
        private final int[] mVibratorIds;
        private final long mRequiredSyncCapabilities;

        DeviceEffectMap(CombinedVibrationEffect.Mono mono) {
            mVibratorEffects = new SparseArray<>(mVibrators.size());
            mVibratorIds = new int[mVibrators.size()];
            for (int i = 0; i < mVibrators.size(); i++) {
                int vibratorId = mVibrators.keyAt(i);
                VibratorInfo vibratorInfo = mVibrators.valueAt(i).getVibratorInfo();
                VibrationEffect effect = mDeviceEffectAdapter.apply(mono.getEffect(), vibratorInfo);
                mVibratorEffects.put(vibratorId, effect);
                mVibratorIds[i] = vibratorId;
            }
            mRequiredSyncCapabilities = calculateRequiredSyncCapabilities(mVibratorEffects);
        }

        DeviceEffectMap(CombinedVibrationEffect.Stereo stereo) {
            SparseArray<VibrationEffect> stereoEffects = stereo.getEffects();
            mVibratorEffects = new SparseArray<>();
            for (int i = 0; i < stereoEffects.size(); i++) {
                int vibratorId = stereoEffects.keyAt(i);
                if (mVibrators.contains(vibratorId)) {
                    VibratorInfo vibratorInfo = mVibrators.valueAt(i).getVibratorInfo();
                    VibrationEffect effect = mDeviceEffectAdapter.apply(
                            stereoEffects.valueAt(i), vibratorInfo);
                    mVibratorEffects.put(vibratorId, effect);
                }
            }
            mVibratorIds = new int[mVibratorEffects.size()];
            for (int i = 0; i < mVibratorEffects.size(); i++) {
                mVibratorIds[i] = mVibratorEffects.keyAt(i);
            }
            mRequiredSyncCapabilities = calculateRequiredSyncCapabilities(mVibratorEffects);
        }

        /**
         * Return the number of vibrators mapped to play the {@link CombinedVibrationEffect} on this
         * device.
         */
        public int size() {
            return mVibratorIds.length;
        }

        /**
         * Return all capabilities required to play the {@link CombinedVibrationEffect} in
         * between calls to {@link IVibratorManager#prepareSynced} and
         * {@link IVibratorManager#triggerSynced}.
         */
        public long getRequiredSyncCapabilities() {
            return mRequiredSyncCapabilities;
        }

        /** Return all vibrator ids mapped to play the {@link CombinedVibrationEffect}. */
        public int[] getVibratorIds() {
            return mVibratorIds;
        }

        /** Return the id of the vibrator at given index. */
        public int vibratorIdAt(int index) {
            return mVibratorEffects.keyAt(index);
        }

        /** Return the {@link VibrationEffect} at given index. */
        public VibrationEffect effectAt(int index) {
            return mVibratorEffects.valueAt(index);
        }

        /**
         * Return all capabilities required from the {@link IVibratorManager} to prepare and
         * trigger all given effects in sync.
         *
         * @return {@link IVibratorManager#CAP_SYNC} together with all required
         * IVibratorManager.CAP_PREPARE_* and IVibratorManager.CAP_MIXED_TRIGGER_* capabilities.
         */
        private long calculateRequiredSyncCapabilities(SparseArray<VibrationEffect> effects) {
            long prepareCap = 0;
            for (int i = 0; i < effects.size(); i++) {
                VibrationEffect.Composed composed = (VibrationEffect.Composed) effects.valueAt(i);
                VibrationEffectSegment firstSegment = composed.getSegments().get(0);
                if (firstSegment instanceof StepSegment) {
                    prepareCap |= IVibratorManager.CAP_PREPARE_ON;
                } else if (firstSegment instanceof PrebakedSegment) {
                    prepareCap |= IVibratorManager.CAP_PREPARE_PERFORM;
                } else if (firstSegment instanceof PrimitiveSegment) {
                    prepareCap |= IVibratorManager.CAP_PREPARE_COMPOSE;
                }
            }
            int triggerCap = 0;
            if (requireMixedTriggerCapability(prepareCap, IVibratorManager.CAP_PREPARE_ON)) {
                triggerCap |= IVibratorManager.CAP_MIXED_TRIGGER_ON;
            }
            if (requireMixedTriggerCapability(prepareCap, IVibratorManager.CAP_PREPARE_PERFORM)) {
                triggerCap |= IVibratorManager.CAP_MIXED_TRIGGER_PERFORM;
            }
            if (requireMixedTriggerCapability(prepareCap, IVibratorManager.CAP_PREPARE_COMPOSE)) {
                triggerCap |= IVibratorManager.CAP_MIXED_TRIGGER_COMPOSE;
            }
            return IVibratorManager.CAP_SYNC | prepareCap | triggerCap;
        }

        /**
         * Return true if {@code prepareCapabilities} contains this {@code capability} mixed with
         * different ones, requiring a mixed trigger capability from the vibrator manager for
         * syncing all effects.
         */
        private boolean requireMixedTriggerCapability(long prepareCapabilities, long capability) {
            return (prepareCapabilities & capability) != 0
                    && (prepareCapabilities & ~capability) != 0;
        }
    }
}
