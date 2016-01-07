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

        // Layout of event log entry received from Android logger.
        //  see system/core/include/log/logger.h
        private static final int LENGTH_OFFSET = 0;
        private static final int HEADER_SIZE_OFFSET = 2;
        private static final int PROCESS_OFFSET = 4;
        private static final int THREAD_OFFSET = 8;
        private static final int SECONDS_OFFSET = 12;
        private static final int NANOSECONDS_OFFSET = 16;

        // Layout for event log v1 format, v2 and v3 use HEADER_SIZE_OFFSET
        private static final int V1_PAYLOAD_START = 20;
        private static final int DATA_OFFSET = 4;

        // Value types
        private static final byte INT_TYPE    = 0;
        private static final byte LONG_TYPE   = 1;
        private static final byte STRING_TYPE = 2;
        private static final byte LIST_TYPE   = 3;
        private static final byte FLOAT_TYPE = 4;

        /** @param data containing event, read from the system */
        /*package*/ Event(byte[] data) {
            mBuffer = ByteBuffer.wrap(data);
            mBuffer.order(ByteOrder.nativeOrder());
        }

        /** @return the process ID which wrote the log entry */
        public int getProcessId() {
            return mBuffer.getInt(PROCESS_OFFSET);
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
            int offset = mBuffer.getShort(HEADER_SIZE_OFFSET);
            if (offset == 0) {
                offset = V1_PAYLOAD_START;
            }
            return mBuffer.getInt(offset);
        }

        /** @return one of Integer, Long, Float, String, null, or Object[] of same. */
        public synchronized Object getData() {
            try {
                int offset = mBuffer.getShort(HEADER_SIZE_OFFSET);
                if (offset == 0) {
                    offset = V1_PAYLOAD_START;
                }
                mBuffer.limit(offset + mBuffer.getShort(LENGTH_OFFSET));
                mBuffer.position(offset + DATA_OFFSET); // Just after the tag.
                return decodeObject();
            } catch (IllegalArgumentException e) {
                Log.wtf(TAG, "Illegal entry payload: tag=" + getTag(), e);
                return null;
            } catch (BufferUnderflowException e) {
                Log.wtf(TAG, "Truncated entry payload: tag=" + getTag(), e);
                return null;
            }
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

        /** @hide */
        public static Event fromBytes(byte[] data) {
            return new Event(data);
        }

        /** @hide */
        public byte[] getBytes() {
            byte[] bytes = mBuffer.array();
            return Arrays.copyOf(bytes, bytes.length);
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
