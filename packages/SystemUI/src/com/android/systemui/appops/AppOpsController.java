/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.appops;

import java.util.List;

/**
 * Controller to keep track of applications that have requested access to given App Ops.
 *
 * It can be subscribed to with callbacks. Additionally, it passes on the information to
 * NotificationPresenter to be displayed to the user.
 */
public interface AppOpsController {

    /**
     * Callback to notify when the state of active AppOps tracked by the controller has changed
     */
    interface Callback {
        void onActiveStateChanged(int code, int uid, String packageName, boolean active);
    }

    /**
     * Adds a callback that will get notified when an AppOp of the type the controller tracks
     * changes
     *
     * @param opsCodes App Ops the callback was interested in checking
     * @param cb Callback to report changes
     *
     * @see #removeCallback(int[], Callback)
     */
    void addCallback(int[] opsCodes, Callback cb);

    /**
     * Removes a callback from those notifified when an AppOp of the type the controller tracks
     * changes
     *
     * @param opsCodes App Ops the callback is interested in checking
     * @param cb Callback to stop reporting changes
     *
     * @see #addCallback(int[], Callback)
     */
    void removeCallback(int[] opsCodes, Callback cb);

    /**
     * Returns a copy of the list containing all the active AppOps that the controller tracks.
     *
     * @return List of active AppOps information
     */
    List<AppOpItem> getActiveAppOps();

    /**
     * Returns a copy of the list containing all the active AppOps that the controller tracks, for
     * a given user id.
     *
     * @param userId User id to track
     *
     * @return List of active AppOps information for that user id
     */
    List<AppOpItem> getActiveAppOpsForUser(int userId);
}
