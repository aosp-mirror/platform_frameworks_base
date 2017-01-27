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
package com.android.internal.logging.legacy;

import android.util.ArrayMap;
import android.util.EventLog;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Scan the event log for interaction metrics events.
 * @hide
 */
public class EventLogCollector {
    private static final String TAG = "EventLogCollector";

    // TODO replace this with GoogleLogTags.TRON_HEARTBEAT
    @VisibleForTesting
    static final int TRON_HEARTBEAT = 208000;

    private static EventLogCollector sInstance;

    private final ArrayMap<Integer, TagParser> mTagParsers;
    private int[] mInterestingTags;

    private LogReader mLogReader;

    private EventLogCollector() {
        mTagParsers = new ArrayMap<>();
        addParser(new LockscreenGestureParser());
        addParser(new StatusBarStateParser());
        addParser(new PowerScreenStateParser());
        addParser(new SysuiMultiActionParser());

        mLogReader = new LogReader();
    }

    public static EventLogCollector getInstance() {
        if (sInstance == null) {
            sInstance = new EventLogCollector();
        }
        return sInstance;
    }

    @VisibleForTesting
    public void setLogReader(LogReader logReader) {
        mLogReader = logReader;
    }

    private int[] getInterestingTags() {
        if (mInterestingTags == null) {
            mInterestingTags = new int[mTagParsers.size()];
            for (int i = 0; i < mTagParsers.size(); i++) {
                mInterestingTags[i] = mTagParsers.valueAt(i).getTag();
            }
        }
        return mInterestingTags;
    }

    // I would customize ArrayMap to add put(TagParser), but ArrayMap is final.
    @VisibleForTesting
    void addParser(TagParser parser) {
        mTagParsers.put(parser.getTag(), parser);
        mInterestingTags = null;
    }

    public void collect(LegacyConversionLogger logger) {
        collect(logger, 0L);
    }

    public long collect(TronLogger logger, long lastSeenEventMs) {
        long lastEventMs = 0L;
        final boolean debug = Util.debug();

        if (debug) {
            Log.d(TAG, "Eventlog Collection");
        }
        ArrayList<Event> events = new ArrayList<>();
        try {
            mLogReader.readEvents(getInterestingTags(), events);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (debug) {
            Log.d(TAG, "read this many events: " + events.size());
        }

        for (Event event : events) {
            final long millis = event.getTimeNanos() / 1000000;
            if (millis > lastSeenEventMs) {
                final int tag = event.getTag();
                TagParser parser = mTagParsers.get(tag);
                if (parser == null) {
                    if (debug) {
                        Log.d(TAG, "unknown tag: " + tag);
                    }
                    continue;
                }
                if (debug) {
                    Log.d(TAG, "parsing tag: " + tag);
                }
                parser.parseEvent(logger, event);
                lastEventMs = Math.max(lastEventMs, millis);
            } else {
                if (debug) {
                    Log.d(TAG, "old event: " + millis + " < " + lastSeenEventMs);
                }
            }
        }
        return lastEventMs;
    }

    @VisibleForTesting
    static class Event {
        long mTimeNanos;
        int mTag;
        Object mData;

        Event(long timeNanos, int tag, Object data) {
            super();
            mTimeNanos = timeNanos;
            mTag = tag;
            mData = data;
        }

        Event(EventLog.Event event) {
            mTimeNanos = event.getTimeNanos();
            mTag = event.getTag();
            mData = event.getData();
        }

        public long getTimeNanos() {
            return mTimeNanos;
        }

        public int getTag() {
            return mTag;
        }

        public Object getData() {
            return mData;
        }
    }

    @VisibleForTesting
    static class LogReader {
        public void readEvents(int[] tags, Collection<Event> events) throws IOException {
            // Testing in Android: the Static Final Class Strikes Back!
            ArrayList<EventLog.Event> nativeEvents = new ArrayList<>();
            EventLog.readEventsOnWrapping(tags, 0L, nativeEvents);
            for (EventLog.Event nativeEvent : nativeEvents) {
                Event event = new Event(nativeEvent);
                events.add(event);
            }
        }
    }
}
