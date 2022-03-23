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

import android.content.pm.IncrementalStatesInfo;
import android.os.Handler;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.function.pooled.PooledLambda;

/**
 * Manages state transitions of a package installed on Incremental File System. Currently manages:
 * 1. loading state (whether a package is still loading or has been fully loaded).
 *
 * The following events might change the states of a package:
 * 1. Installation commit
 * 2. Loading progress changes
 *
 * @hide
 */
public final class IncrementalStates {
    private static final String TAG = "IncrementalStates";
    private static final boolean DEBUG = false;
    private final Handler mHandler = BackgroundThread.getHandler();
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final LoadingState mLoadingState;
    @GuardedBy("mLock")
    private Callback mCallback = null;

    public IncrementalStates() {
        // By default the package is not fully loaded (i.e., is loading)
        this(true, 0);
    }

    public IncrementalStates(boolean isLoading, float loadingProgress) {
        mLoadingState = new LoadingState(isLoading, loadingProgress);
    }

    /**
     * Callback interface to report that the loading state of this package has changed.
     */
    public interface Callback {
        /**
         * Reports that package is fully loaded.
         */
        void onPackageFullyLoaded();
    }

    /**
     * By calling this method, the caller indicates that package installation has just been
     * committed. Set the initial loading state after the package
     * is committed. Incremental packages are by-default loading; non-Incremental packages are not.
     *
     * @param isIncremental whether a package is installed on Incremental or not.
     */
    public void onCommit(boolean isIncremental) {
        if (DEBUG) {
            Slog.i(TAG, "received package commit event");
        }
        if (!isIncremental) {
            synchronized (mLock) {
                updateProgressLocked(1.0f);
            }
            onLoadingStateChanged();
        }
    }

    private void onLoadingStateChanged() {
        mHandler.post(PooledLambda.obtainRunnable(
                IncrementalStates::reportFullyLoaded,
                IncrementalStates.this).recycleOnUse());
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
     * Update the package loading progress to specified value.
     *
     * @param progress Value between [0, 1].
     */
    public void setProgress(float progress) {
        final boolean oldLoadingState;
        final boolean newLoadingState;
        synchronized (mLock) {
            oldLoadingState = mLoadingState.isLoading();
            if (oldLoadingState) {
                // Due to asynchronous progress reporting, incomplete progress might be received
                // after the app is migrated off incremental. Ignore such progress updates.
                updateProgressLocked(progress);
            }
            newLoadingState = mLoadingState.isLoading();
        }
        if (oldLoadingState && !newLoadingState) {
            // Only report the state change when loading state changes from true to false
            onLoadingStateChanged();
        }
    }

    /**
     * @return all current states in a Parcelable.
     */
    public IncrementalStatesInfo getIncrementalStatesInfo() {
        synchronized (mLock) {
            return new IncrementalStatesInfo(
                    mLoadingState.isLoading(),
                    mLoadingState.getProgress());
        }
    }

    private void updateProgressLocked(float progress) {
        if (DEBUG) {
            Slog.i(TAG, "received progress update: " + progress);
        }
        mLoadingState.setProgress(progress);
        if (Math.abs(1.0f - progress) < 0.00000001f) {
            if (DEBUG) {
                Slog.i(TAG, "package is fully loaded");
            }
            mLoadingState.setProgress(1.0f);
            if (mLoadingState.isLoading()) {
                mLoadingState.adoptNewLoadingStateLocked(false);
            }
        }
    }

    private class LoadingState {
        private boolean mIsLoading;
        private float mProgress;

        LoadingState(boolean isLoading, float loadingProgress) {
            mIsLoading = isLoading;
            // loading progress is reset to 1 if loading has finished
            mProgress = isLoading ? loadingProgress : 1;
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
        return l.mLoadingState.equals(mLoadingState);
    }

    @Override
    public int hashCode() {
        return mLoadingState.hashCode();
    }
}
