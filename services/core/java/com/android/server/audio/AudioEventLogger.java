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

package com.android.server.audio;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;

public class AudioEventLogger {

    // ring buffer of events to log.
    private final LinkedList<Event> mEvents;

    private final String mTitle;

    // the maximum number of events to keep in log
    private final int mMemSize;

    public static abstract class Event {
        // formatter for timestamps
        private final static SimpleDateFormat sFormat = new SimpleDateFormat("MM-dd HH:mm:ss:SSS");

        private final long mTimestamp;

        Event() {
            mTimestamp = System.currentTimeMillis();
        }

        public String toString() {
            return (new StringBuilder(sFormat.format(new Date(mTimestamp))))
                    .append(" ").append(eventToString()).toString();
        }

        /**
         * Convert event to String.
         * This method is only called when the logger history is about to the dumped,
         * so this method is where expensive String conversions should be made, not when the Event
         * subclass is created.
         * Timestamp information will be automatically added, do not include it.
         * @return a string representation of the event that occurred.
         */
        abstract public String eventToString();
    }

    public static class StringEvent extends Event {
        private final String mMsg;

        public StringEvent(String msg) {
            mMsg = msg;
        }

        @Override
        public String eventToString() {
            return mMsg;
        }
    }

    /**
     * Constructor for logger.
     * @param size the maximum number of events to keep in log
     * @param title the string displayed before the recorded log
     */
    public AudioEventLogger(int size, String title) {
        mEvents = new LinkedList<Event>();
        mMemSize = size;
        mTitle = title;
    }

    public synchronized void log(Event evt) {
        if (mEvents.size() >= mMemSize) {
            mEvents.removeFirst();
        }
        mEvents.add(evt);
    }

    public synchronized void dump(PrintWriter pw) {
        pw.println("Audio event log: " + mTitle);
        for (Event evt : mEvents) {
            pw.println(evt.toString());
        }
    }
}
