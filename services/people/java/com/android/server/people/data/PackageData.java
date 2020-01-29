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
import android.annotation.UserIdInt;
import android.content.LocusId;
import android.text.TextUtils;

import java.util.function.Consumer;

/** The data associated with a package. */
public class PackageData {

    @NonNull
    private final String mPackageName;

    private final @UserIdInt int mUserId;

    @NonNull
    private final ConversationStore mConversationStore;

    @NonNull
    private final EventStore mEventStore;

    private boolean mIsDefaultDialer;

    private boolean mIsDefaultSmsApp;

    PackageData(@NonNull String packageName, @UserIdInt int userId) {
        mPackageName = packageName;
        mUserId = userId;
        mConversationStore = new ConversationStore();
        mEventStore = new EventStore();
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
        return mIsDefaultDialer;
    }

    public boolean isDefaultSmsApp() {
        return mIsDefaultSmsApp;
    }

    @NonNull
    ConversationStore getConversationStore() {
        return mConversationStore;
    }

    @NonNull
    EventStore getEventStore() {
        return mEventStore;
    }

    void setIsDefaultDialer(boolean value) {
        mIsDefaultDialer = value;
    }

    void setIsDefaultSmsApp(boolean value) {
        mIsDefaultSmsApp = value;
    }

    void onDestroy() {
        // TODO: STOPSHIP: Implements this method for the case of package being uninstalled.
    }
}
