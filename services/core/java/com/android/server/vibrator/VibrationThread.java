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

import android.os.CombinedVibration;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.util.NoSuchElementException;

/** Plays a {@link Vibration} in dedicated thread. */
final class VibrationThread extends Thread implements IBinder.DeathRecipient {
    static final String TAG = "VibrationThread";
    static final boolean DEBUG = false;

    /** Calls into VibratorManager functionality needed for playing a {@link Vibration}. */
    interface VibratorManagerHooks {

        /**
         * Request the manager to prepare for triggering a synchronized vibration step.
         *
         * @param requiredCapabilities The required syncing capabilities for this preparation step.
         *                             Expect CAP_SYNC and a combination of values from
         *                             IVibratorManager.CAP_PREPARE_* and
         *                             IVibratorManager.CAP_MIXED_TRIGGER_*.
         * @param vibratorIds          The id of the vibrators to be prepared.
         */
        boolean prepareSyncedVibration(long requiredCapabilities, int[] vibratorIds);

        /**
         * Request the manager to trigger a synchronized vibration. The vibration must already
         * have been prepared with {@link #prepareSyncedVibration}.
         */
        boolean triggerSyncedVibration(long vibrationId);

        /** Tell the manager to cancel a synced vibration. */
        void cancelSyncedVibration();

        /**
         * Record that a vibrator was turned on, and may remain on for the specified duration,
         * on behalf of the given uid.
         */
        void noteVibratorOn(int uid, long duration);

        /** Record that a vibrator was turned off, on behalf of the given uid. */
        void noteVibratorOff(int uid);

        /**
         * Tell the manager that the currently active vibration has completed its vibration, from
         * the perspective of the Effect. However, the VibrationThread may still be continuing with
         * cleanup tasks, and should not be given new work until {@link #onVibrationThreadReleased}
         * is called.
         */
        void onVibrationCompleted(long vibrationId, Vibration.Status status);

        /**
         * Tells the manager that the VibrationThread is finished with the previous vibration and
         * all of its cleanup tasks, and the vibrators can now be used for another vibration.
         */
        void onVibrationThreadReleased();
    }

    private final PowerManager.WakeLock mWakeLock;
    private final VibrationThread.VibratorManagerHooks mVibratorManagerHooks;

    private final VibrationStepConductor mStepConductor;

    private volatile boolean mStop;
    private volatile boolean mForceStop;
    // Variable only set and read in main thread.
    private boolean mCalledVibrationCompleteCallback = false;

    VibrationThread(Vibration vib, VibrationSettings vibrationSettings,
            DeviceVibrationEffectAdapter effectAdapter,
            SparseArray<VibratorController> availableVibrators, PowerManager.WakeLock wakeLock,
            VibratorManagerHooks vibratorManagerHooks) {
        mVibratorManagerHooks = vibratorManagerHooks;
        mWakeLock = wakeLock;
        mStepConductor = new VibrationStepConductor(vib, vibrationSettings, effectAdapter,
                availableVibrators, vibratorManagerHooks);
    }

    Vibration getVibration() {
        return mStepConductor.getVibration();
    }

    @VisibleForTesting
    SparseArray<VibratorController> getVibrators() {
        return mStepConductor.getVibrators();
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
        // Structured to guarantee the vibrators completed and released callbacks at the end of
        // thread execution. Both of these callbacks are exclusively called from this thread.
        try {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
                runWithWakeLock();
            } finally {
                clientVibrationCompleteIfNotAlready(Vibration.Status.FINISHED_UNEXPECTED);
            }
        } finally {
            mVibratorManagerHooks.onVibrationThreadReleased();
        }
    }

    /** Runs the VibrationThread ensuring that the wake lock is acquired and released. */
    private void runWithWakeLock() {
        mWakeLock.setWorkSource(mStepConductor.getWorkSource());
        mWakeLock.acquire();
        try {
            runWithWakeLockAndDeathLink();
        } finally {
            mWakeLock.release();
        }
    }

    /**
     * Runs the VibrationThread with the binder death link, handling link/unlink failures.
     * Called from within runWithWakeLock.
     */
    private void runWithWakeLockAndDeathLink() {
        IBinder vibrationBinderToken = mStepConductor.getVibration().token;
        try {
            vibrationBinderToken.linkToDeath(this, 0);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error linking vibration to token death", e);
            clientVibrationCompleteIfNotAlready(Vibration.Status.IGNORED_ERROR_TOKEN);
            return;
        }
        // Ensure that the unlink always occurs now.
        try {
            // This is the actual execution of the vibration.
            playVibration();
        } finally {
            try {
                vibrationBinderToken.unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                Slog.wtf(TAG, "Failed to unlink token", e);
            }
        }
    }

    /** Cancel current vibration and ramp down the vibrators gracefully. */
    public void cancel() {
        if (mStop) {
            // Already cancelled, running clean-up steps.
            return;
        }
        mStop = true;
        synchronized (mStepConductor.mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Vibration cancelled");
            }
            mStepConductor.mLock.notify();
        }
    }

    /** Cancel current vibration and shuts off the vibrators immediately. */
    public void cancelImmediately() {
        if (mForceStop) {
            // Already forced the thread to stop, wait for it to finish.
            return;
        }
        mStop = mForceStop = true;
        synchronized (mStepConductor.mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Vibration cancelled immediately");
            }
            mStepConductor.mLock.notify();
        }
    }

    /** Notify current vibration that a synced step has completed. */
    public void syncedVibrationComplete() {
        mStepConductor.notifySyncedVibrationComplete();
    }

    /** Notify current vibration that a step has completed on given vibrator. */
    public void vibratorComplete(int vibratorId) {
        mStepConductor.notifyVibratorComplete(vibratorId);
    }

    // Indicate that the vibration is complete. This can be called multiple times only for
    // convenience of handling error conditions - an error after the client is complete won't
    // affect the status.
    private void clientVibrationCompleteIfNotAlready(Vibration.Status completedStatus) {
        if (!mCalledVibrationCompleteCallback) {
            mCalledVibrationCompleteCallback = true;
            mVibratorManagerHooks.onVibrationCompleted(
                    mStepConductor.getVibration().id, completedStatus);
        }
    }

    private void playVibration() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "playVibration");
        try {
            CombinedVibration.Sequential sequentialEffect =
                    toSequential(mStepConductor.getVibration().getEffect());
            final int sequentialEffectSize = sequentialEffect.getEffects().size();
            mStepConductor.initializeForEffect(sequentialEffect);

            while (!mStepConductor.isFinished()) {
                long waitMillisBeforeNextStep;
                synchronized (mStepConductor.mLock) {
                    waitMillisBeforeNextStep = mStepConductor.getWaitMillisBeforeNextStepLocked();
                    if (waitMillisBeforeNextStep > 0) {
                        try {
                            mStepConductor.mLock.wait(waitMillisBeforeNextStep);
                        } catch (InterruptedException e) {
                        }
                    }
                }
                // Only run the next vibration step if we didn't have to wait in this loop.
                // If we waited then the queue may have changed, so loop again to re-evaluate
                // the scheduling of the queue top element.
                if (waitMillisBeforeNextStep <= 0) {
                    if (DEBUG) {
                        Slog.d(TAG, "Play vibration consuming next step...");
                    }
                    // Run the step without holding the main lock, to avoid HAL interactions from
                    // blocking the thread.
                    mStepConductor.runNextStep();
                }
                Vibration.Status status = mStop ? Vibration.Status.CANCELLED
                        : mStepConductor.calculateVibrationStatus(sequentialEffectSize);
                if (status != Vibration.Status.RUNNING && !mCalledVibrationCompleteCallback) {
                    // First time vibration stopped running, start clean-up tasks and notify
                    // callback immediately.
                    clientVibrationCompleteIfNotAlready(status);
                    if (status == Vibration.Status.CANCELLED) {
                        mStepConductor.cancel();
                    }
                }
                if (mForceStop) {
                    // Cancel every step and stop playing them right away, even clean-up steps.
                    mStepConductor.cancelImmediately();
                    clientVibrationCompleteIfNotAlready(Vibration.Status.CANCELLED);
                    break;
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
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

}
