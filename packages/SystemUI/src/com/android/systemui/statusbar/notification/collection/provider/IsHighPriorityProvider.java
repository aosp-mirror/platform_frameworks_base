/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.provider;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Person;

import com.android.systemui.Dependency;
import com.android.systemui.statusbar.notification.collection.GroupEntry;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.phone.NotificationGroupManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether the ListEntry is shown to the user as a high priority notification: visible on
 * the lock screen/status bar and in the top section in the shade.
 *
 * A NotificationEntry is considered high priority if it:
 *  - has importance greater than or equal to IMPORTANCE_DEFAULT
 *  OR
 *  - their importance has NOT been set to a low priority option by the user AND the notification
 *  fulfills one of the following:
 *      - has a person associated with it
 *      - has a media session associated with it
 *      - has messaging style
 *
 * A GroupEntry is considered high priority if its representativeEntry (summary) or children are
 * high priority
 */
public class IsHighPriorityProvider extends DerivedMember<ListEntry, Boolean> {
    // TODO: (b/145659174) remove groupManager when moving to NewNotifPipeline. Logic
    //  replaced in GroupEntry and NotifListBuilderImpl
    private final NotificationGroupManager mGroupManager;


    public IsHighPriorityProvider() {
        // TODO: (b/145659174) remove
        mGroupManager = Dependency.get(NotificationGroupManager.class);
    }

    @Override
    protected Boolean computeValue(ListEntry entry) {
        if (entry == null) {
            return false;
        }

        return isHighPriority(entry);
    }

    private boolean isHighPriority(ListEntry listEntry) {
        // requires groups have been set (AFTER PipelineState.STATE_TRANSFORMING)
        final NotificationEntry notifEntry = listEntry.getRepresentativeEntry();
        return notifEntry.getRanking().getImportance() >= NotificationManager.IMPORTANCE_DEFAULT
                || hasHighPriorityCharacteristics(notifEntry)
                || hasHighPriorityChild(listEntry);

    }

    private boolean hasHighPriorityChild(ListEntry entry) {
        // TODO: (b/145659174) remove
        if (entry instanceof NotificationEntry) {
            NotificationEntry notifEntry = (NotificationEntry) entry;
            if (mGroupManager.isSummaryOfGroup(notifEntry.getSbn())) {
                List<NotificationEntry> logicalChildren =
                        mGroupManager.getLogicalChildren(notifEntry.getSbn());
                for (NotificationEntry child : logicalChildren) {
                    if (child.isHighPriority()) {
                        return true;
                    }
                }
            }
        }

        if (entry instanceof GroupEntry) {
            for (NotificationEntry child : ((GroupEntry) entry).getChildren()) {
                if (child.isHighPriority()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasHighPriorityCharacteristics(NotificationEntry entry) {
        return !hasUserSetImportance(entry)
                && (isImportantOngoing(entry)
                || entry.getSbn().getNotification().hasMediaSession()
                || hasPerson(entry)
                || isMessagingStyle(entry));
    }

    private boolean isImportantOngoing(NotificationEntry entry) {
        return entry.getSbn().getNotification().isForegroundService()
                && entry.getRanking().getImportance() >= NotificationManager.IMPORTANCE_LOW;
    }

    private boolean isMessagingStyle(NotificationEntry entry) {
        return Notification.MessagingStyle.class.equals(
                entry.getSbn().getNotification().getNotificationStyle());
    }

    private boolean hasPerson(NotificationEntry entry) {
        // TODO: cache favorite and recent contacts to check contact affinity
        Notification notification = entry.getSbn().getNotification();
        ArrayList<Person> people = notification.extras != null
                ? notification.extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST)
                : new ArrayList<>();
        return people != null && !people.isEmpty();
    }

    private boolean hasUserSetImportance(NotificationEntry entry) {
        return entry.getRanking().getChannel() != null
                && entry.getRanking().getChannel().hasUserSetImportance();
    }

    @Override
    public void onSbnUpdated() {
        invalidate();
    }

    @Override
    public void onRankingUpdated() {
        invalidate();
    }

    @Override
    public void onGroupingUpdated() {
        invalidate();
    }
}
