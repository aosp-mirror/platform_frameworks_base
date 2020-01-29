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
import android.util.ArrayMap;

import java.util.Map;

/** The store that stores and accesses the events data for a package. */
class EventStore {

    private final EventHistoryImpl mPackageEventHistory = new EventHistoryImpl();

    // Shortcut ID -> Event History
    private final Map<String, EventHistoryImpl> mShortcutEventHistoryMap = new ArrayMap<>();

    // Locus ID -> Event History
    private final Map<LocusId, EventHistoryImpl> mLocusEventHistoryMap = new ArrayMap<>();

    // Phone Number -> Event History
    private final Map<String, EventHistoryImpl> mCallEventHistoryMap = new ArrayMap<>();

    // Phone Number -> Event History
    private final Map<String, EventHistoryImpl> mSmsEventHistoryMap = new ArrayMap<>();

    /** Gets the package level {@link EventHistory}. */
    @NonNull
    EventHistory getPackageEventHistory() {
        return mPackageEventHistory;
    }

    /** Gets the {@link EventHistory} for the specified {@code shortcutId} if exists. */
    @Nullable
    EventHistory getShortcutEventHistory(String shortcutId) {
        return mShortcutEventHistoryMap.get(shortcutId);
    }

    /** Gets the {@link EventHistory} for the specified {@code locusId} if exists. */
    @Nullable
    EventHistory getLocusEventHistory(LocusId locusId) {
        return mLocusEventHistoryMap.get(locusId);
    }

    /** Gets the phone call {@link EventHistory} for the specified {@code phoneNumber} if exists. */
    @Nullable
    EventHistory getCallEventHistory(String phoneNumber) {
        return mCallEventHistoryMap.get(phoneNumber);
    }

    /** Gets the SMS {@link EventHistory} for the specified {@code phoneNumber} if exists. */
    @Nullable
    EventHistory getSmsEventHistory(String phoneNumber) {
        return mSmsEventHistoryMap.get(phoneNumber);
    }

    /**
     * Gets the {@link EventHistoryImpl} for the specified {@code shortcutId} or creates a new
     * instance and put it into the store if not exists. The caller needs to verify if a
     * conversation with this shortcut ID exists before calling this method.
     */
    @NonNull
    EventHistoryImpl getOrCreateShortcutEventHistory(String shortcutId) {
        return mShortcutEventHistoryMap.computeIfAbsent(shortcutId, key -> new EventHistoryImpl());
    }

    /**
     * Gets the {@link EventHistoryImpl} for the specified {@code locusId} or creates a new
     * instance and put it into the store if not exists. The caller needs to ensure a conversation
     * with this locus ID exists before calling this method.
     */
    @NonNull
    EventHistoryImpl getOrCreateLocusEventHistory(LocusId locusId) {
        return mLocusEventHistoryMap.computeIfAbsent(locusId, key -> new EventHistoryImpl());
    }

    /**
     * Gets the {@link EventHistoryImpl} for the specified {@code phoneNumber} for call events
     * or creates a new instance and put it into the store if not exists. The caller needs to ensure
     * a conversation with this phone number exists and this package is the default dialer
     * before calling this method.
     */
    @NonNull
    EventHistoryImpl getOrCreateCallEventHistory(String phoneNumber) {
        return mCallEventHistoryMap.computeIfAbsent(phoneNumber, key -> new EventHistoryImpl());
    }

    /**
     * Gets the {@link EventHistoryImpl} for the specified {@code phoneNumber} for SMS events
     * or creates a new instance and put it into the store if not exists. The caller needs to ensure
     * a conversation with this phone number exists and this package is the default SMS app
     * before calling this method.
     */
    @NonNull
    EventHistoryImpl getOrCreateSmsEventHistory(String phoneNumber) {
        return mSmsEventHistoryMap.computeIfAbsent(phoneNumber, key -> new EventHistoryImpl());
    }
}
