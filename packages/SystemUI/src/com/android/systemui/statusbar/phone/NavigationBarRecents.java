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
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.systemui.R;

import java.util.List;

/**
 * Recent task icons appearing in the navigation bar. Touching an icon brings the activity to the
 * front. There is a fixed set of icons and icons are hidden if there are fewer recent tasks than
 * icons.
 */
class NavigationBarRecents extends LinearLayout {
    private final static String TAG = "NavigationBarRecents";

    private final static int[] RECENT_APP_BUTTON_IDS = { R.id.recent0, R.id.recent1, R.id.recent2 };

    private final ActivityManager mActivityManager;
    private final PackageManager mPackageManager;
    private final TaskStackListenerImpl mTaskStackListener;

    public NavigationBarRecents(Context context, AttributeSet attrs) {
        super(context, attrs);
        mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mPackageManager = getContext().getPackageManager();

        // Listen for task stack changes and refresh when they happen. Update notifications happen
        // on an IPC thread, so use Handler to handle the message on the main thread.
        Handler handler = new Handler();
        mTaskStackListener = new TaskStackListenerImpl(handler);
        IActivityManager iam = ActivityManagerNative.getDefault();
        try {
            iam.registerTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        // TODO: When is the right time to do the initial update?
        updateRecentApps();
    }

    private ImageView getRecentAppButton(int index) {
        return (ImageView) findViewById(RECENT_APP_BUTTON_IDS[index]);
    }

    private void updateRecentApps() {
        // TODO: Should this be getRunningTasks?
        List<ActivityManager.RecentTaskInfo> tasks = mActivityManager.getRecentTasksForUser(
                RECENT_APP_BUTTON_IDS.length,
                ActivityManager.RECENT_IGNORE_HOME_STACK_TASKS |
                ActivityManager.RECENT_IGNORE_UNAVAILABLE |
                ActivityManager.RECENT_INCLUDE_PROFILES |
                ActivityManager.RECENT_WITH_EXCLUDED,
                UserHandle.USER_CURRENT);
        // Show the recent icons with the oldest on the left.
        int buttonIndex = 0;
        int taskIndex = tasks.size() - 1;
        while (taskIndex >= 0 && buttonIndex < RECENT_APP_BUTTON_IDS.length) {
            updateRecentAppButton(getRecentAppButton(buttonIndex), tasks.get(taskIndex));
            taskIndex--;
            buttonIndex++;
        }
        // Hide the unused buttons.
        while (buttonIndex < RECENT_APP_BUTTON_IDS.length) {
            hideButton(getRecentAppButton(buttonIndex));
            buttonIndex++;
        }
    }

    private void updateRecentAppButton(ImageView button, ActivityManager.RecentTaskInfo task) {
        ComponentName component = task.baseIntent.getComponent();
        if (component == null) {
            hideButton(button);
            return;
        }

        // Load the activity icon on a background thread.
        new GetActivityIconTask(button).execute(component);

        final Intent baseIntent = task.baseIntent;
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // TODO: Consider using ActivityManager.moveTaskToFront(). This will need a task id.
                v.getContext().startActivity(baseIntent);
            }
        });
    }

    private void hideButton(ImageView button) {
        button.setImageDrawable(null);
        button.setVisibility(View.GONE);
    }

    /**
     * Retrieves the icon for an activity and sets it as the Drawable on an ImageView. The ImageView
     * is hidden if the activity isn't recognized or if there is no icon.
     */
    private class GetActivityIconTask extends AsyncTask<ComponentName, Void, Drawable> {
        private final ImageView mButton;  // The ImageView that will receive the icon.

        public GetActivityIconTask(ImageView button) {
            mButton = button;
        }

        @Override
        protected Drawable doInBackground(ComponentName... params) {
            try {
                return mPackageManager.getActivityIcon(params[0]);
            } catch (NameNotFoundException e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(Drawable icon) {
            mButton.setImageDrawable(icon);
            mButton.setVisibility(icon != null ? View.VISIBLE : View.GONE);
        }
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
}
