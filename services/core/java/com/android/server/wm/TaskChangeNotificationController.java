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

package com.android.server.wm;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.TaskSnapshot;
import android.app.ITaskStackListener;
import android.app.TaskInfo;
import android.content.ComponentName;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import java.util.ArrayList;

class TaskChangeNotificationController {
    private static final int LOG_STACK_STATE_MSG = 1;
    private static final int NOTIFY_TASK_STACK_CHANGE_LISTENERS_MSG = 2;
    private static final int NOTIFY_ACTIVITY_PINNED_LISTENERS_MSG = 3;
    private static final int NOTIFY_PINNED_ACTIVITY_RESTART_ATTEMPT_LISTENERS_MSG = 4;
    private static final int NOTIFY_PINNED_STACK_ANIMATION_ENDED_LISTENERS_MSG = 5;
    private static final int NOTIFY_FORCED_RESIZABLE_MSG = 6;
    private static final int NOTIFY_ACTIVITY_DISMISSING_DOCKED_STACK_MSG = 7;
    private static final int NOTIFY_TASK_ADDED_LISTENERS_MSG = 8;
    private static final int NOTIFY_TASK_REMOVED_LISTENERS_MSG = 9;
    private static final int NOTIFY_TASK_MOVED_TO_FRONT_LISTENERS_MSG = 10;
    private static final int NOTIFY_TASK_DESCRIPTION_CHANGED_LISTENERS_MSG = 11;
    private static final int NOTIFY_ACTIVITY_REQUESTED_ORIENTATION_CHANGED_LISTENERS = 12;
    private static final int NOTIFY_TASK_REMOVAL_STARTED_LISTENERS = 13;
    private static final int NOTIFY_TASK_PROFILE_LOCKED_LISTENERS_MSG = 14;
    private static final int NOTIFY_TASK_SNAPSHOT_CHANGED_LISTENERS_MSG = 15;
    private static final int NOTIFY_PINNED_STACK_ANIMATION_STARTED_LISTENERS_MSG = 16;
    private static final int NOTIFY_ACTIVITY_UNPINNED_LISTENERS_MSG = 17;
    private static final int NOTIFY_ACTIVITY_LAUNCH_ON_SECONDARY_DISPLAY_FAILED_MSG = 18;
    private static final int NOTIFY_ACTIVITY_LAUNCH_ON_SECONDARY_DISPLAY_REROUTED_MSG = 19;
    private static final int NOTIFY_SIZE_COMPAT_MODE_ACTIVITY_CHANGED_MSG = 20;

    // Delay in notifying task stack change listeners (in millis)
    private static final int NOTIFY_TASK_STACK_CHANGE_LISTENERS_DELAY = 100;

    // Global lock used by the service the instantiate objects of this class.
    private final Object mServiceLock;
    private final ActivityStackSupervisor mStackSupervisor;
    private final Handler mHandler;

    // Task stack change listeners in a remote process.
    private final RemoteCallbackList<ITaskStackListener> mRemoteTaskStackListeners =
            new RemoteCallbackList<>();

    /*
     * Task stack change listeners in a local process. Tracked separately so that they can be
     * called on the same thread.
     */
    private final ArrayList<ITaskStackListener> mLocalTaskStackListeners = new ArrayList<>();

    private final TaskStackConsumer mNotifyTaskStackChanged = (l, m) -> {
        l.onTaskStackChanged();
    };

    private final TaskStackConsumer mNotifyTaskCreated = (l, m) -> {
        l.onTaskCreated(m.arg1, (ComponentName) m.obj);
    };

    private final TaskStackConsumer mNotifyTaskRemoved = (l, m) -> {
        l.onTaskRemoved(m.arg1);
    };

    private final TaskStackConsumer mNotifyTaskMovedToFront = (l, m) -> {
        l.onTaskMovedToFront((RunningTaskInfo) m.obj);
    };

    private final TaskStackConsumer mNotifyTaskDescriptionChanged = (l, m) -> {
        l.onTaskDescriptionChanged((RunningTaskInfo) m.obj);
    };

    private final TaskStackConsumer mNotifyActivityRequestedOrientationChanged = (l, m) -> {
        l.onActivityRequestedOrientationChanged(m.arg1, m.arg2);
    };

    private final TaskStackConsumer mNotifyTaskRemovalStarted = (l, m) -> {
        l.onTaskRemovalStarted((RunningTaskInfo) m.obj);
    };

    private final TaskStackConsumer mNotifyActivityPinned = (l, m) -> {
        l.onActivityPinned((String) m.obj /* packageName */, m.sendingUid /* userId */,
                m.arg1 /* taskId */, m.arg2 /* stackId */);
    };

    private final TaskStackConsumer mNotifyActivityUnpinned = (l, m) -> {
        l.onActivityUnpinned();
    };

    private final TaskStackConsumer mNotifyPinnedActivityRestartAttempt = (l, m) -> {
        l.onPinnedActivityRestartAttempt(m.arg1 != 0);
    };

    private final TaskStackConsumer mNotifyPinnedStackAnimationStarted = (l, m) -> {
        l.onPinnedStackAnimationStarted();
    };

    private final TaskStackConsumer mNotifyPinnedStackAnimationEnded = (l, m) -> {
        l.onPinnedStackAnimationEnded();
    };

    private final TaskStackConsumer mNotifyActivityForcedResizable = (l, m) -> {
        l.onActivityForcedResizable((String) m.obj, m.arg1, m.arg2);
    };

    private final TaskStackConsumer mNotifyActivityDismissingDockedStack = (l, m) -> {
        l.onActivityDismissingDockedStack();
    };

    private final TaskStackConsumer mNotifyActivityLaunchOnSecondaryDisplayFailed = (l, m) -> {
        l.onActivityLaunchOnSecondaryDisplayFailed((RunningTaskInfo) m.obj, m.arg1);
    };

    private final TaskStackConsumer mNotifyActivityLaunchOnSecondaryDisplayRerouted = (l, m) -> {
        l.onActivityLaunchOnSecondaryDisplayRerouted((RunningTaskInfo) m.obj, m.arg1);
    };

    private final TaskStackConsumer mNotifyTaskProfileLocked = (l, m) -> {
        l.onTaskProfileLocked(m.arg1, m.arg2);
    };

    private final TaskStackConsumer mNotifyTaskSnapshotChanged = (l, m) -> {
        l.onTaskSnapshotChanged(m.arg1, (TaskSnapshot) m.obj);
    };

    private final TaskStackConsumer mOnSizeCompatModeActivityChanged = (l, m) -> {
        l.onSizeCompatModeActivityChanged(m.arg1, (IBinder) m.obj);
    };

    @FunctionalInterface
    public interface TaskStackConsumer {
        void accept(ITaskStackListener t, Message m) throws RemoteException;
    }

    private class MainHandler extends Handler {
        public MainHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case LOG_STACK_STATE_MSG: {
                    synchronized (mServiceLock) {
                        mStackSupervisor.logStackState();
                    }
                    break;
                }
                case NOTIFY_TASK_STACK_CHANGE_LISTENERS_MSG:
                    forAllRemoteListeners(mNotifyTaskStackChanged, msg);
                    break;
                case NOTIFY_TASK_ADDED_LISTENERS_MSG:
                    forAllRemoteListeners(mNotifyTaskCreated, msg);
                    break;
                case NOTIFY_TASK_REMOVED_LISTENERS_MSG:
                    forAllRemoteListeners(mNotifyTaskRemoved, msg);
                    break;
                case NOTIFY_TASK_MOVED_TO_FRONT_LISTENERS_MSG:
                    forAllRemoteListeners(mNotifyTaskMovedToFront, msg);
                    break;
                case NOTIFY_TASK_DESCRIPTION_CHANGED_LISTENERS_MSG:
                    forAllRemoteListeners(mNotifyTaskDescriptionChanged, msg);
                    break;
                case NOTIFY_ACTIVITY_REQUESTED_ORIENTATION_CHANGED_LISTENERS:
                    forAllRemoteListeners(mNotifyActivityRequestedOrientationChanged, msg);
                    break;
                case NOTIFY_TASK_REMOVAL_STARTED_LISTENERS:
                    forAllRemoteListeners(mNotifyTaskRemovalStarted, msg);
                    break;
                case NOTIFY_ACTIVITY_PINNED_LISTENERS_MSG:
                    forAllRemoteListeners(mNotifyActivityPinned, msg);
                    break;
                case NOTIFY_ACTIVITY_UNPINNED_LISTENERS_MSG:
                    forAllRemoteListeners(mNotifyActivityUnpinned, msg);
                    break;
                case NOTIFY_PINNED_ACTIVITY_RESTART_ATTEMPT_LISTENERS_MSG:
                    forAllRemoteListeners(mNotifyPinnedActivityRestartAttempt, msg);
                    break;
                case NOTIFY_PINNED_STACK_ANIMATION_STARTED_LISTENERS_MSG:
                    forAllRemoteListeners(mNotifyPinnedStackAnimationStarted, msg);
                    break;
                case NOTIFY_PINNED_STACK_ANIMATION_ENDED_LISTENERS_MSG:
                    forAllRemoteListeners(mNotifyPinnedStackAnimationEnded, msg);
                    break;
                case NOTIFY_FORCED_RESIZABLE_MSG:
                    forAllRemoteListeners(mNotifyActivityForcedResizable, msg);
                    break;
                case NOTIFY_ACTIVITY_DISMISSING_DOCKED_STACK_MSG:
                    forAllRemoteListeners(mNotifyActivityDismissingDockedStack, msg);
                    break;
                case NOTIFY_ACTIVITY_LAUNCH_ON_SECONDARY_DISPLAY_FAILED_MSG:
                    forAllRemoteListeners(mNotifyActivityLaunchOnSecondaryDisplayFailed, msg);
                    break;
                case NOTIFY_ACTIVITY_LAUNCH_ON_SECONDARY_DISPLAY_REROUTED_MSG:
                    forAllRemoteListeners(mNotifyActivityLaunchOnSecondaryDisplayRerouted, msg);
                    break;
                case NOTIFY_TASK_PROFILE_LOCKED_LISTENERS_MSG:
                    forAllRemoteListeners(mNotifyTaskProfileLocked, msg);
                    break;
                case NOTIFY_TASK_SNAPSHOT_CHANGED_LISTENERS_MSG:
                    forAllRemoteListeners(mNotifyTaskSnapshotChanged, msg);
                    break;
                case NOTIFY_SIZE_COMPAT_MODE_ACTIVITY_CHANGED_MSG:
                    forAllRemoteListeners(mOnSizeCompatModeActivityChanged, msg);
                    break;
            }
        }
    }

    public TaskChangeNotificationController(Object serviceLock,
            ActivityStackSupervisor stackSupervisor, Handler handler) {
        mServiceLock = serviceLock;
        mStackSupervisor = stackSupervisor;
        mHandler = new MainHandler(handler.getLooper());
    }

    public void registerTaskStackListener(ITaskStackListener listener) {
        synchronized (mServiceLock) {
            if (listener != null) {
                if (Binder.getCallingPid() == android.os.Process.myPid()) {
                    if (!mLocalTaskStackListeners.contains(listener)) {
                        mLocalTaskStackListeners.add(listener);
                    }
                } else {
                    mRemoteTaskStackListeners.register(listener);
                }
            }
        }
    }

    public void unregisterTaskStackListener(ITaskStackListener listener) {
        synchronized (mServiceLock) {
            if (listener != null) {
                if (Binder.getCallingPid() == android.os.Process.myPid()) {
                    mLocalTaskStackListeners.remove(listener);
                } else {
                    mRemoteTaskStackListeners.unregister(listener);
                }
            }
        }
    }

    private void forAllRemoteListeners(TaskStackConsumer callback, Message message) {
        synchronized (mServiceLock) {
            for (int i = mRemoteTaskStackListeners.beginBroadcast() - 1; i >= 0; i--) {
                try {
                    // Make a one-way callback to the listener
                    callback.accept(mRemoteTaskStackListeners.getBroadcastItem(i), message);
                } catch (RemoteException e) {
                    // Handled by the RemoteCallbackList.
                }
            }
            mRemoteTaskStackListeners.finishBroadcast();
        }
    }

    private void forAllLocalListeners(TaskStackConsumer callback, Message message) {
        synchronized (mServiceLock) {
            for (int i = mLocalTaskStackListeners.size() - 1; i >= 0; i--) {
                try {
                    callback.accept(mLocalTaskStackListeners.get(i), message);
                } catch (RemoteException e) {
                    // Never thrown since this is called locally.
                }
            }
        }
    }

    /** Notifies all listeners when the task stack has changed. */
    void notifyTaskStackChanged() {
        mHandler.sendEmptyMessage(LOG_STACK_STATE_MSG);
        mHandler.removeMessages(NOTIFY_TASK_STACK_CHANGE_LISTENERS_MSG);
        final Message msg = mHandler.obtainMessage(NOTIFY_TASK_STACK_CHANGE_LISTENERS_MSG);
        forAllLocalListeners(mNotifyTaskStackChanged, msg);
        // Only the main task stack change notification requires a delay.
        mHandler.sendMessageDelayed(msg, NOTIFY_TASK_STACK_CHANGE_LISTENERS_DELAY);
    }

    /** Notifies all listeners when an Activity is pinned. */
    void notifyActivityPinned(ActivityRecord r) {
        mHandler.removeMessages(NOTIFY_ACTIVITY_PINNED_LISTENERS_MSG);
        final Message msg = mHandler.obtainMessage(NOTIFY_ACTIVITY_PINNED_LISTENERS_MSG,
                r.getTaskRecord().taskId, r.getStackId(), r.packageName);
        msg.sendingUid = r.mUserId;
        forAllLocalListeners(mNotifyActivityPinned, msg);
        msg.sendToTarget();
    }

    /** Notifies all listeners when an Activity is unpinned. */
    void notifyActivityUnpinned() {
        mHandler.removeMessages(NOTIFY_ACTIVITY_UNPINNED_LISTENERS_MSG);
        final Message msg = mHandler.obtainMessage(NOTIFY_ACTIVITY_UNPINNED_LISTENERS_MSG);
        forAllLocalListeners(mNotifyActivityUnpinned, msg);
        msg.sendToTarget();
    }

    /**
     * Notifies all listeners when an attempt was made to start an an activity that is already
     * running in the pinned stack and the activity was not actually started, but the task is
     * either brought to the front or a new Intent is delivered to it.
     */
    void notifyPinnedActivityRestartAttempt(boolean clearedTask) {
        mHandler.removeMessages(NOTIFY_PINNED_ACTIVITY_RESTART_ATTEMPT_LISTENERS_MSG);
        final Message msg =
                mHandler.obtainMessage(NOTIFY_PINNED_ACTIVITY_RESTART_ATTEMPT_LISTENERS_MSG,
                        clearedTask ? 1 : 0, 0);
        forAllLocalListeners(mNotifyPinnedActivityRestartAttempt, msg);
        msg.sendToTarget();
    }

    /** Notifies all listeners when the pinned stack animation starts. */
    void notifyPinnedStackAnimationStarted() {
        mHandler.removeMessages(NOTIFY_PINNED_STACK_ANIMATION_STARTED_LISTENERS_MSG);
        final Message msg =
                mHandler.obtainMessage(NOTIFY_PINNED_STACK_ANIMATION_STARTED_LISTENERS_MSG);
        forAllLocalListeners(mNotifyPinnedStackAnimationStarted, msg);
        msg.sendToTarget();
    }

    /** Notifies all listeners when the pinned stack animation ends. */
    void notifyPinnedStackAnimationEnded() {
        mHandler.removeMessages(NOTIFY_PINNED_STACK_ANIMATION_ENDED_LISTENERS_MSG);
        final Message msg =
                mHandler.obtainMessage(NOTIFY_PINNED_STACK_ANIMATION_ENDED_LISTENERS_MSG);
        forAllLocalListeners(mNotifyPinnedStackAnimationEnded, msg);
        msg.sendToTarget();
    }

    void notifyActivityDismissingDockedStack() {
        mHandler.removeMessages(NOTIFY_ACTIVITY_DISMISSING_DOCKED_STACK_MSG);
        final Message msg = mHandler.obtainMessage(NOTIFY_ACTIVITY_DISMISSING_DOCKED_STACK_MSG);
        forAllLocalListeners(mNotifyActivityDismissingDockedStack, msg);
        msg.sendToTarget();
    }

    void notifyActivityForcedResizable(int taskId, int reason, String packageName) {
        mHandler.removeMessages(NOTIFY_FORCED_RESIZABLE_MSG);
        final Message msg = mHandler.obtainMessage(NOTIFY_FORCED_RESIZABLE_MSG, taskId, reason,
                packageName);
        forAllLocalListeners(mNotifyActivityForcedResizable, msg);
        msg.sendToTarget();
    }

    void notifyActivityLaunchOnSecondaryDisplayFailed(TaskInfo ti, int requestedDisplayId) {
        mHandler.removeMessages(NOTIFY_ACTIVITY_LAUNCH_ON_SECONDARY_DISPLAY_FAILED_MSG);
        final Message msg = mHandler.obtainMessage(
                NOTIFY_ACTIVITY_LAUNCH_ON_SECONDARY_DISPLAY_FAILED_MSG, requestedDisplayId,
                0 /* unused */, ti);
        forAllLocalListeners(mNotifyActivityLaunchOnSecondaryDisplayFailed, msg);
        msg.sendToTarget();
    }

    void notifyActivityLaunchOnSecondaryDisplayRerouted(TaskInfo ti, int requestedDisplayId) {
        mHandler.removeMessages(NOTIFY_ACTIVITY_LAUNCH_ON_SECONDARY_DISPLAY_REROUTED_MSG);
        final Message msg = mHandler.obtainMessage(
                NOTIFY_ACTIVITY_LAUNCH_ON_SECONDARY_DISPLAY_REROUTED_MSG, requestedDisplayId,
                0 /* unused */, ti);
        forAllLocalListeners(mNotifyActivityLaunchOnSecondaryDisplayRerouted, msg);
        msg.sendToTarget();
    }

    void notifyTaskCreated(int taskId, ComponentName componentName) {
        final Message msg = mHandler.obtainMessage(NOTIFY_TASK_ADDED_LISTENERS_MSG,
                taskId, 0 /* unused */, componentName);
        forAllLocalListeners(mNotifyTaskCreated, msg);
        msg.sendToTarget();
    }

    void notifyTaskRemoved(int taskId) {
        final Message msg = mHandler.obtainMessage(NOTIFY_TASK_REMOVED_LISTENERS_MSG,
                taskId, 0 /* unused */);
        forAllLocalListeners(mNotifyTaskRemoved, msg);
        msg.sendToTarget();
    }

    void notifyTaskMovedToFront(TaskInfo ti) {
        final Message msg = mHandler.obtainMessage(NOTIFY_TASK_MOVED_TO_FRONT_LISTENERS_MSG, ti);
        forAllLocalListeners(mNotifyTaskMovedToFront, msg);
        msg.sendToTarget();
    }

    void notifyTaskDescriptionChanged(TaskInfo taskInfo) {
        final Message msg = mHandler.obtainMessage(NOTIFY_TASK_DESCRIPTION_CHANGED_LISTENERS_MSG,
                taskInfo);
        forAllLocalListeners(mNotifyTaskDescriptionChanged, msg);
        msg.sendToTarget();

    }

    void notifyActivityRequestedOrientationChanged(int taskId, int orientation) {
        final Message msg = mHandler.obtainMessage(
                NOTIFY_ACTIVITY_REQUESTED_ORIENTATION_CHANGED_LISTENERS, taskId, orientation);
        forAllLocalListeners(mNotifyActivityRequestedOrientationChanged, msg);
        msg.sendToTarget();
    }

    /**
     * Notify listeners that the task is about to be finished before its surfaces are removed from
     * the window manager. This allows interested parties to perform relevant animations before
     * the window disappears.
     */
    void notifyTaskRemovalStarted(ActivityManager.RunningTaskInfo taskInfo) {
        final Message msg = mHandler.obtainMessage(NOTIFY_TASK_REMOVAL_STARTED_LISTENERS, taskInfo);
        forAllLocalListeners(mNotifyTaskRemovalStarted, msg);
        msg.sendToTarget();
    }

    /**
     * Notify listeners that the task has been put in a locked state because one or more of the
     * activities inside it belong to a managed profile user that has been locked.
     */
    void notifyTaskProfileLocked(int taskId, int userId) {
        final Message msg = mHandler.obtainMessage(NOTIFY_TASK_PROFILE_LOCKED_LISTENERS_MSG, taskId,
                userId);
        forAllLocalListeners(mNotifyTaskProfileLocked, msg);
        msg.sendToTarget();
    }

    /**
     * Notify listeners that the snapshot of a task has changed.
     */
    void notifyTaskSnapshotChanged(int taskId, TaskSnapshot snapshot) {
        final Message msg = mHandler.obtainMessage(NOTIFY_TASK_SNAPSHOT_CHANGED_LISTENERS_MSG,
                taskId, 0, snapshot);
        forAllLocalListeners(mNotifyTaskSnapshotChanged, msg);
        msg.sendToTarget();
    }

    /**
     * Notify listeners that whether a size compatibility mode activity is using the override
     * bounds which is not fit its parent.
     */
    void notifySizeCompatModeActivityChanged(int displayId, IBinder activityToken) {
        final Message msg = mHandler.obtainMessage(NOTIFY_SIZE_COMPAT_MODE_ACTIVITY_CHANGED_MSG,
                displayId, 0 /* unused */, activityToken);
        forAllLocalListeners(mOnSizeCompatModeActivityChanged, msg);
        msg.sendToTarget();
    }
}
