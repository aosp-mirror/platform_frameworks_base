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
 * limitations under the License.
 */
package com.android.systemui.recents.grid;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

import com.android.systemui.recents.RecentsImpl;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.RecentsActivityStartingEvent;

public class RecentsGridImpl extends RecentsImpl {
    public static final String RECENTS_MOSAIC_ACTIVITY =
            "com.android.systemui.recents.grid.RecentsGridActivity";

    public RecentsGridImpl(Context context) {
        super(context);
    }

    @Override
    protected void startRecentsActivity(ActivityManager.RunningTaskInfo runningTask,
            boolean isHomeStackVisible, boolean animate, int growTarget) {
        Intent intent = new Intent();
        intent.setClassName(RECENTS_PACKAGE, RECENTS_MOSAIC_ACTIVITY);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_TASK_ON_HOME);

        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        EventBus.getDefault().send(new RecentsActivityStartingEvent());
    }
}

