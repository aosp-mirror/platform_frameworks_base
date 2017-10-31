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

package com.android.systemui.shared.system;

import static android.app.ActivityManager.RECENT_IGNORE_UNAVAILABLE;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityManager.RecentTaskInfo;
import android.app.AppGlobals;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.IconDrawableFactory;
import android.util.Log;

import com.android.systemui.shared.recents.model.ThumbnailData;

import java.util.ArrayList;
import java.util.List;

public class ActivityManagerWrapper {

    private static final String TAG = "ActivityManagerWrapper";

    private static final ActivityManagerWrapper sInstance = new ActivityManagerWrapper();

    private final PackageManager mPackageManager;
    private final IconDrawableFactory mDrawableFactory;
    private final BackgroundExecutor mBackgroundExecutor;

    private ActivityManagerWrapper() {
        final Context context = AppGlobals.getInitialApplication();
        mPackageManager = context.getPackageManager();
        mDrawableFactory = IconDrawableFactory.newInstance(context);
        mBackgroundExecutor = BackgroundExecutor.get();
    }

    public static ActivityManagerWrapper getInstance() {
        return sInstance;
    }

    /**
     * @return the current user's id.
     */
    public int getCurrentUserId() {
        UserInfo ui;
        try {
            ui = ActivityManager.getService().getCurrentUser();
            return ui != null ? ui.id : 0;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @return a list of the recents tasks.
     */
    public List<RecentTaskInfo> getRecentTasks(int numTasks, int userId) {
        try {
            return ActivityManager.getService().getRecentTasks(numTasks,
                            RECENT_IGNORE_UNAVAILABLE, userId).getList();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get recent tasks", e);
            return new ArrayList<>();
        }
    }

    /**
     * @return the task snapshot for the given {@param taskId}.
     */
    public @NonNull ThumbnailData getTaskThumbnail(int taskId, boolean reducedResolution) {
        ActivityManager.TaskSnapshot snapshot = null;
        try {
            snapshot = ActivityManager.getService().getTaskSnapshot(taskId, reducedResolution);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to retrieve task snapshot", e);
        }
        if (snapshot != null) {
            return new ThumbnailData(snapshot);
        } else {
            return new ThumbnailData();
        }
    }

    /**
     * @return the task description icon, loading and badging it if it necessary.
     */
    public Drawable getBadgedTaskDescriptionIcon(Context context,
            ActivityManager.TaskDescription taskDescription, int userId, Resources res) {
        Bitmap tdIcon = taskDescription.getInMemoryIcon();
        Drawable dIcon = null;
        if (tdIcon != null) {
            dIcon = new BitmapDrawable(res, tdIcon);
        } else if (taskDescription.getIconResource() != 0) {
            try {
                dIcon = context.getDrawable(taskDescription.getIconResource());
            } catch (NotFoundException e) {
                Log.e(TAG, "Could not find icon drawable from resource", e);
            }
        } else {
            tdIcon = ActivityManager.TaskDescription.loadTaskDescriptionIcon(
                    taskDescription.getIconFilename(), userId);
            if (tdIcon != null) {
                dIcon = new BitmapDrawable(res, tdIcon);
            }
        }
        if (dIcon != null) {
            return getBadgedIcon(dIcon, userId);
        }
        return null;
    }

    /**
     * @return the given icon for a user, badging if necessary.
     */
    private Drawable getBadgedIcon(Drawable icon, int userId) {
        if (userId != UserHandle.myUserId()) {
            icon = mPackageManager.getUserBadgedIcon(icon, new UserHandle(userId));
        }
        return icon;
    }

    /**
     * @return the activity icon for the ActivityInfo for a user, badging if necessary.
     */
    public Drawable getBadgedActivityIcon(ActivityInfo info, int userId) {
        return mDrawableFactory.getBadgedIcon(info, info.applicationInfo, userId);
    }

    /**
     * @return the application icon for the ApplicationInfo for a user, badging if necessary.
     */
    public Drawable getBadgedApplicationIcon(ApplicationInfo appInfo, int userId) {
        return mDrawableFactory.getBadgedIcon(appInfo, userId);
    }

    /**
     * @return the activity label, badging if necessary.
     */
    public String getBadgedActivityLabel(ActivityInfo info, int userId) {
        return getBadgedLabel(info.loadLabel(mPackageManager).toString(), userId);
    }

    /**
     * @return the application label, badging if necessary.
     */
    public String getBadgedApplicationLabel(ApplicationInfo appInfo, int userId) {
        return getBadgedLabel(appInfo.loadLabel(mPackageManager).toString(), userId);
    }

    /**
     * @return the content description for a given task, badging it if necessary.  The content
     * description joins the app and activity labels.
     */
    public String getBadgedContentDescription(ActivityInfo info, int userId,
            ActivityManager.TaskDescription td) {
        String activityLabel;
        if (td != null && td.getLabel() != null) {
            activityLabel = td.getLabel();
        } else {
            activityLabel = info.loadLabel(mPackageManager).toString();
        }
        String applicationLabel = info.applicationInfo.loadLabel(mPackageManager).toString();
        String badgedApplicationLabel = getBadgedLabel(applicationLabel, userId);
        return applicationLabel.equals(activityLabel)
                ? badgedApplicationLabel
                : badgedApplicationLabel + " " + activityLabel;
    }

    /**
     * @return the given label for a user, badging if necessary.
     */
    private String getBadgedLabel(String label, int userId) {
        if (userId != UserHandle.myUserId()) {
            label = mPackageManager.getUserBadgedLabel(label, new UserHandle(userId)).toString();
        }
        return label;
    }

    /**
     * Requests that the system close any open system windows (including other SystemUI).
     */
    public void closeSystemWindows(String reason) {
        mBackgroundExecutor.submit(() -> {
            try {
                ActivityManager.getService().closeSystemDialogs(reason);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to close system windows", e);
            }
        });
    }

    /**
     * Removes a task by id.
     */
    public void removeTask(int taskId) {
        mBackgroundExecutor.submit(() -> {
            try {
                ActivityManager.getService().removeTask(taskId);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to remove task=" + taskId, e);
            }
        });
    }

    /**
     * Cancels the current window transtion to/from Recents for the given task id.
     */
    public void cancelWindowTransition(int taskId) {
        try {
            ActivityManager.getService().cancelTaskWindowTransition(taskId);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to cancel window transition for task=" + taskId, e);
        }
    }

    /**
     * Cancels the current thumbnail transtion to/from Recents for the given task id.
     */
    public void cancelThumbnailTransition(int taskId) {
        try {
            ActivityManager.getService().cancelTaskThumbnailTransition(taskId);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to cancel window transition for task=" + taskId, e);
        }
    }
}
