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
import android.content.Context;
import android.os.UserHandle;
import android.util.Log;

/**
 * An abstract class to track task stack changes.
 * Classes should implement this instead of {@link android.app.ITaskStackListener}
 * to reduce IPC calls from system services. These callbacks will be called on the main thread.
 */
public abstract class TaskStackChangeListener {

    /**
     * NOTE: This call is made of the thread that the binder call comes in on.
     */
    public void onTaskStackChangedBackground() { }
    public void onTaskStackChanged() { }
    public void onTaskSnapshotChanged(int taskId, TaskSnapshot snapshot) { }
    public void onActivityPinned(String packageName, int userId, int taskId, int stackId) { }
    public void onActivityUnpinned() { }
    public void onPinnedActivityRestartAttempt(boolean clearedTask) { }
    public void onPinnedStackAnimationStarted() { }
    public void onPinnedStackAnimationEnded() { }
    public void onActivityForcedResizable(String packageName, int taskId, int reason) { }
    public void onActivityDismissingDockedStack() { }
    public void onActivityLaunchOnSecondaryDisplayFailed() { }
    public void onTaskProfileLocked(int taskId, int userId) { }

    /**
     * Checks that the current user matches the user's SystemUI process. Since
     * {@link android.app.ITaskStackListener} is not multi-user aware, handlers of
     * TaskStackChangeListener should make this call to verify that we don't act on events from other
     * user's processes.
     */
    protected final boolean checkCurrentUserId(Context context, boolean debug) {
        int processUserId = UserHandle.myUserId();
        int currentUserId = SystemServicesProxy.getInstance(context).getCurrentUser();
        if (processUserId != currentUserId) {
            if (debug) {
                Log.d(SystemServicesProxy.TAG, "UID mismatch. SystemUI is running uid=" + processUserId
                        + " and the current user is uid=" + currentUserId);
            }
            return false;
        }
        return true;
    }
}
