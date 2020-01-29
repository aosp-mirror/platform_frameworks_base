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
import android.content.LocusId;
import android.net.Uri;
import android.util.ArrayMap;

import java.util.Map;
import java.util.function.Consumer;

/** The store that stores and accesses the conversations data for a package. */
class ConversationStore {

    // Shortcut ID -> Conversation Info
    private final Map<String, ConversationInfo> mConversationInfoMap = new ArrayMap<>();

    // Locus ID -> Shortcut ID
    private final Map<LocusId, String> mLocusIdToShortcutIdMap = new ArrayMap<>();

    // Contact URI -> Shortcut ID
    private final Map<Uri, String> mContactUriToShortcutIdMap = new ArrayMap<>();

    // Phone Number -> Shortcut ID
    private final Map<String, String> mPhoneNumberToShortcutIdMap = new ArrayMap<>();

    void addOrUpdate(@NonNull ConversationInfo conversationInfo) {
        mConversationInfoMap.put(conversationInfo.getShortcutId(), conversationInfo);

        LocusId locusId = conversationInfo.getLocusId();
        if (locusId != null) {
            mLocusIdToShortcutIdMap.put(locusId, conversationInfo.getShortcutId());
        }

        Uri contactUri = conversationInfo.getContactUri();
        if (contactUri != null) {
            mContactUriToShortcutIdMap.put(contactUri, conversationInfo.getShortcutId());
        }

        String phoneNumber = conversationInfo.getContactPhoneNumber();
        if (phoneNumber != null) {
            mPhoneNumberToShortcutIdMap.put(phoneNumber, conversationInfo.getShortcutId());
        }
    }

    void deleteConversation(@NonNull String shortcutId) {
        ConversationInfo conversationInfo = mConversationInfoMap.remove(shortcutId);
        if (conversationInfo == null) {
            return;
        }

        LocusId locusId = conversationInfo.getLocusId();
        if (locusId != null) {
            mLocusIdToShortcutIdMap.remove(locusId);
        }

        Uri contactUri = conversationInfo.getContactUri();
        if (contactUri != null) {
            mContactUriToShortcutIdMap.remove(contactUri);
        }

        String phoneNumber = conversationInfo.getContactPhoneNumber();
        if (phoneNumber != null) {
            mPhoneNumberToShortcutIdMap.remove(phoneNumber);
        }
    }

    void forAllConversations(@NonNull Consumer<ConversationInfo> consumer) {
        for (ConversationInfo ci : mConversationInfoMap.values()) {
            consumer.accept(ci);
        }
    }

    @Nullable
    ConversationInfo getConversation(@Nullable String shortcutId) {
        return shortcutId != null ? mConversationInfoMap.get(shortcutId) : null;
    }

    @Nullable
    ConversationInfo getConversationByLocusId(@NonNull LocusId locusId) {
        return getConversation(mLocusIdToShortcutIdMap.get(locusId));
    }

    @Nullable
    ConversationInfo getConversationByContactUri(@NonNull Uri contactUri) {
        return getConversation(mContactUriToShortcutIdMap.get(contactUri));
    }

    @Nullable
    ConversationInfo getConversationByPhoneNumber(@NonNull String phoneNumber) {
        return getConversation(mPhoneNumberToShortcutIdMap.get(phoneNumber));
    }
}
