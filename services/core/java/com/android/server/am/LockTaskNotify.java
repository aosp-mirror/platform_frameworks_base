/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.am;

import android.content.Context;
import android.os.SystemClock;
import android.util.Slog;
import android.view.WindowManager;
import android.widget.Toast;

import com.android.internal.R;

/**
 *  Helper to manage showing/hiding a image to notify them that they are entering or exiting screen
 *  pinning mode. All exposed methods should be called from a handler thread.
 */
public class LockTaskNotify {
    private static final String TAG = "LockTaskNotify";
    private static final long SHOW_TOAST_MINIMUM_INTERVAL = 1000;

    private final Context mContext;
    private Toast mLastToast;
    private long mLastShowToastTime;

    public LockTaskNotify(Context context) {
        mContext = context;
    }

    /** Show "Screen pinned" toast. */
    void showPinningStartToast() {
        makeAllUserToastAndShow(R.string.lock_to_app_start);
    }

    /** Show "Screen unpinned" toast. */
    void showPinningExitToast() {
        makeAllUserToastAndShow(R.string.lock_to_app_exit);
    }

    /** Show a toast that describes the gesture the user should use to escape pinned mode. */
    void showEscapeToast() {
        long showToastTime = SystemClock.elapsedRealtime();
        if ((showToastTime - mLastShowToastTime) < SHOW_TOAST_MINIMUM_INTERVAL) {
            Slog.i(TAG, "Ignore toast since it is requested in very short interval.");
            return;
        }
        if (mLastToast != null) {
            mLastToast.cancel();
        }
        mLastToast = makeAllUserToastAndShow(R.string.lock_to_app_toast);
        mLastShowToastTime = showToastTime;
    }

    private Toast makeAllUserToastAndShow(int resId) {
        Toast toast = Toast.makeText(mContext, resId, Toast.LENGTH_LONG);
        toast.getWindowParams().privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        toast.show();
        return toast;
    }
}
