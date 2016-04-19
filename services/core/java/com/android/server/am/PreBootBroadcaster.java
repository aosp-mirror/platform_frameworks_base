/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.am;

import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.util.ProgressReporter;

import java.util.List;

/**
 * Simple broadcaster that sends {@link Intent#ACTION_PRE_BOOT_COMPLETED} to all
 * system apps that register for it. Override {@link #onFinished()} to handle
 * when all broadcasts are finished.
 */
public abstract class PreBootBroadcaster extends IIntentReceiver.Stub {
    private static final String TAG = "PreBootBroadcaster";

    private final ActivityManagerService mService;
    private final int mUserId;
    private final ProgressReporter mProgress;

    private final Intent mIntent;
    private final List<ResolveInfo> mTargets;

    private int mIndex = 0;

    public PreBootBroadcaster(ActivityManagerService service, int userId,
            ProgressReporter progress) {
        mService = service;
        mUserId = userId;
        mProgress = progress;

        mIntent = new Intent(Intent.ACTION_PRE_BOOT_COMPLETED);
        mIntent.addFlags(Intent.FLAG_RECEIVER_BOOT_UPGRADE | Intent.FLAG_DEBUG_TRIAGED_MISSING);

        mTargets = mService.mContext.getPackageManager().queryBroadcastReceiversAsUser(mIntent,
                MATCH_SYSTEM_ONLY, UserHandle.of(userId));
    }

    public void sendNext() {
        if (mIndex >= mTargets.size()) {
            onFinished();
            return;
        }

        if (!mService.isUserRunning(mUserId, 0)) {
            Slog.i(TAG, "User " + mUserId + " is no longer running; skipping remaining receivers");
            onFinished();
            return;
        }

        final ResolveInfo ri = mTargets.get(mIndex++);
        final ComponentName componentName = ri.activityInfo.getComponentName();

        if (mProgress != null) {
            final CharSequence label = ri.activityInfo
                    .loadLabel(mService.mContext.getPackageManager());
            mProgress.setProgress(mIndex, mTargets.size(),
                    mService.mContext.getString(R.string.android_preparing_apk, label));
        }

        Slog.i(TAG, "Pre-boot of " + componentName.toShortString() + " for user " + mUserId);
        EventLogTags.writeAmPreBoot(mUserId, componentName.getPackageName());

        mIntent.setComponent(componentName);
        mService.broadcastIntentLocked(null, null, mIntent, null, this, 0, null, null, null,
                AppOpsManager.OP_NONE, null, true, false, ActivityManagerService.MY_PID,
                Process.SYSTEM_UID, mUserId);
    }

    @Override
    public void performReceive(Intent intent, int resultCode, String data, Bundle extras,
            boolean ordered, boolean sticky, int sendingUser) {
        sendNext();
    }

    public abstract void onFinished();
}
