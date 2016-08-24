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

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.view.WindowManager;
import android.widget.Toast;

import com.android.internal.R;

/**
 *  Helper to manage showing/hiding a image to notify them that they are entering
 *  or exiting lock-to-app mode.
 */
public class LockTaskNotify {
    private static final String TAG = "LockTaskNotify";

    private final Context mContext;
    private final H mHandler;
    private Toast mLastToast;

    public LockTaskNotify(Context context) {
        mContext = context;
        mHandler = new H();
    }

    public void showToast(int lockTaskModeState) {
        mHandler.obtainMessage(H.SHOW_TOAST, lockTaskModeState, 0 /* Not used */).sendToTarget();
    }

    public void handleShowToast(int lockTaskModeState) {
        String text = null;
        if (lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED) {
            text = mContext.getString(R.string.lock_to_app_toast_locked);
        } else if (lockTaskModeState == ActivityManager.LOCK_TASK_MODE_PINNED) {
            text = mContext.getString(R.string.lock_to_app_toast);
        }
        if (text == null) {
            return;
        }
        if (mLastToast != null) {
            mLastToast.cancel();
        }
        mLastToast = makeAllUserToastAndShow(text);
    }

    public void show(boolean starting) {
        int showString = R.string.lock_to_app_exit;
        if (starting) {
            showString = R.string.lock_to_app_start;
        }
        makeAllUserToastAndShow(mContext.getString(showString));
    }

    private Toast makeAllUserToastAndShow(String text) {
        Toast toast = Toast.makeText(mContext, text, Toast.LENGTH_LONG);
        toast.getWindowParams().privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        toast.show();
        return toast;
    }

    private final class H extends Handler {
        private static final int SHOW_TOAST = 3;

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case SHOW_TOAST:
                    handleShowToast(msg.arg1);
                    break;
            }
        }
    }
}
