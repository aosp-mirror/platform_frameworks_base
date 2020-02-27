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

import com.android.internal.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A container that holds a list of {@link Event}s in chronological order. */
class EventList {

    private final List<Event> mEvents = new ArrayList<>();

    /**
     * Adds an event to the list unless there is an existing event with the same timestamp and
     * type.
     */
    void add(@NonNull Event event) {
        int index = firstIndexOnOrAfter(event.getTimestamp());
        if (index < mEvents.size()
                && mEvents.get(index).getTimestamp() == event.getTimestamp()
                && isDuplicate(event, index)) {
            return;
        }
        mEvents.add(index, event);
    }


    /**
     * Call #add on each event to keep the order.
     */
    void addAll(@NonNull List<Event> events) {
        for (Event event : events) {
            add(event);
        }
    }

    /**
     * Returns a {@link List} of {@link Event}s whose timestamps are between the specified {@code
     * fromTimestamp}, inclusive, and {@code toTimestamp} exclusive, and match the specified event
     * types.
     *
     * @return a {@link List} of matched {@link Event}s in chronological order.
     */
    @NonNull
    List<Event> queryEvents(@NonNull Set<Integer> eventTypes, long fromTimestamp,
            long toTimestamp) {
        int fromIndex = firstIndexOnOrAfter(fromTimestamp);
        if (fromIndex == mEvents.size()) {
            return new ArrayList<>();
        }
        int toIndex = firstIndexOnOrAfter(toTimestamp);
        if (toIndex < fromIndex) {
            return new ArrayList<>();
        }
        List<Event> result = new ArrayList<>();
        for (int i = fromIndex; i < toIndex; i++) {
            Event e = mEvents.get(i);
            if (eventTypes.contains(e.getType())) {
                result.add(e);
            }
        }
        return result;
    }

    void clear() {
        mEvents.clear();
    }

    /**
     * Returns a copy of events.
     */
    @NonNull
    List<Event> getAllEvents() {
        return CollectionUtils.copyOf(mEvents);
    }

    /**
     * Remove events that are older than the specified cut off threshold timestamp.
     */
    void removeOldEvents(long cutOffThreshold) {

        // Everything before the cut off is considered old, and should be removed.
        int cutOffIndex = firstIndexOnOrAfter(cutOffThreshold);
        if (cutOffIndex == 0) {
            return;
        }

        // Clear entire list if the cut off is greater than the last element.
        int eventsSize = mEvents.size();
        if (cutOffIndex == eventsSize) {
            mEvents.clear();
            return;
        }

        // Reorder the list starting from the cut off index.
        int i = 0;
        for (; cutOffIndex < eventsSize; i++, cutOffIndex++) {
            mEvents.set(i, mEvents.get(cutOffIndex));
        }

        // Clear the list after reordering.
        if (eventsSize > i) {
            mEvents.subList(i, eventsSize).clear();
        }
    }

    /** Returns the first index whose timestamp is greater or equal to the provided timestamp. */
    private int firstIndexOnOrAfter(long timestamp) {
        int result = mEvents.size();
        int low = 0;
        int high = mEvents.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (mEvents.get(mid).getTimestamp() >= timestamp) {
                high = mid - 1;
                result = mid;
            } else {
                low = mid + 1;
            }
        }
        return result;
    }

    /**
     * Checks whether the {@link Event} is duplicate with one of the existing events. The checking
     * starts from the {@code startIndex}.
     */
    private boolean isDuplicate(Event event, int startIndex) {
        int size = mEvents.size();
        int index = startIndex;
        while (index < size && mEvents.get(index).getTimestamp() <= event.getTimestamp()) {
            if (mEvents.get(index++).getType() == event.getType()) {
                return true;
            }
        }
        return false;
    }
}
