/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.window;

import android.view.SurfaceControl;
import android.app.ActivityManager;
import android.graphics.Rect;
import android.window.StartingWindowInfo;
import android.window.WindowContainerToken;

/**
 * Interface for ActivityTaskManager/WindowManager to delegate control of tasks.
 * {@hide}
 */
oneway interface ITaskOrganizer {
    /**
     * Called when a Task is starting and the system would like to show a UI to indicate that an
     * application is starting. The client is responsible to add/remove the starting window if it
     * has create a starting window for the Task.
     *
     * @param info The information about the Task that's available
     * @param appToken Token of the application being started.
     */
    void addStartingWindow(in StartingWindowInfo info, IBinder appToken);

    /**
     * Called when the Task want to remove the starting window.
     * @param leash A persistent leash for the top window in this task.
     * @param frame Window frame of the top window.
     * @param playRevealAnimation Play vanish animation.
     */
    void removeStartingWindow(int taskId, in SurfaceControl leash, in Rect frame,
            in boolean playRevealAnimation);

    /**
     * Called when the Task want to copy the splash screen.
     */
    void copySplashScreenView(int taskId);

    /**
     * Called when the Task removed the splash screen.
     */
    void onAppSplashScreenViewRemoved(int taskId);

    /**
     * A callback when the Task is available for the registered organizer. The client is responsible
     * for releasing the SurfaceControl in the callback. For non-root tasks, the leash may initially
     * be hidden so it is up to the organizer to show this task.
     *
     * @param taskInfo The information about the Task that's available
     * @param leash A persistent leash for this Task.
     */
    void onTaskAppeared(in ActivityManager.RunningTaskInfo taskInfo, in SurfaceControl leash);
    void onTaskVanished(in ActivityManager.RunningTaskInfo taskInfo);

    /**
     * Will fire when core attributes of a Task's info change. Relevant properties include the
     * {@link WindowConfiguration.ActivityType} and whether it is resizable.
     *
     * This is used, for example, during split-screen. The flow for starting is: Something sends an
     * Intent with windowingmode. Then WM finds a matching root task and launches the new task into
     * it. This causes the root task's info to change because now it has a task when it didn't
     * before. The default Divider implementation interprets this as a request to enter
     * split-screen mode and will move all other Tasks into the secondary root task. When WM
     * applies this change, it triggers an info change in the secondary root task because it now
     * has children. The Divider impl looks at the info and can see that the secondary root task
     * has adopted an ActivityType of HOME and proceeds to show the minimized dock UX.
     */
    void onTaskInfoChanged(in ActivityManager.RunningTaskInfo taskInfo);

    /**
     * Called when the task organizer has requested
     * {@link ITaskOrganizerController.setInterceptBackPressedOnTaskRoot} to get notified when the
     * user has pressed back on the root activity of a task controlled by the task organizer.
     */
    void onBackPressedOnTaskRoot(in ActivityManager.RunningTaskInfo taskInfo);
}
