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
import android.os.Handler;
import android.view.View;

/**
 * An abstraction of something that presents notifications, e.g. StatusBar. Contains methods
 * for both querying the state of the system (some modularised piece of functionality may
 * want to act differently based on e.g. whether the presenter is visible to the user or not) and
 * for affecting the state of the system (e.g. starting an intent, given that the presenter may
 * want to perform some action before doing so).
 */
public interface NotificationPresenter extends NotificationData.Environment,
        NotificationRemoteInputManager.Callback,
        ExpandableNotificationRow.OnExpandClickListener,
        ActivatableNotificationView.OnActivatedListener,
        NotificationEntryManager.Callback {
    /**
     * Returns true if the presenter is not visible. For example, it may not be necessary to do
     * animations if this returns true.
     */
    boolean isPresenterFullyCollapsed();

    /**
     * Returns true if the presenter is locked. For example, if the keyguard is active.
     */
    boolean isPresenterLocked();

    /**
     * Runs the given intent. The presenter may want to run some animations or close itself when
     * this happens.
     */
    void startNotificationGutsIntent(Intent intent, int appUid, ExpandableNotificationRow row);

    /**
     * Returns the Handler for NotificationPresenter.
     */
    Handler getHandler();

    /**
     * Refresh or remove lockscreen artwork from media metadata or the lockscreen wallpaper.
     */
    void updateMediaMetaData(boolean metaDataChanged, boolean allowEnterAnimation);

    /**
     * Called when the locked status of the device is changed for a work profile.
     */
    void onWorkChallengeChanged();

    /**
     * Called when the current user changes.
     * @param newUserId new user id
     */
    void onUserSwitched(int newUserId);

    /**
     * Gets the NotificationLockscreenUserManager for this Presenter.
     */
    NotificationLockscreenUserManager getNotificationLockscreenUserManager();

    /**
     * Wakes the device up if dozing.
     *
     * @param time the time when the request to wake up was issued
     * @param where which view caused this wake up request
     */
    void wakeUpIfDozing(long time, View where);

    /**
     * True if the device currently requires a PIN, pattern, or password to unlock.
     *
     * @param userId user id to query about
     * @return true iff the device is locked
     */
    boolean isDeviceLocked(int userId);

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
     * Called when the row states are updated by NotificationViewHierarchyManager.
     */
    void onUpdateRowStates();
}
