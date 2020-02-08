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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** The store that stores and accesses the events data for a package. */
class EventStore {

    /** The events that are queryable with a shortcut ID. */
    static final int CATEGORY_SHORTCUT_BASED = 0;

    /** The events that are queryable with a {@link android.content.LocusId}. */
    static final int CATEGORY_LOCUS_ID_BASED = 1;

    /** The phone call events that are queryable with a phone number. */
    static final int CATEGORY_CALL = 2;

    /** The SMS or MMS events that are queryable with a phone number. */
    static final int CATEGORY_SMS = 3;

    /** The events that are queryable with an {@link android.app.Activity} class name. */
    static final int CATEGORY_CLASS_BASED = 4;

    @IntDef(prefix = { "CATEGORY_" }, value = {
            CATEGORY_SHORTCUT_BASED,
            CATEGORY_LOCUS_ID_BASED,
            CATEGORY_CALL,
            CATEGORY_SMS,
            CATEGORY_CLASS_BASED,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface EventCategory {}

    private final List<Map<String, EventHistoryImpl>> mEventHistoryMaps = new ArrayList<>();

    EventStore() {
        mEventHistoryMaps.add(CATEGORY_SHORTCUT_BASED, new ArrayMap<>());
        mEventHistoryMaps.add(CATEGORY_LOCUS_ID_BASED, new ArrayMap<>());
        mEventHistoryMaps.add(CATEGORY_CALL, new ArrayMap<>());
        mEventHistoryMaps.add(CATEGORY_SMS, new ArrayMap<>());
        mEventHistoryMaps.add(CATEGORY_CLASS_BASED, new ArrayMap<>());
    }

    /**
     * Gets the {@link EventHistory} for the specified key if exists.
     *
     * @param key Category-specific key, it can be shortcut ID, locus ID, phone number, or class
     *            name.
     */
    @Nullable
    EventHistory getEventHistory(@EventCategory int category, String key) {
        return mEventHistoryMaps.get(category).get(key);
    }

    /**
     * Gets the {@link EventHistoryImpl} for the specified ID or creates a new instance and put it
     * into the store if not exists. The caller needs to verify if the associated conversation
     * exists before calling this method.
     *
     * @param key Category-specific key, it can be shortcut ID, locus ID, phone number, or class
     *            name.
     */
    @NonNull
    EventHistoryImpl getOrCreateEventHistory(@EventCategory int category, String key) {
        return mEventHistoryMaps.get(category).computeIfAbsent(key, k -> new EventHistoryImpl());
    }

    /**
     * Deletes the events and index data for the specified key.
     *
     * @param key Category-specific key, it can be shortcut ID, locus ID, phone number, or class
     *            name.
     */
    void deleteEventHistory(@EventCategory int category, String key) {
        EventHistoryImpl eventHistory = mEventHistoryMaps.get(category).remove(key);
        if (eventHistory != null) {
            eventHistory.onDestroy();
        }
    }

    /** Deletes all the events and index data for the specified category from disk. */
    void deleteEventHistories(@EventCategory int category) {
        mEventHistoryMaps.get(category).clear();
        // TODO: Implement this method to delete the data from disk.
    }

    /** Deletes the events data that exceeds the retention period. */
    void pruneOldEvents(long currentTimeMillis) {
        for (Map<String, EventHistoryImpl> map : mEventHistoryMaps) {
            for (EventHistoryImpl eventHistory : map.values()) {
                eventHistory.pruneOldEvents(currentTimeMillis);
            }
        }
    }

    /**
     * Prunes the event histories whose key (shortcut ID, locus ID or phone number) does not match
     * any conversations.
     *
     * @param keyChecker Check whether there exists a conversation contains this key.
     */
    void pruneOrphanEventHistories(@EventCategory int category, Predicate<String> keyChecker) {
        Set<String> keys = mEventHistoryMaps.get(category).keySet();
        List<String> keysToDelete = new ArrayList<>();
        for (String key : keys) {
            if (!keyChecker.test(key)) {
                keysToDelete.add(key);
            }
        }
        Map<String, EventHistoryImpl> eventHistoryMap = mEventHistoryMaps.get(category);
        for (String key : keysToDelete) {
            EventHistoryImpl eventHistory = eventHistoryMap.remove(key);
            if (eventHistory != null) {
                eventHistory.onDestroy();
            }
        }
    }
}
