/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.people;

import static android.Manifest.permission.READ_CONTACTS;
import static android.app.Notification.CATEGORY_MISSED_CALL;
import static android.app.Notification.EXTRA_MESSAGES;
import static android.app.Notification.EXTRA_PEOPLE_LIST;

import android.annotation.Nullable;
import android.app.Notification;
import android.app.Person;
import android.content.pm.PackageManager;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.wm.shell.bubbles.Bubbles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Helper functions to handle notifications in People Tiles. */
public class NotificationHelper {
    private static final boolean DEBUG = PeopleSpaceUtils.DEBUG;
    private static final String TAG = "PeopleNotifHelper";

    /** Returns the notification with highest priority to be shown in People Tiles. */
    public static NotificationEntry getHighestPriorityNotification(
            Set<NotificationEntry> notificationEntries) {
        if (notificationEntries == null || notificationEntries.isEmpty()) {
            return null;
        }

        return notificationEntries
                .stream()
                .filter(NotificationHelper::isMissedCallOrHasContent)
                .sorted(notificationEntryComparator)
                .findFirst().orElse(null);
    }


    /** Notification comparator, checking category and timestamps, in reverse order of priority. */
    public static Comparator<NotificationEntry> notificationEntryComparator =
            new Comparator<NotificationEntry>() {
                @Override
                public int compare(NotificationEntry e1, NotificationEntry e2) {
                    Notification n1 = e1.getSbn().getNotification();
                    Notification n2 = e2.getSbn().getNotification();

                    boolean missedCall1 = isMissedCall(n1);
                    boolean missedCall2 = isMissedCall(n2);
                    if (missedCall1 && !missedCall2) {
                        return -1;
                    }
                    if (!missedCall1 && missedCall2) {
                        return 1;
                    }

                    // Get messages in reverse chronological order.
                    List<Notification.MessagingStyle.Message> messages1 =
                            getMessagingStyleMessages(n1);
                    List<Notification.MessagingStyle.Message> messages2 =
                            getMessagingStyleMessages(n2);

                    if (messages1 != null && messages2 != null) {
                        Notification.MessagingStyle.Message message1 = messages1.get(0);
                        Notification.MessagingStyle.Message message2 = messages2.get(0);
                        return (int) (message2.getTimestamp() - message1.getTimestamp());
                    }

                    if (messages1 == null) {
                        return 1;
                    }
                    if (messages2 == null) {
                        return -1;
                    }
                    return (int) (n2.when - n1.when);
                }
            };

    /** Returns whether {@code e} is a missed call notification. */
    public static boolean isMissedCall(NotificationEntry e) {
        return e != null && e.getSbn().getNotification() != null
                && isMissedCall(e.getSbn().getNotification());
    }

    /** Returns whether {@code notification} is a missed call notification. */
    public static boolean isMissedCall(Notification notification) {
        return notification != null && Objects.equals(notification.category, CATEGORY_MISSED_CALL);
    }

    private static boolean hasContent(NotificationEntry e) {
        if (e == null) {
            return false;
        }
        List<Notification.MessagingStyle.Message> messages =
                getMessagingStyleMessages(e.getSbn().getNotification());
        return messages != null && !messages.isEmpty();
    }

    /** Returns whether {@code e} is a valid conversation notification. */
    public static boolean isValid(NotificationEntry e) {
        return e != null && e.getRanking() != null
                && e.getRanking().getConversationShortcutInfo() != null
                && e.getSbn().getNotification() != null;
    }

    /** Returns whether conversation notification should be shown in People Tile. */
    public static boolean isMissedCallOrHasContent(NotificationEntry e) {
        return isMissedCall(e) || hasContent(e);
    }

    /** Returns whether {@code sbn}'s package has permission to read contacts. */
    public static boolean hasReadContactsPermission(
            PackageManager packageManager, StatusBarNotification sbn) {
        return packageManager.checkPermission(READ_CONTACTS,
                sbn.getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns whether a notification should be matched to other Tiles by Uri.
     *
     * <p>Currently only matches missed calls.
     */
    public static boolean shouldMatchNotificationByUri(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null) {
            if (DEBUG) Log.d(TAG, "Notification is null");
            return false;
        }
        boolean isMissedCall = isMissedCall(notification);
        if (!isMissedCall) {
            if (DEBUG) Log.d(TAG, "Not missed call");
        }
        return isMissedCall;
    }

    /**
     * Try to retrieve a valid Uri via {@code sbn}, falling back to the {@code
     * contactUriFromShortcut} if valid.
     */
    @Nullable
    public static String getContactUri(StatusBarNotification sbn) {
        // First, try to get a Uri from the Person directly set on the Notification.
        ArrayList<Person> people = sbn.getNotification().extras.getParcelableArrayList(
                EXTRA_PEOPLE_LIST);
        if (people != null && people.get(0) != null) {
            String contactUri = people.get(0).getUri();
            if (contactUri != null && !contactUri.isEmpty()) {
                return contactUri;
            }
        }

        // Then, try to get a Uri from the Person set on the Notification message.
        List<Notification.MessagingStyle.Message> messages =
                getMessagingStyleMessages(sbn.getNotification());
        if (messages != null && !messages.isEmpty()) {
            Notification.MessagingStyle.Message message = messages.get(0);
            Person sender = message.getSenderPerson();
            if (sender != null && sender.getUri() != null && !sender.getUri().isEmpty()) {
                return sender.getUri();
            }
        }

        return null;
    }

    /**
     * Returns {@link Notification.MessagingStyle.Message}s from the Notification in chronological
     * order from most recent to least.
     */
    @VisibleForTesting
    @Nullable
    public static List<Notification.MessagingStyle.Message> getMessagingStyleMessages(
            Notification notification) {
        if (notification == null) {
            return null;
        }
        if (notification.isStyle(Notification.MessagingStyle.class)
                && notification.extras != null) {
            final Parcelable[] messages = notification.extras.getParcelableArray(EXTRA_MESSAGES);
            if (!ArrayUtils.isEmpty(messages)) {
                List<Notification.MessagingStyle.Message> sortedMessages =
                        Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages);
                sortedMessages.sort(Collections.reverseOrder(
                        Comparator.comparing(Notification.MessagingStyle.Message::getTimestamp)));
                return sortedMessages;
            }
        }
        return null;
    }

    /** Returns whether {@code notification} is a group conversation. */
    private static boolean isGroupConversation(Notification notification) {
        return notification.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
    }

    /**
     * Returns {@code message}'s sender's name if {@code notification} is from a group conversation.
     */
    public static CharSequence getSenderIfGroupConversation(Notification notification,
            Notification.MessagingStyle.Message message) {
        if (!isGroupConversation(notification)) {
            if (DEBUG) {
                Log.d(TAG, "Notification is not from a group conversation, not checking sender.");
            }
            return null;
        }
        Person person = message.getSenderPerson();
        if (person == null) {
            if (DEBUG) Log.d(TAG, "Notification from group conversation doesn't include sender.");
            return null;
        }
        if (DEBUG) Log.d(TAG, "Returning sender from group conversation notification.");
        return person.getName();
    }

    /** Returns whether {@code entry} is suppressed from shade, meaning we should not show it. */
    public static boolean shouldFilterOut(
            Optional<Bubbles> bubblesOptional, NotificationEntry entry) {
        boolean isSuppressed = false;
        //TODO(b/190822282): Investigate what is causing the NullPointerException
        try {
            isSuppressed = bubblesOptional.isPresent()
                    && bubblesOptional.get().isBubbleNotificationSuppressedFromShade(
                    entry.getKey(), entry.getSbn().getGroupKey());
        } catch (Exception e) {
            Log.e(TAG, "Exception checking if notification is suppressed: " + e);
        }
        return isSuppressed;
    }
}

