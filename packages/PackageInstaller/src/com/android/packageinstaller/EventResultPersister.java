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
 * limitations under the License.
 */

package com.android.packageinstaller;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.AsyncTask;
import android.util.AtomicFile;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Persists results of events and calls back observers when a matching result arrives.
 */
class EventResultPersister {
    private static final String LOG_TAG = EventResultPersister.class.getSimpleName();

    /** Id passed to {@link #addObserver(int, EventResultObserver)} to generate new id */
    static final int GENERATE_NEW_ID = Integer.MIN_VALUE;

    /**
     * The extra with the id to set in the intent delivered to
     * {@link #onEventReceived(Context, Intent)}
     */
    static final String EXTRA_ID = "EventResultPersister.EXTRA_ID";

    /** Persisted state of this object */
    private final AtomicFile mResultsFile;

    private final Object mLock = new Object();

    /** Currently stored but not yet called back results (install id -> status, status message) */
    private final SparseArray<EventResult> mResults = new SparseArray<>();

    /** Currently registered, not called back observers (install id -> observer) */
    private final SparseArray<EventResultObserver> mObservers = new SparseArray<>();

    /** Always increasing counter for install event ids */
    private int mCounter;

    /** If a write that will persist the state is scheduled */
    private boolean mIsPersistScheduled;

    /** If the state was changed while the data was being persisted */
    private boolean mIsPersistingStateValid;

    /**
     * @return a new event id.
     */
    public int getNewId() throws OutOfIdsException {
        synchronized (mLock) {
            if (mCounter == Integer.MAX_VALUE) {
                throw new OutOfIdsException();
            }

            mCounter++;
            writeState();

            return mCounter - 1;
        }
    }

    /** Call back when a result is received. Observer is removed when onResult it called. */
    interface EventResultObserver {
        void onResult(int status, int legacyStatus, @Nullable String message);
    }

    /**
     * Progress parser to the next element.
     *
     * @param parser The parser to progress
     */
    private static void nextElement(@NonNull XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int type;
        do {
            type = parser.next();
        } while (type != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT);
    }

    /**
     * Read an int attribute from the current element
     *
     * @param parser The parser to read from
     * @param name The attribute name to read
     *
     * @return The value of the attribute
     */
    private static int readIntAttribute(@NonNull XmlPullParser parser, @NonNull String name) {
        return Integer.parseInt(parser.getAttributeValue(null, name));
    }

    /**
     * Read an String attribute from the current element
     *
     * @param parser The parser to read from
     * @param name The attribute name to read
     *
     * @return The value of the attribute or null if the attribute is not set
     */
    private static String readStringAttribute(@NonNull XmlPullParser parser, @NonNull String name) {
        return parser.getAttributeValue(null, name);
    }

    /**
     * Read persisted state.
     *
     * @param resultFile The file the results are persisted in
     */
    EventResultPersister(@NonNull File resultFile) {
        mResultsFile = new AtomicFile(resultFile);
        mCounter = GENERATE_NEW_ID + 1;

        try (FileInputStream stream = mResultsFile.openRead()) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, StandardCharsets.UTF_8.name());

            nextElement(parser);
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                if ("results".equals(tagName)) {
                    mCounter = readIntAttribute(parser, "counter");
                } else if ("result".equals(tagName)) {
                    int id = readIntAttribute(parser, "id");
                    int status = readIntAttribute(parser, "status");
                    int legacyStatus = readIntAttribute(parser, "legacyStatus");
                    String statusMessage = readStringAttribute(parser, "statusMessage");

                    if (mResults.get(id) != null) {
                        throw new Exception("id " + id + " has two results");
                    }

                    mResults.put(id, new EventResult(status, legacyStatus, statusMessage));
                } else {
                    throw new Exception("unexpected tag");
                }

                nextElement(parser);
            }
        } catch (Exception e) {
            mResults.clear();
            writeState();
        }
    }

    /**
     * Add a result. If the result is an pending user action, execute the pending user action
     * directly and do not queue a result.
     *
     * @param context The context the event was received in
     * @param intent The intent the activity received
     */
    void onEventReceived(@NonNull Context context, @NonNull Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, 0);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            context.startActivity(intent.getParcelableExtra(Intent.EXTRA_INTENT));

            return;
        }

        int id = intent.getIntExtra(EXTRA_ID, 0);
        String statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        int legacyStatus = intent.getIntExtra(PackageInstaller.EXTRA_LEGACY_STATUS, 0);

        EventResultObserver observerToCall = null;
        synchronized (mLock) {
            int numObservers = mObservers.size();
            for (int i = 0; i < numObservers; i++) {
                if (mObservers.keyAt(i) == id) {
                    observerToCall = mObservers.valueAt(i);
                    mObservers.removeAt(i);

                    break;
                }
            }

            if (observerToCall != null) {
                observerToCall.onResult(status, legacyStatus, statusMessage);
            } else {
                mResults.put(id, new EventResult(status, legacyStatus, statusMessage));
                writeState();
            }
        }
    }

    /**
     * Persist current state. The persistence might be delayed.
     */
    private void writeState() {
        synchronized (mLock) {
            mIsPersistingStateValid = false;

            if (!mIsPersistScheduled) {
                mIsPersistScheduled = true;

                AsyncTask.execute(() -> {
                    int counter;
                    SparseArray<EventResult> results;

                    while (true) {
                        // Take snapshot of state
                        synchronized (mLock) {
                            counter = mCounter;
                            results = mResults.clone();
                            mIsPersistingStateValid = true;
                        }

                        FileOutputStream stream = null;
                        try {
                            stream = mResultsFile.startWrite();
                            XmlSerializer serializer = Xml.newSerializer();
                            serializer.setOutput(stream, StandardCharsets.UTF_8.name());
                            serializer.startDocument(null, true);
                            serializer.setFeature(
                                    "http://xmlpull.org/v1/doc/features.html#indent-output", true);
                            serializer.startTag(null, "results");
                            serializer.attribute(null, "counter", Integer.toString(counter));

                            int numResults = results.size();
                            for (int i = 0; i < numResults; i++) {
                                serializer.startTag(null, "result");
                                serializer.attribute(null, "id",
                                        Integer.toString(results.keyAt(i)));
                                serializer.attribute(null, "status",
                                        Integer.toString(results.valueAt(i).status));
                                serializer.attribute(null, "legacyStatus",
                                        Integer.toString(results.valueAt(i).legacyStatus));
                                if (results.valueAt(i).message != null) {
                                    serializer.attribute(null, "statusMessage",
                                            results.valueAt(i).message);
                                }
                                serializer.endTag(null, "result");
                            }

                            serializer.endTag(null, "results");
                            serializer.endDocument();

                            mResultsFile.finishWrite(stream);
                        } catch (IOException e) {
                            if (stream != null) {
                                mResultsFile.failWrite(stream);
                            }

                            Log.e(LOG_TAG, "error writing results", e);
                            mResultsFile.delete();
                        }

                        // Check if there was changed state since we persisted. If so, we need to
                        // persist again.
                        synchronized (mLock) {
                            if (mIsPersistingStateValid) {
                                mIsPersistScheduled = false;
                                break;
                            }
                        }
                    }
                });
            }
        }
    }

    /**
     * Add an observer. If there is already an event for this id, call back inside of this call.
     *
     * @param id       The id the observer is for or {@code GENERATE_NEW_ID} to generate a new one.
     * @param observer The observer to call back.
     *
     * @return The id for this event
     */
    int addObserver(int id, @NonNull EventResultObserver observer)
            throws OutOfIdsException {
        synchronized (mLock) {
            int resultIndex = -1;

            if (id == GENERATE_NEW_ID) {
                id = getNewId();
            } else {
                resultIndex = mResults.indexOfKey(id);
            }

            // Check if we can instantly call back
            if (resultIndex >= 0) {
                EventResult result = mResults.valueAt(resultIndex);

                observer.onResult(result.status, result.legacyStatus, result.message);
                mResults.removeAt(resultIndex);
                writeState();
            } else {
                mObservers.put(id, observer);
            }
        }


        return id;
    }

    /**
     * Remove a observer.
     *
     * @param id The id the observer was added for
     */
    void removeObserver(int id) {
        synchronized (mLock) {
            mObservers.delete(id);
        }
    }

    /**
     * The status from an event.
     */
    private class EventResult {
        public final int status;
        public final int legacyStatus;
        @Nullable public final String message;

        private EventResult(int status, int legacyStatus, @Nullable String message) {
            this.status = status;
            this.legacyStatus = legacyStatus;
            this.message = message;
        }
    }

    class OutOfIdsException extends Exception {}
}
