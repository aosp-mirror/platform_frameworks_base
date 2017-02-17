/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

import android.app.ActivityManager;
import android.content.ComponentName;

/** @hide */
oneway interface ITaskStackListener {
    /** Called whenever there are changes to the state of tasks in a stack. */
    void onTaskStackChanged();

    /** Called whenever an Activity is moved to the pinned stack from another stack. */
    void onActivityPinned();

    /**
     * Called whenever IActivityManager.startActivity is called on an activity that is already
     * running in the pinned stack and the activity is not actually started, but the task is either
     * brought to the front or a new Intent is delivered to it.
     *
     * @param launchedFromPackage the package name of the activity that initiated the restart
     *                            attempt
     */
    void onPinnedActivityRestartAttempt(String launchedFromPackage);

    /**
     * Called whenever the pinned stack is done animating a resize.
     */
    void onPinnedStackAnimationEnded();

    /**
     * Called when we launched an activity that we forced to be resizable.
     */
    void onActivityForcedResizable(String packageName, int taskId);

    /**
     * Callen when we launched an activity that is dismissed the docked stack.
     */
    void onActivityDismissingDockedStack();

    /**
     * Called when a task is added.
     *
     * @param taskId id of the task.
     * @param componentName of the activity that the task is being started with.
    */
    void onTaskCreated(int taskId, in ComponentName componentName);

    /**
     * Called when a task is removed.
     *
     * @param taskId id of the task.
    */
    void onTaskRemoved(int taskId);

    /**
     * Called when a task is moved to the front of its stack.
     *
     * @param taskId id of the task.
    */
    void onTaskMovedToFront(int taskId);

    /**
     * Called when a task’s description is changed due to an activity calling
     * ActivityManagerService.setTaskDescription
     *
     * @param taskId id of the task.
     * @param td the new TaskDescription.
    */
    void onTaskDescriptionChanged(int taskId, in ActivityManager.TaskDescription td);

    /**
     * Called when a activity’s orientation is changed due to it calling
     * ActivityManagerService.setRequestedOrientation
     *
     * @param taskId id of the task that the activity is in.
     * @param requestedOrientation the new requested orientation.
    */
    void onActivityRequestedOrientationChanged(int taskId, int requestedOrientation);

    /**
     * Called when the task is about to be finished but before its surfaces are
     * removed from the window manager. This allows interested parties to
     * perform relevant animations before the window disappears.
     */
    void onTaskRemovalStarted(int taskId);

    /**
     * Called when the task has been put in a locked state because one or more of the
     * activities inside it belong to a managed profile user, and that user has just
     * been locked.
     */
    void onTaskProfileLocked(int taskId, int userId);

    /**
     * Called when a task snapshot got updated.
     */
    void onTaskSnapshotChanged(int taskId, in ActivityManager.TaskSnapshot snapshot);
}
