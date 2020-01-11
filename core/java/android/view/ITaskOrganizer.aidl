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
    void taskAppeared(in IWindowContainer container,
        in ActivityManager.RunningTaskInfo taskInfo);
    void taskVanished(in IWindowContainer container);

    /**
     * Called upon completion of
     * ActivityTaskManagerService#applyTaskOrganizerTransaction
     */
    void transactionReady(int id, in SurfaceControl.Transaction t);
}