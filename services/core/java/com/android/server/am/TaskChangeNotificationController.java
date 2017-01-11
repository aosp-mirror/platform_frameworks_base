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

package com.android.server.am;

import android.app.ITaskStackListener;
import android.app.ActivityManager.TaskDescription;
import android.content.ComponentName;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

class TaskChangeNotificationController {
    static final int LOG_STACK_STATE_MSG = 1;
    static final int NOTIFY_TASK_STACK_CHANGE_LISTENERS_MSG = 2;
    static final int NOTIFY_ACTIVITY_PINNED_LISTENERS_MSG = 3;
    static final int NOTIFY_PINNED_ACTIVITY_RESTART_ATTEMPT_LISTENERS_MSG = 4;
    static final int NOTIFY_PINNED_STACK_ANIMATION_ENDED_LISTENERS_MSG = 5;
    static final int NOTIFY_FORCED_RESIZABLE_MSG = 6;
    static final int NOTIFY_ACTIVITY_DISMISSING_DOCKED_STACK_MSG = 7;
    static final int NOTIFY_TASK_ADDED_LISTENERS_MSG = 8;
    static final int NOTIFY_TASK_REMOVED_LISTENERS_MSG = 9;
    static final int NOTIFY_TASK_MOVED_TO_FRONT_LISTENERS_MSG = 10;
    static final int NOTIFY_TASK_DESCRIPTION_CHANGED_LISTENERS_MSG = 11;
    static final int NOTIFY_ACTIVITY_REQUESTED_ORIENTATION_CHANGED_LISTENERS = 12;
    static final int NOTIFY_TASK_REMOVAL_STARTED_LISTENERS = 13;
    static final int NOTIFY_TASK_PROFILE_LOCKED_LISTENERS_MSG = 14;

    // Delay in notifying task stack change listeners (in millis)
    static final int NOTIFY_TASK_STACK_CHANGE_LISTENERS_DELAY = 100;

    private final ActivityManagerService mService;
    private final ActivityStackSupervisor mStackSupervisor;
    private final Handler mHandler;

    /** Task stack change listeners. */
    private final RemoteCallbackList<ITaskStackListener> mTaskStackListeners =
            new RemoteCallbackList<ITaskStackListener>();

    @FunctionalInterface
    public interface ConsumerWithRemoteException<T> {
        void accept(T t) throws RemoteException;
    }

    private class MainHandler extends Handler {
        public MainHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case LOG_STACK_STATE_MSG: {
                    synchronized (mService) {
                        mStackSupervisor.logStackState();
                    }
                    break;
                }
                case NOTIFY_TASK_STACK_CHANGE_LISTENERS_MSG:
                    forAllListeners((listener) -> listener.onTaskStackChanged());
                    break;
                case NOTIFY_TASK_ADDED_LISTENERS_MSG:
                    forAllListeners((listener) -> listener.onTaskCreated(msg.arg1,
                            (ComponentName) msg.obj));
                    break;
                case NOTIFY_TASK_REMOVED_LISTENERS_MSG:
                    forAllListeners((listener) -> listener.onTaskRemoved(msg.arg1));
                    break;
                case NOTIFY_TASK_MOVED_TO_FRONT_LISTENERS_MSG:
                    forAllListeners((listener) -> listener.onTaskMovedToFront(msg.arg1));
                    break;
                case NOTIFY_TASK_DESCRIPTION_CHANGED_LISTENERS_MSG:
                    forAllListeners((listener) -> listener.onTaskDescriptionChanged(msg.arg1,
                            (TaskDescription) msg.obj));
                    break;
                case NOTIFY_ACTIVITY_REQUESTED_ORIENTATION_CHANGED_LISTENERS:
                    forAllListeners((listener) -> listener.onActivityRequestedOrientationChanged(
                            msg.arg1, msg.arg2));
                    break;
                case NOTIFY_TASK_REMOVAL_STARTED_LISTENERS:
                    forAllListeners((listener) -> listener.onTaskRemovalStarted(msg.arg1));
                    break;
                case NOTIFY_ACTIVITY_PINNED_LISTENERS_MSG:
                    forAllListeners((listener) -> listener.onActivityPinned());
                    break;
                case NOTIFY_PINNED_ACTIVITY_RESTART_ATTEMPT_LISTENERS_MSG:
                    forAllListeners((listener) -> listener.onPinnedActivityRestartAttempt());
                    break;
                case NOTIFY_PINNED_STACK_ANIMATION_ENDED_LISTENERS_MSG:
                    forAllListeners((listener) -> listener.onPinnedStackAnimationEnded());
                    break;
                case NOTIFY_FORCED_RESIZABLE_MSG:
                    forAllListeners((listener) -> listener.onActivityForcedResizable(
                            (String) msg.obj, msg.arg1));
                    break;
                case NOTIFY_ACTIVITY_DISMISSING_DOCKED_STACK_MSG:
                    forAllListeners((listener) -> listener.onActivityDismissingDockedStack());
                    break;
                case NOTIFY_TASK_PROFILE_LOCKED_LISTENERS_MSG:
                    forAllListeners((listener) -> listener.onTaskProfileLocked(msg.arg1, msg.arg2));
                    break;
            }
        }
    }

    public TaskChangeNotificationController(ActivityManagerService service,
            ActivityStackSupervisor stackSupervisor, Handler handler) {
        mService = service;
        mStackSupervisor = stackSupervisor;
        mHandler = new MainHandler(handler.getLooper());
    }

    public void registerTaskStackListener(ITaskStackListener listener) {
        synchronized (mService) {
            if (listener != null) {
                mTaskStackListeners.register(listener);
            }
        }
    }

    public void unregisterTaskStackListener(ITaskStackListener listener) {
        synchronized (mService) {
            if (listener != null) {
                mTaskStackListeners.unregister(listener);
            }
        }
    }

    void forAllListeners(ConsumerWithRemoteException<ITaskStackListener> callback) {
        synchronized (mService) {
            for (int i = mTaskStackListeners.beginBroadcast() - 1; i >= 0; i--) {
                try {
                    // Make a one-way callback to the listener
                    callback.accept(mTaskStackListeners.getBroadcastItem(i));
                } catch (RemoteException e) {
                    // Handled by the RemoteCallbackList.
                }
            }
            mTaskStackListeners.finishBroadcast();
         }
      }

    /** Notifies all listeners when the task stack has changed. */
    void notifyTaskStackChanged() {
        mHandler.sendEmptyMessage(LOG_STACK_STATE_MSG);
        mHandler.removeMessages(NOTIFY_TASK_STACK_CHANGE_LISTENERS_MSG);
        Message msg = mHandler.obtainMessage(NOTIFY_TASK_STACK_CHANGE_LISTENERS_MSG);
        // Only the main task stack change notification requires a delay.
        mHandler.sendMessageDelayed(msg, NOTIFY_TASK_STACK_CHANGE_LISTENERS_DELAY);
    }

    /** Notifies all listeners when an Activity is pinned. */
    void notifyActivityPinned() {
        mHandler.removeMessages(NOTIFY_ACTIVITY_PINNED_LISTENERS_MSG);
        mHandler.obtainMessage(NOTIFY_ACTIVITY_PINNED_LISTENERS_MSG).sendToTarget();
    }

    /**
     * Notifies all listeners when an attempt was made to start an an activity that is already
     * running in the pinned stack and the activity was not actually started, but the task is
     * either brought to the front or a new Intent is delivered to it.
     */
    void notifyPinnedActivityRestartAttempt() {
        mHandler.removeMessages(NOTIFY_PINNED_ACTIVITY_RESTART_ATTEMPT_LISTENERS_MSG);
        mHandler.obtainMessage(NOTIFY_PINNED_ACTIVITY_RESTART_ATTEMPT_LISTENERS_MSG).sendToTarget();
    }

    /** Notifies all listeners when the pinned stack animation ends. */
    void notifyPinnedStackAnimationEnded() {
        mHandler.removeMessages(NOTIFY_PINNED_STACK_ANIMATION_ENDED_LISTENERS_MSG);
        mHandler.obtainMessage(NOTIFY_PINNED_STACK_ANIMATION_ENDED_LISTENERS_MSG)
                .sendToTarget();
    }

    void notifyActivityDismissingDockedStack() {
        mHandler.removeMessages(NOTIFY_ACTIVITY_DISMISSING_DOCKED_STACK_MSG);
        mHandler.obtainMessage(NOTIFY_ACTIVITY_DISMISSING_DOCKED_STACK_MSG).sendToTarget();
    }

    void notifyActivityForcedResizable(int taskId, String packageName) {
        mHandler.removeMessages(NOTIFY_FORCED_RESIZABLE_MSG);
        mHandler.obtainMessage(NOTIFY_FORCED_RESIZABLE_MSG, taskId, 0 /* unused */, packageName)
                .sendToTarget();
    }

    void notifyTaskCreated(int taskId, ComponentName componentName) {
        mHandler.obtainMessage(NOTIFY_TASK_ADDED_LISTENERS_MSG, taskId, 0 /* unused */,
                componentName).sendToTarget();
    }

    void notifyTaskRemoved(int taskId) {
        mHandler.obtainMessage(NOTIFY_TASK_REMOVED_LISTENERS_MSG, taskId, 0 /* unused */)
                .sendToTarget();
    }

    void notifyTaskMovedToFront(int taskId) {
        mHandler.obtainMessage(NOTIFY_TASK_MOVED_TO_FRONT_LISTENERS_MSG, taskId, 0 /* unused */)
                .sendToTarget();
    }

    void notifyTaskDescriptionChanged(int taskId, TaskDescription taskDescription) {
        mHandler.obtainMessage(NOTIFY_TASK_DESCRIPTION_CHANGED_LISTENERS_MSG, taskId,
                0 /* unused */, taskDescription).sendToTarget();
    }

    void notifyActivityRequestedOrientationChanged(int taskId, int orientation) {
        mHandler.obtainMessage(NOTIFY_ACTIVITY_REQUESTED_ORIENTATION_CHANGED_LISTENERS, taskId,
                orientation).sendToTarget();
    }

    /**
     * Notify listeners that the task is about to be finished before its surfaces are removed from
     * the window manager. This allows interested parties to perform relevant animations before
     * the window disappears.
     */
    void notifyTaskRemovalStarted(int taskId) {
        mHandler.obtainMessage(NOTIFY_TASK_REMOVAL_STARTED_LISTENERS, taskId, 0 /* unused */)
                .sendToTarget();
    }

    /**
     * Notify listeners that the task has been put in a locked state because one or more of the
     * activities inside it belong to a managed profile user that has been locked.
     */
    void notifyTaskProfileLocked(int taskId, int userId) {
        mHandler.obtainMessage(NOTIFY_TASK_PROFILE_LOCKED_LISTENERS_MSG, taskId, userId)
                .sendToTarget();
    }
}
