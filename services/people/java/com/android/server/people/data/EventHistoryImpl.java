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
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;
import java.util.Set;

class EventHistoryImpl implements EventHistory {

    private final Injector mInjector;

    // Event Type -> Event Index
    private final SparseArray<EventIndex> mEventIndexArray = new SparseArray<>();

    private final EventList mRecentEvents = new EventList();

    EventHistoryImpl() {
        mInjector = new Injector();
    }

    @VisibleForTesting
    EventHistoryImpl(Injector injector) {
        mInjector = injector;
    }

    @Override
    @NonNull
    public EventIndex getEventIndex(@Event.EventType int eventType) {
        EventIndex eventIndex = mEventIndexArray.get(eventType);
        return eventIndex != null ? new EventIndex(eventIndex) : mInjector.createEventIndex();
    }

    @Override
    @NonNull
    public EventIndex getEventIndex(Set<Integer> eventTypes) {
        EventIndex combined = mInjector.createEventIndex();
        for (@Event.EventType int eventType : eventTypes) {
            EventIndex eventIndex = mEventIndexArray.get(eventType);
            if (eventIndex != null) {
                combined = EventIndex.combine(combined, eventIndex);
            }
        }
        return combined;
    }

    @Override
    @NonNull
    public List<Event> queryEvents(Set<Integer> eventTypes, long startTime, long endTime) {
        return mRecentEvents.queryEvents(eventTypes, startTime, endTime);
    }

    void addEvent(Event event) {
        EventIndex eventIndex = mEventIndexArray.get(event.getType());
        if (eventIndex == null) {
            eventIndex = mInjector.createEventIndex();
            mEventIndexArray.put(event.getType(), eventIndex);
        }
        eventIndex.addEvent(event.getTimestamp());
        mRecentEvents.add(event);
    }

    void onDestroy() {
        mEventIndexArray.clear();
        mRecentEvents.clear();
        // TODO: STOPSHIP: Delete the data files.
    }

    /** Deletes the events data that exceeds the retention period. */
    void pruneOldEvents(long currentTimeMillis) {
        // TODO: STOPSHIP: Delete the old events data files.
    }

    @VisibleForTesting
    static class Injector {

        EventIndex createEventIndex() {
            return new EventIndex();
        }
    }
}
