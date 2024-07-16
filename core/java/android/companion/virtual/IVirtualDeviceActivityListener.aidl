/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.companion.virtual;

import android.content.ComponentName;

/**
 * Interface to listen for activity changes in a virtual device.
 *
 * @hide
 */
oneway interface IVirtualDeviceActivityListener {

    /**
     * Called when the top activity is changed.
     *
     * @param displayId The display ID on which the activity change happened.
     * @param topActivity The component name of the top activity.
     * @param userId The user ID associated with the top activity.
     */
    void onTopActivityChanged(int displayId, in ComponentName topActivity, int userId);

    /**
     * Called when the display becomes empty (e.g. if the user hits back on the last
     * activity of the root task).
     *
     * @param displayId The display ID that became empty.
     */
    void onDisplayEmpty(int displayId);

    /**
     * Called when an activity launch was blocked due to a policy violation.
     *
     * @param displayId The display ID on which the activity tried to launch.
     * @param componentName The component name of the blocked activity.
     * @param userId The user ID associated with the blocked activity.
     */
    void onActivityLaunchBlocked(int displayId, in ComponentName componentName, int userId);
}
