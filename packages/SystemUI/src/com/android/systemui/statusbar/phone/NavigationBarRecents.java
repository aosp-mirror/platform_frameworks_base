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

package com.android.systemui.statusbar.phone;

import android.app.ActivityManager;
import android.app.ActivityManager.RecentTaskInfo;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.ITaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.AttributeSet;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.systemui.R;

import java.util.List;

/**
 * Recent task icons appearing in the navigation bar. Touching an icon brings the activity to the
 * front. The tag for each icon's View contains the RecentTaskInfo.
 */
class NavigationBarRecents extends LinearLayout {
    private final static boolean DEBUG = false;
    private final static String TAG = "NavigationBarRecents";

    // Maximum number of icons to show.
    // TODO: Implement an overflow UI so the shelf can display an unlimited number of recents.
    private final static int MAX_RECENTS = 10;

    private final ActivityManager mActivityManager;
    private final PackageManager mPackageManager;
    private final LayoutInflater mLayoutInflater;
    // All icons share the same long-click listener.
    private final AppLongClickListener mAppLongClickListener;
    private final TaskStackListenerImpl mTaskStackListener;

    public NavigationBarRecents(Context context, AttributeSet attrs) {
        super(context, attrs);
        mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mPackageManager = getContext().getPackageManager();
        mLayoutInflater = LayoutInflater.from(context);
        mAppLongClickListener = new AppLongClickListener(context);

        // Listen for task stack changes and refresh when they happen. Update notifications happen
        // on an IPC thread, so use Handler to handle the message on the main thread.
        // TODO: This has too much latency. It only adds the icon when app launch is completed
        // and the launch animation is done playing. This class should add the icon immediately
        // when the launch starts.
        Handler handler = new Handler();
        mTaskStackListener = new TaskStackListenerImpl(handler);
        IActivityManager iam = ActivityManagerNative.getDefault();
        try {
            iam.registerTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void updateRecentApps() {
        // TODO: Should this be getRunningTasks?
        // TODO: Query other UserHandles?
        List<RecentTaskInfo> recentTasks = mActivityManager.getRecentTasksForUser(
                MAX_RECENTS,
                ActivityManager.RECENT_IGNORE_HOME_STACK_TASKS |
                ActivityManager.RECENT_IGNORE_UNAVAILABLE |
                ActivityManager.RECENT_INCLUDE_PROFILES,
                UserHandle.USER_CURRENT);
        if (DEBUG) Slog.d(TAG, "Got recents " + recentTasks.size());
        removeMissingRecents(recentTasks);
        addNewRecents(recentTasks);
    }

    // Removes any icons that disappeared from recents.
    private void removeMissingRecents(List<RecentTaskInfo> recentTasks) {
        // Build a set of the new task ids.
        SparseBooleanArray newTaskIds = new SparseBooleanArray();
        for (RecentTaskInfo task : recentTasks) {
            newTaskIds.put(task.persistentId, true);
        }

        // Iterate through the currently displayed tasks. If they no longer exist in recents,
        // remove them.
        int i = 0;
        while (i < getChildCount()) {
            RecentTaskInfo currentTask = (RecentTaskInfo) getChildAt(i).getTag();
            if (!newTaskIds.get(currentTask.persistentId)) {
                if (DEBUG) Slog.d(TAG, "Removing " + currentTask.baseIntent);
                removeViewAt(i);
            } else {
                i++;
            }
        }
    }

    // Adds new tasks at the end of the icon list.
    private void addNewRecents(List<RecentTaskInfo> recentTasks) {
        // Build a set of the current task ids.
        SparseBooleanArray currentTaskIds = new SparseBooleanArray();
        for (int i = 0; i < getChildCount(); i++) {
            RecentTaskInfo task = (RecentTaskInfo) getChildAt(i).getTag();
            currentTaskIds.put(task.persistentId, true);
        }

        // Add tasks that don't currently exist to the end of the view.
        for (RecentTaskInfo task : recentTasks) {
            // Don't overflow the list.
            if (getChildCount() >= MAX_RECENTS) {
                return;
            }
            // Don't add tasks that are already being shown.
            if (currentTaskIds.get(task.persistentId)) {
                continue;
            }
            addRecentAppButton(task);
        }
    }

    // Adds an icon at the end of the shelf.
    private void addRecentAppButton(RecentTaskInfo task) {
        if (DEBUG) Slog.d(TAG, "Adding " + task.baseIntent);

        // Add an icon for the task.
        ImageView button = (ImageView) mLayoutInflater.inflate(
                R.layout.navigation_bar_app_item, this, false /* attachToRoot */);
        button.setOnLongClickListener(mAppLongClickListener);
        addView(button);

        ComponentName activityName = getRealActivityForTask(task);
        CharSequence appLabel = NavigationBarApps.getAppLabel(mPackageManager, activityName);
        button.setContentDescription(appLabel);

        // Use the View's tag to store metadata for drag and drop.
        button.setTag(task);

        button.setVisibility(View.VISIBLE);
        // Load the activity icon on a background thread.
        AppInfo app = new AppInfo(activityName, new UserHandle(task.userId));
        new GetActivityIconTask(mPackageManager, button).execute(app);

        final int taskPersistentId = task.persistentId;
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Launch or bring the activity to front.
                IActivityManager manager = ActivityManagerNative.getDefault();
                try {
                    manager.startActivityFromRecents(taskPersistentId, null /* options */);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Exception when activating a recent task", e);
                } catch (IllegalArgumentException e) {
                    Slog.e(TAG, "Exception when activating a recent task", e);
                }
            }
        });
    }

    private static ComponentName getRealActivityForTask(RecentTaskInfo task) {
        // Prefer the activity that started the task.
        if (task.realActivity != null) {
            return task.realActivity;
        }
        // This should not happen, but fall back to the base intent's activity component name.
        return task.baseIntent.getComponent();
    }

    /**
     * A listener that updates the app buttons whenever the recents task stack changes.
     * NOTE: This is not the right way to do this.
     */
    private class TaskStackListenerImpl extends ITaskStackListener.Stub {
        // Handler to post messages to the UI thread.
        private Handler mHandler;

        public TaskStackListenerImpl(Handler handler) {
            mHandler = handler;
        }

        @Override
        public void onTaskStackChanged() throws RemoteException {
            // Post the message back to the UI thread.
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateRecentApps();
                }
            });
        }
    }

    /** Starts a drag on long-click on an app icon. */
    private static class AppLongClickListener implements View.OnLongClickListener {
        private final Context mContext;

        public AppLongClickListener(Context context) {
            mContext = context;
        }

        private ComponentName getLaunchComponentForPackage(String packageName, int userId) {
            // This code is based on ApplicationPackageManager.getLaunchIntentForPackage.
            PackageManager packageManager = mContext.getPackageManager();

            // First see if the package has an INFO activity; the existence of
            // such an activity is implied to be the desired front-door for the
            // overall package (such as if it has multiple launcher entries).
            Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
            intentToResolve.addCategory(Intent.CATEGORY_INFO);
            intentToResolve.setPackage(packageName);
            List<ResolveInfo> ris = packageManager.queryIntentActivitiesAsUser(
                    intentToResolve, 0, userId);

            // Otherwise, try to find a main launcher activity.
            if (ris == null || ris.size() <= 0) {
                // reuse the intent instance
                intentToResolve.removeCategory(Intent.CATEGORY_INFO);
                intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER);
                intentToResolve.setPackage(packageName);
                ris = packageManager.queryIntentActivitiesAsUser(intentToResolve, 0, userId);
            }
            if (ris == null || ris.size() <= 0) {
                Slog.e(TAG, "Failed to build intent for " + packageName);
                return null;
            }
            return new ComponentName(ris.get(0).activityInfo.packageName,
                    ris.get(0).activityInfo.name);
        }

        @Override
        public boolean onLongClick(View v) {
            ImageView icon = (ImageView) v;

            // The drag will go to the pinned section, which wants to launch the main activity
            // for the task's package.
            RecentTaskInfo task = (RecentTaskInfo) v.getTag();
            String packageName = getRealActivityForTask(task).getPackageName();
            ComponentName component = getLaunchComponentForPackage(packageName, task.userId);
            if (component == null) {
                return false;
            }

            if (DEBUG) Slog.d(TAG, "Start drag with " + component);

            NavigationBarApps.startAppDrag(
                    icon, new AppInfo(component, new UserHandle(task.userId)));
            return true;
        }
    }
}
