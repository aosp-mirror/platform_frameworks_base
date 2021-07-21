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

package android.window;

import android.content.pm.ParceledListSlice;
import android.window.DisplayAreaAppearedInfo;
import android.window.IDisplayAreaOrganizer;
import android.window.WindowContainerToken;

/** @hide */
interface IDisplayAreaOrganizerController {

    /**
     * Registers a DisplayAreaOrganizer to manage display areas for a given feature. A feature can
     * not be registered by multiple organizers at the same time.
     *
     * @return a list of display areas that should be managed by the organizer.
     * @throws IllegalStateException if the feature has already been registered.
     */
    ParceledListSlice<DisplayAreaAppearedInfo> registerOrganizer(in IDisplayAreaOrganizer organizer,
        int displayAreaFeature);

    /**
     * Unregisters a previously registered display area organizer.
     */
    void unregisterOrganizer(in IDisplayAreaOrganizer organizer);

    /**
     * Creates a persistent {@link com.android.server.wm.TaskDisplayArea}.
     *
     * The new created TDA is organized by the organizer, and will be deleted on calling
     * {@link #deleteTaskDisplayArea(WindowContainerToken)} or {@link #unregisterOrganizer()}.
     *
     * @param displayId the display to create the new TDA in.
     * @param parentFeatureId the parent to create the new TDA in. If it is a
     *                        {@link com.android.server.wm.RootDisplayArea}, the new TDA will be
     *                        placed as the topmost TDA. If it is another TDA, the new TDA will be
     *                        placed as the topmost child.
     *                        Caller can use {@link #FEATURE_ROOT} as the root of the logical
     *                        display, or {@link #FEATURE_DEFAULT_TASK_CONTAINER} as the default
     *                        TDA.
     * @param name the name for the new task display area.
     * @return the new created task display area.
     * @throws IllegalArgumentException if failed to create a new task display area.
     */
    DisplayAreaAppearedInfo createTaskDisplayArea(in IDisplayAreaOrganizer organizer, int displayId,
        int parentFeatureId, in String name);

    /**
     * Deletes a persistent task display area. It can only be one that created by an organizer.
     *
     * @throws IllegalArgumentException if failed to delete the task display area.
     */
    void deleteTaskDisplayArea(in WindowContainerToken taskDisplayArea);
}
