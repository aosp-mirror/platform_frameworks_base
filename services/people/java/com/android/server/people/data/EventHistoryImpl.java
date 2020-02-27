/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.people.data;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.net.Uri;
import android.os.FileUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoInputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.people.PeopleEventIndexesProto;
import com.android.server.people.PeopleEventsProto;
import com.android.server.people.TypedPeopleEventIndexProto;

import com.google.android.collect.Lists;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;


class EventHistoryImpl implements EventHistory {

    private static final long MAX_EVENTS_AGE = 4L * DateUtils.HOUR_IN_MILLIS;
    private static final long PRUNE_OLD_EVENTS_DELAY = 15L * DateUtils.MINUTE_IN_MILLIS;

    private static final String EVENTS_DIR = "events";
    private static final String INDEXES_DIR = "indexes";

    private final Injector mInjector;
    private final ScheduledExecutorService mScheduledExecutorService;
    private final EventsProtoDiskReadWriter mEventsProtoDiskReadWriter;
    private final EventIndexesProtoDiskReadWriter mEventIndexesProtoDiskReadWriter;
    private final File mRootDir;

    // Event Type -> Event Index
    @GuardedBy("this")
    private final SparseArray<EventIndex> mEventIndexArray = new SparseArray<>();

    @GuardedBy("this")
    private final EventList mRecentEvents = new EventList();

    private long mLastPruneTime;

    EventHistoryImpl(@NonNull File rootDir,
            @NonNull ScheduledExecutorService scheduledExecutorService) {
        this(new Injector(), rootDir, scheduledExecutorService);
    }

    @VisibleForTesting
    EventHistoryImpl(@NonNull Injector injector, @NonNull File rootDir,
            @NonNull ScheduledExecutorService scheduledExecutorService) {
        mInjector = injector;
        mScheduledExecutorService = scheduledExecutorService;
        mLastPruneTime = injector.currentTimeMillis();

        mRootDir = rootDir;
        File eventsDir = new File(mRootDir, EVENTS_DIR);
        mEventsProtoDiskReadWriter = new EventsProtoDiskReadWriter(eventsDir,
                mScheduledExecutorService);
        File indexesDir = new File(mRootDir, INDEXES_DIR);
        mEventIndexesProtoDiskReadWriter = new EventIndexesProtoDiskReadWriter(indexesDir,
                scheduledExecutorService);
    }

    @WorkerThread
    @NonNull
    static Map<String, EventHistoryImpl> eventHistoriesImplFromDisk(File categoryDir,
            ScheduledExecutorService scheduledExecutorService) {
        return eventHistoriesImplFromDisk(new Injector(), categoryDir, scheduledExecutorService);
    }

    @VisibleForTesting
    @NonNull
    static Map<String, EventHistoryImpl> eventHistoriesImplFromDisk(Injector injector,
            File categoryDir, ScheduledExecutorService scheduledExecutorService) {
        Map<String, EventHistoryImpl> results = new ArrayMap<>();
        File[] keyDirs = categoryDir.listFiles(File::isDirectory);
        if (keyDirs == null) {
            return results;
        }
        for (File keyDir : keyDirs) {
            File[] dirContents = keyDir.listFiles(
                    (dir, name) -> EVENTS_DIR.equals(name) || INDEXES_DIR.equals(name));
            if (dirContents != null && dirContents.length == 2) {
                EventHistoryImpl eventHistory = new EventHistoryImpl(injector, keyDir,
                        scheduledExecutorService);
                eventHistory.loadFromDisk();
                results.put(Uri.decode(keyDir.getName()), eventHistory);
            }
        }
        return results;
    }

    /**
     * Loads recent events and indexes from disk to memory in a background thread. This should be
     * called after the device powers on and the user has been unlocked.
     */
    @VisibleForTesting
    @MainThread
    synchronized void loadFromDisk() {
        mScheduledExecutorService.execute(() -> {
            synchronized (this) {
                EventList diskEvents = mEventsProtoDiskReadWriter.loadRecentEventsFromDisk();
                if (diskEvents != null) {
                    diskEvents.removeOldEvents(mInjector.currentTimeMillis() - MAX_EVENTS_AGE);
                    mRecentEvents.addAll(diskEvents.getAllEvents());
                }

                SparseArray<EventIndex> diskIndexes =
                        mEventIndexesProtoDiskReadWriter.loadIndexesFromDisk();
                if (diskIndexes != null) {
                    for (int i = 0; i < diskIndexes.size(); i++) {
                        mEventIndexArray.put(diskIndexes.keyAt(i), diskIndexes.valueAt(i));
                    }
                }
            }
        });
    }

    /**
     * Flushes events and indexes immediately. This should be called when device is powering off.
     */
    @MainThread
    synchronized void saveToDisk() {
        mEventsProtoDiskReadWriter.saveEventsImmediately(mRecentEvents);
        mEventIndexesProtoDiskReadWriter.saveIndexesImmediately(mEventIndexArray);
    }

    @Override
    @NonNull
    public synchronized EventIndex getEventIndex(@Event.EventType int eventType) {
        EventIndex eventIndex = mEventIndexArray.get(eventType);
        return eventIndex != null ? new EventIndex(eventIndex) : mInjector.createEventIndex();
    }

    @Override
    @NonNull
    public synchronized EventIndex getEventIndex(Set<Integer> eventTypes) {
        EventIndex combined = mInjector.createEventIndex();
        for (@Event.EventType int eventType : eventTypes) {
            EventIndex eventIndex = mEventIndexArray.get(eventType);
            if (eventIndex != null) {
                combined = EventIndex.combine(combined, eventIndex);
            }
        }
        return combined;
    }

    @Override
    @NonNull
    public synchronized List<Event> queryEvents(Set<Integer> eventTypes, long startTime,
            long endTime) {
        return mRecentEvents.queryEvents(eventTypes, startTime, endTime);
    }

    synchronized void addEvent(Event event) {
        pruneOldEvents();
        addEventInMemory(event);
        mEventsProtoDiskReadWriter.scheduleEventsSave(mRecentEvents);
        mEventIndexesProtoDiskReadWriter.scheduleIndexesSave(mEventIndexArray);
    }

    synchronized void onDestroy() {
        mEventIndexArray.clear();
        mRecentEvents.clear();
        mEventsProtoDiskReadWriter.deleteRecentEventsFile();
        mEventIndexesProtoDiskReadWriter.deleteIndexesFile();
        FileUtils.deleteContentsAndDir(mRootDir);
    }

    /** Deletes the events data that exceeds the retention period. */
    synchronized void pruneOldEvents() {
        long currentTime = mInjector.currentTimeMillis();
        if (currentTime - mLastPruneTime > PRUNE_OLD_EVENTS_DELAY) {
            mRecentEvents.removeOldEvents(currentTime - MAX_EVENTS_AGE);
            mLastPruneTime = currentTime;
        }
    }

    private synchronized void addEventInMemory(Event event) {
        EventIndex eventIndex = mEventIndexArray.get(event.getType());
        if (eventIndex == null) {
            eventIndex = mInjector.createEventIndex();
            mEventIndexArray.put(event.getType(), eventIndex);
        }
        eventIndex.addEvent(event.getTimestamp());
        mRecentEvents.add(event);
    }

    @VisibleForTesting
    static class Injector {

        EventIndex createEventIndex() {
            return new EventIndex();
        }

        long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    }

    /** Reads and writes {@link Event}s on disk. */
    private static class EventsProtoDiskReadWriter extends AbstractProtoDiskReadWriter<EventList> {

        private static final String TAG = EventsProtoDiskReadWriter.class.getSimpleName();

        private static final String RECENT_FILE = "recent";


        EventsProtoDiskReadWriter(@NonNull File rootDir,
                @NonNull ScheduledExecutorService scheduledExecutorService) {
            super(rootDir, scheduledExecutorService);
            rootDir.mkdirs();
        }

        @Override
        ProtoStreamWriter<EventList> protoStreamWriter() {
            return (protoOutputStream, data) -> {
                for (Event event : data.getAllEvents()) {
                    long token = protoOutputStream.start(PeopleEventsProto.EVENTS);
                    event.writeToProto(protoOutputStream);
                    protoOutputStream.end(token);
                }
            };
        }

        @Override
        ProtoStreamReader<EventList> protoStreamReader() {
            return protoInputStream -> {
                List<Event> results = Lists.newArrayList();
                try {
                    while (protoInputStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                        if (protoInputStream.getFieldNumber() != (int) PeopleEventsProto.EVENTS) {
                            continue;
                        }
                        long token = protoInputStream.start(PeopleEventsProto.EVENTS);
                        Event event = Event.readFromProto(protoInputStream);
                        protoInputStream.end(token);
                        results.add(event);
                    }
                } catch (IOException e) {
                    Slog.e(TAG, "Failed to read protobuf input stream.", e);
                }
                EventList eventList = new EventList();
                eventList.addAll(results);
                return eventList;
            };
        }

        @MainThread
        void scheduleEventsSave(EventList recentEvents) {
            scheduleSave(RECENT_FILE, recentEvents);
        }

        @MainThread
        void saveEventsImmediately(EventList recentEvents) {
            saveImmediately(RECENT_FILE, recentEvents);
        }

        /**
         * Loads recent events from disk. This should be called when device is powered on.
         */
        @WorkerThread
        @Nullable
        EventList loadRecentEventsFromDisk() {
            return read(RECENT_FILE);
        }

        @WorkerThread
        void deleteRecentEventsFile() {
            delete(RECENT_FILE);
        }
    }

    /** Reads and writes {@link EventIndex}s on disk. */
    private static class EventIndexesProtoDiskReadWriter extends
            AbstractProtoDiskReadWriter<SparseArray<EventIndex>> {

        private static final String TAG = EventIndexesProtoDiskReadWriter.class.getSimpleName();

        private static final String INDEXES_FILE = "index";

        EventIndexesProtoDiskReadWriter(@NonNull File rootDir,
                @NonNull ScheduledExecutorService scheduledExecutorService) {
            super(rootDir, scheduledExecutorService);
            rootDir.mkdirs();
        }

        @Override
        ProtoStreamWriter<SparseArray<EventIndex>> protoStreamWriter() {
            return (protoOutputStream, data) -> {
                for (int i = 0; i < data.size(); i++) {
                    @Event.EventType int eventType = data.keyAt(i);
                    EventIndex index = data.valueAt(i);
                    long token = protoOutputStream.start(PeopleEventIndexesProto.TYPED_INDEXES);
                    protoOutputStream.write(TypedPeopleEventIndexProto.EVENT_TYPE, eventType);
                    long indexToken = protoOutputStream.start(TypedPeopleEventIndexProto.INDEX);
                    index.writeToProto(protoOutputStream);
                    protoOutputStream.end(indexToken);
                    protoOutputStream.end(token);
                }
            };
        }

        @Override
        ProtoStreamReader<SparseArray<EventIndex>> protoStreamReader() {
            return protoInputStream -> {
                SparseArray<EventIndex> results = new SparseArray<>();
                try {
                    while (protoInputStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                        if (protoInputStream.getFieldNumber()
                                != (int) PeopleEventIndexesProto.TYPED_INDEXES) {
                            continue;
                        }
                        long token = protoInputStream.start(PeopleEventIndexesProto.TYPED_INDEXES);
                        @Event.EventType int eventType = 0;
                        EventIndex index = EventIndex.EMPTY;
                        while (protoInputStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                            switch (protoInputStream.getFieldNumber()) {
                                case (int) TypedPeopleEventIndexProto.EVENT_TYPE:
                                    eventType = protoInputStream.readInt(
                                            TypedPeopleEventIndexProto.EVENT_TYPE);
                                    break;
                                case (int) TypedPeopleEventIndexProto.INDEX:
                                    long indexToken = protoInputStream.start(
                                            TypedPeopleEventIndexProto.INDEX);
                                    index = EventIndex.readFromProto(protoInputStream);
                                    protoInputStream.end(indexToken);
                                    break;
                                default:
                                    Slog.w(TAG, "Could not read undefined field: "
                                            + protoInputStream.getFieldNumber());
                            }
                        }
                        results.append(eventType, index);
                        protoInputStream.end(token);
                    }
                } catch (IOException e) {
                    Slog.e(TAG, "Failed to read protobuf input stream.", e);
                }
                return results;
            };
        }

        @MainThread
        void scheduleIndexesSave(SparseArray<EventIndex> indexes) {
            scheduleSave(INDEXES_FILE, indexes);
        }

        @MainThread
        void saveIndexesImmediately(SparseArray<EventIndex> indexes) {
            saveImmediately(INDEXES_FILE, indexes);
        }

        @WorkerThread
        @Nullable
        SparseArray<EventIndex> loadIndexesFromDisk() {
            return read(INDEXES_FILE);
        }

        @WorkerThread
        void deleteIndexesFile() {
            delete(INDEXES_FILE);
        }
    }
}
