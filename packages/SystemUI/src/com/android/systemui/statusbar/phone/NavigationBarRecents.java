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
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.ITaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.systemui.R;

import java.util.List;
import java.util.Set;

/**
 * Recent task icons appearing in the navigation bar. Touching an icon brings the activity to the
 * front. There is a fixed set of icons and icons are hidden if there are fewer recent tasks than
 * icons.
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
    private final TaskStackListenerImpl mTaskStackListener;
    // Recent tasks being displayed in the shelf.
    private final Set<ComponentName> mCurrentTasks = new ArraySet<ComponentName>(MAX_RECENTS);

    public NavigationBarRecents(Context context, AttributeSet attrs) {
        super(context, attrs);
        mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mPackageManager = getContext().getPackageManager();
        mLayoutInflater = LayoutInflater.from(context);

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
        List<ActivityManager.RecentTaskInfo> recentTasks = mActivityManager.getRecentTasksForUser(
                MAX_RECENTS,
                ActivityManager.RECENT_IGNORE_HOME_STACK_TASKS |
                ActivityManager.RECENT_IGNORE_UNAVAILABLE |
                ActivityManager.RECENT_INCLUDE_PROFILES |
                ActivityManager.RECENT_WITH_EXCLUDED,
                UserHandle.USER_CURRENT);
        if (DEBUG) Slog.d(TAG, "Got recents " + recentTasks.size());
        removeMissingRecents(recentTasks);
        addNewRecents(recentTasks);
    }

    // Remove any icons that disappeared from recents.
    private void removeMissingRecents(List<ActivityManager.RecentTaskInfo> recentTasks) {
        // Extract the component names.
        Set<ComponentName> recentComponents = new ArraySet<ComponentName>(recentTasks.size());
        for (ActivityManager.RecentTaskInfo task : recentTasks) {
            ComponentName component = task.baseIntent.getComponent();
            if (component == null) {  // It's unclear if this can happen in practice.
                continue;
            }
            recentComponents.add(component);
        }

        // Start with a copy of the currently displayed tasks.
        Set<ComponentName> removed = new ArraySet<ComponentName>(mCurrentTasks);
        // Remove all the entries that still exist in recents.
        removed.removeAll(recentComponents);
        // The remaining entries no longer exist in recents, so remove their icons.
        for (ComponentName task : removed) {
            removeIcon(task);
        }
    }

    // Removes the icon for a task.
    private void removeIcon(ComponentName task) {
        for (int i = 0; i < getChildCount(); i++) {
            ComponentName childTask = (ComponentName) getChildAt(i).getTag();
            if (childTask.equals(task)) {
                if (DEBUG) Slog.d(TAG, "removing missing " + task);
                removeViewAt(i);
                mCurrentTasks.remove(task);
                return;
            }
        }
    }

    // Adds new tasks at the end of the icon list.
    private void addNewRecents(List<ActivityManager.RecentTaskInfo> recentTasks) {
        for (ActivityManager.RecentTaskInfo task : recentTasks) {
            // Don't overflow the list.
            if (getChildCount() >= MAX_RECENTS) {
                return;
            }
            ComponentName component = task.baseIntent.getComponent();
            if (component == null) {  // It's unclear if this can happen in practice.
                continue;
            }
            // Don't add tasks that are already being shown.
            if (mCurrentTasks.contains(component)) {
                continue;
            }
            addRecentAppButton(task);
        }
    }

    // Adds an icon at the end of the list to represent an activity for a given component.
    private void addRecentAppButton(ActivityManager.RecentTaskInfo task) {
        // Add this task to the currently-shown set.
        ComponentName component = task.baseIntent.getComponent();
        mCurrentTasks.add(component);
        if (DEBUG) Slog.d(TAG, "adding " + component);

        ImageView button = (ImageView) mLayoutInflater.inflate(
                R.layout.navigation_bar_app_item, this, false /* attachToRoot */);
        button.setOnLongClickListener(AppLongClickListener.getInstance());
        addView(button);

        // Use the View's tag to store metadata for drag and drop.
        button.setTag(component);

        button.setVisibility(View.VISIBLE);
        // Load the activity icon on a background thread.
        new GetActivityIconTask(mPackageManager, button).execute(component);

        final Intent baseIntent = task.baseIntent;
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // TODO: Consider using ActivityManager.moveTaskToFront(). This will need a task id.
                v.getContext().startActivity(baseIntent);
            }
        });
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
        private static AppLongClickListener INSTANCE = new AppLongClickListener();

        public static AppLongClickListener getInstance() {
            return INSTANCE;
        }

        @Override
        public boolean onLongClick(View v) {
            ImageView icon = (ImageView) v;
            ComponentName activityName = (ComponentName) v.getTag();
            NavigationBarApps.startAppDrag(icon, activityName);
            return true;
        }
    }
}
