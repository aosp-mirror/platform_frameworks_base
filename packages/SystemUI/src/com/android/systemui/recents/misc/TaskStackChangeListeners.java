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
 * limitations under the License.
 */

package com.android.systemui.recents.misc;

import android.app.ActivityManager.TaskSnapshot;
import android.app.IActivityManager;
import android.app.TaskStackListener;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks all the task stack listeners
 */
public class TaskStackChangeListeners extends TaskStackListener {

    private static final String TAG = TaskStackChangeListeners.class.getSimpleName();

    /**
     * List of {@link TaskStackChangeListener} registered from {@link #addListener}.
     */
    private final List<TaskStackChangeListener> mTaskStackListeners = new ArrayList<>();
    private final List<TaskStackChangeListener> mTmpListeners = new ArrayList<>();

    private final Handler mHandler;

    public TaskStackChangeListeners(Looper looper) {
        mHandler = new H(looper);
    }

    public void addListener(IActivityManager am, TaskStackChangeListener listener) {
        mTaskStackListeners.add(listener);
        if (mTaskStackListeners.size() == 1) {
            // Register mTaskStackListener to IActivityManager only once if needed.
            try {
                am.registerTaskStackListener(this);
            } catch (Exception e) {
                Log.w(TAG, "Failed to call registerTaskStackListener", e);
            }
        }
    }

    @Override
    public void onTaskStackChanged() throws RemoteException {
        // Call the task changed callback for the non-ui thread listeners first
        synchronized (mTaskStackListeners) {
            mTmpListeners.clear();
            mTmpListeners.addAll(mTaskStackListeners);
        }
        for (int i = mTmpListeners.size() - 1; i >= 0; i--) {
            mTmpListeners.get(i).onTaskStackChangedBackground();
        }

        mHandler.removeMessages(H.ON_TASK_STACK_CHANGED);
        mHandler.sendEmptyMessage(H.ON_TASK_STACK_CHANGED);
    }

    @Override
    public void onActivityPinned(String packageName, int userId, int taskId, int stackId)
            throws RemoteException {
        mHandler.removeMessages(H.ON_ACTIVITY_PINNED);
        mHandler.obtainMessage(H.ON_ACTIVITY_PINNED,
                new PinnedActivityInfo(packageName, userId, taskId, stackId)).sendToTarget();
    }

    @Override
    public void onActivityUnpinned() throws RemoteException {
        mHandler.removeMessages(H.ON_ACTIVITY_UNPINNED);
        mHandler.sendEmptyMessage(H.ON_ACTIVITY_UNPINNED);
    }

    @Override
    public void onPinnedActivityRestartAttempt(boolean clearedTask)
            throws RemoteException{
        mHandler.removeMessages(H.ON_PINNED_ACTIVITY_RESTART_ATTEMPT);
        mHandler.obtainMessage(H.ON_PINNED_ACTIVITY_RESTART_ATTEMPT, clearedTask ? 1 : 0, 0)
                .sendToTarget();
    }

    @Override
    public void onPinnedStackAnimationStarted() throws RemoteException {
        mHandler.removeMessages(H.ON_PINNED_STACK_ANIMATION_STARTED);
        mHandler.sendEmptyMessage(H.ON_PINNED_STACK_ANIMATION_STARTED);
    }

    @Override
    public void onPinnedStackAnimationEnded() throws RemoteException {
        mHandler.removeMessages(H.ON_PINNED_STACK_ANIMATION_ENDED);
        mHandler.sendEmptyMessage(H.ON_PINNED_STACK_ANIMATION_ENDED);
    }

    @Override
    public void onActivityForcedResizable(String packageName, int taskId, int reason)
            throws RemoteException {
        mHandler.obtainMessage(H.ON_ACTIVITY_FORCED_RESIZABLE, taskId, reason, packageName)
                .sendToTarget();
    }

    @Override
    public void onActivityDismissingDockedStack() throws RemoteException {
        mHandler.sendEmptyMessage(H.ON_ACTIVITY_DISMISSING_DOCKED_STACK);
    }

    @Override
    public void onActivityLaunchOnSecondaryDisplayFailed() throws RemoteException {
        mHandler.sendEmptyMessage(H.ON_ACTIVITY_LAUNCH_ON_SECONDARY_DISPLAY_FAILED);
    }

    @Override
    public void onTaskProfileLocked(int taskId, int userId) throws RemoteException {
        mHandler.obtainMessage(H.ON_TASK_PROFILE_LOCKED, taskId, userId).sendToTarget();
    }

    @Override
    public void onTaskSnapshotChanged(int taskId, TaskSnapshot snapshot)
            throws RemoteException {
        mHandler.obtainMessage(H.ON_TASK_SNAPSHOT_CHANGED, taskId, 0, snapshot).sendToTarget();
    }

    private final class H extends Handler {
        private static final int ON_TASK_STACK_CHANGED = 1;
        private static final int ON_TASK_SNAPSHOT_CHANGED = 2;
        private static final int ON_ACTIVITY_PINNED = 3;
        private static final int ON_PINNED_ACTIVITY_RESTART_ATTEMPT = 4;
        private static final int ON_PINNED_STACK_ANIMATION_ENDED = 5;
        private static final int ON_ACTIVITY_FORCED_RESIZABLE = 6;
        private static final int ON_ACTIVITY_DISMISSING_DOCKED_STACK = 7;
        private static final int ON_TASK_PROFILE_LOCKED = 8;
        private static final int ON_PINNED_STACK_ANIMATION_STARTED = 9;
        private static final int ON_ACTIVITY_UNPINNED = 10;
        private static final int ON_ACTIVITY_LAUNCH_ON_SECONDARY_DISPLAY_FAILED = 11;

        public H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            synchronized (mTaskStackListeners) {
                switch (msg.what) {
                    case ON_TASK_STACK_CHANGED: {
                        Trace.beginSection("onTaskStackChanged");
                        for (int i = mTaskStackListeners.size() - 1; i >= 0; i--) {
                            mTaskStackListeners.get(i).onTaskStackChanged();
                        }
                        Trace.endSection();
                        break;
                    }
                    case ON_TASK_SNAPSHOT_CHANGED: {
                        Trace.beginSection("onTaskSnapshotChanged");
                        for (int i = mTaskStackListeners.size() - 1; i >= 0; i--) {
                            mTaskStackListeners.get(i).onTaskSnapshotChanged(msg.arg1,
                                    (TaskSnapshot) msg.obj);
                        }
                        Trace.endSection();
                        break;
                    }
                    case ON_ACTIVITY_PINNED: {
                        final PinnedActivityInfo info = (PinnedActivityInfo) msg.obj;
                        for (int i = mTaskStackListeners.size() - 1; i >= 0; i--) {
                            mTaskStackListeners.get(i).onActivityPinned(
                                    info.mPackageName, info.mUserId, info.mTaskId, info.mStackId);
                        }
                        break;
                    }
                    case ON_ACTIVITY_UNPINNED: {
                        for (int i = mTaskStackListeners.size() - 1; i >= 0; i--) {
                            mTaskStackListeners.get(i).onActivityUnpinned();
                        }
                        break;
                    }
                    case ON_PINNED_ACTIVITY_RESTART_ATTEMPT: {
                        for (int i = mTaskStackListeners.size() - 1; i >= 0; i--) {
                            mTaskStackListeners.get(i).onPinnedActivityRestartAttempt(
                                    msg.arg1 != 0);
                        }
                        break;
                    }
                    case ON_PINNED_STACK_ANIMATION_STARTED: {
                        for (int i = mTaskStackListeners.size() - 1; i >= 0; i--) {
                            mTaskStackListeners.get(i).onPinnedStackAnimationStarted();
                        }
                        break;
                    }
                    case ON_PINNED_STACK_ANIMATION_ENDED: {
                        for (int i = mTaskStackListeners.size() - 1; i >= 0; i--) {
                            mTaskStackListeners.get(i).onPinnedStackAnimationEnded();
                        }
                        break;
                    }
                    case ON_ACTIVITY_FORCED_RESIZABLE: {
                        for (int i = mTaskStackListeners.size() - 1; i >= 0; i--) {
                            mTaskStackListeners.get(i).onActivityForcedResizable(
                                    (String) msg.obj, msg.arg1, msg.arg2);
                        }
                        break;
                    }
                    case ON_ACTIVITY_DISMISSING_DOCKED_STACK: {
                        for (int i = mTaskStackListeners.size() - 1; i >= 0; i--) {
                            mTaskStackListeners.get(i).onActivityDismissingDockedStack();
                        }
                        break;
                    }
                    case ON_ACTIVITY_LAUNCH_ON_SECONDARY_DISPLAY_FAILED: {
                        for (int i = mTaskStackListeners.size() - 1; i >= 0; i--) {
                            mTaskStackListeners.get(i).onActivityLaunchOnSecondaryDisplayFailed();
                        }
                        break;
                    }
                    case ON_TASK_PROFILE_LOCKED: {
                        for (int i = mTaskStackListeners.size() - 1; i >= 0; i--) {
                            mTaskStackListeners.get(i).onTaskProfileLocked(msg.arg1, msg.arg2);
                        }
                        break;
                    }
                }
            }
        }
    }

    private static class PinnedActivityInfo {
        final String mPackageName;
        final int mUserId;
        final int mTaskId;
        final int mStackId;

        PinnedActivityInfo(String packageName, int userId, int taskId, int stackId) {
            mPackageName = packageName;
            mUserId = userId;
            mTaskId = taskId;
            mStackId = stackId;
        }
    }
}
