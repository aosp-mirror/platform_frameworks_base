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
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.os.Build;
import android.os.RemoteException;
import android.window.TaskSnapshot;

/**
 * Classes interested in observing only a subset of changes using ITaskStackListener can extend
 * this class to avoid having to implement all the methods.
 *
 * @hide
 */
public abstract class TaskStackListener extends ITaskStackListener.Stub {

    /** Whether this listener and the callback dispatcher are in different processes. */
    private boolean mIsRemote = true;

    @UnsupportedAppUsage
    public TaskStackListener() {
    }

    /** Indicates that this listener lives in system server. */
    public void setIsLocal() {
        mIsRemote = false;
    }

    @Override
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void onTaskStackChanged() throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void onActivityPinned(String packageName, int userId, int taskId, int rootTaskId)
            throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void onActivityUnpinned() throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void onActivityRestartAttempt(RunningTaskInfo task, boolean homeTaskVisible,
            boolean clearedTask, boolean wasVisible) throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void onActivityForcedResizable(String packageName, int taskId, int reason)
            throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void onActivityDismissingDockedTask() throws RemoteException {
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void onActivityLaunchOnSecondaryDisplayFailed() throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void onActivityLaunchOnSecondaryDisplayRerouted(RunningTaskInfo taskInfo,
            int requestedDisplayId) throws RemoteException {
    }

    @Override
    public void onTaskCreated(int taskId, ComponentName componentName) throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void onActivityRequestedOrientationChanged(int taskId, int requestedOrientation)
            throws RemoteException {
    }

    @Override
    public void onTaskProfileLocked(RunningTaskInfo taskInfo, int userId)
            throws RemoteException {
        onTaskProfileLocked(taskInfo);
    }

    /**
     * @deprecated see {@link #onTaskProfileLocked(RunningTaskInfo, int)}
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void onTaskProfileLocked(RunningTaskInfo taskInfo)
            throws RemoteException {
    }

    @Override
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void onTaskSnapshotChanged(int taskId, TaskSnapshot snapshot) throws RemoteException {
        if (mIsRemote && snapshot != null && snapshot.getHardwareBuffer() != null) {
            // Preemptively clear any reference to the buffer
            snapshot.getHardwareBuffer().close();
        }
    }

    @Override
    public void onTaskSnapshotInvalidated(int taskId) { }

    @Override
    public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo)
            throws RemoteException {
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

    @Override
    public void onTaskRequestedOrientationChanged(int taskId, int requestedOrientation) {
    }

    @Override
    public void onActivityRotation(int displayId) {
    }

    @Override
    public void onTaskMovedToBack(RunningTaskInfo taskInfo) {
    }

    @Override
    public void onLockTaskModeChanged(int mode) {
    }
}
