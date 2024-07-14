/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.location.contexthub;

import android.chre.flags.Flags;
import android.hardware.location.NanoAppMessage;
import android.util.Log;

import java.util.Collection;
import java.util.Optional;

/**
 * A class to log events and useful metrics within the Context Hub service.
 *
 * The class holds a queue of the last NUM_EVENTS_TO_STORE events for each
 * event category: nanoapp load, nanoapp unload, message from a nanoapp,
 * message to a nanoapp, and context hub restarts. The dump() function
 * will be called during debug dumps, giving access to the event information
 * and aggregate data since the instantiation of this class.
 *
 * @hide
 */
public class ContextHubEventLogger {

    /**
     * The base class for all Context Hub events
     */
    public static class ContextHubEventBase {
        /**
         * the timestamp in milliseconds
         */
        public final long timeStampInMs;

        /**
         * the ID of the context hub
         */
        public final int contextHubId;

        public ContextHubEventBase(long mTimeStampInMs, int mContextHubId) {
            timeStampInMs = mTimeStampInMs;
            contextHubId = mContextHubId;
        }
    }

    /**
     * A base class for nanoapp events
     */
    public static class NanoappEventBase extends ContextHubEventBase {
        /**
         * the ID of the nanoapp
         */
        public final long nanoappId;

        /**
         * whether the event was successful
         */
        public final boolean success;

        public NanoappEventBase(long mTimeStampInMs, int mContextHubId,
                                long mNanoappId, boolean mSuccess) {
            super(mTimeStampInMs, mContextHubId);
            nanoappId = mNanoappId;
            success = mSuccess;
        }
    }

    /**
     * Represents a nanoapp load event
     */
    public static class NanoappLoadEvent extends NanoappEventBase {
        /**
         * the version of the nanoapp
         */
        public final int nanoappVersion;

        /**
         * the size in bytes of the nanoapp
         */
        public final long nanoappSize;

        public NanoappLoadEvent(long mTimeStampInMs, int mContextHubId, long mNanoappId,
                                int mNanoappVersion, long mNanoappSize, boolean mSuccess) {
            super(mTimeStampInMs, mContextHubId, mNanoappId, mSuccess);
            nanoappVersion = mNanoappVersion;
            nanoappSize = mNanoappSize;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(ContextHubServiceUtil.formatDateFromTimestamp(timeStampInMs));
            sb.append(": NanoappLoadEvent[hubId = ");
            sb.append(contextHubId);
            sb.append(", appId = 0x");
            sb.append(Long.toHexString(nanoappId));
            sb.append(", appVersion = ");
            sb.append(nanoappVersion);
            sb.append(", appSize = ");
            sb.append(nanoappSize);
            sb.append(" bytes, success = ");
            sb.append(success ? "true" : "false");
            sb.append(']');
            return sb.toString();
        }
    }

    /**
     * Represents a nanoapp unload event
     */
    public static class NanoappUnloadEvent extends NanoappEventBase {
        public NanoappUnloadEvent(long mTimeStampInMs, int mContextHubId,
                                  long mNanoappId, boolean mSuccess) {
            super(mTimeStampInMs, mContextHubId, mNanoappId, mSuccess);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(ContextHubServiceUtil.formatDateFromTimestamp(timeStampInMs));
            sb.append(": NanoappUnloadEvent[hubId = ");
            sb.append(contextHubId);
            sb.append(", appId = 0x");
            sb.append(Long.toHexString(nanoappId));
            sb.append(", success = ");
            sb.append(success ? "true" : "false");
            sb.append(']');
            return sb.toString();
        }
    }

    /**
     * Represents a nanoapp message event
     */
    public static class NanoappMessageEvent extends NanoappEventBase {
        /**
         * the message that was sent
         */
        public final NanoAppMessage message;

        /**
         * the error code for the message
         */
        public Optional<Byte> errorCode;

        public NanoappMessageEvent(long mTimeStampInMs, int mContextHubId,
                                   NanoAppMessage mMessage, boolean mSuccess) {
            super(mTimeStampInMs, mContextHubId, 0, mSuccess);
            message = mMessage;
            errorCode = Optional.empty();
        }

        public void setErrorCode(byte errorCode) {
            this.errorCode = Optional.of(errorCode);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(ContextHubServiceUtil.formatDateFromTimestamp(timeStampInMs));
            sb.append(": NanoappMessageEvent[hubId = ");
            sb.append(contextHubId);
            sb.append(", ");
            sb.append(message.toString());
            sb.append(", success = ");
            sb.append(success ? "true" : "false");
            sb.append(", errorCode = ");
            sb.append(errorCode.isPresent() ? errorCode.get() : "null");
            sb.append(']');
            return sb.toString();
        }
    }

    /**
     * Represents a context hub restart event
     */
    public static class ContextHubRestartEvent extends ContextHubEventBase {
        public ContextHubRestartEvent(long mTimeStampInMs, int mContextHubId) {
            super(mTimeStampInMs, mContextHubId);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(ContextHubServiceUtil.formatDateFromTimestamp(timeStampInMs));
            sb.append(": ContextHubRestartEvent[hubId = ");
            sb.append(contextHubId);
            sb.append(']');
            return sb.toString();
        }
    }

    public static final int NUM_EVENTS_TO_STORE = 20;
    private static final String TAG = "ContextHubEventLogger";

    private final ConcurrentLinkedEvictingDeque<NanoappLoadEvent> mNanoappLoadEventQueue =
            new ConcurrentLinkedEvictingDeque<>(NUM_EVENTS_TO_STORE);
    private final ConcurrentLinkedEvictingDeque<NanoappUnloadEvent> mNanoappUnloadEventQueue =
            new ConcurrentLinkedEvictingDeque<>(NUM_EVENTS_TO_STORE);
    private final ConcurrentLinkedEvictingDeque<NanoappMessageEvent> mMessageFromNanoappQueue =
            new ConcurrentLinkedEvictingDeque<>(NUM_EVENTS_TO_STORE);
    private final ConcurrentLinkedEvictingDeque<NanoappMessageEvent> mMessageToNanoappQueue =
            new ConcurrentLinkedEvictingDeque<>(NUM_EVENTS_TO_STORE);
    private final ConcurrentLinkedEvictingDeque<ContextHubRestartEvent>
            mContextHubRestartEventQueue = new ConcurrentLinkedEvictingDeque<>(NUM_EVENTS_TO_STORE);

    // Make ContextHubEventLogger a singleton
    private static ContextHubEventLogger sInstance = null;

    private ContextHubEventLogger() {}

    /**
     * Gets the singleton instance for ContextHubEventLogger
     */
    public static synchronized ContextHubEventLogger getInstance() {
        if (sInstance == null) {
            sInstance = new ContextHubEventLogger();
        }
        return sInstance;
    }

    /**
     * Clears all queues of events.
     */
    public synchronized void clear() {
        for (Collection<?> deque:
                new Collection<?>[] {mNanoappLoadEventQueue, mNanoappUnloadEventQueue,
                                     mMessageFromNanoappQueue, mMessageToNanoappQueue,
                                     mContextHubRestartEventQueue}) {
            deque.clear();
        }
    }

    /**
     * Logs a nanoapp load event
     *
     * @param contextHubId      the ID of the context hub
     * @param nanoappId         the ID of the nanoapp
     * @param nanoappVersion    the version of the nanoapp
     * @param nanoappSize       the size in bytes of the nanoapp
     * @param success           whether the load was successful
     */
    public synchronized void logNanoappLoad(int contextHubId, long nanoappId, int nanoappVersion,
                                            long nanoappSize, boolean success) {
        long timeStampInMs = System.currentTimeMillis();
        NanoappLoadEvent event = new NanoappLoadEvent(timeStampInMs, contextHubId, nanoappId,
                                                      nanoappVersion, nanoappSize, success);
        boolean status = mNanoappLoadEventQueue.add(event);
        if (!status) {
            Log.e(TAG, "Unable to add nanoapp load event to queue: " + event);
        }
    }

    /**
     * Logs a nanoapp unload event
     *
     * @param contextHubId      the ID of the context hub
     * @param nanoappId         the ID of the nanoapp
     * @param success           whether the unload was successful
     */
    public synchronized void logNanoappUnload(int contextHubId, long nanoappId, boolean success) {
        long timeStampInMs = System.currentTimeMillis();
        NanoappUnloadEvent event = new NanoappUnloadEvent(timeStampInMs, contextHubId,
                                                          nanoappId, success);
        boolean status = mNanoappUnloadEventQueue.add(event);
        if (!status) {
            Log.e(TAG, "Unable to add nanoapp unload event to queue: " + event);
        }
    }

    /**
     * Logs the event where a nanoapp sends a message to a client
     *
     * @param contextHubId      the ID of the context hub
     * @param message           the message that was sent
     * @param success           whether the message was sent successfully
     */
    public synchronized void logMessageFromNanoapp(int contextHubId, NanoAppMessage message,
                                                   boolean success) {
        if (message == null) {
            return;
        }

        long timeStampInMs = System.currentTimeMillis();
        NanoappMessageEvent event = new NanoappMessageEvent(timeStampInMs, contextHubId,
                                                            message, success);
        boolean status = mMessageFromNanoappQueue.add(event);
        if (!status) {
            Log.e(TAG, "Unable to add message from nanoapp event to queue: " + event);
        }
    }

    /**
     * Logs the event where a client sends a message to a nanoapp
     *
     * @param contextHubId      the ID of the context hub
     * @param message           the message that was sent
     * @param success           whether the message was sent successfully
     */
    public synchronized void logMessageToNanoapp(int contextHubId, NanoAppMessage message,
                                                 boolean success) {
        if (message == null) {
            return;
        }

        long timeStampInMs = System.currentTimeMillis();
        NanoappMessageEvent event = new NanoappMessageEvent(timeStampInMs, contextHubId,
                                                            message, success);
        boolean status = mMessageToNanoappQueue.add(event);
        if (!status) {
            Log.e(TAG, "Unable to add message to nanoapp event to queue: " + event);
        }
    }

    /**
     * Logs the status of a reliable message
     *
     * @param messageSequenceNumber the message sequence number
     * @param errorCode the error code
     */
    public synchronized void logReliableMessageToNanoappStatus(
            int messageSequenceNumber, byte errorCode) {
        if (!Flags.reliableMessage()) {
            return;
        }

        for (NanoappMessageEvent event : mMessageToNanoappQueue) {
            if (event.message.isReliable()
                    && event.message.getMessageSequenceNumber()
                            == messageSequenceNumber) {
                event.setErrorCode(errorCode);
                break;
            }
        }
    }

    /**
     * Logs a context hub restart event
     *
     * @param contextHubId      the ID of the context hub
     */
    public synchronized void logContextHubRestart(int contextHubId) {
        long timeStampInMs = System.currentTimeMillis();
        ContextHubRestartEvent event = new ContextHubRestartEvent(timeStampInMs, contextHubId);
        boolean status = mContextHubRestartEventQueue.add(event);
        if (!status) {
            Log.e(TAG, "Unable to add Context Hub restart event to queue: " + event);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Nanoapp Loads:");
        sb.append(System.lineSeparator());
        for (NanoappLoadEvent event : mNanoappLoadEventQueue) {
            sb.append(event);
            sb.append(System.lineSeparator());
        }
        sb.append(System.lineSeparator());
        sb.append("Nanoapp Unloads:");
        sb.append(System.lineSeparator());
        for (NanoappUnloadEvent event : mNanoappUnloadEventQueue) {
            sb.append(event);
            sb.append(System.lineSeparator());
        }
        sb.append(System.lineSeparator());
        sb.append("Messages from Nanoapps:");
        sb.append(System.lineSeparator());
        for (NanoappMessageEvent event : mMessageFromNanoappQueue) {
            sb.append(event);
            sb.append(System.lineSeparator());
        }
        sb.append(System.lineSeparator());
        sb.append("Messages to Nanoapps:");
        sb.append(System.lineSeparator());
        for (NanoappMessageEvent event : mMessageToNanoappQueue) {
            sb.append(event);
            sb.append(System.lineSeparator());
        }
        sb.append(System.lineSeparator());
        sb.append("Context Hub Restarts:");
        sb.append(System.lineSeparator());
        for (ContextHubRestartEvent event : mContextHubRestartEventQueue) {
            sb.append(event);
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }
}
