/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.widget;

import android.app.Person;

import java.util.List;

/**
 * @hide
 */
final class MessagingData {
    private final Person mUser;
    private final boolean mShowSpinner;
    private final List<MessagingMessage> mHistoricMessagingMessages;
    private final List<MessagingMessage> mNewMessagingMessages;
    private final List<List<MessagingMessage>> mGroups;
    private final List<Person> mSenders;
    private final int mUnreadCount;

    MessagingData(Person user, boolean showSpinner,
            List<MessagingMessage> historicMessagingMessages,
            List<MessagingMessage> newMessagingMessages, List<List<MessagingMessage>> groups,
            List<Person> senders) {
        this(user, showSpinner, /* unreadCount= */0,
                historicMessagingMessages, newMessagingMessages,
                groups,
                senders);
    }

    MessagingData(Person user, boolean showSpinner,
            int unreadCount,
            List<MessagingMessage> historicMessagingMessages,
            List<MessagingMessage> newMessagingMessages,
            List<List<MessagingMessage>> groups,
            List<Person> senders) {
        mUser = user;
        mShowSpinner = showSpinner;
        mUnreadCount = unreadCount;
        mHistoricMessagingMessages = historicMessagingMessages;
        mNewMessagingMessages = newMessagingMessages;
        mGroups = groups;
        mSenders = senders;
    }

    public Person getUser() {
        return mUser;
    }

    public boolean getShowSpinner() {
        return mShowSpinner;
    }

    public List<MessagingMessage> getHistoricMessagingMessages() {
        return mHistoricMessagingMessages;
    }

    public List<MessagingMessage> getNewMessagingMessages() {
        return mNewMessagingMessages;
    }

    public int getUnreadCount() {
        return mUnreadCount;
    }

    public List<Person> getSenders() {
        return mSenders;
    }

    public List<List<MessagingMessage>> getGroups() {
        return mGroups;
    }
}
