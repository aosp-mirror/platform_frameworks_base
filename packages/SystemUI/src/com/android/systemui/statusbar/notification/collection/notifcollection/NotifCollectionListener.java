/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.notifcollection;

import android.annotation.NonNull;
import android.app.NotificationChannel;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.android.systemui.statusbar.notification.collection.NotifCollection.CancellationReason;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * Listener interface for {@link NotificationEntry} events.
 */
public interface NotifCollectionListener {

    /**
     * Called when the entry is having a new status bar notification bound to it. This should
     * be used to initialize any derivative state on the entry that needs to update when the
     * notification is updated.
     */
    default void onEntryBind(NotificationEntry entry, StatusBarNotification sbn) {
    }

    /**
     * Called whenever a new {@link NotificationEntry} is initialized. This should be used for
     * initializing any decorated state tied to the notification.
     *
     * Do not reference other registered {@link NotifCollectionListener} implementations here as
     * there is no guarantee of order and they may not have had a chance to initialize yet. Instead,
     * use {@link #onEntryAdded} which is called after all initialization.
     */
    default void onEntryInit(@NonNull NotificationEntry entry) {
    }

    /**
     * Called whenever a notification with a new key is posted.
     */
    default void onEntryAdded(@NonNull NotificationEntry entry) {
    }

    /**
     * Called whenever a notification with the same key as an existing notification is posted. By
     * the time this listener is called, the entry's SBN and Ranking will already have been updated.
     * This delegates to {@link #onEntryUpdated(NotificationEntry)} by default.
     * @param fromSystem If true, this update came from the NotificationManagerService.
     *                   If false, the notification update is an internal change within systemui.
     */
    default void onEntryUpdated(@NonNull NotificationEntry entry, boolean fromSystem) {
        onEntryUpdated(entry);
    }

    /**
     * Called whenever a notification with the same key as an existing notification is posted. By
     * the time this listener is called, the entry's SBN and Ranking will already have been updated.
     */
    default void onEntryUpdated(@NonNull NotificationEntry entry) {
    }

    /**
     * Called whenever a notification is retracted by system server. This method is not called
     * immediately after a user dismisses a notification: we wait until we receive confirmation from
     * system server before considering the notification removed.
     */
    default void onEntryRemoved(@NonNull NotificationEntry entry, @CancellationReason int reason) {
    }

    /**
     * Called whenever a {@link NotificationEntry} is considered deleted. This should be used for
     * cleaning up any state tied to the notification.
     *
     * This is the deletion parallel of {@link #onEntryInit} and similarly means that you cannot
     * expect other {@link NotifCollectionListener} implementations to still have valid state for
     * the entry during this call. Instead, use {@link #onEntryRemoved} which will be called before
     * deletion.
     */
    default void onEntryCleanUp(@NonNull NotificationEntry entry) {
    }

    /**
     * Called whenever a ranking update is applied. During a ranking update, all active,
     * non-lifetime-extended notification entries will have their ranking object updated.
     *
     * Ranking updates occur whenever a notification is added, updated, or removed, or when a
     * standalone ranking is sent from the server. If a non-standalone ranking is applied, the event
     * that accompanied the ranking is emitted first (e.g. {@link #onEntryAdded}), followed by the
     * ranking event.
     */
    default void onRankingApplied() {
    }

    /**
     * Called whenever system server sends a standalone ranking update (i.e. one that isn't
     * associated with a notification being added or removed).
     *
     * In general it is unsafe to depend on this method as rankings can change for other reasons.
     * Instead, listen for {@link #onRankingApplied()}, which is called whenever ANY ranking update
     * is applied, regardless of source.
     *
     * @deprecated Use {@link #onRankingApplied()} instead.
     */
    default void onRankingUpdate(NotificationListenerService.RankingMap rankingMap) {
    }

    /**
     * Called when a notification channel is modified, in response to
     * {@link NotificationListenerService#onNotificationChannelModified}.
     *
     * @param pkgName the package the notification channel belongs to.
     * @param user the user the notification channel belongs to.
     * @param channel the channel being modified.
     * @param modificationType the type of modification that occurred to the channel.
     */
    default void onNotificationChannelModified(
            String pkgName,
            UserHandle user,
            NotificationChannel channel,
            int modificationType) {
    }
}
