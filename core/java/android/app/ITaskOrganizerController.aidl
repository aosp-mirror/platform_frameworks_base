/**
 * Copyright (c) 2020, The Android Open Source Project
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
import android.view.ITaskOrganizer;
import android.view.IWindowContainer;
import android.view.WindowContainerTransaction;

/** @hide */
interface ITaskOrganizerController {

    /**
     * Register a TaskOrganizer to manage tasks as they enter the given windowing mode.
     * If there was already a TaskOrganizer for this windowing mode it will be evicted
     * and receive taskVanished callbacks in the process.
     */
    void registerTaskOrganizer(ITaskOrganizer organizer, int windowingMode);

    /** Apply multiple WindowContainer operations at once. */
    void applyContainerTransaction(in WindowContainerTransaction t);

    /** Creates a persistent root task in WM for a particular windowing-mode. */
    ActivityManager.RunningTaskInfo createRootTask(int displayId, int windowingMode);

    /** Deletes a persistent root task in WM */
    boolean deleteRootTask(IWindowContainer task);

    /** Get the root task which contains the current ime target */
    IWindowContainer getImeTarget(int display);

    /**
     * Set's the root task to launch new tasks into on a display. {@code null} means no launch root
     * and thus new tasks just end up directly on the display.
     */
    void setLaunchRoot(int displayId, in IWindowContainer root);
}
