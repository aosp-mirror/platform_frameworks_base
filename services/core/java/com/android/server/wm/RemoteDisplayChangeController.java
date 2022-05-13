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

import android.annotation.Nullable;
import android.os.RemoteException;
import android.view.IDisplayChangeWindowCallback;
import android.window.DisplayAreaInfo;
import android.window.WindowContainerTransaction;

import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.ArrayList;
import java.util.List;

/**
 * A helper class, a wrapper around {@link android.view.IDisplayChangeWindowController} to perform
 * a synchronous display change in other parts (e.g. in the Shell) and continue the process
 * in the system server. It handles timeouts and multiple requests.
 * We have an instance of this controller for each display.
 */
public class RemoteDisplayChangeController {

    private static final int REMOTE_DISPLAY_CHANGE_TIMEOUT_MS = 800;

    private final WindowManagerService mService;
    private final int mDisplayId;

    private boolean mIsWaitingForRemoteDisplayChange;
    private final Runnable mTimeoutRunnable = () -> {
        continueDisplayChange(null /* appliedChange */, null /* transaction */);
    };

    private final List<ContinueRemoteDisplayChangeCallback> mCallbacks = new ArrayList<>();

    public RemoteDisplayChangeController(WindowManagerService service, int displayId) {
        mService = service;
        mDisplayId = displayId;
    }

    /**
     * A Remote change is when we are waiting for some registered (remote)
     * {@link IDisplayChangeWindowController} to calculate and return some hierarchy operations
     *  to perform in sync with the display change.
     */
    public boolean isWaitingForRemoteDisplayChange() {
        return mIsWaitingForRemoteDisplayChange;
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
        mIsWaitingForRemoteDisplayChange = true;
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

        final RemoteDisplayChange change = new RemoteDisplayChange(fromRotation, toRotation,
                newDisplayAreaInfo);
        final IDisplayChangeWindowCallback remoteCallback = createCallback(change);
        try {
            mService.mDisplayChangeController.onDisplayChange(mDisplayId, fromRotation, toRotation,
                    newDisplayAreaInfo, remoteCallback);

            mService.mH.removeCallbacks(mTimeoutRunnable);
            mService.mH.postDelayed(mTimeoutRunnable, REMOTE_DISPLAY_CHANGE_TIMEOUT_MS);
            return true;
        } catch (RemoteException e) {
            mIsWaitingForRemoteDisplayChange = false;
            return false;
        }
    }

    private void continueDisplayChange(@Nullable RemoteDisplayChange appliedChange,
            @Nullable WindowContainerTransaction transaction) {
        synchronized (mService.mGlobalLock) {
            if (appliedChange != null) {
                ProtoLog.v(WM_DEBUG_CONFIGURATION,
                        "Received remote change for Display[%d], applied: [%dx%d, rot = %d]",
                        mDisplayId,
                        appliedChange.displayAreaInfo != null ? appliedChange.displayAreaInfo
                                .configuration.windowConfiguration.getMaxBounds().width() : -1,
                        appliedChange.displayAreaInfo != null ? appliedChange.displayAreaInfo
                                .configuration.windowConfiguration.getMaxBounds().height() : -1,
                        appliedChange.toRotation);
            } else {
                ProtoLog.v(WM_DEBUG_CONFIGURATION, "Remote change for Display[%d]: timeout reached",
                        mDisplayId);
            }

            mIsWaitingForRemoteDisplayChange = false;

            for (int i = 0; i < mCallbacks.size(); i++) {
                ContinueRemoteDisplayChangeCallback callback = mCallbacks.get(i);
                callback.onContinueRemoteDisplayChange(appliedChange, transaction);
            }
        }
    }

    private IDisplayChangeWindowCallback createCallback(RemoteDisplayChange originalChange) {
        return new IDisplayChangeWindowCallback.Stub() {
                    @Override
                    public void continueDisplayChange(WindowContainerTransaction t) {
                        synchronized (mService.mGlobalLock) {
                            mService.mH.removeCallbacks(mTimeoutRunnable);
                            mService.mH.sendMessage(PooledLambda.obtainMessage(
                                    RemoteDisplayChangeController::continueDisplayChange,
                                    RemoteDisplayChangeController.this,
                                    originalChange, t));
                        }
                    }
                };
    }

    /**
     * Data class that contains information about a remote display change
     */
    public static class RemoteDisplayChange {
        final int fromRotation;
        final int toRotation;
        @Nullable
        final DisplayAreaInfo displayAreaInfo;

        public RemoteDisplayChange(int fromRotation, int toRotation,
                @Nullable DisplayAreaInfo displayAreaInfo) {
            this.fromRotation = fromRotation;
            this.toRotation = toRotation;
            this.displayAreaInfo = displayAreaInfo;
        }
    }

    /**
     * Callback interface to handle continuation of the remote display change
     */
    public interface ContinueRemoteDisplayChangeCallback {
        /**
         * This method is called when the remote display change has been applied
         * @param appliedChange the change that was applied or null if there was
         *                      an error during remote display change (e.g. timeout)
         * @param transaction window changes collected by the remote display change
         */
        void onContinueRemoteDisplayChange(@Nullable RemoteDisplayChange appliedChange,
                @Nullable WindowContainerTransaction transaction);
    }
}
