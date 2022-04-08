/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Access to the system diagnostic event record.  System diagnostic events are
 * used to record certain system-level events (such as garbage collection,
 * activity manager state, system watchdogs, and other low level activity),
 * which may be automatically collected and analyzed during system development.
 *
 * <p>This is <b>not</b> the main "logcat" debugging log ({@link android.util.Log})!
 * These diagnostic events are for system integrators, not application authors.
 *
 * <p>Events use integer tag codes corresponding to /system/etc/event-log-tags.
 * They carry a payload of one or more int, long, or String values.  The
 * event-log-tags file defines the payload contents for each type code.
 */
public class EventLog {
    /** @hide */ public EventLog() {}

    private static final String TAG = "EventLog";

    private static final String TAGS_FILE = "/system/etc/event-log-tags";
    private static final String COMMENT_PATTERN = "^\\s*(#.*)?$";
    private static final String TAG_PATTERN = "^\\s*(\\d+)\\s+(\\w+)\\s*(\\(.*\\))?\\s*$";
    private static HashMap<String, Integer> sTagCodes = null;
    private static HashMap<Integer, String> sTagNames = null;

    /** A previously logged event read from the logs. Instances are thread safe. */
    public static final class Event {
        private final ByteBuffer mBuffer;
        private Exception mLastWtf;

        // Layout of event log entry received from Android logger.
        //  see system/core/liblog/include/log/log_read.h
        private static final int LENGTH_OFFSET = 0;
        private static final int HEADER_SIZE_OFFSET = 2;
        private static final int PROCESS_OFFSET = 4;
        private static final int THREAD_OFFSET = 8;
        private static final int SECONDS_OFFSET = 12;
        private static final int NANOSECONDS_OFFSET = 16;
        private static final int UID_OFFSET = 24;

        // Layout for event log v1 format, v2 and v3 use HEADER_SIZE_OFFSET
        private static final int V1_PAYLOAD_START = 20;
        private static final int TAG_LENGTH = 4;

        // Value types
        private static final byte INT_TYPE    = 0;
        private static final byte LONG_TYPE   = 1;
        private static final byte STRING_TYPE = 2;
        private static final byte LIST_TYPE   = 3;
        private static final byte FLOAT_TYPE = 4;

        /** @param data containing event, read from the system */
        @UnsupportedAppUsage
        /*package*/ Event(byte[] data) {
            mBuffer = ByteBuffer.wrap(data);
            mBuffer.order(ByteOrder.nativeOrder());
        }

        /** @return the process ID which wrote the log entry */
        public int getProcessId() {
            return mBuffer.getInt(PROCESS_OFFSET);
        }

        /**
         * @return the UID which wrote the log entry
         * @hide
         */
        @SystemApi
        public int getUid() {
            try {
                return mBuffer.getInt(UID_OFFSET);
            } catch (IndexOutOfBoundsException e) {
                // buffer won't contain the UID if the caller doesn't have permission.
                return -1;
            }
        }

        /** @return the thread ID which wrote the log entry */
        public int getThreadId() {
            return mBuffer.getInt(THREAD_OFFSET);
        }

        /** @return the wall clock time when the entry was written */
        public long getTimeNanos() {
            return mBuffer.getInt(SECONDS_OFFSET) * 1000000000l
                    + mBuffer.getInt(NANOSECONDS_OFFSET);
        }

        /** @return the type tag code of the entry */
        public int getTag() {
            return mBuffer.getInt(getHeaderSize());
        }

        private int getHeaderSize() {
            int length = mBuffer.getShort(HEADER_SIZE_OFFSET);
            if (length != 0) {
                return length;
            }
            return V1_PAYLOAD_START;
        }
        /** @return one of Integer, Long, Float, String, null, or Object[] of same. */
        public synchronized Object getData() {
            try {
                int offset = getHeaderSize();
                mBuffer.limit(offset + mBuffer.getShort(LENGTH_OFFSET));
                if ((offset + TAG_LENGTH) >= mBuffer.limit()) {
                    // no payload
                    return null;
                }
                mBuffer.position(offset + TAG_LENGTH); // Just after the tag.
                return decodeObject();
            } catch (IllegalArgumentException e) {
                Log.wtf(TAG, "Illegal entry payload: tag=" + getTag(), e);
                mLastWtf = e;
                return null;
            } catch (BufferUnderflowException e) {
                Log.wtf(TAG, "Truncated entry payload: tag=" + getTag(), e);
                mLastWtf = e;
                return null;
            }
        }

        /**
         * Construct a new EventLog object from the current object, copying all log metadata
         * but replacing the actual payload with the content provided.
         * @hide
         */
        public Event withNewData(@Nullable Object object) {
            byte[] payload = encodeObject(object);
            if (payload.length > 65535 - TAG_LENGTH) {
                throw new IllegalArgumentException("Payload too long");
            }
            int headerLength = getHeaderSize();
            byte[] newBytes = new byte[headerLength + TAG_LENGTH + payload.length];
            // Copy header (including the 4 bytes of tag integer at the beginning of payload)
            System.arraycopy(mBuffer.array(), 0, newBytes, 0, headerLength + TAG_LENGTH);
            // Fill in encoded objects
            System.arraycopy(payload, 0, newBytes, headerLength + TAG_LENGTH, payload.length);
            Event result = new Event(newBytes);
            // Patch payload length in header
            result.mBuffer.putShort(LENGTH_OFFSET, (short) (payload.length + TAG_LENGTH));
            return result;
        }

        /** @return the loggable item at the current position in mBuffer. */
        private Object decodeObject() {
            byte type = mBuffer.get();
            switch (type) {
            case INT_TYPE:
                return mBuffer.getInt();

            case LONG_TYPE:
                return mBuffer.getLong();

            case FLOAT_TYPE:
                return mBuffer.getFloat();

            case STRING_TYPE:
                try {
                    int length = mBuffer.getInt();
                    int start = mBuffer.position();
                    mBuffer.position(start + length);
                    return new String(mBuffer.array(), start, length, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    Log.wtf(TAG, "UTF-8 is not supported", e);
                    mLastWtf = e;
                    return null;
                }

            case LIST_TYPE:
                int length = mBuffer.get();
                if (length < 0) length += 256;  // treat as signed byte
                Object[] array = new Object[length];
                for (int i = 0; i < length; ++i) array[i] = decodeObject();
                return array;

            default:
                throw new IllegalArgumentException("Unknown entry type: " + type);
            }
        }

        private static @NonNull byte[] encodeObject(@Nullable Object object) {
            if (object == null) {
                return new byte[0];
            }
            if (object instanceof Integer) {
                return ByteBuffer.allocate(1 + 4)
                        .order(ByteOrder.nativeOrder())
                        .put(INT_TYPE)
                        .putInt((Integer) object)
                        .array();
            } else if (object instanceof Long) {
                return ByteBuffer.allocate(1 + 8)
                        .order(ByteOrder.nativeOrder())
                        .put(LONG_TYPE)
                        .putLong((Long) object)
                        .array();
            } else if (object instanceof Float) {
                return ByteBuffer.allocate(1 + 4)
                        .order(ByteOrder.nativeOrder())
                        .put(FLOAT_TYPE)
                        .putFloat((Float) object)
                        .array();
            } else if (object instanceof String) {
                String string = (String) object;
                byte[] bytes;
                try {
                    bytes = string.getBytes("UTF-8");
                } catch (UnsupportedEncodingException e) {
                    bytes = new byte[0];
                }
                return ByteBuffer.allocate(1 + 4 + bytes.length)
                         .order(ByteOrder.nativeOrder())
                         .put(STRING_TYPE)
                         .putInt(bytes.length)
                         .put(bytes)
                         .array();
            } else if (object instanceof Object[]) {
                Object[] objects = (Object[]) object;
                if (objects.length > 255) {
                    throw new IllegalArgumentException("Object array too long");
                }
                byte[][] bytes = new byte[objects.length][];
                int totalLength = 0;
                for (int i = 0; i < objects.length; i++) {
                    bytes[i] = encodeObject(objects[i]);
                    totalLength += bytes[i].length;
                }
                ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + totalLength)
                        .order(ByteOrder.nativeOrder())
                        .put(LIST_TYPE)
                        .put((byte) objects.length);
                for (int i = 0; i < objects.length; i++) {
                    buffer.put(bytes[i]);
                }
                return buffer.array();
            } else {
                throw new IllegalArgumentException("Unknown object type " + object);
            }
        }

        /** @hide */
        public static Event fromBytes(byte[] data) {
            return new Event(data);
        }

        /** @hide */
        public byte[] getBytes() {
            byte[] bytes = mBuffer.array();
            return Arrays.copyOf(bytes, bytes.length);
        }

        /**
         * Retreive the last WTF error generated by this object.
         * @hide
         */
        //VisibleForTesting
        public Exception getLastError() {
            return mLastWtf;
        }

        /**
         * Clear the error state for this object.
         * @hide
         */
        //VisibleForTesting
        public void clearError() {
            mLastWtf = null;
        }

        /**
         * @hide
         */
        @Override
        public boolean equals(Object o) {
            // Not using ByteBuffer.equals since it takes buffer position into account and we
            // always use absolute positions here.
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Event other = (Event) o;
            return Arrays.equals(mBuffer.array(), other.mBuffer.array());
        }

        /**
         * @hide
         */
        @Override
        public int hashCode() {
            // Not using ByteBuffer.hashCode since it takes buffer position into account and we
            // always use absolute positions here.
            return Arrays.hashCode(mBuffer.array());
        }
    }

    // We assume that the native methods deal with any concurrency issues.

    /**
     * Record an event log message.
     * @param tag The event type tag code
     * @param value A value to log
     * @return The number of bytes written
     */
    public static native int writeEvent(int tag, int value);

    /**
     * Record an event log message.
     * @param tag The event type tag code
     * @param value A value to log
     * @return The number of bytes written
     */
    public static native int writeEvent(int tag, long value);

    /**
     * Record an event log message.
     * @param tag The event type tag code
     * @param value A value to log
     * @return The number of bytes written
     */
    public static native int writeEvent(int tag, float value);

    /**
     * Record an event log message.
     * @param tag The event type tag code
     * @param str A value to log
     * @return The number of bytes written
     */
    public static native int writeEvent(int tag, String str);

    /**
     * Record an event log message.
     * @param tag The event type tag code
     * @param list A list of values to log
     * @return The number of bytes written
     */
    public static native int writeEvent(int tag, Object... list);

    /**
     * Read events from the log, filtered by type.
     * @param tags to search for
     * @param output container to add events into
     * @throws IOException if something goes wrong reading events
     */
    public static native void readEvents(int[] tags, Collection<Event> output)
            throws IOException;

    /**
     * Read events from the log, filtered by type, blocking until logs are about to be overwritten.
     * @param tags to search for
     * @param timestamp timestamp allow logs before this time to be overwritten.
     * @param output container to add events into
     * @throws IOException if something goes wrong reading events
     * @hide
     */
    @SystemApi
    public static native void readEventsOnWrapping(int[] tags, long timestamp,
            Collection<Event> output)
            throws IOException;

    /**
     * Get the name associated with an event type tag code.
     * @param tag code to look up
     * @return the name of the tag, or null if no tag has that number
     */
    public static String getTagName(int tag) {
        readTagsFile();
        return sTagNames.get(tag);
    }

    /**
     * Get the event type tag code associated with an event name.
     * @param name of event to look up
     * @return the tag code, or -1 if no tag has that name
     */
    public static int getTagCode(String name) {
        readTagsFile();
        Integer code = sTagCodes.get(name);
        return code != null ? code : -1;
    }

    /**
     * Read TAGS_FILE, populating sTagCodes and sTagNames, if not already done.
     */
    private static synchronized void readTagsFile() {
        if (sTagCodes != null && sTagNames != null) return;

        sTagCodes = new HashMap<String, Integer>();
        sTagNames = new HashMap<Integer, String>();

        Pattern comment = Pattern.compile(COMMENT_PATTERN);
        Pattern tag = Pattern.compile(TAG_PATTERN);
        BufferedReader reader = null;
        String line;

        try {
            reader = new BufferedReader(new FileReader(TAGS_FILE), 256);
            while ((line = reader.readLine()) != null) {
                if (comment.matcher(line).matches()) continue;

                Matcher m = tag.matcher(line);
                if (!m.matches()) {
                    Log.wtf(TAG, "Bad entry in " + TAGS_FILE + ": " + line);
                    continue;
                }

                try {
                    int num = Integer.parseInt(m.group(1));
                    String name = m.group(2);
                    sTagCodes.put(name, num);
                    sTagNames.put(num, name);
                } catch (NumberFormatException e) {
                    Log.wtf(TAG, "Error in " + TAGS_FILE + ": " + line, e);
                }
            }
        } catch (IOException e) {
            Log.wtf(TAG, "Error reading " + TAGS_FILE, e);
            // Leave the maps existing but unpopulated
        } finally {
            try { if (reader != null) reader.close(); } catch (IOException e) {}
        }
    }
}
