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

package com.android.server.pm;

import static android.os.incremental.IStorageHealthListener.HEALTH_STATUS_OK;
import static android.os.incremental.IStorageHealthListener.HEALTH_STATUS_UNHEALTHY;
import static android.os.incremental.IStorageHealthListener.HEALTH_STATUS_UNHEALTHY_STORAGE;
import static android.os.incremental.IStorageHealthListener.HEALTH_STATUS_UNHEALTHY_TRANSPORT;

import android.content.pm.IncrementalStatesInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.function.Consumer;

/**
 * Manages state transitions of a package installed on Incremental File System. Currently manages:
 * 1. startable state (whether a package is allowed to be launched), and
 * 2. loading state (whether a package is still loading or has been fully loaded).
 *
 * The following events might change the states of a package:
 * 1. Installation commit
 * 2. Incremental storage health changes
 * 4. Loading progress changes
 *
 * @hide
 */
public final class IncrementalStates {
    private static final String TAG = "IncrementalStates";
    private static final boolean DEBUG = false;
    private final Handler mHandler = BackgroundThread.getHandler();
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private int mStorageHealthStatus = HEALTH_STATUS_OK;
    @GuardedBy("mLock")
    private final LoadingState mLoadingState;
    @GuardedBy("mLock")
    private StartableState mStartableState;
    @GuardedBy("mLock")
    private Callback mCallback = null;
    private final Consumer<Integer> mStatusConsumer;

    public IncrementalStates() {
        // By default the package is not startable and not fully loaded (i.e., is loading)
        this(false, true);
    }

    public IncrementalStates(boolean isStartable, boolean isLoading) {
        mStartableState = new StartableState(isStartable);
        mLoadingState = new LoadingState(isLoading);
        mStatusConsumer = new StatusConsumer();
    }

    /**
     * Callback interface to report that the startable state of this package has changed.
     */
    public interface Callback {
        /**
         * Reports that the package is now unstartable and the unstartable reason.
         */
        void onPackageUnstartable(int reason);

        /**
         * Reports that the package is now startable.
         */
        void onPackageStartable();

        /**
         * Reports that package is fully loaded.
         */
        void onPackageFullyLoaded();
    }

    /**
     * By calling this method, the caller indicates that package installation has just been
     * committed. The package becomes startable. Set the initial loading state after the package
     * is committed. Incremental packages are by-default loading; non-Incremental packages are not.
     *
     * @param isIncremental whether a package is installed on Incremental or not.
     */
    public void onCommit(boolean isIncremental) {
        if (DEBUG) {
            Slog.i(TAG, "received package commit event");
        }
        synchronized (mLock) {
            if (!mStartableState.isStartable()) {
                mStartableState.adoptNewStartableStateLocked(true);
            }
            if (!isIncremental) {
                updateProgressLocked(1);
            }
        }
        mHandler.post(PooledLambda.obtainRunnable(
                IncrementalStates::reportStartableState,
                IncrementalStates.this).recycleOnUse());
        if (!isIncremental) {
            mHandler.post(PooledLambda.obtainRunnable(
                    IncrementalStates::reportFullyLoaded,
                    IncrementalStates.this).recycleOnUse());
        }
    }

    /**
     * Change the startable state if the app has crashed or ANR'd during loading.
     * If the app is not loading (i.e., fully loaded), this event doesn't change startable state.
     */
    public void onCrashOrAnr() {
        if (DEBUG) {
            Slog.i(TAG, "received package crash or ANR event");
        }
        final boolean startableStateChanged;
        synchronized (mLock) {
            if (mStartableState.isStartable() && mLoadingState.isLoading()) {
                // Changing from startable -> unstartable only if app is still loading.
                mStartableState.adoptNewStartableStateLocked(false);
                startableStateChanged = true;
            } else {
                // If the app is fully loaded, the crash or ANR is caused by the app itself, so
                // we do not change the startable state.
                startableStateChanged = false;
            }
        }
        if (startableStateChanged) {
            mHandler.post(PooledLambda.obtainRunnable(
                    IncrementalStates::reportStartableState,
                    IncrementalStates.this).recycleOnUse());
        }
    }

    private void reportStartableState() {
        final Callback callback;
        final boolean startable;
        final int reason;
        synchronized (mLock) {
            callback = mCallback;
            startable = mStartableState.isStartable();
            reason = mStartableState.getUnstartableReason();
        }
        if (callback == null) {
            return;
        }
        if (startable) {
            callback.onPackageStartable();
        } else {
            callback.onPackageUnstartable(reason);
        }
    }

    private void reportFullyLoaded() {
        final Callback callback;
        synchronized (mLock) {
            callback = mCallback;
        }
        if (callback != null) {
            callback.onPackageFullyLoaded();
        }
    }

    private class StatusConsumer implements Consumer<Integer> {
        @Override
        public void accept(Integer storageStatus) {
            final boolean oldState, newState;
            synchronized (mLock) {
                if (!mLoadingState.isLoading()) {
                    // Do nothing if the package is already fully loaded
                    return;
                }
                oldState = mStartableState.isStartable();
                mStorageHealthStatus = storageStatus;
                updateStartableStateLocked();
                newState = mStartableState.isStartable();
            }
            if (oldState != newState) {
                mHandler.post(PooledLambda.obtainRunnable(IncrementalStates::reportStartableState,
                        IncrementalStates.this).recycleOnUse());
            }
        }
    };

    /**
     * By calling this method, the caller indicates that there issues with the Incremental
     * Storage,
     * on which the package is installed. The state will change according to the status
     * code defined in {@code IStorageHealthListener}.
     */
    public void onStorageHealthStatusChanged(int storageHealthStatus) {
        if (DEBUG) {
            Slog.i(TAG, "received storage health status changed event : storageHealthStatus="
                    + storageHealthStatus);
        }
        mStatusConsumer.accept(storageHealthStatus);
    }

    /**
     * Use the specified callback to report state changing events.
     *
     * @param callback Object to report new state.
     */
    public void setCallback(Callback callback) {
        if (DEBUG) {
            Slog.i(TAG, "registered callback");
        }
        synchronized (mLock) {
            mCallback = callback;
        }
    }

    /**
     * Update the package loading progress to specified value. This might change startable state.
     *
     * @param progress Value between [0, 1].
     */
    public void setProgress(float progress) {
        final boolean newLoadingState;
        final boolean oldStartableState, newStartableState;
        synchronized (mLock) {
            oldStartableState = mStartableState.isStartable();
            updateProgressLocked(progress);
            newLoadingState = mLoadingState.isLoading();
            newStartableState = mStartableState.isStartable();
        }
        if (!newLoadingState) {
            mHandler.post(PooledLambda.obtainRunnable(
                    IncrementalStates::reportFullyLoaded,
                    IncrementalStates.this).recycleOnUse());
        }
        if (newStartableState != oldStartableState) {
            mHandler.post(PooledLambda.obtainRunnable(
                    IncrementalStates::reportStartableState,
                    IncrementalStates.this).recycleOnUse());
        }
    }

    /**
     * @return the current startable state.
     */
    public boolean isStartable() {
        synchronized (mLock) {
            return mStartableState.isStartable();
        }
    }

    /**
     * @return Whether the package is still being loaded or has been fully loaded.
     */
    public boolean isLoading() {
        synchronized (mLock) {
            return mLoadingState.isLoading();
        }
    }

    /**
     * @return all current states in a Parcelable.
     */
    public IncrementalStatesInfo getIncrementalStatesInfo() {
        synchronized (mLock) {
            return new IncrementalStatesInfo(mStartableState.isStartable(),
                    mLoadingState.isLoading(),
                    mLoadingState.getProgress());
        }
    }

    /**
     * Determine the next state based on the current state, current stream status and storage
     * health
     * status. If the next state is different from the current state, proceed with state
     * change.
     */
    private void updateStartableStateLocked() {
        final boolean currentState = mStartableState.isStartable();
        boolean nextState = currentState;
        if (!currentState) {
            if (mStorageHealthStatus == HEALTH_STATUS_OK) {
                // change from unstartable -> startable
                nextState = true;
            }
        } else {
            if (mStorageHealthStatus == HEALTH_STATUS_UNHEALTHY
                    || mStorageHealthStatus == HEALTH_STATUS_UNHEALTHY_STORAGE
                    || mStorageHealthStatus == HEALTH_STATUS_UNHEALTHY_TRANSPORT) {
                // change from startable -> unstartable
                nextState = false;
            }
        }
        if (nextState == currentState) {
            return;
        }
        mStartableState.adoptNewStartableStateLocked(nextState);
    }

    private void updateProgressLocked(float progress) {
        if (DEBUG) {
            Slog.i(TAG, "received progress update: " + progress);
        }
        mLoadingState.setProgress(progress);
        if (1 - progress < 0.001) {
            if (DEBUG) {
                Slog.i(TAG, "package is fully loaded");
            }
            mLoadingState.setProgress(1);
            if (mLoadingState.isLoading()) {
                mLoadingState.adoptNewLoadingStateLocked(false);
            }
            // Also updates startable state if necessary
            if (!mStartableState.isStartable()) {
                mStartableState.adoptNewStartableStateLocked(true);
            }
        }
    }

    private class StartableState {
        private boolean mIsStartable;
        private int mUnstartableReason = PackageManager.UNSTARTABLE_REASON_UNKNOWN;

        StartableState(boolean isStartable) {
            mIsStartable = isStartable;
        }

        public boolean isStartable() {
            return mIsStartable;
        }

        public int getUnstartableReason() {
            return mUnstartableReason;
        }

        public void adoptNewStartableStateLocked(boolean nextState) {
            if (DEBUG) {
                Slog.i(TAG, "startable state changed from " + mIsStartable + " to " + nextState);
            }
            mIsStartable = nextState;
            mUnstartableReason = getUnstartableReasonLocked();
        }

        private int getUnstartableReasonLocked() {
            if (mIsStartable) {
                return PackageManager.UNSTARTABLE_REASON_UNKNOWN;
            }
            // Translate stream status to reason for unstartable state
            switch (mStorageHealthStatus) {
                case HEALTH_STATUS_UNHEALTHY_STORAGE:
                    return PackageManager.UNSTARTABLE_REASON_INSUFFICIENT_STORAGE;
                case HEALTH_STATUS_UNHEALTHY_TRANSPORT:
                    return PackageManager.UNSTARTABLE_REASON_CONNECTION_ERROR;
                default:
                    return PackageManager.UNSTARTABLE_REASON_UNKNOWN;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof StartableState)) {
                return false;
            }
            StartableState l = (StartableState) o;
            return l.mIsStartable == mIsStartable;
        }

        @Override
        public int hashCode() {
            return Boolean.hashCode(mIsStartable);
        }
    }

    private class LoadingState {
        private boolean mIsLoading;
        private float mProgress;

        LoadingState(boolean isLoading) {
            mIsLoading = isLoading;
            mProgress = isLoading ? 0 : 1;
        }

        public boolean isLoading() {
            return mIsLoading;
        }

        public float getProgress() {
            return mProgress;
        }

        public void setProgress(float progress) {
            mProgress = progress;
        }

        public void adoptNewLoadingStateLocked(boolean nextState) {
            if (DEBUG) {
                Slog.i(TAG, "Loading state changed from " + mIsLoading + " to " + nextState);
            }
            mIsLoading = nextState;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof LoadingState)) {
                return false;
            }
            LoadingState l = (LoadingState) o;
            return l.mIsLoading == mIsLoading && l.mProgress == mProgress;
        }

        @Override
        public int hashCode() {
            int hashCode = Boolean.hashCode(mIsLoading);
            hashCode = 31 * hashCode + Float.hashCode(mProgress);
            return hashCode;
        }
    }



    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof IncrementalStates)) {
            return false;
        }
        IncrementalStates l = (IncrementalStates) o;
        return l.mStorageHealthStatus == mStorageHealthStatus
                && l.mStartableState.equals(mStartableState)
                && l.mLoadingState.equals(mLoadingState);
    }

    @Override
    public int hashCode() {
        int hashCode = mStartableState.hashCode();
        hashCode = 31 * hashCode + mLoadingState.hashCode();
        hashCode = 31 * hashCode + mStorageHealthStatus;
        return hashCode;
    }
}
