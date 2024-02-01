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
import android.os.Build;
import android.os.CombinedVibration;
import android.os.IBinder;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.vibrator.Flags;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Creates and manages a queue of steps for performing a VibrationEffect, as well as coordinating
 * dispatch of callbacks.
 *
 * <p>In general, methods in this class are intended to be called only by a single instance of
 * VibrationThread. The only thread-safe methods for calling from other threads are the "notify"
 * methods (which should never be used from the VibrationThread thread).
 */
final class VibrationStepConductor implements IBinder.DeathRecipient {
    private static final boolean DEBUG = VibrationThread.DEBUG;
    private static final String TAG = VibrationThread.TAG;

    /**
     * Extra timeout added to the end of each vibration step to ensure it finishes even when
     * vibrator callbacks are lost.
     */
    static final long CALLBACKS_EXTRA_TIMEOUT = 1_000;
    /** Threshold to prevent the ramp off steps from trying to set extremely low amplitudes. */
    static final float RAMP_OFF_AMPLITUDE_MIN = 1e-3f;
    static final List<Step> EMPTY_STEP_LIST = new ArrayList<>();

    // Used within steps.
    public final VibrationSettings vibrationSettings;
    public final VibrationThread.VibratorManagerHooks vibratorManagerHooks;

    private final DeviceAdapter mDeviceAdapter;
    private final VibrationScaler mVibrationScaler;

    // Not guarded by lock because it's mostly used to read immutable fields by this conductor.
    // This is only modified here at the prepareToStart method which always runs at the vibration
    // thread, to update the adapted effect and report start time.
    private final HalVibration mVibration;
    private final PriorityQueue<Step> mNextSteps = new PriorityQueue<>();
    private final Queue<Step> mPendingOnVibratorCompleteSteps = new LinkedList<>();

    @Nullable
    private final CompletableFuture<Void> mRequestVibrationParamsFuture;

    // Signalling fields.
    // Note that vibrator callback signals may happen inside vibrator HAL calls made by the
    // VibrationThread, or on an external executor, so this lock should not be held for anything
    // other than updating signalling state - particularly not during HAL calls or when invoking
    // other callbacks that may trigger calls into the thread.
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final IntArray mSignalVibratorsComplete;
    @Nullable
    @GuardedBy("mLock")
    private Vibration.EndInfo mSignalCancel = null;
    @GuardedBy("mLock")
    private boolean mSignalCancelImmediate = false;

    @Nullable
    private Vibration.EndInfo mCancelledVibrationEndInfo = null;
    private boolean mCancelledImmediately = false;  // hard stop
    private int mPendingVibrateSteps;
    private int mRemainingStartSequentialEffectSteps;
    private int mSuccessfulVibratorOnSteps;

    VibrationStepConductor(HalVibration vib, VibrationSettings vibrationSettings,
            DeviceAdapter deviceAdapter,
            VibrationScaler vibrationScaler,
            CompletableFuture<Void> requestVibrationParamsFuture,
            VibrationThread.VibratorManagerHooks vibratorManagerHooks) {
        this.mVibration = vib;
        this.vibrationSettings = vibrationSettings;
        this.mDeviceAdapter = deviceAdapter;
        mVibrationScaler = vibrationScaler;
        mRequestVibrationParamsFuture = requestVibrationParamsFuture;
        this.vibratorManagerHooks = vibratorManagerHooks;
        this.mSignalVibratorsComplete =
                new IntArray(mDeviceAdapter.getAvailableVibratorIds().length);
    }

    @Nullable
    AbstractVibratorStep nextVibrateStep(long startTime, VibratorController controller,
            VibrationEffect.Composed effect, int segmentIndex, long pendingVibratorOffDeadline) {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }
        if (segmentIndex >= effect.getSegments().size()) {
            segmentIndex = effect.getRepeatIndex();
        }
        if (segmentIndex < 0) {
            // No more segments to play, last step is to complete the vibration on this vibrator.
            return new CompleteEffectVibratorStep(this, startTime, /* cancelled= */ false,
                    controller, pendingVibratorOffDeadline);
        }

        VibrationEffectSegment segment = effect.getSegments().get(segmentIndex);
        if (segment instanceof PrebakedSegment) {
            return new PerformPrebakedVibratorStep(this, startTime, controller, effect,
                    segmentIndex, pendingVibratorOffDeadline);
        }
        if (segment instanceof PrimitiveSegment) {
            return new ComposePrimitivesVibratorStep(this, startTime, controller, effect,
                    segmentIndex, pendingVibratorOffDeadline);
        }
        if (segment instanceof RampSegment) {
            return new ComposePwleVibratorStep(this, startTime, controller, effect, segmentIndex,
                    pendingVibratorOffDeadline);
        }
        return new SetAmplitudeVibratorStep(this, startTime, controller, effect, segmentIndex,
                pendingVibratorOffDeadline);
    }

    /** Called when this conductor is going to be started running by the VibrationThread. */
    public void prepareToStart() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }

        if (!mVibration.callerInfo.attrs.isFlagSet(
                VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_SCALE)) {
            if (Flags.adaptiveHapticsEnabled()) {
                waitForVibrationParamsIfRequired();
            }
            mVibration.scaleEffects(mVibrationScaler::scale);
        }

        mVibration.adaptToDevice(mDeviceAdapter);
        CombinedVibration.Sequential sequentialEffect = toSequential(mVibration.getEffectToPlay());
        mPendingVibrateSteps++;
        // This count is decremented at the completion of the step, so we don't subtract one.
        mRemainingStartSequentialEffectSteps = sequentialEffect.getEffects().size();
        mNextSteps.offer(new StartSequentialEffectStep(this, sequentialEffect));
        // Vibration will start playing in the Vibrator, following the effect timings and delays.
        // Report current time as the vibration start time, for debugging.
        mVibration.stats.reportStarted();
    }

    public HalVibration getVibration() {
        // No thread assertion: immutable
        return mVibration;
    }

    SparseArray<VibratorController> getVibrators() {
        // No thread assertion: immutable
        return mDeviceAdapter.getAvailableVibrators();
    }

    public boolean isFinished() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }
        if (mCancelledImmediately) {
            return true;  // Terminate.
        }

        // No need to check for vibration complete callbacks - if there were any, they would
        // have no steps to notify anyway.
        return mPendingOnVibratorCompleteSteps.isEmpty() && mNextSteps.isEmpty();
    }

    /**
     * Calculate the {@link Vibration.Status} based on the current queue state and the expected
     * number of {@link StartSequentialEffectStep} to be played.
     */
    @Nullable
    public Vibration.EndInfo calculateVibrationEndInfo() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }

        if (mCancelledVibrationEndInfo != null) {
            return mCancelledVibrationEndInfo;
        }
        if (mPendingVibrateSteps > 0 || mRemainingStartSequentialEffectSteps > 0) {
            // Vibration still running.
            return null;
        }
        // No pending steps, and something happened.
        if (mSuccessfulVibratorOnSteps > 0) {
            return new Vibration.EndInfo(Vibration.Status.FINISHED);
        }
        // If no step was able to turn the vibrator ON successfully.
        return new Vibration.EndInfo(Vibration.Status.IGNORED_UNSUPPORTED);
    }

    /**
     * Blocks until the next step is due to run. The wait here may be interrupted by calling
     * one of the "notify" methods.
     *
     * <p>This method returns true if the next step is ready to run now. If the method returns
     * false, then some waiting was done, but may have been interrupted by a wakeUp, and the
     * status and isFinished of the vibration should be re-checked before calling this method again.
     *
     * @return true if the next step can be run now or the vibration is finished, or false if this
     *   method waited and the conductor state may have changed asynchronously, in which case this
     *   method needs to be run again.
     */
    public boolean waitUntilNextStepIsDue() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }

        processAllNotifySignals();
        if (mCancelledImmediately) {
            // Don't try to run a step for immediate cancel, although there should be none left.
            // Non-immediate cancellation may have cleanup steps, so it continues processing.
            return false;
        }
        if (!mPendingOnVibratorCompleteSteps.isEmpty()) {
            return true;  // Resumed step ready.
        }
        Step nextStep = mNextSteps.peek();
        if (nextStep == null) {
            return true;  // Finished
        }
        long waitMillis = nextStep.calculateWaitTime();
        if (waitMillis <= 0) {
            return true;  // Regular step ready
        }
        synchronized (mLock) {
            // Double check for signals before sleeping, as their notify wouldn't interrupt a fresh
            // wait.
            if (hasPendingNotifySignalLocked()) {
                // Don't run the next step, it will loop back to this method and process them.
                return false;
            }
            try {
                mLock.wait(waitMillis);
            } catch (InterruptedException e) {
            }
            return false;  // Caller needs to check isFinished and maybe wait again.
        }
    }

    @Nullable
    private Step pollNext() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }

        // Prioritize the steps resumed by a vibrator complete callback, irrespective of their
        // "next run time".
        if (!mPendingOnVibratorCompleteSteps.isEmpty()) {
            return mPendingOnVibratorCompleteSteps.poll();
        }
        return mNextSteps.poll();
    }

    /**
     * Play and remove the step at the top of this queue, and also adds the next steps generated
     * to be played next.
     */
    public void runNextStep() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }
        // In theory a completion callback could have come in between the wait finishing and
        // this method starting, but that only means the step is due now anyway, so it's reasonable
        // to run it before processing callbacks as the window is tiny.
        Step nextStep = pollNext();
        if (nextStep != null) {
            if (DEBUG) {
                Slog.d(TAG, "Playing vibration id " + getVibration().id
                        + ((nextStep instanceof AbstractVibratorStep)
                        ? " on vibrator " + ((AbstractVibratorStep) nextStep).getVibratorId() : "")
                        + " " + nextStep.getClass().getSimpleName()
                        + (nextStep.isCleanUp() ? " (cleanup)" : ""));
            }

            List<Step> nextSteps = nextStep.play();
            if (nextStep.getVibratorOnDuration() > 0) {
                mSuccessfulVibratorOnSteps++;
            }
            if (nextStep instanceof StartSequentialEffectStep) {
                mRemainingStartSequentialEffectSteps--;
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

    /**
     * Binder death notification. VibrationThread registers this when it's running a conductor.
     * Note that cancellation could theoretically happen immediately, before the conductor has
     * started, but in this case it will be processed in the first signals loop.
     */
    @Override
    public void binderDied() {
        if (DEBUG) {
            Slog.d(TAG, "Binder died, cancelling vibration...");
        }
        notifyCancelled(new Vibration.EndInfo(Vibration.Status.CANCELLED_BINDER_DIED),
                /* immediate= */ false);
    }

    /**
     * Notify the execution that cancellation is requested. This will be acted upon
     * asynchronously in the VibrationThread.
     *
     * <p>Only the first cancel signal will be used to end a cancelled vibration, but subsequent
     * calls with {@code immediate} flag set to true can still force the first cancel signal to
     * take effect urgently.
     *
     * @param immediate indicates whether cancellation should abort urgently and skip cleanup steps.
     */
    public void notifyCancelled(@NonNull Vibration.EndInfo cancelInfo, boolean immediate) {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(false);
        }
        if (DEBUG) {
            Slog.d(TAG, "Vibration cancel requested with signal=" + cancelInfo
                    + ", immediate=" + immediate);
        }
        if ((cancelInfo == null) || !cancelInfo.status.name().startsWith("CANCEL")) {
            Slog.w(TAG, "Vibration cancel requested with bad signal=" + cancelInfo
                    + ", using CANCELLED_UNKNOWN_REASON to ensure cancellation.");
            cancelInfo = new Vibration.EndInfo(Vibration.Status.CANCELLED_BY_UNKNOWN_REASON);
        }
        synchronized (mLock) {
            if ((immediate && mSignalCancelImmediate) || (mSignalCancel != null)) {
                if (DEBUG) {
                    Slog.d(TAG, "Vibration cancel request ignored as the vibration "
                            + mVibration.id + "is already being cancelled with signal="
                            + mSignalCancel + ", immediate=" + mSignalCancelImmediate);
                }
                return;
            }
            mSignalCancelImmediate |= immediate;
            if (mSignalCancel == null) {
                mSignalCancel = cancelInfo;
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "Vibration cancel request new signal=" + cancelInfo
                            + " ignored as the vibration was already cancelled with signal="
                            + mSignalCancel + ", but immediate flag was updated to "
                            + mSignalCancelImmediate);
                }
            }
            if (mRequestVibrationParamsFuture != null) {
                mRequestVibrationParamsFuture.cancel(/* mayInterruptIfRunning= */true);
            }
            mLock.notify();
        }
    }

    /**
     * Notify the conductor that a vibrator has completed its work.
     *
     * <p>This is a lightweight method intended to be called directly via native callbacks.
     * The state update is recorded for processing on the main execution thread (VibrationThread).
     */
    public void notifyVibratorComplete(int vibratorId) {
        // HAL callbacks may be triggered directly within HAL calls, so these notifications
        // could be on the VibrationThread as it calls the HAL, or some other executor later.
        // Therefore no thread assertion is made here.

        if (DEBUG) {
            Slog.d(TAG, "Vibration complete reported by vibrator " + vibratorId);
        }

        synchronized (mLock) {
            mSignalVibratorsComplete.add(vibratorId);
            mLock.notify();
        }
    }

    /**
     * Notify that a VibratorManager sync operation has completed.
     *
     * <p>This is a lightweight method intended to be called directly via native callbacks.
     * The state update is recorded for processing on the main execution thread
     * (VibrationThread).
     */
    public void notifySyncedVibrationComplete() {
        // HAL callbacks may be triggered directly within HAL calls, so these notifications
        // could be on the VibrationThread as it calls the HAL, or some other executor later.
        // Therefore no thread assertion is made here.

        if (DEBUG) {
            Slog.d(TAG, "Synced vibration complete reported by vibrator manager");
        }

        synchronized (mLock) {
            for (int vibratorId : mDeviceAdapter.getAvailableVibratorIds()) {
                mSignalVibratorsComplete.add(vibratorId);
            }
            mLock.notify();
        }
    }

    /** Returns true if a cancellation signal was sent via {@link #notifyCancelled}. */
    public boolean wasNotifiedToCancel() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(false);
        }
        synchronized (mLock) {
            return mSignalCancel != null;
        }
    }

    /**
     * Blocks until the vibration params future is complete.
     *
     * This should be called by the VibrationThread and may be interrupted by calling
     * `notifyCancelled` from outside it.
     */
    private void waitForVibrationParamsIfRequired() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }

        if (mRequestVibrationParamsFuture == null) {
            return;
        }

        try {
            mRequestVibrationParamsFuture.orTimeout(
                    vibrationSettings.getRequestVibrationParamsTimeoutMs(),
                    TimeUnit.MILLISECONDS).get();
        } catch (Throwable e) {
            Slog.w(TAG, "Failed to retrieve vibration params.", e);
        }
    }

    @GuardedBy("mLock")
    private boolean hasPendingNotifySignalLocked() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);  // Reads VibrationThread variables as well as signals.
        }
        return (mSignalCancel != null && mCancelledVibrationEndInfo == null)
                || (mSignalCancelImmediate && !mCancelledImmediately)
                || (mSignalVibratorsComplete.size() > 0);
    }

    /**
     * Process any notified cross-thread signals, applying the necessary VibrationThread state
     * changes.
     */
    private void processAllNotifySignals() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }

        int[] vibratorsToProcess = null;
        Vibration.EndInfo doCancelInfo = null;
        boolean doCancelImmediate = false;
        // Collect signals to process, but don't keep the lock while processing them.
        synchronized (mLock) {
            if (mSignalCancelImmediate) {
                if (mCancelledImmediately) {
                    Slog.wtf(TAG, "Immediate cancellation signal processed twice");
                }
                // This should only happen once.
                doCancelImmediate = true;
                doCancelInfo = mSignalCancel;
            }
            if ((mSignalCancel != null) && (mCancelledVibrationEndInfo == null)) {
                doCancelInfo = mSignalCancel;
            }
            if (!doCancelImmediate && mSignalVibratorsComplete.size() > 0) {
                // Swap out the queue of completions to process.
                vibratorsToProcess = mSignalVibratorsComplete.toArray();  // makes a copy
                mSignalVibratorsComplete.clear();
            }
        }

        // Force cancellation means stop everything and clear all steps, so the execution loop
        // shouldn't come back to this method. To observe explicitly: this drops vibrator
        // completion signals that were collected in this call, but we won't process them
        // anyway as all steps are cancelled.
        if (doCancelImmediate) {
            processCancelImmediately(doCancelInfo);
            return;
        }
        if (doCancelInfo != null) {
            processCancel(doCancelInfo);
        }
        if (vibratorsToProcess != null) {
            processVibratorsComplete(vibratorsToProcess);
        }
    }

    /**
     * Cancel the current queue, replacing all remaining steps with respective clean-up steps.
     *
     * <p>This will remove all steps and replace them with respective results of
     * {@link Step#cancel()}.
     */
    public void processCancel(Vibration.EndInfo cancelInfo) {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }

        mCancelledVibrationEndInfo = cancelInfo;
        // Vibrator callbacks should wait until all steps from the queue are properly cancelled
        // and clean up steps are added back to the queue, so they can handle the callback.
        List<Step> cleanUpSteps = new ArrayList<>();
        Step step;
        while ((step = pollNext()) != null) {
            cleanUpSteps.addAll(step.cancel());
        }
        // All steps generated by Step.cancel() should be clean-up steps.
        mPendingVibrateSteps = 0;
        mNextSteps.addAll(cleanUpSteps);
    }

    /**
     * Cancel the current queue immediately, clearing all remaining steps and skipping clean-up.
     *
     * <p>This will remove and trigger {@link Step#cancelImmediately()} in all steps, in order.
     */
    public void processCancelImmediately(Vibration.EndInfo cancelInfo) {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }

        mCancelledImmediately = true;
        mCancelledVibrationEndInfo = cancelInfo;
        Step step;
        while ((step = pollNext()) != null) {
            step.cancelImmediately();
        }
        mPendingVibrateSteps = 0;
    }

    /**
     * Processes the vibrators that have sent their complete callbacks. A step is found that will
     * accept the completion callback, and this step is brought forward for execution in the next
     * run.
     *
     * <p>This assumes only one of the next steps is waiting on this given vibrator, so the
     * first step found will be resumed by this method, in no particular order.
     */
    private void processVibratorsComplete(@NonNull int[] vibratorsToProcess) {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }

        for (int vibratorId : vibratorsToProcess) {
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

    private static CombinedVibration.Sequential toSequential(CombinedVibration effect) {
        if (effect instanceof CombinedVibration.Sequential) {
            return (CombinedVibration.Sequential) effect;
        }
        return (CombinedVibration.Sequential) CombinedVibration.startSequential()
                .addNext(effect)
                .combine();
    }

    /**
     * This check is used for debugging and documentation to indicate the thread that's expected
     * to invoke a given public method on this class. Most methods are only invoked by
     * VibrationThread, which is where all the steps and HAL calls should be made. Other threads
     * should only signal to the execution flow being run by VibrationThread.
     */
    private static void expectIsVibrationThread(boolean isVibrationThread) {
        if ((Thread.currentThread() instanceof VibrationThread) != isVibrationThread) {
            Slog.wtfStack("VibrationStepConductor",
                    "Thread caller assertion failed, expected isVibrationThread="
                            + isVibrationThread);
        }
    }
}
