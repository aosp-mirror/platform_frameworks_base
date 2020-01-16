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

package com.android.server.people.data;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.LocusId;
import android.text.TextUtils;

import java.io.File;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** The data associated with a package. */
public class PackageData {

    @NonNull
    private final String mPackageName;

    private final @UserIdInt int mUserId;

    @NonNull
    private final ConversationStore mConversationStore;

    @NonNull
    private final EventStore mEventStore;

    private final Predicate<String> mIsDefaultDialerPredicate;

    private final Predicate<String> mIsDefaultSmsAppPredicate;

    private final File mPackageDataDir;

    PackageData(@NonNull String packageName, @UserIdInt int userId,
            @NonNull Predicate<String> isDefaultDialerPredicate,
            @NonNull Predicate<String> isDefaultSmsAppPredicate,
            @NonNull ScheduledExecutorService scheduledExecutorService,
            @NonNull File perUserPeopleDataDir,
            @NonNull ContactsQueryHelper helper) {
        mPackageName = packageName;
        mUserId = userId;

        mPackageDataDir = new File(perUserPeopleDataDir, mPackageName);
        mConversationStore = new ConversationStore(mPackageDataDir, scheduledExecutorService,
                helper);
        mEventStore = new EventStore();
        mIsDefaultDialerPredicate = isDefaultDialerPredicate;
        mIsDefaultSmsAppPredicate = isDefaultSmsAppPredicate;
    }

    /** Called when user is unlocked. */
    void loadFromDisk() {
        mPackageDataDir.mkdirs();
        mConversationStore.loadConversationsFromDisk();
    }

    /** Called when device is shutting down. */
    void saveToDisk() {
        mConversationStore.saveConversationsToDisk();
    }

    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    public @UserIdInt int getUserId() {
        return mUserId;
    }

    /** Iterates over all the conversations in this package. */
    public void forAllConversations(@NonNull Consumer<ConversationInfo> consumer) {
        mConversationStore.forAllConversations(consumer);
    }

    @NonNull
    public EventHistory getPackageLevelEventHistory() {
        return getEventStore().getPackageEventHistory();
    }

    /**
     * Gets the {@link ConversationInfo} for a given shortcut ID. Returns null if such as {@link
     * ConversationInfo} does not exist.
     */
    @Nullable
    public ConversationInfo getConversationInfo(@NonNull String shortcutId) {
        return getConversationStore().getConversation(shortcutId);
    }

    /**
     * Gets the combined {@link EventHistory} for a given shortcut ID. This returned {@link
     * EventHistory} has events of all types, no matter whether they're annotated with shortcut ID,
     * Locus ID, or phone number etc.
     */
    @NonNull
    public EventHistory getEventHistory(@NonNull String shortcutId) {
        AggregateEventHistoryImpl result = new AggregateEventHistoryImpl();

        ConversationInfo conversationInfo = mConversationStore.getConversation(shortcutId);
        if (conversationInfo == null) {
            return result;
        }

        EventHistory shortcutEventHistory = getEventStore().getShortcutEventHistory(shortcutId);
        if (shortcutEventHistory != null) {
            result.addEventHistory(shortcutEventHistory);
        }

        LocusId locusId = conversationInfo.getLocusId();
        if (locusId != null) {
            EventHistory locusEventHistory = getEventStore().getLocusEventHistory(locusId);
            if (locusEventHistory != null) {
                result.addEventHistory(locusEventHistory);
            }
        }

        String phoneNumber = conversationInfo.getContactPhoneNumber();
        if (TextUtils.isEmpty(phoneNumber)) {
            return result;
        }
        if (isDefaultDialer()) {
            EventHistory callEventHistory = getEventStore().getCallEventHistory(phoneNumber);
            if (callEventHistory != null) {
                result.addEventHistory(callEventHistory);
            }
        }
        if (isDefaultSmsApp()) {
            EventHistory smsEventHistory = getEventStore().getSmsEventHistory(phoneNumber);
            if (smsEventHistory != null) {
                result.addEventHistory(smsEventHistory);
            }
        }
        return result;
    }

    public boolean isDefaultDialer() {
        return mIsDefaultDialerPredicate.test(mPackageName);
    }

    public boolean isDefaultSmsApp() {
        return mIsDefaultSmsAppPredicate.test(mPackageName);
    }

    @NonNull
    ConversationStore getConversationStore() {
        return mConversationStore;
    }

    @NonNull
    EventStore getEventStore() {
        return mEventStore;
    }

    void onDestroy() {
        // TODO: STOPSHIP: Implements this method for the case of package being uninstalled.
    }
}
