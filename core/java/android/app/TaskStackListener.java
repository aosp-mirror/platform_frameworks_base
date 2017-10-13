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

import android.app.ActivityManager.TaskSnapshot;
import android.content.ComponentName;
import android.os.RemoteException;

/**
 * Classes interested in observing only a subset of changes using ITaskStackListener can extend
 * this class to avoid having to implement all the methods.
 * @hide
 */
public abstract class TaskStackListener extends ITaskStackListener.Stub {
    @Override
    public void onTaskStackChanged() throws RemoteException {
    }

    @Override
    public void onActivityPinned(String packageName, int userId, int taskId, int stackId)
            throws RemoteException {
    }

    @Override
    public void onActivityUnpinned() throws RemoteException {
    }

    @Override
    public void onPinnedActivityRestartAttempt(boolean clearedTask) throws RemoteException {
    }

    @Override
    public void onPinnedStackAnimationStarted() throws RemoteException {
    }

    @Override
    public void onPinnedStackAnimationEnded() throws RemoteException {
    }

    @Override
    public void onActivityForcedResizable(String packageName, int taskId, int reason)
            throws RemoteException {
    }

    @Override
    public void onActivityDismissingDockedStack() throws RemoteException {
    }

    @Override
    public void onActivityLaunchOnSecondaryDisplayFailed() throws RemoteException {
    }

    @Override
    public void onTaskCreated(int taskId, ComponentName componentName) throws RemoteException {
    }

    @Override
    public void onTaskRemoved(int taskId) throws RemoteException {
    }

    @Override
    public void onTaskMovedToFront(int taskId) throws RemoteException {
    }

    @Override
    public void onTaskRemovalStarted(int taskId) throws RemoteException {
    }

    @Override
    public void onTaskDescriptionChanged(int taskId, ActivityManager.TaskDescription td)
            throws RemoteException {
    }

    @Override
    public void onActivityRequestedOrientationChanged(int taskId, int requestedOrientation)
            throws RemoteException {
    }

    @Override
    public void onTaskProfileLocked(int taskId, int userId) throws RemoteException {
    }

    @Override
    public void onTaskSnapshotChanged(int taskId, TaskSnapshot snapshot) throws RemoteException {
    }
}
