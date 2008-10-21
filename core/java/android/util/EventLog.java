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

import com.google.android.collect.Lists;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@hide}
 * Dynamically defined (in terms of event types), space efficient (i.e. "tight") event logging
 * to help instrument code for large scale stability and performance monitoring.
 *
 * Note that this class contains all static methods.  This is done for efficiency reasons.
 *
 * Events for the event log are self-describing binary data structures.  They start with a 20 byte
 * header (generated automatically) which contains all of the following in order:
 *
 * <ul>
 * <li> Payload length: 2 bytes - length of the non-header portion </li>
 * <li> Padding: 2 bytes - no meaning at this time </li>
 * <li> Timestamp:
 *   <ul>
 *   <li> Seconds: 4 bytes - seconds since Epoch </li>
 *   <li> Nanoseconds: 4 bytes - plus extra nanoseconds </li>
 *   </ul></li>
 * <li> Process ID: 4 bytes - matching {@link android.os.Process#myPid} </li>
 * <li> Thread ID: 4 bytes - matching {@link android.os.Process#myTid} </li>
 * </li>
 * </ul>
 *
 * The above is followed by a payload, comprised of the following:
 * <ul>
 * <li> Tag: 4 bytes - unique integer used to identify a particular event.  This number is also
 *                     used as a key to map to a string that can be displayed by log reading tools.
 * </li>
 * <li> Type: 1 byte - can be either {@link #INT}, {@link #LONG}, {@link #STRING},
 *                     or {@link #LIST}. </li>
 * <li> Event log value: the size and format of which is one of:
 *   <ul>
 *   <li> INT: 4 bytes </li>
 *   <li> LONG: 8 bytes </li>
 *   <li> STRING:
 *     <ul>
 *     <li> Size of STRING: 4 bytes </li>
 *     <li> The string:  n bytes as specified in the size fields above. </li>
 *     </ul></li>
 *   <li> {@link List LIST}:
 *     <ul>
 *     <li> Num items: 1 byte </li>
 *     <li> N value payloads, where N is the number of items specified above. </li>
 *     </ul></li>
 *   </ul>
 * </li>
 * <li> '\n': 1 byte - an automatically generated newline, used to help detect and recover from log
 *                     corruption and enable stansard unix tools like grep, tail and wc to operate
 *                     on event logs. </li>
 * </ul>
 *
 * Note that all output is done in the endian-ness of the device (as determined
 * by {@link ByteOrder#nativeOrder()}).
 */

public class EventLog {

    // Value types
    public static final byte INT    = 0;
    public static final byte LONG   = 1;
    public static final byte STRING = 2;
    public static final byte LIST   = 3;

    /**
     * An immutable tuple used to log a heterogeneous set of loggable items.
     * The items can be Integer, Long, String, or {@link List}.
     * The maximum number of items is 127
     */
    public static final class List {
        private Object[] mItems;

        /**
         * Get a particular tuple item
         * @param pos The position of the item in the tuple
         */
        public final Object getItem(int pos) {
            return mItems[pos];
        }

        /**
         * Get the number of items in the tuple.
         */
        public final byte getNumItems() {
            return (byte) mItems.length;
        }

        /**
         * Create a new tuple.
         * @param items The items to create the tuple with, as varargs.
         * @throws IllegalArgumentException if the arguments are too few (0),
         *         too many, or aren't loggable types.
         */
        public List(Object... items) throws IllegalArgumentException {
            if (items.length > Byte.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "A List must have fewer than "
                        + Byte.MAX_VALUE + " items in it.");
            }
            if (items.length < 1) {
                throw new IllegalArgumentException(
                        "A List must have at least one item in it.");
            }
            for (int i = 0; i < items.length; i++) {
                final Object item = items[i];
                if (item == null) {
                    // Would be nice to be able to write null strings...
                    items[i] = "";
                } else if (!(item instanceof List ||
                      item instanceof String ||
                      item instanceof Integer ||
                      item instanceof Long)) {
                    throw new IllegalArgumentException(
                            "Attempt to create a List with illegal item type.");
                }
            }
            this.mItems = items;
        }
    }

    /**
     * A previously logged event read from the logs.
     */
    public static final class Event {
        private final ByteBuffer mBuffer;

        // Layout of event log entry received from kernel.
        private static final int LENGTH_OFFSET = 0;
        private static final int PROCESS_OFFSET = 4;
        private static final int THREAD_OFFSET = 8;
        private static final int SECONDS_OFFSET = 12;
        private static final int NANOSECONDS_OFFSET = 16;

        private static final int PAYLOAD_START = 20;
        private static final int TAG_OFFSET = 20;
        private static final int DATA_START = 24;

        /** @param data containing event, read from the system */
        public Event(byte[] data) {
            mBuffer = ByteBuffer.wrap(data);
            mBuffer.order(ByteOrder.nativeOrder());
        }

        public int getProcessId() {
            return mBuffer.getInt(PROCESS_OFFSET);
        }

        public int getThreadId() {
            return mBuffer.getInt(THREAD_OFFSET);
        }

        public long getTimeNanos() {
            return mBuffer.getInt(SECONDS_OFFSET) * 1000000000l
                    + mBuffer.getInt(NANOSECONDS_OFFSET);
        }

        public int getTag() {
            return mBuffer.getInt(TAG_OFFSET);
        }

        /** @return one of Integer, Long, String, or List. */
        public synchronized Object getData() {
            mBuffer.limit(PAYLOAD_START + mBuffer.getShort(LENGTH_OFFSET));
            mBuffer.position(DATA_START);  // Just after the tag.
            return decodeObject();
        }

        /** @return the loggable item at the current position in mBuffer. */
        private Object decodeObject() {
            if (mBuffer.remaining() < 1) return null;
            switch (mBuffer.get()) {
            case INT:
                if (mBuffer.remaining() < 4) return null;
                return mBuffer.getInt();

            case LONG:
                if (mBuffer.remaining() < 8) return null;
                return mBuffer.getLong();

            case STRING:
                try {
                    if (mBuffer.remaining() < 4) return null;
                    int length = mBuffer.getInt();
                    if (length < 0 || mBuffer.remaining() < length) return null;
                    int start = mBuffer.position();
                    mBuffer.position(start + length);
                    return new String(mBuffer.array(), start, length, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);  // UTF-8 is guaranteed.
                }

            case LIST:
                if (mBuffer.remaining() < 1) return null;
                int length = mBuffer.get();
                if (length <= 0) return null;
                Object[] array = new Object[length];
                for (int i = 0; i < length; ++i) {
                    array[i] = decodeObject();
                    if (array[i] == null) return null;
                }
                return new List(array);

            default:
                return null;
            }
        }
    }

    // We assume that the native methods deal with any concurrency issues.

    /**
     * Send an event log message.
     * @param tag An event identifer
     * @param value A value to log
     * @return The number of bytes written
     */
    public static native int writeEvent(int tag, int value);

    /**
     * Send an event log message.
     * @param tag An event identifer
     * @param value A value to log
     * @return The number of bytes written
     */
    public static native int writeEvent(int tag, long value);

    /**
     * Send an event log message.
     * @param tag An event identifer
     * @param str A value to log
     * @return The number of bytes written
     */
    public static native int writeEvent(int tag, String str);

    /**
     * Send an event log message.
     * @param tag An event identifer
     * @param list A {@link List} to log
     * @return The number of bytes written
     */
    public static native int writeEvent(int tag, List list);

    /**
     * Send an event log message.
     * @param tag An event identifer
     * @param list A list of values to log
     * @return The number of bytes written
     */
    public static int writeEvent(int tag, Object... list) {
        return writeEvent(tag, new List(list));
    }

    /**
     * Read events from the log, filtered by type.
     * @param tags to search for
     * @param output container to add events into
     * @throws IOException if something goes wrong reading events
     */
    public static native void readEvents(int[] tags, Collection<Event> output)
            throws IOException;
}
