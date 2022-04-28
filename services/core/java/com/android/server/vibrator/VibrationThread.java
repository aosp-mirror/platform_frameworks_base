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
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.WorkSource;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.NoSuchElementException;
import java.util.Objects;

/** Plays a {@link Vibration} in dedicated thread. */
final class VibrationThread extends Thread {
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
        void onVibrationThreadReleased(long vibrationId);
    }

    private final PowerManager.WakeLock mWakeLock;
    private final VibrationThread.VibratorManagerHooks mVibratorManagerHooks;

    // mLock is used here to communicate that the thread's work status has changed. The
    // VibrationThread is expected to wait until work arrives, and other threads may wait until
    // work has finished. Therefore, any changes to the conductor must be followed by a notifyAll
    // so that threads check if their desired state is achieved.
    private final Object mLock = new Object();

    /**
     * The conductor that is intended to be active. Null value means that a new conductor can
     * be set to run. Note that this field is only reset to null when mExecutingConductor has
     * completed, so the two fields should be in sync.
     */
    @GuardedBy("mLock")
    @Nullable
    private VibrationStepConductor mRequestedActiveConductor;

    /**
     * The conductor being executed by this thread, should only be accessed within this thread's
     * execution. i.e. not thread-safe. {@link #mRequestedActiveConductor} is for cross-thread
     * signalling.
     */
    @Nullable
    private VibrationStepConductor mExecutingConductor;

    // Variable only set and read in main thread, no need to lock.
    private boolean mCalledVibrationCompleteCallback = false;

    VibrationThread(PowerManager.WakeLock wakeLock, VibratorManagerHooks vibratorManagerHooks) {
        mWakeLock = wakeLock;
        mVibratorManagerHooks = vibratorManagerHooks;
    }

    /**
     * Sets/activates the current vibration. Must only be called after receiving
     * onVibratorsReleased from the previous vibration.
     *
     * @return false if VibrationThread couldn't accept it, which shouldn't happen unless called
     *  before the release callback.
     */
    boolean runVibrationOnVibrationThread(VibrationStepConductor conductor) {
        synchronized (mLock) {
            if (mRequestedActiveConductor != null) {
                Slog.wtf(TAG, "Attempt to start vibration when one already running");
                return false;
            }
            mRequestedActiveConductor = conductor;
            mLock.notifyAll();
        }
        return true;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        while (true) {
            // mExecutingConductor is only modified in this loop.
            mExecutingConductor = Objects.requireNonNull(waitForVibrationRequest());

            mCalledVibrationCompleteCallback = false;
            runCurrentVibrationWithWakeLock();
            if (!mExecutingConductor.isFinished()) {
                Slog.wtf(TAG, "VibrationThread terminated with unfinished vibration");
            }
            synchronized (mLock) {
                // Allow another vibration to be requested.
                mRequestedActiveConductor = null;
            }
            // The callback is run without holding the lock, as it may initiate another vibration.
            // It's safe to notify even if mVibratorConductor has been re-written, as the "wait"
            // methods all verify their waited state before returning. In reality though, if the
            // manager is waiting for the thread to finish, then there is no pending vibration
            // for this thread.
            // No point doing this in finally, as if there's an exception, this thread will die
            // and be unusable anyway.
            mVibratorManagerHooks.onVibrationThreadReleased(mExecutingConductor.getVibration().id);
            synchronized (mLock) {
                mLock.notifyAll();
            }
            mExecutingConductor = null;
        }
    }

    /**
     * Waits until the VibrationThread has finished processing, timing out after the given
     * number of milliseconds. In general, external locking will manage the ordering of this
     * with calls to {@link #runVibrationOnVibrationThread}.
     *
     * @return true if the vibration completed, or false if waiting timed out.
     */
    public boolean waitForThreadIdle(long maxWaitMillis) {
        long now = SystemClock.elapsedRealtime();
        long deadline = now + maxWaitMillis;
        synchronized (mLock) {
            while (true) {
                if (mRequestedActiveConductor == null) {
                    return true;  // Done
                }
                if (now >= deadline) {  // Note that thread.wait(0) waits indefinitely.
                    return false;  // Timed out.
                }
                try {
                    mLock.wait(deadline - now);
                } catch (InterruptedException e) {
                    Slog.w(TAG, "VibrationThread interrupted waiting to stop, continuing");
                }
                now = SystemClock.elapsedRealtime();
            }
        }
    }

    /** Waits for a signal indicating a vibration is ready to run, then returns its conductor. */
    @NonNull
    private VibrationStepConductor waitForVibrationRequest() {
        while (true) {
            synchronized (mLock) {
                if (mRequestedActiveConductor != null) {
                    return mRequestedActiveConductor;
                }
                try {
                    mLock.wait();
                } catch (InterruptedException e) {
                    Slog.w(TAG, "VibrationThread interrupted waiting to start, continuing");
                }
            }
        }
    }

    /**
     * Only for testing: this method relies on the requested-active conductor, rather than
     * the executing conductor that's not intended for other threads.
     *
     * @return true if the vibration that's currently desired to be active has the given id.
     */
    @VisibleForTesting
    boolean isRunningVibrationId(long id) {
        synchronized (mLock) {
            return (mRequestedActiveConductor != null
                    && mRequestedActiveConductor.getVibration().id == id);
        }
    }

    /** Runs the VibrationThread ensuring that the wake lock is acquired and released. */
    private void runCurrentVibrationWithWakeLock() {
        WorkSource workSource = new WorkSource(mExecutingConductor.getVibration().uid);
        mWakeLock.setWorkSource(workSource);
        mWakeLock.acquire();
        try {
            try {
                runCurrentVibrationWithWakeLockAndDeathLink();
            } finally {
                clientVibrationCompleteIfNotAlready(Vibration.Status.FINISHED_UNEXPECTED);
            }
        } finally {
            mWakeLock.release();
            mWakeLock.setWorkSource(null);
        }
    }

    /**
     * Runs the VibrationThread with the binder death link, handling link/unlink failures.
     * Called from within runWithWakeLock.
     */
    private void runCurrentVibrationWithWakeLockAndDeathLink() {
        IBinder vibrationBinderToken = mExecutingConductor.getVibration().token;
        try {
            vibrationBinderToken.linkToDeath(mExecutingConductor, 0);
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
                vibrationBinderToken.unlinkToDeath(mExecutingConductor, 0);
            } catch (NoSuchElementException e) {
                Slog.wtf(TAG, "Failed to unlink token", e);
            }
        }
    }

    // Indicate that the vibration is complete. This can be called multiple times only for
    // convenience of handling error conditions - an error after the client is complete won't
    // affect the status.
    private void clientVibrationCompleteIfNotAlready(Vibration.Status completedStatus) {
        if (!mCalledVibrationCompleteCallback) {
            mCalledVibrationCompleteCallback = true;
            mVibratorManagerHooks.onVibrationCompleted(
                    mExecutingConductor.getVibration().id, completedStatus);
        }
    }

    private void playVibration() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "playVibration");
        try {
            mExecutingConductor.prepareToStart();
            while (!mExecutingConductor.isFinished()) {
                boolean readyToRun = mExecutingConductor.waitUntilNextStepIsDue();
                // If we waited, don't run the next step, but instead re-evaluate status.
                if (readyToRun) {
                    if (DEBUG) {
                        Slog.d(TAG, "Play vibration consuming next step...");
                    }
                    // Run the step without holding the main lock, to avoid HAL interactions from
                    // blocking the thread.
                    mExecutingConductor.runNextStep();
                }

                Vibration.Status status = mExecutingConductor.calculateVibrationStatus();
                // This block can only run once due to mCalledVibrationCompleteCallback.
                if (status != Vibration.Status.RUNNING && !mCalledVibrationCompleteCallback) {
                    // First time vibration stopped running, start clean-up tasks and notify
                    // callback immediately.
                    clientVibrationCompleteIfNotAlready(status);
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }
}
