/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.midi;

import java.util.Iterator;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Store arbitrary timestamped events using a Long timestamp.
 * Only one Thread can write into the buffer.
 * And only one Thread can read from the buffer.
 */
public class EventScheduler {
    private static final long NANOS_PER_MILLI = 1000000;

    private final Object mLock = new Object();
    volatile private SortedMap<Long, FastEventQueue> mEventBuffer;
    private FastEventQueue mEventPool = null;
    private int mMaxPoolSize = 200;
    private boolean mClosed;

    public EventScheduler() {
        mEventBuffer = new TreeMap<Long, FastEventQueue>();
    }

    // If we keep at least one node in the list then it can be atomic
    // and non-blocking.
    private class FastEventQueue {
        // One thread takes from the beginning of the list.
        volatile SchedulableEvent mFirst;
        // A second thread returns events to the end of the list.
        volatile SchedulableEvent mLast;
        volatile long mEventsAdded;
        volatile long mEventsRemoved;

        FastEventQueue(SchedulableEvent event) {
            mFirst = event;
            mLast = mFirst;
            mEventsAdded = 1;
            mEventsRemoved = 0;
        }

        int size() {
            return (int)(mEventsAdded - mEventsRemoved);
        }

        /**
         * Do not call this unless there is more than one event
         * in the list.
         * @return first event in the list
         */
        public SchedulableEvent remove() {
            // Take first event.
            mEventsRemoved++;
            SchedulableEvent event = mFirst;
            mFirst = event.mNext;
            event.mNext = null;
            return event;
        }

        /**
         * @param event
         */
        public void add(SchedulableEvent event) {
            event.mNext = null;
            mLast.mNext = event;
            mLast = event;
            mEventsAdded++;
        }
    }

    /**
     * Base class for events that can be stored in the EventScheduler.
     */
    public static class SchedulableEvent {
        private long mTimestamp;
        volatile private SchedulableEvent mNext = null;

        /**
         * @param timestamp
         */
        public SchedulableEvent(long timestamp) {
            mTimestamp = timestamp;
        }

        /**
         * @return timestamp
         */
        public long getTimestamp() {
            return mTimestamp;
        }

        /**
         * The timestamp should not be modified when the event is in the
         * scheduling buffer.
         */
        public void setTimestamp(long timestamp) {
            mTimestamp = timestamp;
        }
    }

    /**
     * Get an event from the pool.
     * Always leave at least one event in the pool.
     * @return event or null
     */
    public SchedulableEvent removeEventfromPool() {
        SchedulableEvent event = null;
        if (mEventPool != null && (mEventPool.size() > 1)) {
            event = mEventPool.remove();
        }
        return event;
    }

    /**
     * Return events to a pool so they can be reused.
     *
     * @param event
     */
    public void addEventToPool(SchedulableEvent event) {
        if (mEventPool == null) {
            mEventPool = new FastEventQueue(event);
        // If we already have enough items in the pool then just
        // drop the event. This prevents unbounded memory leaks.
        } else if (mEventPool.size() < mMaxPoolSize) {
            mEventPool.add(event);
        }
    }

    /**
     * Add an event to the scheduler. Events with the same time will be
     * processed in order.
     *
     * @param event
     */
    public void add(SchedulableEvent event) {
        synchronized (mLock) {
            FastEventQueue list = mEventBuffer.get(event.getTimestamp());
            if (list == null) {
                long lowestTime = mEventBuffer.isEmpty() ? Long.MAX_VALUE
                        : mEventBuffer.firstKey();
                list = new FastEventQueue(event);
                mEventBuffer.put(event.getTimestamp(), list);
                // If the event we added is earlier than the previous earliest
                // event then notify any threads waiting for the next event.
                if (event.getTimestamp() < lowestTime) {
                    mLock.notify();
                }
            } else {
                list.add(event);
            }
        }
    }

    private SchedulableEvent removeNextEventLocked(long lowestTime) {
        SchedulableEvent event;
        FastEventQueue list = mEventBuffer.get(lowestTime);
        // Remove list from tree if this is the last node.
        if ((list.size() == 1)) {
            mEventBuffer.remove(lowestTime);
        }
        event = list.remove();
        return event;
    }

    /**
     * Check to see if any scheduled events are ready to be processed.
     *
     * @param timestamp
     * @return next event or null if none ready
     */
    public SchedulableEvent getNextEvent(long time) {
        SchedulableEvent event = null;
        synchronized (mLock) {
            if (!mEventBuffer.isEmpty()) {
                long lowestTime = mEventBuffer.firstKey();
                // Is it time for this list to be processed?
                if (lowestTime <= time) {
                    event = removeNextEventLocked(lowestTime);
                }
            }
        }
        // Log.i(TAG, "getNextEvent: event = " + event);
        return event;
    }

    /**
     * Return the next available event or wait until there is an event ready to
     * be processed. This method assumes that the timestamps are in nanoseconds
     * and that the current time is System.nanoTime().
     *
     * @return event
     * @throws InterruptedException
     */
    public SchedulableEvent waitNextEvent() throws InterruptedException {
        SchedulableEvent event = null;
        synchronized (mLock) {
            while (!mClosed) {
                long millisToWait = Integer.MAX_VALUE;
                if (!mEventBuffer.isEmpty()) {
                    long now = System.nanoTime();
                    long lowestTime = mEventBuffer.firstKey();
                    // Is it time for the earliest list to be processed?
                    if (lowestTime <= now) {
                        event = removeNextEventLocked(lowestTime);
                        break;
                    } else {
                        // Figure out how long to sleep until next event.
                        long nanosToWait = lowestTime - now;
                        // Add 1 millisecond so we don't wake up before it is
                        // ready.
                        millisToWait = 1 + (nanosToWait / NANOS_PER_MILLI);
                        // Clip 64-bit value to 32-bit max.
                        if (millisToWait > Integer.MAX_VALUE) {
                            millisToWait = Integer.MAX_VALUE;
                        }
                    }
                }
                mLock.wait((int) millisToWait);
            }
        }
        return event;
    }

    protected void flush() {
        // Replace our event buffer with a fresh empty one
        mEventBuffer = new TreeMap<Long, FastEventQueue>();
    }

    public void close() {
        synchronized (mLock) {
            mClosed = true;
            mLock.notify();
        }
    }
}
