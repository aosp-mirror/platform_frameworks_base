/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Acts as a shim around the real system services that we need to access data from, and provides
 * a point of injection when testing UI.
 */
public class SystemServicesProxy {
    ActivityManager mAm;
    PackageManager mPm;
    String mPackage;

    Bitmap mDummyIcon;

    /** Private constructor */
    public SystemServicesProxy(Context context) {
        mAm = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mPm = context.getPackageManager();
        mPackage = context.getPackageName();

        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            // Create a dummy icon
            mDummyIcon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(mDummyIcon);
            c.drawColor(0xFF999999);
            c.setBitmap(null);
        }
    }

    /** Returns a list of the recents tasks */
    public List<ActivityManager.RecentTaskInfo> getRecentTasks(int numTasks, int userId) {
        if (mAm == null) return null;

        // If we are mocking, then create some recent tasks
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            ArrayList<ActivityManager.RecentTaskInfo> tasks =
                    new ArrayList<ActivityManager.RecentTaskInfo>();
            int count = Math.min(numTasks, Constants.DebugFlags.App.SystemServicesProxyMockTaskCount);
            for (int i = 0; i < count; i++) {
                // Create a dummy component name
                int packageIndex = i % Constants.DebugFlags.App.SystemServicesProxyMockPackageCount;
                ComponentName cn = new ComponentName("com.android.test" + packageIndex,
                        "com.android.test" + i + ".Activity");
                // Create the recent task info
                ActivityManager.RecentTaskInfo rti = new ActivityManager.RecentTaskInfo();
                rti.id = rti.persistentId = i;
                rti.baseIntent = new Intent();
                rti.baseIntent.setComponent(cn);
                rti.description = rti.activityLabel = "" + i + " - " +
                        Long.toString(Math.abs(new Random().nextLong()), 36);
                if (i % 2 == 0) {
                    rti.activityIcon = Bitmap.createBitmap(mDummyIcon);
                }
                tasks.add(rti);
            }
            return tasks;
        }

        return mAm.getRecentTasksForUser(numTasks,
                ActivityManager.RECENT_IGNORE_UNAVAILABLE |
                ActivityManager.RECENT_INCLUDE_PROFILES, userId);
    }

    /** Returns a list of the running tasks */
    public List<ActivityManager.RunningTaskInfo> getRunningTasks(int numTasks) {
        if (mAm == null) return null;
        return mAm.getRunningTasks(numTasks);
    }

    /** Returns whether the specified task is in the home stack */
    public boolean isInHomeStack(int taskId) {
        if (mAm == null) return false;

        // If we are mocking, then just return false
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            return false;
        }

        return mAm.isInHomeStack(taskId);
    }

    /** Returns the top task thumbnail for the given task id */
    public Bitmap getTaskThumbnail(int taskId) {
        if (mAm == null) return null;

        // If we are mocking, then just return a dummy thumbnail
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            Bitmap thumbnail = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(thumbnail);
            c.drawColor(0xff333333);
            c.setBitmap(null);
            return thumbnail;
        }

        return mAm.getTaskTopThumbnail(taskId);
    }

    /** Moves a task to the front with the specified activity options */
    public void moveTaskToFront(int taskId, ActivityOptions opts) {
        if (mAm == null) return;
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) return;

        if (opts != null) {
            mAm.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME,
                    opts.toBundle());
        } else {
            mAm.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME);
        }
    }

    /** Removes the task and kills the process */
    public void removeTask(int taskId) {
        if (mAm == null) return;
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) return;

        mAm.removeTask(taskId, ActivityManager.REMOVE_TASK_KILL_PROCESS);
    }

    /** Returns the activity info for a given component name */
    public ActivityInfo getActivityInfo(ComponentName cn) {
        if (mPm == null) return null;
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) return null;

        try {
            return mPm.getActivityInfo(cn, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    /** Returns the activity label */
    public String getActivityLabel(ActivityInfo info) {
        if (mPm == null) return null;

        // If we are mocking, then return a mock label
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            return "Recent Task";
        }

        return info.loadLabel(mPm).toString();
    }

    /** Returns the activity icon */
    public Drawable getActivityIcon(ActivityInfo info) {
        if (mPm == null) return null;

        // If we are mocking, then return a mock label
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            return new ColorDrawable(0xFF666666);
        }

        return info.loadIcon(mPm);
    }
}
