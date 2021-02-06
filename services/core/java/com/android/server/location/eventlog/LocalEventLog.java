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

import android.os.SystemClock;
import android.util.TimeUtils;

import com.android.internal.util.Preconditions;

import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

/**
 * An in-memory event log to support historical event information.
 */
public abstract class LocalEventLog {

    private interface Log {
        // true if this is a filler element that should not be queried
        boolean isFiller();
        long getTimeDeltaMs();
        String getLogString();
    }

    private static final class FillerEvent implements Log {

        static final long MAX_TIME_DELTA = (1L << 32) - 1;

        private final int mTimeDelta;

        FillerEvent(long timeDelta) {
            Preconditions.checkArgument(timeDelta >= 0);
            mTimeDelta = (int) timeDelta;
        }

        @Override
        public boolean isFiller() {
            return true;
        }

        @Override
        public long getTimeDeltaMs() {
            return Integer.toUnsignedLong(mTimeDelta);
        }

        @Override
        public String getLogString() {
            throw new AssertionError();
        }
    }

    /**
     * An abstraction of a log event to be implemented by subclasses.
     */
    public abstract static class LogEvent implements Log {

        static final long MAX_TIME_DELTA = (1L << 32) - 1;

        private final int mTimeDelta;

        protected LogEvent(long timeDelta) {
            Preconditions.checkArgument(timeDelta >= 0);
            mTimeDelta = (int) timeDelta;
        }

        @Override
        public final boolean isFiller() {
            return false;
        }

        @Override
        public final long getTimeDeltaMs() {
            return Integer.toUnsignedLong(mTimeDelta);
        }
    }

    // circular buffer of log entries
    private final Log[] mLog;
    private int mLogSize;
    private int mLogEndIndex;

    // invalid if log is empty
    private long mStartRealtimeMs;
    private long mLastLogRealtimeMs;

    public LocalEventLog(int size) {
        Preconditions.checkArgument(size > 0);
        mLog = new Log[size];
        mLogSize = 0;
        mLogEndIndex = 0;

        mStartRealtimeMs = -1;
        mLastLogRealtimeMs = -1;
    }

    /**
     * Should be overridden by subclasses to return a new immutable log event for the given
     * arguments (as passed into {@link #addLogEvent(int, Object...)}.
     */
    protected abstract LogEvent createLogEvent(long timeDelta, int event, Object... args);

    /**
     * May be optionally overridden by subclasses if they wish to change how log event time is
     * formatted.
     */
    protected String getTimePrefix(long timeMs) {
        return TimeUtils.logTimeOfDay(timeMs) + ": ";
    }

    /**
     * Call to add a new log event at the current time. The arguments provided here will be passed
     * into {@link #createLogEvent(long, int, Object...)} in addition to a time delta, and should be
     * used to construct an appropriate {@link LogEvent} object.
     */
    public synchronized void addLogEvent(int event, Object... args) {
        long timeMs = SystemClock.elapsedRealtime();

        // calculate delta
        long delta = 0;
        if (!isEmpty()) {
            delta = timeMs - mLastLogRealtimeMs;

            // if the delta is invalid, or if the delta is great enough using filler elements would
            // result in an empty log anyways, just clear the log and continue, otherwise insert
            // filler elements until we have a reasonable delta
            if (delta < 0 || (delta / FillerEvent.MAX_TIME_DELTA) >= mLog.length - 1) {
                clear();
                delta = 0;
            } else {
                while (delta >= LogEvent.MAX_TIME_DELTA) {
                    long timeDelta = Math.min(FillerEvent.MAX_TIME_DELTA, delta);
                    addLogEventInternal(new FillerEvent(timeDelta));
                    delta -= timeDelta;
                }
            }
        }

        // for first log entry, set initial times
        if (isEmpty()) {
            mStartRealtimeMs = timeMs;
            mLastLogRealtimeMs = mStartRealtimeMs;
        }

        addLogEventInternal(createLogEvent(delta, event, args));
    }

    private void addLogEventInternal(Log event) {
        Preconditions.checkState(mStartRealtimeMs != -1 && mLastLogRealtimeMs != -1);

        if (mLogSize == mLog.length) {
            // if log is full, size will remain the same, but update the start time
            mStartRealtimeMs += mLog[startIndex()].getTimeDeltaMs();
        } else {
            // otherwise add an item
            mLogSize++;
        }

        // set log and increment end index
        mLog[mLogEndIndex] = event;
        mLogEndIndex = incrementIndex(mLogEndIndex);
        mLastLogRealtimeMs = mLastLogRealtimeMs + event.getTimeDeltaMs();
    }

    /** Clears the log of all entries. */
    public synchronized void clear() {
        mLogEndIndex = 0;
        mLogSize = 0;

        mStartRealtimeMs = -1;
        mLastLogRealtimeMs = -1;
    }

    // checks if the log is empty (if empty, times are invalid)
    private synchronized boolean isEmpty() {
        return mLogSize == 0;
    }

    /** Iterates over the event log, passing each log string to the given consumer. */
    public synchronized void iterate(Consumer<String> consumer) {
        LogIterator it = new LogIterator();
        while (it.hasNext()) {
            consumer.accept(it.next());
        }
    }

    // returns the index of the first element
    private int startIndex() {
        return wrapIndex(mLogEndIndex - mLogSize);
    }

    // returns the index after this one
    private int incrementIndex(int index) {
        return wrapIndex(index + 1);
    }

    // returns the index before this one
    private int decrementIndex(int index) {
        return wrapIndex(index - 1);
    }

    // rolls over the given index if necessary
    private int wrapIndex(int index) {
        // java modulo will keep negative sign, we need to rollover
        return (index % mLog.length + mLog.length) % mLog.length;
    }

    private class LogIterator implements ListIterator<String> {

        private final long mSystemTimeDeltaMs;

        private long mCurrentRealtimeMs;
        private int mIndex;
        private int mCount;

        LogIterator() {
            mSystemTimeDeltaMs = System.currentTimeMillis() - SystemClock.elapsedRealtime();
            mCurrentRealtimeMs = mStartRealtimeMs;
            mIndex = startIndex();
            mCount = 0;
        }

        @Override
        public boolean hasNext() {
            return mCount < mLogSize;
        }

        @Override
        public boolean hasPrevious() {
            return mCount > 0;
        }

        @Override
        // return then increment
        public String next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            Log log = mLog[mIndex];
            long nextDeltaMs = log.getTimeDeltaMs();
            long realtimeMs = mCurrentRealtimeMs + nextDeltaMs;

            // calculate next index, skipping filler events
            do {
                mCurrentRealtimeMs += nextDeltaMs;
                mIndex = incrementIndex(mIndex);
                if (++mCount < mLogSize) {
                    nextDeltaMs = mLog[mIndex].getTimeDeltaMs();
                }
            } while (mCount < mLogSize && mLog[mIndex].isFiller());

            return getTimePrefix(realtimeMs + mSystemTimeDeltaMs) + log.getLogString();
        }

        @Override
        // decrement then return
        public String previous() {
            Log log;
            long currentDeltaMs;
            long realtimeMs;

            // calculate previous index, skipping filler events with MAX_TIME_DELTA
            do {
                if (!hasPrevious()) {
                    throw new NoSuchElementException();
                }

                mIndex = decrementIndex(mIndex);
                mCount--;

                log = mLog[mIndex];
                realtimeMs = mCurrentRealtimeMs;

                if (mCount > 0) {
                    currentDeltaMs = log.getTimeDeltaMs();
                    mCurrentRealtimeMs -= currentDeltaMs;
                }
            } while (mCount >= 0 && log.isFiller());

            return getTimePrefix(realtimeMs + mSystemTimeDeltaMs) + log.getLogString();
        }

        @Override
        public int nextIndex() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int previousIndex() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void add(String s) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void set(String s) {
            throw new UnsupportedOperationException();
        }
    }
}

