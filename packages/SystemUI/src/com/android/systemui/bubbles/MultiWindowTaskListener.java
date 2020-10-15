/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.bubbles;

import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_MULTI_WINDOW;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.view.SurfaceControl;
import android.window.TaskOrganizer;
import android.window.WindowContainerToken;

import com.android.wm.shell.ShellTaskOrganizer;

import java.util.ArrayList;

/**
 * Manages tasks that are displayed in multi-window (e.g. bubbles). These are displayed in a
 * {@link TaskView}.
 *
 * This class listens on {@link TaskOrganizer} callbacks for events. Once visible, these tasks will
 * intercept back press events.
 *
 * @see android.app.WindowConfiguration#WINDOWING_MODE_MULTI_WINDOW
 * @see TaskView
 */
// TODO: Place in com.android.wm.shell vs. com.android.wm.shell.bubbles on shell migration.
public class MultiWindowTaskListener implements ShellTaskOrganizer.TaskListener {
    private static final String TAG = MultiWindowTaskListener.class.getSimpleName();

    private static final boolean DEBUG = false;

    //TODO(b/170153209): Have shell listener allow per task registration and remove this.
    public interface Listener {
        void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash);
        void onTaskVanished(RunningTaskInfo taskInfo);
        void onTaskInfoChanged(RunningTaskInfo taskInfo);
        void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo);
    }

    private static class TaskData {
        final RunningTaskInfo taskInfo;
        final Listener listener;

        TaskData(RunningTaskInfo info, Listener l) {
            taskInfo = info;
            listener = l;
        }
    }

    private final Handler mHandler;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final ArrayMap<WindowContainerToken, TaskData> mTasks = new ArrayMap<>();

    private ArrayMap<IBinder, Listener> mLaunchCookieToListener = new ArrayMap<>();

    /**
     * Create a listener for tasks in multi-window mode.
     */
    public MultiWindowTaskListener(Handler handler, ShellTaskOrganizer organizer) {
        mHandler = handler;
        mTaskOrganizer = organizer;
        mTaskOrganizer.addListener(this, TASK_LISTENER_TYPE_MULTI_WINDOW);
    }

    /**
     * @return the task organizer that is listened to.
     */
    public TaskOrganizer getTaskOrganizer() {
        return mTaskOrganizer;
    }

    public void setPendingLaunchCookieListener(IBinder cookie, Listener listener) {
        mLaunchCookieToListener.put(cookie, listener);
    }

    /**
     * Removes a task listener previously registered when starting a new activity.
     */
    public void removeListener(Listener listener) {
        if (DEBUG) {
            Log.d(TAG, "removeListener: listener=" + listener);
        }
        for (int i = 0; i < mTasks.size(); i++) {
            if (mTasks.valueAt(i).listener == listener) {
                mTasks.removeAt(i);
            }
        }
    }

    @Override
    public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
        if (DEBUG) {
            Log.d(TAG, "onTaskAppeared: taskInfo=" + taskInfo);
        }

        // We only care about task we launch which should all have a tracking launch cookie.
        final ArrayList<IBinder> launchCookies = taskInfo.launchCookies;
        if (launchCookies.isEmpty()) return;

        // See if this task has one of our launch cookies.
        Listener listener = null;
        for (int i = launchCookies.size() - 1; i >= 0; --i) {
            final IBinder cookie = launchCookies.get(i);
            listener = mLaunchCookieToListener.get(cookie);
            if (listener != null) {
                mLaunchCookieToListener.remove(cookie);
                break;
            }
        }

        // This is either not a task we launched or we have handled it previously.
        if (listener == null) return;

        mTaskOrganizer.setInterceptBackPressedOnTaskRoot(taskInfo.token, true);

        final TaskData data = new TaskData(taskInfo, listener);
        mTasks.put(taskInfo.token, data);
        mHandler.post(() -> data.listener.onTaskAppeared(taskInfo, leash));
    }

    @Override
    public void onTaskVanished(RunningTaskInfo taskInfo) {
        final TaskData data = mTasks.remove(taskInfo.token);
        if (data == null) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "onTaskVanished: taskInfo=" + taskInfo + " listener=" + data.listener);
        }
        mHandler.post(() -> data.listener.onTaskVanished(taskInfo));
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        final TaskData data = mTasks.get(taskInfo.token);
        if (data == null) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "onTaskInfoChanged: taskInfo=" + taskInfo + " listener=" + data.listener);
        }
        mHandler.post(() -> data.listener.onTaskInfoChanged(taskInfo));
    }

    @Override
    public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {
        final TaskData data = mTasks.get(taskInfo.token);
        if (data == null) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "onTaskInfoChanged: taskInfo=" + taskInfo + " listener=" + data.listener);
        }
        mHandler.post(() -> data.listener.onBackPressedOnTaskRoot(taskInfo));
    }
}
