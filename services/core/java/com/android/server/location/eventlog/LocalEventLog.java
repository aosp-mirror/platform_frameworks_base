/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.location.eventlog;

import static java.lang.Integer.bitCount;

import android.annotation.Nullable;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * An in-memory event log to support historical event information. The log is of a constant size,
 * and new events will overwrite old events as the log fills up.
 *
 * @param <T> log event type
 */
public class LocalEventLog<T> {

    /**
     * Consumer of log events for iterating over the log.
     *
     * @param <T> log event type
     */
    public interface LogConsumer<T> {
        /** Invoked with a time and a logEvent. */
        void acceptLog(long time, T logEvent);
    }

    // masks for the entries field. 1 bit is used to indicate whether this is a filler event or not,
    // and 31 bits to store the time delta.
    private static final int IS_FILLER_MASK = 0b10000000000000000000000000000000;
    private static final int TIME_DELTA_MASK = 0b01111111111111111111111111111111;

    private static final int IS_FILLER_OFFSET = countTrailingZeros(IS_FILLER_MASK);
    private static final int TIME_DELTA_OFFSET = countTrailingZeros(TIME_DELTA_MASK);

    static final int MAX_TIME_DELTA = (1 << bitCount(TIME_DELTA_MASK)) - 1;

    private static int countTrailingZeros(int i) {
        int c = 0;
        while (i != 0 && (i & 1) == 0) {
            c++;
            i = i >>> 1;
        }
        return c;
    }

    private static int createEntry(boolean isFiller, int timeDelta) {
        Preconditions.checkArgument(timeDelta >= 0 && timeDelta <= MAX_TIME_DELTA);
        return (((isFiller ? 1 : 0) << IS_FILLER_OFFSET) & IS_FILLER_MASK)
                | ((timeDelta << TIME_DELTA_OFFSET) & TIME_DELTA_MASK);
    }

    static int getTimeDelta(int entry) {
        return (entry & TIME_DELTA_MASK) >>> TIME_DELTA_OFFSET;
    }

    static boolean isFiller(int entry) {
        return (entry & IS_FILLER_MASK) != 0;
    }

    // circular buffer of log entries and events. each entry corrosponds to the log event at the
    // same index. the log entry holds the filler status and time delta according to the bit masks
    // above, and the log event is the log event.

    @GuardedBy("this")
    final int[] mEntries;

    @GuardedBy("this")
    final @Nullable T[] mLogEvents;

    @GuardedBy("this")
    int mLogSize;

    @GuardedBy("this")
    int mLogEndIndex;

    // invalid if log is empty

    @GuardedBy("this")
    long mStartTime;

    @GuardedBy("this")
    long mLastLogTime;

    @SuppressWarnings("unchecked")
    public LocalEventLog(int size, Class<T> clazz) {
        Preconditions.checkArgument(size > 0);

        mEntries = new int[size];
        mLogEvents = (T[]) Array.newInstance(clazz, size);
        mLogSize = 0;
        mLogEndIndex = 0;

        mStartTime = -1;
        mLastLogTime = -1;
    }

    /** Call to add a new log event at the given time. */
    protected synchronized void addLog(long time, T logEvent) {
        Preconditions.checkArgument(logEvent != null);

        // calculate delta
        long delta = 0;
        if (!isEmpty()) {
            delta = time - mLastLogTime;

            // if the delta is negative, or if the delta is great enough using filler elements would
            // result in an empty log anyways, just clear the log and continue, otherwise insert
            // filler elements until we have a reasonable delta
            if (delta < 0 || (delta / MAX_TIME_DELTA) >= mEntries.length - 1) {
                clear();
                delta = 0;
            } else {
                while (delta >= MAX_TIME_DELTA) {
                    addLogEventInternal(true, MAX_TIME_DELTA, null);
                    delta -= MAX_TIME_DELTA;
                }
            }
        }

        // for first log entry, set initial times
        if (isEmpty()) {
            mStartTime = time;
            mLastLogTime = mStartTime;
        }

        addLogEventInternal(false, (int) delta, logEvent);
    }

    @GuardedBy("this")
    private void addLogEventInternal(boolean isFiller, int timeDelta, @Nullable T logEvent) {
        Preconditions.checkArgument(isFiller || logEvent != null);
        Preconditions.checkState(mStartTime != -1 && mLastLogTime != -1);

        if (mLogSize == mEntries.length) {
            // if log is full, size will remain the same, but update the start time
            mStartTime += getTimeDelta(mEntries[startIndex()]);
        } else {
            // otherwise add an item
            mLogSize++;
        }

        // set log and increment end index
        mEntries[mLogEndIndex] = createEntry(isFiller, timeDelta);
        mLogEvents[mLogEndIndex] = logEvent;
        mLogEndIndex = incrementIndex(mLogEndIndex);
        mLastLogTime = mLastLogTime + timeDelta;
    }

    /** Clears the log of all entries. */
    public synchronized void clear() {
        // clear entries to allow gc
        Arrays.fill(mLogEvents, null);

        mLogEndIndex = 0;
        mLogSize = 0;

        mStartTime = -1;
        mLastLogTime = -1;
    }

    // checks if the log is empty (if empty, times are invalid)
    @GuardedBy("this")
    private boolean isEmpty() {
        return mLogSize == 0;
    }

    /** Iterates over the event log, passing each log string to the given consumer. */
    public synchronized void iterate(LogConsumer<? super T> consumer) {
        LogIterator it = new LogIterator();
        while (it.hasNext()) {
            it.next();
            consumer.acceptLog(it.getTime(), it.getLog());
        }
    }

    // returns the index of the first element
    @GuardedBy("this")
    private int startIndex() {
        return wrapIndex(mLogEndIndex - mLogSize);
    }

    // returns the index after this one
    @GuardedBy("this")
    private int incrementIndex(int index) {
        if (index == -1) {
            return startIndex();
        } else if (index >= 0) {
            return wrapIndex(index + 1);
        } else {
            throw new IllegalArgumentException();
        }
    }

    // rolls over the given index if necessary
    @GuardedBy("this")
    private int wrapIndex(int index) {
        // java modulo will keep negative sign, we need to rollover
        return (index % mEntries.length + mEntries.length) % mEntries.length;
    }

    private class LogIterator {

        private long mLogTime;
        private int mIndex;
        private int mCount;

        private long mCurrentTime;
        private T mCurrentLogEvent;

        LogIterator() {
            synchronized (LocalEventLog.this) {
                mLogTime = mStartTime;
                mIndex = -1;
                mCount = -1;

                increment();
            }
        }

        public boolean hasNext() {
            synchronized (LocalEventLog.this) {
                return mCount < mLogSize;
            }
        }

        public void next() {
            synchronized (LocalEventLog.this) {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                mCurrentTime = mLogTime + getTimeDelta(mEntries[mIndex]);
                mCurrentLogEvent = Objects.requireNonNull(mLogEvents[mIndex]);

                increment();
            }
        }

        public long getTime() {
            return mCurrentTime;
        }

        public T getLog() {
            return mCurrentLogEvent;
        }

        @GuardedBy("LocalEventLog.this")
        private void increment(LogIterator this) {
            long nextDeltaMs = mIndex == -1 ? 0 : getTimeDelta(mEntries[mIndex]);
            do {
                mLogTime += nextDeltaMs;
                mIndex = incrementIndex(mIndex);
                if (++mCount < mLogSize) {
                    nextDeltaMs = getTimeDelta(mEntries[mIndex]);
                }
            } while (mCount < mLogSize && isFiller(mEntries[mIndex]));
        }
    }
}
