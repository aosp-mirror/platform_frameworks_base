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
import android.os.CombinedVibration;
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
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;

/** Plays a {@link Vibration} in dedicated thread. */
final class VibrationThread extends Thread implements IBinder.DeathRecipient {
    private static final String TAG = "VibrationThread";
    private static final boolean DEBUG = false;

    /**
     * Extra timeout added to the end of each vibration step to ensure it finishes even when
     * vibrator callbacks are lost.
     */
    private static final long CALLBACKS_EXTRA_TIMEOUT = 1_000;

    /** Threshold to prevent the ramp off steps from trying to set extremely low amplitudes. */
    private static final float RAMP_OFF_AMPLITUDE_MIN = 1e-3f;

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

        /** Callback triggered when the vibration is complete. */
        void onVibrationCompleted(long vibrationId, Vibration.Status status);

        /** Callback triggered when the vibrators are released after the thread is complete. */
        void onVibratorsReleased();
    }

    private final Object mLock = new Object();
    private final WorkSource mWorkSource;
    private final PowerManager.WakeLock mWakeLock;
    private final IBatteryStats mBatteryStatsService;
    private final VibrationSettings mVibrationSettings;
    private final DeviceVibrationEffectAdapter mDeviceEffectAdapter;
    private final Vibration mVibration;
    private final VibrationCallbacks mCallbacks;
    private final SparseArray<VibratorController> mVibrators = new SparseArray<>();
    private final StepQueue mStepQueue = new StepQueue();

    private volatile boolean mStop;
    private volatile boolean mForceStop;

    VibrationThread(Vibration vib, VibrationSettings vibrationSettings,
            DeviceVibrationEffectAdapter effectAdapter,
            SparseArray<VibratorController> availableVibrators, PowerManager.WakeLock wakeLock,
            IBatteryStats batteryStatsService, VibrationCallbacks callbacks) {
        mVibration = vib;
        mVibrationSettings = vibrationSettings;
        mDeviceEffectAdapter = effectAdapter;
        mCallbacks = callbacks;
        mWorkSource = new WorkSource(mVibration.uid);
        mWakeLock = wakeLock;
        mBatteryStatsService = batteryStatsService;

        CombinedVibration effect = vib.getEffect();
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
        mWakeLock.setWorkSource(mWorkSource);
        mWakeLock.acquire();
        try {
            mVibration.token.linkToDeath(this, 0);
            playVibration();
            mCallbacks.onVibratorsReleased();
        } catch (RemoteException e) {
            Slog.e(TAG, "Error linking vibration to token death", e);
        } finally {
            mVibration.token.unlinkToDeath(this, 0);
            mWakeLock.release();
        }
    }

    /** Cancel current vibration and ramp down the vibrators gracefully. */
    public void cancel() {
        if (mStop) {
            // Already cancelled, running clean-up steps.
            return;
        }
        mStop = true;
        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Vibration cancelled");
            }
            mLock.notify();
        }
    }

    /** Cancel current vibration and shuts off the vibrators immediately. */
    public void cancelImmediately() {
        if (mForceStop) {
            // Already forced the thread to stop, wait for it to finish.
            return;
        }
        mStop = mForceStop = true;
        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Vibration cancelled immediately");
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
                mStepQueue.notifyVibratorComplete(mVibrators.keyAt(i));
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
            mStepQueue.notifyVibratorComplete(vibratorId);
            mLock.notify();
        }
    }

    private void playVibration() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "playVibration");
        try {
            CombinedVibration.Sequential sequentialEffect = toSequential(mVibration.getEffect());
            final int sequentialEffectSize = sequentialEffect.getEffects().size();
            mStepQueue.offer(new StartVibrateStep(sequentialEffect));

            Vibration.Status status = null;
            while (!mStepQueue.isEmpty()) {
                long waitTime;
                synchronized (mLock) {
                    waitTime = mStepQueue.calculateWaitTime();
                    if (waitTime > 0) {
                        try {
                            mLock.wait(waitTime);
                        } catch (InterruptedException e) {
                        }
                    }
                }
                // If we waited, the queue may have changed, so let the loop run again.
                if (waitTime <= 0) {
                    mStepQueue.consumeNext();
                }
                Vibration.Status currentStatus = mStop ? Vibration.Status.CANCELLED
                        : mStepQueue.calculateVibrationStatus(sequentialEffectSize);
                if (status == null && currentStatus != Vibration.Status.RUNNING) {
                    // First time vibration stopped running, start clean-up tasks and notify
                    // callback immediately.
                    status = currentStatus;
                    mCallbacks.onVibrationCompleted(mVibration.id, status);
                    if (status == Vibration.Status.CANCELLED) {
                        mStepQueue.cancel();
                    }
                }
                if (mForceStop) {
                    // Cancel every step and stop playing them right away, even clean-up steps.
                    mStepQueue.cancelImmediately();
                    break;
                }
            }

            if (status == null) {
                status = mStepQueue.calculateVibrationStatus(sequentialEffectSize);
                if (status == Vibration.Status.RUNNING) {
                    Slog.w(TAG, "Something went wrong, step queue completed but vibration status"
                            + " is still RUNNING for vibration " + mVibration.id);
                    status = Vibration.Status.FINISHED;
                }
                mCallbacks.onVibrationCompleted(mVibration.id, status);
            }
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
        if (segmentIndex >= effect.getSegments().size()) {
            segmentIndex = effect.getRepeatIndex();
        }
        if (segmentIndex < 0) {
            // No more segments to play, last step is to complete the vibration on this vibrator.
            return new CompleteStep(startTime, /* cancelled= */ false, controller,
                    vibratorOffTimeout);
        }

        VibrationEffectSegment segment = effect.getSegments().get(segmentIndex);
        if (segment instanceof PrebakedSegment) {
            return new PerformStep(startTime, controller, effect, segmentIndex, vibratorOffTimeout);
        }
        if (segment instanceof PrimitiveSegment) {
            return new ComposePrimitivesStep(startTime, controller, effect, segmentIndex,
                    vibratorOffTimeout);
        }
        if (segment instanceof RampSegment) {
            return new ComposePwleStep(startTime, controller, effect, segmentIndex,
                    vibratorOffTimeout);
        }
        return new AmplitudeStep(startTime, controller, effect, segmentIndex, vibratorOffTimeout);
    }

    private static CombinedVibration.Sequential toSequential(CombinedVibration effect) {
        if (effect instanceof CombinedVibration.Sequential) {
            return (CombinedVibration.Sequential) effect;
        }
        return (CombinedVibration.Sequential) CombinedVibration.startSequential()
                .addNext(effect)
                .combine();
    }

    /** Queue for {@link Step Steps}, sorted by their start time. */
    private final class StepQueue {
        @GuardedBy("mLock")
        private final PriorityQueue<Step> mNextSteps = new PriorityQueue<>();
        @GuardedBy("mLock")
        private final Queue<Step> mPendingOnVibratorCompleteSteps = new LinkedList<>();
        @GuardedBy("mLock")
        private final Queue<Integer> mNotifiedVibrators = new LinkedList<>();

        @GuardedBy("mLock")
        private int mPendingVibrateSteps;
        @GuardedBy("mLock")
        private int mConsumedStartVibrateSteps;
        @GuardedBy("mLock")
        private int mSuccessfulVibratorOnSteps;
        @GuardedBy("mLock")
        private boolean mWaitToProcessVibratorCallbacks;

        public void offer(@NonNull Step step) {
            synchronized (mLock) {
                if (!step.isCleanUp()) {
                    mPendingVibrateSteps++;
                }
                mNextSteps.offer(step);
            }
        }

        public boolean isEmpty() {
            synchronized (mLock) {
                return mPendingOnVibratorCompleteSteps.isEmpty() && mNextSteps.isEmpty();
            }
        }

        /**
         * Calculate the {@link Vibration.Status} based on the current queue state and the expected
         * number of {@link StartVibrateStep} to be played.
         */
        public Vibration.Status calculateVibrationStatus(int expectedStartVibrateSteps) {
            synchronized (mLock) {
                if (mPendingVibrateSteps > 0
                        || mConsumedStartVibrateSteps < expectedStartVibrateSteps) {
                    return Vibration.Status.RUNNING;
                }
                if (mSuccessfulVibratorOnSteps > 0) {
                    return Vibration.Status.FINISHED;
                }
                // If no step was able to turn the vibrator ON successfully.
                return Vibration.Status.IGNORED_UNSUPPORTED;
            }
        }

        /** Returns the time in millis to wait before calling {@link #consumeNext()}. */
        @GuardedBy("mLock")
        public long calculateWaitTime() {
            if (!mPendingOnVibratorCompleteSteps.isEmpty()) {
                // Steps anticipated by vibrator complete callback should be played right away.
                return 0;
            }
            Step nextStep = mNextSteps.peek();
            return nextStep == null ? 0 : nextStep.calculateWaitTime();
        }

        /**
         * Play and remove the step at the top of this queue, and also adds the next steps generated
         * to be played next.
         */
        public void consumeNext() {
            // Vibrator callbacks should wait until the polled step is played and the next steps are
            // added back to the queue, so they can handle the callback.
            markWaitToProcessVibratorCallbacks();
            try {
                Step nextStep = pollNext();
                if (nextStep != null) {
                    // This might turn on the vibrator and have a HAL latency. Execute this outside
                    // any lock to avoid blocking other interactions with the thread.
                    List<Step> nextSteps = nextStep.play();
                    synchronized (mLock) {
                        if (nextStep.getVibratorOnDuration() > 0) {
                            mSuccessfulVibratorOnSteps++;
                        }
                        if (nextStep instanceof StartVibrateStep) {
                            mConsumedStartVibrateSteps++;
                        }
                        if (!nextStep.isCleanUp()) {
                            mPendingVibrateSteps--;
                        }
                        for (int i = 0; i < nextSteps.size(); i++) {
                            mPendingVibrateSteps += nextSteps.get(i).isCleanUp() ? 0 : 1;
                        }
                        mNextSteps.addAll(nextSteps);
                    }
                }
            } finally {
                synchronized (mLock) {
                    processVibratorCallbacks();
                }
            }
        }

        /**
         * Notify the vibrator completion.
         *
         * <p>This is a lightweight method that do not trigger any operation from {@link
         * VibratorController}, so it can be called directly from a native callback.
         */
        @GuardedBy("mLock")
        public void notifyVibratorComplete(int vibratorId) {
            mNotifiedVibrators.offer(vibratorId);
            if (!mWaitToProcessVibratorCallbacks) {
                // No step is being played or cancelled now, process the callback right away.
                processVibratorCallbacks();
            }
        }

        /**
         * Cancel the current queue, replacing all remaining steps with respective clean-up steps.
         *
         * <p>This will remove all steps and replace them with respective
         * {@link Step#cancel()}.
         */
        public void cancel() {
            // Vibrator callbacks should wait until all steps from the queue are properly cancelled
            // and clean up steps are added back to the queue, so they can handle the callback.
            markWaitToProcessVibratorCallbacks();
            try {
                List<Step> cleanUpSteps = new ArrayList<>();
                Step step;
                while ((step = pollNext()) != null) {
                    cleanUpSteps.addAll(step.cancel());
                }
                synchronized (mLock) {
                    // All steps generated by Step.cancel() should be clean-up steps.
                    mPendingVibrateSteps = 0;
                    mNextSteps.addAll(cleanUpSteps);
                }
            } finally {
                synchronized (mLock) {
                    processVibratorCallbacks();
                }
            }
        }

        /**
         * Cancel the current queue immediately, clearing all remaining steps and skipping clean-up.
         *
         * <p>This will remove and trigger {@link Step#cancelImmediately()} in all steps, in order.
         */
        public void cancelImmediately() {
            // Vibrator callbacks should wait until all steps from the queue are properly cancelled.
            markWaitToProcessVibratorCallbacks();
            try {
                Step step;
                while ((step = pollNext()) != null) {
                    // This might turn off the vibrator and have a HAL latency. Execute this outside
                    // any lock to avoid blocking other interactions with the thread.
                    step.cancelImmediately();
                }
                synchronized (mLock) {
                    mPendingVibrateSteps = 0;
                }
            } finally {
                synchronized (mLock) {
                    processVibratorCallbacks();
                }
            }
        }

        @Nullable
        private Step pollNext() {
            synchronized (mLock) {
                // Prioritize the steps anticipated by a vibrator complete callback.
                if (!mPendingOnVibratorCompleteSteps.isEmpty()) {
                    return mPendingOnVibratorCompleteSteps.poll();
                }
                return mNextSteps.poll();
            }
        }

        private void markWaitToProcessVibratorCallbacks() {
            synchronized (mLock) {
                mWaitToProcessVibratorCallbacks = true;
            }
        }

        /**
         * Notify the step in this queue that should be anticipated by the vibrator completion
         * callback and keep it separate to be consumed by {@link #consumeNext()}.
         *
         * <p>This is a lightweight method that do not trigger any operation from {@link
         * VibratorController}, so it can be called directly from a native callback.
         *
         * <p>This assumes only one of the next steps is waiting on this given vibrator, so the
         * first step found will be anticipated by this method, in no particular order.
         */
        @GuardedBy("mLock")
        private void processVibratorCallbacks() {
            mWaitToProcessVibratorCallbacks = false;
            while (!mNotifiedVibrators.isEmpty()) {
                int vibratorId = mNotifiedVibrators.poll();
                Iterator<Step> it = mNextSteps.iterator();
                while (it.hasNext()) {
                    Step step = it.next();
                    if (step.shouldPlayWhenVibratorComplete(vibratorId)) {
                        it.remove();
                        mPendingOnVibratorCompleteSteps.offer(step);
                        break;
                    }
                }
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

        /**
         * Returns true if this step is a clean up step and not part of a {@link VibrationEffect} or
         * {@link CombinedVibration}.
         */
        public boolean isCleanUp() {
            return false;
        }

        /** Play this step, returning a (possibly empty) list of next steps. */
        @NonNull
        public abstract List<Step> play();

        /**
         * Cancel this pending step and return a (possibly empty) list of clean-up steps that should
         * be played to gracefully cancel this step.
         */
        @NonNull
        public abstract List<Step> cancel();

        /** Cancel this pending step immediately, skipping any clean-up. */
        public abstract void cancelImmediately();

        /**
         * Return the duration the vibrator was turned on when this step was played.
         *
         * @return A positive duration that the vibrator was turned on for by this step;
         * Zero if the segment is not supported, the step was not played yet or vibrator was never
         * turned on by this step; A negative value if the vibrator call has failed.
         */
        public long getVibratorOnDuration() {
            return 0;
        }

        /**
         * Return true to play this step right after a vibrator has notified vibration completed,
         * used to anticipate steps waiting on vibrator callbacks with a timeout.
         */
        public boolean shouldPlayWhenVibratorComplete(int vibratorId) {
            return false;
        }

        /**
         * Returns the time in millis to wait before playing this step. This is performed
         * while holding the queue lock, so should not rely on potentially slow operations.
         */
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
        public final CombinedVibration.Sequential sequentialEffect;
        public final int currentIndex;

        private long mVibratorsOnMaxDuration;

        StartVibrateStep(CombinedVibration.Sequential effect) {
            this(SystemClock.uptimeMillis() + effect.getDelays().get(0), effect, /* index= */ 0);
        }

        StartVibrateStep(long startTime, CombinedVibration.Sequential effect, int index) {
            super(startTime);
            sequentialEffect = effect;
            currentIndex = index;
        }

        @Override
        public long getVibratorOnDuration() {
            return mVibratorsOnMaxDuration;
        }

        @Override
        public List<Step> play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "StartVibrateStep");
            List<Step> nextSteps = new ArrayList<>();
            mVibratorsOnMaxDuration = -1;
            try {
                if (DEBUG) {
                    Slog.d(TAG, "StartVibrateStep for effect #" + currentIndex);
                }
                CombinedVibration effect = sequentialEffect.getEffects().get(currentIndex);
                DeviceEffectMap effectMapping = createEffectToVibratorMapping(effect);
                if (effectMapping == null) {
                    // Unable to map effects to vibrators, ignore this step.
                    return nextSteps;
                }

                mVibratorsOnMaxDuration = startVibrating(effectMapping, nextSteps);
                noteVibratorOn(mVibratorsOnMaxDuration);
            } finally {
                if (mVibratorsOnMaxDuration >= 0) {
                    // It least one vibrator was started then add a finish step to wait for all
                    // active vibrators to finish their individual steps before going to the next.
                    // Otherwise this step was ignored so just go to the next one.
                    Step nextStep =
                            mVibratorsOnMaxDuration > 0 ? new FinishVibrateStep(this) : nextStep();
                    if (nextStep != null) {
                        nextSteps.add(nextStep);
                    }
                }
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
            return nextSteps;
        }

        @Override
        public List<Step> cancel() {
            return EMPTY_STEP_LIST;
        }

        @Override
        public void cancelImmediately() {
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
                CombinedVibration effect) {
            if (effect instanceof CombinedVibration.Mono) {
                return new DeviceEffectMap((CombinedVibration.Mono) effect);
            }
            if (effect instanceof CombinedVibration.Stereo) {
                return new DeviceEffectMap((CombinedVibration.Stereo) effect);
            }
            return null;
        }

        /**
         * Starts playing effects on designated vibrators, in sync.
         *
         * @param effectMapping The {@link CombinedVibration} mapped to this device vibrators
         * @param nextSteps     An output list to accumulate the future {@link Step Steps} created
         *                      by this method, typically one for each vibrator that has
         *                      successfully started vibrating on this step.
         * @return The duration, in millis, of the {@link CombinedVibration}. Repeating
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
                            nextSteps.remove(i).cancelImmediately();
                        }
                    }
                }
            }
        }

        private long startVibrating(SingleVibratorStep step, List<Step> nextSteps) {
            nextSteps.addAll(step.play());
            long stepDuration = step.getVibratorOnDuration();
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
        public boolean isCleanUp() {
            // This step only notes that all the vibrators has been turned off.
            return true;
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
        public List<Step> cancel() {
            cancelImmediately();
            return EMPTY_STEP_LIST;
        }

        @Override
        public void cancelImmediately() {
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
        boolean mVibratorCallbackReceived;

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

        @Override
        public long getVibratorOnDuration() {
            return mVibratorOnResult;
        }

        @Override
        public boolean shouldPlayWhenVibratorComplete(int vibratorId) {
            boolean isSameVibrator = controller.getVibratorInfo().getId() == vibratorId;
            mVibratorCallbackReceived |= isSameVibrator;
            // Only anticipate this step if a timeout was set to wait for the vibration to complete,
            // otherwise we are waiting for the correct time to play the next step.
            return isSameVibrator && (vibratorOffTimeout > SystemClock.uptimeMillis());
        }

        @Override
        public List<Step> cancel() {
            return Arrays.asList(new CompleteStep(SystemClock.uptimeMillis(),
                    /* cancelled= */ true, controller, vibratorOffTimeout));
        }

        @Override
        public void cancelImmediately() {
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

        void changeAmplitude(float amplitude) {
            if (DEBUG) {
                Slog.d(TAG, "Amplitude changed on vibrator " + controller.getVibratorInfo().getId()
                        + " to " + amplitude);
            }
            controller.setAmplitude(amplitude);
        }

        /** Return the {@link #nextVibrateStep} with same timings, only jumping the segments. */
        public List<Step> skipToNextSteps(int segmentsSkipped) {
            return nextSteps(startTime, vibratorOffTimeout, segmentsSkipped);
        }

        /**
         * Return the {@link #nextVibrateStep} with same start and off timings calculated from
         * {@link #getVibratorOnDuration()}, jumping all played segments.
         *
         * <p>This method has same behavior as {@link #skipToNextSteps(int)} when the vibrator
         * result is non-positive, meaning the vibrator has either ignored or failed to turn on.
         */
        public List<Step> nextSteps(int segmentsPlayed) {
            if (mVibratorOnResult <= 0) {
                // Vibration was not started, so just skip the played segments and keep timings.
                return skipToNextSteps(segmentsPlayed);
            }
            long nextStartTime = SystemClock.uptimeMillis() + mVibratorOnResult;
            long nextVibratorOffTimeout = nextStartTime + CALLBACKS_EXTRA_TIMEOUT;
            return nextSteps(nextStartTime, nextVibratorOffTimeout, segmentsPlayed);
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
            // This step should wait for the last vibration to finish (with the timeout) and for the
            // intended step start time (to respect the effect delays).
            super(Math.max(startTime, vibratorOffTimeout), controller, effect, index,
                    vibratorOffTimeout);
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
                    mVibratorOnResult = fallbackStep.getVibratorOnDuration();
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
            // This step should wait for the last vibration to finish (with the timeout) and for the
            // intended step start time (to respect the effect delays).
            super(Math.max(startTime, vibratorOffTimeout), controller, effect, index,
                    vibratorOffTimeout);
        }

        @Override
        public List<Step> play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "ComposePrimitivesStep");
            try {
                // Load the next PrimitiveSegments to create a single compose call to the vibrator,
                // limited to the vibrator composition maximum size.
                int limit = controller.getVibratorInfo().getCompositionSizeMax();
                int segmentCount = limit > 0
                        ? Math.min(effect.getSegments().size(), segmentIndex + limit)
                        : effect.getSegments().size();
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
                    Slog.d(TAG, "Compose " + primitives + " primitives on vibrator "
                            + controller.getVibratorInfo().getId());
                }
                mVibratorOnResult = controller.on(
                        primitives.toArray(new PrimitiveSegment[primitives.size()]),
                        mVibration.id);

                return nextSteps(/* segmentsPlayed= */ primitives.size());
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
            // This step should wait for the last vibration to finish (with the timeout) and for the
            // intended step start time (to respect the effect delays).
            super(Math.max(startTime, vibratorOffTimeout), controller, effect, index,
                    vibratorOffTimeout);
        }

        @Override
        public List<Step> play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "ComposePwleStep");
            try {
                // Load the next RampSegments to create a single composePwle call to the vibrator,
                // limited to the vibrator PWLE maximum size.
                int limit = controller.getVibratorInfo().getPwleSizeMax();
                int segmentCount = limit > 0
                        ? Math.min(effect.getSegments().size(), segmentIndex + limit)
                        : effect.getSegments().size();
                List<RampSegment> pwles = new ArrayList<>();
                for (int i = segmentIndex; i < segmentCount; i++) {
                    VibrationEffectSegment segment = effect.getSegments().get(i);
                    if (segment instanceof RampSegment) {
                        pwles.add((RampSegment) segment);
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
                    Slog.d(TAG, "Compose " + pwles + " PWLEs on vibrator "
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
     * Represents a step to complete a {@link VibrationEffect}.
     *
     * <p>This runs right at the time the vibration is considered to end and will update the pending
     * vibrators count. This can turn off the vibrator or slowly ramp it down to zero amplitude.
     */
    private final class CompleteStep extends SingleVibratorStep {
        private final boolean mCancelled;

        CompleteStep(long startTime, boolean cancelled, VibratorController controller,
                long vibratorOffTimeout) {
            super(startTime, controller, /* effect= */ null, /* index= */ -1, vibratorOffTimeout);
            mCancelled = cancelled;
        }

        @Override
        public boolean isCleanUp() {
            // If the vibration was cancelled then this is just a clean up to ramp off the vibrator.
            // Otherwise this step is part of the vibration.
            return mCancelled;
        }

        @Override
        public List<Step> cancel() {
            if (mCancelled) {
                // Double cancelling will just turn off the vibrator right away.
                return Arrays.asList(new OffStep(SystemClock.uptimeMillis(), controller));
            }
            return super.cancel();
        }

        @Override
        public List<Step> play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "CompleteStep");
            try {
                if (DEBUG) {
                    Slog.d(TAG, "Running " + (mCancelled ? "cancel" : "complete") + " vibration"
                            + " step on vibrator " + controller.getVibratorInfo().getId());
                }
                if (mVibratorCallbackReceived) {
                    // Vibration completion callback was received by this step, just turn if off
                    // and skip any clean-up.
                    stopVibrating();
                    return EMPTY_STEP_LIST;
                }

                float currentAmplitude = controller.getCurrentAmplitude();
                long remainingOnDuration =
                        vibratorOffTimeout - CALLBACKS_EXTRA_TIMEOUT - SystemClock.uptimeMillis();
                long rampDownDuration =
                        Math.min(remainingOnDuration, mVibrationSettings.getRampDownDuration());
                long stepDownDuration = mVibrationSettings.getRampStepDuration();
                if (currentAmplitude < RAMP_OFF_AMPLITUDE_MIN
                        || rampDownDuration <= stepDownDuration) {
                    // No need to ramp down the amplitude, just wait to turn it off.
                    if (mCancelled) {
                        // Vibration is completing because it was cancelled, turn off right away.
                        stopVibrating();
                        return EMPTY_STEP_LIST;
                    } else {
                        return Arrays.asList(new OffStep(vibratorOffTimeout, controller));
                    }
                }

                if (DEBUG) {
                    Slog.d(TAG, "Ramping down vibrator " + controller.getVibratorInfo().getId()
                            + " from amplitude " + currentAmplitude
                            + " for " + rampDownDuration + "ms");
                }
                float amplitudeDelta = currentAmplitude / (rampDownDuration / stepDownDuration);
                float amplitudeTarget = currentAmplitude - amplitudeDelta;
                long newVibratorOffTimeout = mCancelled ? rampDownDuration : vibratorOffTimeout;
                return Arrays.asList(new RampOffStep(startTime, amplitudeTarget, amplitudeDelta,
                        controller, newVibratorOffTimeout));
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }
    }

    /** Represents a step to ramp down the vibrator amplitude before turning it off. */
    private final class RampOffStep extends SingleVibratorStep {
        private final float mAmplitudeTarget;
        private final float mAmplitudeDelta;

        RampOffStep(long startTime, float amplitudeTarget, float amplitudeDelta,
                VibratorController controller, long vibratorOffTimeout) {
            super(startTime, controller, /* effect= */ null, /* index= */ -1, vibratorOffTimeout);
            mAmplitudeTarget = amplitudeTarget;
            mAmplitudeDelta = amplitudeDelta;
        }

        @Override
        public boolean isCleanUp() {
            return true;
        }

        @Override
        public List<Step> cancel() {
            return Arrays.asList(new OffStep(SystemClock.uptimeMillis(), controller));
        }

        @Override
        public List<Step> play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "RampOffStep");
            try {
                if (DEBUG) {
                    long latency = SystemClock.uptimeMillis() - startTime;
                    Slog.d(TAG, "Ramp down the vibrator amplitude, step with "
                            + latency + "ms latency.");
                }
                if (mVibratorCallbackReceived) {
                    // Vibration completion callback was received by this step, just turn if off
                    // and skip the rest of the steps to ramp down the vibrator amplitude.
                    stopVibrating();
                    return EMPTY_STEP_LIST;
                }

                changeAmplitude(mAmplitudeTarget);

                float newAmplitudeTarget = mAmplitudeTarget - mAmplitudeDelta;
                if (newAmplitudeTarget < RAMP_OFF_AMPLITUDE_MIN) {
                    // Vibrator amplitude cannot go further down, just turn it off.
                    return Arrays.asList(new OffStep(vibratorOffTimeout, controller));
                }
                return Arrays.asList(new RampOffStep(
                        startTime + mVibrationSettings.getRampStepDuration(), newAmplitudeTarget,
                        mAmplitudeDelta, controller, vibratorOffTimeout));
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
        public boolean isCleanUp() {
            return true;
        }

        @Override
        public List<Step> cancel() {
            return Arrays.asList(new OffStep(SystemClock.uptimeMillis(), controller));
        }

        @Override
        public void cancelImmediately() {
            stopVibrating();
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
            // This step has a fixed startTime coming from the timings of the waveform it's playing.
            super(startTime, controller, effect, index, vibratorOffTimeout);
            mNextOffTime = vibratorOffTimeout;
        }

        @Override
        public boolean shouldPlayWhenVibratorComplete(int vibratorId) {
            if (controller.getVibratorInfo().getId() == vibratorId) {
                mVibratorCallbackReceived = true;
                mNextOffTime = SystemClock.uptimeMillis();
            }
            // Timings are tightly controlled here, so only anticipate if the vibrator was supposed
            // to be ON but has completed prematurely, to turn it back on as soon as possible.
            return mNextOffTime < startTime && controller.getCurrentAmplitude() > 0;
        }

        @Override
        public List<Step> play() {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "AmplitudeStep");
            try {
                long now = SystemClock.uptimeMillis();
                long latency = now - startTime;
                if (DEBUG) {
                    Slog.d(TAG, "Running amplitude step with " + latency + "ms latency.");
                }

                if (mVibratorCallbackReceived && latency < 0) {
                    // This step was anticipated because the vibrator turned off prematurely.
                    // Turn it back on and return this same step to run at the exact right time.
                    mNextOffTime = turnVibratorBackOn(/* remainingDuration= */ -latency);
                    return Arrays.asList(new AmplitudeStep(startTime, controller, effect,
                            segmentIndex, mNextOffTime));
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

                float amplitude = stepSegment.getAmplitude();
                if (amplitude == 0) {
                    if (vibratorOffTimeout > now) {
                        // Amplitude cannot be set to zero, so stop the vibrator.
                        stopVibrating();
                        mNextOffTime = now;
                    }
                } else {
                    if (startTime >= mNextOffTime) {
                        // Vibrator is OFF. Turn vibrator back on for the duration of another
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

        private long turnVibratorBackOn(long remainingDuration) {
            long onDuration = getVibratorOnDuration(effect, segmentIndex);
            if (onDuration <= 0) {
                // Vibrator is supposed to go back off when this step starts, so just leave it off.
                return vibratorOffTimeout;
            }
            onDuration += remainingDuration;
            float expectedAmplitude = controller.getCurrentAmplitude();
            mVibratorOnResult = startVibrating(onDuration);
            if (mVibratorOnResult > 0) {
                // Set the amplitude back to the value it was supposed to be playing at.
                changeAmplitude(expectedAmplitude);
            }
            return SystemClock.uptimeMillis() + onDuration + CALLBACKS_EXTRA_TIMEOUT;
        }

        private long startVibrating(long duration) {
            if (DEBUG) {
                Slog.d(TAG, "Turning on vibrator " + controller.getVibratorInfo().getId() + " for "
                        + duration + "ms");
            }
            return controller.on(duration, mVibration.id);
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
                    // The repeating waveform keeps the vibrator ON all the time. Use a minimum
                    // of 1s duration to prevent short patterns from turning the vibrator ON too
                    // frequently.
                    return Math.max(timing, 1000);
                }
            }
            if (i == segmentCount && effect.getRepeatIndex() < 0) {
                // Vibration ending at non-zero amplitude, add extra timings to ramp down after
                // vibration is complete.
                timing += mVibrationSettings.getRampDownDuration();
            }
            return timing;
        }
    }

    /**
     * Map a {@link CombinedVibration} to the vibrators available on the device.
     *
     * <p>This contains the logic to find the capabilities required from {@link IVibratorManager} to
     * play all of the effects in sync.
     */
    private final class DeviceEffectMap {
        private final SparseArray<VibrationEffect.Composed> mVibratorEffects;
        private final int[] mVibratorIds;
        private final long mRequiredSyncCapabilities;

        DeviceEffectMap(CombinedVibration.Mono mono) {
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

        DeviceEffectMap(CombinedVibration.Stereo stereo) {
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
         * Return the number of vibrators mapped to play the {@link CombinedVibration} on this
         * device.
         */
        public int size() {
            return mVibratorIds.length;
        }

        /**
         * Return all capabilities required to play the {@link CombinedVibration} in
         * between calls to {@link IVibratorManager#prepareSynced} and
         * {@link IVibratorManager#triggerSynced}.
         */
        public long getRequiredSyncCapabilities() {
            return mRequiredSyncCapabilities;
        }

        /** Return all vibrator ids mapped to play the {@link CombinedVibration}. */
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
