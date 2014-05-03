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
import android.app.AppGlobals;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;

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
    IPackageManager mIpm;
    UserManager mUm;
    SearchManager mSm;
    String mPackage;

    Bitmap mDummyIcon;

    /** Private constructor */
    public SystemServicesProxy(Context context) {
        mAm = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mPm = context.getPackageManager();
        mUm = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mIpm = AppGlobals.getPackageManager();
        mSm = (SearchManager) context.getSystemService(Context.SEARCH_SERVICE);
        mPackage = context.getPackageName();

        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            // Create a dummy icon
            mDummyIcon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            mDummyIcon.eraseColor(0xFF999999);
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
                rti.activityValues = new ActivityManager.RecentsActivityValues();
                rti.description = "" + i + " - " +
                        Long.toString(Math.abs(new Random().nextLong()), 36);
                if (i % 2 == 0) {
                    rti.activityValues.label = rti.description;
                    rti.activityValues.icon = Bitmap.createBitmap(mDummyIcon);
                    rti.activityValues.colorPrimary = new Random().nextInt();
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
            thumbnail.eraseColor(0xff333333);
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

    /**
     * Returns the activity info for a given component name.
     * 
     * @param ComponentName The component name of the activity.
     * @param userId The userId of the user that this is for.
     */
    public ActivityInfo getActivityInfo(ComponentName cn, int userId) {
        if (mIpm == null) return null;
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) return null;

        try {
            return mIpm.getActivityInfo(cn, PackageManager.GET_META_DATA, userId);
        } catch (RemoteException e) {
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

    /**
     * Returns the activity icon for the ActivityInfo for a user, badging if
     * necessary.
     */
    public Drawable getActivityIcon(ActivityInfo info, int userId) {
        if (mPm == null || mUm == null) return null;

        // If we are mocking, then return a mock label
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            return new ColorDrawable(0xFF666666);
        }

        Drawable icon = info.loadIcon(mPm);
        if (userId != UserHandle.myUserId()) {
            icon = mUm.getBadgedDrawableForUser(icon, new UserHandle(userId));
        }
        return icon;
    }


    /**
     * Composes an intent to launch the global search activity.
     */
    public Intent getGlobalSearchIntent(Rect sourceBounds) {
        if (mSm == null) return null;

        // Try and get the global search activity
        ComponentName globalSearchActivity = mSm.getGlobalSearchActivity();
        if (globalSearchActivity == null) return null;

        // Bundle the source of the search
        Bundle appSearchData = new Bundle();
        appSearchData.putString("source", mPackage);

        // Compose the intent and Start the search activity
        Intent intent = new Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setComponent(globalSearchActivity);
        intent.putExtra(SearchManager.APP_DATA, appSearchData);
        intent.setSourceBounds(sourceBounds);
        return intent;
    }
}
