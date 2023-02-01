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
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Iterator;
import java.util.LinkedList;

/**
 * Base class to track certain individual event of app states, it groups the events into time-based
 * slots, thus we could only track the total number of events in a slot, eliminating
 * the needs to track the timestamps for each individual event. This will be much more memory
 * efficient for the case of massive amount of events.
 */
class BaseAppStateTimeSlotEvents extends BaseAppStateEvents<Integer> {

    static final boolean DEBUG_BASE_APP_TIME_SLOT_EVENTS = false;

    /**
     * The size (in ms) of the timeslot, should be greater than 0 always.
     */
    final long mTimeSlotSize;

    /**
     * The start timestamp of current timeslot.
     */
    long[] mCurSlotStartTime;

    BaseAppStateTimeSlotEvents(int uid, @NonNull String packageName, int numOfEventTypes,
            long timeslotSize, @NonNull String tag,
            @NonNull MaxTrackingDurationConfig maxTrackingDurationConfig) {
        super(uid, packageName, numOfEventTypes, tag, maxTrackingDurationConfig);
        mTimeSlotSize = timeslotSize;
        mCurSlotStartTime = new long[numOfEventTypes];
    }

    BaseAppStateTimeSlotEvents(@NonNull BaseAppStateTimeSlotEvents other) {
        super(other);
        mTimeSlotSize = other.mTimeSlotSize;
        mCurSlotStartTime = new long[other.mCurSlotStartTime.length];
        for (int i = 0; i < mCurSlotStartTime.length; i++) {
            mCurSlotStartTime[i] = other.mCurSlotStartTime[i];
        }
    }

    @Override
    LinkedList<Integer> add(LinkedList<Integer> events, LinkedList<Integer> otherEvents) {
        if (DEBUG_BASE_APP_TIME_SLOT_EVENTS) {
            Slog.wtf(mTag, "Called into BaseAppStateTimeSlotEvents#add unexpected.");
        }
        // This function is invalid semantically here without the information of time-bases.
        return null;
    }

    @Override
    void add(BaseAppStateEvents otherObj) {
        if (otherObj == null || !(otherObj instanceof BaseAppStateTimeSlotEvents)) {
            return;
        }
        final BaseAppStateTimeSlotEvents other = (BaseAppStateTimeSlotEvents) otherObj;
        if (mEvents.length != other.mEvents.length) {
            if (DEBUG_BASE_APP_TIME_SLOT_EVENTS) {
                Slog.wtf(mTag, "Incompatible event table this=" + this + ", other=" + other);
            }
            return;
        }
        for (int i = 0; i < mEvents.length; i++) {
            final LinkedList<Integer> otherEvents = other.mEvents[i];
            if (otherEvents == null || otherEvents.size() == 0) {
                continue;
            }
            LinkedList<Integer> events = mEvents[i];
            if (events == null || events.size() == 0) {
                mEvents[i] = new LinkedList<Integer>(otherEvents);
                mCurSlotStartTime[i] = other.mCurSlotStartTime[i];
                continue;
            }

            final LinkedList<Integer> dest = new LinkedList<>();
            final Iterator<Integer> itl = events.iterator();
            final Iterator<Integer> itr = otherEvents.iterator();
            final long maxl = mCurSlotStartTime[i];
            final long maxr = other.mCurSlotStartTime[i];
            final long minl = maxl - mTimeSlotSize * (events.size() - 1);
            final long minr = maxr - mTimeSlotSize * (otherEvents.size() - 1);
            final long latest = Math.max(maxl, maxr);
            final long earliest = Math.min(minl, minr);
            for (long start = earliest; start <= latest; start += mTimeSlotSize) {
                dest.add((start >= minl && start <= maxl ? itl.next() : 0)
                        + (start >= minr && start <= maxr ? itr.next() : 0));
            }
            mEvents[i] = dest;
            if (maxl < maxr) {
                mCurSlotStartTime[i] = other.mCurSlotStartTime[i];
            }
            trimEvents(getEarliest(mCurSlotStartTime[i]), i);
        }
    }

    @Override
    int getTotalEventsSince(long since, long now, int index) {
        final LinkedList<Integer> events = mEvents[index];
        if (events == null || events.size() == 0) {
            return 0;
        }
        final long start = getSlotStartTime(since);
        if (start > mCurSlotStartTime[index]) {
            return 0;
        }
        final long end = Math.min(getSlotStartTime(now), mCurSlotStartTime[index]);
        final Iterator<Integer> it = events.descendingIterator();
        int count = 0;
        for (long time = mCurSlotStartTime[index]; time >= start && it.hasNext();
                time -= mTimeSlotSize) {
            final int val = it.next();
            if (time <= end) {
                count += val;
            }
        }
        return count;
    }

    void addEvent(long now, int index) {
        final long slot = getSlotStartTime(now);
        if (DEBUG_BASE_APP_TIME_SLOT_EVENTS) {
            Slog.i(mTag, "Adding event to slot " + slot);
        }
        LinkedList<Integer> events = mEvents[index];
        if (events == null) {
            events = new LinkedList<Integer>();
            mEvents[index] = events;
        }
        if (events.size() == 0) {
            events.add(1);
        } else {
            for (long start = mCurSlotStartTime[index]; start < slot; start += mTimeSlotSize) {
                events.add(0);
            }
            events.offerLast(events.pollLast() + 1);
        }
        mCurSlotStartTime[index] = slot;
        trimEvents(getEarliest(now), index);
    }

    @Override
    void trimEvents(long earliest, int index) {
        final LinkedList<Integer> events = mEvents[index];
        if (events == null || events.size() == 0) {
            return;
        }
        final long slot = getSlotStartTime(earliest);
        for (long time = mCurSlotStartTime[index] - mTimeSlotSize * (events.size() - 1);
                time < slot && events.size() > 0; time += mTimeSlotSize) {
            events.pop();
        }
    }

    long getSlotStartTime(long timestamp) {
        return timestamp - timestamp % mTimeSlotSize;
    }

    @VisibleForTesting
    long getCurrentSlotStartTime(int index) {
        return mCurSlotStartTime[index];
    }
}
