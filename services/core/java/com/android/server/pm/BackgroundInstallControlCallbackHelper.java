/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

import android.annotation.NonNull;
import android.app.BackgroundInstallControlManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IRemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ServiceThread;

public class BackgroundInstallControlCallbackHelper {

    @VisibleForTesting static final String FLAGGED_PACKAGE_NAME_KEY = "packageName";
    @VisibleForTesting static final String FLAGGED_USER_ID_KEY = "userId";
    private static final String TAG = "BackgroundInstallControlCallbackHelper";

    private final Handler mHandler;

    BackgroundInstallControlCallbackHelper() {
        HandlerThread backgroundThread =
                new ServiceThread(
                        "BackgroundInstallControlCallbackHelperBg",
                        THREAD_PRIORITY_BACKGROUND,
                        true);
        backgroundThread.start();
        mHandler = new Handler(backgroundThread.getLooper());
    }

    @NonNull @VisibleForTesting
    final RemoteCallbackList<IRemoteCallback> mCallbacks = new RemoteCallbackList<>();

    /** Registers callback that gets invoked upon detection of an MBA
     *
     * NOTE: The callback is user context agnostic and currently broadcasts to all users of other
     * users app installs. This is fine because the API is for SystemServer use only.
     */
    public void registerBackgroundInstallCallback(IRemoteCallback callback) {
        synchronized (mCallbacks) {
            mCallbacks.register(callback, null);
        }
    }

    /** Unregisters callback */
    public void unregisterBackgroundInstallCallback(IRemoteCallback callback) {
        synchronized (mCallbacks) {
            mCallbacks.unregister(callback);
        }
    }

    /**
     * Invokes all registered callbacks Callbacks are processed through user provided-threads and
     * parameters are passed in via {@link BackgroundInstallControlManager} InstallEvent
     */
    public void notifyAllCallbacks(int userId, String packageName) {
        Bundle extras = new Bundle();
        extras.putCharSequence(FLAGGED_PACKAGE_NAME_KEY, packageName);
        extras.putInt(FLAGGED_USER_ID_KEY, userId);
        synchronized (mCallbacks) {
            mHandler.post(
                    () ->
                            mCallbacks.broadcast(
                                    callback -> {
                                        try {
                                            callback.sendResult(extras);
                                        } catch (RemoteException e) {
                                            Slog.e(
                                                    TAG,
                                                    "error detected: " + e.getLocalizedMessage(),
                                                    e);
                                        }
                                    }));
        }
    }
}
