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
    /** Activity was resized to be displayed in split-screen. */
    const int FORCED_RESIZEABLE_REASON_SPLIT_SCREEN = 1;
    /** Activity was resized to be displayed on a secondary display. */
    const int FORCED_RESIZEABLE_REASON_SECONDARY_DISPLAY = 2;

    /** Called whenever there are changes to the state of tasks in a stack. */
    void onTaskStackChanged();

    /** Called whenever an Activity is moved to the pinned stack from another stack. */
    void onActivityPinned(String packageName, int userId, int taskId, int stackId);

    /** Called whenever an Activity is moved from the pinned stack to another stack. */
    void onActivityUnpinned();

    /**
     * Called whenever IActivityManager.startActivity is called on an activity that is already
     * running in the pinned stack and the activity is not actually started, but the task is either
     * brought to the front or a new Intent is delivered to it.
     *
     * @param clearedTask whether or not the launch activity also cleared the task as a part of
     * starting
     */
    void onPinnedActivityRestartAttempt(boolean clearedTask);

    /**
     * Called whenever the pinned stack is starting animating a resize.
     */
    void onPinnedStackAnimationStarted();

    /**
     * Called whenever the pinned stack is done animating a resize.
     */
    void onPinnedStackAnimationEnded();

    /**
     * Called when we launched an activity that we forced to be resizable.
     *
     * @param packageName Package name of the top activity in the task.
     * @param taskId Id of the task.
     * @param reason {@link #FORCED_RESIZEABLE_REASON_SPLIT_SCREEN} or
      *              {@link #FORCED_RESIZEABLE_REASON_SECONDARY_DISPLAY}.
     */
    void onActivityForcedResizable(String packageName, int taskId, int reason);

    /**
     * Called when we launched an activity that dismissed the docked stack.
     */
    void onActivityDismissingDockedStack();

    /**
     * Called when an activity was requested to be launched on a secondary display but was not
     * allowed there.
     *
     * @param taskInfo info about the Activity's task
     * @param requestedDisplayId the id of the requested launch display
     */
    void onActivityLaunchOnSecondaryDisplayFailed(in ActivityManager.RunningTaskInfo taskInfo,
            int requestedDisplayId);

    /**
     * Called when an activity was requested to be launched on a secondary display but was rerouted
     * to default display.
     *
     * @param taskInfo info about the Activity's task
     * @param requestedDisplayId the id of the requested launch display
     */
    void onActivityLaunchOnSecondaryDisplayRerouted(in ActivityManager.RunningTaskInfo taskInfo,
                int requestedDisplayId);

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
     * @param taskInfo info about the task which moved
    */
    void onTaskMovedToFront(in ActivityManager.RunningTaskInfo taskInfo);

    /**
     * Called when a task’s description is changed due to an activity calling
     * ActivityManagerService.setTaskDescription
     *
     * @param taskInfo info about the task which changed, with {@link TaskInfo#taskDescription}
    */
    void onTaskDescriptionChanged(in ActivityManager.RunningTaskInfo taskInfo);

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
     *
     * @param taskInfo info about the task being removed
     */
    void onTaskRemovalStarted(in ActivityManager.RunningTaskInfo taskInfo);

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

    /**
     * Called when the resumed activity is in size compatibility mode and its override configuration
     * is different from the current one of system.
     *
     * @param displayId Id of the display where the activity resides.
     * @param activityToken Token of the size compatibility mode activity. It will be null when
     *                      switching to a activity that is not in size compatibility mode or the
     *                      configuration of the activity.
     * @see com.android.server.wm.AppWindowToken#inSizeCompatMode
     */
    void onSizeCompatModeActivityChanged(int displayId, in IBinder activityToken);

    /**
     * Reports that an Activity received a back key press when there were no additional activities
     * on the back stack.
     *
     * @param taskInfo info about the task which received the back press
     */
    void onBackPressedOnTaskRoot(in ActivityManager.RunningTaskInfo taskInfo);

    /*
     * Called when contents are drawn for the first time on a display which can only contain one
     * task.
     *
     * @param displayId the id of the display on which contents are drawn.
     */
    void onSingleTaskDisplayDrawn(int displayId);

    /**
     * Called when a task is reparented to a stack on a different display.
     *
     * @param taskId id of the task which was moved to a different display.
     * @param newDisplayId id of the new display.
     */
    void onTaskDisplayChanged(int taskId, int newDisplayId);
}
