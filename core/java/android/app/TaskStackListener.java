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

package android.app;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.TaskSnapshot;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

/**
 * Classes interested in observing only a subset of changes using ITaskStackListener can extend
 * this class to avoid having to implement all the methods.
 *
 * @hide
 */
public abstract class TaskStackListener extends ITaskStackListener.Stub {

    @UnsupportedAppUsage
    public TaskStackListener() {
    }

    @Override
    @UnsupportedAppUsage
    public void onTaskStackChanged() throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage
    public void onActivityPinned(String packageName, int userId, int taskId, int stackId)
            throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage
    public void onActivityUnpinned() throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage
    public void onActivityRestartAttempt(RunningTaskInfo task, boolean homeTaskVisible,
            boolean clearedTask) throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage
    public void onActivityForcedResizable(String packageName, int taskId, int reason)
            throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage
    public void onActivityDismissingDockedStack() throws RemoteException {
    }

    @Override
    public void onActivityLaunchOnSecondaryDisplayFailed(RunningTaskInfo taskInfo,
            int requestedDisplayId) throws RemoteException {
        onActivityLaunchOnSecondaryDisplayFailed();
    }

    /**
     * @deprecated see {@link
     *         #onActivityLaunchOnSecondaryDisplayFailed(RunningTaskInfo, int)}
     */
    @Deprecated
    @UnsupportedAppUsage
    public void onActivityLaunchOnSecondaryDisplayFailed() throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage
    public void onActivityLaunchOnSecondaryDisplayRerouted(RunningTaskInfo taskInfo,
            int requestedDisplayId) throws RemoteException {
    }

    @Override
    public void onTaskCreated(int taskId, ComponentName componentName) throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage
    public void onTaskRemoved(int taskId) throws RemoteException {
    }

    @Override
    public void onTaskMovedToFront(RunningTaskInfo taskInfo)
            throws RemoteException {
        onTaskMovedToFront(taskInfo.taskId);
    }

    /**
     * @deprecated see {@link #onTaskMovedToFront(RunningTaskInfo)}
     */
    @Deprecated
    @UnsupportedAppUsage
    public void onTaskMovedToFront(int taskId) throws RemoteException {
    }

    @Override
    public void onTaskRemovalStarted(RunningTaskInfo taskInfo)
            throws RemoteException {
        onTaskRemovalStarted(taskInfo.taskId);
    }

    /**
     * @deprecated see {@link #onTaskRemovalStarted(RunningTaskInfo)}
     */
    @Deprecated
    public void onTaskRemovalStarted(int taskId) throws RemoteException {
    }

    @Override
    public void onTaskDescriptionChanged(RunningTaskInfo taskInfo)
            throws RemoteException {
        onTaskDescriptionChanged(taskInfo.taskId, taskInfo.taskDescription);
    }

    /**
     * @deprecated see {@link #onTaskDescriptionChanged(RunningTaskInfo)}
     */
    @Deprecated
    public void onTaskDescriptionChanged(int taskId, ActivityManager.TaskDescription td)
            throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage
    public void onActivityRequestedOrientationChanged(int taskId, int requestedOrientation)
            throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage
    public void onTaskProfileLocked(int taskId, int userId) throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage
    public void onTaskSnapshotChanged(int taskId, TaskSnapshot snapshot) throws RemoteException {
        if (Binder.getCallingPid() != android.os.Process.myPid()
                && snapshot != null && snapshot.getHardwareBuffer() != null) {
            // Preemptively clear any reference to the buffer
            snapshot.getHardwareBuffer().close();
        }
    }

    @Override
    @UnsupportedAppUsage
    public void onSizeCompatModeActivityChanged(int displayId, IBinder activityToken)
            throws RemoteException {
    }

    @Override
    public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo)
            throws RemoteException {
    }

    @Override
    public void onSingleTaskDisplayDrawn(int displayId) throws RemoteException {
    }

    @Override
    public void onSingleTaskDisplayEmpty(int displayId) throws RemoteException {
    }

    @Override
    public void onTaskDisplayChanged(int taskId, int newDisplayId) throws RemoteException {
    }

    @Override
    public void onRecentTaskListUpdated() throws RemoteException {
    }

    @Override
    public void onRecentTaskListFrozenChanged(boolean frozen) {
    }

    @Override
    public void onTaskFocusChanged(int taskId, boolean focused) {
    }
}
