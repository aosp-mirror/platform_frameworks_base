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

package com.android.server.wm;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_CONFIGURATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;
import android.util.Slog;
import android.view.IDisplayChangeWindowCallback;
import android.window.DisplayAreaInfo;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;

import java.util.ArrayList;
import java.util.List;

/**
 * A helper class, a wrapper around {@link android.view.IDisplayChangeWindowController} to perform
 * a synchronous display change in other parts (e.g. in the Shell) and continue the process
 * in the system server. It handles timeouts and multiple requests.
 * We have an instance of this controller for each display.
 */
public class RemoteDisplayChangeController {

    private static final String TAG = "RemoteDisplayChangeController";

    private static final int REMOTE_DISPLAY_CHANGE_TIMEOUT_MS = 800;

    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;

    private final Runnable mTimeoutRunnable = this::onContinueTimedOut;

    // all remote changes that haven't finished yet.
    private final List<ContinueRemoteDisplayChangeCallback> mCallbacks = new ArrayList<>();

    RemoteDisplayChangeController(@NonNull DisplayContent displayContent) {
        mService = displayContent.mWmService;
        mDisplayContent = displayContent;
    }

    /**
     * A Remote change is when we are waiting for some registered (remote)
     * {@link IDisplayChangeWindowController} to calculate and return some hierarchy operations
     *  to perform in sync with the display change.
     */
    public boolean isWaitingForRemoteDisplayChange() {
        return !mCallbacks.isEmpty();
    }

    /**
     * Starts remote display change
     * @param fromRotation rotation before the change
     * @param toRotation rotation after the change
     * @param newDisplayAreaInfo display area info after change
     * @param callback that will be called after completing remote display change
     * @return true if the change successfully started, false otherwise
     */
    public boolean performRemoteDisplayChange(
            int fromRotation, int toRotation,
            @Nullable DisplayAreaInfo newDisplayAreaInfo,
            ContinueRemoteDisplayChangeCallback callback) {
        if (mService.mDisplayChangeController == null) {
            return false;
        }
        mCallbacks.add(callback);

        if (newDisplayAreaInfo != null) {
            ProtoLog.v(WM_DEBUG_CONFIGURATION,
                    "Starting remote display change: "
                            + "from [rot = %d], "
                            + "to [%dx%d, rot = %d]",
                    fromRotation,
                    newDisplayAreaInfo.configuration.windowConfiguration
                            .getMaxBounds().width(),
                    newDisplayAreaInfo.configuration.windowConfiguration
                            .getMaxBounds().height(),
                    toRotation);
        }

        final IDisplayChangeWindowCallback remoteCallback = createCallback(callback);
        try {
            mService.mH.removeCallbacks(mTimeoutRunnable);
            mService.mH.postDelayed(mTimeoutRunnable, REMOTE_DISPLAY_CHANGE_TIMEOUT_MS);
            mService.mDisplayChangeController.onDisplayChange(mDisplayContent.mDisplayId,
                    fromRotation, toRotation, newDisplayAreaInfo, remoteCallback);
            return true;
        } catch (RemoteException e) {
            Slog.e(TAG, "Exception while dispatching remote display-change", e);
            mCallbacks.remove(callback);
            return false;
        }
    }

    private void onContinueTimedOut() {
        Slog.e(TAG, "RemoteDisplayChange timed-out, UI might get messed-up after this.");
        // timed-out, so run all continue callbacks and clear the list
        synchronized (mService.mGlobalLock) {
            for (int i = 0; i < mCallbacks.size(); ++i) {
                mCallbacks.get(i).onContinueRemoteDisplayChange(null /* transaction */);
            }
            mCallbacks.clear();
            onCompleted();
        }
    }

    /** Called when all remote callbacks are done. */
    private void onCompleted() {
        // Because DisplayContent#sendNewConfiguration() will be skipped if there are pending remote
        // changes, check again when all remote callbacks are done. E.g. callback X is done but
        // there is a pending callback Y so its invocation is skipped, and when the callback Y is
        // done, it doesn't call sendNewConfiguration().
        if (mDisplayContent.mWaitingForConfig) {
            mDisplayContent.sendNewConfiguration();
        }
    }

    @VisibleForTesting
    void continueDisplayChange(@NonNull ContinueRemoteDisplayChangeCallback callback,
            @Nullable WindowContainerTransaction transaction) {
        synchronized (mService.mGlobalLock) {
            int idx = mCallbacks.indexOf(callback);
            if (idx < 0) {
                // already called this callback or a more-recent one (eg. via timeout)
                return;
            }
            for (int i = 0; i < idx; ++i) {
                // Expect remote callbacks in order. If they don't come in order, then force
                // ordering by continuing everything up until this one with empty transactions.
                mCallbacks.get(i).onContinueRemoteDisplayChange(null /* transaction */);
            }
            // The "toIndex" is exclusive, so it needs +1 to clear the current calling callback.
            mCallbacks.subList(0, idx + 1).clear();
            final boolean completed = mCallbacks.isEmpty();
            if (completed) {
                mService.mH.removeCallbacks(mTimeoutRunnable);
            }
            callback.onContinueRemoteDisplayChange(transaction);
            if (completed) {
                onCompleted();
            }
        }
    }

    private IDisplayChangeWindowCallback createCallback(
            @NonNull ContinueRemoteDisplayChangeCallback callback) {
        return new IDisplayChangeWindowCallback.Stub() {
                    @Override
                    public void continueDisplayChange(WindowContainerTransaction t) {
                        synchronized (mService.mGlobalLock) {
                            if (!mCallbacks.contains(callback)) {
                                // already ran this callback or a more-recent one.
                                return;
                            }
                            mService.mH.post(() -> RemoteDisplayChangeController.this
                                    .continueDisplayChange(callback, t));
                        }
                    }
                };
    }

    /**
     * Callback interface to handle continuation of the remote display change
     */
    public interface ContinueRemoteDisplayChangeCallback {
        /**
         * This method is called when the remote display change has been applied
         * @param transaction window changes collected by the remote display change
         */
        void onContinueRemoteDisplayChange(@Nullable WindowContainerTransaction transaction);
    }
}
