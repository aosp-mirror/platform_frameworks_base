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

import static com.android.server.people.data.EventStore.CATEGORY_CALL;
import static com.android.server.people.data.EventStore.CATEGORY_CLASS_BASED;
import static com.android.server.people.data.EventStore.CATEGORY_LOCUS_ID_BASED;
import static com.android.server.people.data.EventStore.CATEGORY_SHORTCUT_BASED;
import static com.android.server.people.data.EventStore.CATEGORY_SMS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.content.LocusId;
import android.os.FileUtils;
import android.text.TextUtils;
import android.util.ArrayMap;

import java.io.File;
import java.util.Map;
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
            @NonNull File perUserPeopleDataDir) {
        mPackageName = packageName;
        mUserId = userId;

        mPackageDataDir = new File(perUserPeopleDataDir, mPackageName);
        mPackageDataDir.mkdirs();

        mConversationStore = new ConversationStore(mPackageDataDir, scheduledExecutorService);
        mEventStore = new EventStore(mPackageDataDir, scheduledExecutorService);
        mIsDefaultDialerPredicate = isDefaultDialerPredicate;
        mIsDefaultSmsAppPredicate = isDefaultSmsAppPredicate;
    }

    /**
     * Returns a map of package directory names as keys and their associated {@link PackageData}.
     * This should be called when device is powered on and unlocked.
     */
    @WorkerThread
    @NonNull
    static Map<String, PackageData> packagesDataFromDisk(@UserIdInt int userId,
            @NonNull Predicate<String> isDefaultDialerPredicate,
            @NonNull Predicate<String> isDefaultSmsAppPredicate,
            @NonNull ScheduledExecutorService scheduledExecutorService,
            @NonNull File perUserPeopleDataDir) {
        Map<String, PackageData> results = new ArrayMap<>();
        File[] packageDirs = perUserPeopleDataDir.listFiles(File::isDirectory);
        if (packageDirs == null) {
            return results;
        }
        for (File packageDir : packageDirs) {
            PackageData packageData = new PackageData(packageDir.getName(), userId,
                    isDefaultDialerPredicate, isDefaultSmsAppPredicate, scheduledExecutorService,
                    perUserPeopleDataDir);
            packageData.loadFromDisk();
            results.put(packageDir.getName(), packageData);
        }
        return results;
    }

    private void loadFromDisk() {
        mConversationStore.loadConversationsFromDisk();
        mEventStore.loadFromDisk();
    }

    /** Called when device is shutting down. */
    void saveToDisk() {
        mConversationStore.saveConversationsToDisk();
        mEventStore.saveToDisk();
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

        EventHistory shortcutEventHistory = getEventStore().getEventHistory(
                CATEGORY_SHORTCUT_BASED, shortcutId);
        if (shortcutEventHistory != null) {
            result.addEventHistory(shortcutEventHistory);
        }

        LocusId locusId = conversationInfo.getLocusId();
        if (locusId != null) {
            EventHistory locusEventHistory = getEventStore().getEventHistory(
                    CATEGORY_LOCUS_ID_BASED, locusId.getId());
            if (locusEventHistory != null) {
                result.addEventHistory(locusEventHistory);
            }
        }

        String phoneNumber = conversationInfo.getContactPhoneNumber();
        if (TextUtils.isEmpty(phoneNumber)) {
            return result;
        }
        if (isDefaultDialer()) {
            EventHistory callEventHistory = getEventStore().getEventHistory(
                    CATEGORY_CALL, phoneNumber);
            if (callEventHistory != null) {
                result.addEventHistory(callEventHistory);
            }
        }
        if (isDefaultSmsApp()) {
            EventHistory smsEventHistory = getEventStore().getEventHistory(
                    CATEGORY_SMS, phoneNumber);
            if (smsEventHistory != null) {
                result.addEventHistory(smsEventHistory);
            }
        }
        return result;
    }

    /** Gets the {@link EventHistory} for a given Activity class. */
    @NonNull
    public EventHistory getClassLevelEventHistory(String className) {
        EventHistory eventHistory = getEventStore().getEventHistory(
                CATEGORY_CLASS_BASED, className);
        return eventHistory != null ? eventHistory : new AggregateEventHistoryImpl();
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

    /**
     * Deletes all the data (including conversation, events and index) for the specified
     * conversation shortcut ID.
     */
    void deleteDataForConversation(String shortcutId) {
        ConversationInfo conversationInfo = mConversationStore.deleteConversation(shortcutId);
        if (conversationInfo == null) {
            return;
        }
        mEventStore.deleteEventHistory(CATEGORY_SHORTCUT_BASED, shortcutId);
        if (conversationInfo.getLocusId() != null) {
            mEventStore.deleteEventHistory(
                    CATEGORY_LOCUS_ID_BASED, conversationInfo.getLocusId().getId());
        }
        String phoneNumber = conversationInfo.getContactPhoneNumber();
        if (!TextUtils.isEmpty(phoneNumber)) {
            if (isDefaultDialer()) {
                mEventStore.deleteEventHistory(CATEGORY_CALL, phoneNumber);
            }
            if (isDefaultSmsApp()) {
                mEventStore.deleteEventHistory(CATEGORY_SMS, phoneNumber);
            }
        }
    }

    /** Prunes the events and index data that don't have a associated conversation. */
    void pruneOrphanEvents() {
        mEventStore.pruneOrphanEventHistories(CATEGORY_SHORTCUT_BASED,
                key -> mConversationStore.getConversation(key) != null);
        mEventStore.pruneOrphanEventHistories(CATEGORY_LOCUS_ID_BASED,
                key -> mConversationStore.getConversationByLocusId(new LocusId(key)) != null);
        if (isDefaultDialer()) {
            mEventStore.pruneOrphanEventHistories(CATEGORY_CALL,
                    key -> mConversationStore.getConversationByPhoneNumber(key) != null);
        }
        if (isDefaultSmsApp()) {
            mEventStore.pruneOrphanEventHistories(CATEGORY_SMS,
                    key -> mConversationStore.getConversationByPhoneNumber(key) != null);
        }
    }

    void onDestroy() {
        mEventStore.onDestroy();
        mConversationStore.onDestroy();
        FileUtils.deleteContentsAndDir(mPackageDataDir);
    }
}
