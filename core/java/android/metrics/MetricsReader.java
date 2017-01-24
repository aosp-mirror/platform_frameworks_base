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

import com.android.internal.logging.legacy.LegacyConversionLogger;
import com.android.internal.logging.legacy.EventLogCollector;

import java.util.Queue;

/**
 * Read platform logs.
 * @hide
 */
@SystemApi
public class MetricsReader {
    private EventLogCollector mReader;
    private Queue<LogMaker> mEventQueue;
    private long mLastEventMs;
    private long mCheckpointMs;

    /** Open a new session and start reading logs.
     *
     * Starts reading from the oldest log not already read by this reader object.
     * On first invocation starts from the oldest available log ion the system.
     */
    public void read(long startMs) {
        EventLogCollector reader = EventLogCollector.getInstance();
        LegacyConversionLogger logger = new LegacyConversionLogger();
        mLastEventMs = reader.collect(logger, startMs);
        mEventQueue = logger.getEvents();
    }

    public void checkpoint() {
        read(0L);
        mCheckpointMs = mLastEventMs;
        mEventQueue = null;
    }

    public void reset() {
        read(mCheckpointMs);
    }

    /* Does the current log session have another entry? */
    public boolean hasNext() {
        return mEventQueue == null ? false : !mEventQueue.isEmpty();
    }

    /* Next entry in the current log session. */
    public LogMaker next() {
        return mEventQueue == null ? null : mEventQueue.remove();
    }

}
