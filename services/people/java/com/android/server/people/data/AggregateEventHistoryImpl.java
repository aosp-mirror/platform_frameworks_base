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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** An {@link EventHistory} that aggregates multiple {@link EventHistory}.  */
class AggregateEventHistoryImpl implements EventHistory {

    private final List<EventHistory> mEventHistoryList = new ArrayList<>();

    @NonNull
    @Override
    public EventIndex getEventIndex(int eventType) {
        for (EventHistory eventHistory : mEventHistoryList) {
            EventIndex eventIndex = eventHistory.getEventIndex(eventType);
            if (!eventIndex.isEmpty()) {
                return eventIndex;
            }
        }
        return EventIndex.EMPTY;
    }

    @NonNull
    @Override
    public EventIndex getEventIndex(Set<Integer> eventTypes) {
        EventIndex merged = null;
        for (EventHistory eventHistory : mEventHistoryList) {
            EventIndex eventIndex = eventHistory.getEventIndex(eventTypes);
            if (merged == null) {
                merged = eventIndex;
            } else if (!eventIndex.isEmpty()) {
                merged = EventIndex.combine(merged, eventIndex);
            }
        }
        return merged != null ? merged : EventIndex.EMPTY;
    }

    @NonNull
    @Override
    public List<Event> queryEvents(Set<Integer> eventTypes, long startTime, long endTime) {
        List<Event> results = new ArrayList<>();
        for (EventHistory eventHistory : mEventHistoryList) {
            EventIndex eventIndex = eventHistory.getEventIndex(eventTypes);
            if (eventIndex.isEmpty()) {
                continue;
            }
            List<Event> queryResults = eventHistory.queryEvents(eventTypes, startTime, endTime);
            results = combineEventLists(results, queryResults);
        }
        return results;
    }

    void addEventHistory(EventHistory eventHistory) {
        mEventHistoryList.add(eventHistory);
    }

    /**
     * Combines the sorted events (in chronological order) from the given 2 lists {@code lhs}
     * and {@code rhs} and preserves the order.
     */
    private List<Event> combineEventLists(List<Event> lhs, List<Event> rhs) {
        List<Event> results = new ArrayList<>();
        int i = 0, j = 0;
        while (i < lhs.size() && j < rhs.size()) {
            if (lhs.get(i).getTimestamp() < rhs.get(j).getTimestamp()) {
                results.add(lhs.get(i++));
            } else {
                results.add(rhs.get(j++));
            }
        }
        if (i < lhs.size()) {
            results.addAll(lhs.subList(i, lhs.size()));
        } else if (j < rhs.size()) {
            results.addAll(rhs.subList(j, rhs.size()));
        }
        return results;
    }
}
