/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package android.telecom.Logging;

import android.annotation.NonNull;
import android.telecom.Log;
import android.text.TextUtils;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A utility class that provides the ability to define Events that a subsystem deems important, and
 * then relate those events to other events so that information can be extracted. For example, a
 * START and FINISH event can be defined and when a START and then FINISH occurs in a sequence, the
 * time it took to complete that sequence can be saved to be retrieved later.
 * @hide
 */

public class EventManager {

    public static final String TAG = "Logging.Events";
    @VisibleForTesting
    public static final int DEFAULT_EVENTS_TO_CACHE = 10;  // Arbitrarily chosen.
    public static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public interface Loggable {
        /**
         * @return a unique String ID that will allow the Event to be recognized later in the logs.
         */
        String getId();

        /**
         * @return Formatted information about the state that will be printed out later in the logs.
         */
        String getDescription();
    }

    private final Map<Loggable, EventRecord> mCallEventRecordMap = new HashMap<>();
    private LinkedBlockingQueue<EventRecord> mEventRecords =
            new LinkedBlockingQueue<>(DEFAULT_EVENTS_TO_CACHE);

    private List<EventListener> mEventListeners = new ArrayList<>();

    public interface EventListener {
        /**
         * Notifies the implementation of this method that a new event record has been added.
         * @param eventRecord Reference to the recently added EventRecord
         */
        void eventRecordAdded(EventRecord eventRecord);
    }

    private SessionManager.ISessionIdQueryHandler mSessionIdHandler;
    /**
     * Maps from request events to a list of possible response events. Used to track
     * end-to-end timing for critical user-facing operations in Telecom.
     */
    private final Map<String, List<TimedEventPair>> requestResponsePairs = new HashMap<>();

    private static final Object mSync = new Object();

    /**
     * Stores the various events.
     * Also stores all request-response pairs amongst the events.
     */
    public static class TimedEventPair {
        private static final long DEFAULT_TIMEOUT = 3000L;

        String mRequest;
        String mResponse;
        String mName;
        long mTimeoutMillis = DEFAULT_TIMEOUT;

        public TimedEventPair(String request, String response, String name) {
            this.mRequest = request;
            this.mResponse = response;
            this.mName = name;
        }

        public TimedEventPair(String request, String response, String name, long timeoutMillis) {
            this.mRequest = request;
            this.mResponse = response;
            this.mName = name;
            this.mTimeoutMillis = timeoutMillis;
        }
    }

    public void addRequestResponsePair(TimedEventPair p) {
        if (requestResponsePairs.containsKey(p.mRequest)) {
            requestResponsePairs.get(p.mRequest).add(p);
        } else {
            ArrayList<TimedEventPair> responses = new ArrayList<>();
            responses.add(p);
            requestResponsePairs.put(p.mRequest, responses);
        }
    }

    public static class Event {
        public String eventId;
        public String sessionId;
        public long time;
        public Object data;
        // String storing the date for display. This will be computed at the time/timezone when
        // the event is recorded.
        public final String timestampString;

        public Event(String eventId, String sessionId, long time, Object data) {
            this.eventId = eventId;
            this.sessionId = sessionId;
            this.time = time;
            timestampString =
                    ZonedDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault())
                    .format(DATE_TIME_FORMATTER);
            this.data = data;
        }
    }

    public class EventRecord {
        public class EventTiming extends TimedEvent<String> {
            public String name;
            public long time;

            public EventTiming(String name, long time) {
                this.name = name;
                this.time = time;
            }

            public String getKey() {
                return name;
            }

            public long getTime() {
                return time;
            }
        }

        private class PendingResponse {
            String requestEventId;
            long requestEventTimeMillis;
            long timeoutMillis;
            String name;

            public PendingResponse(String requestEventId, long requestEventTimeMillis,
                    long timeoutMillis, String name) {
                this.requestEventId = requestEventId;
                this.requestEventTimeMillis = requestEventTimeMillis;
                this.timeoutMillis = timeoutMillis;
                this.name = name;
            }
        }

        private final List<Event> mEvents = Collections.synchronizedList(new LinkedList<>());
        private final Loggable mRecordEntry;

        public EventRecord(Loggable recordEntry) {
            mRecordEntry = recordEntry;
        }

        public Loggable getRecordEntry() {
            return mRecordEntry;
        }

        public void addEvent(String event, String sessionId, Object data) {
            mEvents.add(new Event(event, sessionId, System.currentTimeMillis(), data));
            Log.i("Event", "RecordEntry %s: %s, %s", mRecordEntry.getId(), event, data);
        }

        public List<Event> getEvents() {
            return new LinkedList<>(mEvents);
        }

        public List<EventTiming> extractEventTimings() {
            if (mEvents == null) {
                return Collections.emptyList();
            }

            LinkedList<EventTiming> result = new LinkedList<>();
            Map<String, PendingResponse> pendingResponses = new HashMap<>();
            synchronized (mEvents) {
                for (Event event : mEvents) {
                    if (requestResponsePairs.containsKey(event.eventId)) {
                        // This event expects a response, so add that expected response to the maps
                        // of pending events.
                        for (EventManager.TimedEventPair p : requestResponsePairs.get(
                                event.eventId)) {
                            pendingResponses.put(p.mResponse, new PendingResponse(event.eventId,
                                    event.time, p.mTimeoutMillis, p.mName));
                        }
                    }

                    PendingResponse pendingResponse = pendingResponses.remove(event.eventId);
                    if (pendingResponse != null) {
                        long elapsedTime = event.time - pendingResponse.requestEventTimeMillis;
                        if (elapsedTime < pendingResponse.timeoutMillis) {
                            result.add(new EventTiming(pendingResponse.name, elapsedTime));
                        }
                    }
                }
            }

            return result;
        }

        public void dump(IndentingPrintWriter pw) {
            pw.print(mRecordEntry.getDescription());

            pw.increaseIndent();
            // Iterate over copy of events so that this doesn't hold the lock for too long.
            for (Event event : getEvents()) {
                pw.print(event.timestampString);
                pw.print(" - ");
                pw.print(event.eventId);
                if (event.data != null) {
                    pw.print(" (");
                    Object data = event.data;

                    if (data instanceof Loggable) {
                        // If the data is another Loggable, then change the data to the
                        // Entry's Event ID instead.
                        EventRecord record = mCallEventRecordMap.get(data);
                        if (record != null) {
                            data = "RecordEntry " + record.mRecordEntry.getId();
                        }
                    }

                    pw.print(data);
                    pw.print(")");
                }
                if (!TextUtils.isEmpty(event.sessionId)) {
                    pw.print(":");
                    pw.print(event.sessionId);
                }
                pw.println();
            }

            pw.println("Timings (average for this call, milliseconds):");
            pw.increaseIndent();
            Map<String, Double> avgEventTimings = EventTiming.averageTimings(extractEventTimings());
            List<String> eventNames = new ArrayList<>(avgEventTimings.keySet());
            Collections.sort(eventNames);
            for (String eventName : eventNames) {
                pw.printf("%s: %.2f\n", eventName, avgEventTimings.get(eventName));
            }
            pw.decreaseIndent();
            pw.decreaseIndent();
        }
    }

    public EventManager(@NonNull SessionManager.ISessionIdQueryHandler l) {
        mSessionIdHandler = l;
    }

    public void event(Loggable recordEntry, String event, Object data) {
        String currentSessionID = mSessionIdHandler.getSessionId();

        if (recordEntry == null) {
            Log.i(TAG, "Non-call EVENT: %s, %s", event, data);
            return;
        }
        synchronized (mEventRecords) {
            if (!mCallEventRecordMap.containsKey(recordEntry)) {
                EventRecord newRecord = new EventRecord(recordEntry);
                addEventRecord(newRecord);
            }

            EventRecord record = mCallEventRecordMap.get(recordEntry);
            record.addEvent(event, currentSessionID, data);
        }
    }

    public void event(Loggable recordEntry, String event, String format, Object... args) {
        String msg;
        try {
            msg = (args == null || args.length == 0) ? format
                    : String.format(Locale.US, format, args);
        } catch (IllegalFormatException ife) {
            Log.e(this, ife, "IllegalFormatException: formatString='%s' numArgs=%d", format,
                    args.length);
            msg = format + " (An error occurred while formatting the message.)";
        }

        event(recordEntry, event, msg);
    }

    public void dumpEvents(IndentingPrintWriter pw) {
        pw.println("Historical Events:");
        pw.increaseIndent();
        for (EventRecord eventRecord : mEventRecords) {
            eventRecord.dump(pw);
        }
        pw.decreaseIndent();
    }

    /**
     * Dumps events in a timeline format.
     * @param pw The {@link IndentingPrintWriter} to output the timeline to.
     * @hide
     */
    public void dumpEventsTimeline(IndentingPrintWriter pw) {
        pw.println("Historical Events (sorted by time):");

        // Flatten event records out for sorting.
        List<Pair<Loggable, Event>> events = new ArrayList<>();
        for (EventRecord er : mEventRecords) {
            for (Event ev : er.getEvents()) {
                events.add(new Pair<>(er.getRecordEntry(), ev));
            }
        }

        // Sort by event time. This might result in out-of-order seeming events if the timezone
        // changes somewhere in the middle.
        Comparator<Pair<Loggable, Event>> byEventTime =
                Comparator.comparingLong(e -> e.second.time);
        events.sort(byEventTime);

        pw.increaseIndent();
        for (Pair<Loggable, Event> event : events) {
            pw.print(event.second.timestampString);
            pw.print(",");
            pw.print(event.first.getId());
            pw.print(",");
            pw.print(event.second.eventId);
            pw.print(",");
            pw.println(event.second.data);
        }
        pw.decreaseIndent();
    }

    public void changeEventCacheSize(int newSize) {

        // Resize the event queue.
        LinkedBlockingQueue<EventRecord> oldEventLog = mEventRecords;
        mEventRecords = new LinkedBlockingQueue<>(newSize);
        mCallEventRecordMap.clear();

        oldEventLog.forEach((newRecord -> {
            Loggable recordEntry = newRecord.getRecordEntry();
            // Copy the existing queue into the new one.
            // First remove the oldest entry if no new ones exist.
            if (mEventRecords.remainingCapacity() == 0) {
                EventRecord record = mEventRecords.poll();
                if (record != null) {
                    mCallEventRecordMap.remove(record.getRecordEntry());
                }
            }

            // Now add a new entry
            mEventRecords.add(newRecord);
            mCallEventRecordMap.put(recordEntry, newRecord);

            // Don't worry about notifying mEventListeners, since we are just resizing the records.
        }));
    }

    public void registerEventListener(EventListener e) {
        if (e != null) {
            synchronized (mSync) {
                mEventListeners.add(e);
            }
        }
    }

    @VisibleForTesting
    public LinkedBlockingQueue<EventRecord> getEventRecords() {
        return mEventRecords;
    }

    @VisibleForTesting
    public Map<Loggable, EventRecord> getCallEventRecordMap() {
        return mCallEventRecordMap;
    }

    private void addEventRecord(EventRecord newRecord) {
        Loggable recordEntry = newRecord.getRecordEntry();

        // First remove the oldest entry if no new ones exist.
        if (mEventRecords.remainingCapacity() == 0) {
            EventRecord record = mEventRecords.poll();
            if (record != null) {
                mCallEventRecordMap.remove(record.getRecordEntry());
            }
        }

        // Now add a new entry
        mEventRecords.add(newRecord);
        mCallEventRecordMap.put(recordEntry, newRecord);
        synchronized (mSync) {
            for (EventListener l : mEventListeners) {
                l.eventRecordAdded(newRecord);
            }
        }
    }
}
