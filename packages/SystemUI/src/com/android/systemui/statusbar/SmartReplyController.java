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
 * limitations under the License
 */
package com.android.systemui.statusbar;

import android.app.Notification;
import android.os.RemoteException;
import android.util.ArraySet;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.statusbar.dagger.StatusBarModule;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;

import java.util.Set;

/**
 * Handles when smart replies are added to a notification
 * and clicked upon.
 */
public class SmartReplyController {
    private final IStatusBarService mBarService;
    private final NotificationEntryManager mEntryManager;
    private final NotificationClickNotifier mClickNotifier;
    private Set<String> mSendingKeys = new ArraySet<>();
    private Callback mCallback;

    /**
     * Injected constructor. See {@link StatusBarModule}.
     */
    public SmartReplyController(NotificationEntryManager entryManager,
            IStatusBarService statusBarService,
            NotificationClickNotifier clickNotifier) {
        mBarService = statusBarService;
        mEntryManager = entryManager;
        mClickNotifier = clickNotifier;
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    /**
     * Notifies StatusBarService a smart reply is sent.
     */
    public void smartReplySent(NotificationEntry entry, int replyIndex, CharSequence reply,
            int notificationLocation, boolean modifiedBeforeSending) {
        mCallback.onSmartReplySent(entry, reply);
        mSendingKeys.add(entry.getKey());
        try {
            mBarService.onNotificationSmartReplySent(entry.getSbn().getKey(), replyIndex, reply,
                    notificationLocation, modifiedBeforeSending);
        } catch (RemoteException e) {
            // Nothing to do, system going down
        }
    }

    /**
     * Notifies StatusBarService a smart action is clicked.
     */
    public void smartActionClicked(
            NotificationEntry entry, int actionIndex, Notification.Action action,
            boolean generatedByAssistant) {
        final int count = mEntryManager.getActiveNotificationsCount();
        final int rank = entry.getRanking().getRank();
        NotificationVisibility.NotificationLocation location =
                NotificationLogger.getNotificationLocation(entry);
        final NotificationVisibility nv = NotificationVisibility.obtain(
                entry.getKey(), rank, count, true, location);
        mClickNotifier.onNotificationActionClick(
                entry.getKey(), actionIndex, action, nv, generatedByAssistant);
    }

    /**
     * Have we posted an intent to an app about sending a smart reply from the
     * notification with this key.
     */
    public boolean isSendingSmartReply(String key) {
        return mSendingKeys.contains(key);
    }

    /**
     * Smart Replies and Actions have been added to the UI.
     */
    public void smartSuggestionsAdded(final NotificationEntry entry, int replyCount,
            int actionCount, boolean generatedByAssistant, boolean editBeforeSending) {
        try {
            mBarService.onNotificationSmartSuggestionsAdded(entry.getSbn().getKey(), replyCount,
                    actionCount, generatedByAssistant, editBeforeSending);
        } catch (RemoteException e) {
            // Nothing to do, system going down
        }
    }

    public void stopSending(final NotificationEntry entry) {
        if (entry != null) {
            mSendingKeys.remove(entry.getSbn().getKey());
        }
    }

    /**
     * Callback for any class that needs to do something in response to a smart reply being sent.
     */
    public interface Callback {
        /**
         * A smart reply has just been sent for a notification
         *
         * @param entry the entry for the notification
         * @param reply the reply that was sent
         */
        void onSmartReplySent(NotificationEntry entry, CharSequence reply);
    }
}
