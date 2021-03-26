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

    @Nullable
    private SingleVibratorStep nextVibrateStep(long startTime, VibratorController controller,
            VibrationEffect.Composed effect, int segmentIndex, long vibratorOffTimeout) {
        // Some steps should only start after the vibrator has finished the previous vibration, so
        // make sure we take the latest between both timings.
        long latestStartTime = Math.max(startTime, vibratorOffTimeout);
        if (segmentIndex >= effect.getSegments().size()) {
            segmentIndex = effect.getRepeatIndex();
        }
        if (segmentIndex < 0) {
            if (vibratorOffTimeout > SystemClock.uptimeMillis()) {
                // No more segments to play, last step is to wait for the vibrator to complete
                return new OffStep(vibratorOffTimeout, controller);
            } else {
                return null;
            }
        }

        VibrationEffectSegment segment = effect.getSegments().get(segmentIndex);
        if (segment instanceof PrebakedSegment) {
            return new PerformStep(latestStartTime, controller, effect, segmentIndex,
                    vibratorOffTimeout);
        }
        if (segment instanceof PrimitiveSegment) {
            return new ComposePrimitivesStep(latestStartTime, controller, effect, segmentIndex,
                    vibratorOffTimeout);
        }
        if (segment instanceof RampSegment) {
            // TODO(b/167947076): check capabilities to play steps with PWLE once APIs introduced
            return new ComposePwleStep(latestStartTime, controller, effect, segmentIndex,
                    vibratorOffTimeout);
        }
        return new AmplitudeStep(startTime, controller, effect, segmentIndex, vibratorOffTimeout);
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
                if (duration < 0) {
                    // Something failed while playing this step so stop playing this sequence.
                    return EMPTY_STEP_LIST;
                }
                // It least one vibrator was started then add a finish step to wait for all
                // active vibrators to finish their individual steps before going to the next.
                // Otherwise this step was ignored so just go to the next one.
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

            SingleVibratorStep[] steps = new SingleVibratorStep[vibratorCount];
            long vibrationStartTime = SystemClock.uptimeMillis();
            for (int i = 0; i < vibratorCount; i++) {
                steps[i] = nextVibrateStep(vibrationStartTime,
                        mVibrators.get(effectMapping.vibratorIdAt(i)),
                        effectMapping.effectAt(i),
                        /* segmentIndex= */ 0, /* vibratorOffTimeout= */ 0);
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
                long maxDuration = 0;
                try {
                    hasPrepared = mCallbacks.prepareSyncedVibration(
                            effectMapping.getRequiredSyncCapabilities(),
                            effectMapping.getVibratorIds());

                    for (SingleVibratorStep step : steps) {
                        long duration = startVibrating(step, nextSteps);
                        if (duration < 0) {
                            // One vibrator has failed, fail this entire sync attempt.
                            return maxDuration = -1;
                        }
                        maxDuration = Math.max(maxDuration, duration);
                    }

                    // Check if sync was prepared and if any step was accepted by a vibrator,
                    // otherwise there is nothing to trigger here.
                    if (hasPrepared && maxDuration > 0) {
                        hasTriggered = mCallbacks.triggerSyncedVibration(mVibration.id);
                    }
                    return maxDuration;
                } finally {
                    if (hasPrepared && !hasTriggered) {
                        // Trigger has failed or all steps were ignored by the vibrators.
                        mCallbacks.cancelSyncedVibration();
                        nextSteps.clear();
                    } else if (maxDuration < 0) {
                        // Some vibrator failed without being prepared so other vibrators might be
                        // active. Cancel and remove every pending step from output list.
                        for (int i = nextSteps.size() - 1; i >= 0; i--) {
                            nextSteps.remove(i).cancel();
                        }
                    }
                }
            }
        }

        private long startVibrating(SingleVibratorStep step, List<Step> nextSteps) {
            nextSteps.addAll(step.play());
            long stepDuration = step.getOnResult();
            if (stepDuration < 0) {
                // Step failed, so return negative duration to propagate failure.
                return stepDuration;
            }
            // Return the longest estimation for the entire effect.
            return Math.max(stepDuration, step.effect.getDuration());
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
     * Represent a step on a single vibrator that plays one or more segments from a
     * {@link VibrationEffect.Composed} effect.
     */
    private abstract class SingleVibratorStep extends Step {
        public final VibratorController controller;
        public final VibrationEffect.Composed effect;
        public final int segmentIndex;
        public final long vibratorOffTimeout;

        long mVibratorOnResult;

        /**
         * @param startTime          The time to schedule this step in the {@link StepQueue}.
         * @param controller         The vibrator that is playing the effect.
         * @param effect             The effect being played in this step.
         * @param index              The index of the next segment to be played by this step
         * @param vibratorOffTimeout The time the vibrator is expected to complete any previous
         *                           vibration and turn off. This is used to allow this step to be
         *                           anticipated when the completion callback is triggered, and can
         *                           be used play effects back-to-back.
         */
        SingleVibratorStep(long startTime, VibratorController controller,
                VibrationEffect.Composed effect, int index, long vibratorOffTimeout) {
            super(startTime);
            this.controller = controller;
            this.effect = effect;
            this.segmentIndex = index;
            this.vibratorOffTimeout = vibratorOffTimeout;
        }

        /**
         * Return the result a call to {@link VibratorController#on} method triggered by
         * {@link #play()}.
         *
         * @return A positive duration that the vibrator was turned on for by this step;
         * Zero if the segment is not supported or vibrator was never turned on;
         * A negative value if the vibrator call has failed.
         */
        public long getOnResult() {
            return mVibratorOnResult;
        }

        @Override
        public boolean shouldPlayWhenVibratorComplete(int vibratorId) {
            // Only anticipate this step if a timeout was set to wait for the vibration to complete,
            // otherwise we are waiting for the correct time to play the next step.
            return (controller.getVibratorInfo().getId() == vibratorId)
                    && (vibratorOffTimeout > SystemClock.uptimeMillis());
        }

        @Override
        public void cancel() {
            if (vibratorOffTimeout > SystemClock.uptimeMillis()) {
                // Vibrator might be running from previous steps, so turn it off while canceling.
                stopVibrating();
            }
        }

        void stopVibrating() {
            if (DEBUG) {
                Slog.d(TAG, "Turning off vibrator " + controller.getVibratorInfo().getId());
            }
            controller.off();
        }

        /** Return the {@link #nextVibrateStep} with same timings, only jumping the segments. */
        public List<Step> skipToNextSteps(int segmentsSkipped) {
            return nextSteps(startTime, vibratorOffTimeout, segmentsSkipped);
        }

        /**
         * Return the {@link #nextVibrateStep} with same start and off timings calculated from
         * {@link #getOnResult()}, jumping all played segments.
         *
         * <p>This method has same behavior as {@link #skipToNextSteps(int)} when the vibrator
         * result is non-positive, meaning the vibrator has either ignored or failed to turn on.
         */
        public List<Step> nextSteps(int segmentsPlayed) {
            if (mVibratorOnResult <= 0) {
                // Vibration was not started, so just skip the played segments and keep timings.
                return skipToNextSteps(segmentsPlayed);
            }
            long nextVibratorOffTimeout =
                    SystemClock.uptimeMillis() + mVibratorOnResult + CALLBACKS_EXTRA_TIMEOUT;
            return nextSteps(nextVibratorOffTimeout, nextVibratorOffTimeout, segmentsPlayed);
        }

        /**
         * Return the {@link #nextVibrateStep} with given start and off timings, which might be
         * calculated independently, jumping all played segments.
         *
         * <p>This should be used when the vibrator on/off state is not responsible for the steps
         * execution timings, e.g. while playing the vibrator amplitudes.
         */
        public List<Step> nextSteps(long nextStartTime, long vibratorOffTimeout,
                int segmentsPlayed) {
            Step nextStep = nextVibrateStep(nextStartTime, controller, effect,
                    segmentIndex + segmentsPlayed, vibratorOffTimeout);
            return nextStep == null ? EMPTY_STEP_LIST : Arrays.asList(nextStep);
        }
    }

    /**
     * Represent a step turn the vibrator on with a single prebaked effect.
     *
     * <p>This step automatically falls back by replacing the prebaked segment with
     * {@link VibrationSettings#getFallbackEffect(int)}, if available.
     */
    private final class PerformStep extends SingleVibratorStep {

        PerformStep(long startTime, VibratorController controller,
                VibrationEffect.Composed effect, int index, long vibratorOffTimeout) {
            super(startTime, controller, effect, index, vibratorOffTimeout);
        }

        @Override
        public List<Step> play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "PerformStep");
            try {
                VibrationEffectSegment segment = effect.getSegments().get(segmentIndex);
                if (!(segment instanceof PrebakedSegment)) {
                    Slog.w(TAG, "Ignoring wrong segment for a PerformStep: " + segment);
                    return skipToNextSteps(/* segmentsSkipped= */ 1);
                }

                PrebakedSegment prebaked = (PrebakedSegment) segment;
                if (DEBUG) {
                    Slog.d(TAG, "Perform " + VibrationEffect.effectIdToString(
                            prebaked.getEffectId()) + " on vibrator "
                            + controller.getVibratorInfo().getId());
                }

                VibrationEffect fallback = mVibration.getFallback(prebaked.getEffectId());
                mVibratorOnResult = controller.on(prebaked, mVibration.id);

                if (mVibratorOnResult == 0 && prebaked.shouldFallback()
                        && (fallback instanceof VibrationEffect.Composed)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Playing fallback for effect "
                                + VibrationEffect.effectIdToString(prebaked.getEffectId()));
                    }
                    SingleVibratorStep fallbackStep = nextVibrateStep(startTime, controller,
                            replaceCurrentSegment((VibrationEffect.Composed) fallback),
                            segmentIndex, vibratorOffTimeout);
                    List<Step> fallbackResult = fallbackStep.play();
                    // Update the result with the fallback result so this step is seamlessly
                    // replaced by the fallback to any outer application of this.
                    mVibratorOnResult = fallbackStep.getOnResult();
                    return fallbackResult;
                }

                return nextSteps(/* segmentsPlayed= */ 1);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        /**
         * Replace segment at {@link #segmentIndex} in {@link #effect} with given fallback segments.
         *
         * @return a copy of {@link #effect} with replaced segment.
         */
        private VibrationEffect.Composed replaceCurrentSegment(VibrationEffect.Composed fallback) {
            List<VibrationEffectSegment> newSegments = new ArrayList<>(effect.getSegments());
            int newRepeatIndex = effect.getRepeatIndex();
            newSegments.remove(segmentIndex);
            newSegments.addAll(segmentIndex, fallback.getSegments());
            if (segmentIndex < effect.getRepeatIndex()) {
                newRepeatIndex += fallback.getSegments().size();
            }
            return new VibrationEffect.Composed(newSegments, newRepeatIndex);
        }
    }

    /**
     * Represent a step turn the vibrator on using a composition of primitives.
     *
     * <p>This step will use the maximum supported number of consecutive segments of type
     * {@link PrimitiveSegment} starting at the current index.
     */
    private final class ComposePrimitivesStep extends SingleVibratorStep {

        ComposePrimitivesStep(long startTime, VibratorController controller,
                VibrationEffect.Composed effect, int index, long vibratorOffTimeout) {
            super(startTime, controller, effect, index, vibratorOffTimeout);
        }

        @Override
        public List<Step> play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "ComposePrimitivesStep");
            try {
                int segmentCount = effect.getSegments().size();
                List<PrimitiveSegment> primitives = new ArrayList<>();
                for (int i = segmentIndex; i < segmentCount; i++) {
                    VibrationEffectSegment segment = effect.getSegments().get(i);
                    if (segment instanceof PrimitiveSegment) {
                        primitives.add((PrimitiveSegment) segment);
                    } else {
                        break;
                    }
                }

                if (primitives.isEmpty()) {
                    Slog.w(TAG, "Ignoring wrong segment for a ComposePrimitivesStep: "
                            + effect.getSegments().get(segmentIndex));
                    return skipToNextSteps(/* segmentsSkipped= */ 1);
                }

                if (DEBUG) {
                    Slog.d(TAG, "Compose " + primitives.size() + " primitives on vibrator "
                            + controller.getVibratorInfo().getId());
                }
                mVibratorOnResult = controller.on(
                        primitives.toArray(new PrimitiveSegment[primitives.size()]),
                        mVibration.id);

                return nextSteps(/* segmntsPlayed= */ primitives.size());
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }
    }

    /**
     * Represent a step turn the vibrator on using a composition of PWLE segments.
     *
     * <p>This step will use the maximum supported number of consecutive segments of type
     * {@link StepSegment} or {@link RampSegment} starting at the current index.
     */
    private final class ComposePwleStep extends SingleVibratorStep {

        ComposePwleStep(long startTime, VibratorController controller,
                VibrationEffect.Composed effect, int index, long vibratorOffTimeout) {
            super(startTime, controller, effect, index, vibratorOffTimeout);
        }

        @Override
        public List<Step> play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "ComposePwleStep");
            try {
                int segmentCount = effect.getSegments().size();
                List<RampSegment> pwles = new ArrayList<>();
                for (int i = segmentIndex; i < segmentCount; i++) {
                    VibrationEffectSegment segment = effect.getSegments().get(i);
                    if (segment instanceof RampSegment) {
                        pwles.add((RampSegment) segment);
                    } else if (segment instanceof StepSegment) {
                        StepSegment stepSegment = (StepSegment) segment;
                        pwles.add(new RampSegment(stepSegment.getAmplitude(),
                                stepSegment.getAmplitude(), stepSegment.getFrequency(),
                                stepSegment.getFrequency(), (int) stepSegment.getDuration()));
                    } else {
                        break;
                    }
                }

                if (pwles.isEmpty()) {
                    Slog.w(TAG, "Ignoring wrong segment for a ComposePwleStep: "
                            + effect.getSegments().get(segmentIndex));
                    return skipToNextSteps(/* segmentsSkipped= */ 1);
                }

                if (DEBUG) {
                    Slog.d(TAG, "Compose " + pwles.size() + " PWLEs on vibrator "
                            + controller.getVibratorInfo().getId());
                }
                mVibratorOnResult = controller.on(pwles.toArray(new RampSegment[pwles.size()]),
                        mVibration.id);

                return nextSteps(/* segmentsPlayed= */ pwles.size());
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }
    }

    /**
     * Represents a step to turn the vibrator off.
     *
     * <p>This runs after a timeout on the expected time the vibrator should have finished playing,
     * and can anticipated by vibrator complete callbacks.
     */
    private final class OffStep extends SingleVibratorStep {

        OffStep(long startTime, VibratorController controller) {
            super(startTime, controller, /* effect= */ null, /* index= */ -1, startTime);
        }

        @Override
        public List<Step> play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "OffStep");
            try {
                stopVibrating();
                return EMPTY_STEP_LIST;
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }
    }

    /**
     * Represents a step to turn the vibrator on and change its amplitude.
     *
     * <p>This step ignores vibration completion callbacks and control the vibrator on/off state
     * and amplitude to simulate waveforms represented by a sequence of {@link StepSegment}.
     */
    private final class AmplitudeStep extends SingleVibratorStep {
        private long mNextOffTime;

        AmplitudeStep(long startTime, VibratorController controller,
                VibrationEffect.Composed effect, int index, long vibratorOffTimeout) {
            super(startTime, controller, effect, index, vibratorOffTimeout);
            mNextOffTime = vibratorOffTimeout;
        }

        @Override
        public boolean shouldPlayWhenVibratorComplete(int vibratorId) {
            if (controller.getVibratorInfo().getId() == vibratorId) {
                mNextOffTime = SystemClock.uptimeMillis();
            }
            // Timings are tightly controlled here, so never anticipate when vibrator is complete.
            return false;
        }

        @Override
        public List<Step> play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "AmplitudeStep");
            try {
                if (DEBUG) {
                    long latency = SystemClock.uptimeMillis() - startTime;
                    Slog.d(TAG, "Running amplitude step with " + latency + "ms latency.");
                }

                VibrationEffectSegment segment = effect.getSegments().get(segmentIndex);
                if (!(segment instanceof StepSegment)) {
                    Slog.w(TAG, "Ignoring wrong segment for a AmplitudeStep: " + segment);
                    return skipToNextSteps(/* segmentsSkipped= */ 1);
                }

                StepSegment stepSegment = (StepSegment) segment;
                if (stepSegment.getDuration() == 0) {
                    // Skip waveform entries with zero timing.
                    return skipToNextSteps(/* segmentsSkipped= */ 1);
                }

                long now = SystemClock.uptimeMillis();
                float amplitude = stepSegment.getAmplitude();
                if (amplitude == 0) {
                    if (mNextOffTime > now) {
                        // Amplitude cannot be set to zero, so stop the vibrator.
                        stopVibrating();
                        mNextOffTime = now;
                    }
                } else {
                    if (startTime >= mNextOffTime) {
                        // Vibrator has stopped. Turn vibrator back on for the duration of another
                        // cycle before setting the amplitude.
                        long onDuration = getVibratorOnDuration(effect, segmentIndex);
                        if (onDuration > 0) {
                            mVibratorOnResult = startVibrating(onDuration);
                            mNextOffTime = now + onDuration + CALLBACKS_EXTRA_TIMEOUT;
                        }
                    }
                    changeAmplitude(amplitude);
                }

                // Use original startTime to avoid propagating latencies to the waveform.
                long nextStartTime = startTime + segment.getDuration();
                return nextSteps(nextStartTime, mNextOffTime, /* segmentsPlayed= */ 1);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        private long startVibrating(long duration) {
            if (DEBUG) {
                Slog.d(TAG, "Turning on vibrator " + controller.getVibratorInfo().getId() + " for "
                        + duration + "ms");
            }
            return controller.on(duration, mVibration.id);
        }

        private void changeAmplitude(float amplitude) {
            if (DEBUG) {
                Slog.d(TAG, "Amplitude changed on vibrator " + controller.getVibratorInfo().getId()
                        + " to " + amplitude);
            }
            controller.setAmplitude(amplitude);
        }

        /**
         * Get the duration the vibrator will be on for a waveform, starting at {@code startIndex}
         * until the next time it's vibrating amplitude is zero or a different type of segment is
         * found.
         */
        private long getVibratorOnDuration(VibrationEffect.Composed effect, int startIndex) {
            List<VibrationEffectSegment> segments = effect.getSegments();
            int segmentCount = segments.size();
            int repeatIndex = effect.getRepeatIndex();
            int i = startIndex;
            long timing = 0;
            while (i < segmentCount) {
                VibrationEffectSegment segment = segments.get(i);
                if (!(segment instanceof StepSegment)
                        || ((StepSegment) segment).getAmplitude() == 0) {
                    break;
                }
                timing += segment.getDuration();
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
    }

    /**
     * Map a {@link CombinedVibrationEffect} to the vibrators available on the device.
     *
     * <p>This contains the logic to find the capabilities required from {@link IVibratorManager} to
     * play all of the effects in sync.
     */
    private final class DeviceEffectMap {
        private final SparseArray<VibrationEffect.Composed> mVibratorEffects;
        private final int[] mVibratorIds;
        private final long mRequiredSyncCapabilities;

        DeviceEffectMap(CombinedVibrationEffect.Mono mono) {
            mVibratorEffects = new SparseArray<>(mVibrators.size());
            mVibratorIds = new int[mVibrators.size()];
            for (int i = 0; i < mVibrators.size(); i++) {
                int vibratorId = mVibrators.keyAt(i);
                VibratorInfo vibratorInfo = mVibrators.valueAt(i).getVibratorInfo();
                VibrationEffect effect = mDeviceEffectAdapter.apply(mono.getEffect(), vibratorInfo);
                if (effect instanceof VibrationEffect.Composed) {
                    mVibratorEffects.put(vibratorId, (VibrationEffect.Composed) effect);
                    mVibratorIds[i] = vibratorId;
                }
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
                    if (effect instanceof VibrationEffect.Composed) {
                        mVibratorEffects.put(vibratorId, (VibrationEffect.Composed) effect);
                    }
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
        public VibrationEffect.Composed effectAt(int index) {
            return mVibratorEffects.valueAt(index);
        }

        /**
         * Return all capabilities required from the {@link IVibratorManager} to prepare and
         * trigger all given effects in sync.
         *
         * @return {@link IVibratorManager#CAP_SYNC} together with all required
         * IVibratorManager.CAP_PREPARE_* and IVibratorManager.CAP_MIXED_TRIGGER_* capabilities.
         */
        private long calculateRequiredSyncCapabilities(
                SparseArray<VibrationEffect.Composed> effects) {
            long prepareCap = 0;
            for (int i = 0; i < effects.size(); i++) {
                VibrationEffectSegment firstSegment = effects.valueAt(i).getSegments().get(0);
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
