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
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.server.am.BaseAppStateTimeEvents.BaseTimeEvent;

import java.util.Iterator;
import java.util.LinkedList;

/**
 * A helper class to track the accumulated durations of certain events; supports tracking event
 * start/stop, trim.
 */
abstract class BaseAppStateDurations<T extends BaseTimeEvent> extends BaseAppStateTimeEvents<T> {
    static final boolean DEBUG_BASE_APP_STATE_DURATIONS = false;

    BaseAppStateDurations(int uid, @NonNull String packageName, int numOfEventTypes,
            @NonNull String tag, @NonNull MaxTrackingDurationConfig maxTrackingDurationConfig) {
        super(uid, packageName, numOfEventTypes, tag, maxTrackingDurationConfig);
    }

    BaseAppStateDurations(@NonNull BaseAppStateDurations other) {
        super(other);
    }

    /**
     * Add a start/stop event.
     */
    void addEvent(boolean start, @NonNull T event, int index) {
        if (mEvents[index] == null) {
            mEvents[index] = new LinkedList<>();
        }
        final LinkedList<T> events = mEvents[index];
        final int size = events.size();
        final boolean active = isActive(index);

        if (DEBUG_BASE_APP_STATE_DURATIONS && !start && !active) {
            Slog.wtf(mTag, "Under-counted start event");
            return;
        }
        if (start != active) {
            // Only record the event time if it's not the same state as now
            events.add(event);
        }
        trimEvents(getEarliest(event.getTimestamp()), index);
    }

    @Override
    void trimEvents(long earliest, int index) {
        trimEvents(earliest, mEvents[index]);
    }

    void trimEvents(long earliest, LinkedList<T> events) {
        if (events == null) {
            return;
        }
        while (events.size() > 1) {
            final T current = events.peek();
            if (current.getTimestamp() >= earliest) {
                return; // All we have are newer than the given timestamp.
            }
            // Check the timestamp of stop event.
            if (events.get(1).getTimestamp() > earliest) {
                // Trim the duration by moving the start time.
                events.get(0).trimTo(earliest);
                return;
            }
            // Discard the 1st duration as it's older than the given timestamp.
            events.pop();
            events.pop();
        }
        if (events.size() == 1) {
            // Trim the duration by moving the start time.
            events.get(0).trimTo(Math.max(earliest, events.peek().getTimestamp()));
        }
    }

    /**
     * Merge the two given duration table and return the result.
     */
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
        boolean actl = false, actr = false;
        for (long lts = l.getTimestamp(), rts = r.getTimestamp();
                lts != Long.MAX_VALUE || rts != Long.MAX_VALUE;) {
            final boolean actCur = actl || actr;
            final T earliest;
            if (lts == rts) {
                earliest = l;
                actl = !actl;
                actr = !actr;
                lts = itl.hasNext() ? (l = itl.next()).getTimestamp() : Long.MAX_VALUE;
                rts = itr.hasNext() ? (r = itr.next()).getTimestamp() : Long.MAX_VALUE;
            } else if (lts < rts) {
                earliest = l;
                actl = !actl;
                lts = itl.hasNext() ? (l = itl.next()).getTimestamp() : Long.MAX_VALUE;
            } else {
                earliest = r;
                actr = !actr;
                rts = itr.hasNext() ? (r = itr.next()).getTimestamp() : Long.MAX_VALUE;
            }
            if (actCur != (actl || actr)) {
                dest.add((T) earliest.clone());
            }
        }
        return dest;
    }

    /**
     * Subtract the other durations from the this duration table at given index
     */
    void subtract(BaseAppStateDurations otherDurations, int thisIndex, int otherIndex) {
        if (mEvents.length <= thisIndex || mEvents[thisIndex] == null
                || otherDurations.mEvents.length <= otherIndex
                || otherDurations.mEvents[otherIndex] == null) {
            if (DEBUG_BASE_APP_STATE_DURATIONS) {
                Slog.wtf(mTag, "Incompatible event table this=" + this + ", other=" + otherDurations
                        + ", thisIndex=" + thisIndex + ", otherIndex=" + otherIndex);
            }
            return;
        }
        mEvents[thisIndex] = subtract(mEvents[thisIndex], otherDurations.mEvents[otherIndex]);
    }

    /**
     * Subtract the other durations at given index from the this duration table at all indexes.
     */
    void subtract(BaseAppStateDurations otherDurations, int otherIndex) {
        if (otherDurations.mEvents.length <= otherIndex
                || otherDurations.mEvents[otherIndex] == null) {
            if (DEBUG_BASE_APP_STATE_DURATIONS) {
                Slog.wtf(mTag, "Incompatible event table this=" + this + ", other=" + otherDurations
                        + ", otherIndex=" + otherIndex);
            }
            return;
        }
        for (int i = 0; i < mEvents.length; i++) {
            if (mEvents[i] != null) {
                mEvents[i] = subtract(mEvents[i], otherDurations.mEvents[otherIndex]);
            }
        }
    }

    /**
     * Subtract the other durations from the given duration table and return the new one.
     */
    LinkedList<T> subtract(LinkedList<T> durations, LinkedList<T> otherDurations) {
        if (otherDurations == null || otherDurations.size() == 0
                || durations == null || durations.size() == 0) {
            return durations;
        }
        final Iterator<T> itl = durations.iterator();
        final Iterator<T> itr = otherDurations.iterator();
        T l = itl.next(), r = itr.next();
        LinkedList<T> dest = new LinkedList<>();
        boolean actl = false, actr = false;
        for (long lts = l.getTimestamp(), rts = r.getTimestamp();
                lts != Long.MAX_VALUE || rts != Long.MAX_VALUE;) {
            final boolean actCur = actl && !actr;
            final T earliest;
            if (lts == rts) {
                earliest = l;
                actl = !actl;
                actr = !actr;
                lts = itl.hasNext() ? (l = itl.next()).getTimestamp() : Long.MAX_VALUE;
                rts = itr.hasNext() ? (r = itr.next()).getTimestamp() : Long.MAX_VALUE;
            } else if (lts < rts) {
                earliest = l;
                actl = !actl;
                lts = itl.hasNext() ? (l = itl.next()).getTimestamp() : Long.MAX_VALUE;
            } else {
                earliest = r;
                actr = !actr;
                rts = itr.hasNext() ? (r = itr.next()).getTimestamp() : Long.MAX_VALUE;
            }
            if (actCur != (actl && !actr)) {
                dest.add((T) earliest.clone());
            }
        }
        return dest;
    }

    long getTotalDurations(long now, int index) {
        return getTotalDurationsSince(getEarliest(0), now, index);
    }

    long getTotalDurationsSince(long since, long now, int index) {
        final LinkedList<T> events = mEvents[index];
        if (events == null || events.size() == 0) {
            return 0L;
        }
        boolean active = true;
        long last = 0;
        long duration = 0;
        for (T event : events) {
            if (event.getTimestamp() < since || active) {
                last = event.getTimestamp();
            } else {
                duration += Math.max(0, event.getTimestamp() - Math.max(last, since));
            }
            active = !active;
        }
        if ((events.size() & 1) == 1) {
            duration += Math.max(0, now - Math.max(last, since));
        }
        return duration;
    }

    boolean isActive(int index) {
        return mEvents[index] != null && (mEvents[index].size() & 1) == 1;
    }

    @Override
    String formatEventSummary(long now, int index) {
        return TimeUtils.formatDuration(getTotalDurations(now, index));
    }

    @Override
    public String toString() {
        return mPackageName + "/" + UserHandle.formatUid(mUid)
                + " isActive[0]=" + isActive(0)
                + " totalDurations[0]=" + getTotalDurations(SystemClock.elapsedRealtime(), 0);
    }
}
