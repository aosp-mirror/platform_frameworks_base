/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.metrics;

import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.util.EventLog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

/**
 * Read platform logs.
 *
 * @hide
 */
@SystemApi
@TestApi
public class MetricsReader {
    private Queue<LogMaker> mPendingQueue = new LinkedList<>();
    private Queue<LogMaker> mSeenQueue = new LinkedList<>();
    private int[] LOGTAGS = {MetricsLogger.LOGTAG};

    private LogReader mReader = new LogReader();
    private int mCheckpointTag = -1;

    /**
     * Set the reader to isolate unit tests from the framework
     *
     * @hide
     */
    @VisibleForTesting
    public void setLogReader(LogReader reader) {
        mReader = reader;
    }

    /**
     * Read the available logs into a new session.
     *
     * The session will contain events starting from the oldest available
     * log on the system up to the most recent at the time of this call.
     *
     * A call to {@link #checkpoint()} will cause the session to contain
     * only events that occured after that call.
     *
     * This call will not return until the system buffer overflows the
     * specified timestamp. If the specified timestamp is 0, then the
     * call will return immediately since any logs 1970 have already been
     * overwritten (n.b. if the underlying system has the capability to
     * store many decades of system logs, this call may fail in
     * interesting ways.)
     *
     * @param horizonMs block until this timestamp is overwritten, 0 for non-blocking read.
     */
    public void read(long horizonMs) {
        ArrayList<Event> nativeEvents = new ArrayList<>();
        try {
            mReader.readEvents(LOGTAGS, horizonMs, nativeEvents);
        } catch (IOException e) {
            e.printStackTrace();
        }
        mPendingQueue.clear();
        mSeenQueue.clear();
        for (Event event : nativeEvents) {
            final long eventTimestampMs = event.getTimeMillis();
            Object data = event.getData();
            Object[] objects;
            if (data instanceof Object[]) {
                objects = (Object[]) data;
            } else {
                // wrap scalar objects
                objects = new Object[1];
                objects[0] = data;
            }
            final LogMaker log = new LogMaker(objects)
                    .setTimestamp(eventTimestampMs)
                    .setUid(event.getUid())
                    .setProcessId(event.getProcessId());
            if (log.getCategory() == MetricsEvent.METRICS_CHECKPOINT) {
                if (log.getSubtype() == mCheckpointTag) {
                    mPendingQueue.clear();
                }
            } else {
                mPendingQueue.offer(log);
            }
        }
    }

    /**
     * Empties the session and causes the next {@link #read(long)} to
     * yeild a session containing only events that occur after this call.
     */
    public void checkpoint() {
        // write a checkpoint into the log stream
        mCheckpointTag = (int) (System.currentTimeMillis() % 0x7fffffff);
        mReader.writeCheckpoint(mCheckpointTag);
        // any queued event is now too old, so drop them.
        mPendingQueue.clear();
        mSeenQueue.clear();
    }

    /**
     * Rewind the session to the beginning of time and replay all available logs.
     */
    public void reset() {
        // flush the rest of hte pending events
        mSeenQueue.addAll(mPendingQueue);
        mPendingQueue.clear();
        mCheckpointTag = -1;

        // swap queues
        Queue<LogMaker> tmp = mPendingQueue;
        mPendingQueue = mSeenQueue;
        mSeenQueue = tmp;
    }

    /* Does the current log session have another entry? */
    public boolean hasNext() {
        return !mPendingQueue.isEmpty();
    }

    /* Return the next entry in the current log session. */
    public LogMaker next() {
        final LogMaker next = mPendingQueue.poll();
        if (next != null) {
            mSeenQueue.offer(next);
        }
        return next;
    }

    /**
     * Wrapper for the Event object, to facilitate testing.
     *
     * @hide
     */
    @VisibleForTesting
    public static class Event {
        long mTimeMillis;
        int mPid;
        int mUid;
        Object mData;

        public Event(long timeMillis, int pid, int uid, Object data) {
            mTimeMillis = timeMillis;
            mPid = pid;
            mUid = uid;
            mData = data;
        }

        Event(EventLog.Event nativeEvent) {
            mTimeMillis = TimeUnit.MILLISECONDS.convert(
                    nativeEvent.getTimeNanos(), TimeUnit.NANOSECONDS);
            mPid = nativeEvent.getProcessId();
            mUid = nativeEvent.getUid();
            mData = nativeEvent.getData();
        }

        public long getTimeMillis() {
            return mTimeMillis;
        }

        public int getProcessId() {
            return mPid;
        }

        public int getUid() {
            return mUid;
        }

        public Object getData() {
            return mData;
        }

        public void setData(Object data) {
            mData = data;
        }
    }

    /**
     * Wrapper for the Event reader, to facilitate testing.
     *
     * @hide
     */
    @VisibleForTesting
    public static class LogReader {
        public void readEvents(int[] tags, long horizonMs, Collection<Event> events)
                throws IOException {
            // Testing in Android: the Static Final Class Strikes Back!
            ArrayList<EventLog.Event> nativeEvents = new ArrayList<>();
            long horizonNs = TimeUnit.NANOSECONDS.convert(horizonMs, TimeUnit.MILLISECONDS);
            EventLog.readEventsOnWrapping(tags, horizonNs, nativeEvents);
            for (EventLog.Event nativeEvent : nativeEvents) {
                Event event = new Event(nativeEvent);
                events.add(event);
            }
        }

        public void writeCheckpoint(int tag) {
            MetricsLogger logger = new MetricsLogger();
            logger.action(MetricsEvent.METRICS_CHECKPOINT, tag);
        }
    }
}
