/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.AppTask;
import android.app.ActivityManager.RecentTaskInfo;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;

import java.util.List;

/**
 * Provides FilesActivity task grouping support. This allows multiple FilesActivities to be
 * launched (a behavior imparted by way of {@code documentLaunchMode="intoExisting"} and
 * our use of pseudo document {@link Uri}s. This also lets us move an existing task
 * to the foreground when a suitable task exists.
 *
 * Requires that {@code documentLaunchMode="intoExisting"} be set on target activity.
 *
 */
public class LauncherActivity extends Activity {

    public static final String LAUNCH_CONTROL_AUTHORITY = "com.android.documentsui.launchControl";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActivityManager activities = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        List<AppTask> tasks = activities.getAppTasks();

        AppTask raiseTask = null;
        for (AppTask task : tasks) {
            Uri taskUri = task.getTaskInfo().baseIntent.getData();
            if (taskUri != null && isLaunchUri(taskUri)) {
                raiseTask = task;
            }
        }

        if (raiseTask == null) {
            launchFilesTask();
        } else {
            raiseFilesTask(activities, raiseTask.getTaskInfo());
        }

        finish();
    }

    private void launchFilesTask() {
        Intent intent = createLaunchIntent(this);
        startActivity(intent);
    }

    private void raiseFilesTask(ActivityManager activities, RecentTaskInfo task) {
        activities.moveTaskToFront(task.id, 0);
    }

    static Intent createLaunchIntent(Context context) {
        Intent intent = new Intent(context, FilesActivity.class);
        intent.setData(buildLaunchUri());
        return intent;
    }

    private static Uri buildLaunchUri() {
        return new Uri.Builder()
                .authority(LAUNCH_CONTROL_AUTHORITY)
                .fragment(String.valueOf(System.currentTimeMillis()))
                .build();
    }

    static boolean isLaunchUri(@Nullable Uri uri) {
        return uri != null && LAUNCH_CONTROL_AUTHORITY.equals(uri.getAuthority());
    }
}
