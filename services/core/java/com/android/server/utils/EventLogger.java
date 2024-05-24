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

package com.android.server.utils;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;
import android.util.Slog;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Logs human-readable events for debugging purposes.
 */
public class EventLogger {

    /** Prefix for the title added at the beginning of a {@link #dump(PrintWriter)} operation */
    private static final String DUMP_TITLE_PREFIX = "Events log: ";

    /** Identifies the source of events. */
    @Nullable private final String mTag;

    /** Stores the events using a ring buffer. */
    private final ArrayDeque<Event> mEvents;

    /**
     * The maximum number of events to keep in {@code mEvents}.
     *
     * <p>Calling {@link #enqueue} when the size of {@link #mEvents} matches the threshold will
     * cause the oldest event to be evicted.
     */
    private final int mMemSize;

    /**
     * Constructor for logger.
     * @param size the maximum number of events to keep in log
     * @param tag the string displayed before the recorded log
     */
    public EventLogger(int size, @Nullable String tag) {
        mEvents = new ArrayDeque<>(size);
        mMemSize = size;
        mTag = tag;
    }

    /** Enqueues {@code event} to be logged. */
    public synchronized void enqueue(Event event) {
        if (mEvents.size() >= mMemSize) {
            mEvents.removeFirst();
        }

        mEvents.addLast(event);
    }

    /**
     * Add a string-based event to the log, and print it to logcat with a specific severity.
     * @param msg the message for the logs
     * @param logType the type of logcat entry
     * @param tag the logcat tag to use
     */
    public synchronized void enqueueAndLog(String msg, @Event.LogType int logType, String tag) {
        final Event event = new StringEvent(msg);
        enqueue(event.printLog(logType, tag));
    }

    /**
     * Add a string-based event to the system log, and print it to the log with a specific severity.
     * @param msg the message to appear in the log
     * @param logType the log severity (verbose/info/warning/error)
     * @param tag the tag under which the log entry will appear
     */
    public synchronized void enqueueAndSlog(String msg, @Event.LogType int logType, String tag) {
        final Event event = new StringEvent(msg);
        enqueue(event.printSlog(logType, tag));
    }

    /** Dumps events into the given {@link DumpSink}. */
    public synchronized void dump(DumpSink dumpSink) {
        dumpSink.sink(mTag, new ArrayList<>(mEvents));
    }

    /** Dumps events using {@link PrintWriter}. */
    public synchronized void dump(PrintWriter pw) {
        dump(pw, "" /* prefix */);
    }

    protected String getDumpTitle() {
        if (mTag == null) {
            return DUMP_TITLE_PREFIX;
        }
        return DUMP_TITLE_PREFIX + mTag;
    }

    /** Dumps events using {@link PrintWriter} with a certain indent. */
    public synchronized void dump(PrintWriter pw, String indent) {
        pw.println(getDumpTitle());

        for (Event evt : mEvents) {
            pw.println(indent + evt.toString());
        }
    }

    /** Receives events from {@link EventLogger} upon a {@link #dump(DumpSink)} call. **/
    public interface DumpSink {

        /** Processes given events into some pipeline with a given tag. **/
        void sink(String tag, List<Event> events);

    }

    public abstract static class Event {

        /** Timestamps formatter. */
        private static final SimpleDateFormat sFormat =
                new SimpleDateFormat("MM-dd HH:mm:ss:SSS", Locale.US);

        private final long mTimestamp;

        public Event() {
            mTimestamp = System.currentTimeMillis();
        }

        public String toString() {
            return (new StringBuilder(sFormat.format(new Date(mTimestamp))))
                    .append(" ").append(eventToString()).toString();
        }

        /**
         * Causes the string message for the event to appear in the logcat.
         * Here is an example of how to create a new event (a StringEvent), adding it to the logger
         * (an instance of EventLogger) while also making it show in the logcat:
         * <pre>
         *     myLogger.log(
         *         (new StringEvent("something for logcat and logger")).printLog(MyClass.TAG) );
         * </pre>
         * @param tag the tag for the android.util.Log.v
         * @return the same instance of the event
         */
        public Event printLog(String tag) {
            return printLog(ALOGI, tag);
        }

        /** @hide */
        @IntDef(flag = false, value = {
                ALOGI,
                ALOGE,
                ALOGW,
                ALOGV }
        )
        @Retention(RetentionPolicy.SOURCE)
        public @interface LogType {}

        public static final int ALOGI = 0;
        public static final int ALOGE = 1;
        public static final int ALOGW = 2;
        public static final int ALOGV = 3;

        /**
         * Same as {@link #printLog(String)} with a log type
         * @param type one of {@link #ALOGI}, {@link #ALOGE}, {@link #ALOGV}, {@link #ALOGW}
         * @param tag the tag the log entry will be printed under
         * @return the event itself
         */
        public Event printLog(@LogType int type, String tag) {
            switch (type) {
                case ALOGI:
                    Log.i(tag, eventToString());
                    break;
                case ALOGE:
                    Log.e(tag, eventToString());
                    break;
                case ALOGW:
                    Log.w(tag, eventToString());
                    break;
                case ALOGV:
                default:
                    Log.v(tag, eventToString());
                    break;
            }
            return this;
        }

        /**
         * Causes the string message for the event to appear in the system log.
         * @param type one of {@link #ALOGI}, {@link #ALOGE}, {@link #ALOGV}, {@link #ALOGW}
         * @param tag the tag the log entry will be printed under
         * @return the event itself
         * @see #printLog(int, String)
         */
        public Event printSlog(@LogType int type, String tag) {
            switch (type) {
                case ALOGI:
                    Slog.i(tag, eventToString());
                    break;
                case ALOGE:
                    Slog.e(tag, eventToString());
                    break;
                case ALOGW:
                    Slog.w(tag, eventToString());
                    break;
                case ALOGV:
                default:
                    Slog.v(tag, eventToString());
                    break;
            }
            return this;
        }

        /**
         * Convert event to String.
         * This method is only called when the logger history is about to the dumped,
         * so this method is where expensive String conversions should be made, not when the Event
         * subclass is created.
         * Timestamp information will be automatically added, do not include it.
         * @return a string representation of the event that occurred.
         */
        public abstract String eventToString();
    }

    public static class StringEvent extends Event {

        @Nullable
        private final String mSource;

        private final String mDescription;

        /** Creates event from {@code source} and formatted {@code description} with {@code args} */
        public static StringEvent from(@NonNull String source,
                @NonNull String description, Object... args) {
            return new StringEvent(source, String.format(Locale.US, description, args));
        }

        public StringEvent(String description) {
            this(null /* source */, description);
        }

        public StringEvent(String source, String description) {
            mSource = source;
            mDescription = description;
        }

        @Override
        public String eventToString() {
            if (mSource == null) {
                return mDescription;
            }

            // [source ] optional description
            return String.format("[%-40s] %s",
                    mSource,
                    (mDescription == null ? "" : mDescription));
        }
    }
}
