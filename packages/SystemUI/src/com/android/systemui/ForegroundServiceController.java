/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui;

import android.annotation.Nullable;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;

public interface ForegroundServiceController {
    /**
     * @param sbn notification that was just posted
     * @param importance
     */
    void addNotification(StatusBarNotification sbn, int importance);

    /**
     * @param sbn notification that was just changed in some way
     * @param newImportance
     */
    void updateNotification(StatusBarNotification sbn, int newImportance);

    /**
     * @param sbn notification that was just canceled
     */
    boolean removeNotification(StatusBarNotification sbn);

    /**
     * @param userId
     * @return true if this user has services missing notifications and therefore needs a
     * disclosure notification.
     */
    boolean isDungeonNeededForUser(int userId);

    /**
     * @param sbn
     * @return true if sbn is the system-provided "dungeon" (list of running foreground services).
     */
    boolean isDungeonNotification(StatusBarNotification sbn);

    /**
     * @return true if sbn is one of the window manager "drawing over other apps" notifications
     */
    boolean isSystemAlertNotification(StatusBarNotification sbn);

    /**
     * Returns the key of the foreground service from this package using the standard template,
     * if one exists.
     */
    @Nullable String getStandardLayoutKey(int userId, String pkg);

    /**
     * @return true if this user/pkg has a missing or custom layout notification and therefore needs
     * a disclosure notification for system alert windows.
     */
    boolean isSystemAlertWarningNeeded(int userId, String pkg);

    /**
     * Records active app ops. App Ops are stored in FSC in addition to NotificationData in
     * case they change before we have a notification to tag.
     */
    void onAppOpChanged(int code, int uid, String packageName, boolean active);

    /**
     * Gets active app ops for this user and package
     */
    @Nullable ArraySet<Integer> getAppOps(int userId, String packageName);
}
