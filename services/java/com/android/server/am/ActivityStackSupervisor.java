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
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Looper;

import java.io.PrintWriter;
import java.util.ArrayList;

public class ActivityStackSupervisor {
    public static final int HOME_STACK_ID = 0;

    final ActivityManagerService mService;
    final Context mContext;
    final Looper mLooper;

    /** Dismiss the keyguard after the next activity is displayed? */
    private boolean mDismissKeyguardOnNextActivity = false;

    private ActivityStack mHomeStack;
    private ActivityStack mMainStack;
    private ArrayList<ActivityStack> mStacks = new ArrayList<ActivityStack>();

    public ActivityStackSupervisor(ActivityManagerService service, Context context,
            Looper looper) {
        mService = service;
        mContext = context;
        mLooper = looper;
    }

    void init() {
        mHomeStack = new ActivityStack(mService, mContext, mLooper, HOME_STACK_ID, this);
        setMainStack(mHomeStack);
        mService.mFocusedStack = mHomeStack;
    }

    void dismissKeyguard() {
        if (mDismissKeyguardOnNextActivity) {
            mDismissKeyguardOnNextActivity = false;
            mService.mWindowManager.dismissKeyguard();
        }
    }

    boolean isMainStack(ActivityStack stack) {
        return stack == mMainStack;
    }

    void setMainStack(ActivityStack stack) {
        mMainStack = stack;
    }

    void setDismissKeyguard(boolean dismiss) {
        mDismissKeyguardOnNextActivity = dismiss;
    }

    void startHomeActivity(Intent intent, ActivityInfo aInfo) {
        mHomeStack.startActivityLocked(null, intent, null, aInfo, null, null, 0, 0, 0, null, 0,
                null, false, null);
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mDismissKeyguardOnNextActivity:");
                pw.println(mDismissKeyguardOnNextActivity);
    }
}
