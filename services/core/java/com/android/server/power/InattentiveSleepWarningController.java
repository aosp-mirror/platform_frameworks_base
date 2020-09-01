/**
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.power;

import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.IStatusBarService;

/**
 * Communicates with System UI to show/hide the inattentive sleep warning overlay.
 */
@VisibleForTesting
public class InattentiveSleepWarningController {
    private static final String TAG = "InattentiveSleepWarning";

    private final Handler mHandler = new Handler();

    private boolean mIsShown;
    private IStatusBarService mStatusBarService;

    InattentiveSleepWarningController() {
    }

    /**
     * Returns true if the warning is currently being displayed, false otherwise.
     */
    @GuardedBy("PowerManagerService.mLock")
    public boolean isShown() {
        return mIsShown;
    }

    /**
     * Show the warning.
     */
    @GuardedBy("PowerManagerService.mLock")
    public void show() {
        if (isShown()) {
            return;
        }

        mHandler.post(this::showInternal);
        mIsShown = true;
    }

    private void showInternal() {
        try {
            getStatusBar().showInattentiveSleepWarning();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to show inattentive sleep warning", e);
            mIsShown = false;
        }
    }

    /**
     * Dismiss the warning.
     */
    @GuardedBy("PowerManagerService.mLock")
    public void dismiss(boolean animated) {
        if (!isShown()) {
            return;
        }

        mHandler.post(() -> dismissInternal(animated));
        mIsShown = false;
    }

    private void dismissInternal(boolean animated) {
        try {
            getStatusBar().dismissInattentiveSleepWarning(animated);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to dismiss inattentive sleep warning", e);
        }
    }

    private IStatusBarService getStatusBar() {
        if (mStatusBarService == null) {
            mStatusBarService = IStatusBarService.Stub.asInterface(
                    ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        }

        return mStatusBarService;
    }
}
