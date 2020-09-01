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

package com.android.server.people.prediction;

import android.annotation.NonNull;

import com.android.server.people.data.ConversationInfo;
import com.android.server.people.data.EventHistory;

/** The conversation data which is used for scoring and then ranking the conversations. */
class ConversationData {

    private final String mPackageName;
    private final int mUserId;
    private final ConversationInfo mConversationInfo;
    private final EventHistory mEventHistory;

    ConversationData(@NonNull String packageName, int userId,
            @NonNull ConversationInfo conversationInfo, @NonNull EventHistory eventHistory) {
        mPackageName = packageName;
        mUserId = userId;
        mConversationInfo = conversationInfo;
        mEventHistory = eventHistory;
    }

    String getPackageName() {
        return mPackageName;
    }

    int getUserId() {
        return mUserId;
    }

    ConversationInfo getConversationInfo() {
        return mConversationInfo;
    }

    EventHistory getEventHistory() {
        return mEventHistory;
    }
}
