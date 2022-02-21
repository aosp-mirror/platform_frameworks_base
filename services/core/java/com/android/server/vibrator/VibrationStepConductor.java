/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.os.VibrationEffect;
import android.os.WorkSource;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;

/**
 * Creates and manages a queue of steps for performing a VibrationEffect, as well as coordinating
 * dispatch of callbacks.
 */
final class VibrationStepConductor {
    /**
     * Extra timeout added to the end of each vibration step to ensure it finishes even when
     * vibrator callbacks are lost.
     */
    static final long CALLBACKS_EXTRA_TIMEOUT = 1_000;
    /** Threshold to prevent the ramp off steps from trying to set extremely low amplitudes. */
    static final float RAMP_OFF_AMPLITUDE_MIN = 1e-3f;
    static final List<Step> EMPTY_STEP_LIST = new ArrayList<>();

    final Object mLock = new Object();

    // Used within steps.
    public final VibrationSettings vibrationSettings;
    public final DeviceVibrationEffectAdapter deviceEffectAdapter;
    public final VibrationThread.VibratorManagerHooks vibratorManagerHooks;

    private final WorkSource mWorkSource;
    private final Vibration mVibration;
    private final SparseArray<VibratorController> mVibrators = new SparseArray<>();

    @GuardedBy("mLock")
    private final PriorityQueue<Step> mNextSteps = new PriorityQueue<>();
    @GuardedBy("mLock")
    private final Queue<Step> mPendingOnVibratorCompleteSteps = new LinkedList<>();
    @GuardedBy("mLock")
    private final Queue<Integer> mCompletionNotifiedVibrators = new LinkedList<>();

    @GuardedBy("mLock")
    private int mPendingVibrateSteps;
    @GuardedBy("mLock")
    private int mConsumedStartVibrateSteps;
    @GuardedBy("mLock")
    private int mSuccessfulVibratorOnSteps;
    @GuardedBy("mLock")
    private boolean mWaitToProcessVibratorCompleteCallbacks;

    VibrationStepConductor(Vibration vib, VibrationSettings vibrationSettings,
            DeviceVibrationEffectAdapter effectAdapter,
            SparseArray<VibratorController> availableVibrators,
            VibrationThread.VibratorManagerHooks vibratorManagerHooks) {
        this.mVibration = vib;
        this.vibrationSettings = vibrationSettings;
        this.deviceEffectAdapter = effectAdapter;
        this.vibratorManagerHooks = vibratorManagerHooks;
        this.mWorkSource = new WorkSource(mVibration.uid);

        CombinedVibration effect = vib.getEffect();
        for (int i = 0; i < availableVibrators.size(); i++) {
            if (effect.hasVibrator(availableVibrators.keyAt(i))) {
                mVibrators.put(availableVibrators.keyAt(i), availableVibrators.valueAt(i));
            }
        }
    }

    @Nullable
    AbstractVibratorStep nextVibrateStep(long startTime, VibratorController controller,
            VibrationEffect.Composed effect, int segmentIndex,
            long previousStepVibratorOffTimeout) {
        if (segmentIndex >= effect.getSegments().size()) {
            segmentIndex = effect.getRepeatIndex();
        }
        if (segmentIndex < 0) {
            // No more segments to play, last step is to complete the vibration on this vibrator.
            return new CompleteEffectVibratorStep(this, startTime, /* cancelled= */ false,
                    controller, previousStepVibratorOffTimeout);
        }

        VibrationEffectSegment segment = effect.getSegments().get(segmentIndex);
        if (segment instanceof PrebakedSegment) {
            return new PerformPrebakedVibratorStep(this, startTime, controller, effect,
                    segmentIndex, previousStepVibratorOffTimeout);
        }
        if (segment instanceof PrimitiveSegment) {
            return new ComposePrimitivesVibratorStep(this, startTime, controller, effect,
                    segmentIndex, previousStepVibratorOffTimeout);
        }
        if (segment instanceof RampSegment) {
            return new ComposePwleVibratorStep(this, startTime, controller, effect, segmentIndex,
                    previousStepVibratorOffTimeout);
        }
        return new SetAmplitudeVibratorStep(this, startTime, controller, effect, segmentIndex,
                previousStepVibratorOffTimeout);
    }

    public void initializeForEffect(@NonNull CombinedVibration.Sequential vibration) {
        synchronized (mLock) {
            mPendingVibrateSteps++;
            mNextSteps.offer(new StartSequentialEffectStep(this, vibration));
        }
    }

    public Vibration getVibration() {
        return mVibration;
    }

    public WorkSource getWorkSource() {
        return mWorkSource;
    }

    SparseArray<VibratorController> getVibrators() {
        return mVibrators;
    }

    public boolean isFinished() {
        synchronized (mLock) {
            return mPendingOnVibratorCompleteSteps.isEmpty() && mNextSteps.isEmpty();
        }
    }

    /**
     * Calculate the {@link Vibration.Status} based on the current queue state and the expected
     * number of {@link StartSequentialEffectStep} to be played.
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

    /** Returns the time in millis to wait before calling {@link #runNextStep()}. */
    @GuardedBy("mLock")
    public long getWaitMillisBeforeNextStepLocked() {
        if (!mPendingOnVibratorCompleteSteps.isEmpty()) {
            // Steps resumed by vibrator complete callback should be played right away.
            return 0;
        }
        Step nextStep = mNextSteps.peek();
        return nextStep == null ? 0 : nextStep.calculateWaitTime();
    }

    /**
     * Play and remove the step at the top of this queue, and also adds the next steps generated
     * to be played next.
     */
    public void runNextStep() {
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
                    if (nextStep instanceof StartSequentialEffectStep) {
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
                processVibratorCompleteCallbacksLocked();
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
    private void notifyVibratorCompleteLocked(int vibratorId) {
        mCompletionNotifiedVibrators.offer(vibratorId);
        if (!mWaitToProcessVibratorCompleteCallbacks) {
            // No step is being played or cancelled now, process the callback right away.
            processVibratorCompleteCallbacksLocked();
        }
    }

    public void notifyVibratorComplete(int vibratorId) {
        synchronized (mLock) {
            if (VibrationThread.DEBUG) {
                Slog.d(VibrationThread.TAG,
                        "Vibration complete reported by vibrator " + vibratorId);
            }
            notifyVibratorCompleteLocked(vibratorId);
            mLock.notify();
        }
    }

    public void notifySyncedVibrationComplete() {
        synchronized (mLock) {
            if (VibrationThread.DEBUG) {
                Slog.d(VibrationThread.TAG,
                        "Synced vibration complete reported by vibrator manager");
            }
            for (int i = 0; i < mVibrators.size(); i++) {
                notifyVibratorCompleteLocked(mVibrators.keyAt(i));
            }
            mLock.notify();
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
                processVibratorCompleteCallbacksLocked();
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
                processVibratorCompleteCallbacksLocked();
            }
        }
    }

    @Nullable
    private Step pollNext() {
        synchronized (mLock) {
            // Prioritize the steps resumed by a vibrator complete callback.
            if (!mPendingOnVibratorCompleteSteps.isEmpty()) {
                return mPendingOnVibratorCompleteSteps.poll();
            }
            return mNextSteps.poll();
        }
    }

    private void markWaitToProcessVibratorCallbacks() {
        synchronized (mLock) {
            mWaitToProcessVibratorCompleteCallbacks = true;
        }
    }

    /**
     * Notify the step in this queue that should be resumed by the vibrator completion
     * callback and keep it separate to be consumed by {@link #runNextStep()}.
     *
     * <p>This is a lightweight method that do not trigger any operation from {@link
     * VibratorController}, so it can be called directly from a native callback.
     *
     * <p>This assumes only one of the next steps is waiting on this given vibrator, so the
     * first step found will be resumed by this method, in no particular order.
     */
    @GuardedBy("mLock")
    private void processVibratorCompleteCallbacksLocked() {
        mWaitToProcessVibratorCompleteCallbacks = false;
        while (!mCompletionNotifiedVibrators.isEmpty()) {
            int vibratorId = mCompletionNotifiedVibrators.poll();
            Iterator<Step> it = mNextSteps.iterator();
            while (it.hasNext()) {
                Step step = it.next();
                if (step.acceptVibratorCompleteCallback(vibratorId)) {
                    it.remove();
                    mPendingOnVibratorCompleteSteps.offer(step);
                    break;
                }
            }
        }
    }
}
