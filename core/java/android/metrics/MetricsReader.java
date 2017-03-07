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
import android.util.EventLog;
import android.util.EventLog.Event;
import android.util.Log;

import com.android.internal.logging.MetricsLogger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Read platform logs.
 * @hide
 */
@SystemApi
public class MetricsReader {
    private Queue<LogMaker> mEventQueue = new LinkedList<>();
    private long mLastEventMs;
    private long mCheckpointMs;
    private int[] LOGTAGS = { MetricsLogger.LOGTAG };

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
            EventLog.readEventsOnWrapping(LOGTAGS, horizonMs, nativeEvents);
        } catch (IOException e) {
            e.printStackTrace();
        }
        mEventQueue.clear();
        for (EventLog.Event event : nativeEvents) {
            final long eventTimestampMs = event.getTimeNanos() / 1000000;
            if (eventTimestampMs > mCheckpointMs) {
                Object data = event.getData();
                Object[] objects;
                if (data instanceof Object[]) {
                    objects = (Object[]) data;
                } else {
                    // wrap scalar objects
                    objects = new Object[1];
                    objects[0] = data;
                }
                mEventQueue.add(new LogMaker(objects)
                        .setTimestamp(eventTimestampMs));
                mLastEventMs = eventTimestampMs;
            }
        }
    }

    /** Cause this session to only contain events that occur after this call. */
    public void checkpoint() {
        // read the log to find the most recent event.
        read(0L);
        // any queued event is now too old, so drop them.
        mEventQueue.clear();
        mCheckpointMs = mLastEventMs;
    }

    /**
     * Rewind the session to the beginning of time and read all available logs.
     *
     * A prior call to {@link #checkpoint()} will cause the reader to ignore
     * any event with a timestamp before the time of that call.
     *
     * The underlying log buffer is live: between calls to {@link #reset()}, older
     * events may be lost from the beginning of the session, and new events may
     * appear at the end.
     */
    public void reset() {
        read(0l);
    }

    /* Does the current log session have another entry? */
    public boolean hasNext() {
        return !mEventQueue.isEmpty();
    }

    /* Return the next entry in the current log session. */
    public LogMaker next() {
        return mEventQueue.poll();
    }

}
