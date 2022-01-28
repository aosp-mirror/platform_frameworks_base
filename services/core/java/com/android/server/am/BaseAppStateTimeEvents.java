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

import com.android.server.am.BaseAppStateTimeEvents.BaseTimeEvent;

import java.util.Iterator;
import java.util.LinkedList;

/**
 * A helper class to track the timestamps of individual events.
 */
class BaseAppStateTimeEvents<T extends BaseTimeEvent> extends BaseAppStateEvents<T> {

    BaseAppStateTimeEvents(int uid, @NonNull String packageName, int numOfEventTypes,
            @NonNull String tag, @NonNull MaxTrackingDurationConfig maxTrackingDurationConfig) {
        super(uid, packageName, numOfEventTypes, tag, maxTrackingDurationConfig);
    }

    BaseAppStateTimeEvents(@NonNull BaseAppStateTimeEvents other) {
        super(other);
    }

    @Override
    LinkedList<T> add(LinkedList<T> durations, LinkedList<T> otherDurations) {
        if (otherDurations == null || otherDurations.size() == 0) {
            return durations;
        }
        if (durations == null || durations.size() == 0) {
            return (LinkedList<T>) otherDurations.clone();
        }
        final Iterator<T> itl = durations.iterator();
        final Iterator<T> itr = otherDurations.iterator();
        T l = itl.next(), r = itr.next();
        LinkedList<T> dest = new LinkedList<>();
        for (long lts = l.getTimestamp(), rts = r.getTimestamp();
                lts != Long.MAX_VALUE || rts != Long.MAX_VALUE;) {
            if (lts == rts) {
                dest.add((T) l.clone());
                lts = itl.hasNext() ? (l = itl.next()).getTimestamp() : Long.MAX_VALUE;
                rts = itr.hasNext() ? (r = itr.next()).getTimestamp() : Long.MAX_VALUE;
            } else if (lts < rts) {
                dest.add((T) l.clone());
                lts = itl.hasNext() ? (l = itl.next()).getTimestamp() : Long.MAX_VALUE;
            } else {
                dest.add((T) r.clone());
                rts = itr.hasNext() ? (r = itr.next()).getTimestamp() : Long.MAX_VALUE;
            }
        }
        return dest;
    }

    @Override
    int getTotalEventsSince(long since, long now, int index) {
        final LinkedList<T> events = mEvents[index];
        if (events == null || events.size() == 0) {
            return 0;
        }
        int count = 0;
        for (T event : events) {
            if (event.getTimestamp() >= since) {
                count++;
            }
        }
        return count;
    }

    @Override
    void trimEvents(long earliest, int index) {
        final LinkedList<T> events = mEvents[index];
        if (events == null) {
            return;
        }
        while (events.size() > 0) {
            final T current = events.peek();
            if (current.getTimestamp() >= earliest) {
                return; // All we have are newer than the given timestamp.
            }
            events.pop();
        }
    }

    /**
     * A data class encapsulate the individual event data.
     */
    static class BaseTimeEvent implements Cloneable {
        /**
         * The timestamp this event occurred at.
         */
        long mTimestamp;

        BaseTimeEvent(long timestamp) {
            mTimestamp = timestamp;
        }

        BaseTimeEvent(BaseTimeEvent other) {
            mTimestamp = other.mTimestamp;
        }

        void trimTo(long timestamp) {
            mTimestamp = timestamp;
        }

        long getTimestamp() {
            return mTimestamp;
        }

        @Override
        public Object clone() {
            return new BaseTimeEvent(this);
        }

        @Override
        public boolean equals(Object other) {
            if (other == null) {
                return false;
            }
            if (other.getClass() != BaseTimeEvent.class) {
                return false;
            }
            return ((BaseTimeEvent) other).mTimestamp == mTimestamp;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(mTimestamp);
        }
    }
}
