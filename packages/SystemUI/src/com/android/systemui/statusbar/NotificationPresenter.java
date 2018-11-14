/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.statusbar;

import android.content.Intent;

import com.android.systemui.statusbar.notification.ActivityLaunchAnimator;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

/**
 * An abstraction of something that presents notifications, e.g. StatusBar. Contains methods
 * for both querying the state of the system (some modularised piece of functionality may
 * want to act differently based on e.g. whether the presenter is visible to the user or not) and
 * for affecting the state of the system (e.g. starting an intent, given that the presenter may
 * want to perform some action before doing so).
 */
public interface NotificationPresenter extends ExpandableNotificationRow.OnExpandClickListener,
        ActivatableNotificationView.OnActivatedListener,
        NotificationEntryManager.Callback {
    /**
     * Returns true if the presenter is not visible. For example, it may not be necessary to do
     * animations if this returns true.
     */
    boolean isPresenterFullyCollapsed();

    /**
     * Runs the given intent. The presenter may want to run some animations or close itself when
     * this happens.
     */
    void startNotificationGutsIntent(Intent intent, int appUid, ExpandableNotificationRow row);

    /**
     * Refresh or remove lockscreen artwork from media metadata or the lockscreen wallpaper.
     */
    void updateMediaMetaData(boolean metaDataChanged, boolean allowEnterAnimation);

    /**
     * Called when the current user changes.
     * @param newUserId new user id
     */
    void onUserSwitched(int newUserId);

    /**
     * @return true iff the device is in vr mode
     */
    boolean isDeviceInVrMode();

    /**
     * Updates the visual representation of the notifications.
     */
    void updateNotificationViews();

    /**
     * Returns the maximum number of notifications to show while locked.
     *
     * @param recompute whether something has changed that means we should recompute this value
     * @return the maximum number of notifications to show while locked
     */
    int getMaxNotificationsWhileLocked(boolean recompute);

    /**
     * True if the presenter
     * @return
     */
    default boolean isPresenterLocked() { return false; }

    /**
     * Called when the row states are updated by {@link NotificationViewHierarchyManager}.
     */
    void onUpdateRowStates();

    /**
     * @return true if the shade is collapsing.
     */
    boolean isCollapsing();

    /**
     * @return true if the shade is collapsing to show an activity over the lock screen
     */
    default public boolean isCollapsingToShowActivityOverLockscreen() {
        return false;
    }

    /**
     * Get the {@link ActivityLaunchAnimator} from the presenter so it can be queried by
     * {@link com.android.systemui.statusbar.phone.StatusBar}
     * @return the current animator
     * @deprecated This is only here for now because StatusBar is still the ActivityLaunchAnimator
     * callback but shouldn't be.
     */
    default public ActivityLaunchAnimator getActivityLaunchAnimator() {
        return null;
    }
}
