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

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;

import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.SystemServicesProxy.TaskStackListener;

public class WorkLockActivityController {
    private final Context mContext;

    public WorkLockActivityController(Context context) {
        mContext = context;
        EventBus.getDefault().register(this);
        SystemServicesProxy.getInstance(context).registerTaskStackListener(mLockListener);
    }

    private void startWorkChallengeInTask(int taskId, int userId) {
        Intent intent = new Intent(KeyguardManager.ACTION_CONFIRM_DEVICE_CREDENTIAL_WITH_USER)
                .setComponent(new ComponentName(mContext, WorkLockActivity.class))
                .putExtra(Intent.EXTRA_USER_ID, userId)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchTaskId(taskId);
        options.setTaskOverlay(true, false /* canResume */);
        mContext.startActivityAsUser(intent, options.toBundle(), UserHandle.CURRENT);
    }

    private final TaskStackListener mLockListener = new TaskStackListener() {
        @Override
        public void onTaskProfileLocked(int taskId, int userId) {
            startWorkChallengeInTask(taskId, userId);
        }
    };
}
