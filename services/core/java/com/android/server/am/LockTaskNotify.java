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
import android.os.Handler;
import android.os.Message;
import android.view.accessibility.AccessibilityManager;
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
    private AccessibilityManager mAccessibilityManager;

    public LockTaskNotify(Context context) {
        mContext = context;
        mAccessibilityManager = (AccessibilityManager)
                mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
        mHandler = new H();
    }

    public void showToast(boolean isLocked) {
        mHandler.obtainMessage(H.SHOW_TOAST, isLocked ? 1 : 0, 0 /* Not used */).sendToTarget();
    }

    public void handleShowToast(boolean isLocked) {
        String text = mContext.getString(isLocked
                ? R.string.lock_to_app_toast_locked : R.string.lock_to_app_toast);
        if (!isLocked && mAccessibilityManager.isEnabled()) {
            text = mContext.getString(R.string.lock_to_app_toast_accessible);
        }
        Toast.makeText(mContext, text, Toast.LENGTH_LONG).show();
    }

    public void show(boolean starting) {
        int showString = R.string.lock_to_app_exit;
        if (starting) {
            showString = R.string.lock_to_app_start;
        }
        Toast.makeText(mContext, mContext.getString(showString), Toast.LENGTH_LONG).show();
    }

    private final class H extends Handler {
        private static final int SHOW_TOAST = 3;

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case SHOW_TOAST:
                    handleShowToast(msg.arg1 != 0);
                    break;
            }
        }
    }
}
