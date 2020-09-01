/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.keyguard;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;

public class WorkLockActivityController {
    private static final String TAG = WorkLockActivityController.class.getSimpleName();

    private final Context mContext;
    private final IActivityTaskManager mIatm;

    public WorkLockActivityController(Context context) {
        this(context, ActivityManagerWrapper.getInstance(), ActivityTaskManager.getService());
    }

    @VisibleForTesting
    WorkLockActivityController(
            Context context, ActivityManagerWrapper am, IActivityTaskManager iAtm) {
        mContext = context;
        mIatm = iAtm;

        am.registerTaskStackListener(mLockListener);
    }

    private void startWorkChallengeInTask(int taskId, int userId) {
        ActivityManager.TaskDescription taskDescription = null;
        try {
            taskDescription = mIatm.getTaskDescription(taskId);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to get description for task=" + taskId);
        }
        Intent intent = new Intent(KeyguardManager.ACTION_CONFIRM_DEVICE_CREDENTIAL_WITH_USER)
                .setComponent(new ComponentName(mContext, WorkLockActivity.class))
                .putExtra(Intent.EXTRA_USER_ID, userId)
                .putExtra(WorkLockActivity.EXTRA_TASK_DESCRIPTION, taskDescription)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchTaskId(taskId);
        options.setTaskOverlay(true, false /* canResume */);

        final int result = startActivityAsUser(intent, options.toBundle(), UserHandle.USER_CURRENT);
        if (ActivityManager.isStartResultSuccessful(result)) {
            // OK
        } else {
            // Starting the activity inside the task failed. We can't be sure why, so to be
            // safe just remove the whole task if it still exists.
            try {
                mIatm.removeTask(taskId);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to get description for task=" + taskId);
            }
        }
    }

    /**
     * Version of {@link Context#startActivityAsUser} which keeps the success code from
     * IActivityManager, so we can read back whether ActivityManager thinks it started properly.
     */
    private int startActivityAsUser(Intent intent, Bundle options, int userId) {
        try {
            return mIatm.startActivityAsUser(
                    mContext.getIApplicationThread() /*caller*/,
                    mContext.getBasePackageName() /*callingPackage*/,
                    mContext.getAttributionTag() /*callingAttributionTag*/,
                    intent /*intent*/,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()) /*resolvedType*/,
                    null /*resultTo*/,
                    null /*resultWho*/,
                    0 /*requestCode*/,
                    Intent.FLAG_ACTIVITY_NEW_TASK /*flags*/,
                    null /*profilerInfo*/,
                    options /*options*/,
                    userId /*user*/);
        } catch (RemoteException e) {
            return ActivityManager.START_CANCELED;
        } catch (Exception e) {
            return ActivityManager.START_CANCELED;
        }
    }

    private final TaskStackChangeListener mLockListener = new TaskStackChangeListener() {
        @Override
        public void onTaskProfileLocked(int taskId, int userId) {
            startWorkChallengeInTask(taskId, userId);
        }
    };
}
