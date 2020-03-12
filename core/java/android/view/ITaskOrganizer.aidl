/* //device/java/android/android/view/ITaskOrganizer.aidl
**
** Copyright 2019, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.view;

import android.view.IWindowContainer;
import android.view.SurfaceControl;
import android.app.ActivityManager;

/**
 * Interface for ActivityTaskManager/WindowManager to delegate control of tasks.
 * {@hide}
 */
oneway interface ITaskOrganizer {
    void taskAppeared(in ActivityManager.RunningTaskInfo taskInfo);
    void taskVanished(in ActivityManager.RunningTaskInfo taskInfo);

    /**
     * Called upon completion of
     * ActivityTaskManagerService#applyTaskOrganizerTransaction
     */
    void transactionReady(int id, in SurfaceControl.Transaction t);

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
    void onTaskInfoChanged(in ActivityManager.RunningTaskInfo info);
}