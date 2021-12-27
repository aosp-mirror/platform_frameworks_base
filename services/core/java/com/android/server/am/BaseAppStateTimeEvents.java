/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.am;

import android.annotation.NonNull;

import java.util.Iterator;
import java.util.LinkedList;

/**
 * A helper class to track the timestamps of individual events.
 */
class BaseAppStateTimeEvents extends BaseAppStateEvents<Long> {

    BaseAppStateTimeEvents(int uid, @NonNull String packageName, int numOfEventTypes,
            @NonNull String tag, @NonNull MaxTrackingDurationConfig maxTrackingDurationConfig) {
        super(uid, packageName, numOfEventTypes, tag, maxTrackingDurationConfig);
    }

    BaseAppStateTimeEvents(@NonNull BaseAppStateTimeEvents other) {
        super(other);
    }

    @Override
    LinkedList<Long> add(LinkedList<Long> durations, LinkedList<Long> otherDurations) {
        if (otherDurations == null || otherDurations.size() == 0) {
            return durations;
        }
        if (durations == null || durations.size() == 0) {
            return (LinkedList<Long>) otherDurations.clone();
        }
        final Iterator<Long> itl = durations.iterator();
        final Iterator<Long> itr = otherDurations.iterator();
        LinkedList<Long> dest = new LinkedList<>();
        for (long l = itl.next(), r = itr.next(); l != Long.MAX_VALUE || r != Long.MAX_VALUE;) {
            if (l == r) {
                dest.add(l);
                l = itl.hasNext() ? itl.next() : Long.MAX_VALUE;
                r = itr.hasNext() ? itr.next() : Long.MAX_VALUE;
            } else if (l < r) {
                dest.add(l);
                l = itl.hasNext() ? itl.next() : Long.MAX_VALUE;
            } else {
                dest.add(r);
                r = itr.hasNext() ? itr.next() : Long.MAX_VALUE;
            }
        }
        return dest;
    }

    @Override
    int getTotalEventsSince(long since, long now, int index) {
        final LinkedList<Long> timestamps = mEvents[index];
        if (timestamps == null || timestamps.size() == 0) {
            return 0;
        }
        int count = 0;
        for (long timestamp: timestamps) {
            if (timestamp >= since) {
                count++;
            }
        }
        return count;
    }

    void addEvent(long now, int index) {
        addEvent(now, now, index);
    }

    @Override
    void trimEvents(long earliest, int index) {
        final LinkedList<Long> events = mEvents[index];
        if (events == null) {
            return;
        }
        while (events.size() > 0) {
            final long current = events.peek();
            if (current >= earliest) {
                return; // All we have are newer than the given timestamp.
            }
            events.pop();
        }
    }
}
